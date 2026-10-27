import { impactEnvelope } from './touch-reflex.mjs'

/**
 * Dedicated 小狼 rig profile. The signature is taken from the user's
 * supplied CDI3/physics3/VTuber mapping, not a guessed name or model URL.
 * No private model asset/moc3 is bundled into the public lulu1 repository.
 * Only activate if the actual Cubism Core owns all signature channels.
 */
export const WOLF_RIG_IDS = Object.freeze([
  'ParamCheekPuff', 'ParamJawOpen', 'ParamAngleZ2',
  'body_gensui_z', 'ParamMouthPuckerWiden', 'ParamBrowLForm',
])
export function isWolfRig(core, has) {
  return !!core && typeof has === 'function' &&
    WOLF_RIG_IDS.every(id => has(core, id))
}
export const WOLF_MOUTH = Object.freeze({
  attackMs: 48, releaseMs: 125, restMs: 185,
  jawGain: .82, jawAttackMs: 95, jawReleaseMs: 195,
  widthGain: 12, funnelGain: .075, shrugGain: .065,
  fallbackBase: .24, fallbackBeat: .4,
})
const clamp01 = v => Math.max(0, Math.min(1, Number.isFinite(v) ? v : 0))
/**
 * Refine the existing locally-authorized tactile response; do not create an
 * emotional decision. Head Z + delayed torso Z are this specific rig's main
 * tilt/deformation inputs. Physics3 reacts to them naturally.
 */
export function enhanceWolfTouch(base, region, gesture, state = {}, elapsedMs = 0) {
  if (!base || !region || !gesture) return base
  const pose = { ...base, body: { ...base.body },
    face: { ...base.face }, parameters: { ...base.parameters } }
  const force = clamp01(state.force ?? .55)
  const right = /-right$/.test(gesture)
  const left = /-left$/.test(gesture)
  const side = region.endsWith('-left') ? -1 :
    region.endsWith('-right') ? 1 : 0
  const travel = right ? 1 : left ? -1 : side || 1
  const family = region.replace(/-(?:left|right)$/, '')
  if (/^slap(?:-(?:left|right))?$/.test(gesture)) {
    // Spend this rig's real ±30 neck range across the three force settings.
    // Yaw supplies visible side displacement; Z alone only tips the chin.
    // Keep the signed return so the body and head share the spring recovery.
    const envelope = impactEnvelope(elapsedMs)
    // A stationary slap follows the contacted cheek, as the base reflex does.
    const direction = right || left ? travel :
      (family === 'cheek' && side !== 0 ? side : -side || -1)
    if (family === 'cheek' || family === 'mouth') {
      // The supplied wolf model has neck ±30°, torso ±10°. Keep most motion
      // in the neck, not in the screen-space sprite or its whole-body mesh.
      // Short stationary taps are smaller than a directional swipe.
      const contactGain = right || left ? 1 : .58
      pose.roll = direction * (11 + 15 * force) * envelope * contactGain
      pose.yaw = direction * (2 + 5 * force) * envelope * contactGain
      pose.body.roll = direction * (1 + 2.6 * force) * envelope *
        (.12 + .88 * Math.min(1, Math.max(0, elapsedMs - 100) / 380)) * contactGain
      pose.body.yaw = direction * (.4 + 1.1 * force) * envelope * contactGain
    }
    if (family === 'cheek') {
      pose.face.cheekPuff = elapsedMs < 230 ? .05 : .2 + force * .12
      pose.face.mouthX = direction * (5 + 3 * force) * envelope
      pose.face[side < 0 ? 'browLeftY' : 'browRightY'] =
        elapsedMs < 230 ? .52 : .32
    }
  } else if ((family === 'hair' || family === 'forehead' ||
      family === 'ear' || family === 'beast-ear') &&
      (/^stroke/.test(gesture) || gesture === 'hold')) {
    // Most motion is in neck Z, rather than yawing the entire chin across
    // the image. The torso's subtle follow is already owned by touch-reflex.
    pose.roll = travel * (10 + force * 4)
    pose.yaw *= .24
    pose.body.roll *= .55
    if (family === 'ear' || family === 'beast-ear')
      pose.face[side < 0 ? 'browLeftY' : 'browRightY'] = .22 + .13 * force
  } else if (family === 'cheek' && gesture !== 'kiss') {
    pose.face.cheekPuff = Math.max(pose.face.cheekPuff ?? 0, .3 + force * .32)
    pose.face.pout = Math.max(pose.face.pout ?? 0, .13)
    pose.face.mouthX = (side || travel) * (4.5 + 3 * force)
    pose.roll += (side || travel) * 2.5
  } else if (family === 'nose' && gesture !== 'kiss') {
    pose.face.cheekPuff = Math.max(.12, pose.face.cheekPuff ?? 0)
    pose.face.browLeftY = .23
  } else if (family === 'mouth' && gesture !== 'kiss') {
    pose.face.cheekPuff = Math.max(.25, pose.face.cheekPuff ?? 0)
    pose.face.pout = Math.max(.25, pose.face.pout ?? 0)
    if (right || left) pose.face.mouthX = travel * (2.5 + 2 * force)
  }
  // Physics3 owns these ear OUTPUTS; never override their simulation directly.
  delete pose.parameters.se_wuli_x_l
  delete pose.parameters.se_wuli_x_r
  return pose
}


/**
 * Screen-space torso recoil for the owner's 小狼 rig only.
 *
 * The authored torso rotation reaches only roughly ±10° in its VTube rig
 * mapping. Rotating the model display around its existing mid-body anchor
 * supplements (never replaces) the real Cubism head/torso/physics motion.
 * No other imported model gets this profile. Keep it in the renderer so
 * a 15fps Android mirrored frame sees a meaningful swing without a second
 * touch or animation loop.
 */
export function createWolfImpactPresentation() {
  let angle = 0
  function update(model, pose, deltaMs) {
    if (!model) return
    const roll = pose?.impactMotion && Number.isFinite(pose.roll) ? pose.roll : 0
    const torso = pose?.impactMotion && Number.isFinite(pose.body?.roll)
      ? pose.body.roll : 0
    const direction = Math.sign(roll || torso)
    // Only a near-imperceptible display stabilizer remains; the actual
    // visible slap is driven by head Cubism joints. The old 0.32-radian
    // full-avatar tilt made chest taps and cheek swipes look like a fall.
    const target = pose?.impactFace && direction
      ? direction * Math.min(.012, Math.abs(roll) / 30 * .012) : 0
    const dt = Math.max(0, Math.min(60, Number.isFinite(deltaMs) ? deltaMs : 0))
    // A quick impact and a slower, soft return. The touch envelope already
    // contains one small reverse overshoot; preserve it rather than snapping.
    const attacking = Math.abs(target) > Math.abs(angle) &&
      Math.sign(target) === Math.sign(angle || target)
    const tau = attacking ? 50 : 260
    angle += (target - angle) * (1 - Math.exp(-dt / tau))
    if (!target && Math.abs(angle) < .00025) angle = 0
    model.rotation = angle
  }
  function reset(model) {
    angle = 0
    if (model) model.rotation = 0
  }
  return { update, reset }
}
