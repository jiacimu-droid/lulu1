// Render-only adapter. Character cognition, call control and audio remain in Android.
import * as PIXI from 'pixi.js'
// Pixi v6.5.x does NOT auto-install the CSP adapter via a bare import.
// Its source exports install({ ShaderSystem }), which patches the exact Pixi core
// class used by our renderer. Do this before the first PIXI.Application.
import { install as installCspShaderAdapter } from '@pixi/unsafe-eval'
import { Live2DModel, config } from 'pixi-live2d-display/cubism4'
import { CubismParameterAccess } from './cubism-parameters.mjs'
import { FACE_BINDINGS, BODY_BINDINGS, createVisualLayerRuntime } from './visual-layers.mjs'
import { createPerformanceRuntime } from './speech-performance.mjs'
import { createSpeechExpressionGate, isMouthExclusiveExpression, isEyeExclusiveExpression } from './speech-expression-gate.mjs'
import { resolveTouchRegion, sanitizeTouchCalibration, calibratedRegion, TOUCH_REGIONS, touchZoneArea } from './touch-hit.mjs'
import { touchReflexFor, touchExpressionFor, touchCompanionOverlayFor } from './touch-reflex.mjs'
import { createTouchResponseRuntime, isImpact } from './touch-response.mjs'
import { createTouchExpressionLayer } from './touch-expression-layer.mjs'

installCspShaderAdapter({ ShaderSystem: PIXI.ShaderSystem })
window.PIXI = PIXI
// Pixi v6 is bundled by the existing APK workflow; the official Cubism Core remains user-supplied.
Live2DModel.registerTicker(PIXI.Ticker)
if (config) {
  config.sound = false // Do not mix motion sounds into the existing phone's TTS.
  config.preserveExpressionOnMotion = true // A chosen face and motion are one combined performance.
}
const root = document.getElementById('stage')
const notice = document.getElementById('notice')
console.info('__LULU_LIVE2D__' + JSON.stringify({
  type: 'loading', detail: 'Pixi 6 CSP ShaderSystem adapter 已显式安装'
}))
let app
let model
let nativeViewport = { width: 0, height: 0 }
let inflightModelUrl = null
let loadSequence = 0
let previewObserved = false
// A diagnostic-only compositor workaround. A decoded WebGL frame can remain
// invisible in Android WebView even when readPixels / drawImage both succeed.
let mirrorImage = null
let mirrorTimer = null
let mirrorErrorSent = false
let nativeCaptureCanvas = null
let nativeCaptureContext = null
let nativeCaptureErrorReported = false
// Optional AndroidX WebMessage ArrayBuffer streaming bridge. JPEG bytes move
// without evaluateJavascript polling, JSON escaping or Base64 inflation.
let nativeStreamEnabled = false
let nativeStreamBusy = false
let nativeStreamTimer = null
let nativeStreamLastFrameAt = 0
let nativeStreamMaxEdge = 1024
let nativeStreamWarned = false
// Avoid JPEG-encoding duplicate frames when the WebView's Pixi ticker runs
// slower than the Android bridge. Capture only after a NEW rendered frame.
let nativeStreamLastTickerTime = -1
let nativeStreamAckMeanMs = 45
// Bounded, renderer-only timings. No user audio, images, model contents or
// permanent telemetry. GPU FPS below means Pixi render cadence, NOT display FPS.
const framePerf = { startedAt: 0, renders: 0, captures: 0,
  renderCostMs: 0, copyCostMs: 0, encodeCostMs: 0, ackCostMs: 0 }
function recordRenderTick(costMs, now) {
  if (!framePerf.startedAt) framePerf.startedAt = now
  framePerf.renders++
  framePerf.renderCostMs += costMs
  const span = now - framePerf.startedAt
  if (span < 4000) return
  if (model && previewObserved && !paused) {
    const mean = (value, count) => count ? Math.round(value / count) : 0
    const rendererFps = (framePerf.renders * 1000 / Math.max(1, span)).toFixed(1)
    notify('performance', 'WebGL ' + rendererFps + ' FPS · 绘制 ' +
      mean(framePerf.renderCostMs, framePerf.renders) + 'ms · 拷贝 ' +
      mean(framePerf.copyCostMs, framePerf.captures) + 'ms · JPEG ' +
      mean(framePerf.encodeCostMs, framePerf.captures) + 'ms · 桥接 ' +
      mean(framePerf.ackCostMs, framePerf.captures) + 'ms')
  }
  framePerf.startedAt = now
  framePerf.renders = 0
  framePerf.captures = 0
  framePerf.renderCostMs = framePerf.copyCostMs =
    framePerf.encodeCostMs = framePerf.ackCostMs = 0
}
// Full body for diagnostics; video calls may request a closer waist-up view.
let framingMode = window.__luluFramingMode === 'portrait' ? 'portrait' : 'full'
let gpuLost = false
let gpuMaxTexture = 0
let textureDetails = ''
let playing = false
let mouth = 0
let pcmAvailable = false
let listening = false
let paused = false
// Simulated body rhythm is separate from emotional expression choreography.
let physicalState = { energy: 78, sleepPressure: 22 }
function setBodyState(next) {
  if (!next) return
  const energy = Number(next.energy)
  const sleep = Number(next.sleepPressure)
  if (!Number.isFinite(energy) || !Number.isFinite(sleep)) return
  physicalState = {
    energy: Math.min(100, Math.max(0, energy)),
    sleepPressure: Math.min(100, Math.max(0, sleep)),
  }
}
let seconds = 0
let headRequest = null
let headRequestEndAt = 0
let touchReflex = null
let touchReflexEndAt = 0
let tactileContact = null
const touchResponse = createTouchResponseRuntime()
let touchSequence = 0
let touchCalibration = Object.create(null)
let calibrationVisible = false
let calibrationSelection = 'cheek-left'
let calibrationGuide = null
// Visual regions belong to the currently imported model, not to the character.
function drawCalibrationGuide() {
  if (!app || !model) return
  if (!calibrationGuide) {
    calibrationGuide = new PIXI.Graphics()
    app.stage.addChild(calibrationGuide)
  }
  calibrationGuide.clear()
  if (!calibrationVisible) return
  const b = model.getBounds()
  for (const [index, zone] of TOUCH_REGIONS.entries()) {
    const saved = touchCalibration[zone]
    const area = saved || touchZoneArea(zone, b)
    if (!area) continue
    const { u, v } = area
    const rx = area.rx * b.width
    const ry = area.ry * b.height
    const selected = calibrationSelection === zone
    calibrationGuide.lineStyle(selected ? 1.5 : 1,
      selected ? 0xFFE4EF : saved ? 0xC5F7FF : 0xC0D0DC,
      selected ? .95 : saved ? .6 : .3)
    calibrationGuide.beginFill(selected ? 0xFF86BA :
      saved ? 0x70D3F1 : 0xA9BCCA, selected ? .06 : 0)
    calibrationGuide.drawEllipse(b.x + u * b.width, b.y + v * b.height, rx, ry)
    calibrationGuide.endFill()
  }
}
function setCalibrationMode(enabled) {
  calibrationVisible = !!enabled
  drawCalibrationGuide()
}
function setCalibrationSelection(zone) {
  if (!TOUCH_REGIONS.includes(zone)) return
  calibrationSelection = zone
  drawCalibrationGuide()
}
function setTouchCalibration(value) {
  touchCalibration = sanitizeTouchCalibration(value)
  drawCalibrationGuide()
}
function calibrateTouch(nx, ny, zone, radius = null) {
  if (!model || !TOUCH_REGIONS.includes(zone) || !Number.isFinite(nx) ||
      !Number.isFinite(ny) || (radius != null && !Number.isFinite(radius))) return null
  const b = model.getBounds()
  const u = (nx * root.clientWidth - b.x) / b.width
  const v = (ny * root.clientHeight - b.y) / b.height
  if (![u, v].every(Number.isFinite) || u < 0 || u > 1 || v < 0 || v > 1)
    return null
  const area = touchZoneArea(zone, b, touchCalibration[zone], radius)
  touchCalibration = sanitizeTouchCalibration({
    ...touchCalibration, [zone]: { ...area, u, v },
  })
  drawCalibrationGuide()
  return { zone, calibration: touchCalibration }
}
function resizeCalibrationZone(zone, radius) {
  if (!model || !Number.isFinite(radius)) return null
  const area = touchZoneArea(zone, model.getBounds(), touchCalibration[zone], radius)
  if (!area) return null
  touchCalibration = sanitizeTouchCalibration({ ...touchCalibration, [zone]: area })
  drawCalibrationGuide()
  return { zone, calibration: touchCalibration }
}
function clearTouchCalibration() {
  touchCalibration = Object.create(null)
  drawCalibrationGuide()
}

let touchExpressionNames = []
let touchExpressionMetadata = Object.create(null)
function clearTouchCompanion() {
  tactileExpressionLayer.clear(model?.internalModel?.coreModel)
  activeTouchExpression = null
}
let activeTouchExpression = null
let activeTouchExpressionUntil = 0
let pendingExpressionName = null
let pendingSpeechParts = null
let activeExpressionName = null
let activeExpressionStartedAt = 0
let activeExpressionIsPreview = false
let lastSpeechSoundAt = 0
let bodyRequest = null
let bodyRequestEndAt = 0
let faceRequest = null
let faceRequestEndAt = 0
let faceRequestStartedAt = 0
let authoredMotion = null
let motionRevision = 0
let proceduralBlinkEnabled = false
let blinkClock = 4.4
let blinkProgress = -1
let smoothedMouthOpen = 0
let mouthReleaseUntil = 0
let lastRealMouthSignalAt = 0
let lastMouthSignal = 0
let speakingBeganAt = 0
// Audio amplitude is unavailable for some built-in/streaming TTS providers.
// During actual playback only, use a local syllabic fallback so the mouth
// never remains glued shut. Real PCM amplitude always takes precedence.
let fallbackSpeechSeed = 0
// Only semantic parameters confirmed in the uploaded wolf model's CDI3 and
// VTube settings. For OTHER models the runtime Cubism parameter probe must
// independently confirm every individual ID before applying any value.
const cubismParameters = new CubismParameterAccess()
const hasParameter = (core, id) => cubismParameters.has(core, id)
const getParameter = (core, id) => cubismParameters.read(core, id)
const setParameter = (core, id, value) => cubismParameters.write(core, id, value)
const tactileExpressionLayer = createTouchExpressionLayer({
  has: hasParameter, read: getParameter, write: setParameter,
})

const visualLayers = createVisualLayerRuntime({
  has: hasParameter, read: getParameter, write: setParameter,
})
const performanceRuntime = createPerformanceRuntime()
const mouthExpressionGate = createSpeechExpressionGate()
let mouthExclusiveExpressions = new Set()
let eyeLockedExpressions = new Set()

function status(message, phase = message ? 'loading' : 'ready') {
  notice.textContent = message || ''
  // In the full-screen call, Android already owns the loading/error UI.
  // Do not render the internal WebView debug notice over the user's portrait.
  // The standalone diagnostic stage still receives the full visual notice.
  notice.style.display = message && !window.__luluNativeStreamEnabled ? 'block' : 'none'
  console.info('__LULU_LIVE2D__' + JSON.stringify({
    type: phase, detail: String(message || '模型渲染完成').slice(0, 200)
  }))
}
// Android attaches and measures WebView asynchronously. The JavaScript stage
// can load while document.body still measures 0x0; keep Android's true viewport
// as an independent layout source rather than constructing a permanent 1x1 canvas.
function setViewport(widthPx, heightPx) {
  if (!Number.isFinite(widthPx) || !Number.isFinite(heightPx) ||
      widthPx < 20 || heightPx < 20) return
  nativeViewport = { width: widthPx, height: heightPx }
  const scale = Math.max(1, window.devicePixelRatio || 1)
  const widthCss = Math.max(20, Math.round(widthPx / scale))
  const heightCss = Math.max(20, Math.round(heightPx / scale))
  // Only force CSS dimensions when the actual WebView document has no viewport.
  if (root.clientWidth < 20) root.style.width = widthCss + 'px'
  if (root.clientHeight < 20) root.style.height = heightCss + 'px'
  if (app && root.clientWidth >= 20 && root.clientHeight >= 20) fit()
}
function validViewport() {
  return root.clientWidth >= 20 && root.clientHeight >= 20
}
async function awaitViewport(sequence) {
  if (window.__luluNativeViewport) {
    setViewport(window.__luluNativeViewport.width, window.__luluNativeViewport.height)
  }
  for (let attempt = 0; attempt < 100; attempt++) {
    if (sequence !== loadSequence) return false
    if (validViewport()) return true
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  throw new Error('手机视频页面未完成布局：DOM ' + root.clientWidth + '×' +
    root.clientHeight + '；Android ' + nativeViewport.width + '×' +
    nativeViewport.height + '。请切回语音再打开视频')
}
function fit() {
  if (!app) return
  const viewWidth = Math.max(1, root.clientWidth)
  const viewHeight = Math.max(1, root.clientHeight)
  // Resizing an unchanged WebGL drawing buffer each probe clears the buffer
  // and costs GPU memory; resize only when the Android WebView actually changes.
  if (app.renderer.screen.width !== viewWidth ||
      app.renderer.screen.height !== viewHeight) {
    app.renderer.resize(viewWidth, viewHeight)
  }
  if (!model) return
  const originalWidth = model.width / Math.max(Math.abs(model.scale.x), 1e-6)
  const originalHeight = model.height / Math.max(Math.abs(model.scale.y), 1e-6)
  if (!Number.isFinite(originalWidth) || !Number.isFinite(originalHeight) ||
    originalWidth <= 0 || originalHeight <= 0) {
    status('模型画布尺寸无效，无法居中绘制', 'error')
    return
  }
  // The *same model* can be framed as full body or waist-up. Never
  // preprocess textures or alter the original author-supplied assets.
  // In portrait view the top of the model remains in shot, while the lower
  // half is intentionally below the visible viewport.
  const isPortrait = framingMode === 'portrait'
  const heightTarget = viewHeight * (isPortrait ? 2.13 : 1.02)
  const widthTarget = viewWidth * (isPortrait ? 2.40 : .92)
  model.scale.set(Math.min(widthTarget / originalWidth, heightTarget / originalHeight))
  model.anchor.set(.5, .5)
  model.position.set(viewWidth / 2, viewHeight * (isPortrait ? 1.00 : .49))
  drawCalibrationGuide()
}
function setFramingMode(nextMode) {
  framingMode = nextMode === 'portrait' ? 'portrait' : 'full'
  if (app && model) {
    fit()
    app.render()
  }
}
function notify(type, detail) {
  console.info('__LULU_LIVE2D__' + JSON.stringify({
    type, detail: String(detail || '').slice(0, type === 'parameters' ? 850 : 200)
  }))
}
function describeTextureLimits(loaded) {
  const gl = app?.renderer?.gl
  if (!gl || gl.isContextLost()) throw new Error('WebGL 不可用或 GPU 上下文已丢失')
  gpuMaxTexture = gl.getParameter(gl.MAX_TEXTURE_SIZE)
  const textures = (loaded.textures || []).map(texture => {
    const base = texture?.baseTexture || texture
    return [base?.realWidth || base?.width || 0, base?.realHeight || base?.height || 0]
  })
  textureDetails = textures.map(size => size.join('×')).join('、')
  if (textures.some(([width,height]) => width > gpuMaxTexture || height > gpuMaxTexture)) {
    throw new Error('模型贴图超过手机 WebGL 最大支持尺寸 ' + gpuMaxTexture +
      '，贴图为 ' + textureDetails + '；需要缩小贴图后才能渲染')
  }
  notify('diagnostic', 'WebGL 纹理上限 ' + gpuMaxTexture + '；模型纹理 ' +
    (textureDetails || '尺寸未公开'))
}
/**
 * Never tell Compose that a model rendered just because the loader resolved.
 * In Android WebView a zero-sized canvas, dropped GPU context or invisible
 * Live2D drawable otherwise hides the usable static author preview as "ready".
 */
async function verifyVisibleFrame(loaded, sequence) {
  const gl = app?.renderer?.gl
  if (!gl) throw new Error('未能取得 WebGL 上下文')
  let priorError = ''
  for (let attempt = 0; attempt < 32; attempt++) {
    if (sequence !== loadSequence || loaded !== model) return
    await new Promise(resolve => setTimeout(resolve, 120))
    if (gl.isContextLost() || gpuLost) {
      throw new Error('手机 GPU 的 WebGL 上下文丢失，请降低贴图分辨率')
    }
    if (root.clientWidth < 20 || root.clientHeight < 20) {
      priorError = '视频画布尚未获得有效尺寸'
      continue
    }
    fit()
    // AIRI explicitly renders before capture and retains the drawing buffer.
    // Verify that a visible canvas contains CHARACTER COLORED PIXELS instead of
    // merely checking alpha, which can be 255 even on a black WebView surface.
    try {
      app.render()
      const bounds = app.view.getBoundingClientRect()
      if (!app.view.isConnected || bounds.width < 20 || bounds.height < 20) {
        priorError = 'Pixi 画布未显示到页面中'
        continue
      }
      const w = app.renderer.width
      const h = app.renderer.height
      const sw = Math.min(96, Math.floor(w / 2))
      const sh = Math.min(96, Math.floor(h / 2))
      if (sw < 2 || sh < 2) continue
      const pixels = new Uint8Array(sw * sh * 4)
      gl.readPixels(Math.floor((w-sw)/2), Math.floor((h-sh)/2), sw, sh,
        gl.RGBA, gl.UNSIGNED_BYTE, pixels)
      if (gl.getError() !== gl.NO_ERROR) {
        priorError = 'GPU 画面读取失败'
        continue
      }
      let visibleColoredPixels = 0
      for (let i = 0; i < pixels.length; i += 4) {
        const r = pixels[i]
        const g = pixels[i + 1]
        const b = pixels[i + 2]
        const alpha = pixels[i + 3]
        // A fully opaque black frame is NOT a valid model preview.
        if (alpha > 32 && (Math.max(r, g, b) > 45 ||
          Math.max(r, g, b) - Math.min(r, g, b) > 18)) {
          visibleColoredPixels++
        }
      }
      if (visibleColoredPixels >= 48) {
        // AIRI captures the actual canvas instead of trusting an arbitrary
        // WebGL framebuffer. Some GPU pipelines leave colored pixels in a
        // render target while the canvas presented to WebView stays black.
        const checkCanvas = document.createElement('canvas')
        checkCanvas.width = 64
        checkCanvas.height = 64
        const snapshot = checkCanvas.getContext('2d', { willReadFrequently: true })
        if (!snapshot) throw new Error('无法验证 Live2D Canvas 画面')
        snapshot.clearRect(0, 0, 64, 64)
        snapshot.drawImage(app.view, 0, 0, 64, 64)
        const presentedPixels = snapshot.getImageData(0, 0, 64, 64).data
        let presentedColored = 0
        for (let i = 0; i < presentedPixels.length; i += 4) {
          const r = presentedPixels[i]
          const g = presentedPixels[i + 1]
          const b = presentedPixels[i + 2]
          const alpha = presentedPixels[i + 3]
          if (alpha > 32 && (Math.max(r, g, b) > 45 ||
            Math.max(r, g, b) - Math.min(r, g, b) > 18)) {
            presentedColored++
          }
        }
        if (presentedColored >= 32) {
          previewObserved = true
          // This proves the canvas buffer, NOT Android's onscreen compositor.
          notify('diagnostic', 'Canvas 缓冲区有角色（彩色像素 ' +
            presentedColored + '）；画布 ' +
            Math.round(bounds.width) + '×' + Math.round(bounds.height))
          return
        }
        priorError = 'GPU 已处理模型，但屏幕 Canvas 仍是黑色；已保留静态预览'
      } else {
        priorError = 'Pixi 画布仍为黑色或透明（彩色像素 ' + visibleColoredPixels + ')'
      }
    } catch (error) {
      priorError = error?.message || String(error)
    }
  }
  throw new Error(priorError || '模型虽然载入，但未能产生可见的视频画面')
}
function applyPerformanceBeat(beat) {
  if (beat.expression) startSpeechExpression(beat.expression)
  if (beat.head) head(beat.head, true)
  if (beat.body) body(beat.body, true)
  if (beat.face) face(beat.face, true)
  // A short score controls its leases instead of leaving every partial pose
  // latched for the old 3-6.5 second default.
  if (Number.isFinite(beat.durationMs) && beat.durationMs > 0) {
    const until = Date.now() + Math.min(30000, beat.durationMs)
    if (beat.head) headRequestEndAt = until
    if (beat.body) bodyRequestEndAt = until
    if (beat.face) faceRequestEndAt = until
  }
}
function settleSpeechMoment() {
  const until = Date.now() + 900
  headRequestEndAt = Math.min(headRequestEndAt, until)
  bodyRequestEndAt = Math.min(bodyRequestEndAt, until)
  faceRequestEndAt = Math.min(faceRequestEndAt, until)
}
// Apply at the LAST mutable stage: after motion, expressions, focus, auto
// breathing, physics and pose, but before core.update computes the drawables.
// The former motionManager.update hook ran before breathing and physics.
function applyFinalParameters(core, deltaMs) {
  if (!core || paused) return
  seconds += Math.min(Math.max(deltaMs / 1000, 0), .05)
  if (headRequest && Date.now() > headRequestEndAt) headRequest = null
  const tactileNow = performance.now()
  tickTactileContact(tactileNow)
  const visibleTouchExpression = tactileExpressionLayer.update(core, tactileNow)
  if (activeTouchExpression && tactileNow > activeTouchExpressionUntil) activeTouchExpression = null
  if (bodyRequest && Date.now() > bodyRequestEndAt) bodyRequest = null
  if (faceRequest && Date.now() > faceRequestEndAt) faceRequest = null
  const timeNow = Date.now()
  // Score and physics are separate. A low rendering FPS cannot slow the
  // audio timeline; a failed TTS must never leave queued facial actions.
  performanceRuntime.tick(beat => {
    applyPerformanceBeat(beat)
  })
  // Mouth-occupying gestures are deferred to a CONFIRMED audio pause or
  // utterance completion, not blindly triggered at a model-chosen millisecond.
  const gate = mouthExpressionGate.tick()
  if (gate.reset && activeExpressionName &&
      mouthExclusiveExpressions.has(activeExpressionName)) resetSpeechExpression()
  if (gate.play) playVerifiedSpeechExpression(gate.play, true)
  // Expression cues are emotional beats, not permanent faces. Let gaps
  // between TTS fragments breathe, while bounding even long monologues.
  if (activeExpressionName &&
      ((!playing && !activeExpressionIsPreview && lastSpeechSoundAt && timeNow - lastSpeechSoundAt > 1050) ||
       timeNow - activeExpressionStartedAt > 6200)) {
    resetSpeechExpression()
  }
  if ((pendingExpressionName &&
       timeNow - pendingExpressionName.queuedAt > 20000) ||
      (pendingSpeechParts &&
       timeNow - pendingSpeechParts.queuedAt > 20000)) {
    pendingExpressionName = null
    pendingSpeechParts = null
  }
  // AIRI-style final-phase ownership: normal motion/physics already ran.
  // Hold steady eye contact, spring deliberate poses, release expressions,
  // and preserve subtle dedicated breathing; passive blink and speech mouth
  // remain subsequent independent layers.
  // An authored star/heart/closed-eye exp3 owns its eye shape. Keep local
  // tactile neck/cheek pose and smile, but don't close over the special eyes.
  const touchEyesLocked = eyeLockedExpressions.has(visibleTouchExpression)
  const eyesLocked = eyeLockedExpressions.has(activeExpressionName) || touchEyesLocked
  const tactilePose = touchReflex && (eyeLockedExpressions.has(activeExpressionName) || touchEyesLocked)
    ? { ...touchReflex, face: {
        ...touchReflex.face, eyeOpen: undefined, eyeSmile: undefined, squint: undefined,
        eyeLeftOpen: undefined, eyeRightOpen: undefined,
      } }
    : touchReflex
  const visibleFace = eyesLocked && faceRequest ? { ...faceRequest,
    eyeOpen: undefined, eyeSmile: undefined, squint: undefined,
    eyeLeftOpen: undefined, eyeRightOpen: undefined,
  } : faceRequest
  const manager = model?.internalModel?.motionManager
  const motionActive = !!authoredMotion?.started && authoredMotion.model === model &&
    manager?.isFinished?.() === false
  if (authoredMotion?.started && !motionActive) authoredMotion = null
  visualLayers.update(core, {
    deltaMs, head: headRequest, body: bodyRequest, face: visibleFace,
    authoredMotion: motionActive,
    touchReflex: tactilePose, physical: physicalState,
    speaking: playing, expressionActive: !!activeExpressionName || !!visibleTouchExpression,
    voiceLevel: pcmAvailable ? mouth : null, seconds,
  })
  // A natural reflex, not an invented emotional intention: this particular
  // model has an empty EyeBlink group but exposes both eyelid parameters.
  // Avoid overwriting eye opening outside the brief blink interval.
  const eyePoseBusy = eyeLockedExpressions.has(activeExpressionName) || touchEyesLocked ||
    (faceRequest?.eyeOpen != null && faceRequest.eyeOpen < .25) ||
    (touchReflex?.face?.eyeOpen != null && touchReflex.face.eyeOpen < .7) ||
    touchReflex?.face?.eyeLeftOpen != null || touchReflex?.face?.eyeRightOpen != null
  if (eyePoseBusy && blinkProgress >= 0) {
    blinkProgress = -1
    blinkClock = 2.5
  }
  if (proceduralBlinkEnabled && !eyePoseBusy &&
      hasParameter(core, 'ParamEyeLOpen') &&
      hasParameter(core, 'ParamEyeROpen')) {
    const dt = Math.min(.06, Math.max(0, deltaMs / 1000))
    if (blinkProgress < 0) {
      blinkClock -= dt
      if (blinkClock <= 0) blinkProgress = 0
    } else {
      blinkProgress += dt / .22
      const closing = Math.sin(Math.min(1, blinkProgress) * Math.PI)
      for (const id of ['ParamEyeLOpen', 'ParamEyeROpen']) {
        const current = getParameter(core, id) ?? 1
        setParameter(core, id, current * (1 - closing * .95))
      }
      if (blinkProgress >= 1) {
        blinkProgress = -1
        // Slightly slower, more frequent natural blinks with sleep pressure.
        // Do not replace actual model-authored eye motions or expressions.
        blinkClock = (3.8 + Math.random() * 2.8) *
          (1.05 - physicalState.sleepPressure / 100 * .25)
      }
    }
  }
  // Cubism's expression fade can take several hundred milliseconds to
  // reset. Audio owns this *physical* tongue channel at the final drawing
  // stage, so a previously playing tongue will never remain visible across
  // the first syllable of the next utterance.
  const audibleMouth = playing && (!pcmAvailable || mouth > .055)
  if (audibleMouth && hasParameter(core, 'ParamTongueOut')) {
    setParameter(core, 'ParamTongueOut', 0)
  }
  if (hasParameter(core, 'ParamMouthOpenY')) {
    // AIRI uses a speech-release tail rather than snapping the jaw shut on
    // the very first silent frame. Don't permanently own the mouth during
    // idle, either: the model's own expression may need this parameter.
    if (playing && !mouthExpressionGate.state().active) {
      const realSignal = Math.min(1, Math.max(0, mouth))
      // The PCM callback is not provided by every platform TTS engine; for
      // those we approximate light syllables, only while speech is audible.
      const duration = Math.max(0, timeNow - speakingBeganAt)
      const syllables = .5 + .5 * Math.sin(duration * .029 + fallbackSpeechSeed) *
        Math.sin(duration * .011 + .4)
      const fallback = .24 + .43 * Math.abs(syllables)
      // A real zero-amplitude PCM sample must remain a genuine silent pause.
      const target = pcmAvailable ? realSignal : fallback
      const alpha = Math.min(1, deltaMs * (target > smoothedMouthOpen ? .045 : .031))
      smoothedMouthOpen += (target - smoothedMouthOpen) * alpha
      mouthReleaseUntil = timeNow + 470
      setParameter(core, 'ParamMouthOpenY', smoothedMouthOpen)
    } else if (mouthExpressionGate.state().active) {
      // Tongue/covered-mouth gestures cannot coexist with talking mouth shape.
      smoothedMouthOpen = 0
      mouthReleaseUntil = 0
      setParameter(core, 'ParamMouthOpenY', 0)
    } else if (timeNow < mouthReleaseUntil || smoothedMouthOpen > .012) {
      smoothedMouthOpen += (0 - smoothedMouthOpen) * Math.min(1, deltaMs * .019)
      if (smoothedMouthOpen < .012) smoothedMouthOpen = 0
      setParameter(core, 'ParamMouthOpenY', smoothedMouthOpen)
    }
  }
}
function installFinalParameterControl(loadedModel) {
  const internal = loadedModel.internalModel
  if (!internal || typeof internal.on !== 'function' || !internal.coreModel) {
    status('模型未提供 Cubism 每帧更新事件', 'error')
    return false
  }
  let previousTime = 0
  // pixi-live2d-display Cubism4InternalModel.update emits this after
  // the model's auto-breath and physics and immediately before model.update.
  internal.on('beforeModelUpdate', () => {
    const now = performance.now()
    const deltaMs = previousTime ? Math.min(Math.max(now - previousTime, 0), 60) : 16
    previousTime = now
    applyFinalParameters(internal.coreModel, deltaMs)
  })
  return true
}

function ensureApp() {
  if (app) return
  app = new PIXI.Application({
    width: Math.max(1, root.clientWidth),
    height: Math.max(1, root.clientHeight),
    // Android WebView can fail to composite a transparent WebGL surface onto
    // an opaque WebView. Render a truly opaque drawing buffer instead.
    backgroundColor: 0x151719,
    backgroundAlpha: 1,
    // AIRI keeps the drawing buffer across WebView compositor frames.
    // Without this, readback can pass before Android composites a cleared buffer.
    preserveDrawingBuffer: true,
    antialias: true,
    // A 2x WebGL drawing buffer produces millions of pixels per frame,
    // then each is GPU-readback and JPEG-compressed for Compose. The native
    // mirror itself is the final output, so render at a bounded density.
    // On Android this trades imperceptible offscreen supersampling for
    // substantially less GPU readback work and steadier frame pacing.
    // The old 1.4x GPU buffer capped real output at 552x1200.
    // Upscaling JPEG cannot restore that lost facial detail.
    // Most phones transport only ~1320px along the longer frame edge;
    // rendering a 2.35x source only to downsample it before JPEG wastes GPU
    // fill and readback bandwidth. Keep a ~1600px source pixel budget,
    // retaining some sharpness headroom without extra per-frame allocations.
    resolution: Math.min(2.2, window.devicePixelRatio || 1,
      Math.max(1, 1600 / Math.max(1, root.clientWidth, root.clientHeight))),
    autoDensity: true,
  })
  root.insertBefore(app.view, notice)
  app.ticker.remove(app.render, app)
  // AIRI's Canvas.vue wraps ticker renders so animation exceptions cannot
  // silently terminate rendering while Android still shows a "ready" status.
  app.ticker.add(() => {
    const started = performance.now()
    try {
      app.render()
      recordRenderTick(performance.now() - started, performance.now())
    } catch (error) {
      app.ticker.stop()
      status('Live2D 动画渲染中断：' + (error?.message || String(error)), 'error')
    }
  })
  // Allow smoother Cubism motion on capable Android GPUs. The native frame
  // bridge remains backpressured by its real decode ACK; do not capture
  // duplicate frames or tie pose timing to JPEG delivery.
  app.ticker.maxFPS = 36
  window.addEventListener('resize', fit)
  new ResizeObserver(() => fit()).observe(root)
  app.view.addEventListener('webglcontextlost', event => {
    event.preventDefault()
    gpuLost = true
    status('WebGL/GPU 上下文丢失，已保留静态人物。可切换语音后重新打开视频', 'error')
  })
  app.view.addEventListener('webglcontextrestored', () => {
    gpuLost = false
    status('WebGL 已恢复，请重新载入动态模型', 'error')
  })
}
async function loadCore() {
  if (window.Live2DCubismCore) return
  await new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = '/private/core/live2dcubismcore.min.js'
    script.onload = () => window.Live2DCubismCore ? resolve() : reject(new Error('Cubism Core 无效'))
    script.onerror = () => reject(new Error('尚未导入官方 Cubism Core，请在视频通话中选择导入'))
    document.head.appendChild(script)
  })
}
async function load(modelURL) {
  if (!modelURL.startsWith('https://appassets.androidplatform.net/private/models/')) {
    status('Live2D 模型路径不合法')
    return
  }
  // Native onPageFinished and the async JS bootstrap may announce the same
  // model simultaneously. One loading attempt must not cancel the other.
  if (inflightModelUrl === modelURL) return
  inflightModelUrl = modelURL
  const sequence = ++loadSequence
  setPresentationMode(false)
  previewObserved = false
  notify('step', '① Cubism Core：检查引擎')
  status('正在加载 Live2D 模型…')
  try {
    await loadCore()
    if (sequence !== loadSequence) return
    notify('step', '① Cubism Core：已加载')
    status('等待手机视频画布完成布局…')
    if (!await awaitViewport(sequence)) return
    notify('step', '② 视频画布：DOM ' + root.clientWidth + '×' +
      root.clientHeight + ' / Android ' + nativeViewport.width + '×' +
      nativeViewport.height)
    ensureApp()
    const gl = app.renderer?.gl
    notify('step', '③ WebGL：' + (gl ? '可用' : '不可用') +
      '，最大纹理 ' + (gl?.getParameter(gl.MAX_TEXTURE_SIZE) ?? '未知'))
    gpuLost = false
    // Check native preprocessing errors BEFORE the Pixi model parser can turn
    // them into an unhelpful blank canvas or generic "invalid model" error.
    const settingsResponse = await fetch(modelURL, { cache: 'no-store' })
    if (!settingsResponse.ok) throw new Error('模型配置文件无法加载 (HTTP ' + settingsResponse.status + ')')
    const modelSettings = await settingsResponse.json()
    // Describe what a .exp3 actually changes; do not assume a Chinese or
    // English filename is enough to infer if it blocks mouth movement.
    mouthExclusiveExpressions = new Set()
    eyeLockedExpressions = new Set()
    touchExpressionNames = []
    touchExpressionMetadata = Object.create(null)
    clearTouchCompanion()
    touchResponse.reset()
    const references = modelSettings.FileReferences?.Expressions || []
    touchExpressionNames = references.map(entry => entry?.Name).filter(Boolean)
    await Promise.all(references.map(async entry => {
      if (!entry?.Name || !entry?.File) return
      try {
        const url = new URL(entry.File, modelURL)
        if (url.origin !== new URL(modelURL).origin) return
        const response = await fetch(url.href)
        if (!response.ok) return
        const metadata = await response.json()
        if (sequence === loadSequence) {
          touchExpressionMetadata[entry.Name] = metadata
          if (isMouthExclusiveExpression(metadata)) mouthExclusiveExpressions.add(entry.Name)
          if (isEyeExclusiveExpression(metadata, entry.Name)) eyeLockedExpressions.add(entry.Name)
        }
      } catch (_) { /* not every model ships valid optional exp3 files */ }
    }))
    // A malformed local metadata file should fail safely for the known
    // tongue expressions rather than showing a tongue during TTS.
    for (const entry of references) {
      if (/吐舌|tongue/i.test(entry?.Name || '')) mouthExclusiveExpressions.add(entry.Name)
      if (isEyeExclusiveExpression(null, entry?.Name)) eyeLockedExpressions.add(entry.Name)
    }
    const eyeBlinkGroup = (modelSettings.Groups || []).find(group =>
      group.Name === 'EyeBlink' && group.Target === 'Parameter')
    proceduralBlinkEnabled = !eyeBlinkGroup || !(eyeBlinkGroup.Ids || []).length
    blinkProgress = -1
    blinkClock = 3.4
    notify('step', '④ model3.json：HTTP ' + settingsResponse.status +
      '，纹理 ' + (modelSettings.FileReferences?.Textures?.length ?? '?') + ' 张')
    if (modelSettings.luluMobileTextureError) {
      throw new Error('手机贴图优化失败：' + modelSettings.luluMobileTextureError)
    }
    if (sequence !== loadSequence) return
    if (model) {
      clearSpeechMoment()
      pendingExpressionName = null
      pendingSpeechParts = null
      app.stage.removeChild(model)
      model.destroy({ texture: true, baseTexture: true })
      model = null
    }
    // A new moc3 has different parameter indices and visual state.
    // Neither a prior call's eye/pose interpolation nor its queued speech
    // score is allowed to bleed into the next imported model.
    visualLayers.reset()
    touchReflex = null
    tactileContact = null
    performanceRuntime.reset()
    mouthExpressionGate.reset()
    if (playing) performanceRuntime.startSpeech()
    headRequest = null
    bodyRequest = null
    faceRequest = null
    notify('step', '⑤ Moc3 / 纹理 / Cubism：开始解码模型')
    const loaded = await Live2DModel.from(modelURL, { autoInteract: false, autoUpdate: true })
    notify('step', '⑤ 模型解析：成功；宽高 ' + Math.round(loaded.width) +
      '×' + Math.round(loaded.height))
    if (sequence !== loadSequence) {
      loaded.destroy({ texture: true, baseTexture: true })
      return
    }
    model = loaded
    describeTextureLimits(model)
    if (!installFinalParameterControl(model)) throw new Error('缺少 Cubism 最终参数控制阶段')
    app.stage.addChild(model)
    if (calibrationGuide) app.stage.addChild(calibrationGuide)
    fit()
    notify('step', '⑥ Canvas：验证真实可见人物像素')
    status('模型已解码，正在验证实际绘制画面…')
    await verifyVisibleFrame(model, sequence)
    if (sequence !== loadSequence) return
    status('', 'ready')
    if (window.__luluNativeStreamEnabled) setNativeStreaming(true)
    notify('step', '⑥ 仅通过 WebGL/Canvas 验证；Android 屏幕仍需独立确认')
    const coreModel = model.internalModel?.coreModel
    const candidates = [
      'ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
      ...Object.values(BODY_BINDINGS),
      ...Object.values(FACE_BINDINGS).flat(),
    ]
    const available = [...new Set(candidates)].filter(id =>
      hasParameter(coreModel, id))
    notify('parameters', available.join(','))
    notify('diagnostic', '真实 Moc3 口型索引=' + cubismParameters.index(coreModel, 'ParamMouthOpenY') +
      '；头部X索引=' + cubismParameters.index(coreModel, 'ParamAngleX') +
      '；已绑定最终绘制前参数控制')
    notify('diagnostic', '可执行视觉语义参数 ' + available.length +
      ' 项；自动眨眼=' + (proceduralBlinkEnabled ? '仅反射性眼睑参数' : '模型自带'))
  } catch (error) {
    if (sequence === loadSequence) {
      notify('step', '失败阶段：' + (error?.message || String(error)))
      status('无法呈现 Live2D 动态模型：' + (error?.message || String(error)), 'error')
    }
  } finally {
    if (sequence === loadSequence) inflightModelUrl = null
  }
}
// Moment state is driven by the Android phone player's actual audible
// events, not merely an LLM response or a pending TTS network request.
function activatePendingSpeechMoment() {
  if (!pendingExpressionName && !pendingSpeechParts) return
  const selected = pendingExpressionName?.name
  const parts = pendingSpeechParts
  pendingExpressionName = null
  pendingSpeechParts = null
  if (parts?.replace) clearSpeechMoment()
  if (parts?.motion) motion(parts.motion.group, parts.motion.index, true)
  if (selected) startSpeechExpression(selected)
  if (parts?.head) head(parts.head)
  if (parts?.body) body(parts.body)
  if (parts?.face) face(parts.face)
}
function onPlaybackEvent(phase) {
  if (phase === 'playing') {
    // PCM/status and utterance events must not restart one speech performance.
    if (playing) {
      activatePendingSpeechMoment()
      return
    }
    playing = true
    const gate = mouthExpressionGate.started()
    if (gate.reset && activeExpressionName) resetSpeechExpression()
    performanceRuntime.startSpeech()
    lastSpeechSoundAt = Date.now()
    speakingBeganAt = Date.now()
    fallbackSpeechSeed = Math.random() * 4
    activatePendingSpeechMoment()
    return
  }
  if (phase !== 'finished' && phase !== 'interrupted') return
  playing = false
  performanceRuntime.stopSpeech()
  const gate = phase === 'finished'
    ? mouthExpressionGate.finished() : mouthExpressionGate.interrupted()
  if (gate.reset && activeExpressionName) resetSpeechExpression()
  if (gate.play) playVerifiedSpeechExpression(gate.play, true)
  mouth = 0
  pcmAvailable = false
  lastRealMouthSignalAt = 0
  pendingExpressionName = null
  pendingSpeechParts = null
  if (phase === 'interrupted') clearSpeechMoment()
  else {
    // A brief utterance still gets a visible settling tail. The normal spring
    // and facial release then return to the imported pose without snapping.
    settleSpeechMoment()
  }
  // A short reply must still show its facial expression for a moment after
  // the audio ends; otherwise a one-second star-eye cue is barely visible.
  // A genuine interruption, on the other hand, clears it immediately.
  if (phase === 'interrupted') resetSpeechExpression()
  else lastSpeechSoundAt = Date.now()
  // Keep the mouth's short release tail on normal completion. Interruptions
  // should cancel immediately rather than make the role keep talking.
  if (phase === 'interrupted') {
    smoothedMouthOpen = 0
    mouthReleaseUntil = 0
  }
}
function update(next) {
  const wasPlaying = playing
  playing = !!next.speaking
  listening = !!next.listening
  pcmAvailable = !!next.hasPcmSamples
  const incomingMouth = Number(next.mouth)
  mouth = Number.isFinite(incomingMouth)
    ? Math.max(0, Math.min(1, incomingMouth)) : 0
  if (mouth > .015) {
    lastRealMouthSignalAt = Date.now()
    lastMouthSignal = mouth
  }
  if (playing && !wasPlaying) {
    const gate = mouthExpressionGate.started()
    if (gate.reset && activeExpressionName) resetSpeechExpression()
    performanceRuntime.startSpeech()
    speakingBeganAt = Date.now()
    fallbackSpeechSeed = Math.random() * 4
  }
  if (!playing) {
    mouth = 0
    if (wasPlaying) {
      performanceRuntime.stopSpeech()
      settleSpeechMoment()
      const gate = mouthExpressionGate.finished()
      if (gate.play) playVerifiedSpeechExpression(gate.play, true)
    }
  } else {
    const gate = mouthExpressionGate.sample({
      audible: playing, pcm: pcmAvailable, level: mouth,
    })
    if (gate.reset && activeExpressionName) resetSpeechExpression()
  }
  if (playing) {
    lastSpeechSoundAt = Date.now()
    // The older call providers only expose speaking status. Real TTS timeline
    // events also call this, so both paths remain idempotent.
    activatePendingSpeechMoment()
  } else if (wasPlaying && activeExpressionName) {
    lastSpeechSoundAt = Date.now()
  }
}
// Debug option: mirror decoded WebGL frames through an ordinary HTML image.
// This deliberately uses a different Chromium compositing path. Kept behind
// an explicit diagnostic call to avoid JPEG encoding during normal phone calls.
function setPresentationMode(useMirror) {
  if (!useMirror) {
    if (mirrorTimer !== null) clearInterval(mirrorTimer)
    mirrorTimer = null
    mirrorImage?.remove()
    mirrorImage = null
    notify('diagnostic', '已关闭图片通道，恢复原生 WebGL 画面')
    return
  }
  if (!app || !model) {
    notify('error', '图片通道尚未获得已解码的 Live2D 画面')
    return
  }
  if (mirrorImage) return
  mirrorErrorSent = false
  mirrorImage = document.createElement('img')
  mirrorImage.id = 'lulu-live2d-frame-mirror'
  mirrorImage.alt = ''
  mirrorImage.setAttribute('aria-hidden', 'true')
  mirrorImage.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:fill;pointer-events:none;z-index:1'
  root.appendChild(mirrorImage)
  const refresh = () => {
    if (!app || !model || !mirrorImage || paused || document.hidden) return
    try {
      // Avoid fetching images or models again. This is a same-origin canvas snapshot.
      app.render()
      mirrorImage.src = app.view.toDataURL('image/jpeg', 0.76)
    } catch (error) {
      if (!mirrorErrorSent) {
        mirrorErrorSent = true
        notify('error', '图片通道无法截取画面：' + (error?.message || String(error)))
      }
    }
  }
  refresh()
  mirrorTimer = setInterval(refresh, 200)
  notify('diagnostic', '已启用普通图片通道（本机约 5 帧/秒，不调用 API）')
}
// Some HyperOS / Android WebView compositions show an entirely blank native
// WebView surface even when readPixels and an HTML <img> contain the model.
// A native Android Image overlay can display these frames without depending
// on the WebView compositing surface. Called only by the Android fallback
// at a throttled rate, never streamed to a server or exposed to the model.
function drawNativeCapture(maxEdge = 960) {
  if (!app || !model || gpuLost || paused || document.hidden ||
      !previewObserved) return null
  if (!nativeCaptureCanvas) {
    nativeCaptureCanvas = document.createElement('canvas')
    nativeCaptureContext = nativeCaptureCanvas.getContext('2d', { alpha: false })
  }
  if (!nativeCaptureContext) return null
  const w = app.view.width
  const h = app.view.height
  if (w < 32 || h < 32) return null
  const safeEdge = Math.max(400, Math.min(2200, Math.floor(Number(maxEdge) || 960)))
  const scale = Math.min(1, safeEdge / Math.max(w, h))
  const width = Math.max(32, Math.round(w * scale))
  const height = Math.max(32, Math.round(h * scale))
  if (nativeCaptureCanvas.width !== width || nativeCaptureCanvas.height !== height) {
    nativeCaptureCanvas.width = width
    nativeCaptureCanvas.height = height
  }
  // The Pixi ticker already maintains the freshest drawing buffer. The
  // capture only copies it; never invoke Cubism render a second time.
  nativeCaptureContext.fillStyle = '#151719'
  nativeCaptureContext.fillRect(0, 0, width, height)
  nativeCaptureContext.drawImage(app.view, 0, 0, width, height)
  return nativeCaptureCanvas
}
// Conservative compatibility route for older Android System WebViews.
function captureNativeFrame(maxEdge = 800) {
  try {
    const canvas = drawNativeCapture(maxEdge)
    return canvas ? canvas.toDataURL('image/jpeg', 0.86) : null
  } catch (error) {
    if (!nativeCaptureErrorReported) {
      nativeCaptureErrorReported = true
      notify('error', '兼容画面捕获失败：' + (error?.message || String(error)))
    }
    return null
  }
}
function scheduleNativeStreamFrame(delay = 0) {
  if (!nativeStreamEnabled || nativeStreamBusy) return
  if (nativeStreamTimer !== null) clearTimeout(nativeStreamTimer)
  nativeStreamTimer = setTimeout(sendNativeStreamFrame, delay)
}
function sendNativeStreamFrame() {
  nativeStreamTimer = null
  if (!nativeStreamEnabled || nativeStreamBusy) return
  const tickerTime = app?.ticker?.lastTime
  if (Number.isFinite(tickerTime) && tickerTime === nativeStreamLastTickerTime) {
    // The GPU hasn't finished another model frame. Don't repeatedly wake
    // JavaScript at 12 ms just to discover the same image.
    scheduleNativeStreamFrame(22)
    return
  }
  const bridge = window.LuluNativeFrames
  if (!bridge || typeof bridge.postMessage !== 'function') return
  let canvas
  const copyStartedAt = performance.now()
  try {
    canvas = drawNativeCapture(nativeStreamMaxEdge)
  } catch (error) {
    if (!nativeStreamWarned) {
      nativeStreamWarned = true
      notify('error', '二进制帧绘制失败：' + (error?.message || String(error)))
    }
  }
  if (!canvas) {
    scheduleNativeStreamFrame(250)
    return
  }
  framePerf.copyCostMs += performance.now() - copyStartedAt
  framePerf.captures++
  nativeStreamBusy = true
  if (Number.isFinite(tickerTime)) nativeStreamLastTickerTime = tickerTime
  nativeStreamLastFrameAt = performance.now()
  const encodingStartedAt = nativeStreamLastFrameAt
  try {
    // Asynchronous JPEG encoding avoids a blocking toDataURL on every frame.
    canvas.toBlob(async blob => {
      framePerf.encodeCostMs += performance.now() - encodingStartedAt
      if (!nativeStreamEnabled) { nativeStreamBusy = false; return }
      if (!blob) {
        nativeStreamBusy = false
        scheduleNativeStreamFrame(160)
        return
      }
      try {
        const bytes = await blob.arrayBuffer()
        if (nativeStreamEnabled) {
          // Android acknowledges AFTER decoding the frame. This backpressure
          // bounds allocations and prevents rendering work piling up.
          bridge.postMessage(bytes)
        } else {
          nativeStreamBusy = false
        }
      } catch (error) {
        nativeStreamBusy = false
        if (!nativeStreamWarned) {
          nativeStreamWarned = true
          notify('error', '二进制 JPEG 发送失败：' + (error?.message || String(error)))
        }
        scheduleNativeStreamFrame(250)
      }
    }, 'image/jpeg', nativeStreamAckMeanMs > 90 ? 0.86 : 0.89)
  } catch (error) {
    nativeStreamBusy = false
    scheduleNativeStreamFrame(300)
  }
}
function setNativeStreaming(enabled) {
  nativeStreamEnabled = !!enabled
  if (nativeStreamTimer !== null) clearTimeout(nativeStreamTimer)
  nativeStreamTimer = null
  // Reset in-flight state when the native layer switches back to the proven
  // Base64 compatibility path.
  nativeStreamBusy = false
  nativeStreamLastTickerTime = -1
  if (!nativeStreamEnabled) {
    // WebGL direct output avoids JPEG/IPC entirely: let the visual model
    // update at a real 60Hz target instead of inheriting JPEG's 30/36/45 cap.
    if (app?.ticker) app.ticker.maxFPS = 60
    return
  }
  const bridge = window.LuluNativeFrames
  if (!bridge || typeof bridge.postMessage !== 'function') {
    notify('diagnostic', 'Android System WebView 未提供二进制消息桥接，使用兼容画面路线')
    return
  }
  bridge.onmessage = event => {
    if (!nativeStreamEnabled) return
    const reply = String(event?.data || '')
    if (reply.startsWith('next:')) {
      const maxEdge = Number(reply.substring(5))
      const ackMs = performance.now() - nativeStreamLastFrameAt
      framePerf.ackCostMs += ackMs
      nativeStreamAckMeanMs = nativeStreamAckMeanMs * .8 + ackMs * .2
    // Avoid wasting render/GPU readback bandwidth when JPEG transport cannot
    // sustain high cadence. Raise the ticker only after stable fast ACKs.
    if (app?.ticker) app.ticker.maxFPS =
      nativeStreamAckMeanMs < 30 ? 45 :
      nativeStreamAckMeanMs < 45 ? 36 : 30
      if (Number.isFinite(maxEdge)) {
        // Android already has an adaptive quality controller based on actual
        // decoded FPS/p95. Previously multiplying its requested dimension by
        // 1.38 silently defeated that controller (1320px => 1822px), forcing
        // huge GPU readbacks and JPEG encoding that made the avatar stutter.
        // Honor the measured size directly. Sharpness is maintained with
        // reasonable JPEG quality instead of hidden supersampling.
        nativeStreamMaxEdge = Math.min(1850,
          Math.max(760, Math.round(maxEdge)))
      }
    }
    nativeStreamBusy = false
    // Decode ACK is our backpressure. Avoid overlapping encoding and keep
    // a regular ~30fps pacing when the pipeline is fast; when it is slow
    // resume immediately rather than introducing an extra fixed wait.
    scheduleNativeStreamFrame(Math.max(0, 34 - (performance.now() - nativeStreamLastFrameAt)))
  }
  notify('diagnostic', '已启用 Android WebMessage 二进制桥接（带解码回执）')
  scheduleNativeStreamFrame()
}

// Native mirrored video already maintains a recent CPU-side 2D frame.
// Sampling its 5x5 pixels avoids a new WebGL readPixels stall on each touch.
// A true background pixel must not become a virtual head just because it falls
// inside coarse model bounds. null means unknown: rely on authored HitAreas.
function actualAvatarPixel(nx, ny) {
  if (!nativeCaptureCanvas || !nativeCaptureContext || !nativeStreamEnabled ||
      performance.now() - nativeStreamLastFrameAt > 1200) return null
  const w = nativeCaptureCanvas.width
  const h = nativeCaptureCanvas.height
  if (w < 32 || h < 32) return null
  const x = Math.max(0, Math.min(w - 1, Math.round(nx * w)))
  const y = Math.max(0, Math.min(h - 1, Math.round(ny * h)))
  const left = Math.max(0, x - 2), top = Math.max(0, y - 2)
  const width = Math.min(5, w - left), height = Math.min(5, h - top)
  try {
    const rgb = nativeCaptureContext.getImageData(left, top, width, height).data
    let colored = 0
    for (let i = 0; i < rgb.length; i += 4) {
      // Opaque stage background is exactly #151719.
      const delta = Math.max(Math.abs(rgb[i] - 21),
        Math.abs(rgb[i + 1] - 23), Math.abs(rgb[i + 2] - 25))
      if (delta > 12) colored++
    }
    return colored >= 2
  } catch (_) {
    return null
  }
}
/**
 * Android's visible Compose image captures the finger and forwards the
 * normalized point to the actual Pixi model. Never pretend background is a hit.
 * The renderer handles only physical feedback, not AI decisions.
 */
function tickTactileContact(tactileNow) {
  if (tactileContact && (tactileContact.pressed || tactileNow <= touchReflexEndAt)) {
    // A poised palm is not a completed strike: don't spend the impact before
    // the user's finger releases and the actual touch gets its timestamp.
    if (tactileContact.pressed && isImpact(tactileContact.kind)) {
      touchReflex = null
      return
    }
    if (tactileContact.pressed) {
      tactileContact.state = touchResponse.snapshot()
      if (!isImpact(tactileContact.kind) && tactileContact.kind !== 'kiss')
        updateTactileExpression(tactileContact.region, tactileContact.kind,
          tactileContact.state, tactileNow)
    }
    const poseKind = tactileContact.pressed && tactileContact.kind === 'tap' &&
      tactileNow - tactileContact.start > 520 ? 'hold' : tactileContact.kind
    touchReflex = touchReflexFor(tactileContact.region, poseKind,
      tactileContact.ordinal, tactileContact.state, tactileNow - tactileContact.start)
  } else { touchReflex = null; tactileContact = null }
}
function updateTactileExpression(region, kind, state, now) {
  if (activeExpressionName) return
  const selected = touchExpressionFor(region, touchExpressionNames,
    touchSequence, kind, playing, state)
  if (selected === activeTouchExpression && selected && now < activeTouchExpressionUntil) {
    tactileExpressionLayer.retarget(state, now)
    activeTouchExpressionUntil = now + 2200
    return
  }
  clearTouchCompanion()
  if (!selected || (playing && mouthExclusiveExpressions.has(selected))) return
  const extra = touchCompanionOverlayFor(selected, touchExpressionNames,
    touchExpressionMetadata, kind, state)
  const delay = isImpact(kind) ? 180 : 0
  const duration = kind === 'kiss' ? 2800 : 2200
  if (tactileExpressionLayer.start(model.internalModel.coreModel, selected,
      touchExpressionMetadata, extra, state, now, delay, duration)) {
    activeTouchExpression = selected
    activeTouchExpressionUntil = now + delay + duration
  }
}
function touch(normalizedX, normalizedY, gesture = 'tap', options = {}) {
  if (gesture === 'cancel') {
    touchResponse.end()
    tactileContact = null
    touchReflex = null
    return { hit: false }
  }
  if (!model || !previewObserved || paused || calibrationVisible ||
      !Number.isFinite(normalizedX) || !Number.isFinite(normalizedY) ||
      normalizedX < 0 || normalizedX > 1 ||
      normalizedY < 0 || normalizedY > 1) {
    touchResponse.end()
    if (tactileContact) tactileContact.pressed = false
    return { hit: false }
  }
  if (!['down', 'hold', 'tap', 'stroke-left', 'stroke-right',
      'stroke-up', 'stroke-down', 'kiss', 'slap', 'slap-left', 'slap-right'].includes(gesture))
    return { hit: false }
  const x = normalizedX * root.clientWidth
  const y = normalizedY * root.clientHeight
  const bounds = model.getBounds()
  const manualZone = calibratedRegion(
    (x - bounds.x) / bounds.width, (y - bounds.y) / bounds.height, touchCalibration)
  if (!manualZone && actualAvatarPixel(normalizedX, normalizedY) === false) {
    touchResponse.end()
    if (tactileContact) tactileContact.pressed = false
    return { hit: false }
  }
  let hits = []
  try { hits = model.hitTest(x, y) || [] } catch (_) { /* optional HitAreas */ }
  const authoredAreas = Object.keys(model.internalModel?.hitAreas || {}).length
  const region = resolveTouchRegion({ hits, authoredAreas, bounds, x, y,
    calibration: touchCalibration })
  if (!region) {
    touchResponse.end()
    if (tactileContact) tactileContact.pressed = false
    return { hit: false }
  }
  let kind = ['down', 'hold', 'tap', 'stroke-left', 'stroke-right',
    'stroke-up', 'stroke-down', 'kiss', 'slap', 'slap-left', 'slap-right'
  ].includes(gesture) ? gesture : 'tap'
  const completed = options.phase ? options.phase === 'complete' : kind !== 'down'
  const starting = kind === 'down'
  if (starting) {
    touchSequence++
    kind = options.mode === 'kiss' ? 'kiss' : options.mode === 'pat' ? 'slap' : 'tap'
  }
  const now = performance.now()
  const point = { x: x / bounds.width, y: y / bounds.width }
  const state = completed ? touchResponse.complete(region, kind, options.force)
    : starting ? touchResponse.begin(region, kind, options.force, point)
    : touchResponse.move(region, kind, options.force, point)
  // A palm impact belongs to the moment of release/contact. A long finger
  // hold in slap mode must not consume the visible recoil before the slap.
  const contactStart = isImpact(kind) && completed ? now
    : starting || !tactileContact ? now : tactileContact.start
  tactileContact = { region, kind, ordinal: touchSequence, state,
    start: contactStart, pressed: !completed }
  touchReflex = !completed && isImpact(kind) ? null :
    touchReflexFor(region, kind, touchSequence, state)
  touchReflexEndAt = now + (isImpact(kind) ? 1700 : kind === 'kiss' ? 1850
    : kind === 'hold' || kind.startsWith('stroke') ? 1000 : 550)
  // A new impact clears affectionate eyes. Petting grows with actual time
  // and path length while held, not with the number of MOVE callbacks.
  if (isImpact(kind)) clearTouchCompanion()
  if (completed) updateTactileExpression(region, kind, state, now)
  return { hit: true, region, gesture: kind, force: state.force }
}

function setPaused(value) {
  paused = !!value
  if (paused) {
    touchResponse.end()
    // Do not play a stale animation score in a burst when Android restores
    // the WebView after it was hidden while TTS continued in the background.
    performanceRuntime.stopSpeech()
    clearSpeechMoment()
    touchReflex = null
    activeTouchExpression = null
    tactileContact = null
    clearTouchCompanion()
  } else if (playing) {
    // Audio may have kept running while the renderer was hidden; resume
    // present-time mouth animation without resurrecting skipped gestures.
    performanceRuntime.startSpeech()
  }
  if (app) { if (paused) app.ticker.stop(); else app.ticker.start() }
}
function resetSpeechExpression() {
  if (model && activeExpressionName) {
    const manager = model.internalModel?.motionManager?.expressionManager
    try { manager?.resetExpression() } catch (error) {
      notify('diagnostic', '表情复位失败：' + (error?.message || String(error)))
    }
  }
  activeExpressionName = null
  activeExpressionStartedAt = 0
  activeExpressionIsPreview = false
}
function clearSpeechMoment() {
  resetSpeechExpression()
  mouthExpressionGate.reset()
  if (playing) mouthExpressionGate.started()
  headRequest = null
  bodyRequest = null
  faceRequest = null
  motionRevision++
  if (authoredMotion) {
    try { authoredMotion.model?.internalModel?.motionManager?.stopAllMotions?.() } catch (_) {}
    authoredMotion = null
  }
}
function startSpeechExpression(name, immediatePreview = false) {
  if (!model || !name) return
  clearTouchCompanion()
  if (mouthExclusiveExpressions.has(name)) {
    // Playing audio takes precedence over an animated tongue. Wait for a
    // PCM-confirmed pause or a real playback-finished event.
    if (!mouthExpressionGate.request(name, playing)) return
  }
  playVerifiedSpeechExpression(name, immediatePreview)
}
function playVerifiedSpeechExpression(name, immediatePreview = false) {
  if (!model || !name) return
  resetSpeechExpression()
  activeExpressionName = name
  activeExpressionStartedAt = Date.now()
  activeExpressionIsPreview = !!immediatePreview
  lastSpeechSoundAt = Date.now()
  const manager = model.internalModel?.motionManager?.expressionManager
  const knownIndex = manager?.getExpressionIndex?.(name) ?? -1
  if (knownIndex < 0) {
    notify('diagnostic', '无法找到表情：' + name + '；需与模型 exp3 文件名一致')
    if (activeExpressionName === name) resetSpeechExpression()
    return
  }
  Promise.resolve(model.expression(name)).then(ok => {
    if (ok === false) {
      notify('diagnostic', '表情未切换：' + name +
        '（资源未加载或当前已在使用）')
    } else {
      notify('diagnostic', '表情已提交：' + name)
    }
  }).catch(error => {
    notify('diagnostic', '表情播放异常：' + name + ' ' +
      (error?.message || String(error)))
    if (activeExpressionName === name) resetSpeechExpression()
  })
}
function expression(name, immediatePreview = false) {
  if (!model || !name || name.length > 128) return
  // Explicit manual/role-selected expressions take priority over the local
  // touch companion (but still respect the speech/mouth gate below).
  clearTouchCompanion()
  activeTouchExpression = null
  if (playing || immediatePreview) {
    pendingExpressionName = null
    startSpeechExpression(name, immediatePreview)
  } else {
    // The decision arrives before TTS plays; do not grimace throughout
    // network/voice generation latency.
    pendingExpressionName = { name, queuedAt: Date.now() }
  }
}
// AIRI-inspired staged expressivity: separate authored visual beats use the
// SAME phone turn and timeline, not an independent personality/animation LLM.
function choreography(beats, immediatePreview = false) {
  if (!model) return
  performanceRuntime.schedule(beats, !!immediatePreview)
}
// One atomic character decision, including ordinary chat performances. A new
// expression-only cue must also supersede an older, queued choreography.
function perform(cue, immediatePreview = false) {
  if (!model || !cue || typeof cue !== 'object') return false
  performanceRuntime.clearScore()
  pendingExpressionName = null
  pendingSpeechParts = null
  if (playing || immediatePreview) clearSpeechMoment()
  else pendingSpeechParts = { queuedAt: Date.now(), replace: true }
  if (cue.motion) motion(cue.motion.group, cue.motion.index, immediatePreview)
  if (cue.expression) expression(cue.expression, immediatePreview)
  if (cue.head) head(cue.head, immediatePreview)
  if (cue.body) body(cue.body, immediatePreview)
  if (cue.face) face(cue.face, immediatePreview)
  if (cue.beats) choreography(cue.beats, immediatePreview)
  return true
}
function motion(group, index, immediatePreview = false) {
  if (!model || !group || !Number.isInteger(index) || index < 0) return
  if (!playing && !immediatePreview) {
    pendingSpeechParts = pendingSpeechParts || { queuedAt: Date.now() }
    pendingSpeechParts.motion = { group, index }
    return
  }
  const target = model
  const revision = ++motionRevision
  const chosen = { model: target, revision, group, index, started: false }
  authoredMotion = chosen
  Promise.resolve(target.motion(group, index)).then(ok => {
    if (revision !== motionRevision || target !== model) {
      const manager = target.internalModel?.motionManager
      // A resource load may finish after cancellation. Stop only that stale
      // motion, never an already-started motion from a newer decision.
      if (!authoredMotion?.started && manager?.state?.currentGroup === group &&
          manager?.state?.currentIndex === index) manager.stopAllMotions?.()
      return
    }
    if (ok === false) authoredMotion = null
    else chosen.started = true
  }).catch(() => { if (authoredMotion === chosen) authoredMotion = null })
}
document.addEventListener('visibilitychange', () => setPaused(document.hidden))
function face(target, immediatePreview = false) {
  if (!model || !target || typeof target !== 'object') return
  if (!playing && !immediatePreview) {
    pendingSpeechParts = pendingSpeechParts || { queuedAt: Date.now() }
    pendingSpeechParts.face = target
    return
  }
  const next = Object.create(null)
  for (const [key, ids] of Object.entries(FACE_BINDINGS)) {
    if (ids.some(id => !hasParameter(model.internalModel?.coreModel, id))) continue
    const value = target[key]
    if (typeof value !== 'number' || !Number.isFinite(value)) continue
    const low = ['eyeOpen', 'eyeSmile', 'squint', 'cheekPuff', 'pout'].includes(key) ? 0 : -1
    next[key] = Math.max(low, Math.min(1, value))
  }
  if (!Object.keys(next).length) return
  faceRequest = next
  faceRequestStartedAt = Date.now()
  faceRequestEndAt = faceRequestStartedAt + (playing ? 6500 : 4300)
}
function body(target, immediatePreview = false) {
  if (!model || !target || typeof target !== 'object') return
  if (!playing && !immediatePreview) {
    pendingSpeechParts = pendingSpeechParts || { queuedAt: Date.now() }
    pendingSpeechParts.body = target
    return
  }
  const next = Object.create(null)
  for (const [axis, id] of Object.entries(BODY_BINDINGS)) {
    if (!hasParameter(model.internalModel?.coreModel, id)) continue
    const value = target[axis]
    if (typeof value === 'number' && Number.isFinite(value)) {
      next[axis] = Math.max(-10, Math.min(10, value))
    }
  }
  if (!Object.keys(next).length) return
  bodyRequest = next
  bodyRequestEndAt = Date.now() + 3000
}
function head(target, immediatePreview = false) {
  if (!model || !target) return
  if (!playing && !immediatePreview) {
    pendingSpeechParts = pendingSpeechParts || { queuedAt: Date.now() }
    pendingSpeechParts.head = target
    return
  }
  headRequest = {}
  for (const axis of ['yaw', 'pitch', 'roll']) {
    if (typeof target[axis] === 'number' && Number.isFinite(target[axis])) {
      headRequest[axis] = Math.max(-25, Math.min(25, target[axis]))
    }
  }
  // Hold a real selected action long enough to be visible even on the
  // native Compose compatibility bridge (~6-20fps), then yield to the
  // imported model's authored idle/motion instead of oscillating forever.
  headRequestEndAt = Date.now() + 2800
}
window.LuluLive2D = { load, update, onPlaybackEvent, setPaused, perform, expression, motion, head, face, body, choreography, touch, setViewport, setPresentationMode, captureNativeFrame, setFramingMode, setNativeStreaming, setBodyState, setTouchCalibration, setCalibrationMode, calibrateTouch, clearTouchCalibration, setCalibrationSelection, resizeCalibrationZone }
if (window.__luluNativeViewport) {
  setViewport(window.__luluNativeViewport.width, window.__luluNativeViewport.height)
}
if (window.__luluStageState) update(window.__luluStageState)
if (window.__luluStageModelUrl) load(window.__luluStageModelUrl)
else status('请选择 Live2D 模型')
