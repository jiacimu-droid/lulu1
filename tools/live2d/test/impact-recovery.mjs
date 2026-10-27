import assert from 'node:assert/strict'
import { CubismParameterAccess } from '../src/cubism-parameters.mjs'
import { createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { touchReflexFor } from '../src/touch-reflex.mjs'
import { enhanceWolfTouch, createWolfImpactPresentation } from '../src/wolf-rig-profile.mjs'

// Exercise the final actuator + real-index access + rig clamps, rather than
// asserting only the requested pose. A small-range imported rig remains safe.
function run(force, direction, fps, range = 30) {
  const names = ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
    'ParamBodyAngleX', 'ParamBodyAngleY', 'ParamBodyAngleZ',
    'ParamEyeBallX', 'ParamEyeBallY', 'ParamMouthFunnel', 'ParamBrowLY', 'ParamBrowRY']
  const values = names.map(() => 0)
  // The uploaded wolf maps torso ±10° and head ±30°, unlike the old mock.
  const limits = names.map((_, i) => i < 3 ? range : i < 6 ? Math.min(10, range) : 1)
  const core = {
    getModel: () => ({ parameters: { ids: names, count: names.length,
      minimumValues: limits.map(v => -v), maximumValues: limits } }),
    getParameterValueByIndex: i => values[i],
    setParameterValueByIndex: (i, v) => { values[i] = v },
  }
  const access = new CubismParameterAccess()
  const layer = createVisualLayerRuntime({ has: access.has.bind(access),
    read: access.read.bind(access), write: access.write.bind(access) })
  const samples = []
  const presenter = createWolfImpactPresentation()
  const display = { rotation: 0 }
  const gesture = direction > 0 ? 'slap-right' : 'slap-left'
  for (let t = 0; t < 4500; t += 1000 / fps) {
    const state = { force }
    const pose = t <= 2250 ? enhanceWolfTouch(
      touchReflexFor('cheek-left', gesture, 0, state, t),
      'cheek-left', gesture, state, t) : null
    layer.update(core, { deltaMs: 1000 / fps, seconds: t / 1000, touchReflex: pose, wolf: true })
    presenter.update(display, pose, 1000 / fps)
    assert.ok(values.every((v, i) => Number.isFinite(v) && Math.abs(v) <= limits[i]))
    samples.push({ t, z: values[2] * direction, x: values[0] * direction,
      body: values[5] * direction, screen: display.rotation * direction,
      gaze: values[6] * direction, pout: values[8] })
  }
  presenter.reset(display)
  assert.equal(display.rotation, 0, 'reset should never leave the model leaning')
  return samples
}
for (const fps of [15, 30, 60, 120]) for (const direction of [-1, 1]) {
  const curves = [.25, .55, .9].map(force => run(force, direction, fps))
  const peaks = curves.map(c => Math.max(...c.map(p => p.z)))
  assert.ok(peaks[1] > peaks[0] + 2.5 && peaks[2] > peaks[1] + 3,
    `light and strong swipes must have visibly different neck motion at ${fps}fps: ${peaks}`)
  const heavy = curves[2]
  assert.ok(peaks[2] > 18 && heavy.some(p => p.x > 3),
    'strong impact should rotate the head without toppling the torso')
  assert.ok(heavy.every(p => Math.abs(p.body) < 5),
    'a cheek swipe must never turn the torso to the old 10-degree limit')
  const screenPeaks = curves.map(c => Math.max(...c.map(p => Math.abs(p.screen))))
  assert.ok(screenPeaks.every(p => p <= .015),
    `whole-avatar rotation must remain nearly imperceptible at ${fps}fps: ${screenPeaks}`)
  assert.ok(heavy.some(p => p.t > 1700 && Math.abs(p.z) < 3),
    'the neck should gradually recover over the long release')
  assert.ok(heavy.some(p => p.t > 350 && p.t < 1000 && p.gaze < -.2 && p.pout > .5),
    'eyes look back while the head is still displaced, with the aggrieved mouth')
  assert.ok(Math.abs(heavy.at(-1).z) < .02 && Math.abs(heavy.at(-1).x) < .02)
  assert.ok(Math.abs(heavy.at(-1).screen) < .015, 'display returns to rest')
  run(.9, direction, fps, 10)
}
// Non-impact gestures keep their own actuators (especially directional mouth touch).
for (const gesture of ['stroke-left', 'stroke-right', 'kiss']) {
  enhanceWolfTouch(touchReflexFor('mouth', gesture), 'mouth', gesture)
}
console.log('Live2D impact: force levels, direction, coordinated recoil/recovery, gaze and bounds passed at 15–120fps')

for (const region of ['hand-left', 'arm-right', 'chest', 'body']) {
  const base = touchReflexFor(region, 'slap-right', 0, { force: .9 }, 200)
  const wolf = enhanceWolfTouch(base, region, 'slap-right', { force: .9 }, 200)
  assert.equal(wolf.roll, base.roll, 'body contact must not inherit a face strike')
  assert.deepEqual(wolf.body, base.body)
}


// A simultaneously playing authored Motion3 and a strong physical reflex
// must NOT add their neck/torso angles. Otherwise two individually legal
// ±25/30-degree movements combine to exceed the real Cubism rig range and
// the clamp creates a visually frozen, then suddenly snapping, head.
{
  const values = {
    ParamAngleX: 0, ParamAngleY: 0, ParamAngleZ: 21,
    ParamBodyAngleX: 0, ParamBodyAngleY: 0, ParamBodyAngleZ: 8,
  }
  const writes = []
  const layer = createVisualLayerRuntime({
    has: (_core, id) => Object.hasOwn(values, id),
    read: (_core, id) => values[id],
    write: (_core, id, value) => {
      writes.push({ id, value })
      values[id] = value
    },
  })
  const strike = {
    impactMotion: true, yaw: 0, pitch: 0, roll: 29,
    body: { yaw: 0, pitch: 0, roll: 9 },
  }
  for (let i = 0; i < 45; i++) {
    // Emulate a real authored Motion3 updating its own pose before the
    // beforeModelUpdate callback, as pixi-live2d-display does each frame.
    values.ParamAngleZ = 21
    values.ParamBodyAngleZ = 8
    layer.update(values, { deltaMs: 16, seconds: i * .016,
      authoredMotion: true, touchReflex: strike })
    assert.ok(Number.isFinite(values.ParamAngleZ) &&
      values.ParamAngleZ <= 30.1, 'physical reflex owns the neck during impact')
    assert.ok(Number.isFinite(values.ParamBodyAngleZ) &&
      values.ParamBodyAngleZ <= 10.1, 'physical reflex owns torso during impact')
  }
  assert.ok(writes.some(w => w.id === 'ParamAngleZ' && w.value > 25),
    'the strong touch still produces a visible neck recoil')
  for (let i = 0; i < 270; i++) {
    values.ParamAngleZ = 0
    values.ParamBodyAngleZ = 0
    layer.update(values, { deltaMs: 16, seconds: 1 + i * .016,
      authoredMotion: false, touchReflex: null })
  }
  // Neutral is not literally 0: the renderer intentionally retains a
  // <0.25° breathing sway even at rest. Compare against the SAME idle
  // actuator trajectory instead of falsely requiring exact zero and
  // blocking APKs whenever the breathing phase is not near its zero crossing.
  const idleValues = {
    ParamAngleX: 0, ParamAngleY: 0, ParamAngleZ: 0,
    ParamBodyAngleX: 0, ParamBodyAngleY: 0, ParamBodyAngleZ: 0,
  }
  const idleLayer = createVisualLayerRuntime({
    has: (_core, id) => Object.hasOwn(idleValues, id),
    read: (_core, id) => idleValues[id],
    write: (_core, id, value) => { idleValues[id] = value },
  })
  for (let i = 0; i < 270; i++) {
    idleLayer.update(idleValues, { deltaMs: 16, seconds: 1 + i * .016,
      authoredMotion: false, touchReflex: null })
  }
  assert.ok(Math.abs(values.ParamAngleZ - idleValues.ParamAngleZ) < .05 &&
    Math.abs(values.ParamBodyAngleZ - idleValues.ParamBodyAngleZ) < .05,
    'motion + touch must converge to the normal breathing posture, with no residual recoil')
}
