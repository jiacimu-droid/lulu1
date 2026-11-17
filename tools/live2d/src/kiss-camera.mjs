// Renderer-only framing and facial score for a successfully started wolf
// kiss Motion3. No new character intent, audio or independent scheduler.
export const WOLF_KISS_MOTION_INDEX = 48
// A neutral proximity action: any expression can accompany a closer look.
// It uses the SAME calibrated face framing as a kiss, without a kiss face score.
export const WOLF_APPROACH_MOTION_INDEX = 49
export const APPROACH_CAMERA_DURATION_MS = 4200
export const APPROACH_CAMERA_PEAK_ZOOM = 1.82
export const isScreenProximityMotion = index =>
  index === WOLF_KISS_MOTION_INDEX || index === WOLF_APPROACH_MOTION_INDEX
export const screenProximityDuration = index =>
  index === WOLF_APPROACH_MOTION_INDEX ? APPROACH_CAMERA_DURATION_MS :
    index === WOLF_KISS_MOTION_INDEX ? KISS_CAMERA_DURATION_MS : 0
export const screenProximityPeak = index =>
  index === WOLF_APPROACH_MOTION_INDEX ? APPROACH_CAMERA_PEAK_ZOOM :
    KISS_CAMERA_PEAK_ZOOM
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
 * Center the FACE for a neutral look-in; a kiss instead centers the lips.
 * Use the actual calibrated eye centers when available, not an assumed
 * smiling or leaning expression. Uncalibrated rigs keep the old safe
 * horizontal offset and can still show their own unmodified facial pose.
 */
export function screenFaceLocalOffsetX(base, bounds, calibration = {}) {
  const eyes = [calibration['eye-left'], calibration['eye-right']]
    .filter(zone => Number.isFinite(zone?.u) && zone.u >= 0 && zone.u <= 1)
    .map(zone => zone.u)
  if (eyes.length && bounds && Number.isFinite(bounds.x) &&
      Number.isFinite(bounds.width) && bounds.width > 0 &&
      base && Number.isFinite(base.x) && Number.isFinite(base.width) &&
      Number.isFinite(base.scale) && base.scale > 0 && base.width > 0) {
    const u = eyes.reduce((a, b) => a + b, 0) / eyes.length
    const difference = bounds.x + bounds.width * u - base.x
    if (Number.isFinite(difference) && Math.abs(difference) < base.width * .6)
      return difference / base.scale
  }
  return kissMouthLocalOffsetX(base, bounds, calibration.mouth)
}

/**
 * Center the lips as the wolf approaches the glass instead of magnifying
 * their offset from the torso pivot. At zoom=1 the ordinary framing stays
 * unchanged; the exact same curve reverses smoothly during withdrawal.
 */
export function kissCameraHorizontalPosition(base, zoom, mouthLocalX,
  peakZoom = KISS_CAMERA_PEAK_ZOOM) {
  if (!base || ![base.x, base.width, base.scale, zoom, mouthLocalX]
      .every(Number.isFinite) || base.width <= 0 || base.scale <= 0 ||
      zoom < 1) return base?.x ?? 0
  const mouthX = base.x + mouthLocalX * base.scale
  const closeness = Math.max(0, Math.min(1,
    (zoom - 1) / Math.max(.01, peakZoom - 1)))
  return base.x + (1 - zoom) * (mouthX - base.x) +
    closeness * (base.width * .5 - mouthX)
}

/**
 * The zoom pivot for a kiss is the character's FACE, not the middle of
 * their full-body sprite. Prefer the user's calibrated eye centers. The
 * fallback is a proportion of the actual model bounds, so portraits and
 * resizes use the same model-local point without looking at TTS timing.
 */
export function kissFaceLocalOffsetY(base, bounds, calibration = {}) {
  if (!base || !Number.isFinite(base.y) || !Number.isFinite(base.scale) ||
      base.scale <= 0 || !Number.isFinite(base.height) || base.height <= 0) return 0
  const validBounds = bounds && Number.isFinite(bounds.y) &&
    Number.isFinite(bounds.height) && bounds.height > 0
  const validV = zone => Number.isFinite(zone?.v) &&
    zone.v >= 0 && zone.v <= 1
  const eyes = [calibration['eye-left'], calibration['eye-right']]
    .filter(validV).map(zone => zone.v)
  let y
  if (validBounds && eyes.length)
    y = bounds.y + eyes.reduce((a, b) => a + b, 0) / eyes.length * bounds.height
  else if (validBounds && validV(calibration.mouth))
    y = bounds.y + Math.max(0, calibration.mouth.v - .075) * bounds.height
  else if (validBounds)
    y = bounds.y + .18 * bounds.height
  else y = base.height * .30
  return (y - base.y) / base.scale
}

/**
 * Keep eyes/face in the central portrait as the character leans toward the
 * glass. A fixed 42%-of-screen zoom pivot pushed the eyes OUT of the frame
 * because this portrait's actual face starts much higher than its torso.
 * Zoom=1 always restores the exact original fit without a vertical jump.
 */
export function kissCameraVerticalPosition(base, zoom, faceLocalY,
  peakZoom = KISS_CAMERA_PEAK_ZOOM) {
  if (!base || ![base.y, base.height, base.scale, zoom, faceLocalY]
      .every(Number.isFinite) || base.height <= 0 || base.scale <= 0 ||
      zoom < 1) return base?.y ?? 0
  const faceY = base.y + faceLocalY * base.scale
  const closeness = Math.max(0, Math.min(1,
    (zoom - 1) / Math.max(.01, peakZoom - 1)))
  const desiredFaceY = base.height * .45
  return base.y + (1 - zoom) * (faceY - base.y) +
    closeness * (desiredFaceY - faceY)
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

// An explicitly selected next Motion3 can take the body immediately while
// the camera eases away from the previous kiss. This handoff is distinct from
// a real call cancellation, which must clear the camera without a lingering tail.
export const KISS_HANDOFF_MS = 1450
export function kissHandoffFrame(startZoom, elapsedMs) {
  if (!Number.isFinite(startZoom) || startZoom < 1 ||
      !Number.isFinite(elapsedMs) || elapsedMs >= KISS_HANDOFF_MS)
    return { zoom: 1, finished: true }
  const recovery = smooth(Math.max(0, elapsedMs) / KISS_HANDOFF_MS)
  return { zoom: 1 + (startZoom - 1) * (1 - recovery), finished: false }
}

/**
 * Screen proximity is a distance/placement channel, NOT a mood. The body
 * Motion3 brings the wolf forward; the renderer tracks its calibrated face,
 * holds direct visual contact, then withdraws. Eye, brow, mouth, blush and
 * character-chosen exp3 belong to independent layers, never this curve.
 */
export function approachCameraFrame(elapsedMs) {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0 ||
      elapsedMs >= APPROACH_CAMERA_DURATION_MS)
    return { zoom: 1, finished: true }
  const approach = smooth((elapsedMs - 110) / 1190)
  const withdraw = 1 - smooth((elapsedMs - 2610) / 1590)
  return { zoom: 1 + (APPROACH_CAMERA_PEAK_ZOOM - 1) *
    approach * withdraw, finished: false }
}

export function screenProximityFrame(index, elapsedMs) {
  return index === WOLF_KISS_MOTION_INDEX ? kissCameraFrame(elapsedMs) :
    index === WOLF_APPROACH_MOTION_INDEX ? approachCameraFrame(elapsedMs) :
      { zoom: 1, finished: true }
}
