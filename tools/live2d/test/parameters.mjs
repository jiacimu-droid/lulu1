import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { CubismParameterAccess } from '../src/cubism-parameters.mjs'
import { createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { createPerformanceRuntime } from '../src/speech-performance.mjs'
import { createPoseSpring } from '../src/pose-spring.mjs'
import { createSpeechExpressionGate, isMouthExclusiveExpression, isEyeExclusiveExpression } from '../src/speech-expression-gate.mjs'

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
other.getModel = () => ({ parameters: {
  ids: other.names, count: other.names.length,
  minimumValues: [-10, -10, -10, 0, 0],
  maximumValues: [10, 10, 10, .7, 1],
}})
assert.equal(access.write(other, 'ParamMouthOpenY', 5), true)
assert.equal(access.read(other, 'ParamMouthOpenY'), .7)
assert.equal(access.write(other, 'ParamAngleX', -25), true)
assert.equal(access.read(other, 'ParamAngleX'), -10)
assert.equal(other.values[3], 0.4)
assert.equal(core.values[3], 0.85)

const stage = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
assert.match(stage, /internal\.on\('beforeModelUpdate'/)
assert.doesNotMatch(stage, /\.getParameterIndex\(|\.setParameterValueById\(/)
assert.match(stage, /setParameter\(core, 'ParamMouthOpenY', smoothedMouthOpen\)/)
assert.match(stage, /visualLayers\.update\(core, \{/)
assert.match(stage, /performanceRuntime\.tick\(beat =>/)
assert.match(stage, /performanceRuntime\.stopSpeech\(\)/)
assert.match(stage, /performanceRuntime\.schedule\(beats, !!immediatePreview\)/)
assert.match(stage, /model\.position\.set\(viewWidth \/ 2, viewHeight \* \(isPortrait \? 1\.00 : \.49\)\)/)
assert.doesNotMatch(stage, /gazeReflex|Math\.random\(\).*radius/)
assert.doesNotMatch(stage, /maxEdge \* factor|Math\.min\(2100/)
assert.match(stage, /nativeStreamMaxEdge = Math\.min\(1850,/)
assert.match(stage, /nativeStreamAckMeanMs > 90 \? 0\.86 : 0\.89/)

// Final-layer Cubism ownership must be tested by executing writes rather
// than searching the former monolithic stage for string names.
const valuesById = new Map([
  ['ParamAngleX', 14], ['ParamAngleY', 8], ['ParamAngleZ', -7],
  ['ParamAngleX2', 9], ['ParamAngleY2', 5], ['ParamAngleZ2', -8],
  ['ParamBodyAngleX', 7], ['body_gensui_x', 4],
  ['ParamEyeBallX', 0.5], ['ParamEyeBallY', -0.3],
  ['ParamMouthForm', 0.4], ['ParamEyeLOpen', 1], ['ParamEyeROpen', 1],
  ['ParamEyeLSmile', 0], ['ParamEyeRSmile', 0],
  ['ParamBreath', 0],
])
const readValue = (_core, id) => valuesById.get(id)
const writeValue = (_core, id, value) => { valuesById.set(id, value); return true }
const rig = createVisualLayerRuntime({
  has: (_core, id) => valuesById.has(id),
  read: readValue, write: writeValue,
})
let seconds = 0
function frame(head = null, body = null, face = null, speaking = false, deltaMs = 16) {
  seconds += deltaMs / 1000
  rig.update(core, { deltaMs, head, body, face, speaking, seconds })
}
for (let i = 0; i < 50; i++) frame()
for (const id of ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
  'ParamAngleX2', 'ParamAngleY2', 'ParamAngleZ2',
  'ParamBodyAngleX', 'body_gensui_x', 'ParamEyeBallX', 'ParamEyeBallY']) {
  assert.ok(Math.abs(valuesById.get(id)) < .05, 'idle not centered: ' + id)
}
assert.ok(valuesById.get('ParamBreath') > 0.3)
for (let i = 0; i < 60; i++) frame({ roll: 12 }, null, null, true)
assert.ok(valuesById.get('ParamAngleZ') > 11)
for (let i = 0; i < 90; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamAngleZ')) < .05)
valuesById.set('ParamMouthForm', .62)
frame()
assert.equal(valuesById.get('ParamMouthForm'), .62,
  'idle should release expression parameters to the model')
for (let i = 0; i < 45; i++) frame(null, null, { smile: .7, eyeOpen: .55 }, true)
assert.ok(valuesById.get('ParamMouthForm') > .55)
assert.ok(valuesById.get('ParamEyeLOpen') < .7)
for (let i = 0; i < 90; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamMouthForm') - .62) < .05,
  'released smile must restore the imported model expression')
assert.ok(Math.abs(valuesById.get('ParamEyeLOpen') - 1) < .05)
valuesById.set('ParamMouthForm', .28)
frame()
assert.equal(valuesById.get('ParamMouthForm'), .28,
  'after face release, imported expressions own facial parameter')
rig.reset()
frame()
assert.equal(valuesById.get('ParamAngleX'), 0)

// Speech score uses real monotonic time; dropped frames never delay gestures,
// repeated 'playing' callbacks do not replay any completed beat.
let clock = 1000
const performance = createPerformanceRuntime({ now: () => clock })
const seen = []
assert.equal(performance.schedule([
  { atMs: 1100, expression: 'star' }, { atMs: 0, face: { smile: .3 } },
  { atMs: 450, head: { roll: 8 } },
]), true)
clock += 80
assert.equal(performance.tick(beat => seen.push(beat)), 0)
performance.startSpeech()
performance.startSpeech()
assert.equal(performance.tick(beat => seen.push(beat)), 1)
clock += 630
assert.equal(performance.tick(beat => seen.push(beat)), 1)
clock += 800
assert.equal(performance.tick(beat => seen.push(beat)), 1)
assert.deepEqual(seen.map(beat => beat.atMs), [0, 450, 1100])
clock += 200
assert.equal(performance.tick(beat => seen.push(beat)), 0)
performance.schedule([{ atMs: 5000, head: { yaw: 5 } }])
performance.stopSpeech()
clock += 6000
assert.equal(performance.tick(beat => seen.push(beat)), 0)
assert.equal(performance.state().remaining, 0)
performance.schedule([{ atMs: 100, face: { smile: .6 } }], true)
clock += 110
assert.equal(performance.tick(beat => seen.push(beat)), 1)
assert.equal(seen.length, 4)
performance.reset()
assert.equal(performance.state().queued, false)

// The independent spring is clamped on long stalls and never moves in idle.
const spring = createPoseSpring(['yaw', 'pitch', 'roll'])
for (let i = 0; i < 90; i++) spring.step(null, 16)
assert.deepEqual(spring.step(null, 16), { yaw: 0, pitch: 0, roll: 0 })
for (let i = 0; i < 60; i++) spring.step({ yaw: 16, pitch: -5, roll: 4 }, 16)
assert.ok(Math.abs(spring.step({ yaw: 16 }, 16).yaw - 16) < .1)
for (let i = 0; i < 90; i++) spring.step(null, 16)
assert.ok(Math.abs(spring.step(null, 16).yaw) < .05)
const stalled = spring.step({ yaw: -20 }, 20000)
assert.ok(Number.isFinite(stalled.yaw) && Math.abs(stalled.yaw) < 25)
// Model authored exp3 metadata, not free-form text or other role personalities,
// determines whether the expression physically occupies the speaking mouth.
assert.equal(isMouthExclusiveExpression({
  Parameters: [{ Id: 'ParamTongueOut', Value: 1 }]
}), true)
assert.equal(isMouthExclusiveExpression({
  Parameters: [{ Id: 'ParamEyeLSmile', Value: .75 }]
}), false)
assert.equal(isMouthExclusiveExpression({
  Parameters: [{ Id: 'ParamTongueOut', Value: 0 }]
}), false)
assert.equal(isEyeExclusiveExpression({
  Parameters: [{ Id: 'ParamEyeLOpen', Value: .25 }]
}, 'sleepy'), true)
assert.equal(isEyeExclusiveExpression({
  Parameters: [{ Id: 'ParamMouthForm', Value: .25 }]
}, 'blush'), false)
assert.equal(isEyeExclusiveExpression(null, '小狼-星星眼'), true)
let audioMs = 1000
const gate = createSpeechExpressionGate({ now: () => audioMs })
assert.equal(gate.started().reset, false)
gate.sample({ audible: true, pcm: true, level: .6 })
assert.equal(gate.request('吐舌', true), false)
audioMs += 450
assert.equal(gate.tick().play, null,
  'a speaking PCM level cannot trigger tongue expression')
gate.sample({ audible: true, pcm: true, level: .02 })
audioMs += 215
const quietBeat = gate.tick()
assert.equal(quietBeat.play, '吐舌', 'a genuine quiet interval may show a tongue')
assert.equal(gate.state().active, true)
audioMs += 50
assert.equal(gate.sample({ audible: true, pcm: true, level: .5 }).reset, true,
  'mouth expression must clear instantly when vocal PCM resumes')
assert.equal(gate.state().active, false)
assert.equal(gate.request('吐舌', true), false)
gate.sample({ audible: true, pcm: false, level: 0 })
audioMs += 2000
assert.equal(gate.tick().play, null, 'without PCM, do not invent a mid-sentence pause')
assert.equal(gate.finished().play, '吐舌',
  'non-PCM TTS executes the gesture when actual playback finishes')
audioMs += 780
assert.equal(gate.tick().reset, true, 'a tongue cannot remain out for seconds')
gate.reset()
gate.started()
gate.request('吐舌', true)
assert.equal(gate.interrupted().reset, false)
audioMs += 2000
assert.equal(gate.finished().play, null,
  'interrupted utterance may not replay an old tongue cue')
gate.reset()
assert.equal(gate.request('吐舌', false), true,
  'visual-only, silent reactions remain possible')
assert.equal(gate.started().reset, true,
  'a new sentence closes an existing tongue before playing sound')
assert.ok(stage.includes('mouthExpressionGate.tick()'))
assert.ok(stage.includes('!eyePoseBusy'))
assert.ok(stage.includes('voiceLevel: pcmAvailable ? mouth : null'))
assert.ok(stage.includes('if (playing) {\n      activatePendingSpeechMoment()'))
assert.ok(stage.includes('mouthExpressionGate.finished()'))
assert.ok(stage.includes('mouthExpressionGate.interrupted()'))
assert.ok(stage.includes("setParameter(core, 'ParamTongueOut', 0)"))
assert.ok(stage.includes('const audibleMouth = playing && (!pcmAvailable || mouth > .055)'))
assert.ok(stage.includes('1600 / Math.max(1, root.clientWidth, root.clientHeight)'))
console.log('Cubism, audio-expression gating, AIRI layers and transport checks passed')
