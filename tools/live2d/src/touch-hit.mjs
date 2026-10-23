/**
 * Resolve tactile zones from actual Cubism authored HitAreas when available.
 * A hit never counts just because a finger was inside the phone viewport.
 * Fine subdivisions are approximations within the model's transformed bounds:
 * they are NOT claims of an anatomically rigged model.
 */
export const TOUCH_REGIONS = Object.freeze([
  'ear-left', 'ear-right', 'hair', 'forehead', 'eye-left', 'eye-right',
  'nose', 'cheek-left', 'cheek-right', 'mouth', 'neck', 'collarbone',
  'chest', 'arm-left', 'arm-right', 'hand-left', 'hand-right', 'body',
])

// Local per-model calibration. Coordinates are fractions of the transformed
// model bounds (not phone pixels), so they survive camera framing/resizing.
export function sanitizeTouchCalibration(value) {
  const clean = Object.create(null)
  if (!value || typeof value !== 'object' || Array.isArray(value)) return clean
  for (const zone of TOUCH_REGIONS) {
    const item = value[zone]
    if (!item || typeof item !== 'object') continue
    const { u, v, rx, ry } = item
    if (![u, v, rx, ry].every(Number.isFinite) ||
        u < 0 || u > 1 || v < 0 || v > 1 ||
        rx < .015 || rx > .35 || ry < .015 || ry > .35) continue
    clean[zone] = { u, v, rx, ry }
  }
  return clean
}

export function calibratedRegion(u, v, calibration) {
  let winner = null
  let nearest = Infinity
  for (const [zone, area] of Object.entries(calibration || {})) {
    if (!TOUCH_REGIONS.includes(zone)) continue
    const distance = ((u - area.u) / area.rx) ** 2 +
      ((v - area.v) / area.ry) ** 2
    if (Number.isFinite(distance) && distance <= 1 && distance < nearest) {
      winner = zone
      nearest = distance
    }
  }
  return winner
}

const namesMatch = (names, pattern) => names.some(name => pattern.test(name))
function anatomicalZone(u, v) {
  if (v < .34) {
    const h = v / .34
    if (h < .26 && (u < .25 || u > .75))
      return u < .5 ? 'ear-left' : 'ear-right'
    if (h < .19) return 'hair'
    if (h < .31) return 'forehead'
    if (h < .49 && u > .23 && u < .77)
      return u < .5 ? 'eye-left' : 'eye-right'
    if (h < .64 && u > .41 && u < .59) return 'nose'
    if (h < .80 && u > .38 && u < .62) return 'mouth'
    return u < .5 ? 'cheek-left' : 'cheek-right'
  }
  if (v < .39) return 'neck'
  if (v < .46) return 'collarbone'
  if (u < .28 || u > .72) {
    if (v > .78) return u < .5 ? 'hand-left' : 'hand-right'
    return u < .5 ? 'arm-left' : 'arm-right'
  }
  return v < .69 ? 'chest' : 'body'
}

export function resolveTouchRegion({ hits = [], authoredAreas = 0, bounds, x, y, calibration = null }) {
  if (!Number.isFinite(x) || !Number.isFinite(y) || !bounds ||
      !Number.isFinite(bounds.width) || !Number.isFinite(bounds.height) ||
      bounds.width <= 0 || bounds.height <= 0) return null
  const u = (x - bounds.x) / bounds.width
  const v = (y - bounds.y) / bounds.height
  if (u < 0 || u > 1 || v < 0 || v > 1) return null
  const manual = calibratedRegion(u, v, calibration)
  if (manual) return manual
  const names = Array.isArray(hits) ? hits.map(hit => String(hit).toLowerCase()) : []
  // Never turn a rejected authored hit area into a phantom touch.
  if (authoredAreas > 0 && names.length === 0) return null
  if (!names.length) {
    // Conservative silhouette for models without author-defined areas.
    // Bounds include transparent hair/accessory margins. An ellipse-style
    // conservative silhouette is safer than counting the entire rectangle.
    // User-calibrated ellipses above can expand or shrink individual zones.
    const halfWidth = v < .34
      ? .29 * Math.sqrt(Math.max(0, 1 - ((v - .17) / .19) ** 2))
      : v < .46 ? .19 + (v - .34) * .68
      : .27 + .08 * Math.sin(Math.PI * Math.min(1, (v - .46) / .54))
    if (Math.abs(u - .5) > halfWidth) return null
  }
  const side = u < .5 ? 'left' : 'right'
  if (namesMatch(names, /(?:ear|耳)/)) return 'ear-' + side
  if (namesMatch(names, /(?:hair|髪|头发|頭髮)/)) return 'hair'
  if (namesMatch(names, /(?:hand|手|手掌)/)) return 'hand-' + side
  if (namesMatch(names, /(?:arm|腕|うで|手臂)/)) return 'arm-' + side
  if (namesMatch(names, /(?:collar|鎖骨|锁骨)/)) return 'collarbone'
  if (namesMatch(names, /(?:neck|脖|首)/)) return 'neck'
  if (namesMatch(names, /(?:chest|胸)/)) return 'chest'
  if (namesMatch(names, /(?:eye|眼)/)) return 'eye-' + side
  if (namesMatch(names, /(?:nose|鼻)/)) return 'nose'
  if (namesMatch(names, /(?:mouth|lip|嘴|唇)/)) return 'mouth'
  if (namesMatch(names, /(?:cheek|脸颊|臉頰|ほほ)/)) return 'cheek-' + side
  // Common "Head"/"Body" hit areas are coarse. Subdivide the same hit
  // geometrically, without asking the character model or any remote API.
  if (namesMatch(names, /(?:head|face|hair|头|頭|脸|臉|顔)/))
    return anatomicalZone(u, Math.min(v, .339))
  if (namesMatch(names, /(?:body|torso|belly|腹|身体|身體)/))
    return anatomicalZone(u, v)
  return anatomicalZone(u, v)
}
