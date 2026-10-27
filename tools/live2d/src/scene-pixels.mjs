// Shared renderer-only recognition of the static stage scene. The room is
// intentionally opaque; bright pixels alone are not evidence of a model.
export const SCENE_COLORS = Object.freeze([
  0xE9EDF4, 0xDFE8F2, 0xD3E2EE, 0xEAF1F8, 0xD8E2EB, 0xEFF3F8,
  0x151719, // old stage color for retained pre-migration buffers
])

export function isScenePixel(r, g, b) {
  if (![r, g, b].every(Number.isFinite)) return true
  return SCENE_COLORS.some(color =>
    Math.abs(r - ((color >> 16) & 255)) <= 18 &&
    Math.abs(g - ((color >> 8) & 255)) <= 18 &&
    Math.abs(b - (color & 255)) <= 18)
}

export function hasAvatarPixels(rgba, minimum) {
  if (!rgba || rgba.length < 4 || !Number.isFinite(minimum) || minimum < 1) return false
  let count = 0
  for (let i = 0; i + 3 < rgba.length; i += 4) {
    const r = rgba[i], g = rgba[i + 1], b = rgba[i + 2]
    if (rgba[i + 3] > 32 && !isScenePixel(r, g, b) &&
      (Math.max(r, g, b) > 45 || Math.max(r, g, b) - Math.min(r, g, b) > 18)) {
      if (++count >= minimum) return true
    }
  }
  return false
}
