# Live2D 屏幕帧率优化 · 2026-10-24

## 现行事实

- 角色决策、触摸、音频和身体动作继续复用原有通话与 Live2D 执行器，本次不引入第二人格系统或新的模型调用。
- 既有 HyperOS/Redmi 真机出现过 WebView GPU 缓冲区有内容、Android 屏幕却黑屏，因此不直接取消已验证的 Android JPEG/Bitmap 兼容显示。
- 原兼容显示为 Pixi/Cubism WebGL → Canvas 缩放拷贝 → 异步 JPEG 编码 → WebMessage ArrayBuffer → Android Bitmap 解码 → ImageView 显示。优化编码不能让这一链自动变成原生 GPU 直出。
- 真正画面流畅度应先比较 WebGL 渲染 FPS、Android 解码送达 FPS、帧间隔 P95，并结合视觉观察/Perfetto 等判定屏幕合成瓶颈。每帧 GPU 提交时间不是 GPU 真正完成时间。

## 本轮实现

1. `tools/live2d/src/stage.js`：每约四秒上报一次 WebGL Pixi 帧率、JS 渲染提交耗时、Canvas 拷贝、异步 JPEG 回调耗时、Android IPC 往返。仅上报聚合时间，不上传像素或记录敏感素材。
2. JPEG 桥接仍严格维持一个 in-flight 帧和 Android 解码确认，优先按真实往返反馈采用约 45/36/30Hz 的发送节奏，替代先前即使编码和解码足够快也固定追加 34ms 的节流。码流和画面分辨率继续沿用原有自适应控制。
3. 直显时停止 JPEG 数据流，Pixi 渲染上限设置为 60 FPS；这不是保证物理屏幕已经达到 60 FPS。
4. `Live2DFullscreenVideoCall.kt` 的视频右上角菜单新增「试用 WebGL 直显」，暂时移去 JPEG 覆盖层，让 WebView 直接画人物。随后对 Android 窗口真正 PixelCopy 采样；仅采样到足量人物像素才认为设备可用并在本机为该模型记住成功结果。
5. PixelCopy 失败、超时、返回未知/黑色时，停止直显尝试并恢复现有 JPEG/Bitmap 兼容画面；不重建正在通话的角色会话、不取消 TTS 或触摸数据。旧机型不会因为无法采样而假定直显成功。用户主动切回兼容会撤销该模型的「直显成功」本地标记。
6. 同一个视频菜单同时显示「WebGL FPS/开销」和「Android 解码送达 FPS/P95」，避免将 GPU 提交频率误当实际屏幕显示率。

## 可复核的验收

- **基本通话**：未切换任何选项，原兼容视频、触摸、TTS 和字幕保持工作。
- **直显成功**：打开视频、等待出现真实人物，在右上角选择「试用 WebGL 直显」；通过 Android PixelCopy 后应仍有动态人物，且画面不再依赖 JPEG 回传。仅在实际渲染链上使用 60 FPS 上限。
- **直显失败**：模拟不支持 WebView 合成/PixelCopy 的机器，应自动返回兼容画面，角色通话不断，下一轮不自动复试失败通道。
- **性能指标**：持续 10 分钟分别记录半身/全身两种模式下的 WebGL FPS、送达 FPS、P95、分辨率、延迟，观察发热和掉帧。是否真的达到 60 FPS 必须由手机验证，不能由源码、渲染帧率或单测推断。
- GitHub 现有发布构建仍执行 `tools/live2d/test/parameters.mjs`、`tools/live2d/test/touch-hit.mjs`、Android JVM 单测和 `assemblePhone`。本轮增添测试对直显屏幕校验、自动回退、监测与动态节奏的源码回归断言。

## 后续 Native 路线

如果直显仍失败、兼容送达 FPS 较低，而诊断确认 WebGL 绘制能力有余量，进一步验证官方 Cubism Native SDK + Android GPU Surface 可以省去 JPEG 整条链路。仓库目前只有可选的 Native Moc3 解析探针，没有取得并内置授权的官方 Native GPU 完整渲染 SDK，因此 **本轮不宣称已经完成 Native GPU 渲染**。不得从第三方仓库直接挪用闭源 Core、贴图或受限许可代码。
