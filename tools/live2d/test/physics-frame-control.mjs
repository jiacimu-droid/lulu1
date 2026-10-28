import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { readFileSync } from 'node:fs'
import { installPhysicsFrameControl } from '../src/physics-frame-control.mjs'
import { createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { enhanceWolfTouch } from '../src/wolf-rig-profile.mjs'
import { touchReflexFor } from '../src/touch-reflex.mjs'

// Reproduce the SDK's save -> focus/breath -> physics -> draw -> load path.
// Unlike a pose-only test, the physics consumer sees only this frame's input;
// final draw writes cannot survive loadParameters into the next frame.
for (const fps of [15, 30, 60, 120]) {
  const values = { ParamAngleX: 0, ParamAngleY: 0, ParamAngleZ: 0,
    ParamBodyAngleX: 0, ParamBodyAngleY: 0, ParamBodyAngleZ: 0,
    ParamAngleZ2: 0, body_gensui_z: 0, fa_l_1: 0 }
  const core = {}
  const layer = createVisualLayerRuntime({
    has: (_, id) => Object.hasOwn(values, id), read: (_, id) => values[id],
    write: (_, id, value) => { values[id] = value },
  })
  const internal = new EventEmitter()
  let composed = 0, finished = 0, physicsInput = 0, now = 0
  internal.updateNaturalMovements = () => { values.ParamAngleZ += .1 }
  assert.equal(installPhysicsFrameControl(internal, {
    beforePhysics: deltaMs => {
      composed++
      const state = { force: .9 }
      const touch = enhanceWolfTouch(touchReflexFor('hair', 'stroke-left', 0, state, now),
        'hair', 'stroke-left', state, now)
      layer.update(core, { deltaMs, seconds: now / 1000, touchReflex: touch,
        deferPhysicsOutputs: true })
    },
    afterPhysics: () => { finished++; layer.finishPhysics(core) },
  }), true)
  for (let frame = 0; frame < fps; frame++) {
    now += 1000 / fps
    const saved = { ...values }
    internal.updateNaturalMovements(1000 / fps, now)
    physicsInput = values.ParamAngleZ
    values.ParamAngleZ2 = physicsInput * .3
    values.body_gensui_z = values.ParamBodyAngleZ * .4
    values.fa_l_1 = physicsInput * .2
    internal.emit('beforeModelUpdate')
    if (frame === fps - 1) {
      // A fingertip stroke now deliberately avoids the old whole-body
      // 17-degree tilt. Still verify a real head/torso pose enters Physics3
      // before the frame is drawn, even on a 15fps phone.
      assert.ok(physicsInput < -5, `physics receives localized neck pose at ${fps}fps`)
      assert.ok(values.ParamAngleZ2 < -1.3 && values.body_gensui_z < -.4,
        'head and torso secondary outputs survive the final draw stage')
      assert.equal(values.fa_l_1, physicsInput * .2, 'hair is owned by physics')
    }
    Object.assign(values, saved)
  }
  assert.equal(composed, fps, 'one spring/score step per frame')
  assert.equal(finished, fps)
}
assert.equal(installPhysicsFrameControl(new EventEmitter(), {}), false)

// Strong stationary strikes used to be attenuated by .58 even though the
// owner explicitly selected heavy force. Compare the actual tilt asset.
const tilt = JSON.parse(readFileSync(new URL(
  '../../../app/src/main/assets/live2d/wolf-motions/01_tilt_left.motion3.json', import.meta.url)))
const curve = tilt.Curves.find(c => c.Id === 'ParamAngleZ').Segments
const tiltPeak = Math.max(Math.abs(curve[1]), ...curve.slice(2).filter((_, i) =>
  i % 7 === 2 || i % 7 === 4 || i % 7 === 6).map(Math.abs))
for (const side of ['left', 'right']) {
  const pose = force => enhanceWolfTouch(
    touchReflexFor(`cheek-${side}`, 'slap', 0, { force }, 200),
    `cheek-${side}`, 'slap', { force }, 200)
  const light = pose(.25), heavy = pose(.9)
  assert.ok(Math.abs(heavy.roll) > tiltPeak, 'heavy stationary hit exceeds ordinary tilt')
  assert.ok(Math.abs(heavy.roll) > Math.abs(light.roll) + 8)
  assert.ok(Math.abs(heavy.yaw) > 8 && Math.abs(heavy.pitch) > 6)
  assert.equal(Math.sign(heavy.roll), side === 'left' ? -1 : 1)
}
console.log('Live2D frame ordering: physics receives touch, output ownership and stationary force passed')
