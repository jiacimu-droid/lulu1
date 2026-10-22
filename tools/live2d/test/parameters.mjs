import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { CubismParameterAccess } from '../src/cubism-parameters.mjs'
import { createPoseSpring } from '../src/pose-spring.mjs'

// The real Cubism SDK expects CubismIdHandle and can fabricate nonexistent
// parameter indices if handed raw strings. This mock deliberately does so.
const makeCore = () => {
  const names = ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ', 'ParamMouthOpenY', 'bq19']
  const values = [9, -6, 2, 0, 0]
  const virtual = new Map()
  return {
    names, values, virtual,
    getModel: () => ({ parameters: { ids: names, count: names.length } }),
    getParameterIndex: str => names.length + (virtual.set(str, 1), virtual.size - 1),
    setParameterValueById: (id, v) => virtual.set(id, v),
    getParameterValueByIndex: i => values[i],
    setParameterValueByIndex: (i, v) => { values[i] = v },
  }
}

const access = new CubismParameterAccess()
const core = makeCore()
assert.equal(access.index(core, 'ParamMouthOpenY'), 3)
assert.equal(access.index(core, 'ParamAngleX'), 0)
assert.equal(access.index(core, 'bq19'), 4)
assert.equal(access.index(core, 'NonexistentParam'), -1)
assert.equal(access.has(core, 'NonexistentParam'), false)
assert.equal(access.write(core, 'NonexistentParam', 0.5), false)
assert.equal(access.write(core, 'ParamMouthOpenY', 0.85), true)
assert.equal(access.read(core, 'ParamMouthOpenY'), 0.85)
assert.equal(core.values[3], 0.85)
assert.equal(core.virtual.size, 0)
assert.equal(access.write(core, 'ParamAngleX', 0), true)
assert.equal(core.values[0], 0)
assert.equal(access.write(core, 'ParamMouthOpenY', NaN), false)
const other = makeCore()
assert.equal(access.index(other, 'ParamMouthOpenY'), 3)
assert.equal(access.write(other, 'ParamMouthOpenY', 0.4), true)
assert.equal(other.values[3], 0.4)
assert.equal(core.values[3], 0.85)

const stage = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
assert.match(stage, /internal\.on\('beforeModelUpdate'/)
assert.doesNotMatch(stage, /\.getParameterIndex\(|\.setParameterValueById\(/)
assert.match(stage, /setParameter\(core, 'ParamMouthOpenY', smoothedMouthOpen\)/)
// Video eye contact is a continuous default, not a random eye saccade and
// not a conditional release of gaze/body parameters to an idle animation.
assert.doesNotMatch(stage, /gazeReflex|Math\.random\(\).*radius/)
assert.match(stage, /const eyeContact = key === 'gazeX' \|\| key === 'gazeY'/)
assert.match(stage, /if \(!eyeContact && requested == null/)
assert.match(stage, /const headPose = headSpring\.step\(headRequest, deltaMs\)/)
assert.match(stage, /const bodyPose = bodySpring\.step\(bodyRequest, deltaMs\)/)
assert.match(stage, /setParameter\(core, id, bodyPose\[axis\]\)/)
assert.match(stage, /model\.position\.set\(viewWidth \/ 2, viewHeight \* \(isPortrait \? 1\.00 : \.49\)\)/)
// The native receiver is authoritative about final frame size. Oversizing
// every JPEG by 1.38x defeats measured backpressure and causes stutter.
assert.doesNotMatch(stage, /maxEdge \* factor|Math\.min\(2100/)
assert.match(stage, /nativeStreamMaxEdge = Math\.min\(1850,/)
assert.match(stage, /nativeStreamAckMeanMs > 90 \? 0\.86 : 0\.89/)
// The supplied model has physics outputs for secondary skull and torso
// follow-through. Neutralizing only ParamAngleX/Y/Z leaves visible sway.
for (const id of ['ParamAngleX2', 'ParamAngleY2', 'ParamAngleZ2',
  'body_gensui_x', 'body_gensui_y', 'body_gensui_y2', 'body_gensui_y3',
  'body_gensui_z']) assert.ok(stage.includes(id), 'missing ' + id)
assert.match(stage, /secondaryHeadMotionMix/)
assert.match(stage, /secondaryBodyMotionMix/)
assert.match(stage, /getParameter\(core, id\) \?\? 0\) \* secondaryHeadMotionMix/)
assert.match(stage, /getParameter\(core, id\) \?\? 0\) \* secondaryBodyMotionMix/)
// A facial choice can animate eye openness, eyebrow positioning, and subtle
// voicing without releasing centered gaze or fabricating full-arm animation.
assert.match(stage, /eyeOpen: \['ParamEyeLOpen', 'ParamEyeROpen'\]/)
assert.match(stage, /browX: \['ParamBrowLX', 'ParamBrowRX'\]/)
assert.match(stage, /const expressiveVoice = playing && requested != null/)
assert.match(stage, /if \(!eyeContact && requested == null && !hasPriorCue\) continue/)
assert.match(stage, /delete smoothFace\[key\]/)
// Springs never introduce random idle motion and converge across frame rates
// rather than snapping the entire face/head to an exact pose at each cue.
const axes = ['yaw', 'pitch', 'roll']
const spring = createPoseSpring(axes)
for (let i = 0; i < 90; i++) spring.step(null, 16)
assert.deepEqual(spring.step(null, 16), { yaw: 0, pitch: 0, roll: 0 })
for (let i = 0; i < 60; i++) spring.step({ yaw: 16, pitch: -5, roll: 4 }, 16)
const settled = spring.step({ yaw: 16, pitch: -5, roll: 4 }, 16)
assert.ok(Math.abs(settled.yaw - 16) < .1)
assert.ok(Math.abs(settled.pitch + 5) < .1)
assert.ok(Math.abs(settled.roll - 4) < .1)
for (let i = 0; i < 90; i++) spring.step(null, 16)
assert.ok(Math.abs(spring.step(null, 16).yaw) < .05)
const stalled = spring.step({ yaw: -20 }, 20_000)
assert.ok(Number.isFinite(stalled.yaw) && Math.abs(stalled.yaw) < 25)
spring.reset()
assert.deepEqual(spring.step(null, 16), { yaw: 0, pitch: 0, roll: 0 })
assert.match(stage, /function choreography\(beats, immediatePreview = false\)/)
assert.match(stage, /activePerformance\.elapsedMs \+= actualFrameMs/)
assert.match(stage, /timeNow - activePerformance\.lastTickAt/)
assert.match(stage, /if \(performance\) beginPerformance\(performance\.beats, false\)/)
assert.match(stage, /window\.LuluLive2D = .*choreography,/)
console.log('Live2D AIRI-style springs, timed expression beats and real-parameter controls passed')
