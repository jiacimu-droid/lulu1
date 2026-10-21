// The pixi-live2d-display Cubism4 module verifies the Core at evaluation time.
// Load user's officially licensed local Core BEFORE evaluating the bundled renderer.
// Never inject remote scripts, even for fallback.
(() => {
  const notice = document.getElementById('notice')
  const showError = message => {
    notice.textContent = message
    notice.style.display = 'block'
  }
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
