/** Compose procedural inputs in the same frame as Motion3 inputs.
 * Cubism4 restores saved motion parameters after each draw; writing only at
 * beforeModelUpdate cannot drive hair/torso physics, even on the next frame.
 * Own only this model instance, never the library prototype or physics loop.
 */
export function installPhysicsFrameControl(internal, { beforePhysics, afterPhysics }) {
  if (!internal?.on || typeof internal.updateNaturalMovements !== 'function') return false
  const natural = internal.updateNaturalMovements
  internal.updateNaturalMovements = function (deltaMs, now) {
    const result = natural.call(this, deltaMs, now)
    beforePhysics(Math.max(0, Number.isFinite(deltaMs) ? deltaMs : 0))
    return result
  }
  internal.on('beforeModelUpdate', afterPhysics)
  return true
}
