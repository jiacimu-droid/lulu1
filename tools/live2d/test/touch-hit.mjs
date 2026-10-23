import assert from 'node:assert/strict'
import { resolveTouchRegion, sanitizeTouchCalibration } from '../src/touch-hit.mjs'
import { touchReflexFor, touchExpressionFor, touchExpressionCategory, touchCompanionOverlayFor } from '../src/touch-reflex.mjs'
const bounds = { x: 60, y: -160, width: 250, height: 750 }
const hit = (x, y, hits = [], authoredAreas = 0) =>
  resolveTouchRegion({x, y, hits, authoredAreas, bounds})
// The finer local regions replace the legacy coarse head/body responses.
assert.equal(hit(190, -60), 'eye-right')
assert.equal(hit(190, 120), 'neck')
assert.equal(hit(190, -120), 'hair')
assert.equal(hit(190, -20), 'nose')
assert.equal(hit(190, 20), 'mouth')
assert.equal(hit(190, 165), 'collarbone')
assert.equal(hit(190, 220), 'chest')
assert.equal(hit(63, -140), null, 'empty background must not count')
assert.equal(hit(500, 140), null, 'outside silhouette must not count')
assert.equal(hit(180, 90, [], 2), null, 'do not override authored missed HitAreas')
assert.equal(hit(180, 90, ['Head'], 2), 'cheek-left')
assert.equal(hit(180, 90, ['Arm'], 2), 'arm-left')
assert.equal(hit(180, 90, ['something-custom'], 2), 'cheek-left')
assert.equal(hit(Infinity, 90, ['Head'], 2), null)
const calibrated = sanitizeTouchCalibration({
  'ear-left': { u: .4, v: .15, rx: .06, ry: .07 },
  'nose': { u: 20, v: 8, rx: -1, ry: .2 },
  'background': { u: .5, v: .5, rx: .1, ry: .1 },
})
assert.deepEqual(Object.keys(calibrated), ['ear-left'])
assert.equal(resolveTouchRegion({
  x: 160, y: -47.5, bounds, hits: [], authoredAreas: 2,
  calibration: calibrated,
}), 'ear-left', 'manual zone overrides missing coarse authored HitArea')
assert.equal(resolveTouchRegion({
  x: 500, y: 30, bounds, calibration: calibrated,
}), null, 'custom zones must not enable the background')
assert.equal(hit(65, -5), null, 'transparent edge of head is excluded')
const kiss = touchReflexFor('cheek-left', 'kiss', 2)
const swat = touchReflexFor('cheek-left', 'slap-right', 2)
const kick = touchReflexFor('cheek-left', 'kick', 2)
assert.notEqual(kiss.roll, swat.roll)
assert.notEqual(kick.pitch, kiss.pitch)
assert.ok(kiss.face.smile > .25)
assert.ok(touchExpressionFor('cheek-left', ['happy', 'shy'], 0))
// Grounded against the actual 2026-10-23 user-supplied wolf model
// (nine .exp3 files; no Expressions array in the original model3.json).
const realWolfExpressions = [
  '兽耳', '吐舌', '星星眼', '流泪', '爱心眼',
  '耳机', '脸红', '脸黑', '麦',
]
assert.equal(realWolfExpressions.length, 9)
for (const name of ['兽耳', '耳机', '麦']) {
  assert.equal(touchExpressionCategory(name), 'appearance')
}
assert.equal(touchExpressionCategory('爱心眼'), 'heart')
assert.equal(touchExpressionCategory('星星眼'), 'star')
const kissCycle = Array.from({ length: 12 }, (_, i) =>
  touchExpressionFor('cheek-left', realWolfExpressions, i, 'kiss'))
assert.ok(kissCycle.includes('爱心眼'))
assert.ok(kissCycle.includes('星星眼'))
assert.ok(kissCycle.includes('脸红'))
assert.ok(kissCycle.every(x => !['兽耳', '耳机', '麦', '流泪', '脸黑'].includes(x)),
  'affection must not toggle outfits or unexplained sad faces')
const playfulCycle = Array.from({ length: 12 }, (_, i) =>
  touchExpressionFor('cheek-left', realWolfExpressions, i, 'slap-right'))
assert.ok(playfulCycle.includes('脸黑'))
assert.ok(playfulCycle.includes('流泪'))
assert.ok(playfulCycle.every(x => !['兽耳', '耳机', '麦'].includes(x)))
const speakingCycle = Array.from({ length: 10 }, (_, i) =>
  touchExpressionFor('cheek-left', realWolfExpressions, i, 'kiss', true))
assert.ok(speakingCycle.includes('爱心眼'))
assert.ok(!speakingCycle.includes('吐舌'), 'no tongue while speaking')
const exp3 = (id) => ({ Parameters: [{ Id: id, Value: 1, Blend: 'Add' }] })
const wolfParams = {
  '爱心眼': exp3('bq6'), '星星眼': exp3('bq19'), '脸红': exp3('bq4'),
}
assert.deepEqual(touchCompanionOverlayFor('爱心眼', realWolfExpressions, wolfParams, 'kiss'),
  { name: '脸红', id: 'bq4', value: 1 })
assert.deepEqual(touchCompanionOverlayFor('星星眼', realWolfExpressions, wolfParams, 'stroke-right'),
  { name: '脸红', id: 'bq4', value: 1 })
assert.deepEqual(touchCompanionOverlayFor('脸红', realWolfExpressions, wolfParams, 'kiss'),
  { name: '爱心眼', id: 'bq6', value: 1 })
assert.equal(touchCompanionOverlayFor('爱心眼', ['爱心眼','脸红'], {
  '爱心眼': exp3('bq4'), '脸红': exp3('bq4'),
}, 'kiss'), null, 'never overwrite the primary channel')
console.log('Live2D touch hit-tests passed')
