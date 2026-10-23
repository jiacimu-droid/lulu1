import { touchExpressionWeight } from './touch-reflex.mjs'

/** Fractional authored Add channels, with independent release ownership.
 * Automatic touch must not turn a faint blush into a full-strength exp3.
 * Explicit character/manual expressions still use the original manager.
 */
export function createTouchExpressionLayer({ has, read, write }) {
  let channels = []
  let startedAt = 0
  let until = 0
  let activeName = null

  function clear(core) {
    for (const channel of channels) {
      if (has(core, channel.id)) write(core, channel.id, channel.base)
    }
    channels = []
    activeName = null
  }

  function start(core, name, metadata, extra, state, now, delayMs = 0, durationMs = 2100) {
    clear(core)
    const parameters = metadata?.[name]?.Parameters
    if (!Array.isArray(parameters)) return false
    const weight = touchExpressionWeight(name, state)
    for (const p of parameters) {
      if (p?.Blend !== 'Add' || !Number.isFinite(p.Value) ||
          !has(core, p.Id)) continue
      const base = read(core, p.Id)
      if (Number.isFinite(base)) channels.push({ id: p.Id, base,
        expression: name, value: p.Value, delta: p.Value * weight })
    }
    if (extra && has(core, extra.id) && !channels.some(c => c.id === extra.id)) {
      const base = read(core, extra.id)
      if (Number.isFinite(base)) channels.push({ id: extra.id, base,
        expression: extra.name, value: extra.value / touchExpressionWeight(extra.name, state),
        delta: extra.value })
    }
    if (!channels.length) return false
    activeName = name
    startedAt = now + delayMs
    until = startedAt + durationMs
    return true
  }
  function retarget(state, now, durationMs = 2200) {
    if (!activeName) return
    for (const c of channels) c.delta = c.value * touchExpressionWeight(c.expression, state)
    until = now + durationMs
  }

  function update(core, now) {
    if (!activeName) return null
    if (now >= until) { clear(core); return null }
    const fade = Math.max(0, Math.min(1, (now - startedAt) / 160, (until - now) / 650))
    for (const c of channels) write(core, c.id, c.base + c.delta * fade)
    return now >= startedAt ? activeName : null
  }
  return { start, update, retarget, clear }
}
