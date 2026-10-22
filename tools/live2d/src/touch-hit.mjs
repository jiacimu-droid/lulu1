/**
 * Determine which part of the model was actually touched.
 * Prefer author-provided Cubism HitAreas: if present, a missed area is NOT a hit.
 * For rigs with no HitAreas at all, use a conservative silhouette inside
 * Pixi's transformed model bounds, not the whole video viewport.
 */
export function resolveTouchRegion({ hits = [], authoredAreas = 0, bounds, x, y }) {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null
  if (Array.isArray(hits) && hits.length) {
    const names = hits.map(v => String(v).toLowerCase())
    if (names.some(v => /head|face|hair|cheek|头|脸|髪|发/.test(v))) return 'head'
    if (names.some(v => /hand|arm|手|腕|うで/.test(v))) return 'arm'
    if (names.some(v => /body|chest|torso|belly|身体|腹|胸/.test(v))) return 'body'
    return 'body' // a real model-authored hit area, even if unnamed
  }
  if (authoredAreas > 0 || !bounds ||
      !Number.isFinite(bounds.width) || !Number.isFinite(bounds.height) ||
      bounds.width <= 0 || bounds.height <= 0) return null
  const u = (x - bounds.x) / bounds.width
  const v = (y - bounds.y) / bounds.height
  if (u < 0 || u > 1 || v < 0 || v > 1) return null
  // Thin near the extremities, generous over the torso. This fallback is
  // approximate and deliberately rejects background beyond the silhouette.
  const halfWidth = .27 + .18 * Math.sin(Math.PI * v)
  if (Math.abs(u - .5) > halfWidth) return null
  return v < .32 ? 'head' : 'body'
}
