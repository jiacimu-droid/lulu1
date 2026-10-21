// Render-only adapter. Character cognition, call control and audio remain in Android.
import * as PIXI from 'pixi.js'
import { Live2DModel } from 'pixi-live2d-display/cubism4'

window.PIXI = PIXI
const root = document.getElementById('stage')
const notice = document.getElementById('notice')
let app
let model
let playing = false
let mouth = 0
let listening = false
let paused = false
let seconds = 0

function status(message) {
  notice.textContent = message || ''
  notice.style.display = message ? 'block' : 'none'
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
function tick(delta) {
  if (!model || paused) return
  seconds += Math.min(delta / 60, .05)
  const core = model.internalModel && model.internalModel.coreModel
  if (!core) return
  try {
    core.setParameterValueById('ParamMouthOpenY', playing ? Math.min(1, Math.max(0, mouth)) : 0)
    // Tiny idle movement is a renderer-only interpolation, never a reported world action.
    core.setParameterValueById('ParamBodyAngleX', Math.sin(seconds * .66) * 1.4)
    core.setParameterValueById('ParamAngleX', listening ? 1.3 : Math.sin(seconds * .47) * 1.6)
  } catch (_ignored) { /* Older models may omit optional parameters. */ }
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
  app.ticker.add(tick)
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
    if (model) { app.stage.removeChild(model); model.destroy(); model = null }
    model = await Live2DModel.from(modelURL, { autoInteract: false, autoUpdate: true })
    app.stage.addChild(model)
    fit()
    status('')
  } catch (error) {
    status('无法加载动态模型：' + (error && error.message ? error.message : String(error)))
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
window.LuluLive2D = { load, update, setPaused }
status('请选择 Live2D 模型')
