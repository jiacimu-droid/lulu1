import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { resolveTouchRegion, sanitizeTouchCalibration, calibratedRegion,
  TOUCH_REGIONS, TOUCH_ZONE_DEFAULTS, touchZoneArea } from '../src/touch-hit.mjs'
import { touchReflexFor, touchExpressionFor, touchExpressionWeight,
  touchExpressionCategory, touchCompanionOverlayFor, touchMotionFor } from '../src/touch-reflex.mjs'
import { createTouchResponseRuntime, touchFamily, isImpact } from '../src/touch-response.mjs'
import { createTouchExpressionLayer } from '../src/touch-expression-layer.mjs'
import { createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { isWolfRig, enhanceWolfTouch } from '../src/wolf-rig-profile.mjs'
import { isScenePixel, hasAvatarPixels, SCENE_COLORS } from '../src/scene-pixels.mjs'

// Renderer and Android must not mistake the new light room for a loaded avatar
// or for a touched cheek, even with JPEG/antialiasing variation.
for (const color of SCENE_COLORS) {
  const r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255
  assert.equal(isScenePixel(r, g, b), true)
  assert.equal(isScenePixel(r - 6, g + 4, b - 3), true)
  const scene = Uint8Array.from(Array.from({ length: 64 * 64 }, () => [r, g, b, 255]).flat())
  assert.equal(hasAvatarPixels(scene, 32), false)
}
const avatar = new Uint8Array(64 * 64 * 4)
for (let i = 0; i < 64 * 64; i++) {
  const color = i < 600 ? [120, 80, 65] : [233, 237, 244]
  avatar.set([...color, 255], i * 4)
}
assert.equal(hasAvatarPixels(avatar, 32), true)
assert.equal(isScenePixel(120, 80, 65), false)
const textOnly = avatar.slice()
for (let i = 60; i < 600; i++) textOnly.set([233, 237, 244, 255], i * 4)
assert.equal(hasAvatarPixels(textOnly, 64), false)

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


assert.equal(isWolfRig({}, (_core, id) => new Set([
  'ParamCheekPuff', 'ParamJawOpen', 'ParamAngleZ2', 'body_gensui_z',
  'ParamMouthPuckerWiden', 'ParamBrowLForm',
]).has(id)), true)
assert.equal(isWolfRig({}, (_core, id) => id === 'ParamMouthOpenY'), false)
const hairTilt = enhanceWolfTouch(
  touchReflexFor('hair', 'stroke-left', 0, { force: .7 }, 300),
  'hair', 'stroke-left', { force: .7 }, 300)
assert.ok(hairTilt.roll < -12 && Math.abs(hairTilt.yaw) < 3,
  'wolf soft touch tilts its neck without a sideways head shake')
const hardImpact = enhanceWolfTouch(
  touchReflexFor('cheek-right', 'slap-left', 0, { force: 1 }, 240),
  'cheek-right', 'slap-left', { force: 1 }, 240)
assert.ok(hardImpact.roll < -20 && Math.abs(hardImpact.body.roll) < 3.2,
  'wolf impact bends neck sharply while keeping the torso controlled')
assert.ok(hardImpact.face.mouthX < -4, 'slap must drive mouth physics input')
const wolfEar = enhanceWolfTouch(
  touchReflexFor('beast-ear-left', 'stroke-right', 0, { force: .6 }, 230),
  'beast-ear-left', 'stroke-right', { force: .6 }, 230)
assert.equal(wolfEar.parameters.se_wuli_x_l, undefined,
  'do not write Cubism physics ear output directly')
const touchedCheek = touchReflexFor('cheek-left', 'stroke-right', 2,
  { force: .7, shyness: 30 }, 280)
assert.ok(touchedCheek.face.cheekPuff > .22 && touchedCheek.face.cheekPuff <= .62)
assert.ok(touchedCheek.face.browLeftY > 0 && touchedCheek.face.browRightY == null)
assert.ok(touchReflexFor('nose', 'tap', 1).face.browRightY > 0)
assert.ok(touchReflexFor('hair', 'stroke-left', 0).face.browLeftY > 0)
assert.ok(touchReflexFor('cheek-right', 'slap-left', 0,
  { force: .8 }, 90).face.jawOpen > 0)

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
assert.equal(touchExpressionFor('mouth', wolf, 0, 'kiss', true, high), '星星眼')
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
assert.ok(early.yaw > later.yaw && early.yaw > 1,
  'a rightward slap pushes the head toward screen right even on the left cheek')
assert.ok(Math.abs(early.roll) > Math.abs(early.yaw) * 2,
  'a slapped head should visibly roll at the neck, not sway horizontally')
assert.equal(early.impactMotion, true)
assert.ok(early.face.browY > 0 && later.face.browY > 0 && later.face.pout > early.face.pout,
  'startled brows soften into an aggrieved pout, not an angry scowl')
assert.ok(touchReflexFor('cheek-left', 'slap-left').yaw < 0)
const rightCheekStrike = touchReflexFor('cheek-right', 'slap-right', 0, { force: .55 }, 70)
assert.ok(rightCheekStrike.yaw > 1 && rightCheekStrike.roll > 0,
  'a rightward slap must go right even when hitting the right cheek')
assert.equal(rightCheekStrike.yaw, early.yaw)
const leftwardStrike = touchReflexFor('cheek-right', 'slap-left', 0, { force: .55 }, 70)
assert.ok(leftwardStrike.yaw < -1 && leftwardStrike.roll < -10,
  'leftward hand motion must override the contacted cheek side')
assert.equal(leftwardStrike.roll, -rightCheekStrike.roll)
assert.ok(touchReflexFor('cheek-left', 'slap').roll < 0)
assert.ok(touchReflexFor('cheek-right', 'slap').roll > 0)
assert.ok(Math.abs(touchReflexFor('cheek-left', 'slap-right', 0, { force: .55 }, 340).roll) >
  Math.abs(touchReflexFor('cheek-left', 'slap-right', 0, { force: .55 }, 850).roll),
  'the bent head remains visible for multiple native-mirror frames before settling')
assert.ok(Math.abs(early.body.roll) < Math.abs(
  touchReflexFor('cheek-left', 'slap-right', 0, { force: .55 }, 260).body.roll),
  'shoulders must follow the head, not swing ahead of contact')
assert.ok(touchReflexFor('cheek-left', 'slap', 0, { force: .9 }).yaw <
  touchReflexFor('cheek-left', 'slap', 0, { force: .25 }).yaw)
// Point-tap and swipe are distinct even for the same cheek and strength.
const cheekTap = touchReflexFor('cheek-left', 'slap', 0, { force: .9 }, 120)
const cheekSwipe = touchReflexFor('cheek-left', 'slap-right', 0, { force: .9 }, 120)
assert.ok(Math.abs(cheekSwipe.roll) > Math.abs(cheekTap.roll) * 1.5)
const chestSlap = touchReflexFor('chest', 'slap-right', 0, { force: .9 }, 120)
assert.equal(chestSlap.roll, 0, 'chest hit cannot rotate the face')
assert.equal(chestSlap.yaw, 0)
assert.equal(chestSlap.impactMotion, false, 'chest never triggers facial full-tilt')
assert.ok(Math.abs(chestSlap.body.roll) < 2)
const leftEyeTap = touchReflexFor('eye-left', 'tap', 0, { force: .25 }, 300)
const rightEyeTap = touchReflexFor('eye-right', 'tap', 0, { force: .25 }, 300)
assert.equal(leftEyeTap.face.eyeOpen, rightEyeTap.face.eyeOpen)
assert.equal(leftEyeTap.face.eyeLeftOpen, undefined)
assert.equal(rightEyeTap.face.eyeRightOpen, undefined)
const petVariants = [0, 1, 2].map(i =>
  touchReflexFor('cheek-left', 'stroke-right', i, { shyness: 30, force: .55 }, 250))
assert.ok(new Set(petVariants.map(p => p.face.eyeOpen)).size >= 2,
  'soft touches should not show the same eye squint every time')
assert.ok(petVariants.every(p => p.face.eyeOpen >= .45),
  'a caress should not force tightly closed eyes on every gesture')
const unilateral = touchReflexFor('eye-left', 'kiss')
assert.ok(unilateral.face.eyeOpen < .5 &&
  unilateral.face.eyeLeftOpen == null && unilateral.face.eyeRightOpen == null,
  'eye touch must close both lids together, never blink the opposite eye alone')
assert.ok(Object.hasOwn(touchReflexFor('beast-ear-right', 'stroke').parameters, 'se_wuli_x_r'))
runtime.reset()
const impact = runtime.complete('cheek-left', 'slap')
assert.equal(touchExpressionFor('cheek-left', wolf, 0, 'slap', false, impact), '流泪')
for (let i = 0; i < 5; i++) runtime.complete('cheek-left', 'slap')
assert.equal(touchExpressionFor('cheek-left', wolf, 0, 'slap', false, runtime.snapshot()), '流泪',
  'repeated contact should not inevitably turn every response into a dark face')
const strongImpact = runtime.complete('cheek-left', 'slap', .9)
assert.equal(touchExpressionFor('cheek-left', wolf, 4, 'slap', false, strongImpact), '流泪',
  'even repeated strong contacts choose existing tears instead of an angry black face')
assert.equal(touchExpressionFor('cheek-left', ['脸黑'], 4, 'slap', false, strongImpact), null,
  'a black-face asset alone must never be a surrogate for crying')
const aggrieved = touchReflexFor('cheek-left', 'slap-left', 0, strongImpact, 520)
assert.ok(aggrieved.roll < -16 && aggrieved.face.browY > 0 &&
  aggrieved.face.pout > .4, 'visible leftward neck bend and raised, tearful brows')
assert.equal(touchExpressionFor('hair', ['流泪', '爱心眼', '兽耳'], 99, 'stroke'), null,
  'a missing mild expression must not fall back to an inappropriate one')
assert.equal(touchCompanionOverlayFor('脸红', wolf, metadata, 'kiss', { shyness: 20 }), null)
const extra = touchCompanionOverlayFor('星星眼', wolf, metadata, 'kiss', high)
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

// A premade exp3 can contain an unpaired wink. Automatic tactile feedback
// must skip that channel rather than blinking the wrong eye.
{
  const eyeVals = new Map([['ParamEyeLOpen', 1], ['ParamEyeROpen', 1], ['ParamCheekPuff', 0]])
  const l = createTouchExpressionLayer({
    has: (_c, id) => eyeVals.has(id),
    read: (_c, id) => eyeVals.get(id),
    write: (_c, id, value) => eyeVals.set(id, value),
  })
  const accidentalWink = { 露珠: { Parameters: [
    { Id: 'ParamEyeROpen', Blend: 'Add', Value: -1 },
    { Id: 'ParamCheekPuff', Blend: 'Add', Value: .3 },
  ] } }
  assert.equal(l.start({}, '露珠', accidentalWink, null, { shyness: 40 }, 1000), true)
  l.update({}, 1200)
  assert.equal(eyeVals.get('ParamEyeROpen'), 1)
  assert.equal(eyeVals.get('ParamEyeLOpen'), 1)
  assert.ok(eyeVals.get('ParamCheekPuff') > 0)
  l.clear({})
}

const faceValues = new Map([['ParamEyeLOpen', 1], ['ParamEyeROpen', 1]])
const rig = createVisualLayerRuntime({ has: (_c, id) => faceValues.has(id),
  read: (_c, id) => faceValues.get(id), write: (_c, id, value) => faceValues.set(id, value) })
for (let i = 0; i < 45; i++) rig.update({}, { deltaMs: 16, seconds: i * .016, touchReflex: unilateral })
assert.ok(faceValues.get('ParamEyeLOpen') < .5)
assert.ok(Math.abs(faceValues.get('ParamEyeLOpen') -
  faceValues.get('ParamEyeROpen')) < .001,
  'touching one eye must never make only the opposite eye blink')
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
  wolfRigActive: false, enhanceWolfTouch,
  touchExpressionFor, touchExpressionCategory, touchCompanionOverlayFor, isImpact,
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
assert.equal(bridge.touchReflex, null, 'pressing the palm must not play the hit before contact')
bridge.tickTactileContact(stageTime)
assert.equal(bridge.touchReflex, null, 'holding the palm must not consume the eventual recoil')
const completedHit = bridge.touch(.32, .235, 'slap', { phase: 'complete', mode: 'pat', force: .9 })
assert.equal(completedHit.gesture, 'slap')
assert.equal(completedHit.force, .9)
assert.equal(stageResponse.snapshot().shyness, 0)
assert.ok(stageResponse.snapshot().impact > 40)
assert.ok(bridge.touchReflex.roll < -2, 'even a stationary tap bends the cheek-side neck, without toppling the avatar')
// The first real cheek contact owns the hit even if release crosses the
// other cheek or leaves the avatar silhouette within the video viewport.
stageResponse.reset()
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'pat' })
const acrossFace = bridge.touch(.68, .235, 'slap-right',
  { phase: 'complete', mode: 'pat', force: .9 })
assert.equal(acrossFace.region, 'cheek-left', 'slap is anchored to DOWN')
assert.ok(bridge.touchReflex.roll > 3, 'rightward sweep bends the head right')
// Even if Android's last MOVE was leftward, net rightward travel wins.
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'pat' })
const jitteredRight = bridge.touch(.68, .235, 'slap-left',
  { phase: 'complete', mode: 'pat', force: .7 })
assert.equal(jitteredRight.gesture, 'slap-right')
assert.ok(bridge.touchReflex.roll > 3)
bridge.touch(.68, .235, 'down', { phase: 'move', mode: 'pat' })
const jitteredLeft = bridge.touch(.32, .235, 'slap-right',
  { phase: 'complete', mode: 'pat', force: .7 })
assert.equal(jitteredLeft.gesture, 'slap-left')
assert.ok(bridge.touchReflex.roll < -3)
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'pat' })
bridge.actualAvatarPixel = nx => nx < .9
const beyondFace = bridge.touch(.96, .235, 'slap-right',
  { phase: 'complete', mode: 'pat', force: .9 })
assert.equal(beyondFace.region, 'cheek-left', 'a valid strike survives release outside the face')
// The same verified contact rule applies to long caresses and kisses,
// not only palm strikes. Releasing over a blank pixel must not erase
// the real last-touched body part or create a phantom touch from nowhere.
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand' })
bridge.actualAvatarPixel = nx => nx < .9
const offscreenCaress = bridge.touch(.96, .235, 'stroke-right',
  { phase: 'complete', mode: 'hand', force: .25 })
assert.equal(offscreenCaress.region, 'cheek-left')
assert.equal(offscreenCaress.gesture, 'stroke-right')
bridge.actualAvatarPixel = () => true
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'kiss' })
bridge.actualAvatarPixel = nx => nx < .9
const offscreenKiss = bridge.touch(.96, .235, 'kiss',
  { phase: 'complete', mode: 'kiss', force: .9 })
assert.equal(offscreenKiss.region, 'cheek-left')
assert.equal(offscreenKiss.gesture, 'kiss')
const phantomRelease = bridge.touch(.96, .235, 'kiss',
  { phase: 'complete', mode: 'kiss' })
assert.equal(phantomRelease.hit, false, 'release without an active hit cannot touch background')
bridge.actualAvatarPixel = () => true
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
assert.equal(touchMotionFor('beast-ear-left', 'stroke-left'), 1)
assert.equal(touchMotionFor('beast-ear-right', 'stroke-right'), 2)
assert.equal(touchMotionFor('hair', 'stroke-left'), 1)
assert.equal(touchMotionFor('hair', 'stroke-right'), 2)
assert.equal(touchMotionFor('cheek-left', 'slap'), null)
assert.equal(touchMotionFor('cheek-right', 'slap-left'), null)
assert.equal(touchMotionFor('chest', 'tap'), 17)
assert.equal(touchMotionFor('nose', 'tap'), 23)
assert.equal(touchMotionFor('forehead', 'stroke-up'), 26)
assert.equal(touchMotionFor('cheek-right', 'stroke-down'), 28)
assert.equal(touchMotionFor('mouth', 'tap'), null)
assert.equal(touchMotionFor('chest', 'hold'), null)
assert.equal(touchMotionFor('chest', 'kiss'), null)
assert.equal(touchMotionFor('nose', 'stroke-left'), null)
console.log(`Live2D precise calibration, progression, layered release and ${cases} contact cases passed`)
