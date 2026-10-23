import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { FACE_BINDINGS, BODY_BINDINGS, createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { createPerformanceRuntime } from '../src/speech-performance.mjs'
import { createSpeechExpressionGate } from '../src/speech-expression-gate.mjs'

// Execute the actual Android-facing stage functions, including replacement,
// voice gating and authored motions. No mock reimplementation of the player.
const source = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
const entries = source.slice(source.indexOf('function applyPerformanceBeat('),
  source.indexOf('// Apply at the LAST mutable stage')) +
  source.slice(source.indexOf('function applyFinalParameters('),
    source.indexOf('function installFinalParameterControl(')) +
  source.slice(source.indexOf('function activatePendingSpeechMoment('),
    source.indexOf('// Debug option: mirror decoded WebGL frames')) +
  source.slice(source.indexOf('function resetSpeechExpression('),
    source.indexOf('window.LuluLive2D ='))
let clock = 1000
const expressions = []
const motions = []
const parameters = new Set([...Object.values(FACE_BINDINGS).flat(), ...Object.values(BODY_BINDINGS)])
const manager = { expressionManager: {
  getExpressionIndex: name => ['star', 'tongue'].indexOf(name), resetExpression: () => {},
}, state: {}, stopAllMotions: () => {}, isFinished: () => false }
const bridge = {
  Date: { now: () => clock }, document: { addEventListener: () => {} },
  model: { internalModel: { coreModel: {}, motionManager: manager },
    expression: name => { expressions.push(name); return true },
    motion: (group, index) => { motions.push([group, index]); return true },
  },
  performanceRuntime: createPerformanceRuntime({ now: () => clock }),
  mouthExpressionGate: createSpeechExpressionGate({ now: () => clock }),
  FACE_BINDINGS, BODY_BINDINGS,
  hasParameter: (_core, id) => parameters.has(id),
  notify: () => {}, clearTouchCompanion: () => {}, setPaused: () => {},
  mouthExclusiveExpressions: new Set(['tongue']),
  playing: false, mouth: 0, pcmAvailable: false, listening: false,
  pendingExpressionName: null, pendingSpeechParts: null,
  activeExpressionName: null, activeTouchExpression: null, activeExpressionStartedAt: 0,
  activeExpressionIsPreview: false, lastSpeechSoundAt: 0, lastRealMouthSignalAt: 0,
  speakingBeganAt: 0, fallbackSpeechSeed: 0, lastMouthSignal: 0,
  smoothedMouthOpen: 0, mouthReleaseUntil: 0, authoredMotion: null, motionRevision: 0,
  headRequest: null, headRequestEndAt: 0, bodyRequest: null, bodyRequestEndAt: 0,
  faceRequest: null, faceRequestStartedAt: 0, faceRequestEndAt: 0,
  seconds: 0, paused: false, performance: { now: () => clock },
  tickTactileContact: () => {}, tactileExpressionLayer: { update: () => null },
  activeTouchExpressionUntil: 0, touchReflex: null, physicalState: { energy: 78, sleepPressure: 22 },
  eyeLockedExpressions: new Set(['star']), proceduralBlinkEnabled: false, blinkProgress: -1,
}
vm.createContext(bridge)
vm.runInContext(entries, bridge)
const first = { head: { roll: 6 }, body: { yaw: 2 }, face: { smile: .4 },
  motion: { group: 'Gesture', index: 0 },
  beats: [{ atMs: 0, head: { roll: 8 }, durationMs: 450 },
    { atMs: 1100, face: { smile: .7 }, durationMs: 400 }],
}
bridge.perform(first)
assert.equal(bridge.headRequest, null)
assert.equal(bridge.bodyRequest, null)
assert.equal(bridge.faceRequest, null)
assert.equal(motions.length, 0, 'motion must wait for actual TTS, just like the face')
clock += 900
bridge.onPlaybackEvent('playing')
await Promise.resolve()
assert.equal(bridge.headRequest.roll, 6)
assert.equal(bridge.bodyRequest.yaw, 2)
assert.equal(bridge.faceRequest.smile, .4)
assert.equal(motions.length, 1)
assert.equal(bridge.authoredMotion.started, true)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
assert.equal(bridge.headRequest.roll, 8)
assert.equal(bridge.headRequestEndAt, clock + 450)
bridge.onPlaybackEvent('playing')
assert.equal(motions.length, 1, 'duplicate audio callbacks cannot restart an action')

// A new expression-only decision cancels ALL old unplayed beats and poses.
bridge.perform({ expression: 'star' })
assert.equal(bridge.headRequest, null)
assert.equal(bridge.bodyRequest, null)
assert.equal(bridge.faceRequest, null)
assert.equal(expressions.at(-1), 'star')
clock += 2000
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
assert.equal(bridge.faceRequest, null)

bridge.perform({ head: { pitch: -4 }, body: { yaw: 3 }, face: { smile: -.2 } })
const beforeFinish = bridge.faceRequestEndAt
clock += 100
bridge.onPlaybackEvent('finished')
assert.equal(bridge.headRequest.pitch, -4, 'short speech gets a settling tail')
assert.equal(bridge.bodyRequest.yaw, 3)
assert.equal(bridge.faceRequest.smile, -.2)
assert.equal(bridge.faceRequestEndAt, clock + 900)
assert.ok(bridge.faceRequestEndAt < beforeFinish)

// A silent character decision is visible immediately, with no fake speech.
bridge.perform({ beats: [{ atMs: 0, body: { roll: 2 }, durationMs: 300 }] }, true)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
assert.equal(bridge.bodyRequest.roll, 2)
assert.equal(bridge.bodyRequestEndAt, clock + 300)
assert.equal(bridge.playing, false)
bridge.onPlaybackEvent('interrupted')
assert.equal(bridge.headRequest, null)
assert.equal(bridge.bodyRequest, null)
assert.equal(bridge.faceRequest, null)
bridge.perform({ motion: { group: 'Gesture', index: 1 }, beats: first.beats })
bridge.onPlaybackEvent('interrupted')
bridge.onPlaybackEvent('playing')
assert.equal(motions.length, 1, 'cancelled pending motion cannot leak into the next utterance')
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)

// Mouth-occupying expressions retain the real PCM/finished gate.
bridge.perform({ expression: 'tongue', face: { smile: .2 } })
assert.equal(expressions.at(-1), 'star')
bridge.onPlaybackEvent('finished')
assert.equal(expressions.at(-1), 'tongue')

// The actual final-frame callback expires leases and protects authored star
// eyelids even when combined with a semantic microexpression.
bridge.clearSpeechMoment()
const finalValues = new Map([...parameters].map(id => [id, id.includes('Open') ? 1 : 0]))
bridge.getParameter = (_core, id) => finalValues.get(id)
bridge.setParameter = (_core, id, value) => finalValues.set(id, value)
bridge.visualLayers = createVisualLayerRuntime({ has: bridge.hasParameter,
  read: bridge.getParameter, write: bridge.setParameter })
bridge.perform({ beats: [{ atMs: 0, face: { smile: .4 }, body: { yaw: 2 }, durationMs: 350 }] }, true)
bridge.applyFinalParameters({}, 16)
assert.equal(bridge.bodyRequest.yaw, 2)
assert.equal(bridge.faceRequest.smile, .4)
clock += 400
bridge.applyFinalParameters({}, 16)
assert.equal(bridge.bodyRequest, null)
assert.equal(bridge.faceRequest, null)
bridge.perform({ expression: 'star', face: { smile: .4, eyeOpen: .1, eyeSmile: .8 } }, true)
for (let i = 0; i < 40; i++) { clock += 16; bridge.applyFinalParameters({}, 16) }
assert.equal(finalValues.get('ParamEyeLOpen'), 1)
assert.equal(finalValues.get('ParamEyeROpen'), 1)
assert.equal(finalValues.get('ParamEyeLSmile'), 0)
assert.ok(finalValues.get('ParamMouthForm') > .35)

// Speaking alone must not create a smile. Authored motion files own their
// actual head/body/gaze unless this decision selects a specific override.
const values = new Map([
  ['ParamMouthForm', -.32], ['ParamAngleX', 15], ['ParamBodyAngleX', -7],
  ['ParamEyeBallX', .6], ['ParamAngleX2', 4],
])
const writes = []
const rig = createVisualLayerRuntime({ has: (_c, id) => values.has(id),
  read: (_c, id) => values.get(id),
  write: (_c, id, value) => { values.set(id, value); writes.push(id) },
})
for (let i = 0; i < 60; i++) rig.update({}, { deltaMs: 16, seconds: i * .016, speaking: true })
assert.equal(values.get('ParamMouthForm'), -.32, 'serious speech cannot be auto-smiled over')
values.set('ParamAngleX', 15)
values.set('ParamBodyAngleX', -7)
values.set('ParamEyeBallX', .6)
writes.length = 0
for (let i = 0; i < 60; i++) rig.update({}, {
  deltaMs: 16, seconds: i * .016, speaking: true, authoredMotion: true,
})
for (const id of ['ParamAngleX', 'ParamBodyAngleX', 'ParamEyeBallX']) {
  assert.ok(!writes.includes(id), 'authored motion channel overwritten: ' + id)
}
for (let i = 0; i < 60; i++) rig.update({}, {
  deltaMs: 16, seconds: i * .016, authoredMotion: true,
  head: { yaw: -5 }, body: { yaw: 2 }, face: { gazeX: -.25 },
})
assert.ok(Math.abs(values.get('ParamAngleX') + 5) < .1)
assert.ok(Math.abs(values.get('ParamBodyAngleX') - 2) < .1)
assert.ok(Math.abs(values.get('ParamEyeBallX') + .25) < .02)
// Authored movement underneath touch must return via a crossfade, not snap.
const handoffValues = new Map([['ParamAngleX', 7], ['ParamBodyAngleX', -3]])
const handoffWrites = []
const handoff = createVisualLayerRuntime({
  has: (_c, id) => handoffValues.has(id),
  read: (_c, id) => handoffValues.get(id),
  write: (_c, id, value) => { handoffValues.set(id, value); handoffWrites.push(id) },
})
for (let i = 0; i < 65; i++) {
  handoffValues.set('ParamAngleX', 7)
  handoffValues.set('ParamBodyAngleX', -3)
  handoff.update({}, { deltaMs: 16, seconds: i * .016, authoredMotion: true,
    touchReflex: { yaw: -11, pitch: 0, roll: 0,
      body: { yaw: 6, pitch: 0, roll: 0 } } })
}
const heldHead = handoffValues.get('ParamAngleX')
const heldBody = handoffValues.get('ParamBodyAngleX')
handoffValues.set('ParamAngleX', 7)
handoffValues.set('ParamBodyAngleX', -3)
handoff.update({}, { deltaMs: 16, seconds: 1.1, authoredMotion: true })
assert.ok(Math.abs(handoffValues.get('ParamAngleX') - heldHead) < 1,
  'head must not snap to authored center after touch')
assert.ok(Math.abs(handoffValues.get('ParamBodyAngleX') - heldBody) < 1,
  'body must not snap to authored center after touch')
for (let i = 0; i < 100; i++) {
  handoffValues.set('ParamAngleX', 7)
  handoffValues.set('ParamBodyAngleX', -3)
  handoff.update({}, { deltaMs: 16, seconds: 1.2 + i * .016, authoredMotion: true })
}
handoffWrites.length = 0
handoffValues.set('ParamAngleX', 7)
handoffValues.set('ParamBodyAngleX', -3)
handoff.update({}, { deltaMs: 16, seconds: 4, authoredMotion: true })
assert.ok(!handoffWrites.includes('ParamAngleX') && !handoffWrites.includes('ParamBodyAngleX'),
  'fully released authored motion must regain ownership')
console.log('Live2D ordinary conversation performance, audio timing, cancellation and authored motion ownership passed')
