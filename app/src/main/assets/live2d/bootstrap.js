// The pixi-live2d-display Cubism4 module verifies the Core at evaluation time.
// Load user's officially licensed local Core BEFORE evaluating the bundled renderer.
// Never inject remote scripts, even for fallback.
(() => {
  const notice = document.getElementById('notice')
  const send = (type, detail) => console.info('__LULU_LIVE2D__' + JSON.stringify({
    type, detail: String(detail || '').slice(0, 200)
  }))
  const showError = message => {
    notice.textContent = message
    notice.style.display = 'block'
    send('error', message)
  }
  window.addEventListener('error', event => showError('渲染脚本错误：' + (event.message || '未知错误')))
  window.addEventListener('unhandledrejection', event => showError('模型加载异常：' +
    (event.reason?.message || '无法完成资源加载')))
  send('loading', '正在初始化 Cubism Core')
  const core = document.createElement('script')
  core.src = '/private/core/live2dcubismcore.min.js'
  core.onload = () => {
    if (!window.Live2DCubismCore) {
      showError('导入的 Cubism Core 未提供 Live2DCubismCore，请检查官方 SDK 文件')
      return
    }
    const renderer = document.createElement('script')
    renderer.src = '/assets/live2d/renderer.js'
    renderer.onerror = () => showError('Live2D 渲染器尚未打包，请检查 APK 构建')
    document.body.appendChild(renderer)
  }
  core.onerror = () => showError('缺少官方 Cubism Core。请在视频通话中先导入 SDK Core JS')
  document.head.appendChild(core)
})()
