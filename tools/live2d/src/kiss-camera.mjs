// Renderer-only framing and facial score for a successfully started wolf
// kiss Motion3. No new character intent, audio or independent scheduler.
export const WOLF_KISS_MOTION_INDEX = 48
// The original 3.35s Motion3 starts the action. A renderer-only recovery tail
// lets the lips soften and the camera retreat without racing the next TTS part.
export const KISS_CAMERA_DURATION_MS = 4450
export const KISS_CAMERA_PEAK_ZOOM = 2.26

/**
 * The wolf's lips are slightly right of its mesh pivot. Prefer the
 * existing per-model mouth touch calibration, when available. Otherwise use
 * the observed wolf portrait's small default offset (not a global camera
 * bias). Keep the anchor in model-local units so a resize preserves it.
 */
export function kissMouthLocalOffsetX(base, bounds, calibratedMouth) {
  if (!base || !Number.isFinite(base.x) || !Number.isFinite(base.width) ||
      !Number.isFinite(base.scale) || base.scale <= 0 || base.width <= 0)
    return 0
  const fallback = base.width * .035 / base.scale
  if (!Number.isFinite(calibratedMouth?.u) ||
      !Number.isFinite(bounds?.x) || !Number.isFinite(bounds?.width) ||
      bounds.width <= 0 || calibratedMouth.u < 0 || calibratedMouth.u > 1)
    return fallback
  const offset = bounds.x + calibratedMouth.u * bounds.width - base.x
  return Number.isFinite(offset) && Math.abs(offset) < base.width * .6
    ? offset / base.scale : fallback
}

/**
 * Center the lips as the wolf approaches the glass instead of magnifying
 * their offset from the torso pivot. At zoom=1 the ordinary framing stays
 * unchanged; the exact same curve reverses smoothly during withdrawal.
 */
export function kissCameraHorizontalPosition(base, zoom, mouthLocalX) {
  if (!base || ![base.x, base.width, base.scale, zoom, mouthLocalX]
      .every(Number.isFinite) || base.width <= 0 || base.scale <= 0 ||
      zoom < 1) return base?.x ?? 0
  const mouthX = base.x + mouthLocalX * base.scale
  const closeness = Math.max(0, Math.min(1,
    (zoom - 1) / (KISS_CAMERA_PEAK_ZOOM - 1)))
  return base.x + (1 - zoom) * (mouthX - base.x) +
    closeness * (base.width * .5 - mouthX)
}
const smooth = value => {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
}
export function kissCameraFrame(elapsedMs) {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0 ||
      elapsedMs >= KISS_CAMERA_DURATION_MS)
    return { zoom: 1, finished: true }
  // Approach, settle into a soft contact/nuzzle, and leave a long, eased
  // retreat. The tail is deliberately longer than the authored Motion3;
  // it is not tied to a guessed TTS sentence length.
  const approach = smooth((elapsedMs - 140) / 1080)
  const depart = 1 - smooth((elapsedMs - 2530) / 1920)
  const contact = smooth((elapsedMs - 1330) / 230) *
    (1 - smooth((elapsedMs - 2080) / 280))
  return { zoom: 1 + (1.15 * approach + .11 * contact) * depart, finished: false }
}
/**
 * Align the physical lips and eyelids with the SAME elapsed clock as the
 * camera. A static smile throughout the clip reads as a camera zoom, not a
 * kiss. Restrict this authored choreography to the verified wolf rig.
 */
export function kissFaceFrame(elapsedMs) {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0 ||
      elapsedMs >= KISS_CAMERA_DURATION_MS) return null
  const close = smooth((elapsedMs - 760) / 550) *
    (1 - smooth((elapsedMs - 2980) / 930))
  // Lips move forward for the contact, soften during the nuzzle, then
  // retract BEFORE the face has fully moved away from the glass.
  const contact = smooth((elapsedMs - 1330) / 230) *
    (1 - smooth((elapsedMs - 2080) / 280))
  const pucker = smooth((elapsedMs - 700) / 620) *
    (1 - smooth((elapsedMs - 2580) / 1050)) * (.86 + .14 * contact)
  return {
    face: {
      eyeOpen: .91 - .88 * close,
      eyeSmile: .07 + .15 * close,
      // A kiss forms closed/pursed lips, not the initial smiling mouth.
      smile: .15 - .39 * pucker,
      pout: .02 + .96 * pucker,
      jawOpen: 0,
    },
    pucker,
  }
}

/**
 * A tiny two-sided near-glass nuzzle, from real neck angles rather than
 * an entire 2D sprite shake. It is part of a chosen kiss, not an extra AI
 * gesture, and finishes before the slow camera retreat.
 */
export function kissNuzzleFrame(elapsedMs) {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0 ||
      elapsedMs >= KISS_CAMERA_DURATION_MS) return null
  const strength = smooth((elapsedMs - 1460) / 370) *
    (1 - smooth((elapsedMs - 2650) / 350))
  if (strength <= .001) return null
  const phase = 2 * Math.PI * (elapsedMs - 1480) / 1170
  return {
    yaw: 2.8 * Math.sin(phase) * strength,
    roll: 3.4 * Math.sin(phase + .35) * strength,
    pitch: -1.25 * strength,
  }
}
