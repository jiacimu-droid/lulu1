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
// Video eye contact is a continuous default, not a random eye saccade and
// not a conditional release of gaze/body parameters to an idle animation.
assert.doesNotMatch(stage, /gazeReflex|Math\.random\(\).*radius/)
assert.match(stage, /const eyeContact = key === 'gazeX' \|\| key === 'gazeY'/)
assert.match(stage, /if \(!eyeContact && requested == null/)
assert.match(stage, /setParameter\(core, id, smoothBody\[axis\]\)/)
assert.match(stage, /model\.position\.set\(viewWidth \/ 2, viewHeight \* \(isPortrait \? 1\.00 : \.49\)\)/)
// The native receiver is authoritative about final frame size. Oversizing
// every JPEG by 1.38x defeats measured backpressure and causes stutter.
assert.doesNotMatch(stage, /maxEdge \* factor|Math\.min\(2100/)
assert.match(stage, /nativeStreamMaxEdge = Math\.min\(1850,/)
assert.match(stage, /nativeStreamAckMeanMs > 90 \? 0\.86 : 0\.89/)
console.log('Live2D actual-index, camera eye-contact and transport guards passed')
