import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { FACE_BINDINGS, BODY_BINDINGS, createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { createPerformanceRuntime } from '../src/speech-performance.mjs'
import { createMotionTransition } from '../src/motion-transition.mjs'
import { createSpeechExpressionGate } from '../src/speech-expression-gate.mjs'
import { createSpeechMouthRuntime } from '../src/speech-mouth.mjs'
import { touchExpressionCategory } from '../src/touch-reflex.mjs'

// Execute the actual Android-facing stage functions, including replacement,
// voice gating and authored motions. No mock reimplementation of the player.
const source = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
const entries = source.slice(source.indexOf('function applyPerformanceBeat('),
  source.indexOf('function applyFinalParameters(')) +
  source.slice(source.indexOf('function applyFinalParameters('),
    source.indexOf('function installFinalParameterControl(')) +
  source.slice(source.indexOf('function activatePendingSpeechMoment('),
    source.indexOf('// Debug option: mirror decoded WebGL frames')) +
  source.slice(source.indexOf('function setPaused('),
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
  motionTransition: createMotionTransition({ read: () => null, write: () => {}, now: () => clock }),
  mouthExpressionGate: createSpeechExpressionGate({ now: () => clock }),
  speechMouth: createSpeechMouthRuntime({
    has: () => false, write: () => false,
  }), wolfRigActive: false,
  FACE_BINDINGS, BODY_BINDINGS, touchExpressionCategory,
  touchExpressionNames: ['star', 'heart'],
  hasParameter: (_core, id) => parameters.has(id),
  notify: () => {}, clearTouchCompanion: () => {}, setPaused: () => {},
  mouthExclusiveExpressions: new Set(['tongue']),
  playing: false, mouth: 0, pcmAvailable: false, listening: false, thinking: false,
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
// The production stage receives the actual call's model-pending state.
bridge.update({ speaking: false, listening: false, thinking: true, mouth: 0 })
assert.equal(bridge.thinking, true)
bridge.update({ speaking: false, listening: true, thinking: false, mouth: 0 })
assert.equal(bridge.thinking, false)
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
bridge.perform({ expression: 'heart' })
assert.equal(expressions.at(-1), 'star',
  'heart-eye selection must keep the motion but use the available star eyes')
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

// Audio-clock recovery: a 500ms dropped video frame must not resurrect a
// 100ms pose or extend the next beat's lease beyond its spoken time.
bridge.clearSpeechMoment()
bridge.perform({ beats: [
  { atMs: 0, head: { roll: 22 }, durationMs: 100 },
  { atMs: 400, head: { roll: 5 }, durationMs: 600 },
] }, true)
clock += 500
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1,
  'only the still-live score beat may play after a dropped render frame')
assert.equal(bridge.headRequest.roll, 5)
assert.equal(bridge.headRequestEndAt, clock + 500,
  'the live beat keeps only its remaining audio-clock lease')
bridge.clearSpeechMoment()
bridge.perform({ beats: [
  { atMs: 0, head: { roll: 20 }, durationMs: 90 },
] }, true)
clock += 200
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
assert.equal(bridge.headRequest, null,
  'an expired score cannot restart a head gesture after a long frame')

// Speaking mouth has the same wall-clock cadence on a 15Hz native mirror and
// a 120Hz GPU display. The old 50ms cap discarded a quarter of each 15Hz
// frame and made both the first syllable and the release visibly late.
function sampleMouth(fps) {
  const frame = 1000 / fps
  const values = new Map()
  const mouth = createSpeechMouthRuntime({
    has: () => true, write: (_core, id, value) => values.set(id, value),
  })
  const samples = new Map()
  for (let i = 1; i <= Math.round(1200 / frame); i++) {
    mouth.update({}, {
      deltaMs: frame, speaking: i <= Math.round(400 / frame),
      pcmAvailable: true, level: .8,
    })
    const ms = Math.round(i * frame)
    if ([200, 400, 600, 1200].includes(ms))
      samples.set(ms, values.get('ParamMouthOpenY') ?? 0)
  }
  return samples
}
const mouthCurves = [15, 30, 60, 120].map(sampleMouth)
for (const ms of [200, 400, 600, 1200]) {
  const values = mouthCurves.map(curve => curve.get(ms))
  assert.ok(values.every(Number.isFinite), 'all frame rates must sample the mouth')
  assert.ok(Math.max(...values) - Math.min(...values) < .003,
    'lip opening/release must not drift across video frame rates at ' + ms + 'ms')
}

// Exact audible TTS fragment starts, not elapsed time guessed from words,
// are the only authority for model-authored phrase movements.
bridge.onPlaybackEvent('interrupted')
bridge.perform({ visualSequence: 123, beats: [
  { segmentIndex: 0, atMs: 0, head: { roll: 4 }, durationMs: 250 },
  { segmentIndex: 1, atMs: 0, face: { smile: .6 }, durationMs: 330 },
] })
bridge.onPlaybackEvent('playing', 0, 122)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0,
  'old utterance must not trigger a new visual choice')
bridge.onPlaybackEvent('finished', 0, 122)
bridge.onPlaybackEvent('playing', 0, 123)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
assert.equal(bridge.headRequest.roll, 4)
bridge.onPlaybackEvent('playing', 0, 123)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0,
  'duplicate playback-start callback must not restart a gesture')
bridge.onPlaybackEvent('finished', 0, 123)
bridge.onPlaybackEvent('playing', 1, 123)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
assert.equal(bridge.faceRequest.smile, .6,
  'second phrase should start its OWN action when its audio becomes audible')
bridge.onPlaybackEvent('interrupted', 1, 123)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
bridge.onPlaybackEvent('playing', 1, 123)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0,
  'cancelled turn may not resurrect the former phrase')

// A model-selected original Motion3 at an exact spoken segment is not
// synthesized from face/head angles, and cannot fire on an earlier segment.
bridge.onPlaybackEvent('interrupted')
const beforeMotion = motions.length
bridge.perform({ visualSequence: 987, beats: [
  { segmentIndex: 0, atMs: 0, motion: { group: 'LuluWolf', index: 17 } },
  { segmentIndex: 2, atMs: 0, motion: { group: 'LuluWolf', index: 6 } },
] })
bridge.onPlaybackEvent('playing', 0, 986)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
assert.equal(motions.length, beforeMotion,
  'old audio sequence may not trigger current model-authored motions')
bridge.onPlaybackEvent('finished', 0, 986)
bridge.onPlaybackEvent('playing', 0, 987)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
await Promise.resolve()
assert.equal(motions.length, beforeMotion + 1)
assert.equal(motions.at(-1)[0], 'LuluWolf')
assert.equal(motions.at(-1)[1], 17)
bridge.onPlaybackEvent('playing', 0, 987)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
assert.equal(motions.length, beforeMotion + 1,
  'duplicate TTS callback must not repeat the same motion')
bridge.onPlaybackEvent('finished', 0, 987)
bridge.onPlaybackEvent('playing', 1, 987)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
assert.equal(motions.length, beforeMotion + 1,
  'unselected TTS clause must not play another movement')
bridge.onPlaybackEvent('finished', 1, 987)
bridge.onPlaybackEvent('playing', 2, 987)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
await Promise.resolve()
assert.equal(motions.length, beforeMotion + 2)
assert.equal(motions.at(-1)[1], 6)
bridge.onPlaybackEvent('interrupted', 2, 987)
bridge.onPlaybackEvent('playing', 2, 987)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
assert.equal(motions.length, beforeMotion + 2,
  'interrupted turn must not replay an old Motion3')

// Android's coarse amplitude update and exact onStarted event are separate
// Compose effects. A late speaking=false from the previous audio fragment
// must not erase THIS fragment's model-selected motion before the next frame.
bridge.onPlaybackEvent('interrupted')
const beforeCoarseRace = motions.length
bridge.perform({ visualSequence: 2001, beats: [
  { segmentIndex: 0, atMs: 80, motion: { group: 'LuluWolf', index: 30 } },
] })
bridge.onPlaybackEvent('playing', 0, 2001)
bridge.update({ speaking: false, mouth: 0 })
clock += 100
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1,
  'a delayed amplitude snapshot cannot cancel the actual utterance score')
assert.equal(motions.length, beforeCoarseRace + 1)
bridge.onPlaybackEvent('finished', 0, 2001)
clock += 100
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)

// Conversely, a stale speaking=true snapshot cannot start the next turn's
// top-level motion before that turn's real voice event arrives.
bridge.perform({ visualSequence: 2002,
  motion: { group: 'LuluWolf', index: 34 } })
const beforePremature = motions.length
bridge.update({ speaking: true, mouth: .5 })
assert.equal(motions.length, beforePremature)
bridge.onPlaybackEvent('playing', 0, 2002)
assert.equal(motions.length, beforePremature + 1)
bridge.onPlaybackEvent('interrupted', 0, 2002)

// The new decision may be prepared while the OLD TTS fragment is still
// audible. Its top-level Motion3 must wait for its own onStarted, and an old
// interruption must not erase the queued next sentence.
bridge.perform({ visualSequence: 2100, beats: [
  { segmentIndex: 0, atMs: 0, head: { roll: 2 } },
] })
bridge.onPlaybackEvent('playing', 0, 2100, 'old')
const beforeNextVoice = motions.length
bridge.perform({ visualSequence: 2101,
  motion: { group: 'LuluWolf', index: 35 },
  expression: 'star',
  beats: [{ segmentIndex: 0, segmentId: 'new', atMs: 0,
    face: { smile: .3 }, durationMs: 350 }],
})
assert.equal(motions.length, beforeNextVoice,
  'a new authored motion is queued while the older voice is audible')
assert.equal(bridge.pendingSpeechParts.motion.index, 35)
assert.equal(bridge.pendingExpressionName.name, 'star')
bridge.onPlaybackEvent('interrupted', 0, 2100, 'old')
assert.equal(vm.runInContext('scoreVisualSequence', bridge), 2101,
  'old interrupted callback must preserve the next selected score')
assert.equal(bridge.pendingSpeechParts.motion.index, 35,
  'old interruption cannot discard the next Motion3')
bridge.onPlaybackEvent('playing', 0, 2101, 'new')
await Promise.resolve()
assert.equal(motions.length, beforeNextVoice + 1,
  'queued Motion3 begins at the next real TTS fragment')
assert.equal(vm.runInContext('audioVisualSequence', bridge), 2101)
bridge.onPlaybackEvent('finished', 0, 2100, 'old')
assert.equal(vm.runInContext('audioVisualSequence', bridge), 2101,
  'late old finish must not stop the new audible segment')
assert.equal(bridge.playing, true)
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
assert.equal(bridge.faceRequest.smile, .3)
bridge.onPlaybackEvent('playing', 0, 2100, 'old')
assert.equal(vm.runInContext('audioVisualSequence', bridge), 2101,
  'late old start must not rewind the next sentence')
bridge.onPlaybackEvent('finished', 0, 2101, 'wrong-id')
assert.equal(bridge.playing, true,
  'a mismatched segment ID cannot finish the active utterance')
bridge.onPlaybackEvent('finished', 0, 2101, 'new')
assert.equal(bridge.playing, false)

// An ordinary completion before the next TTS start has the same ownership
// rule as interruption: old mouth ends, next selected Motion3 remains queued.
bridge.perform({ visualSequence: 2110 })
bridge.onPlaybackEvent('playing', 0, 2110, 'prior')
bridge.perform({ visualSequence: 2111,
  motion: { group: 'LuluWolf', index: 36 },
})
const beforeFinishedHandoff = motions.length
bridge.onPlaybackEvent('finished', 0, 2110, 'prior')
assert.equal(bridge.pendingSpeechParts.motion.index, 36)
assert.equal(vm.runInContext('scoreVisualSequence', bridge), 2111)
bridge.onPlaybackEvent('playing', 0, 2111, 'later')
await Promise.resolve()
assert.equal(motions.length, beforeFinishedHandoff + 1)
bridge.onPlaybackEvent('interrupted', 0, 2111, 'later')

// Paired plans use immutable part IDs; repeated words cannot bind another
// part's action. Continuing the SAME clip must not restart it each sentence.
bridge.perform({ visualSequence: 3000, beats: [
  { segmentId: 's1', segmentIndex: 0, atMs: 0, motion: { group: 'LuluWolf', index: 28 } },
  { segmentId: 's2', segmentIndex: 1, atMs: 0, transition: 'continue', motion: { group: 'LuluWolf', index: 28 } },
  { segmentId: 's3', segmentIndex: 2, atMs: 0, motion: { group: 'LuluWolf', index: 6 } },
] })
const beforePaired = motions.length
bridge.onPlaybackEvent('playing', 0, 3000, 'wrong')
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 0)
bridge.onPlaybackEvent('finished', 0, 3000, 'wrong')
bridge.onPlaybackEvent('playing', 0, 3000, 's1')
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
await Promise.resolve()
assert.equal(motions.length, beforePaired + 1)
bridge.onPlaybackEvent('finished', 0, 3000, 's1')
bridge.onPlaybackEvent('playing', 1, 3000, 's2')
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
assert.equal(motions.length, beforePaired + 1, 'continue does not restart the active authored clip')
bridge.onPlaybackEvent('finished', 1, 3000, 's2')
bridge.onPlaybackEvent('playing', 2, 3000, 's3')
assert.equal(bridge.performanceRuntime.tick(bridge.applyPerformanceBeat), 1)
await Promise.resolve()
assert.equal(motions.length, beforePaired + 2)
assert.equal(bridge.audioPosition(3000, 1, 's2', 500), false, 'old part clock is rejected')
assert.equal(bridge.audioPosition(3000, 2, 's3', 120), true)
bridge.onPlaybackEvent('interrupted', 2, 3000, 's3')
assert.equal(bridge.audioPosition(3000, 2, 's3', 200), false)

// A late TTS start after finished/interrupted is not a fresh phrase.
// A mismatched immutable part ID must not poison the currently audible one.
bridge.perform({ visualSequence: 3100, beats: [
  { segmentIndex: 0, segmentId: 'first', atMs: 0, head: { roll: 7 } },
  { segmentIndex: 1, segmentId: 'second', atMs: 0, head: { roll: -3 } },
] })
bridge.onPlaybackEvent('playing', 0, 3100, 'wrong')
assert.equal(bridge.playing, false, 'wrong part ID cannot open the TTS clock')
assert.equal(vm.runInContext('audioVisualSequence', bridge), 0)
bridge.onPlaybackEvent('playing', 0, 3100, 'first')
assert.equal(bridge.playing, true)
bridge.onPlaybackEvent('playing', 0, 3100, 'stale')
assert.equal(vm.runInContext('audioSegmentId', bridge), 'first',
  'same-index late start cannot steal an audible segment')
bridge.onPlaybackEvent('finished', 0, 3100, 'first')
assert.equal(bridge.playing, false)
bridge.onPlaybackEvent('playing', 0, 3100, 'first')
assert.equal(bridge.playing, false, 'finished part cannot restart its mouth or score')
bridge.onPlaybackEvent('playing', 1, 3100, 'second')
assert.equal(bridge.playing, true, 'next genuine segment can still begin')
bridge.onPlaybackEvent('interrupted', 1, 3100, 'second')
assert.equal(bridge.playing, false)
bridge.onPlaybackEvent('playing', 2, 3100, 'late')
assert.equal(bridge.playing, false, 'interrupted turn cannot start later segments')
bridge.perform({ visualSequence: 3101, beats: [
  { segmentIndex: 0, segmentId: 'fresh', atMs: 0, head: { roll: 2 } },
] })
bridge.onPlaybackEvent('playing', 0, 3101, 'fresh')
assert.equal(bridge.playing, true, 'new decision is not blocked by the old tombstone')
bridge.onPlaybackEvent('finished', 0, 3101, 'fresh')
assert.equal(bridge.playing, false)

// Android can hide the video stage while an old TTS fragment is audible and
// the next decision is already queued. setPaused must retain the cancelled
// score identity BEFORE zeroing it, or late onStarted resurrects old Motion3.
bridge.touchResponse = { end: () => {} }
bridge.cancelTactileMotion = () => {}
bridge.wolfImpactPresentation = { reset: () => {} }
bridge.app = null
bridge.perform({ visualSequence: 3200, beats: [
  { segmentIndex: 0, segmentId: 'old', atMs: 0, head: { roll: 3 } },
] })
bridge.onPlaybackEvent('playing', 0, 3200, 'old')
bridge.perform({ visualSequence: 3201,
  motion: { group: 'LuluWolf', index: 37 },
  beats: [{ segmentIndex: 0, segmentId: 'queued', atMs: 0,
    head: { roll: -4 } }],
})
const beforeHidden = motions.length
bridge.setPaused(true)
assert.equal(vm.runInContext('finalizedAudio.sequence', bridge), 3201,
  'hidden stage must tombstone the newer queued score, not reset it to zero')
assert.equal(vm.runInContext('finalizedAudio.interrupted', bridge), true)
assert.equal(vm.runInContext('scoreVisualSequence', bridge), 0)
assert.equal(vm.runInContext('audioVisualSequence', bridge), 3200,
  'keep the actual audible fragment identity so its hidden finish can retire it')
bridge.update({ speaking: true, mouth: .8, hasPcmSamples: true })
assert.equal(bridge.mouth, 0, 'hidden coarse PCM cannot animate a cancelled mouth')
bridge.onPlaybackEvent('finished', 0, 3200, 'old')
assert.equal(bridge.playing, false,
  'real audio completion while hidden must release the speaking lease')
assert.equal(vm.runInContext('finalizedAudio.sequence', bridge), 3201,
  'old hidden finish cannot lower the newer cancelled-score tombstone')
bridge.onPlaybackEvent('playing', 0, 3201, 'queued')
bridge.onPlaybackEvent('playing', 0, 3202, 'not-yet-visible')
assert.equal(vm.runInContext('audioVisualSequence', bridge), 0,
  'hidden WebView must ignore all TTS starts, including a later sequence')
bridge.setPaused(false)
bridge.onPlaybackEvent('playing', 0, 3200, 'old')
bridge.onPlaybackEvent('playing', 0, 3201, 'queued')
assert.equal(vm.runInContext('audioVisualSequence', bridge), 0,
  'returning to video cannot resurrect the discarded old/queued turn')
assert.equal(motions.length, beforeHidden,
  'no cancelled Motion3 can restart after a hidden-stage transition')
bridge.perform({ visualSequence: 3202,
  motion: { group: 'LuluWolf', index: 38 },
  beats: [{ segmentIndex: 0, segmentId: 'fresh', atMs: 0,
    head: { roll: 2 } }],
})
bridge.onPlaybackEvent('playing', 0, 3202, 'fresh')
await Promise.resolve()
assert.equal(motions.length, beforeHidden + 1,
  'the next visible, actually audible turn may still start its own Motion3')
bridge.onPlaybackEvent('interrupted', 0, 3202, 'fresh')

// A hidden stage must discard queued top-level Motion3 and expression too.
// A coarse speaking snapshot can arrive after the score has been cleared.
bridge.perform({ visualSequence: 3300, beats: [
  { segmentIndex: 0, segmentId: 'prior', atMs: 0, head: { roll: 3 } },
] })
bridge.onPlaybackEvent('playing', 0, 3300, 'prior')
bridge.perform({ visualSequence: 3301,
  motion: { group: 'LuluWolf', index: 39 }, expression: 'star',
  beats: [{ segmentIndex: 0, segmentId: 'queued', atMs: 0, head: { roll: -6 } }],
})
assert.equal(bridge.pendingSpeechParts.motion.index, 39)
assert.equal(bridge.pendingExpressionName.name, 'star')
const beforeHiddenQueue = motions.length
bridge.setPaused(true)
assert.equal(bridge.pendingSpeechParts, null, 'hidden stage drops queued Motion3')
assert.equal(bridge.pendingExpressionName, null, 'hidden stage drops queued expression')
assert.equal(bridge.performanceRuntime.state().remaining, 0)
assert.equal(bridge.perform({ visualSequence: 3302,
  motion: { group: 'LuluWolf', index: 40 } }), false,
  'hidden stage must not accept new performance cues')
bridge.onPlaybackEvent('playing', 0, 3302, 'hidden')
bridge.update({ speaking: true, mouth: .3 })
bridge.setPaused(false)
assert.equal(bridge.playing, true,
  'still-audible TTS may retain its PCM mouth after returning to video')
assert.equal(bridge.performanceRuntime.state().speaking, false,
  'returning to video must not replay the hidden choreography')
bridge.onPlaybackEvent('finished', 0, 3300, 'prior')
assert.equal(bridge.playing, false,
  'old audible TTS completion after resume must close its speaking lease')
assert.equal(vm.runInContext('finalizedAudio.sequence', bridge), 3301,
  'late old finish after resume must preserve the queued-turn tombstone')
bridge.update({ speaking: true, mouth: .3 })
assert.equal(bridge.playing, false,
  'coarse speaking cannot revive the old fragment after visibility returns')
bridge.onPlaybackEvent('playing', 0, 3301, 'queued')
assert.equal(motions.length, beforeHiddenQueue,
  'late coarse speaking updates cannot resurrect discarded Motion3')
assert.equal(bridge.pendingExpressionName, null)
bridge.perform({ visualSequence: 3303,
  motion: { group: 'LuluWolf', index: 41 },
  beats: [{ segmentIndex: 0, segmentId: 'fresh', atMs: 0, head: { roll: 1 } }],
})
bridge.onPlaybackEvent('playing', 0, 3303, 'fresh')
await Promise.resolve()
assert.equal(motions.length, beforeHiddenQueue + 1,
  'new visible turn starts on its own TTS event')
bridge.onPlaybackEvent('interrupted', 0, 3303, 'fresh')

// Versioned audio events own speaking even when Compose sends coarse status
// ahead of onStarted, behind onFinished, or during the next spoken fragment.
bridge.perform({ visualSequence: 3400, beats: [
  { segmentIndex: 0, segmentId: 'first', atMs: 0, head: { roll: 2 } },
] })
bridge.update({ speaking: true, listening: true, mouth: .8, hasPcmSamples: true })
assert.equal(bridge.playing, false, 'coarse speaking must not pre-open the mouth')
assert.equal(bridge.performanceRuntime.state().speaking, false)
bridge.onPlaybackEvent('playing', 0, 3400, 'first')
assert.equal(bridge.playing, true, 'actual first audible sample starts speaking')
bridge.update({ speaking: false, listening: false, mouth: 0, hasPcmSamples: false })
assert.equal(bridge.playing, true, 'late coarse false cannot close audible speech')
bridge.onPlaybackEvent('finished', 0, 3400, 'first')
bridge.update({ speaking: true, mouth: .8 })
assert.equal(bridge.playing, false, 'late coarse true cannot revive a finished segment')
bridge.perform({ visualSequence: 3401, beats: [
  { segmentIndex: 0, segmentId: 'second', atMs: 0, head: { roll: -2 } },
  { segmentIndex: 1, segmentId: 'third', atMs: 0, head: { roll: 3 } },
] })
bridge.update({ speaking: true, mouth: .8 })
assert.equal(bridge.playing, false, 'next decision still waits for its own onStarted')
bridge.onPlaybackEvent('playing', 0, 3401, 'second')
assert.equal(bridge.playing, true)
clock += 80
bridge.onPlaybackEvent('playing', 1, 3401, 'third')
assert.equal(bridge.speakingBeganAt, clock,
  'each actual segment start refreshes lip timing, even without a false status')
bridge.update({ speaking: false, mouth: 0 })
assert.equal(bridge.playing, true)
bridge.onPlaybackEvent('interrupted', 1, 3401, 'third')
bridge.update({ speaking: true, mouth: .8 })
assert.equal(bridge.playing, false, 'interrupted versioned audio cannot reopen via coarse status')
bridge.perform({ visualSequence: 0, head: { roll: 1 } })
bridge.update({ speaking: true, mouth: .3 })
assert.equal(bridge.playing, true, 'a new explicit legacy cue restores coarse provider behavior')
bridge.setPaused(true)
bridge.update({ speaking: true, mouth: .8, hasPcmSamples: true })
assert.equal(bridge.playing, false,
  'unversioned audio cannot remain falsely audible after the stage is hidden')
assert.equal(bridge.mouth, 0, 'hidden legacy PCM cannot open the mouth')
bridge.setPaused(false)
bridge.update({ speaking: true, mouth: .3 })
assert.equal(bridge.playing, true,
  'legacy coarse providers may resume from a fresh visible snapshot')
bridge.update({ speaking: false, mouth: 0 })

// Once actual audio positions exist, wall time and a stalled HTTP stream do
// not advance a future beat. No character-count timestamps are synthesized.
const audioClock = createPerformanceRuntime({ now: () => clock })
audioClock.schedule([{ atMs: 250, motion: { group: 'Gesture', index: 0 } }])
audioClock.startSpeech()
audioClock.audioPosition(100)
clock += 1000
assert.equal(audioClock.tick(() => {}), 0)
assert.equal(audioClock.audioPosition(50), false)
audioClock.audioPosition(260)
assert.equal(audioClock.tick(() => {}), 1)
assert.equal(audioClock.tick(() => {}), 0)

// A new clip starts at the last displayed joints, blends to the real
// incoming motion, and touches no unowned mouth/eye/arm parameters.
const blended = new Map([['ParamAngleX', 12], ['ParamBodyAngleX', -4], ['ParamMouthOpenY', .7]])
const transitionWrites = []
const transition = createMotionTransition({ now: () => clock,
  read: (_core, id) => blended.get(id),
  write: (_core, id, value) => { blended.set(id, value); transitionWrites.push(id) },
})
transition.capture({}, blended.keys())
transition.begin(['ParamAngleX', 'ParamBodyAngleX'], 200)
blended.set('ParamAngleX', -10)
blended.set('ParamBodyAngleX', 3)
transition.update({})
assert.equal(blended.get('ParamAngleX'), 12)
clock += 100
blended.set('ParamAngleX', -10)
transition.update({})
assert.equal(blended.get('ParamAngleX'), 1)
clock += 100
blended.set('ParamAngleX', -10)
transition.update({})
assert.equal(blended.get('ParamAngleX'), -10)
assert.equal(blended.get('ParamMouthOpenY'), .7)
assert.ok(!transitionWrites.includes('ParamMouthOpenY'))
transitionWrites.length = 0
transition.begin(['ParamAngleX'], 200)
transition.cancel()
transition.update({})
assert.equal(transitionWrites.length, 0)


// The actual stage expression() is asynchronous for some imported exp3
// assets. A rejected OLD request with the SAME name must not reset the next
// spoken segment's eyes, while a current rejection must release them.
const originalExpression = bridge.model.expression
const originalNotify = bridge.notify
const originalResetExpression = manager.expressionManager.resetExpression
const expressionDiagnostics = []
let expressionResets = 0
manager.expressionManager.resetExpression = () => { expressionResets++ }
bridge.notify = (type, detail) => {
  if (type === 'diagnostic') expressionDiagnostics.push(detail)
}
let rejectExpression = null
bridge.model.expression = () => new Promise((_resolve, reject) => {
  rejectExpression = reject
})
bridge.perform({ expression: 'star' }, true)
const rejectOldExpression = rejectExpression
bridge.perform({ expression: 'star' }, true)
const rejectCurrentExpression = rejectExpression
const resetsAfterReplacement = expressionResets
rejectOldExpression(new Error('obsolete exp3 load'))
// The VM's Promise and the host's injected Promise have different realms.
// Flush the actual rejection chain rather than assuming two microtasks suffice.
await new Promise(resolve => setImmediate(resolve))
assert.equal(bridge.activeExpressionName, 'star',
  'old same-name exp3 failure must not release the new segment expression')
assert.equal(expressionResets, resetsAfterReplacement,
  'stale exp3 failure must not reset the newer Cubism expression manager')
assert.ok(!expressionDiagnostics.some(line => line.includes('obsolete exp3 load')),
  'stale exp3 failure must not be reported as current playback failure')
rejectCurrentExpression(new Error('current exp3 load'))
await new Promise(resolve => setImmediate(resolve))
assert.equal(bridge.activeExpressionName, null,
  'real current exp3 failure releases eye ownership')
assert.equal(expressionResets, resetsAfterReplacement + 1)
assert.ok(expressionDiagnostics.some(line => line.includes('current exp3 load')),
  'real current failure still has a diagnostic receipt')

// An exp3 wrapper can also return false without throwing. The current
// segment must release its eye ownership instead of keeping a fake star face.
bridge.model.expression = () => false
bridge.perform({ expression: 'star' }, true)
await new Promise(resolve => setImmediate(resolve))
assert.equal(bridge.activeExpressionName, null,
  'a current false exp3 result cannot lock eye ownership')
assert.equal(expressionResets, resetsAfterReplacement + 2)
assert.ok(expressionDiagnostics.some(line => line.includes('表情未切换：star')))

// Some model wrappers throw synchronously rather than returning a Promise.
// The real stage must handle that without aborting the render/score callback.
bridge.model.expression = () => { throw new Error('synchronous exp3 failure') }
bridge.perform({ expression: 'star' }, true)
assert.equal(bridge.activeExpressionName, null,
  'synchronous Cubism failure must not latch an unplayed eye expression')
assert.ok(expressionDiagnostics.some(line => line.includes('synchronous exp3 failure')))
bridge.model.expression = originalExpression
bridge.notify = originalNotify
manager.expressionManager.resetExpression = originalResetExpression

// The actual final-frame stage clock must not run 25% slow on a 15fps
// native video bridge. Both breathing phase and reflex blink use elapsed
// render time; a long resume gap is still bounded to avoid a phase jump.
bridge.playing = false
bridge.activeExpressionName = null
bridge.faceRequest = null
bridge.touchReflex = null
bridge.proceduralBlinkEnabled = true
for (const fps of [15, 30, 60, 120]) {
  bridge.seconds = 0
  bridge.blinkClock = 10
  bridge.blinkProgress = -1
  for (let i = 0; i < fps; i++) bridge.applyFinalParameters({}, 1000 / fps)
  assert.ok(Math.abs(bridge.seconds - 1) < .0001,
    `breathing clock must advance one second at ${fps}fps; got ${bridge.seconds}`)
  assert.ok(Math.abs(bridge.blinkClock - 9) < .0001,
    `blink countdown must advance one second at ${fps}fps; got ${bridge.blinkClock}`)
}
bridge.seconds = 0
bridge.blinkClock = 10
bridge.blinkProgress = -1
bridge.applyFinalParameters({}, 600)
assert.ok(Math.abs(bridge.seconds - .25) < .0001,
  'a long resume gap cannot jump the breathing phase by 600ms')
assert.ok(Math.abs(bridge.blinkClock - 9.75) < .0001,
  'a long resume gap cannot skip the entire blink countdown')
bridge.proceduralBlinkEnabled = false

console.log('Live2D ordinary conversation performance, audio timing, cancellation, authored motion and low-FPS clock passed')
