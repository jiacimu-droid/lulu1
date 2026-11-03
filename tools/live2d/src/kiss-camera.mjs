// Renderer-only framing and facial score for a successfully started wolf
// kiss Motion3. No new character intent, audio or independent scheduler.
export const WOLF_KISS_MOTION_INDEX = 48
export const KISS_CAMERA_DURATION_MS = 3350
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
  // One continuous approach, a tiny near-glass contact pulse, then a
  // smooth withdraw. Zoom changes alone must not pretend the lips kissed.
  const approach = smooth((elapsedMs - 140) / 1100)
  const depart = 1 - smooth((elapsedMs - 2160) / 1190)
  const contact = smooth((elapsedMs - 1350) / 220) *
    (1 - smooth((elapsedMs - 1890) / 260))
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
    (1 - smooth((elapsedMs - 2200) / 650))
  const pucker = smooth((elapsedMs - 720) / 610) *
    (1 - smooth((elapsedMs - 2160) / 860))
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
