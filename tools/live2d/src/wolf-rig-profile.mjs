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
    // Sharp neck tilt; the already delayed torso spring follows later.
    // Do not amplify a decaying pose by a constant: preserve recoil release.
    const envelope = Math.abs(base.roll) / 30
    pose.roll = Math.sign(base.roll || travel) *
      Math.min(30, Math.max(Math.abs(base.roll), (26 + 4 * force) * envelope))
    pose.yaw *= .5
    pose.body.roll = travel * (8 + 4 * force) * envelope *
      (.18 + .82 * Math.min(1, Math.max(0, elapsedMs) / 240))
    pose.body.yaw = travel * 1.4 * envelope
    if (family === 'cheek') {
      pose.face.cheekPuff = elapsedMs < 230 ? .05 : .2 + force * .12
      pose.face.mouthX = travel * (5 + 3 * force) * envelope
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
