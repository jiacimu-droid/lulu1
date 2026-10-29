import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { resolveTouchRegion, sanitizeTouchCalibration, calibratedRegion,
  TOUCH_REGIONS, TOUCH_ZONE_DEFAULTS, touchZoneArea } from '../src/touch-hit.mjs'
import { touchReflexFor, touchRestScale, touchExpressionFor, touchExpressionWeight,
  touchExpressionCategory, touchCompanionOverlayFor, touchMotionFor } from '../src/touch-reflex.mjs'
import { createTouchResponseRuntime, touchFamily, isImpact, committedTouchPose } from '../src/touch-response.mjs'
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
assert.ok(hairTilt.roll < -5 && Math.abs(hairTilt.yaw) > 2 && Math.abs(hairTilt.body.roll) < 2,
  'wolf hair stroke stays expressive without pulling the whole torso')
const hardImpact = enhanceWolfTouch(
  touchReflexFor('cheek-right', 'slap-left', 0, { force: 1 }, 240),
  'cheek-right', 'slap-left', { force: 1 }, 240)
assert.ok(hardImpact.roll < -20 && Math.abs(hardImpact.body.roll) < 4,
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
// Every region retains coordinated body articulation, but touching an arm
// must not move the entire avatar sideways.
for (const region of TOUCH_REGIONS) {
  const reflex = touchReflexFor(region, 'stroke-right', 1, { force: .65 }, 300)
  assert.ok(Object.values(reflex.body).some(value => Math.abs(value) >= .15),
    region + ' should retain a subtle body response')
}
for (const region of ['arm-left', 'arm-right', 'hand-left', 'hand-right']) {
  const tap = touchReflexFor(region, 'tap', 1, { force: .65 }, 100)
  const stroke = touchReflexFor(region, 'stroke-right', 1, { force: .65 }, 100)
  assert.ok(Math.abs(tap.yaw) < 1.5 && Math.abs(tap.body.roll) < 1,
    region + ' tap must not pull the entire model toward the finger')
  assert.ok(Math.abs(stroke.body.roll) < 2 && Math.abs(stroke.yaw) < 3,
    region + ' stroke must remain a local glance, not a full-body lean')
  assert.notDeepEqual(tap, stroke, region + ' tap and stroke are distinct')
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
assert.equal(chestSlap.impactMotion, true, 'chest uses the fast torso impact actuator')
assert.equal(chestSlap.impactFace, false, 'chest hit is not a face strike')
assert.ok(chestSlap.body.pitch < -4 && Math.abs(chestSlap.body.roll) > 2,
  'chest slap recoils visibly at the torso without moving the sprite')
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
assert.equal(touchCompanionOverlayFor('爱心眼', wolf, metadata, 'kiss', high), null,
  'heart eyes cannot trigger any automatic tactile companion overlay')
assert.equal(touchCompanionOverlayFor('星星眼', wolf, {
  ...metadata, '脸红': exp3('ParamEyeLOpen') }, 'kiss', high), null,
  'a one-eye blush overlay must not wink over star eyes')

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
  const unevenPair = { 星星: { Parameters: [
    { Id: 'ParamEyeLOpen', Blend: 'Add', Value: -.8 },
    { Id: 'ParamEyeROpen', Blend: 'Add', Value: -.2 },
    { Id: 'ParamCheekPuff', Blend: 'Add', Value: .3 },
  ] } }
  assert.equal(l.start({}, '星星', unevenPair, null, { shyness: 80 }, 1500), true)
  l.update({}, 1750)
  assert.ok(eyeVals.get('ParamEyeLOpen') < 1)
  assert.ok(Math.abs(eyeVals.get('ParamEyeLOpen') -
    eyeVals.get('ParamEyeROpen')) < 1e-8,
    'automatic paired exp3 cannot inherit an asymmetric wink')
  l.clear({})
  assert.equal(eyeVals.get('ParamEyeLOpen'), 1)
  assert.equal(eyeVals.get('ParamEyeROpen'), 1)
  const malformedPair = { 星星: { Parameters: [
    { Id: 'ParamEyeLOpen', Blend: 'Add', Value: -.8 },
    { Id: 'ParamEyeROpen', Blend: 'Multiply', Value: -.2 },
    { Id: 'ParamCheekPuff', Blend: 'Add', Value: .3 },
  ] } }
  assert.equal(l.start({}, '星星', malformedPair, null, { shyness: 80 }, 2000), true)
  l.update({}, 2300)
  assert.equal(eyeVals.get('ParamEyeLOpen'), 1)
  assert.equal(eyeVals.get('ParamEyeROpen'), 1)
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
// Exercise the real stage Motion3 gate, not only a mocked dispatcher. A
// character-selected motion can still be loading asynchronously when touch
// arrives; that short interval must never let a contact steal its priority.
{
  const clipSource = source.slice(
    source.indexOf('function maybePlayTactileMotion('),
    source.indexOf('\nfunction cancelTactileMotion()'))
  const starts = []
  let managerFinished = true
  const loadedModel = { internalModel: {
    motionManager: { isFinished: () => managerFinished },
  } }
  const ctx = {
    model: loadedModel, wolfRigActive: true,
    tactileMotionIndices: new Set([1, 2]),
    authoredMotion: { model: loadedModel, source: 'character', started: false },
    touchMotionFor,
    motion: (...args) => starts.push(args),
  }
  vm.createContext(ctx)
  vm.runInContext(clipSource, ctx)
  const play = () => ctx.maybePlayTactileMotion(
    'cheek-left', 'stroke-left', { force: .9 }, false, false)
  assert.equal(play(), false, 'pending character motion prevents touch preemption')
  assert.equal(starts.length, 0)
  ctx.authoredMotion.started = true
  managerFinished = false
  assert.equal(play(), false, 'active character Motion3 retains priority')
  managerFinished = true
  assert.equal(play(), true, 'a finished character motion releases its slot')
  assert.deepEqual(starts, [['LuluWolf', 1, true, 'touch']])
  assert.equal(ctx.maybePlayTactileMotion('cheek-left', 'stroke-left',
    { force: .9 }, true, true), false, 'the same finger stroke cannot replay')
  ctx.tactileMotionIndices.clear()
  assert.equal(play(), false, 'uninstalled Motion3 always falls back to local reflex')
  ctx.tactileMotionIndices.add(1)
  ctx.wolfRigActive = false
  assert.equal(play(), false, 'other imported models must not receive wolf clips')
}
// Run the actual stage motion() against deferred Cubism loads. A low-FPS
// frame can miss the moment a clip finishes; the next 'continue' must restart
// it. Conversely, a stale async result must never stop the newer same-name
// clip, and touch choreography cannot impersonate the role's chosen motion.
{
  const motionSource = source.slice(source.indexOf('function motion(group, index,'),
    source.indexOf("\ndocument.addEventListener('visibilitychange'"))
  const loads = []
  let finished = false
  let stops = 0
  const manager = {
    state: { currentGroup: 'Gesture', currentIndex: 0 },
    isFinished: () => finished,
    stopAllMotions: () => { stops++ },
  }
  const loadedModel = {
    internalModel: { motionManager: manager },
    motion: (group, index, priority) => new Promise(resolve =>
      loads.push({ group, index, priority, resolve })),
  }
  const ctx = {
    model: loadedModel, playing: true, pendingSpeechParts: null,
    motionRevision: 0, authoredMotion: null,
    motionTransition: { begin: () => {} }, notify: () => {},
  }
  vm.createContext(ctx)
  vm.runInContext(motionSource, ctx)
  ctx.motion('Gesture', 0, true, 'character')
  assert.equal(loads.length, 1)
  ctx.motion('Gesture', 0, true, 'character', { transition: 'continue' })
  assert.equal(loads.length, 1, 'pending same Motion3 continues without duplicate load')
  loads[0].resolve(true)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(ctx.authoredMotion.started, true)
  finished = true
  ctx.motion('Gesture', 0, true, 'character', { transition: 'continue' })
  assert.equal(loads.length, 2, 'completed clip must restart on next spoken part')
  ctx.motion('Gesture', 0, true, 'character', { transition: 'blend' })
  assert.equal(loads.length, 3)
  loads[1].resolve(true)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(stops, 0, 'stale same-name load cannot stop newer pending motion')
  assert.equal(ctx.authoredMotion.revision, 3)
  loads[2].resolve(true)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(ctx.authoredMotion.started, true)
  finished = false
  ctx.motion('Gesture', 0, true, 'character', { transition: 'continue' })
  assert.equal(loads.length, 3, 'genuinely active same clip continues')
  ctx.authoredMotion.source = 'touch'
  ctx.motion('Gesture', 0, true, 'character', { transition: 'continue' })
  assert.equal(loads.length, 4, 'touch clip cannot consume a character speech choice')
  ctx.authoredMotion = null
  ctx.motionRevision++
  loads[3].resolve(true)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(stops, 1, 'cancelled load with no successor may still be stopped')
}
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
  touchExpressionFor, touchExpressionCategory, touchCompanionOverlayFor, isImpact, committedTouchPose,
  actualAvatarPixel: () => true, tactileContact: null,
  tactileGestureCancelled: false, touchSequence: 0, touchReflex: null, touchReflexEndAt: 0,
  touchExpressionNames: wolf, touchExpressionMetadata: metadata,
  mouthExclusiveExpressions: new Set(['吐舌']), playing: false,
  activeExpressionName: null, activeTouchExpression: null,
  activeTouchExpressionUntil: 0,
  clearTouchCompanion: () => {},
  cancelTactileMotion: () => {},
  tactileExpressionLayer: { start: () => false, retarget: () => {} },
}
vm.createContext(bridge)
vm.runInContext(touchSource, bridge)
// Real stage timing: one authored clip on the first verified MOVE, not
// once per touch sample or again at finger release.
{
  const clips = []
  bridge.wolfRigActive = true
  bridge.tactileMotionIndices = new Set([1, 2, 9, 33])
  bridge.maybePlayTactileMotion = (region, gesture, state, completed, alreadyPlayed) => {
    if (alreadyPlayed) return false
    const choice = touchMotionFor(region, gesture, state)
    if (!choice || !bridge.tactileMotionIndices.has(choice.index) ||
        (!completed && !gesture.startsWith('stroke'))) return false
    clips.push(choice.index)
    return true
  }
  bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand', force: .9 })
  assert.equal(clips.length, 0)
  for (let i = 0; i < 24; i++)
    bridge.touch(.33, .235, 'stroke-left', { phase: 'move', mode: 'hand', force: .9 })
  assert.deepEqual(clips, [1])
  bridge.touch(.33, .235, 'stroke-left', { phase: 'complete', mode: 'hand', force: .9 })
  assert.deepEqual(clips, [1], 'release must not replay a clip used during the stroke')
  bridge.wolfRigActive = false
  bridge.tactileMotionIndices = new Set()
  stageResponse.reset()
}
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
// A palm or kiss stays the selected TOOL during MOVE. Raw Android strokes
// describe the path, not a second petting action before release.
stageResponse.reset()
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'pat' })
stageTime += 650
bridge.touch(.68, .235, 'stroke-right', { phase: 'move', mode: 'pat', force: .9 })
assert.equal(bridge.tactileContact.kind, 'slap')
assert.equal(bridge.tactileContact.region, 'cheek-left')
bridge.tickTactileContact(stageTime)
assert.equal(bridge.touchReflex, null, 'moving palm cannot play impact early')
assert.equal(stageResponse.snapshot().shyness, 0, 'palm MOVE is not a caress')
assert.equal(stageResponse.snapshot().impact, 0, 'palm MOVE is not an impact')
const sweptPalm = bridge.touch(.68, .235, 'slap-right',
  { phase: 'complete', mode: 'pat', force: .9 })
assert.equal(sweptPalm.region, 'cheek-left')
assert.equal(sweptPalm.gesture, 'slap-right')
assert.ok(stageResponse.snapshot().impact > 40)
stageResponse.reset()
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'kiss' })
stageTime += 650
bridge.touch(.34, .235, 'stroke-right', { phase: 'move', mode: 'kiss', force: .9 })
assert.equal(bridge.tactileContact.kind, 'kiss')
assert.equal(stageResponse.snapshot().shyness, 0, 'kiss MOVE cannot count as petting')
assert.equal(stageResponse.snapshot().impact, 0)
const movedKiss = bridge.touch(.34, .235, 'kiss',
  { phase: 'complete', mode: 'kiss', force: .9 })
assert.equal(movedKiss.region, 'cheek-left')
assert.ok(stageResponse.snapshot().shyness > 10, 'kiss is counted once on release')
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
for (const mode of ['hand', 'pat', 'kiss']) {
  const gesture = mode === 'pat' ? 'slap' : mode === 'kiss' ? 'kiss' : 'tap'
  assert.equal(bridge.touch(.32, .235, gesture,
    { phase: 'complete', mode }).hit, false,
    mode + ': release without DOWN cannot invent a contact')
}
bridge.actualAvatarPixel = nx => nx < .5
assert.equal(bridge.touch(.68, .235, 'down',
  { phase: 'move', mode: 'kiss' }).hit, false)
assert.equal(bridge.touch(.32, .235, 'kiss',
  { phase: 'complete', mode: 'kiss' }).hit, false,
  'background DOWN followed by avatar UP is not a real kiss')
bridge.actualAvatarPixel = () => true
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand' })
bridge.actualAvatarPixel = nx => nx < .5
assert.equal(bridge.touch(.68, .235, 'stroke-right',
  { phase: 'move', mode: 'hand' }).hit, false,
  'leaving the avatar stops contact accumulation')
bridge.actualAvatarPixel = () => true
assert.equal(bridge.touch(.32, .235, 'stroke-left',
  { phase: 'move', mode: 'hand' }).hit, true,
  're-entering the avatar starts a new verified contact')
assert.equal(bridge.touch(.32, .235, 'stroke-left',
  { phase: 'complete', mode: 'hand' }).hit, true,
  'a verified re-entry can finish normally')

// Android forwards an out-of-bounds MOVE as an ordinary miss, not an OS
// cancellation. The same finger can then establish a fresh model hit.
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand' })
assert.equal(bridge.touch(-.1, .235, 'stroke-right',
  { phase: 'move', mode: 'hand' }).hit, false)
assert.equal(bridge.tactileGestureCancelled, false)
assert.equal(bridge.touch(.32, .235, 'stroke-left',
  { phase: 'move', mode: 'hand' }).hit, true)
assert.equal(bridge.touch(.32, .235, 'stroke-left',
  { phase: 'complete', mode: 'hand' }).hit, true,
  'a verified contact after viewport re-entry completes normally')
const beforeMiss = stageResponse.snapshot().impact
bridge.touch(-.1, .235, 'slap', { phase: 'complete', mode: 'pat' })
assert.equal(stageResponse.snapshot().impact, beforeMiss)
bridge.touch(.32, .235, 'cancel')
assert.equal(bridge.tactileContact, null)
assert.equal(bridge.touch(.32, .235, 'stroke-right',
  { phase: 'move', mode: 'hand' }).hit, false,
  'OS-cancelled pointer cannot resume without a fresh DOWN')
assert.equal(bridge.touch(.32, .235, 'tap',
  { phase: 'complete', mode: 'hand' }).hit, false,
  'a cancelled Android pointer cannot return as a phantom release')
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand' })
assert.equal(bridge.touch(.32, .235, 'tap',
  { phase: 'complete', mode: 'hand' }).hit, true,
  'a fresh DOWN can start a new valid gesture after cancellation')
bridge.touchCalibration = { 'cheek-left': {
  u: .32, v: .235, rx: .08, ry: .025,
} }
bridge.actualAvatarPixel = () => false
assert.equal(bridge.touch(.32, .235, 'down',
  { phase: 'move', mode: 'hand' }).hit, false,
  'calibrated body zones cannot override confirmed background pixels')
bridge.actualAvatarPixel = () => true
bridge.touchCalibration = {}
bridge.calibrationVisible = true
const beforeCalibration = stageResponse.snapshot().shyness
assert.equal(bridge.touch(.5, .04, 'kiss').hit, false)
assert.equal(stageResponse.snapshot().shyness, beforeCalibration,
  'calibration mode must not add touch state after earlier valid kisses')

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

// Pose commitment: MOVE fragments cannot flip a caress pose on jitter,
// but a travel-proven reversal turns it exactly once.
{
  let pose = committedTouchPose(null, 'stroke-left', 'cheek-left', 0)
  assert.equal(pose.gesture, 'stroke-left')
  for (let i = 0; i < 30; i++) {
    pose = committedTouchPose(pose, i % 2 ? 'stroke-right' : 'stroke-left',
      'cheek-left', 2)
    assert.equal(pose.gesture, 'stroke-left',
      'fragment jitter never flips the committed pose')
  }
  pose = committedTouchPose(pose, 'stroke-right', 'cheek-right', 10)
  assert.equal(pose.gesture, 'stroke-left')
  assert.equal(pose.region, 'cheek-left')
  pose = committedTouchPose(pose, 'stroke-right', 'cheek-right', 20)
  assert.equal(pose.gesture, 'stroke-right',
    'a reversal with enough travel behind it commits')
  assert.equal(pose.region, 'cheek-right', 'the side follows the proven reversal')
  pose = committedTouchPose(pose, 'stroke-right', 'hair', 0)
  assert.equal(pose.region, 'hair', 'a different body part is adopted at once')
}

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
// The selected tactile clips must be actual bundled Motion3 files, not guessed
// numbers. Every unpaired or unsafe contact remains on the local actuator.
for (const region of TOUCH_REGIONS)
  for (const gesture of ['tap', 'hold', 'stroke-left', 'stroke-right', 'kiss', 'slap-right']) {
    const selected = touchMotionFor(region, gesture, { force: .85 })
    if (!selected) continue
    assert.equal(selected.group, 'LuluWolf')
    assert.ok(Number.isInteger(selected.index) && selected.index >= 0)
    assert.ok(selected.index < 40)
  }
assert.deepEqual(touchMotionFor('beast-ear-left', 'stroke-left', { force: .85 }), { group: 'LuluWolf', index: 1 })
assert.deepEqual(touchMotionFor('beast-ear-left', 'stroke-right', { force: .85 }), { group: 'LuluWolf', index: 2 })
assert.deepEqual(touchMotionFor('cheek-right', 'slap-left', { force: .9 }),
  { group: 'LuluWolf', index: 33 })
assert.deepEqual(touchMotionFor('cheek-right', 'slap-right', { force: .9 }),
  { group: 'LuluWolf', index: 9 })
assert.equal(touchMotionFor('cheek-right', 'slap-right', { force: .15 }), null)
assert.equal(touchMotionFor('beast-ear-left', 'stroke-right', { force: .35 }), null,
  'light strokes remain local without a pronounced full-neck/body tilt')
assert.equal(touchMotionFor('arm-left', 'tap', { force: 1 }), null)
assert.equal(touchMotionFor('arm-right', 'stroke-right', { force: 1 }), null)
assert.equal(touchMotionFor('chest', 'slap-right', { force: 1 }), null)
assert.equal(touchMotionFor('hand-left', 'kiss', { force: 1 }), null)
assert.equal(touchMotionFor('beast-ear-left', 'tap', { force: .25 }), null)
assert.equal(touchMotionFor('beast-ear-left', 'tap', { force: .8 }).index, 35)
// Genuine Motion3 control: preserve time-varying authored head + torso joints,
// while local touch adds only a small signed detail and still owns its face.
{
  const v = new Map([
    ['ParamAngleX', 0], ['ParamAngleY', 0], ['ParamAngleZ', -13],
    ['ParamBodyAngleX', 0], ['ParamBodyAngleY', 0], ['ParamBodyAngleZ', -5],
    ['ParamBrowLY', 0], ['ParamBrowRY', 0],
  ])
  const layer = createVisualLayerRuntime({
    has: (_core, id) => v.has(id),
    read: (_core, id) => v.get(id),
    write: (_core, id, value) => v.set(id, value),
  })
  const contact = { yaw: 3, pitch: 1, roll: -5,
    body: { yaw: 2, pitch: 0, roll: -3 },
    face: { browY: .5 },
  }
  layer.update({}, { deltaMs: 16, seconds: .7, authoredMotion: true,
    touchMotionActive: true, touchReflex: contact, speaking: false })
  assert.ok(v.get('ParamAngleZ') < -13 && v.get('ParamAngleZ') > -14,
    'tactile motion must keep authored -13° neck timeline rather than overwrite it')
  assert.ok(v.get('ParamBodyAngleZ') < -5 && v.get('ParamBodyAngleZ') > -6,
    'author motion owns torso timeline, local recoil adds only a tiny complement')
  assert.ok(v.get('ParamBrowLY') > 0 && v.get('ParamBrowRY') > 0,
    'tactile face remains active while author controls motion')
}
// A genuine slow caress has a distinct moving and stationary phase. The
// renderer must keep its last real direction and body part without freezing
// the head at a large angle when Android stops sending MOVE callbacks.
assert.equal(touchRestScale('slap-right', 5000), 1)
assert.equal(touchRestScale('kiss', 5000), 1)
assert.equal(touchRestScale('tap', 5000), 1)
assert.equal(touchRestScale('stroke-left', 200), 1)
assert.ok(touchRestScale('stroke-left', 2500) > .5 &&
  touchRestScale('stroke-left', 2500) < .6)
assert.equal(touchRestScale('stroke-left', NaN), 1)
const movingWolf = enhanceWolfTouch(
  touchReflexFor('hair', 'stroke-left', 0, { force: .9, motionIdleMs: 0 }, 500),
  'hair', 'stroke-left', { force: .9, motionIdleMs: 0 }, 500)
const settledWolf = enhanceWolfTouch(
  touchReflexFor('hair', 'stroke-left', 0, { force: .9, motionIdleMs: 2500 }, 2500),
  'hair', 'stroke-left', { force: .9, motionIdleMs: 2500 }, 2500)
assert.ok(movingWolf.roll < -5 && settledWolf.roll < 0 &&
  Math.abs(settledWolf.roll) < Math.abs(movingWolf.roll) * .6,
  'wolf neck must relax in place without flipping a leftward stroke to the right')
stageResponse.reset()
bridge.actualAvatarPixel = () => true
bridge.calibrationVisible = false
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand', force: .9 })
stageTime += 100
bridge.touch(.34, .235, 'stroke-left', { phase: 'move', mode: 'hand', force: .9 })
bridge.tickTactileContact(stageTime)
const movingPose = bridge.touchReflex
stageTime += 2500
bridge.tickTactileContact(stageTime)
const restingPose = bridge.touchReflex
assert.equal(bridge.tactileContact.kind, 'stroke-left')
assert.equal(bridge.tactileContact.region, 'cheek-left')
assert.ok(Math.abs(restingPose.roll) < Math.abs(movingPose.roll) * .6,
  'the actual stage relaxes a stationary held stroke without changing its gesture')
stageTime += 100
bridge.touch(.36, .235, 'stroke-left', { phase: 'move', mode: 'hand', force: .9 })
bridge.tickTactileContact(stageTime)
assert.ok(Math.abs(bridge.touchReflex.roll) > Math.abs(restingPose.roll) * 1.7,
  'a fresh verified MOVE immediately restores the directional caress')
assert.equal(bridge.touch(.36, .235, 'stroke-left',
  { phase: 'complete', mode: 'hand', force: .9 }).gesture, 'stroke-left')
// Lifting a stationary finger must not replay the original full-amplitude
// swipe. The release pose keeps its last real movement timestamp.
bridge.touch(.32, .235, 'down', { phase: 'move', mode: 'hand', force: .9 })
stageTime += 100
bridge.touch(.34, .235, 'stroke-left', { phase: 'move', mode: 'hand', force: .9 })
stageTime += 2500
bridge.tickTactileContact(stageTime)
const beforeStillRelease = bridge.touchReflex.roll
const stillRelease = bridge.touch(.34, .235, 'stroke-left',
  { phase: 'complete', mode: 'hand', force: .9 })
assert.equal(stillRelease.gesture, 'stroke-left')
assert.ok(Math.abs(bridge.touchReflex.roll) <= Math.abs(beforeStillRelease) * 1.02,
  'release after a long stationary hold cannot jerk the head back to full tilt')
stageTime += 100
bridge.tickTactileContact(stageTime)
assert.ok(Math.abs(bridge.touchReflex.roll) <= Math.abs(beforeStillRelease) * 1.02,
  'the release tail must preserve the relaxed neck angle')
console.log(`Live2D precise calibration, progression, layered release and ${cases} contact cases passed`)
