/**
 * Coordinates expressions that occupy the speaking mouth (tongue out etc.)
 * with actual audio state. Ordinary blush/smile expressions are not blocked.
 *
 * An utterance is not a waveform: TTS playing alone cannot prove a pause.
 * Only fresh PCM evidence may open an in-speech quiet window. If no PCM is
 * available the gesture waits for the actual finished callback.
 */
export function isMouthExclusiveExpression(expressionJson) {
  const parameters = expressionJson?.Parameters
  return Array.isArray(parameters) && parameters.some(parameter => {
    const id = String(parameter?.Id || '').toLowerCase()
    const v = Number(parameter?.Value || 0)
    return Number.isFinite(v) && v > .01 &&
      (id.includes('tongue') || id.includes('mouthclose') || id.includes('mouthcover'))
  })
}

export function createSpeechExpressionGate({ now = () => performance.now() } = {}) {
  let speaking = false
  let hasPcm = false
  let mouth = 0
  let quietSince = null
  let lastSampleAt = -Infinity
  let pending = null
  let active = false
  let activeSince = 0
  // Local *audible* PCM gaps, not an arbitrary character emotion clock.
  const QUIET_MS = 210
  const PCM_FRESH_MS = 270
  const GESTURE_MS = 760
  const safeLevel = v => Number.isFinite(v) ? Math.max(0, Math.min(1, v)) : 1

  function sample({ audible, pcm, level }) {
    const t = now()
    speaking = !!audible
    hasPcm = !!pcm
    mouth = safeLevel(level)
    lastSampleAt = t
    if (!speaking || !hasPcm || mouth > .055) quietSince = null
    else if (quietSince == null) quietSince = t
    // On real resumed audio, instantly remove a tongue occupying the mouth.
    if (active && speaking && (!hasPcm || mouth > .055)) {
      active = false
      return { reset: true }
    }
    return { reset: false }
  }

  /** The expression is already verified against the imported model. */
  function request(name, speechActive) {
    if (!name) return false
    if (speechActive) {
      pending = name
      return false
    }
    pending = null
    active = true
    activeSince = now()
    return true
  }

  function tick() {
    const t = now()
    let reset = false
    let play = null
    if (active && t - activeSince >= GESTURE_MS) {
      active = false
      reset = true
    }
    // Crucially a single stale zero-amplitude sample cannot fake a pause.
    const fresh = t - lastSampleAt <= PCM_FRESH_MS
    if (pending && speaking && hasPcm && fresh && quietSince != null &&
        t - quietSince >= QUIET_MS) {
      play = pending
      pending = null
      active = true
      activeSince = t
    }
    return { play, reset }
  }

  function finished() {
    speaking = false
    hasPcm = false
    quietSince = null
    const play = pending
    pending = null
    if (play) {
      active = true
      activeSince = now()
    }
    return { play }
  }

  function interrupted() {
    const reset = active
    active = false
    pending = null
    speaking = false
    hasPcm = false
    quietSince = null
    return { reset }
  }

  function started() {
    const reset = active
    active = false
    speaking = true
    hasPcm = false
    quietSince = null
    // Never drop a new expression queued for this utterance.
    return { reset }
  }

  function reset() {
    active = false
    pending = null
    speaking = false
    hasPcm = false
    quietSince = null
    lastSampleAt = -Infinity
  }

  function state() { return { active, pending, speaking } }
  return { sample, request, tick, finished, interrupted, started, reset, state }
}

/** An authored eye shape temporarily owns its eyelid parameters. */
export function isEyeExclusiveExpression(expressionJson, name = '') {
  if (/星星眼|爱心眼|闭眼|眨眼|wink|star[ -]?eye|heart[ -]?eye|closed[ -]?eye/i.test(name)) return true
  const params = expressionJson?.Parameters
  return Array.isArray(params) && params.some(param =>
    /^(ParamEye[LR](Open|Smile|Squint)|ParamEyeBallForm)$/.test(String(param?.Id || '')))
}
