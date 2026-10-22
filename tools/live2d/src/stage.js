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
let loadSequence = 0
let previewObserved = false
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
function fit() {
  if (!app) return
  const viewWidth = Math.max(1, root.clientWidth)
  const viewHeight = Math.max(1, root.clientHeight)
  app.renderer.resize(viewWidth, viewHeight)
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
    // An immediate render + readPixels can verify nontransparent body pixels
    // without storing or exposing camera/model pixels to any network service.
    try {
      app.renderer.render(app.stage)
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
      for (let i = 3; i < pixels.length; i += 4) {
        if (pixels[i] > 32) {
          previewObserved = true
          return
        }
      }
      priorError = '模型已加载但画布中央没有实际绘制出人物'
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
    backgroundAlpha: 0,
    antialias: true,
    resolution: Math.min(2, window.devicePixelRatio || 1),
    autoDensity: true,
  })
  root.insertBefore(app.view, notice)
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
  const sequence = ++loadSequence
  previewObserved = false
  status('正在加载 Live2D 模型…')
  try {
    await loadCore()
    if (sequence !== loadSequence) return
    ensureApp()
    gpuLost = false
    if (model) { app.stage.removeChild(model); model.destroy({ texture: true, baseTexture: true }); model = null }
    const loaded = await Live2DModel.from(modelURL, { autoInteract: false, autoUpdate: true })
    if (sequence !== loadSequence) {
      loaded.destroy({ texture: true, baseTexture: true })
      return
    }
    model = loaded
    describeTextureLimits(model)
    if (!installPostMotionControl(model)) throw new Error('缺少可控制的 Live2D 动作管理器')
    app.stage.addChild(model)
    fit()
    status('模型已解码，正在验证实际绘制画面…')
    await verifyVisibleFrame(model, sequence)
    if (sequence !== loadSequence) return
    status('', 'ready')
    const coreModel = model.internalModel?.coreModel
    const available = ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ']
      .filter(id => coreModel?.getParameterIndex(id) >= 0)
    notify('parameters', available.join(','))
  } catch (error) {
    if (sequence === loadSequence)
      status('无法呈现 Live2D 动态模型：' + (error?.message || String(error)), 'error')
  }
}
function update(next) {
  playing = !!next.speaking
  listening = !!next.listening
  mouth = Number.isFinite(Number(next.mouth)) ? Math.max(0, Math.min(1, Number(next.mouth))) : 0
  if (!playing) mouth = 0
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
window.LuluLive2D = { load, update, setPaused, expression, motion, head }
if (window.__luluStageState) update(window.__luluStageState)
if (window.__luluStageModelUrl) load(window.__luluStageModelUrl)
else status('请选择 Live2D 模型')
