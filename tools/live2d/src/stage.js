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
const smoothPose = { yaw: 0, pitch: 0, roll: 0 }

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
  model.scale.set(Math.min(viewWidth * .92 / originalWidth, viewHeight * 1.02 / originalHeight))
  model.anchor.set(.5, .5)
  model.position.set(viewWidth / 2, viewHeight * .53)
}
function notify(type, detail) {
  console.info('__LULU_LIVE2D__' + JSON.stringify({
    type, detail: String(detail || '').slice(0, 200)
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
  const axes = [
    ['ParamAngleX', 'yaw', listening ? 1.3 : Math.sin(seconds * .47) * 1.6],
    ['ParamAngleY', 'pitch', 0],
    ['ParamAngleZ', 'roll', Math.sin(seconds * .35) * .5],
  ]
  for (const [id, axis, idle] of axes) {
    if (core.getParameterIndex(id) < 0) continue
    const target = headRequest?.[axis] ?? idle
    smoothPose[axis] += (target - smoothPose[axis]) * Math.min(1, deltaMs * .008)
    core.setParameterValueById(id, smoothPose[axis])
  }
  if (core.getParameterIndex('ParamMouthOpenY') >= 0) {
    // Motion layers must never swallow the actual voice waveform.
    const target = playing ? Math.min(1, Math.max(0, mouth)) : 0
    core.setParameterValueById('ParamMouthOpenY', target)
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
    resolution: Math.min(2, window.devicePixelRatio || 1),
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
    notify('step', '④ model3.json：HTTP ' + settingsResponse.status +
      '，纹理 ' + (modelSettings.FileReferences?.Textures?.length ?? '?') + ' 张')
    if (modelSettings.luluMobileTextureError) {
      throw new Error('手机贴图优化失败：' + modelSettings.luluMobileTextureError)
    }
    if (sequence !== loadSequence) return
    if (model) { app.stage.removeChild(model); model.destroy({ texture: true, baseTexture: true }); model = null }
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
    notify('step', '⑥ 仅通过 WebGL/Canvas 验证；Android 屏幕仍需独立确认')
    const coreModel = model.internalModel?.coreModel
    const available = ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ']
      .filter(id => coreModel?.getParameterIndex(id) >= 0)
    notify('parameters', available.join(','))
  } catch (error) {
    if (sequence === loadSequence) {
      notify('step', '失败阶段：' + (error?.message || String(error)))
      status('无法呈现 Live2D 动态模型：' + (error?.message || String(error)), 'error')
    }
  } finally {
    if (sequence === loadSequence) inflightModelUrl = null
  }
}
function update(next) {
  playing = !!next.speaking
  listening = !!next.listening
  mouth = Number.isFinite(Number(next.mouth)) ? Math.max(0, Math.min(1, Number(next.mouth))) : 0
  if (!playing) mouth = 0
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
function setPaused(value) {
  paused = !!value
  if (app) { if (paused) app.ticker.stop(); else app.ticker.start() }
}
function expression(name) {
  if (!model || !name || name.length > 128) return
  Promise.resolve(model.expression(name)).catch(() => {})
}
function motion(group, index) {
  if (!model || !group || !Number.isInteger(index) || index < 0) return
  Promise.resolve(model.motion(group, index)).catch(() => {})
}
document.addEventListener('visibilitychange', () => setPaused(document.hidden))
function head(target) {
  if (!model || !target) return
  headRequest = {}
  for (const axis of ['yaw', 'pitch', 'roll']) {
    if (typeof target[axis] === 'number' && Number.isFinite(target[axis])) {
      headRequest[axis] = Math.max(-25, Math.min(25, target[axis]))
    }
  }
  headRequestEndAt = Date.now() + 1600
}
window.LuluLive2D = { load, update, setPaused, expression, motion, head, setViewport, setPresentationMode }
if (window.__luluNativeViewport) {
  setViewport(window.__luluNativeViewport.width, window.__luluNativeViewport.height)
}
if (window.__luluStageState) update(window.__luluStageState)
if (window.__luluStageModelUrl) load(window.__luluStageModelUrl)
else status('请选择 Live2D 模型')
