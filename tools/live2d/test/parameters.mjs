import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { CubismParameterAccess } from '../src/cubism-parameters.mjs'
import {
  kissCameraFrame, kissFaceFrame, kissNuzzleFrame, kissHandoffFrame,
  KISS_HANDOFF_MS, kissCameraHorizontalPosition,
  kissMouthLocalOffsetX, KISS_CAMERA_DURATION_MS,
  KISS_CAMERA_PEAK_ZOOM, WOLF_KISS_MOTION_INDEX,
} from '../src/kiss-camera.mjs'
import { createVisualLayerRuntime } from '../src/visual-layers.mjs'
import { createPerformanceRuntime } from '../src/speech-performance.mjs'
import { createPoseSpring } from '../src/pose-spring.mjs'
import { createMotionTransition } from '../src/motion-transition.mjs'
import { createSpeechMouthRuntime } from '../src/speech-mouth.mjs'
import { resolveTouchRegion } from '../src/touch-hit.mjs'
import { touchReflexFor, touchExpressionFor } from '../src/touch-reflex.mjs'
import { createSpeechExpressionGate, isMouthExclusiveExpression, isEyeExclusiveExpression } from '../src/speech-expression-gate.mjs'
import './conversation-performance.mjs'

import './impact-recovery.mjs'
import './physics-frame-control.mjs'

// This shipped pack is made of genuine Cubism Motion3 files, not mock pose
// objects. Validate counts, signed keyframes and a neutral final pose before
// every release (the same assets are installed beside imported wolf rigs).
const wolfMotionDir = new URL('../../../app/src/main/assets/live2d/wolf-motions/', import.meta.url)
const wolfMotionFiles = readdirSync(wolfMotionDir).filter(name =>
  /^[0-9]{2}_[a-z_]+\.motion3\.json$/.test(name)).sort()
assert.deepEqual(wolfMotionFiles.map(name => name.slice(0, 2)),
  Array.from({ length: 49 }, (_, index) => String(index).padStart(2, '0')),
  'wolf motion indices are stable and contiguous across releases')
const approvedAxes = new Set(['ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
  'ParamBodyAngleX', 'ParamBodyAngleY', 'ParamBodyAngleZ'])
for (const filename of wolfMotionFiles) {
  const motion = JSON.parse(readFileSync(new URL(filename, wolfMotionDir), 'utf8'))
  assert.equal(motion.Version, 3, filename)
  assert.equal(motion.Meta.Loop, false, filename)
  assert.ok(motion.Meta.FadeInTime >= 0 && motion.Meta.FadeInTime <= .5, filename)
  assert.ok(motion.Meta.FadeOutTime >= 0 && motion.Meta.FadeOutTime <= .5, filename)
  assert.ok(!('FadeInTime' in motion) && !('FadeOutTime' in motion), filename)
  assert.equal(motion.Meta.AreBeziersRestricted, true, filename)
  assert.equal(motion.Meta.CurveCount, motion.Curves.length, filename)
  let totalSegments = 0, totalPoints = 0, movingAxes = 0
  for (const curve of motion.Curves) {
    assert.equal(curve.Target, 'Parameter', filename)
    assert.ok(approvedAxes.has(curve.Id), filename + ':' + curve.Id)
    const segments = curve.Segments
    assert.equal(segments[0], 0, filename)
    assert.equal(segments[1], 0, filename)
    assert.equal((segments.length - 2) % 7, 0, filename)
    let prior = 0
    let maximum = 0
    const max = curve.Id.startsWith('ParamBody') ? 10 : 30
    const n = (segments.length - 2) / 7
    for (let i = 0; i < n; i++) {
      const offset = 2 + i * 7
      const [type, c1t, c1v, c2t, c2v, endt, endv] = segments.slice(offset, offset + 7)
      // Each clip is an ease-in/out Bezier with flat endpoints. Measure
      // peak joint angular speed, not only maximum angle: a ±20° model is
      // still unnaturally jerky if it crosses that range in 200ms.
      const startTime = i === 0 ? segments[0] : segments[offset - 2]
      const startValue = i === 0 ? segments[1] : segments[offset - 1]
      const maxRate = filename.startsWith('09_') ? 300 :
        filename.startsWith('33_') ? 240 :
        filename.startsWith('08_') ? 155 : 135
      const peakRate = 1.5 * Math.abs(endv - startValue) / (endt - startTime)
      assert.ok(peakRate <= maxRate + .1, filename +
        ': excessive joint velocity ' + peakRate.toFixed(1) + ' deg/s')
      assert.equal(type, 1, filename + ': cubic motion segment')
      assert.ok(prior < c1t && c1t < c2t && c2t < endt,
        filename + ': strictly increasing motion time')
      for (const v of [c1v, c2v, endv]) {
        assert.ok(Number.isFinite(v) && Math.abs(v) <= max, filename + ': rig bounds')
        maximum = Math.max(maximum, Math.abs(v))
      }
      prior = endt
    }
    assert.ok(Math.abs(prior - motion.Meta.Duration) < .0001, filename + ': full duration')
    assert.equal(segments.at(-1), 0, filename + ': neutral return')
    if (maximum > 1) movingAxes++
    totalSegments += n
    totalPoints += 1 + n * 3
  }
  assert.ok(movingAxes >= 2, filename + ': head and torso both move')
  assert.equal(motion.Meta.TotalSegmentCount, totalSegments, filename)
  assert.equal(motion.Meta.TotalPointCount, totalPoints, filename)
}

// Laughing should be a sequence of staggered chin/shoulder chuckles,
// not two perfectly synchronized left-right head shakes.
const giggle = JSON.parse(readFileSync(
  new URL('19_giggle_wobble.motion3.json', wolfMotionDir), 'utf8'))
const giggleHead = giggle.Curves.find(c => c.Id === 'ParamAngleY')
const giggleTorso = giggle.Curves.find(c => c.Id === 'ParamBodyAngleY')
const giggleRoll = giggle.Curves.find(c => c.Id === 'ParamAngleZ')
assert.ok(giggleHead && giggleTorso && giggleRoll,
  'giggle has expressive chin motion and slower shoulder follow-through')
const keyValues = curve => curve.Segments.slice(2)
  .filter((_, i) => i % 7 === 6)
assert.ok(Math.max(...keyValues(giggleRoll).map(Math.abs)) < 2,
  'laughing must not wag the full head side to side')
assert.notDeepEqual(keyValues(giggleHead), keyValues(giggleTorso),
  'head and shoulders may not wobble in rigid lockstep')
assert.ok(keyValues(giggleHead).slice(0, -1).some(v => v < -2),
  'the visible movement is a small chin dip, not a frozen torso')

// The emotional choreography must be physically different, not just
// five different names for the same six-axis head wobble.
// Preserve all 49 authored indices for saved speeches and manual preview.
const readWolf = name => JSON.parse(readFileSync(new URL(name, wolfMotionDir), 'utf8'))
const valuesOf = (motion, axis) => {
  const curve = motion.Curves.find(c => c.Id === axis)
  return curve ? keyValues(curve) : []
}
const bodyTilt = name => valuesOf(readWolf(name), 'ParamBodyAngleY')
const warm = readWolf('28_warm_lean.motion3.json')
const worried = readWolf('41_worried_lean.motion3.json')
assert.ok(Math.min(...valuesOf(warm, 'ParamBodyAngleY')) < -5,
  'comfort actually leans toward the person, rather than away')
assert.ok(Math.min(...valuesOf(worried, 'ParamBodyAngleY')) < -8,
  'urgent concern leans farther forward than gentle comfort')
assert.ok(Math.min(...valuesOf(worried, 'ParamAngleX')) < -4,
  'urgent concern actually searches the other face')
assert.ok(Math.max(...valuesOf(readWolf('18_slow_weight_shift.motion3.json'),
  'ParamBodyAngleX').map(Math.abs)) < 4,
  'restful posture adjustment must not repeat large left/right swaying')
assert.ok(Math.max(...valuesOf(readWolf('35_bright_ear_bounce.motion3.json'),
  'ParamAngleY')) >= 8,
  'playful acknowledgement needs discernible head beats')
assert.ok(Math.max(...valuesOf(readWolf('35_bright_ear_bounce.motion3.json'),
  'ParamBodyAngleY')) < Math.max(...valuesOf(readWolf('17_happy_bounce.motion3.json'),
  'ParamBodyAngleY')),
  'a small playful nod must not duplicate the bigger celebratory motion')

// The same action must be available in the standalone playground and real
// character video decisions, with a pronounced close-up and neutral release.
assert.equal(WOLF_KISS_MOTION_INDEX, 48)
const kissMotion = JSON.parse(readFileSync(
  new URL('48_kiss_close.motion3.json', wolfMotionDir), 'utf8'))
assert.ok(KISS_CAMERA_DURATION_MS - kissMotion.Meta.Duration * 1000 >= 750 &&
  KISS_CAMERA_DURATION_MS - kissMotion.Meta.Duration * 1000 <= 1800,
  'a short independent renderer tail softens the original Motion3 withdrawal')
assert.ok(kissMotion.Curves.some(c => c.Id === 'ParamBodyAngleY'))
assert.ok(kissMotion.Curves.some(c => c.Id === 'ParamAngleZ'))
assert.equal(kissCameraFrame(0).zoom, 1)
assert.ok(kissCameraFrame(1200).zoom > 1.8, 'kiss approaches the camera closely')
assert.ok(kissCameraFrame(2150).zoom > 1.8, 'kiss lingers close to the screen')
assert.ok(kissCameraFrame(3000).zoom > 1.8, 'the kiss must not rush away at 3s')
assert.ok(kissCameraFrame(3900).zoom < 1.4, 'late withdrawal eases back naturally')
assert.ok(kissCameraFrame(3351).zoom < kissCameraFrame(3349).zoom,
  'the camera tail continues smoothly across the actual Motion3 ending')
assert.equal(kissCameraFrame(KISS_CAMERA_DURATION_MS).zoom, 1)
assert.equal(kissCameraFrame(KISS_CAMERA_DURATION_MS).finished, true)
assert.equal(kissCameraFrame(Number.NaN).finished, true)
assert.ok(kissCameraFrame(1650).zoom > 2.2,
  'kiss contacts the glass more closely than a generic camera zoom')
assert.equal(kissFaceFrame(-1), null)
assert.equal(kissFaceFrame(KISS_CAMERA_DURATION_MS), null)
assert.ok(kissFaceFrame(200).face.eyeOpen > .85, 'the character starts with eye contact')
assert.ok(kissFaceFrame(1650).face.eyeOpen < .08, 'eyes close at contact')
assert.ok(kissFaceFrame(1650).face.pout > .92, 'lips actually purse at contact')
assert.ok(kissFaceFrame(1650).face.smile < 0, 'smiling mouth cannot override the kiss')
assert.ok(kissFaceFrame(4050).face.eyeOpen > .85, 'eyes reopen on withdrawal')
assert.ok(kissFaceFrame(4050).face.pout < .09, 'lips relax before the camera exits')
assert.ok(kissFaceFrame(1650).face.pout > kissFaceFrame(3100).face.pout,
  'the lips pucker at contact and gradually withdraw')
const rubLeft = kissNuzzleFrame(1750)
const rubRight = kissNuzzleFrame(2250)
assert.ok(rubLeft?.yaw > 1.0 && rubRight?.yaw < -1.0,
  'real head joints nuzzle both ways without flinging the entire avatar')
assert.ok(Math.abs(rubLeft.roll) < 4 && Math.abs(rubRight.roll) < 4)
assert.equal(kissNuzzleFrame(4000), null, 'nuzzle ends before slow withdrawal')
assert.equal(kissNuzzleFrame(Number.NaN), null)
const changingGestureZoom = kissCameraFrame(1750).zoom
assert.equal(kissHandoffFrame(changingGestureZoom, 0).zoom, changingGestureZoom,
  'a new chosen Motion3 cannot snap the kiss camera on its first frame')
assert.ok(kissHandoffFrame(changingGestureZoom, 500).zoom > 1.4,
  'the kiss still retreats visibly across low-FPS video frames')
assert.ok(kissHandoffFrame(changingGestureZoom, 1100).zoom > 1 &&
  kissHandoffFrame(changingGestureZoom, 1100).zoom < 1.3)
assert.deepEqual(kissHandoffFrame(changingGestureZoom, KISS_HANDOFF_MS),
  { zoom: 1, finished: true })
assert.equal(kissHandoffFrame(1, Number.NaN).finished, true)
assert.ok(Math.abs(kissCameraFrame(4449).zoom - 1) < .005,
  'camera must not cut back in its last rendered frame')

// The wolf's screen-space lips are slightly right of the model pivot. When
// zoomed 2.26x, the lip center must end up at viewport X=50%, not farther
// right. On withdrawal the exact same transform returns to baseline.
const cameraBase = { x: 350, y: 600, width: 700, scale: 2 }
const mouthOffset = kissMouthLocalOffsetX(cameraBase, null, null)
assert.ok(mouthOffset > 0, 'lips are not assumed to equal the torso pivot')
const projectedLipX = (base, zoom, localOffset) =>
  kissCameraHorizontalPosition(base, zoom, localOffset) +
  zoom * base.scale * localOffset
assert.equal(kissCameraHorizontalPosition(cameraBase, 1, mouthOffset), 350,
  'ordinary framing is unchanged before and after the kiss')
assert.ok(Math.abs(projectedLipX(cameraBase, KISS_CAMERA_PEAK_ZOOM,
  mouthOffset) - 350) < .001, 'lips align to the screen center at contact')
for (const fraction of [.2, .5, .8]) {
  const zoom = 1 + fraction * (KISS_CAMERA_PEAK_ZOOM - 1)
  const initialMouth = cameraBase.x + cameraBase.scale * mouthOffset
  assert.ok(Math.abs(projectedLipX(cameraBase, zoom, mouthOffset) -
    (initialMouth + fraction * (350 - initialMouth))) < .001,
    'the mouth slides continuously with zoom rather than snapping')
}
const calibratedOffset = kissMouthLocalOffsetX(cameraBase,
  { x: 140, width: 500 }, { u: .62 })
assert.ok(Math.abs(calibratedOffset - 50) < .001,
  'manual mouth calibration overrides the estimated offset')
assert.ok(Math.abs(projectedLipX(cameraBase, KISS_CAMERA_PEAK_ZOOM,
  calibratedOffset) - 350) < .001, 'calibrated mouth reaches exact screen center')
const resizedBase = { ...cameraBase, width: 1000, x: 500, scale: 2.7 }
assert.ok(Math.abs(projectedLipX(resizedBase, KISS_CAMERA_PEAK_ZOOM,
  calibratedOffset) - 500) < .001, 'resizing preserves the model-local anchor')
assert.equal(kissCameraHorizontalPosition(cameraBase, 1, calibratedOffset), 350)
assert.ok(Number.isFinite(kissMouthLocalOffsetX(cameraBase, null, { u: 2 })),
  'invalid calibration safely falls back')

// A finished body motion keeps its last real rendered pose through the next
// idle frame, then returns smoothly. Entrance and exit operate on opposite
// sides of the face/touch composition so they cannot silently cancel it.
let transitionTime = 0
const transitionValues = new Map([['ParamAngleX', 16], ['ParamMouthForm', .25]])
const transition = createMotionTransition({
  now: () => transitionTime,
  read: (_core, id) => transitionValues.get(id),
  write: (_core, id, value) => transitionValues.set(id, value),
})
transition.capture({}, ['ParamAngleX', 'ParamMouthForm'])
transition.release(['ParamAngleX'])
transitionValues.set('ParamAngleX', 0)
transition.update({}, 'before')
assert.equal(transitionValues.get('ParamAngleX'), 0, 'exit cannot overwrite motion input')
transition.update({}, 'after')
assert.equal(transitionValues.get('ParamAngleX'), 16, 'first exit frame preserves prior pose')
assert.equal(transitionValues.get('ParamMouthForm'), .25,
  'non-authored facial channels remain untouched')
transitionTime = 220
transitionValues.set('ParamAngleX', 0)
transition.update({}, 'after')
assert.ok(transitionValues.get('ParamAngleX') > 7 &&
  transitionValues.get('ParamAngleX') < 9, 'motion returns through a smooth midpoint')
transitionTime = 440
transitionValues.set('ParamAngleX', 0)
transition.update({}, 'after')
assert.equal(transitionValues.get('ParamAngleX'), 0)
transition.reset()
transitionValues.set('ParamAngleX', 6)
transition.capture({}, ['ParamAngleX'])
transition.begin(['ParamAngleX'], 200)
transitionValues.set('ParamAngleX', -6)
transition.update({}, 'before')
assert.equal(transitionValues.get('ParamAngleX'), 6, 'motion entrance starts at prior frame')
transitionTime += 100
transitionValues.set('ParamAngleX', -6)
transition.update({}, 'before')
assert.ok(Math.abs(transitionValues.get('ParamAngleX')) < .01,
  'a new authored motion fades into its own pose')

// Opposite directions must mirror every controlled angle rather than
// accidentally moving the body in the same direction. It is easy for a
// hand-authored head/body curve to be mirrored on only one axis.
for (const [left, right] of [
  ['01_tilt_left.motion3.json', '02_tilt_right.motion3.json'],
  ['05_turn_left.motion3.json', '06_turn_right.motion3.json'],
  ['10_peek_left.motion3.json', '11_peek_right.motion3.json'],
  ['14_sway_left.motion3.json', '15_sway_right.motion3.json'],
]) {
  const a = JSON.parse(readFileSync(new URL(left, wolfMotionDir), 'utf8'))
  const b = JSON.parse(readFileSync(new URL(right, wolfMotionDir), 'utf8'))
  assert.equal(a.Meta.Duration, b.Meta.Duration)
  assert.deepEqual(a.Curves.map(c => c.Id), b.Curves.map(c => c.Id))
  for (let i = 0; i < a.Curves.length; i++) {
    const first = a.Curves[i].Segments, second = b.Curves[i].Segments
    assert.equal(first.length, second.length)
    for (let j = 0; j < first.length; j++) {
      // Segment type and timestamps are identical. Only pose values mirror.
      const isValue = j === 1 || j >= 2 && (j - 2) % 7 !== 0 &&
        (j - 2) % 7 % 2 === 0
      // Axis pitch can intentionally match in a side sway.
      const signedAxis = a.Curves[i].Id !== 'ParamAngleY'
      if (isValue && signedAxis) assert.ok(Math.abs(first[j] + second[j]) < .0001,
        left + ' / ' + right + ': pose values mirror at ' + j)
      else if (!isValue) assert.equal(first[j], second[j])
    }
  }
}

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
other.getModel = () => ({ parameters: {
  ids: other.names, count: other.names.length,
  minimumValues: [-10, -10, -10, 0, 0],
  maximumValues: [10, 10, 10, .7, 1],
}})
assert.equal(access.index(other, 'ParamMouthOpenY'), 3)
assert.equal(access.write(other, 'ParamMouthOpenY', 0.4), true)
assert.equal(access.write(other, 'ParamMouthOpenY', 5), true)
assert.equal(access.read(other, 'ParamMouthOpenY'), .7)
assert.equal(access.write(other, 'ParamAngleX', -25), true)
assert.equal(access.read(other, 'ParamAngleX'), -10)
assert.equal(other.values[3], 0.7)
assert.equal(core.values[3], 0.85)

// A native decoded frame must not invalidate the entire Compose stage.
// This is especially important on the HyperOS ImageView compatibility route.
const androidStage = readFileSync(new URL(
  '../../../app/src/main/java/com/jiacimu/lulu/Live2DCallStage.kt',
  import.meta.url), 'utf8')
const fullscreenVideo = readFileSync(new URL(
  '../../../app/src/main/java/com/jiacimu/lulu/Live2DFullscreenVideoCall.kt',
  import.meta.url), 'utf8')
assert.match(androidStage, /AtomicLong\(0L\)/)
assert.match(androidStage, /lastBinaryFrameAt\.set\(/)
assert.match(androidStage, /lastBinaryFrameAt\.get\(\)/)
assert.doesNotMatch(androidStage, /var lastBinaryFrameAt by remember\(webView\)/)
assert.match(androidStage, /Live2DFrameStats\(reportIntervalMs = 2_000L\)/)
assert.match(fullscreenVideo, /val qualityMode = "smooth"/)
// WebGL direct trial MUST verify the real Android surface and revert without
// restarting the call. The old JPEG bridge remains present for HyperOS.
assert.match(fullscreenVideo, /probePresentedWebGL\(cameraContext, expressionStage\)/)
assert.match(fullscreenVideo, /PixelCopy\.request\(window, rect, image/)
assert.match(fullscreenVideo, /directWebGL = false/)
assert.match(fullscreenVideo, /nativeMirrorEnabled = !directWebGL/)
assert.match(fullscreenVideo, /autoDisplayAttempted by remember/)
assert.match(fullscreenVideo, /directWebGL = true/)
assert.match(fullscreenVideo, /gpuSurfaceRequested = true/)
assert.match(fullscreenVideo, /putString\("preferred:\$modelUrl", "direct"\)/)
assert.match(fullscreenVideo, /putString\("preferred:\$modelUrl", "gpu"\)/)
assert.match(fullscreenVideo, /if \(!ok\) gpuSurfaceRequested = false|gpuSurfaceRequested = false/)
assert.doesNotMatch(fullscreenVideo, /试用 WebGL 直显|试用 GPU 无压缩画面|点击切流畅|点击切高清/)
assert.doesNotMatch(fullscreenVideo, /DropdownMenuItem\([\s\S]*?gpuSurfaceRequested = !gpuSurfaceRequested/)
assert.match(fullscreenVideo, /rendererPerformance = detail/)
// Both display paths run the same WebGL model; presentation is a separate
// verified fact, not a second rendering-FPS counter.
assert.match(fullscreenVideo, /"模型绘制 · " \+ rendererPerformance/)
assert.match(fullscreenVideo, /gpuSurfaceVerified -> "显示 · GPU Surface 已验证 · 屏幕 FPS 未测"/)
assert.match(fullscreenVideo, /directVerified -> "显示 · WebGL 直显已验证 · 屏幕 FPS 未测"/)
assert.match(fullscreenVideo, /gpuSurfaceRequested -> "显示 · GPU Surface 验证中（JPEG 兜底）"/)
assert.match(fullscreenVideo, /else -> "显示 · JPEG 原生镜像"/)
assert.match(fullscreenVideo, /displayChannelLabel \+ displayChannelDetail/)
assert.doesNotMatch(fullscreenVideo, /Text\(rendererPerformance, fontSize/)

const stage = readFileSync(new URL('../src/stage.js', import.meta.url), 'utf8')
assert.match(stage, /installPhysicsFrameControl\(internal/)
assert.doesNotMatch(stage, /\.getParameterIndex\(|\.setParameterValueById\(/)
assert.match(stage, /speechMouth\.update\(core, \{/)
assert.match(stage, /wolfRigActive = isWolfRig/)
assert.match(stage, /enhanceWolfTouch\(/)
assert.match(stage, /speechMouth\.reset\(\)/)
assert.doesNotMatch(stage, /smoothedMouthOpen/)
assert.match(stage, /visualLayers\.update\(core, \{/)
assert.match(stage, /performanceRuntime\.tick\(\(beat, lateMs\) =>/)
assert.match(stage, /applyPerformanceBeat\(beat, lateMs\)/)
assert.match(stage, /performanceRuntime\.stopSpeech\(\)/)
assert.match(stage, /performanceRuntime\.schedule\(selected, !!immediatePreview\)/)
assert.match(stage, /scoredBeats\.filter\(beat =>/)
assert.match(stage, /model\.position\.set\(viewWidth \/ 2, viewHeight \* \(isPortrait \? 1\.00 : \.49\)\)/)
assert.doesNotMatch(stage, /gazeReflex|Math\.random\(\).*radius/)
assert.doesNotMatch(stage, /maxEdge \* factor|Math\.min\(2100/)
assert.match(stage, /nativeStreamMaxEdge = Math\.min\(1850,/)
assert.match(stage, /nativeStreamAckMeanMs > 90 \? 0\.86 : 0\.89/)
assert.match(stage, /recordRenderTick\(performance\.now\(\) - started/)
assert.match(stage, /framePerf\.encodeCostMs/)
assert.match(stage, /const frameBudgetMs = nativeStreamAckMeanMs < 28 \? 22/)
assert.match(stage, /app\.ticker\.maxFPS = nativeStreamEnabled \? 36 : 0/)
assert.match(stage, /if \(app\?\.ticker\) app\.ticker\.maxFPS = 0/)
assert.doesNotMatch(stage, /app\.ticker\.maxFPS = 60/)
assert.doesNotMatch(stage, /scheduleNativeStreamFrame\(Math\.max\(0, 34 -/)


const lip = new Map([
  ['ParamMouthOpenY', 0], ['ParamJawOpen', 0],
  ['ParamMouthPuckerWiden', 0], ['ParamMouthFunnel', 0],
  ['ParamMouthShrug', 0],
])
const lips = createSpeechMouthRuntime({
  has: (_core, id) => lip.has(id),
  write: (_core, id, value) => { lip.set(id, value); return true },
})
const speechValues = { deltaMs: 16, speaking: true, pcmAvailable: true, level: .9 }
for (let i = 0; i < 25; i++) lips.update(core, speechValues)
assert.ok(lip.get('ParamMouthOpenY') > .83, 'PCM must open the mouth smoothly')
assert.ok(lip.get('ParamJawOpen') > .48, 'jaw follows mouth with its own latency')
assert.ok(lip.get('ParamMouthPuckerWiden') > .07, "wolf secondary mouth rig is used")
lips.reset()
for (let i = 0; i < 24; i++) lips.update(core, { ...speechValues, wolf: true })
assert.ok(lip.get('ParamMouthPuckerWiden') > 7 && lip.get('ParamMouthPuckerWiden') < 15,
  'wolf mouth width must follow CDI3 -30..30 range')
lips.update(core, { ...speechValues, level: 0 })
assert.ok(lip.get('ParamMouthOpenY') > .2, 'silence should start closing rather than snap')
for (let i = 0; i < 85; i++) lips.update(core, { deltaMs: 16, speaking: false })
assert.ok(lip.get('ParamMouthOpenY') < .015 && lip.get('ParamJawOpen') < .015,
  'mouth and jaw return without sticking open')
lips.reset()
lips.update(core, { deltaMs: 16, speaking: true, pcmAvailable: true,
  level: 1, blocked: true })
assert.equal(lip.get('ParamMouthOpenY'), 0, 'tongue/cover owns the speaking mouth')
const onlyOpen = new Map([['ParamMouthOpenY', 0]])
const basic = createSpeechMouthRuntime({
  has: (_core, id) => onlyOpen.has(id),
  write: (_core, id, value) => onlyOpen.set(id, value),
})
basic.update(core, { speaking: true, deltaMs: 16, pcmAvailable: true, level: .8 })
assert.ok(onlyOpen.get('ParamMouthOpenY') > 0,
  'models without wolf secondary mouth channels still work')

// Final-layer Cubism ownership must be tested by executing writes rather
// than searching the former monolithic stage for string names.
const valuesById = new Map([
  ['ParamAngleX', 14], ['ParamAngleY', 8], ['ParamAngleZ', -7],
  ['ParamAngleX2', 9], ['ParamAngleY2', 5], ['ParamAngleZ2', -8],
  ['ParamBodyAngleX', 7], ['body_gensui_x', 4],
  ['ParamEyeBallX', 0.5], ['ParamEyeBallY', -0.3],
  ['ParamMouthForm', 0.4], ['ParamEyeLOpen', 1], ['ParamEyeROpen', 1],
  ['ParamEyeLSmile', 0], ['ParamEyeRSmile', 0],
  ['ParamBreath', 0],
])
const readValue = (_core, id) => valuesById.get(id)
const writeValue = (_core, id, value) => { valuesById.set(id, value); return true }
const rig = createVisualLayerRuntime({
  has: (_core, id) => valuesById.has(id),
  read: readValue, write: writeValue,
})
let seconds = 0
function frame(head = null, body = null, face = null, speaking = false, deltaMs = 16) {
  seconds += deltaMs / 1000
  rig.update(core, { deltaMs, head, body, face, speaking, seconds })
}
for (let i = 0; i < 50; i++) frame()
for (const id of ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
  'ParamAngleX2', 'ParamAngleY2', 'ParamAngleZ2',
  'ParamBodyAngleX', 'body_gensui_x', 'ParamEyeBallX', 'ParamEyeBallY']) {
  const minorMotion = ['ParamAngleX', 'ParamAngleY', 'ParamBodyAngleX'].includes(id)
  const bound = id === 'ParamAngleZ' ? 4 : minorMotion ? .8 : .05
  assert.ok(Math.abs(valuesById.get(id)) < bound, 'idle unexpectedly wandered: ' + id)
}
assert.ok(valuesById.get('ParamBreath') > 0.3)
// A strong palm contact must reach a visible neck-roll impulse within
// a few real video frames. Head yaw should stay smaller than the tilt, and
// the same spring should return smoothly after the actual impact ends.
rig.reset()
for (let i = 0; i < 18; i++) frame()
const slapContact = touchReflexFor('cheek-left', 'slap-right', 0, {
  force: .9, impact: 70, shyness: 0,
}, 90)
for (let i = 0; i < 8; i++) {
  seconds += .016
  rig.update(core, { deltaMs: 16, touchReflex: slapContact, seconds })
}
const rollAfterSlap = Math.abs(valuesById.get('ParamAngleZ'))
const yawAfterSlap = Math.abs(valuesById.get('ParamAngleX'))
assert.ok(rollAfterSlap > 12,
  'the palm recoil must be visible before the initial impact expires')
assert.ok(rollAfterSlap > yawAfterSlap * 2,
  'palm contact must primarily tilt around the neck, not shake the jaw')
for (let i = 0; i < 135; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamAngleZ')) < 4,
  'recoil must release naturally to neutral side tilt')
rig.reset()
// Actual touch -> Cubism body/secondary parameters, not just an expression.
// Drive the real final-stage runtime at video cadence, then verify release.
const cheekContact = touchReflexFor('cheek-left', 'stroke-right', 1, { force: .65 }, 350)
for (let i = 0; i < 65; i++) {
  valuesById.set('ParamAngleX2', 3)
  valuesById.set('body_gensui_x', 4)
  seconds += .016
  rig.update(core, { deltaMs: 16, touchReflex: cheekContact, speaking: false, seconds })
}
assert.ok(Math.abs(valuesById.get('ParamBodyAngleX')) > .7,
  'touch must animate the real torso joint without a full-body lunge')
assert.ok(Math.abs(valuesById.get('ParamAngleX2')) > 1,
  'touch must not suppress secondary head follow-through')
assert.ok(Math.abs(valuesById.get('body_gensui_x')) > 1,
  'touch must not suppress secondary torso physics')
for (let i = 0; i < 140; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamBodyAngleX')) < .8,
  'touch torso should return to an idle pose')
assert.ok(Math.abs(valuesById.get('ParamAngleX2')) < .05,
  'secondary touch pose must fully release when not touching')
assert.ok(Math.abs(valuesById.get('body_gensui_x')) < .05,
  'secondary torso physics must settle without idle wandering')
// Same imported rig, same clock: only physical breathing cadence varies.
rig.update(core, { deltaMs: 16, seconds: 1.4, physical: { energy: 95, sleepPressure: 8 } })
const restedBreath = valuesById.get('ParamBreath')
rig.update(core, { deltaMs: 16, seconds: 1.4, physical: { energy: 18, sleepPressure: 88 } })
const tiredBreath = valuesById.get('ParamBreath')
assert.notEqual(restedBreath, tiredBreath)
assert.ok(restedBreath >= .35 && restedBreath <= .65)
assert.ok(tiredBreath >= .35 && tiredBreath <= .65)
for (let i = 0; i < 60; i++) frame({ roll: 12 }, null, null, true)
assert.ok(valuesById.get('ParamAngleZ') > 11)
for (let i = 0; i < 90; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamAngleZ')) < 4,
  'the selected head tilt must release into a subtle idle side tilt')
valuesById.set('ParamMouthForm', .62)
frame()
assert.equal(valuesById.get('ParamMouthForm'), .62,
  'idle should release expression parameters to the model')
for (let i = 0; i < 45; i++) frame(null, null, { smile: .7, eyeOpen: .55 }, true)
assert.ok(valuesById.get('ParamMouthForm') > .55)
assert.ok(valuesById.get('ParamEyeLOpen') < .7)
for (let i = 0; i < 90; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamMouthForm') - .62) < .05,
  'released smile must restore the imported model expression')
assert.ok(Math.abs(valuesById.get('ParamEyeLOpen') - 1) < .05)
valuesById.set('ParamMouthForm', .28)
frame()
assert.equal(valuesById.get('ParamMouthForm'), .28,
  'after face release, imported expressions own facial parameter')

const rigFace = new Map([
  ['ParamBrowLY', 0], ['ParamBrowRY', 0], ['ParamCheekPuff', 0],
])
const faceRig = createVisualLayerRuntime({
  has: (_core, id) => rigFace.has(id),
  read: (_core, id) => rigFace.get(id),
  write: (_core, id, value) => rigFace.set(id, value),
})
for (let i = 0; i < 50; i++) faceRig.update(core, {
  deltaMs: 16, seconds: i / 60, speaking: false,
  touchReflex: { face: { cheekPuff: .6, browLeftY: .4, browRightY: -.2 } },
})
assert.ok(rigFace.get('ParamCheekPuff') > .5,
  'touch must drive the model cheek puff parameter')
assert.ok(rigFace.get('ParamBrowLY') > .3 && rigFace.get('ParamBrowRY') < -.1,
  'independent brow channels allow asymmetric expression')
for (let i = 0; i < 95; i++) faceRig.update(core, {
  deltaMs: 16, seconds: 2 + i / 60, speaking: false,
})
assert.ok(Math.abs(rigFace.get('ParamCheekPuff')) < .08,
  'cheek relaxes after the pointer leaves')
const quietHead = new Map([['ParamAngleZ', 0], ['ParamBodyAngleZ', 0]])
const quietRig = createVisualLayerRuntime({
  has: (_core, id) => quietHead.has(id),
  read: (_core, id) => quietHead.get(id),
  write: (_core, id, value) => quietHead.set(id, value),
})
for (let i = 0; i < 500; i++) quietRig.update(core, {
  deltaMs: 16, seconds: i * .016, speaking: true, voiceLevel: .65,
})
assert.ok(Math.abs(quietHead.get('ParamAngleZ')) < .03,
  'ordinary speech must not oscillate the head Z/neck tilt')

// Wolf cheek touch -> real physics input; release -> imported resting pose.
const physicalMouth = new Map([['ParamMouthX', 0]])
const physicalMouthRig = createVisualLayerRuntime({
  has: (_c, id) => physicalMouth.has(id),
  read: (_c, id) => physicalMouth.get(id),
  write: (_c, id, n) => physicalMouth.set(id, n),
})
for (let i = 0; i < 45; i++) physicalMouthRig.update(core, {
  deltaMs: 16, seconds: i * .016, touchReflex: { face: { mouthX: -8 } },
})
assert.ok(physicalMouth.get('ParamMouthX') < -6,
  'touch should drive meaningful wolf mouth physics input')
for (let i = 0; i < 95; i++) physicalMouthRig.update(core, {
  deltaMs: 16, seconds: 1 + i * .016,
})
assert.ok(Math.abs(physicalMouth.get('ParamMouthX')) < .05,
  'wolf mouth input must return after contact')

// During an actively spoken smiling line, PCM mouth opening is independent
// of the smile channel; touching the face temporarily owns that surface,
// then the character's own spoken smile returns.
for (let i = 0; i < 45; i++) frame(null, null, { smile: .7, eyeSmile: .4 }, true)
assert.ok(valuesById.get('ParamMouthForm') > .6,
  'smile must remain on the face while audio indicates speech')
assert.ok(valuesById.get('ParamEyeLSmile') > .32,
  'smiling eyes accompany a smiling voice')
for (let i = 0; i < 45; i++) {
  seconds += .016
  rig.update(core, { deltaMs: 16, face: { smile: .7, eyeSmile: .4 },
    touchReflex: { yaw: -3, roll: -9, face: { smile: -.48, eyeOpen: .82 } },
    speaking: true, voiceLevel: .4, seconds })
}
assert.ok(valuesById.get('ParamMouthForm') < -.3,
  'the local facial touch should be visible even when a selected smile is speaking')
for (let i = 0; i < 45; i++) frame(null, null, { smile: .7, eyeSmile: .4 }, true)
assert.ok(valuesById.get('ParamMouthForm') > .55,
  'the selected smile should smoothly return after contact ends')
// A wink must not be flattened into two identical eyelids on release.
valuesById.set('ParamEyeLOpen', .54)
valuesById.set('ParamEyeROpen', 1)
for (let i = 0; i < 45; i++) frame(null, null, { eyeOpen: .25 })
for (let i = 0; i < 90; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamEyeLOpen') - .54) < .05)
assert.ok(Math.abs(valuesById.get('ParamEyeROpen') - 1) < .05)
// If the authored expression changes while the AI cue is playing, the new
// author's value must win when the temporary visual cue ends.
valuesById.set('ParamMouthForm', .3)
for (let i = 0; i < 45; i++) frame(null, null, { smile: .8 })
valuesById.set('ParamMouthForm', -.25)
for (let i = 0; i < 90; i++) frame()
assert.ok(Math.abs(valuesById.get('ParamMouthForm') + .25) < .05)
// A head caress may briefly soften eyelids and lift the head without
// replacing the character's own authored expression. Releasing the touch
// must restore the imported neutral face without extra model/API turns.
for (let i = 0; i < 55; i++) {
  rig.update(core, {
    deltaMs: 16, head: null, body: null, face: null,
    touchReflex: { yaw: 2, pitch: 7, roll: -2, face: { eyeOpen: .52, eyeSmile: .42 } },
    speaking: false, seconds,
  })
}
assert.ok(valuesById.get('ParamAngleY') > 4, 'caress should lift an authored head parameter')
assert.ok(valuesById.get('ParamEyeLOpen') < .7, 'caress should soften available eyelids')
for (let i = 0; i < 110; i++) {
  rig.update(core, {
    deltaMs: 16, head: null, body: null, face: null,
    touchReflex: null, speaking: false, seconds,
  })
}
assert.ok(Math.abs(valuesById.get('ParamEyeLOpen') - .54) < .05,
  'left eyelid must restore the authored wink, not a forced neutral face')
assert.ok(Math.abs(valuesById.get('ParamEyeROpen') - 1) < .05,
  'right eyelid must independently restore the authored opening')
// The real star/closed-eye exp3 is already on the Cubism core when the
// final actuator runs. It must win over an older caress/blink release.
{
  const eyeValues = new Map([
    ['ParamEyeLOpen', 1], ['ParamEyeROpen', 1],
    ['ParamEyeLSmile', 0], ['ParamEyeRSmile', 0],
    ['ParamEyeBallX', 0], ['ParamEyeBallY', 0],
  ])
  const eyeWrites = []
  const eyeLayer = createVisualLayerRuntime({
    has: (_core, id) => eyeValues.has(id),
    read: (_core, id) => eyeValues.get(id),
    write: (_core, id, value) => { eyeWrites.push(id); eyeValues.set(id, value) },
  })
  const cue = { face: { eyeOpen: .25, eyeSmile: .55, gazeX: .45, gazeY: -.2 } }
  for (let i = 0; i < 48; i++) eyeLayer.update({}, {
    deltaMs: 16, seconds: i * .016, touchReflex: cue,
  })
  assert.ok(eyeValues.get('ParamEyeLOpen') < .4)
  assert.ok(eyeValues.get('ParamEyeBallX') > .3)
  // A verified star-eye expression changes the actual authored parameters.
  // No local eye/gaze write is permitted, including from stale smoothing.
  eyeValues.set('ParamEyeLOpen', .91)
  eyeValues.set('ParamEyeROpen', .86)
  eyeValues.set('ParamEyeLSmile', .77)
  eyeValues.set('ParamEyeRSmile', .72)
  eyeValues.set('ParamEyeBallX', -.45)
  eyeValues.set('ParamEyeBallY', .2)
  eyeWrites.length = 0
  eyeLayer.update({}, { deltaMs: 16, seconds: 1,
    touchReflex: cue, expressionActive: true, eyeShapeLocked: true })
  assert.deepEqual([...eyeValues.values()], [.91, .86, .77, .72, -.45, .2],
    'authored star eyes and gaze must survive a simultaneous tactile reflex')
  assert.equal(eyeWrites.length, 0,
    'the final layer must not write any authored eye channel while locked')
  // Once the special expression ends, normal bilateral touch can take over.
  for (let i = 0; i < 48; i++) eyeLayer.update({}, {
    deltaMs: 16, seconds: 1 + i * .016, touchReflex: cue,
    eyeShapeLocked: false,
  })
  assert.ok(eyeValues.get('ParamEyeLOpen') < .4)
  assert.ok(Math.abs(eyeValues.get('ParamEyeLOpen') -
    eyeValues.get('ParamEyeROpen')) < .02,
    'the same touch closes both eyes together after the authored cue ends')
}
rig.reset()
frame()
assert.ok(Math.abs(valuesById.get('ParamAngleX')) < .8)
// Touch recoil fades independently of the character's chosen head movement.
for (let i = 0; i < 35; i++) {
  seconds += .016
  rig.update(core, { deltaMs: 16, touchReflex: {yaw: 3, roll: -1}, seconds })
}
assert.ok(valuesById.get('ParamAngleX') > 2)
for (let i = 0; i < 100; i++) {
  seconds += .016
  rig.update(core, { deltaMs: 16, touchReflex: null, seconds })
}
assert.ok(Math.abs(valuesById.get('ParamAngleX')) < .8)

// Speech score uses real monotonic time; dropped frames never delay gestures,
// repeated 'playing' callbacks do not replay any completed beat.
let clock = 1000
const performance = createPerformanceRuntime({ now: () => clock })
const seen = []
assert.equal(performance.schedule([
  { atMs: 1100, expression: 'star' }, { atMs: 0, face: { smile: .3 } },
  { atMs: 450, head: { roll: 8 } },
]), true)
clock += 80
assert.equal(performance.tick(beat => seen.push(beat)), 0)
performance.startSpeech()
performance.startSpeech()
assert.equal(performance.tick(beat => seen.push(beat)), 1)
clock += 630
assert.equal(performance.tick(beat => seen.push(beat)), 1)
clock += 800
assert.equal(performance.tick(beat => seen.push(beat)), 1)
assert.deepEqual(seen.map(beat => beat.atMs), [0, 450, 1100])
clock += 200
assert.equal(performance.tick(beat => seen.push(beat)), 0)
performance.schedule([{ atMs: 5000, head: { yaw: 5 } }])
performance.stopSpeech()
clock += 6000
assert.equal(performance.tick(beat => seen.push(beat)), 0)
assert.equal(performance.state().remaining, 0)
performance.schedule([{ atMs: 100, face: { smile: .6 } }], true)
clock += 110
assert.equal(performance.tick(beat => seen.push(beat)), 1)
assert.equal(seen.length, 4)
performance.reset()
assert.equal(performance.state().queued, false)

// Start promptly, release slowly, and eventually regain the neutral pose.
const soft = createPoseSpring(['yaw'], 100, 1.04, 25)
for (let i = 0; i < 65; i++) soft.step({ yaw: 12 }, 16)
const engaged = soft.step({ yaw: 12 }, 16).yaw
for (let i = 0; i < 20; i++) soft.step(null, 16, true)
const returning = soft.step(null, 16, true).yaw
assert.ok(returning > 3 && returning < engaged,
  'short poses must retain a visible, gradually releasing tail')
for (let i = 0; i < 110; i++) soft.step(null, 16, true)
assert.ok(Math.abs(soft.step(null, 16, true).yaw) < .15,
  'a released pose must eventually settle')
// The independent spring is clamped on long stalls and never moves in idle.
const spring = createPoseSpring(['yaw', 'pitch', 'roll'])
for (let i = 0; i < 90; i++) spring.step(null, 16)
assert.deepEqual(spring.step(null, 16), { yaw: 0, pitch: 0, roll: 0 })
for (let i = 0; i < 60; i++) spring.step({ yaw: 16, pitch: -5, roll: 4 }, 16)
assert.ok(Math.abs(spring.step({ yaw: 16 }, 16).yaw - 16) < .1)
for (let i = 0; i < 90; i++) spring.step(null, 16)
assert.ok(Math.abs(spring.step(null, 16).yaw) < .05)
const stalled = spring.step({ yaw: -20 }, 20000)
assert.ok(Number.isFinite(stalled.yaw) && Math.abs(stalled.yaw) < 25)
// Model authored exp3 metadata, not free-form text or other role personalities,
// determines whether the expression physically occupies the speaking mouth.
assert.equal(isMouthExclusiveExpression({
  Parameters: [{ Id: 'ParamTongueOut', Value: 1 }]
}), true)
assert.equal(isMouthExclusiveExpression({
  Parameters: [{ Id: 'ParamEyeLSmile', Value: .75 }]
}), false)
assert.equal(isMouthExclusiveExpression({
  Parameters: [{ Id: 'ParamTongueOut', Value: 0 }]
}), false)
assert.equal(isEyeExclusiveExpression({
  Parameters: [{ Id: 'ParamEyeLOpen', Value: .25 }]
}, 'sleepy'), true)
assert.equal(isEyeExclusiveExpression({
  Parameters: [{ Id: 'ParamMouthForm', Value: .25 }]
}, 'blush'), false)
assert.equal(isEyeExclusiveExpression(null, '小狼-星星眼'), true)
let audioMs = 1000
const gate = createSpeechExpressionGate({ now: () => audioMs })
assert.equal(gate.started().reset, false)
gate.sample({ audible: true, pcm: true, level: .6 })
assert.equal(gate.request('吐舌', true), false)
audioMs += 450
assert.equal(gate.tick().play, null,
  'a speaking PCM level cannot trigger tongue expression')
gate.sample({ audible: true, pcm: true, level: .02 })
audioMs += 215
const quietBeat = gate.tick()
assert.equal(quietBeat.play, '吐舌', 'a genuine quiet interval may show a tongue')
assert.equal(gate.state().active, true)
audioMs += 50
assert.equal(gate.sample({ audible: true, pcm: true, level: .5 }).reset, true,
  'mouth expression must clear instantly when vocal PCM resumes')
assert.equal(gate.state().active, false)
assert.equal(gate.request('吐舌', true), false)
gate.sample({ audible: true, pcm: false, level: 0 })
audioMs += 2000
assert.equal(gate.tick().play, null, 'without PCM, do not invent a mid-sentence pause')
assert.equal(gate.finished().play, '吐舌',
  'non-PCM TTS executes the gesture when actual playback finishes')
audioMs += 780
assert.equal(gate.tick().reset, true, 'a tongue cannot remain out for seconds')
gate.reset()
gate.started()
gate.request('吐舌', true)
assert.equal(gate.interrupted().reset, false)
audioMs += 2000
assert.equal(gate.finished().play, null,
  'interrupted utterance may not replay an old tongue cue')
gate.reset()
assert.equal(gate.request('吐舌', false), true,
  'visual-only, silent reactions remain possible')
assert.equal(gate.started().reset, true,
  'a new sentence closes an existing tongue before playing sound')
assert.ok(stage.includes('mouthExpressionGate.tick()'))
assert.ok(stage.includes('!eyePoseBusy'))
assert.ok(stage.includes('eyeShapeLocked: eyesLocked'),
  'the real stage must transfer authored eye ownership to the final Cubism layer')
assert.ok(stage.includes('voiceLevel: pcmAvailable ? mouth : null'))
assert.ok(stage.includes('if (playing && !newAudioFragment) {\n      activatePendingSpeechMoment()'),
  'only duplicate exact audio callbacks may skip a fresh mouth onset')
assert.ok(stage.includes('if (!preciseSpeechOwnership) playing = !!next.speaking'),
  'coarse speaking status must not overwrite an exact versioned TTS lease')
assert.ok(stage.includes('mouthExpressionGate.finished()'))
assert.ok(stage.includes('mouthExpressionGate.interrupted()'))
assert.ok(stage.includes('setBodyState'))
assert.ok(stage.includes('physical: physicalState'))
assert.ok(stage.includes('listening, thinking, speaking: playing, expressionActive:'),
  'Android listening and real pending-model phase must reach the final actuator')
assert.match(fullscreenVideo, /thinking = state\.connected && state\.thinking/,
  'the real call session, not a guessed voice level, owns thinking state')
assert.match(androidStage, /LaunchedEffect\(stage, speaking, listening, thinking, mouthStep, hasPcmSamples\)/)
assert.match(androidStage, /\.put\("thinking", thinking\)/)
assert.ok(stage.includes('thinking = !!next.thinking'))
assert.ok(stage.includes("setParameter(core, 'ParamTongueOut', 0)"))
assert.ok(stage.includes('const audibleMouth = playing && (!pcmAvailable || mouth > .055)'))
assert.ok(stage.includes('1600 / Math.max(1, root.clientWidth, root.clientHeight)'))
console.log('Cubism, audio-expression gating, AIRI layers and transport checks passed')

const modelBounds = { x: 0, y: 0, width: 200, height: 1000 }
const zone = (u, v, hits = []) => resolveTouchRegion({
  x: 200 * u, y: 1000 * v, bounds: modelBounds,
  hits, authoredAreas: hits.length ? 2 : 0,
})
assert.equal(zone(.5, .02), 'hair')
assert.equal(zone(.18, .055, ['Head']), 'beast-ear-left')
assert.equal(zone(.82, .055, ['Head']), 'beast-ear-right')
assert.equal(zone(.34, .13, ['Head']), 'eye-left')
assert.equal(zone(.5, .20, ['Head']), 'nose')
assert.equal(zone(.5, .25, ['Head']), 'mouth')
assert.equal(zone(.18, .20, ['Head']), 'ear-left')
assert.equal(zone(.5, .365, ['Body']), 'throat')
assert.equal(zone(.5, .42, ['Body']), 'collarbone')
assert.equal(zone(.5, .55, ['Body']), 'chest')
assert.equal(zone(.13, .52, ['Arm']), 'arm-left')
assert.equal(zone(.87, .52, ['Arm']), 'arm-right')
assert.equal(zone(.13, .84, ['Hand']), 'hand-left')
assert.equal(resolveTouchRegion({
  x: 100, y: 120, hits: [], authoredAreas: 2, bounds: modelBounds,
}), null, 'a missed authored hit must never become a phantom body touch')
assert.equal(zone(.01, .6), null, 'background should not trigger touch')
const caress = touchReflexFor('cheek-left', 'stroke-right', 2)
assert.ok(caress.yaw < -8)
assert.ok(caress.face.eyeOpen < 1)
assert.ok(touchReflexFor('chest', 'tap').body.pitch < -1, 'a chest tap is a subtle local body response, not a whole-body lunge')
assert.notDeepEqual(touchReflexFor('hair', 'hold', 0), touchReflexFor('hair', 'hold', 1))
assert.equal(touchExpressionFor('hair', ['angry', 'smile', 'tongue']), 'smile')
