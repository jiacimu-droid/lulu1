import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { resolveTouchRegion, sanitizeTouchCalibration, calibratedRegion,
  TOUCH_REGIONS, TOUCH_ZONE_DEFAULTS, touchZoneArea } from '../src/touch-hit.mjs'
import { touchReflexFor, touchExpressionFor, touchExpressionWeight,
  touchExpressionCategory, touchCompanionOverlayFor } from '../src/touch-reflex.mjs'
import { createTouchResponseRuntime, touchFamily, isImpact } from '../src/touch-response.mjs'
import { createTouchExpressionLayer } from '../src/touch-expression-layer.mjs'
import { createVisualLayerRuntime } from '../src/visual-layers.mjs'

const bounds = { x: 60, y: -160, width: 250, height: 750 }
const hit = (x, y, hits = [], authoredAreas = 0, calibration = null) =>
  resolveTouchRegion({ x, y, hits, authoredAreas, bounds, calibration })
assert.equal(hit(190, -60), 'eye-right')
assert.equal(hit(190, 120), 'neck')
assert.equal(hit(190, -120), 'hair')
assert.equal(hit(190, -20), 'nose')
assert.equal(hit(190, 20), 'mouth')
assert.equal(hit(190, 165), 'collarbone')
assert.equal(hit(190, 220), 'chest')
assert.equal(hit(63, -140), null)
assert.equal(hit(500, 140), null)
assert.equal(hit(180, 90, [], 2), null)
assert.equal(hit(180, 90, ['Head'], 2), 'cheek-left')
assert.equal(hit(180, 90, ['手臂'], 2), 'arm-left', 'Chinese arm must not match generic hand first')
assert.equal(hit(180, 90, ['兽耳'], 2), 'beast-ear-left')
assert.equal(hit(180, 90, ['人耳'], 2), 'ear-left')
assert.equal(hit(Infinity, 90, ['Head'], 2), null)
const calibrated = sanitizeTouchCalibration({
  'ear-left': { u: .4, v: .15, rx: .06, ry: .07 },
  nose: { u: 20, v: 8, rx: -1, ry: .2 },
  background: { u: .5, v: .5, rx: .1, ry: .1 },
})
assert.deepEqual(Object.keys(calibrated), ['ear-left'])
assert.equal(hit(160, -47.5, [], 2, calibrated), 'ear-left')
assert.equal(TOUCH_REGIONS.length, 21)
assert.deepEqual(Object.keys(TOUCH_ZONE_DEFAULTS).sort(), [...TOUCH_REGIONS].sort())
const tiny = touchZoneArea('nose', bounds, { u: .5, v: .205 }, .003)
assert.equal(tiny.rx * bounds.width, .75)
assert.equal(tiny.ry * bounds.height, .75, 'minimum nose remains circular on a tall model')
const tinySaved = sanitizeTouchCalibration(JSON.parse(JSON.stringify({ nose: tiny })))
assert.ok(tinySaved.nose, 'tiny calibration survives persistence and reload')
assert.equal(hit(185, -6.25, ['Nose'], 2, tinySaved), 'nose')
assert.equal(hit(188, -6.25, ['Nose'], 2, tinySaved), null,
  'shrinking must replace the old authored nose boundary too')
assert.equal(calibratedRegion(.5, .205, { nose: tiny,
  'cheek-left': { u: .5, v: .205, rx: .2, ry: .2 } }), 'nose',
  'a small precise zone wins over a larger overlapping face zone')
for (const zone of TOUCH_REGIONS) {
  const area = touchZoneArea(zone, bounds)
  assert.ok(area.rx > 0 && area.ry > 0)
  assert.equal(hit(bounds.x + area.u * bounds.width,
    bounds.y + area.v * bounds.height, [], 2, { [zone]: area }), zone)
}
const kotlinZones = readFileSync(new URL('../../../app/src/main/java/com/jiacimu/lulu/Live2DTouchZones.kt', import.meta.url), 'utf8')
for (const zone of TOUCH_REGIONS) assert.ok(kotlinZones.includes('"' + zone + '" to '), zone + ' crosses the native bridge')

const wolf = ['兽耳', '吐舌', '星星眼', '流泪', '爱心眼', '耳机', '脸红', '脸黑', '麦']
const exp3 = id => ({ Parameters: [{ Id: id, Value: 1, Blend: 'Add' }] })
const metadata = { '爱心眼': exp3('bq6'), '星星眼': exp3('bq19'),
  '脸红': exp3('bq4'), '流泪': exp3('bq3'), '脸黑': exp3('bq14') }
for (const name of ['兽耳', '耳机', '麦']) assert.equal(touchExpressionCategory(name), 'appearance')
let time = 1000
const runtime = createTouchResponseRuntime({ now: () => time })
for (let i = 0; i < 100; i++) runtime.snapshot()
assert.equal(runtime.snapshot().shyness, 0, 'MOVE/preview cannot accumulate points')
for (let i = 0; i < 200; i++) runtime.complete('hair', i % 2 ? 'stroke-left' : 'stroke-right')
assert.equal(runtime.snapshot().shyness, 8, 'head-only petting stays a small response')
assert.equal(touchExpressionFor('hair', wolf, 200, 'stroke-left', false, runtime.snapshot()), null)
const headLevel = runtime.snapshot().shyness
runtime.complete('cheek-left', 'stroke-right')
const cheekLevel = runtime.snapshot().shyness
runtime.complete('mouth', 'stroke-left')
assert.ok(runtime.snapshot().shyness > cheekLevel && cheekLevel > headLevel)
for (const region of ['mouth', 'throat', 'collarbone']) {
  runtime.reset()
  const state = runtime.complete(region, 'kiss')
  assert.ok(state.shyness >= 49, region + ' direct kiss is visibly stronger')
  assert.equal(touchExpressionFor(region, wolf, 0, 'kiss', false, state), '脸红')
}
runtime.reset()
runtime.complete('mouth', 'kiss')
let high = runtime.complete('collarbone', 'kiss')
assert.equal(high.shyness, 100)
assert.equal(touchExpressionFor('mouth', wolf, 0, 'kiss', true, high), '爱心眼')
assert.equal(touchExpressionFor('hair', wolf, 0, 'stroke', false, high), '脸红',
  'a high tier does not make ordinary head petting produce heart eyes')
assert.ok(touchExpressionWeight('脸红', { shyness: 20 }) < touchExpressionWeight('脸红', high))
time += 45_000
assert.ok(Math.abs(runtime.snapshot().shyness - 52.6) < 3,
  'separate body contributions naturally decay rather than restarting at each touch')
time += 900_000
assert.ok(runtime.snapshot().shyness < .01)
runtime.reset()
assert.equal(runtime.snapshot().impact, 0)
assert.equal(runtime.complete('background', 'kiss').shyness, 0)

// 21 regions × three tools × three strengths: every cell has a finite,
// tool-specific local response. No emotion roulette or full-face overlays.
let cases = 0
for (const region of TOUCH_REGIONS) {
  for (const force of [.25, .55, .9]) {
    const poses = []
    for (const gesture of ['stroke-right', 'kiss', 'slap']) {
      runtime.reset()
      const state = runtime.complete(region, gesture, force)
      const pose = touchReflexFor(region, gesture, 1, state, 400)
      poses.push(pose)
      const numbers = [pose.yaw, pose.pitch, pose.roll,
        ...Object.values(pose.body), ...Object.values(pose.face), ...Object.values(pose.parameters)]
      assert.ok(numbers.every(Number.isFinite), region + ':' + gesture)
      const expression = touchExpressionFor(region, wolf, 1, gesture, true, state)
      assert.ok(!['兽耳', '耳机', '麦', '吐舌'].includes(expression))
      if (isImpact(gesture)) {
        assert.equal(state.shyness, 0)
        assert.ok(!['脸红', '爱心眼', '星星眼'].includes(expression))
      } else assert.ok(!['流泪', '脸黑'].includes(expression))
      cases++
    }
    for (let i = 1; i < poses.length; i++) assert.notDeepEqual(poses[0], poses[i], region)
  }
}
assert.notEqual(touchFamily('beast-ear-left'), touchFamily('ear-left'))
// Every real touch region must cause at least one observable torso-axis
// request on rigs with body rotation, not only a face expression.
for (const region of TOUCH_REGIONS) {
  const reflex = touchReflexFor(region, 'stroke-right', 1, { force: .65 }, 300)
  assert.ok(Object.values(reflex.body).some(value => Math.abs(value) >= 1),
    region + ' should have a visible coordinated body response')
}
const headLeft = touchReflexFor('cheek-left', 'stroke-right')
const headRight = touchReflexFor('cheek-right', 'stroke-right')
assert.equal(headLeft.yaw, -headRight.yaw)
const early = touchReflexFor('cheek-left', 'slap-right', 0, { force: .55 }, 70)
const later = touchReflexFor('cheek-left', 'slap-right', 0, { force: .55 }, 500)
assert.ok(early.yaw > later.yaw && early.yaw > 6)
assert.ok(Math.abs(early.roll) > Math.abs(early.yaw) * 3,
  'a slapped head should visibly roll at the neck, not sway horizontally')
assert.equal(early.impactMotion, true)
assert.ok(early.face.browY > 0 && later.face.browY < 0)
assert.ok(touchReflexFor('cheek-left', 'slap-left').yaw < 0)
assert.ok(touchReflexFor('cheek-left', 'slap', 0, { force: .9 }).yaw >
  touchReflexFor('cheek-left', 'slap', 0, { force: .25 }).yaw)
const petVariants = [0, 1, 2].map(i =>
  touchReflexFor('cheek-left', 'stroke-right', i, { shyness: 30, force: .55 }, 250))
assert.ok(new Set(petVariants.map(p => p.face.eyeOpen)).size >= 2,
  'soft touches should not show the same eye squint every time')
assert.ok(petVariants.every(p => p.face.eyeOpen >= .45),
  'a caress should not force tightly closed eyes on every gesture')
const unilateral = touchReflexFor('eye-left', 'kiss')
assert.ok(unilateral.face.eyeLeftOpen < .1 && unilateral.face.eyeRightOpen == null)
assert.ok(Object.hasOwn(touchReflexFor('beast-ear-right', 'stroke').parameters, 'se_wuli_x_r'))
runtime.reset()
const impact = runtime.complete('cheek-left', 'slap')
assert.equal(touchExpressionFor('cheek-left', wolf, 0, 'slap', false, impact), '流泪')
for (let i = 0; i < 5; i++) runtime.complete('cheek-left', 'slap')
assert.equal(touchExpressionFor('cheek-left', wolf, 0, 'slap', false, runtime.snapshot()), '流泪',
  'repeated contact should not inevitably turn every response into a dark face')
runtime.complete('cheek-left', 'slap', .9)
assert.equal(touchExpressionFor('cheek-left', wolf, 4, 'slap', false, runtime.snapshot()), '脸黑',
  'a high-force/high-impact reaction may still have an authored dark expression')
assert.equal(touchExpressionFor('hair', ['流泪', '爱心眼', '兽耳'], 99, 'stroke'), null,
  'a missing mild expression must not fall back to an inappropriate one')
assert.equal(touchCompanionOverlayFor('脸红', wolf, metadata, 'kiss', { shyness: 20 }), null)
const extra = touchCompanionOverlayFor('爱心眼', wolf, metadata, 'kiss', high)
assert.equal(extra.id, 'bq4')
assert.ok(extra.value < 1)
assert.equal(touchCompanionOverlayFor('爱心眼', wolf, {
  ...metadata, '爱心眼': exp3('bq4') }, 'kiss', high), null)

// Real additive channels retain baseline, gain intensity gradually, release,
// and never fabricate missing rig parameters or switch accessories.
const values = new Map([['bq4', .07], ['bq3', 0], ['bq6', 0]])
const access = { has: (_c, id) => values.has(id), read: (_c, id) => values.get(id),
  write: (_c, id, value) => { assert.ok(values.has(id)); values.set(id, value) } }
const layer = createTouchExpressionLayer(access)
assert.ok(layer.start({}, '脸红', metadata, null, { shyness: 20 }, 0))
layer.update({}, 200)
assert.ok(values.get('bq4') > .07 && values.get('bq4') < .3)
layer.update({}, 2200)
assert.equal(values.get('bq4'), .07)
assert.ok(layer.start({}, '流泪', metadata, null, impact, 3000, 180))
layer.update({}, 3100)
assert.equal(values.get('bq3'), 0, 'tears wait until after the first recoil')
layer.update({}, 3400)
assert.ok(values.get('bq3') > 0)
layer.clear({})
assert.equal(values.get('bq3'), 0)
assert.equal(layer.start({}, '兽耳', { '兽耳': exp3('qh_1') }, null, high, 5000), false)

const faceValues = new Map([['ParamEyeLOpen', 1], ['ParamEyeROpen', 1]])
const rig = createVisualLayerRuntime({ has: (_c, id) => faceValues.has(id),
  read: (_c, id) => faceValues.get(id), write: (_c, id, value) => faceValues.set(id, value) })
for (let i = 0; i < 45; i++) rig.update({}, { deltaMs: 16, seconds: i * .016, touchReflex: unilateral })
assert.ok(faceValues.get('ParamEyeLOpen') < .1)
assert.ok(faceValues.get('ParamEyeROpen') > .8)
for (let i = 0; i < 100; i++) rig.update({}, { deltaMs: 16, seconds: i * .016 })
assert.ok(faceValues.get('ParamEyeLOpen') > .95 && faceValues.get('ParamEyeROpen') > .95)

// Execute the actual stage entry point with a renderer-only test surface:
// held caresses grow before release, independent of MOVE sampling frequency.
const source = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
const touchSource = source.slice(source.indexOf('function tickTactileContact(tactileNow)'), source.indexOf('\nfunction setPaused(value)'))
let stageTime = 1000
const stageResponse = createTouchResponseRuntime({ now: () => stageTime })
const bridge = {
  model: { getBounds: () => ({ x: 0, y: 0, width: 100, height: 400 }),
    hitTest: () => ['Head'], internalModel: { hitAreas: { Head: {} }, coreModel: {} } },
  root: { clientWidth: 100, clientHeight: 400 },
  previewObserved: true, paused: false, calibrationVisible: false,
  performance: { now: () => stageTime }, touchResponse: stageResponse,
  touchCalibration: {}, calibratedRegion, resolveTouchRegion, touchReflexFor,
  touchExpressionFor, touchCompanionOverlayFor, isImpact,
  actualAvatarPixel: () => true, tactileContact: null,
  touchSequence: 0, touchReflex: null, touchReflexEndAt: 0,
  touchExpressionNames: wolf, touchExpressionMetadata: metadata,
  mouthExclusiveExpressions: new Set(['吐舌']), playing: false,
  activeExpressionName: null, activeTouchExpression: null,
  activeTouchExpressionUntil: 0,
  clearTouchCompanion: () => {},
  tactileExpressionLayer: { start: () => false, retarget: () => {} },
}
vm.createContext(bridge)
vm.runInContext(touchSource, bridge)
bridge.touch(.5, .04, 'down', { phase: 'move', mode: 'hand' })
for (let i = 0; i < 100; i++) bridge.touch(.5, .04, 'stroke-right', { phase: 'move', mode: 'hand' })
assert.equal(stageResponse.snapshot().shyness, 0)
bridge.touch(.5, .04, 'stroke-right', { phase: 'complete', mode: 'hand' })
assert.equal(stageResponse.snapshot().shyness, 0, 'zero elapsed time/travel adds nothing on release')
stageResponse.reset()
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'pat' })
assert.equal(bridge.tactileContact.kind, 'slap')
assert.equal(stageResponse.snapshot().impact, 0)
const completedHit = bridge.touch(.32, .235, 'slap', { phase: 'complete', mode: 'pat', force: .9 })
assert.equal(completedHit.gesture, 'slap')
assert.equal(completedHit.force, .9)
assert.equal(stageResponse.snapshot().shyness, 0)
assert.ok(stageResponse.snapshot().impact > 40)
const beforeMiss = stageResponse.snapshot().impact
bridge.touch(-.1, .235, 'slap', { phase: 'complete', mode: 'pat' })
assert.equal(stageResponse.snapshot().impact, beforeMiss)
bridge.touch(.32, .235, 'cancel')
assert.equal(bridge.tactileContact, null)
bridge.calibrationVisible = true
assert.equal(bridge.touch(.5, .04, 'kiss').hit, false)
assert.equal(stageResponse.snapshot().shyness, 0)

// Long, uninterrupted petting: actual stage frames update the weighted
// blush while the finger is STILL DOWN; release does not add it all again.
bridge.calibrationVisible = false
stageResponse.reset()
const liveValues = new Map([['bq4', 0], ['bq6', 0], ['bq19', 0]])
bridge.tactileExpressionLayer = createTouchExpressionLayer({
  has: (_c, id) => liveValues.has(id), read: (_c, id) => liveValues.get(id),
  write: (_c, id, value) => liveValues.set(id, value),
})
bridge.clearTouchCompanion = () => {
  bridge.tactileExpressionLayer.clear({})
  bridge.activeTouchExpression = null
}
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand' })
for (let i = 1; i <= 100; i++) {
  stageTime += 100
  const x = .32 + .01 * (i % 2 ? 1 : -1)
  bridge.touch(x, .235, i % 2 ? 'stroke-right' : 'stroke-left', { phase: 'move', mode: 'hand' })
  bridge.tickTactileContact(stageTime)
  bridge.tactileExpressionLayer.update({}, stageTime)
}
const beforeRelease = stageResponse.snapshot().shyness
assert.ok(beforeRelease > 14 && beforeRelease <= 30)
assert.equal(bridge.activeTouchExpression, '脸红', 'blush appears while still held')
assert.ok(liveValues.get('bq4') > .1 && liveValues.get('bq4') < .5)
assert.equal(bridge.tactileContact.pressed, true)
bridge.touch(.32, .235, 'stroke-left', { phase: 'complete', mode: 'hand' })
assert.equal(stageResponse.snapshot().shyness, beforeRelease, 'release does not double charge the caress')
stageTime += 5000
bridge.tickTactileContact(stageTime)
assert.ok(stageResponse.snapshot().shyness < beforeRelease)
const beforeUnsupported = stageResponse.snapshot().shyness
assert.equal(bridge.touch(.32, .235, 'kick').hit, false)
assert.equal(stageResponse.snapshot().shyness, beforeUnsupported, 'removed foot action is not a disguised pet')

function sameCaressAt(hz) {
  let t = 0
  const tactile = createTouchResponseRuntime({ now: () => t })
  tactile.begin('cheek-left', 'tap', .55, { x: 0, y: 0 })
  tactile.move('cheek-left', 'stroke-right', .55, { x: .001, y: 0 })
  for (let i = 1; i <= hz * 5; i++) {
    t = i * 1000 / hz
    const cycle = t / 1000 % 2
    const x = .001 + (cycle < 1 ? cycle : 2 - cycle) * .06
    tactile.move('cheek-left', 'stroke', .55, { x, y: 0 })
    tactile.snapshot()
  }
  return tactile.snapshot().shyness
}
assert.ok(Math.abs(sameCaressAt(15) - sameCaressAt(120)) < .01,
  'the same physical path/time must not reward more frequent samples')
runtime.reset()
runtime.begin('hair', 'tap', .55, { x: .5, y: .1 })
time += 5000
assert.ok(runtime.snapshot().shyness > 0 && runtime.snapshot().shyness < 1,
  'stationary held contact accumulates slowly without release')
runtime.end()
const ended = runtime.snapshot().shyness
time += 5000
assert.ok(runtime.snapshot().shyness < ended)
console.log(`Live2D precise calibration, progression, layered release and ${cases} contact cases passed`)
