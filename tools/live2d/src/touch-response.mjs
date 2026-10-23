/** Transient visual response, not a second character psyche or relationship store.
 * Caressing integrates real contact time and travel while the finger stays down.
 * MOVE frequency is not a touch count. Discrete kisses/palm strikes commit once.
 */
export const TOUCH_RESPONSE_PROFILES = Object.freeze({
  //                         pet gain/cap, kiss gain/cap, impact sensitivity
  hair:        { pet: [1.2, 8],  kiss: [5, 18],  impact: .75 },
  forehead:    { pet: [1.4, 10], kiss: [7, 22],  impact: .85 },
  'beast-ear': { pet: [2.5, 22], kiss: [10, 40], impact: .9 },
  ear:         { pet: [4, 32],   kiss: [15, 50], impact: 1.1 },
  eye:         { pet: [1.5, 12], kiss: [8, 28],  impact: 1.2 },
  nose:        { pet: [1.2, 10], kiss: [7, 25],  impact: 1.1 },
  cheek:       { pet: [4, 30],   kiss: [15, 48], impact: 1 },
  mouth:       { pet: [8, 45],   kiss: [56, 100],impact: 1.1 },
  neck:        { pet: [7, 42],   kiss: [32, 85], impact: 1.1 },
  throat:      { pet: [9, 48],   kiss: [50, 100],impact: 1.2 },
  collarbone:  { pet: [9, 48],   kiss: [50, 100],impact: .95 },
  chest:       { pet: [5, 34],   kiss: [26, 72], impact: .85 },
  arm:         { pet: [1.4, 10], kiss: [6, 24],  impact: .6 },
  hand:        { pet: [2, 14],   kiss: [8, 28],  impact: .55 },
  body:        { pet: [1, 10],   kiss: [8, 30],  impact: .8 },
})
export const touchFamily = region => String(region).replace(/-(?:left|right)$/, '')
export const isImpact = gesture => /^slap(?:-left|-right)?$/.test(String(gesture))
export const touchForce = value => Number.isFinite(value)
  ? Math.max(.1, Math.min(1, value)) : .55
const clamp = v => Math.max(0, Math.min(100, v))
const decayRate = Math.LN2 / 45_000
const isPet = gesture => gesture === 'tap' || gesture === 'hold' || String(gesture).startsWith('stroke')
const validPoint = point => point && Number.isFinite(point.x) && Number.isFinite(point.y)

export function createTouchResponseRuntime({ now = () => performance.now() } = {}) {
  const contributions = new Map()
  let impact = 0
  let lastAt = now()
  let contact = null

  function integrate(value, perSecond, elapsed, cap) {
    const fade = Math.exp(-elapsed * decayRate)
    if (value > cap) return value * fade
    // Exact exponential integration: the same path/time at 15Hz and 120Hz
    // produces the same response, rather than rewarding a faster phone.
    return Math.min(cap, value * fade + perSecond / 1000 / decayRate * (1 - fade))
  }

  function advance() {
    const time = Math.max(lastAt, now())
    const elapsed = time - lastAt
    const fade = Math.exp(-elapsed * decayRate)
    const family = contact && isPet(contact.gesture) ? touchFamily(contact.region) : null
    const before = contributions.get(family) || 0
    for (const [key, value] of contributions) {
      const next = value * fade
      if (next < .001) contributions.delete(key)
      else contributions.set(key, next)
    }
    if (family && elapsed > 0) {
      const [gain, cap] = TOUCH_RESPONSE_PROFILES[family].pet
      const strength = .7 + contact.force * .55
      const movingMs = Math.max(0, Math.min(time, contact.lastMovedAt + 450) - lastAt)
      const moving = integrate(before, gain * .55 * strength, movingMs, cap)
      const held = integrate(moving, gain * .12 * strength, elapsed - movingMs, cap)
      contributions.set(family, held)
    }
    impact *= 2 ** (-elapsed / 12_000)
    lastAt = time
  }

  function snapshot(force = contact?.force ?? .55) {
    advance()
    return { shyness: clamp([...contributions.values()].reduce((a, b) => a + b, 0)),
      impact: clamp(impact), force: touchForce(force) }
  }

  function begin(region, gesture = 'tap', force = .55, point = null) {
    advance()
    contact = TOUCH_RESPONSE_PROFILES[touchFamily(region)]
      ? { region, gesture, force: touchForce(force), startedAt: lastAt,
          lastMovedAt: -Infinity, point: validPoint(point) ? { ...point } : null } : null
    return snapshot(force)
  }

  function move(region, gesture, force = .55, point = null) {
    advance()
    if (!contact) return begin(region, gesture, force, point) // Re-enter the model, start a fresh interval.
    const family = touchFamily(region)
    const profile = TOUCH_RESPONSE_PROFILES[family]
    if (!profile) { contact = null; return snapshot(force) }
    const sameFamily = family === touchFamily(contact.region)
    const distance = sameFamily && validPoint(point) && validPoint(contact.point)
      ? Math.hypot(point.x - contact.point.x, point.y - contact.point.y) : 0
    if (isPet(gesture) && distance > 0) {
      const [gain, cap] = profile.pet
      const previous = contributions.get(family) || 0
      if (previous < cap) contributions.set(family, Math.min(cap,
        previous + gain * .8 * distance * (.7 + touchForce(force) * .55)))
    }
    contact = { ...contact, region, gesture, force: touchForce(force),
      lastMovedAt: distance > 0 && isPet(gesture) ? lastAt
        : sameFamily ? contact.lastMovedAt : -Infinity,
      point: validPoint(point) ? { ...point } : null }
    return snapshot(force)
  }

  function complete(region, gesture, force = .55) {
    advance()
    const hadPetContact = contact && isPet(contact.gesture)
    contact = null
    const family = touchFamily(region)
    const profile = TOUCH_RESPONSE_PROFILES[family]
    if (!profile) return snapshot(force)
    const strength = touchForce(force)
    if (isImpact(gesture)) {
      impact = clamp(impact + 48 * strength * profile.impact)
      for (const [key, value] of contributions) contributions.set(key, value * .82)
    } else if (gesture === 'kiss' || isPet(gesture)) {
      // Sustained stroking already accumulated before release. A quick tap,
      // kiss or legacy discrete contact still gets its one contact impulse.
      if (hadPetContact && gesture !== 'tap' && gesture !== 'kiss') return snapshot(strength)
      const [gain, cap] = gesture === 'kiss' ? profile.kiss : profile.pet
      const previous = contributions.get(family) || 0
      const multiplier = (.7 + strength * .55) *
        (gesture === 'tap' ? .55 : gesture === 'hold' ? 1.15 : 1)
      contributions.set(family, previous >= cap ? previous : Math.min(cap, previous + gain * multiplier))
      impact *= .65
    }
    return snapshot(strength)
  }

  function end() { advance(); contact = null }
  function reset() {
    contributions.clear()
    impact = 0
    contact = null
    lastAt = now()
  }
  return { begin, move, complete, snapshot, end, reset }
}
