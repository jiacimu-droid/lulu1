import { createPoseSpring } from './pose-spring.mjs'

/**
 * AIRI-inspired final-stage Cubism actuator. Only the character selects
 * gestures. The renderer owns camera contact, stable resting posture and
 * small physical reflexes; never manufactures emotional intentions.
 *
 * Called AFTER authored motions, expressions, focus, breathing and physics,
 * BEFORE drawable model.update. This ownership ordering is essential.
 */
export const FACE_BINDINGS = Object.freeze({
  gazeX: ['ParamEyeBallX'], gazeY: ['ParamEyeBallY'],
  smile: ['ParamMouthForm'],
  eyeSmile: ['ParamEyeLSmile', 'ParamEyeRSmile'],
  eyeOpen: ['ParamEyeLOpen', 'ParamEyeROpen'],
  browX: ['ParamBrowLX', 'ParamBrowRX'],
  browY: ['ParamBrowLY', 'ParamBrowRY'],
  browAngle: ['ParamBrowLAngle', 'ParamBrowRAngle'],
  browForm: ['ParamBrowLForm', 'ParamBrowRForm'],
  squint: ['ParamEyeLSquint', 'ParamEyeRSquint'],
  cheekPuff: ['ParamCheekPuff'], pout: ['ParamMouthFunnel'],
})
export const BODY_BINDINGS = Object.freeze({
  yaw: 'ParamBodyAngleX', pitch: 'ParamBodyAngleY', roll: 'ParamBodyAngleZ',
})
// The names below are inspected against the *actual* Cubism Core parameter
// IDs. A different rig lacking them simply skips them.
const SECONDARY_HEAD = ['ParamAngleX2', 'ParamAngleY2', 'ParamAngleZ2']
const SECONDARY_BODY = [
  'body_gensui_x', 'body_gensui_y', 'body_gensui_y2',
  'body_gensui_y3', 'body_gensui_z',
]
const MAIN_HEAD = [
  ['ParamAngleX', 'yaw'], ['ParamAngleY', 'pitch'], ['ParamAngleZ', 'roll'],
]
const clamp01 = v => Math.min(1, Math.max(0, v))

export function createVisualLayerRuntime({ has, read, write }) {
  const headSpring = createPoseSpring(['yaw', 'pitch', 'roll'], 95, 1.06)
  const bodySpring = createPoseSpring(['yaw', 'pitch', 'roll'], 72, 1.1)
  const touchSpring = createPoseSpring(['yaw', 'pitch', 'roll'], 135, 1.1)
  const touchBodySpring = createPoseSpring(['yaw', 'pitch', 'roll'], 105, 1.08)
  const smoothedFace = Object.create(null)
  let secondaryHeadMix = 0
  let secondaryBodyMix = 0

  function reset() {
    headSpring.reset()
    bodySpring.reset()
    touchSpring.reset()
    touchBodySpring.reset()
    secondaryHeadMix = 0
    secondaryBodyMix = 0
    for (const key of Object.keys(smoothedFace)) delete smoothedFace[key]
  }

  function update(core, { deltaMs, head, body, face, touchReflex = null, physical = null, speaking, voiceLevel = null, seconds }) {
    if (!core) return
    // Breathing alone looks frozen in many rigs. A few sub-degree slow
    // movements keep the body alive without ever pulling eyes off camera.
    // Touch and the character's own explicit pose take precedence.
    const idleHead = !head && !touchReflex ? {
      yaw: .46 * Math.sin(seconds * .37),
      pitch: .3 * Math.sin(seconds * .29 + .8),
      roll: .34 * Math.sin(seconds * .21 + .3),
    } : null
    const idleBody = !body && !touchReflex ? {
      yaw: .5 * Math.sin(seconds * .19 + .5),
      pitch: .28 * Math.sin(seconds * .23),
      roll: .35 * Math.sin(seconds * .17 + .9),
    } : null
    const headPose = headSpring.step(head || idleHead, deltaMs)
    const bodyPose = bodySpring.step(body || idleBody, deltaMs)
    const reflexPose = touchSpring.step(touchReflex, deltaMs)
    const bodyReflex = touchBodySpring.step(touchReflex?.body, deltaMs)
    for (const [id, axis] of MAIN_HEAD) {
      if (has(core, id)) write(core, id, headPose[axis] + (reflexPose[axis] || 0))
    }
    for (const [axis, id] of Object.entries(BODY_BINDINGS)) {
      if (has(core, id)) write(core, id, bodyPose[axis] + bodyReflex[axis])
    }

    // Physics follow-through on a neutral head/torso causes the user's
    // unwanted wandering. Suppress just those bones, not ears or hair.
    const mixStep = Math.min(1, Math.max(0, deltaMs) * .014)
    secondaryHeadMix += ((head ? 1 : 0) - secondaryHeadMix) * mixStep
    secondaryBodyMix += ((body ? 1 : 0) - secondaryBodyMix) * mixStep
    if (!head && secondaryHeadMix < .008) secondaryHeadMix = 0
    if (!body && secondaryBodyMix < .008) secondaryBodyMix = 0
    for (const id of SECONDARY_HEAD) {
      if (has(core, id)) write(core, id, (read(core, id) ?? 0) * secondaryHeadMix)
    }
    for (const id of SECONDARY_BODY) {
      if (has(core, id)) write(core, id, (read(core, id) ?? 0) * secondaryBodyMix)
    }

    for (const [key, ids] of Object.entries(FACE_BINDINGS)) {
      if (ids.some(id => !has(core, id))) continue
      const requested = face?.[key] ?? touchReflex?.face?.[key]
      const eyeContact = key === 'gazeX' || key === 'gazeY'
      let state = smoothedFace[key]
      if (!eyeContact && requested == null && !state) continue
      const resting = key === 'eyeOpen' ? 1 : 0
      if (!state) {
        const bases = ids.map(id => read(core, id) ?? resting)
        state = { values: [...bases], bases, release: null }
        smoothedFace[key] = state
      }
      // Cubism authored motions and expressions ran before this final layer.
      // After our temporary cue, restore the actual imported model's pose.
      if (requested == null && !eyeContact && !state.release) {
        state.release = ids.map((id, i) => {
          const authored = read(core, id)
          return Number.isFinite(authored) &&
            Math.abs(authored - state.values[i]) > .06
            ? authored : state.bases[i]
        })
      }
      if (requested != null) state.release = null
      const energy = Number.isFinite(voiceLevel) ? clamp01(voiceLevel) : 1
      const voiced = speaking && requested != null &&
        (key === 'smile' || key === 'eyeSmile')
      // Small PCM-led articulation; no arbitrary sine-wave facial twitch.
      const target = voiced ? requested * (.94 + .06 * energy) : requested
      const blend = 1 - Math.exp(-Math.max(0, deltaMs) * .012)
      let released = requested == null && !eyeContact
      ids.forEach((id, i) => {
        const goal = target ?? (eyeContact ? 0 : state.release[i])
        state.values[i] += (goal - state.values[i]) * blend
        if (Math.abs(state.values[i] - goal) > .015) released = false
        else if (requested == null) state.values[i] = goal
        write(core, id, state.values[i])
      })
      if (released) delete smoothedFace[key]
    }
    // Unlike head yaw oscillations, the dedicated breathing rig produces
    // subtle life without destroying eye contact. Keep it on its own channel.
    if (has(core, 'ParamBreath')) {
      const stamina = Number.isFinite(physical?.energy)
        ? clamp01(physical.energy / 100) : .78
      const fatigue = Number.isFinite(physical?.sleepPressure)
        ? clamp01(physical.sleepPressure / 100) : .22
      // A mild physical tempo, not synthetic emotions or a forced gesture.
      const rate = 1.13 + .34 * stamina - .22 * fatigue
      write(core, 'ParamBreath', .5 + .14 * Math.sin(seconds * rate))
    }
  }

  return { update, reset }
}
