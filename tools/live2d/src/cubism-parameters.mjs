/**
 * Cubism Web Framework requires a CubismIdHandle (not a JS string) for its
 * *ById() methods. In particular, getParameterIndex('ParamMouthOpenY') can
 * allocate a nonexistent virtual parameter and silently change nothing.
 *
 * The embedded Cubism core already exposes the actual .moc3 parameter IDs.
 * Resolve those to REAL indices once per loaded core model, then use ByIndex.
 * Do not ever fabricate missing parameters.
 */
export class CubismParameterAccess {
  constructor() {
    this.core = null
    this.indices = new Map()
    this.minimums = null
    this.maximums = null
  }

  index(core, id) {
    if (!core || typeof id !== 'string') return -1
    if (this.core !== core) {
      this.core = core
      this.indices = new Map()
      const params = core.getModel?.()?.parameters
      // Moc3 parameter IDs and their physical limits are immutable for one
      // loaded model. Cache both instead of re-reading getModel every frame.
      this.minimums = params?.minimumValues || null
      this.maximums = params?.maximumValues || null
      const ids = params?.ids
      if (ids) {
        const length = Math.min(Math.max(0, Number(params.count) || 0), ids.length || 0)
        for (let i = 0; i < length; i++) this.indices.set(String(ids[i]), i)
      }
    }
    return this.indices.get(id) ?? -1
  }

  has(core, id) {
    return this.index(core, id) >= 0
  }

  read(core, id) {
    const i = this.index(core, id)
    return i >= 0 ? core.getParameterValueByIndex(i) : null
  }

  write(core, id, value) {
    const i = this.index(core, id)
    if (i < 0 || !Number.isFinite(value)) return false
    const min = this.minimums?.[i]
    const max = this.maximums?.[i]
    const bounded = Math.min(Number.isFinite(max) ? max : Infinity,
      Math.max(Number.isFinite(min) ? min : -Infinity, value))
    core.setParameterValueByIndex(i, bounded)
    return true
  }
}
