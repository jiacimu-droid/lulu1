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
  // Independent brow actuators are present in the supplied CDI3 rig.
  browLeftY: ['ParamBrowLY'], browRightY: ['ParamBrowRY'],
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
  // Enter a selected pose promptly, relax out of it without a fast snap.
  const headSpring = createPoseSpring(['yaw', 'pitch', 'roll'], 95, 1.06, 25)
  const bodySpring = createPoseSpring(['yaw', 'pitch', 'roll'], 72, 1.1, 23)
  const touchSpring = createPoseSpring(['yaw', 'pitch', 'roll'], 135, 1.02, 32)
  const touchBodySpring = createPoseSpring(['yaw', 'pitch', 'roll'], 105, 1.08, 26)
  const smoothedFace = Object.create(null)
  let secondaryHeadMix = 0
  let secondaryBodyMix = 0
  let headHandoff = 0
  let bodyHandoff = 0

  function reset() {
    headSpring.reset()
    bodySpring.reset()
    touchSpring.reset()
    touchBodySpring.reset()
    secondaryHeadMix = 0
    secondaryBodyMix = 0
    headHandoff = 0
    bodyHandoff = 0
    for (const key of Object.keys(smoothedFace)) delete smoothedFace[key]
  }

  function update(core, { deltaMs, head, body, face, touchReflex = null, physical = null, speaking, authoredMotion = false, expressionActive = false, voiceLevel = null, seconds }) {
    if (!core) return
    // Breathing alone looks frozen in many rigs. A few sub-degree slow
    // movements keep the body alive without ever pulling eyes off camera.
    // Touch and the character's own explicit pose take precedence.
    const speechEnergy = speaking ? (.4 + .6 * clamp01(
      Number.isFinite(voiceLevel) ? voiceLevel : .45)) : 0
    const idleHead = !head && !touchReflex ? {
      // Head Z is a chosen neck tilt, NOT a permanent sine-wave wobble.
      // Keep the chin stable while ordinary speech animates the mouth.
      yaw: 0,
      pitch: .18 * Math.sin(seconds * .23 + .8) +
        speechEnergy * .18 * Math.sin(seconds * .47),
      roll: 0,
    } : null
    const idleBody = !body && !touchReflex ? {
      yaw: .06 * Math.sin(seconds * .19 + .5),
      pitch: .23 * Math.sin(seconds * .23) +
        speechEnergy * .18 * Math.sin(seconds * .47),
      roll: .09 * Math.sin(seconds * .17 + .9),
    } : null
    // Authored motion files can contain head/torso movement as well as arms.
    // Sample their real pose instead of overwriting it with our resting pose.
    const authoredHead = authoredMotion ? Object.fromEntries(MAIN_HEAD.map(([id, axis]) =>
      [axis, read(core, id) ?? 0])) : null
    const authoredBody = authoredMotion ? Object.fromEntries(Object.entries(BODY_BINDINGS).map(([axis, id]) =>
      [axis, read(core, id) ?? 0])) : null
    const headPose = headSpring.step(head || authoredHead || idleHead, deltaMs,
      !head && !authoredMotion)
    const bodyPose = bodySpring.step(body || authoredBody || idleBody, deltaMs,
      !body && !authoredMotion)
    // Soft stroking gets a gentle spring; a palm hit needs a sharp visible
    // attack at the neck, then a long recovery. A single slow spring for both
    // formerly swallowed the entire 200ms impact peak.
    const reflexPose = touchSpring.step(touchReflex, deltaMs, !touchReflex,
      touchReflex?.impactMotion ? 780 : null)
    const bodyReflex = touchBodySpring.step(touchReflex?.body, deltaMs, !touchReflex,
      touchReflex?.impactMotion ? 230 : null)
    // The original Motion3 still updates underneath a short touch or voice cue.
    // Don't hand it control in one frame when that override expires.
    if (authoredMotion) {
      headHandoff = head || touchReflex ? 1 : Math.max(0, headHandoff - deltaMs / 1050)
      bodyHandoff = body || touchReflex ? 1 : Math.max(0, bodyHandoff - deltaMs / 1050)
    } else {
      headHandoff = 0
      bodyHandoff = 0
    }
    const eased = t => t * t * (3 - 2 * t)
    const headMix = !authoredMotion || head || touchReflex ? 1 : eased(headHandoff)
    const bodyMix = !authoredMotion || body || touchReflex ? 1 : eased(bodyHandoff)
    for (const [id, axis] of MAIN_HEAD) {
      if (!has(core, id) || (authoredMotion && !head && !touchReflex && headHandoff === 0)) continue
      const desired = headPose[axis] + (reflexPose[axis] || 0)
      write(core, id, (authoredHead?.[axis] ?? 0) * (1 - headMix) + desired * headMix)
    }
    for (const [axis, id] of Object.entries(BODY_BINDINGS)) {
      if (!has(core, id) || (authoredMotion && !body && !touchReflex && bodyHandoff === 0)) continue
      const desired = bodyPose[axis] + bodyReflex[axis]
      write(core, id, (authoredBody?.[axis] ?? 0) * (1 - bodyMix) + desired * bodyMix)
    }

    // Physics follow-through on a neutral head/torso causes the user's
    // unwanted wandering. Suppress just those bones, not ears or hair.
    // Follow-through fades gently instead of disappearing as soon as the
    // pointer is released. Rise stays fast; return is deliberately slower.
    const headActive = !!(head || authoredMotion || touchReflex)
    const bodyActive = !!(body || authoredMotion || touchReflex)
    const headStep = 1 - Math.exp(-Math.max(0, deltaMs) / (headActive ? 120 : 650))
    const bodyStep = 1 - Math.exp(-Math.max(0, deltaMs) / (bodyActive ? 120 : 700))
    secondaryHeadMix += ((headActive ? 1 : 0) - secondaryHeadMix) * headStep
    secondaryBodyMix += ((bodyActive ? 1 : 0) - secondaryBodyMix) * bodyStep
    if (!headActive && secondaryHeadMix < .008) secondaryHeadMix = 0
    if (!bodyActive && secondaryBodyMix < .008) secondaryBodyMix = 0
    // Secondary axes are authored physics outputs, not a per-frame additive
    // accumulator. Gate them on during touch; never add our own previous
    // result back each frame or the angles would grow without bound.
    for (const id of SECONDARY_HEAD) {
      if (has(core, id)) write(core, id, (read(core, id) ?? 0) * secondaryHeadMix)
    }
    for (const id of SECONDARY_BODY) {
      if (has(core, id)) write(core, id, (read(core, id) ?? 0) * secondaryBodyMix)
    }

    for (const [key, ids] of Object.entries(FACE_BINDINGS)) {
      if (ids.some(id => !has(core, id))) continue
      // Speech amplitude articulates a selected smile; speech alone never
      // assigns a happy face to a serious, sad or otherwise neutral reply.
      // Only measured PCM emphasis gets a restrained one-brow lift.
      // Never infer "happy" from sound, override chosen expressions, or
      // impose one personality's mannerisms on every imported model.
      const emphasis = speaking && Number.isFinite(voiceLevel) &&
        !touchReflex && !authoredMotion && !expressionActive &&
        face?.browY == null && face?.browLeftY == null &&
        face?.browRightY == null && voiceLevel > .55
        ? Math.min(.16, (voiceLevel - .55) * .36) *
          Math.max(0, Math.sin(seconds * 2.1)) : 0
      const speechBrow = key === 'browLeftY' ? emphasis :
        key === 'browRightY' ? emphasis * .35 : null
      const requested = touchReflex?.face?.[key] ?? face?.[key] ??
        (speechBrow != null && speechBrow > .008 ? speechBrow : null)
      const eyeContact = key === 'gazeX' || key === 'gazeY'
      if (authoredMotion && requested == null) {
        delete smoothedFace[key]
        continue
      }
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
        // Single-eye contact closes just that eyelid. Keep a single owner per
        // parameter so the bilateral release cannot flatten an authored wink.
        const sideKey = key === 'eyeOpen' ? (i === 0 ? 'eyeLeftOpen' : 'eyeRightOpen') : null
        const sideTarget = sideKey ? touchReflex?.face?.[sideKey] ?? face?.[sideKey] : null
        const goal = sideTarget ?? target ?? (eyeContact ? 0 : state.release[i])
        state.values[i] += (goal - state.values[i]) * blend
        if (Math.abs(state.values[i] - goal) > .015) released = false
        else if (requested == null) state.values[i] = goal
        write(core, id, state.values[i])
      })
      if (released) delete smoothedFace[key]
    }
    // These optional physics channels exist in the supplied wolf CDI3.
    // An absent channel is skipped; appearance toggles are never touched.
    for (const [id, value] of Object.entries(touchReflex?.parameters || {})) {
      if (has(core, id) && Number.isFinite(value)) write(core, id, value)
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
