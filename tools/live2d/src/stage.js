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
  if (!app || !model) return
  const viewWidth = Math.max(1, root.clientWidth)
  const viewHeight = Math.max(1, root.clientHeight)
  app.renderer.resize(viewWidth, viewHeight)
  const scale = Math.min(viewWidth * .92 / Math.max(1, model.width / model.scale.x),
    viewHeight * 1.02 / Math.max(1, model.height / model.scale.y))
  model.scale.set(scale)
  model.anchor.set(.5, .5)
  model.position.set(viewWidth / 2, viewHeight * .53)
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
  status('正在加载 Live2D 模型…')
  try {
    await loadCore()
    ensureApp()
    if (model) { app.stage.removeChild(model); model.destroy({ texture: true, baseTexture: true }); model = null }
    model = await Live2DModel.from(modelURL, { autoInteract: false, autoUpdate: true })
    if (!installPostMotionControl(model)) throw new Error('缺少可控制的 Live2D 动作管理器')
    app.stage.addChild(model)
    fit()
    status('', 'ready')
    const coreModel = model.internalModel?.coreModel
    const available = ['ParamAngleX', 'ParamAngleY', 'ParamAngleZ']
      .filter(id => coreModel?.getParameterIndex(id) >= 0)
    console.info('__LULU_LIVE2D__' + JSON.stringify({ type: 'parameters', detail: available.join(',') }))
  } catch (error) {
    status('无法加载动态模型：' + (error && error.message ? error.message : String(error)), 'error')
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
