// Optional actuation of mouth channels present in the imported Cubism rig.
// Audio amplitude is not phoneme recognition: preserve selected smile/face.
import { WOLF_MOUTH } from './wolf-rig-profile.mjs'
export function createSpeechMouthRuntime({ has, write }) {
  let open = 0, jaw = 0, width = 0, funnel = 0, shrug = 0, alive = false
  const clamp = v => Math.max(0, Math.min(1, Number.isFinite(v) ? v : 0))
  const follow = (value, goal, dt, rise, fall) =>
    value + (goal - value) * (1 - Math.exp(-dt / (goal > value ? rise : fall)))
  function reset() { open = jaw = width = funnel = shrug = 0; alive = false }
  function update(core, { deltaMs = 16, speaking = false, pcmAvailable = false,
    level = 0, elapsedMs = 0, seed = 0, blocked = false,
    jawOverride = null, poutOverride = null, wolf = false } = {}) {
    const dt = Math.min(50, Math.max(0, deltaMs))
    const profile = wolf ? WOLF_MOUTH : null
    const beat = Math.pow(Math.sin(Math.max(0, elapsedMs) * .0105 + seed), 2)
    const fallback = (profile?.fallbackBase ?? .24) +
      (profile?.fallbackBeat ?? .34) * beat
    const target = speaking && !blocked ? (pcmAvailable ? clamp(level) : fallback) : 0
    if (speaking || blocked || (Number.isFinite(jawOverride) && jawOverride > 0)) alive = true
    if (!alive) return
    // Exponential, frame-rate-independent attack and release.
    open = blocked ? 0 : follow(open, target, dt,
      profile?.attackMs ?? 54, speaking ? (profile?.releaseMs ?? 112) :
        (profile?.restMs ?? 175))
    jaw = follow(jaw, Math.max(open * (profile?.jawGain ?? .72),
      Number.isFinite(jawOverride) ? clamp(jawOverride) : 0), dt,
    profile?.jawAttackMs ?? 105, profile?.jawReleaseMs ?? 180)
    width = follow(width, speaking && !blocked ?
      open * (profile?.widthGain ?? .17) : 0, dt, 115, 170)
    funnel = follow(funnel, speaking && !blocked ?
      (profile?.funnelGain ?? .055) * (1 - open) : 0, dt, 135, 190)
    shrug = follow(shrug, speaking && !blocked ?
      open * (profile?.shrugGain ?? .045) : 0, dt, 120, 160)
    const outputs = [
      ['ParamMouthOpenY', open], ['ParamJawOpen', jaw],
      ['ParamMouthPuckerWiden', width], ['ParamMouthShrug', shrug],
    ]
    // The character's explicit pout beats speech articulation.
    if (poutOverride == null) outputs.push(['ParamMouthFunnel', funnel])
    else if (speaking && !blocked)
      outputs.push(['ParamMouthFunnel', Math.max(funnel, clamp(poutOverride))])
    for (const [id, value] of outputs) if (has(core, id)) write(core, id, value)
    if (!speaking && !blocked && !(jawOverride > 0) &&
        Math.max(open, jaw, width, funnel, shrug) < .007) reset()
  }
  return { update, reset }
}
