# Cubism Native Android GPU：真实接入条件与操作

**当前状态（2026-10-22）：Native OpenGL 渲染尚未实现，正式电话只使用已在真机验证的 WebGL → Compose 帧桥。**

## 为什么不能宣称已经完成

露露机当前仅有 `core/live2dcubismcore.min.js`（Web Core），不能让 Android NDK / OpenGL ES 的本地 Surface 直接解析和绘制 Cubism 模型。Live2D 官方确认 Native Core 不发布到 GitHub，而是随官方 Native SDK 分发。这个组件不是依靠 JavaScript、AIRI 的 Vue 组件或一个空的 SurfaceView 能替换的。

官方资料：
- Native SDK：https://docs.live2d.com/en/cubism-sdk-manual/cubism-sdk-for-native/
- Android OpenGL 平台：https://docs.live2d.com/en/cubism-sdk-manual/platform/
- Native Core 分发：https://docs.live2d.com/en/cubism-sdk-manual/cubism-core/
- CubismNativeSamples：https://github.com/Live2D/CubismNativeSamples
- CubismNativeFramework：https://github.com/Live2D/CubismNativeFramework

## 获取与验证（不将第三方 Core 二进制提交到公开仓库）

1. 从 Live2D 官方下载适用于 Android 的 Cubism SDK for Native，并阅读相应授权协议。
2. 解压到本机 **Git 忽略目录外**，应至少有 `Core/include/` 与 `Core/lib/android/arm64-v8a/`。按官方 SDK 版本查看是否包含 .a / .so 静态或动态库。
3. 在有 Android SDK/NDK 的机器运行：
   `./gradlew :app:verifyCubismNativePrerequisites -PluluNativeSdkDir=/absolute/path/to/CubismSdkForNative`
4. 这个任务只验证 Native Core 的基础文件**确实存在**，不是 Native 渲染功能已完成的证明。

## 在项目中实现 Native 时的硬性验收

- 单独的 `NativeCubismSurface`（EGL/OpenGL ES + JNI + CubismFramework）提供加载 moc3、贴图、物理、表情、模型实例释放、Surface 重建；图集与原始模型一直在用户设备私有目录。
- GPU 绘制直接输出到 Android `Surface`，不经 WebGL canvas 截图、JPEG 编码、WebMessage IPC、BitmapFactory 解码这条链。
- 只接受 `Live2DSpeechTimeline` 与 `Live2DVisualActionBus` 的同一 callId 数据；口型按实际播放器的 PCM 更新；模型资源缺失须被拒绝，不能替角色决定虚假的动作。
- 在 Redmi K90 Pro Max 上分别测试旋转、后台前台切换、10 分钟视频通话、发热/降频、连续播放和打断。
- **回退策略：** 仅在本机 Native Core + GL 初始化 + 首帧屏幕可见检查全部通过后使用 Native；任一步失败均维持原先已验证的 WebGL→Compose 兼容线路。旧线路不能因接入 Native 而移除。

## 四阶段对应状态

| 阶段 | 目前已实现 | 仍欠缺 |
|---|---|---|
| ① 视觉时间线 | callId + utteranceId、实际开始/结束/失败/插话/挂断撤销、JUnit | 每一句话内部更细粒度的语义转折与动作锚点 |
| ② 语音同步 | 真实播放开始启动表情、PCM 幅度驱动嘴部、平滑收尾、暂停/中断清理 | 真正的音素 viseme 精准映射、逐句动态情绪 |
| ③ Native GPU | 可检验官方 SDK Core 是否在本机存在的 Gradle 任务与工程验收清单 | 正式 EGL/CubismFramework/C++ 渲染实现；Core 分发包是必要输入 |
| ④ 画质性能 | JPEG 二进制流、兼容回退、分辨率档位切换、FPS/p95/解码耗时反馈、JUnit | 8192 图集显存预算实测与 Native 直接纹理管线的手机验收 |

**不把 Native 依赖写死进普通 APK 构建**，防止在官方 SDK 未提供时毁掉能用的语音和视频功能。