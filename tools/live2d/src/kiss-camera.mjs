// Renderer-only framing and facial score for a successfully started wolf
// kiss Motion3. No new character intent, audio or independent scheduler.
export const WOLF_KISS_MOTION_INDEX = 48
export const KISS_CAMERA_DURATION_MS = 3350
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
  const depart = 1 - smooth((elapsedMs - 2220) / 1130)
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
