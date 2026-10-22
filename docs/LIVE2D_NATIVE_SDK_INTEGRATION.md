# Cubism Native Android GPU：真实接入条件与操作

**当前状态（2026-10-23）：已实现可选择编译的官方 Native Core JNI 模型解析探针，但原生 OpenGL/CubismFramework 的完整 GPU 绘制仍未实现；正式视频继续使用 WebGL → Android Compose 图片兼容画面。**

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

## 本轮已落实到源码的 Native 接入基础（不是 GPU 出图）

- `app/build.gradle.kts` 识别 `-PluluNativeSdkDir=/absolute/path/...`，只有明确提供官方 SDK 时才将 ARM64 Core 加入本机 JNI 构建；普通 GitHub CI / 手机 APK 不打包来源不明的 Core。
- `app/src/main/cpp/CMakeLists.txt` 仅在配置该目录时链接官方 `Core/lib/android/arm64-v8a/libLive2DCubismCore.a`；`cubism_native_probe.cpp` 把真实 .moc3 内存加载为 csmMoc/csmModel 并返回 drawable 数量。不提交官方二进制、SDK 头文件或第三方完整渲染框架。
- `NativeCubismProbe.kt` 可检查导入模型的本机私有 Moc3 引用：必须位于原有模型私有存储目录，不能经相对路径访问外部文件。官方 Core 未编入当前 APK 时返回清晰的未就绪状态，不试图创建假 Surface。
- 在「Live2D 动态检测」中，若存在已链接 Native Core，则有单独的可点击解析检测；即使能够解析 Moc3，也只证明**Core 模型兼容**，不能宣称 OpenGL GLES 首帧、纹理上传、遮罩/物理、语音/表情控制已经实现。
- `AGENTS.md` 要求：此功能不可绕过 WebGL 兼容回退，特别是用户 HyperOS 设备先前真实遇到的黑屏；任何 Native Surface 只有通过可见首帧验证才可进入正式视频。
- 下一步仍需要官方 SDK for Native + Framework 的具体版本和 Android NDK 构建实际回执，再按 Live2D Samples 的 EGL/Cubism Framework 路径真正渲染。由于授权核心未在仓库，**本轮无法给手机用户提供原生 GPU 直出 APK**。

## 触摸输入已独立落地

- 在 Android Compose **真正可见的帧覆盖层**捕获轻点、长按、滑动；JS 调用 pixi-live2d-display `model.hitTest` 校验作者 HitAreas。模型没有作者 HitAreas 时才使用保守的模型投影轮廓估计，背景不得产生命中回执。
- 命中只触发轻微的本地参数弹簧位移，不自动表演害羞/占有等人格内容。屏幕点击回执会进入 `InteractionSignalBridge` 的同一通话原始时间线，并合并短时间内的连续动作；角色在既有电话模型中自主决定回应、动作或沉默。
- 连续触摸每组最多会产生**一个已有电话模型的新决策回合**，因此角色主动回应可能产生一次正常通话 API 费用；本地回弹和无命中点击不调用 AI。导入模型无手/头 HitAreas 时，部分触摸部位仅能做近似分类，不能宣称具有真实压力或体感。

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