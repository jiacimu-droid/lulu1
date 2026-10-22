import assert from 'node:assert/strict'
import { resolveTouchRegion } from '../src/touch-hit.mjs'
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
console.log('Live2D touch hit-tests passed')
