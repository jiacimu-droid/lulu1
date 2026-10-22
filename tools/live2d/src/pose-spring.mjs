/**
 * Small, frame-rate-independent critical damping for user/AI-selected poses.
 * Inspired by AIRI's Live2D motion-control-spring, adapted to the Android
 * Pixi/Cubism final-stage callback. A neutral target must stay perfectly
 * neutral: the spring never invents head movements or eye wandering.
 */
export function createPoseSpring(axes, stiffness = 100, dampingRatio = 1.04) {
  const value = Object.fromEntries(axes.map(axis => [axis, 0]))
  const velocity = Object.fromEntries(axes.map(axis => [axis, 0]))
  const damping = 2 * dampingRatio * Math.sqrt(stiffness)

  function reset() {
    for (const axis of axes) { value[axis] = 0; velocity[axis] = 0 }
  }

  function step(target, elapsedMs) {
    const seconds = Math.min(.05, Math.max(0, elapsedMs / 1000))
    const count = Math.max(1, Math.ceil(seconds * 120))
    const dt = seconds / count
    for (let i = 0; i < count; i++) {
      for (const axis of axes) {
        const goal = Number.isFinite(target?.[axis]) ? target[axis] : 0
        const acceleration = stiffness * (goal - value[axis]) - damping * velocity[axis]
        velocity[axis] += acceleration * dt
        value[axis] += velocity[axis] * dt
        if (Math.abs(goal - value[axis]) < .008 && Math.abs(velocity[axis]) < .06) {
          value[axis] = goal
          velocity[axis] = 0
        }
      }
    }
    return value
  }

  return { step, reset }
}
