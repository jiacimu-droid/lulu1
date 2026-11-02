// Renderer-only close-up for an actual, successfully started wolf kiss Motion3.
// No new character intent, audio, app permission or independent scheduling.
export const WOLF_KISS_MOTION_INDEX = 48
export const KISS_CAMERA_DURATION_MS = 3350
const smooth = value => {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
}
export function kissCameraFrame(elapsedMs) {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0)
    return { zoom: 1, finished: true }
  if (elapsedMs >= KISS_CAMERA_DURATION_MS)
    return { zoom: 1, finished: true }
  // Approach, linger for the kiss, and then withdraw without a camera cut.
  // 1.88x intentionally brings the face close to the phone's glass.
  const approaching = smooth((elapsedMs - 140) / 1040)
  const withdrawing = 1 - smooth((elapsedMs - 2120) / 1230)
  return { zoom: 1 + 0.88 * approaching * withdrawing, finished: false }
}
