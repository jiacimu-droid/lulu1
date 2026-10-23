import assert from 'node:assert/strict'
import { resolveTouchRegion, sanitizeTouchCalibration } from '../src/touch-hit.mjs'
import { touchReflexFor, touchExpressionFor } from '../src/touch-reflex.mjs'
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
console.log('Live2D touch hit-tests passed')
