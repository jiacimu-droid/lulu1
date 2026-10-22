/**
 * Audio-clock-driven visual score, inspired by AIRI's layered motion manager.
 * One planned score belongs to one voice turn. No timers, paid API calls,
 * second cognition loop or fabricated movements: the render callback merely
 * samples the plan at a monotonic instant.
 */
export function createPerformanceRuntime({ now = () => performance.now() } = {}) {
  let pending = null
  let current = null
  let speaking = false

  function schedule(beats, immediate = false) {
    const allowed = Array.isArray(beats) ? beats.filter(beat =>
      beat && Number.isFinite(beat.atMs) && beat.atMs >= 0 &&
      beat.atMs <= 30000 &&
      (beat.expression || beat.head || beat.body || beat.face))
      .slice(0, 16).sort((a, b) => a.atMs - b.atMs) : []
    if (!allowed.length) return false
    // A later decision supersedes every unplayed beat of the former decision.
    current = null
    pending = null
    if (speaking || immediate) {
      current = { beats: allowed, index: 0, startedAt: now(), immediate }
    } else {
      pending = { beats: allowed, queuedAt: now() }
    }
    return true
  }

  function startSpeech() {
    if (speaking) return // duplicate PCM/status callback
    speaking = true
    if (pending) {
      if (now() - pending.queuedAt <= 20000) {
        current = { beats: pending.beats, index: 0, startedAt: now(), immediate: false }
      }
      pending = null
    }
  }

  function stopSpeech() {
    speaking = false
    current = null
    pending = null
  }

  /** Render callback calls this after normal motions, before face sampling. */
  function tick(onBeat) {
    const time = now()
    if (pending && time - pending.queuedAt > 20000) pending = null
    if (!current || (!current.immediate && !speaking)) return 0
    // Monotonic wall elapsed, independent of Pixi/JPEG FPS. If a frame takes
    // 200 ms, due gestures are processed once, in chronological order.
    const elapsed = Math.max(0, time - current.startedAt)
    let fired = 0
    while (current && current.index < current.beats.length &&
        current.beats[current.index].atMs <= elapsed) {
      const beat = current.beats[current.index++]
      onBeat(beat)
      fired++
    }
    if (current && current.index >= current.beats.length) current = null
    return fired
  }

  function reset() {
    speaking = false
    current = null
    pending = null
  }
  function state() {
    return { speaking, queued: !!pending,
      remaining: current ? current.beats.length - current.index : 0 }
  }

  return { schedule, startSpeech, stopSpeech, tick, reset, state }
}
