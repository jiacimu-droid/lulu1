/** Crossfade only channels actually authored by the selected Motion3.
 * The entrance runs before touch/face composition. On a natural finish the
 * exit runs AFTER those layers, bridging the last rendered pose to their
 * current target without snapping to the Cubism idle pose. */
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
    active = duration > 0 && from.size
      ? { kind: 'entrance', from, duration, at: now() } : null
  }
  function release(ids, durationMs = 440) {
    // Never include unrelated eyes, lips, or physics outputs in a body
    // motion's release. A missing/failed clip does not claim this lease.
    const from = new Map((ids || []).filter(id => last.has(id))
      .map(id => [id, last.get(id)]))
    active = from.size ? {
      kind: 'exit', from, at: now(),
      duration: Number.isFinite(durationMs) && durationMs > 0 ? durationMs : 440,
    } : null
  }
  function update(core, phase = 'before') {
    if (!active || (active.kind === 'exit' ? phase !== 'after' : phase !== 'before'))
      return
    const ratio = Math.min(1, Math.max(0, (now() - active.at) / active.duration))
    const mix = ratio * ratio * (3 - 2 * ratio)
    for (const [id, from] of active.from) {
      const target = read(core, id)
      if (Number.isFinite(target)) write(core, id, from + (target - from) * mix)
    }
    if (ratio >= 1) active = null
  }
  function cancel() { active = null }
  return { capture, begin, release, update, reset, cancel }
}
