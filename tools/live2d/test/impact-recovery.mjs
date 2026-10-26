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
  assert.ok(peaks[1] > peaks[0] + 4 && peaks[2] > peaks[1] + 5,
    `distinct light/medium/heavy final rig outputs at ${fps}fps: ${peaks}`)
  const heavy = curves[2]
  assert.ok(peaks[2] > 27 && heavy.some(p => p.x > 10 && p.body > 9.2),
    'real ±10° torso should visibly reach its bound')
  const screenPeaks = curves.map(c => Math.max(...c.map(p => p.screen)))
  assert.ok(screenPeaks[1] > screenPeaks[0] + .025 &&
    screenPeaks[2] > screenPeaks[1] + .015,
    `light/medium/heavy display recoil at ${fps}fps: ${screenPeaks}`)
  assert.ok(screenPeaks[2] > .23, 'heavy impact must visibly tilt the entire model')
  assert.ok(heavy.some(p => p.t > 1500 && p.z < -1), 'one spring return overshoot')
  assert.ok(heavy.some(p => p.t > 350 && p.t < 1000 && p.gaze < -.2 && p.pout > .5),
    'eyes look back while the head is still displaced, with the aggrieved mouth')
  assert.ok(Math.abs(heavy.at(-1).z) < .02 && Math.abs(heavy.at(-1).x) < .02)
  assert.ok(Math.abs(heavy.at(-1).screen) < .015, 'whole body returns to rest')
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
