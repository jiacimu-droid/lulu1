import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { CubismParameterAccess } from '../src/cubism-parameters.mjs'

// The real Cubism SDK expects CubismIdHandle and can fabricate nonexistent
// parameter indices if handed raw strings. This mock deliberately does so.
const makeCore = () => {
  const names = ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ', 'ParamMouthOpenY', 'bq19']
  const values = [9, -6, 2, 0, 0]
  const virtual = new Map()
  return {
    names, values, virtual,
    getModel: () => ({ parameters: { ids: names, count: names.length } }),
    getParameterIndex: str => names.length + (virtual.set(str, 1), virtual.size - 1),
    setParameterValueById: (id, v) => virtual.set(id, v),
    getParameterValueByIndex: i => values[i],
    setParameterValueByIndex: (i, v) => { values[i] = v },
  }
}

const access = new CubismParameterAccess()
const core = makeCore()
assert.equal(access.index(core, 'ParamMouthOpenY'), 3)
assert.equal(access.index(core, 'ParamAngleX'), 0)
assert.equal(access.index(core, 'bq19'), 4)
assert.equal(access.index(core, 'NonexistentParam'), -1)
assert.equal(access.has(core, 'NonexistentParam'), false)
assert.equal(access.write(core, 'NonexistentParam', 0.5), false)
assert.equal(access.write(core, 'ParamMouthOpenY', 0.85), true)
assert.equal(access.read(core, 'ParamMouthOpenY'), 0.85)
assert.equal(core.values[3], 0.85)
assert.equal(core.virtual.size, 0)
assert.equal(access.write(core, 'ParamAngleX', 0), true)
assert.equal(core.values[0], 0)
assert.equal(access.write(core, 'ParamMouthOpenY', NaN), false)
const other = makeCore()
assert.equal(access.index(other, 'ParamMouthOpenY'), 3)
assert.equal(access.write(other, 'ParamMouthOpenY', 0.4), true)
assert.equal(other.values[3], 0.4)
assert.equal(core.values[3], 0.85)

const stage = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
assert.match(stage, /internal\.on\('beforeModelUpdate'/)
assert.doesNotMatch(stage, /\.getParameterIndex\(|\.setParameterValueById\(/)
assert.match(stage, /setParameter\(core, 'ParamMouthOpenY', smoothedMouthOpen\)/)
console.log('Live2D actual-index and final-stage tests passed')
