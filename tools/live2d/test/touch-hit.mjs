import assert from 'node:assert/strict'
import { resolveTouchRegion } from '../src/touch-hit.mjs'
const bounds = { x: 60, y: -160, width: 250, height: 750 }
const hit = (x, y, hits = [], authoredAreas = 0) =>
  resolveTouchRegion({x, y, hits, authoredAreas, bounds})
assert.equal(hit(190, -60), 'head')
assert.equal(hit(190, 120), 'body')
assert.equal(hit(63, -140), null, 'empty background must not count')
assert.equal(hit(500, 140), null, 'outside silhouette must not count')
assert.equal(hit(180, 90, [], 2), null, 'do not override authored missed HitAreas')
assert.equal(hit(180, 90, ['Head'], 2), 'head')
assert.equal(hit(180, 90, ['Arm'], 2), 'arm')
assert.equal(hit(180, 90, ['something-custom'], 2), 'body')
assert.equal(hit(Infinity, 90, ['Head'], 2), null)
console.log('Live2D touch hit-tests passed')
