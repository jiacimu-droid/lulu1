// Render-only adapter. Character cognition, call control and audio remain in Android.
import * as PIXI from 'pixi.js'
// Pixi v6.5.x does NOT auto-install the CSP adapter via a bare import.
// Its source exports install({ ShaderSystem }), which patches the exact Pixi core
// class used by our renderer. Do this before the first PIXI.Application.
import { install as installCspShaderAdapter } from '@pixi/unsafe-eval'
import { Live2DModel, config } from 'pixi-live2d-display/cubism4'

installCspShaderAdapter({ ShaderSystem: PIXI.ShaderSystem })
window.PIXI = PIXI
// Pixi v6 is bundled by the existing APK workflow; the official Cubism Core remains user-supplied.
Live2DModel.registerTicker(PIXI.Ticker)
if (config) config.sound = false // Do not mix motion sound effects into the existing phone's TTS.
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
// Full body for diagnostics; video calls may request a closer waist-up view.
let framingMode = window.__luluFramingMode === 'portrait' ? 'portrait' : 'full'
let gpuLost = false
let gpuMaxTexture = 0
let textureDetails = ''
let playing = false
let mouth = 0
let listening = false
let paused = false
let seconds = 0
let headRequest = null
let headRequestEndAt = 0
let pendingExpressionName = null
let pendingSpeechParts = null
let activeExpressionName = null
let activeExpressionStartedAt = 0
let lastSpeechSoundAt = 0
let bodyRequest = null
let bodyRequestEndAt = 0
let faceRequest = null
let faceRequestEndAt = 0
let proceduralBlinkEnabled = false
let blinkClock = 4.4
let blinkProgress = -1
const smoothPose = { yaw: 0, pitch: 0, roll: 0 }
const smoothBody = { yaw: 0, pitch: 0, roll: 0 }
const smoothFace = Object.create(null)
// Local motor reflexes are intentionally tiny: they must never invent head
// nods, smiles or story decisions. A person may hold eye contact, then briefly
// look aside without any new paid character-model call.
const gazeReflex = { x: 0, y: 0, nextAt: 0 }
let smoothedMouthOpen = 0
let mouthReleaseUntil = 0
// Only semantic parameters confirmed in the uploaded wolf model's CDI3 and
// VTube settings. For OTHER models the runtime Cubism parameter probe must
// independently confirm every individual ID before applying any value.
const FACE_BINDINGS = {
  gazeX: ['ParamEyeBallX'], gazeY: ['ParamEyeBallY'],
  smile: ['ParamMouthForm'],
  eyeSmile: ['ParamEyeLSmile', 'ParamEyeRSmile'],
  browY: ['ParamBrowLY', 'ParamBrowRY'],
  squint: ['ParamEyeLSquint', 'ParamEyeRSquint'],
  cheekPuff: ['ParamCheekPuff'], pout: ['ParamMouthFunnel'],
}
const BODY_BINDINGS = { yaw: 'ParamBodyAngleX', pitch: 'ParamBodyAngleY', roll: 'ParamBodyAngleZ' }


function status(message, phase = message ? 'loading' : 'ready') {
  notice.textContent = message || ''
  notice.style.display = message ? 'block' : 'none'
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
  model.position.set(viewWidth / 2, viewHeight * (isPortrait ? 1.06 : .53))
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
// AIRI's motion manager applies user-controlled parameters after its native
// Cubism motion update. Do the same: a Pixi app.ticker callback can run BEFORE
// the Live2D model update and be overwritten by the next Cubism frame.
function applyAfterMotion(core, deltaMs) {
  if (!core || paused) return
  seconds += Math.min(Math.max(deltaMs / 1000, 0), .05)
  if (headRequest && Date.now() > headRequestEndAt) headRequest = null
  if (bodyRequest && Date.now() > bodyRequestEndAt) bodyRequest = null
  if (faceRequest && Date.now() > faceRequestEndAt) faceRequest = null
  const timeNow = Date.now()
  // Expression cues are emotional beats, not permanent faces. Let gaps
  // between TTS fragments breathe, while bounding even long monologues.
  if (activeExpressionName &&
      ((!playing && lastSpeechSoundAt && timeNow - lastSpeechSoundAt > 1050) ||
       timeNow - activeExpressionStartedAt > 6200)) {
    resetSpeechExpression()
  }
  if ((pendingExpressionName &&
       timeNow - pendingExpressionName.queuedAt > 20000) ||
      (pendingSpeechParts &&
       timeNow - pendingSpeechParts.queuedAt > 20000)) {
    // Never carry a previous, unheard body cue to the next phone turn.
    pendingExpressionName = null
    pendingSpeechParts = null
  }
  // Low amplitude, non-periodic eye saccades. Listening favors eye contact;
  // speaking allows a brief glance away. Deliberate AI gaze overrides both.
  // Never move the whole head or imply a particular emotional state.
  if (timeNow >= gazeReflex.nextAt) {
    const radius = listening ? .045 : playing ? .13 : .085
    gazeReflex.x = (Math.random() * 2 - 1) * radius
    gazeReflex.y = (Math.random() * 2 - 1) * radius * .65
    gazeReflex.nextAt = timeNow + 2500 + Math.random() * 3500
  }
  // Cubism's authored idle motion, breathing and eye-blink already handle
  // the body's life. Do not impose a perpetual clock-driven left/right
  // head sway: it looks arbitrary and can erase the character's intentional
  // posture. Only a genuine external head cue sets these three targets.
  const axes = [
    ['ParamAngleX', 'yaw'],
    ['ParamAngleY', 'pitch'],
    ['ParamAngleZ', 'roll'],
  ]
  for (const [id, axis] of axes) {
    if (core.getParameterIndex(id) < 0) continue
    // Let model-authored motion own the neutral posture. Once an explicit
    // gesture finishes, blend back to neutral, then release this override
    // entirely so we no longer fight the original motion3 animation.
    const requested = headRequest?.[axis]
    if (requested == null && Math.abs(smoothPose[axis]) < .12) {
      smoothPose[axis] = 0
      continue
    }
    const target = requested ?? 0
    smoothPose[axis] += (target - smoothPose[axis]) * Math.min(1, deltaMs * .010)
    core.setParameterValueById(id, smoothPose[axis])
  }
  for (const [axis, id] of Object.entries(BODY_BINDINGS)) {
    if (core.getParameterIndex(id) < 0) continue
    const requested = bodyRequest?.[axis]
    if (requested == null && Math.abs(smoothBody[axis]) < .10) {
      smoothBody[axis] = 0
      continue
    }
    smoothBody[axis] += ((requested ?? 0) - smoothBody[axis]) *
      Math.min(1, deltaMs * .008)
    core.setParameterValueById(id, smoothBody[axis])
  }
  for (const [key, ids] of Object.entries(FACE_BINDINGS)) {
    // Eye tracking and facial states can combine with authored expression
    // files (e.g. blush). They are NOT selected randomly each animation frame.
    if (ids.some(id => core.getParameterIndex(id) < 0)) continue
    const requested = faceRequest?.[key]
    const resting = key === 'gazeX' ? gazeReflex.x :
      key === 'gazeY' ? gazeReflex.y : 0
    const target = requested ?? resting
    const previous = smoothFace[key] || 0
    if (requested == null && Math.abs(previous) < .018 &&
        Math.abs(resting) < .018) {
      smoothFace[key] = 0
      continue
    }
    const next = previous + (target - previous) * Math.min(1, deltaMs * .008)
    smoothFace[key] = next
    for (const id of ids) core.setParameterValueById(id, next)
  }
  // A natural reflex, not an invented emotional intention: this particular
  // model has an empty EyeBlink group but exposes both eyelid parameters.
  // Avoid overwriting eye opening outside the brief blink interval.
  if (proceduralBlinkEnabled &&
      core.getParameterIndex('ParamEyeLOpen') >= 0 &&
      core.getParameterIndex('ParamEyeROpen') >= 0) {
    const dt = Math.min(.06, Math.max(0, deltaMs / 1000))
    if (blinkProgress < 0) {
      blinkClock -= dt
      if (blinkClock <= 0) blinkProgress = 0
    } else {
      blinkProgress += dt / .22
      const closing = Math.sin(Math.min(1, blinkProgress) * Math.PI)
      for (const id of ['ParamEyeLOpen', 'ParamEyeROpen']) {
        const current = core.getParameterValueById(id)
        core.setParameterValueById(id, current * (1 - closing * .95))
      }
      if (blinkProgress >= 1) {
        blinkProgress = -1
        blinkClock = 3.8 + Math.random() * 2.8
      }
    }
  }
  if (core.getParameterIndex('ParamMouthOpenY') >= 0) {
    // AIRI uses a speech-release tail rather than snapping the jaw shut on
    // the very first silent frame. Don't permanently own the mouth during
    // idle, either: the model's own expression may need this parameter.
    if (playing) {
      const target = Math.min(1, Math.max(0, mouth))
      const alpha = Math.min(1, deltaMs * (target > smoothedMouthOpen ? .023 : .015))
      smoothedMouthOpen += (target - smoothedMouthOpen) * alpha
      mouthReleaseUntil = timeNow + 470
      core.setParameterValueById('ParamMouthOpenY', smoothedMouthOpen)
    } else if (timeNow < mouthReleaseUntil || smoothedMouthOpen > .012) {
      smoothedMouthOpen += (0 - smoothedMouthOpen) * Math.min(1, deltaMs * .019)
      if (smoothedMouthOpen < .012) smoothedMouthOpen = 0
      core.setParameterValueById('ParamMouthOpenY', smoothedMouthOpen)
    }
  }
}
function installPostMotionControl(loadedModel) {
  const manager = loadedModel.internalModel?.motionManager
  if (!manager || typeof manager.update !== 'function') {
    status('模型未提供 Cubism 动作管理器', 'error')
    return false
  }
  const original = manager.update
  let lastMotionTime = 0
  manager.update = function (core, now) {
    const updated = original.call(this, core, now)
    const deltaMs = lastMotionTime ? Math.min(Math.max(now - lastMotionTime, 0), 60) : 16
    lastMotionTime = now
    applyAfterMotion(core, deltaMs)
    return updated
  }
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
    resolution: Math.min(2.35, window.devicePixelRatio || 1),
    autoDensity: true,
  })
  root.insertBefore(app.view, notice)
  app.ticker.remove(app.render, app)
  // AIRI's Canvas.vue wraps ticker renders so animation exceptions cannot
  // silently terminate rendering while Android still shows a "ready" status.
  app.ticker.add(() => {
    try {
      app.render()
    } catch (error) {
      app.ticker.stop()
      status('Live2D 动画渲染中断：' + (error?.message || String(error)), 'error')
    }
  })
  app.ticker.maxFPS = 30
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
      resetSpeechExpression()
      pendingExpressionName = null
      pendingSpeechParts = null
      app.stage.removeChild(model)
      model.destroy({ texture: true, baseTexture: true })
      model = null
    }
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
    if (!installPostMotionControl(model)) throw new Error('缺少可控制的 Live2D 动作管理器')
    app.stage.addChild(model)
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
      coreModel?.getParameterIndex(id) >= 0)
    notify('parameters', available.join(','))
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
  if (selected) startSpeechExpression(selected)
  if (parts?.head) head(parts.head)
  if (parts?.body) body(parts.body)
  if (parts?.face) face(parts.face)
}
function onPlaybackEvent(phase) {
  if (phase === 'playing') {
    playing = true
    lastSpeechSoundAt = Date.now()
    activatePendingSpeechMoment()
    return
  }
  if (phase !== 'finished' && phase !== 'interrupted') return
  playing = false
  mouth = 0
  pendingExpressionName = null
  pendingSpeechParts = null
  headRequest = null
  bodyRequest = null
  faceRequest = null
  // The expressionManager fades back to the base expression itself.
  resetSpeechExpression()
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
  mouth = Number.isFinite(Number(next.mouth))
    ? Math.max(0, Math.min(1, Number(next.mouth))) : 0
  if (!playing) mouth = 0
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
    scheduleNativeStreamFrame(12)
    return
  }
  const bridge = window.LuluNativeFrames
  if (!bridge || typeof bridge.postMessage !== 'function') return
  let canvas
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
  nativeStreamBusy = true
  if (Number.isFinite(tickerTime)) nativeStreamLastTickerTime = tickerTime
  nativeStreamLastFrameAt = performance.now()
  try {
    // Asynchronous JPEG encoding avoids a blocking toDataURL on every frame.
    canvas.toBlob(async blob => {
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
    }, 'image/jpeg', 0.93)
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
  if (!nativeStreamEnabled) return
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
      nativeStreamAckMeanMs = nativeStreamAckMeanMs * .8 + ackMs * .2
      if (Number.isFinite(maxEdge)) {
        // Android's original adaptive controller capped capture to 1200px
        // even on a GPU now rendering ~2000px, wasting source detail.
        // Boost based on measured encode+IPC+decode throughput; if the bridge
        // gets slow fall back to its conservative requested frame size.
        const factor = nativeStreamAckMeanMs < 57 ? 1.38 :
          nativeStreamAckMeanMs < 77 ? 1.17 : 1.0
        nativeStreamMaxEdge = Math.min(2100,
          Math.max(760, Math.round(maxEdge * factor)))
      }
    }
    nativeStreamBusy = false
    // AIRI's Pixi ticker is capped at 30fps. Target its cadence without
    // ever starting a second encode before Android's decode ACK.
    scheduleNativeStreamFrame(Math.max(0, 34 - (performance.now() - nativeStreamLastFrameAt)))
  }
  notify('diagnostic', '已启用 Android WebMessage 二进制桥接（带解码回执）')
  scheduleNativeStreamFrame()
}

function setPaused(value) {
  paused = !!value
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
}
function startSpeechExpression(name) {
  if (!model || !name) return
  resetSpeechExpression()
  activeExpressionName = name
  activeExpressionStartedAt = Date.now()
  lastSpeechSoundAt = Date.now()
  Promise.resolve(model.expression(name)).catch(() => {
    if (activeExpressionName === name) resetSpeechExpression()
  })
}
function expression(name) {
  if (!model || !name || name.length > 128) return
  if (playing) {
    pendingExpressionName = null
    startSpeechExpression(name)
  } else {
    // The decision arrives before TTS plays; do not grimace throughout
    // network/voice generation latency.
    pendingExpressionName = { name, queuedAt: Date.now() }
  }
}
function motion(group, index) {
  if (!model || !group || !Number.isInteger(index) || index < 0) return
  Promise.resolve(model.motion(group, index)).catch(() => {})
}
document.addEventListener('visibilitychange', () => setPaused(document.hidden))
function face(target) {
  if (!model || !target || typeof target !== 'object') return
  if (!playing) {
    pendingSpeechParts = pendingSpeechParts || { queuedAt: Date.now() }
    pendingSpeechParts.face = target
    return
  }
  const next = Object.create(null)
  for (const [key, ids] of Object.entries(FACE_BINDINGS)) {
    if (ids.some(id => model.internalModel?.coreModel?.getParameterIndex(id) < 0)) continue
    const value = target[key]
    if (typeof value !== 'number' || !Number.isFinite(value)) continue
    const low = ['eyeSmile', 'squint', 'cheekPuff', 'pout'].includes(key) ? 0 : -1
    next[key] = Math.max(low, Math.min(1, value))
  }
  if (!Object.keys(next).length) return
  faceRequest = next
  faceRequestEndAt = Date.now() + 4200
}
function body(target) {
  if (!model || !target || typeof target !== 'object') return
  if (!playing) {
    pendingSpeechParts = pendingSpeechParts || { queuedAt: Date.now() }
    pendingSpeechParts.body = target
    return
  }
  const next = Object.create(null)
  for (const [axis, id] of Object.entries(BODY_BINDINGS)) {
    if (model.internalModel?.coreModel?.getParameterIndex(id) < 0) continue
    const value = target[axis]
    if (typeof value === 'number' && Number.isFinite(value)) {
      next[axis] = Math.max(-10, Math.min(10, value))
    }
  }
  if (!Object.keys(next).length) return
  bodyRequest = next
  bodyRequestEndAt = Date.now() + 3000
}
function head(target) {
  if (!model || !target) return
  if (!playing) {
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
window.LuluLive2D = { load, update, onPlaybackEvent, setPaused, expression, motion, head, face, body, setViewport, setPresentationMode, captureNativeFrame, setFramingMode, setNativeStreaming }
if (window.__luluNativeViewport) {
  setViewport(window.__luluNativeViewport.width, window.__luluNativeViewport.height)
}
if (window.__luluStageState) update(window.__luluStageState)
if (window.__luluStageModelUrl) load(window.__luluStageModelUrl)
else status('请选择 Live2D 模型')
