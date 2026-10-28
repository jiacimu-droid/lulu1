/**
 * Continuous-time damped pose spring. The final Cubism callback may run at
 * 15–120 FPS (or skip a frame during JPEG transfer); integrating only the
 * first 50ms of a long frame makes the same gesture visibly slower on phones
 * with low frame cadence. Solve the oscillator over actual elapsed time so
 * attack, release and head/body follow-through keep their timing.
 * This is a render-only pose, never an emotional or character state.
 */
export function createPoseSpring(axes, stiffness = 100, dampingRatio = 1.04, releaseStiffness = stiffness) {
  const value = Object.fromEntries(axes.map(axis => [axis, 0]))
  const velocity = Object.fromEntries(axes.map(axis => [axis, 0]))

  function reset() {
    for (const axis of axes) { value[axis] = 0; velocity[axis] = 0 }
  }

  function step(target, elapsedMs, releasing = false, selectedStiffness = null) {
    const frequency = releasing ? releaseStiffness :
      Number.isFinite(selectedStiffness) && selectedStiffness > 0 ? selectedStiffness : stiffness
    const omega = Math.sqrt(frequency)
    const seconds = Number.isFinite(elapsedMs) ? Math.max(0, elapsedMs) / 1000 : 0
    if (!(omega > 0) || !Number.isFinite(omega) ||
        !Number.isFinite(dampingRatio) || dampingRatio < 0 || !seconds) return value
    const zeta = dampingRatio
    for (const axis of axes) {
      const goal = Number.isFinite(target?.[axis]) ? target[axis] : 0
      const offset = value[axis] - goal
      const speed = velocity[axis]
      let nextOffset, nextSpeed
      if (zeta > 1 + 1e-5) {
        const root = Math.sqrt(zeta * zeta - 1)
        const slow = -omega * (zeta - root)
        const fast = -omega * (zeta + root)
        const a = (speed - fast * offset) / (slow - fast)
        const b = offset - a
        const slowFade = Math.exp(slow * seconds)
        const fastFade = Math.exp(fast * seconds)
        nextOffset = a * slowFade + b * fastFade
        nextSpeed = a * slow * slowFade + b * fast * fastFade
      } else if (zeta >= 1 - 1e-5) {
        const carry = speed + omega * offset
        const fade = Math.exp(-omega * seconds)
        nextOffset = (offset + carry * seconds) * fade
        nextSpeed = (speed - omega * carry * seconds) * fade
      } else {
        const rate = zeta * omega
        const swing = omega * Math.sqrt(1 - zeta * zeta)
        const b = (speed + rate * offset) / swing
        const c = Math.cos(swing * seconds)
        const s = Math.sin(swing * seconds)
        const fade = Math.exp(-rate * seconds)
        nextOffset = fade * (offset * c + b * s)
        nextSpeed = fade * ((-rate * offset + swing * b) * c +
          (-rate * b - swing * offset) * s)
      }
      value[axis] = goal + nextOffset
      velocity[axis] = nextSpeed
      if (Math.abs(nextOffset) < .008 && Math.abs(nextSpeed) < .06) {
        value[axis] = goal
        velocity[axis] = 0
      }
    }
    return value
  }

  return { step, reset }
}
