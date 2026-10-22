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
  const smoothedFace = Object.create(null)
  let secondaryHeadMix = 0
  let secondaryBodyMix = 0

  function reset() {
    headSpring.reset()
    bodySpring.reset()
    secondaryHeadMix = 0
    secondaryBodyMix = 0
    for (const key of Object.keys(smoothedFace)) delete smoothedFace[key]
  }

  function update(core, { deltaMs, head, body, face, speaking, seconds }) {
    if (!core) return
    const headPose = headSpring.step(head, deltaMs)
    const bodyPose = bodySpring.step(body, deltaMs)
    for (const [id, axis] of MAIN_HEAD) {
      if (has(core, id)) write(core, id, headPose[axis])
    }
    for (const [axis, id] of Object.entries(BODY_BINDINGS)) {
      if (has(core, id)) write(core, id, bodyPose[axis])
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
      const requested = face?.[key]
      const eyeContact = key === 'gazeX' || key === 'gazeY'
      const resting = key === 'eyeOpen' ? 1 : 0
      const prior = Object.prototype.hasOwnProperty.call(smoothedFace, key)
      // Expressions own their parameters while there is no AI face cue.
      // Camera gaze is the exception: always override idle gaze drift.
      if (!eyeContact && requested == null && !prior) continue
      const from = prior ? smoothedFace[key] :
        key === 'eyeOpen' ? (read(core, ids[0]) ?? 1) : 0
      const voiced = speaking && requested != null &&
        (key === 'smile' || key === 'eyeSmile' || key === 'squint')
      const proposed = voiced
        ? requested * (.94 + .06 * Math.sin(seconds * 7.5))
        : requested
      const target = proposed ?? resting
      let next = from + (target - from) * clamp01(deltaMs * .012)
      if (requested == null && Math.abs(next - resting) < .015) {
        next = resting
        delete smoothedFace[key]
      } else {
        smoothedFace[key] = next
      }
      for (const id of ids) write(core, id, next)
    }

    // Unlike head yaw oscillations, the dedicated breathing rig produces
    // subtle life without destroying eye contact. Keep it on its own channel.
    if (has(core, 'ParamBreath')) {
      write(core, 'ParamBreath', .5 + .14 * Math.sin(seconds * 1.35))
    }
  }

  return { update, reset }
}
