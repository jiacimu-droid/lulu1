/** Crossfade only channels actually authored by the selected Motion3.
 * Runs before touch/face/PCM composition, so those layers retain ownership. */
export function createMotionTransition({ read, write, now = () => performance.now() }) {
  const last = new Map()
  let active = null
  function reset() { last.clear(); active = null }
  function capture(core, ids) {
    for (const id of ids || []) {
      const value = read(core, String(id))
      if (Number.isFinite(value)) last.set(String(id), value)
    }
  }
  function begin(ids, fadeInMs) {
    const duration = Number.isFinite(fadeInMs) && fadeInMs > 0 ? fadeInMs : 0
    const from = new Map((ids || []).filter(id => last.has(id)).map(id => [id, last.get(id)]))
    active = duration > 0 && from.size ? { from, duration, at: now() } : null
  }
  function update(core) {
    if (!active) return
    const ratio = Math.min(1, Math.max(0, (now() - active.at) / active.duration))
    const mix = ratio * ratio * (3 - 2 * ratio)
    for (const [id, from] of active.from) {
      const target = read(core, id)
      if (Number.isFinite(target)) write(core, id, from + (target - from) * mix)
    }
    if (ratio >= 1) active = null
  }
  function cancel() { active = null }
  return { capture, begin, update, reset, cancel }
}
