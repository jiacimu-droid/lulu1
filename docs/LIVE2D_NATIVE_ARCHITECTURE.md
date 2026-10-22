# 露露机 Live2D 高清与活人感架构方案（2026-10-22）

> 本方案针对 lulu1 Android／Redmi K90 Pro Max 真机已确认的事实。目标是消除“人物可见但 JPEG 模糊、FPS 尚可却不匀、表情与话不一致”的根因。先保障现有电话与已成功的 Compose 兼容画面不回归，再逐步换掉逐帧截图线路。

## 0. 已验证事实与不能混淆的指标

- 2026-10-22 真机曾显示 **高速 27.6 FPS · 552×1200px**；该 FPS 是 Android 解码后收到的帧数，不等于 GPU 60FPS，也不能证明帧间隔稳定。用户主观反馈面部仍显模糊。
- 小狼原始图集约 8192×8192。 `Live2DMobileTextureAdapter.kt` 当前为兼容 WebView 将超尺寸纹理缓存成 **4096 PNG**；这与运行帧尺寸、视频 JPEG 压缩是三种独立的质量损失源。不能把图片 JPEG 质量调至 100% 当成解决了原图降采样。
- `tools/live2d/src/stage.js` 用 Pixi/Cubism WebGL 绘制，再经过 canvas.toBlob JPEG、WebMessage ArrayBuffer、BitmapFactory 与 Compose Image 显示。先前直接 WebView 在用户 HyperOS 设备上黑屏，原生 Compose 合成兼容路径通过 Android PixelCopy 检测；**绝不可因重构删除后备路径**。
- `Live2DVisualActionBus` 负责验证实际模型表达式、动作和参数，通话 LLM 只需在原有决策中输出少量意图，无需额外付费 API。用户模型有丰富面部参数但缺独立 motion3 文件；算法不能凭空创造挥手动作。

## 1. AIRI 实际可借鉴的层次

源码：
- `packages/stage-ui-live2d/src/components/scenes/live2d/Canvas.vue`：明确分辨率、模型绘制与渲染异常边界；正常网页舞台是 Pixi 渲染器直接输出，不是逐帧 JPEG 发回另一个 UI。
- `packages/stage-ui-live2d/src/composables/live2d/motion-manager.ts`：`pre/post/final` 动作插件，按确定次序叠加 Idle、焦点、眨眼、表情、口型和手动身体动作。
- `packages/stage-ui-live2d/src/contexts/expressions.ts` 与 `packages/stage-ui/src/constants/prompts/live2d-act.ts`：有持续时间的表情命令、复位；仅公布真实导入模型可执行的控制。
- `packages/stage-ui/src/components/scenes/Stage.vue` 和 `packages/model-driver-lipsync/src/live2d/index.ts`：音频侧驱动真实口型信号，区分 speaking/voicing。
- `packages/stage-ui-live2d/src/composables/live2d/animation.ts`：极轻微闲时眼球扫视，不能用固定正弦摆头替代人的真实意图。

**AIRI 不是“每一帧都调用 AI 来决定身体动作”，也不是 Android 已验证的 GPU 直绘方案。** 它把离散意图、自然反射、音频信号与每帧插值分开。迁移其职责划分而非照抄 Vue／浏览器专属代码。

## 2. 目标架构

```
一通电话 / 持久 callExperienceId
    |
    +-- 电话决策（原有角色人设、情绪、关系、经历，原有 API 请求）
    |      -> VisualIntent { expression?, gaze?, face?, head?, body?,
    |                        intensity?, holdMs?, clearOnInterrupt? }
    |
    +-- 实际 TTS/PCM 播放事件（原有通话语音引擎）
    |      -> SpeechTimeline { utteranceId, startedAtMonotonic, sections,
    |                          playbackStart, pause, finish, interrupt }
    |
    +-- VisualTimelineScheduler（新增本地调度；不额外调用 LLM）
    |      -> 只对当前 callId 接受指令；替换旧指令/打断即撤销
    |      -> 将角色真实意图锚定在当前正在播放的句子／停顿
    |
    +-- BodyAnimator（新的单一参数归属者）
    |      -> Base: 作者自己的 motion、呼吸、物理效果
    |      -> Reflex: 微眼动、自然眨眼（极小幅度，无叙事含义）
    |      -> Intent: 限时表情、目光、眉眼与头部/躯干姿态
    |      -> Speech: 基于真实 PCM 音量与音素的嘴型；收尾淡出
    |      -> 权限与优先级：真实音频口型 > 限时表情参数 > Reflex
    |      -> 对每个模型参数只在一个有序阶段最终写一次
    |
    +-- RenderBackend 接口（与上面三个层分离）
           NativeCubismOpenGL（计划）
           WebGLToComposeCompat（目前已验证）
           StaticCreatorPortrait（最后保底）
```

其中 `VisualIntent` 只表达角色意图，不改变人格或记忆，不另建心理中心；参数解析器从**实际导入的模型**自动映射，保证不能超出资源能力。音频中断与主动挂断之后，旧 `callId` 的所有动作、待发 cue 与音量流立即作废。

## 3. 高清路线：不再追逐 JPEG 参数

**阶段 A（已有兼容桥的工程化优化）**：
1. 指标分清：`GPU render fps`、`WebGL readback/canvas copy ms`、`JPEG encode ms/bytes`、`WebMessage IPC ms`、`native decode ms`、`Compose present fps`、p95 间隔、丢帧数和最小/最大分辨率。
2. 先统一设备像素倍率和尺寸单位：`WebView CSS px`、`app.view.width`、`Android physical px`、`JPEG source px`，不能“截图高宽”高于真实 WebGL 细节却宣称高清。
3. 图集质量独立可选：目前 4096 缓存可继续默认。对支持 GPU 8192 的设备可灰度测试原始 8192 图集，但必须加 GPU 最大纹理、显存/内存预算、加载失败回退和连续 10 分钟通话稳定性验证；原始 ZIP 和缓存严格分开，且不上传私有模型。
4. 若不得不传 JPEG，使用异步编码/二进制/解码回执，限制并发为一帧；快设备优先保住面部精度，热降频时先降低背景效果与分辨率。

**阶段 B（推荐正式版）**：Android Cubism SDK for Native + OpenGL ES、`SurfaceView`/EGL、NDK/C++ JNI，`BodyAnimator` 每帧本地驱动。帧直接交 Android Surface 合成：删除 WebGL->Canvas readback->JPEG->IPC->Android JPEG decoder 这一整段。官方 Android OpenGL 支持见 https://docs.live2d.com/en/cubism-sdk-manual/platform/ 。

Native Core 是许可受控资源，不发布在 GitHub（https://docs.live2d.com/en/cubism-sdk-manual/cubism-core/）。须经用户认可许可，从官方下载相应 Android ABI 的分发包；先在实验入口用 Hiyori 构建独立样例并完成加载、卸载/切后台、Surface 重建和纹理测量，再切换正式电话，旧兼容桥保留作为一键回退。无 Core 的时候**不要谎称 Native 已就绪**。

## 4. 真实活人感：要的是同一句话里的时间变化

- **语义层**：角色保留自主决定表达意图的权利，允许安静、保持中性；场景知晓是“本人 Live2D 虚拟视频”，但不能声称看到用户真实摄像头。
- **句子时间层**：现在每轮只有一个 `live2d` cue，会导致前半句/后半句长期用同一张脸。今后需要 `VisualTimeline` 和 PCM/TTS 分句边界。一个 LLM 输出可带几个稀疏动作锚点（首句、说到一个情绪转折点、结束）；如果模型不标注，调度器只保留一个短表情，不依据关键词硬编情绪。
- **音频层**：真正开始播放声音才启动表情；停顿时减弱嘴部动作；被打断、静音、挂断、切到语音时撤销，避免积压的表情在下次说话才触发。若可得到音素/viseme，用 A/E/I/O/U 近似元音驱动嘴形；否则当前 PCM 幅度驱动 `ParamMouthOpenY`，限制 20~30Hz 并采用 100~200ms 收尾。
- **表现层**：原模型动画（呼吸、兽耳、头发）保持；自然眨眼与轻微眼球凝视属于反射层，不是角色自主“随机左右乱动”。角色决定“有点不好意思”后，可以在目标层组合眼神移开、嘴角微扬、轻歪头，再在短时间内平滑回归。要有 priority、可打断、明确生命周期。

## 5. 验收与分阶段切换

| 验收 | 不能靠单张截图证明 |
|---|---|
| 清晰度 | 肉眼在原生物理尺寸审视眼睛/睫毛，给出 GPU/帧/图集各自真实分辨率；不能只读 JPEG header |
| 顺滑度 | 10 分钟实际视频通话、逐帧间隔直方图与 p95/p99，不只平均 FPS；给出掉帧/热降频 |
| 灵动度 | 静默、正常聊天、强烈情绪、短暂停顿、较长段落、打断、结束通话，动作在正确时刻生效或撤销 |
| 安全边界 | 已上传角色模型只保留本机私有存储；不改变语音模型路由/角色记忆/角色设定；无额外付费 API |
| 功能回退 | Native 资源未就绪 / EGL 初始化失败时仍可使用已经验证过的 Compose 图片兼容方案 |

**实施顺序**：先补可测量遥测 → 抽取单一 VisualTimelineScheduler/BodyAnimator 和按句 TTS 事件 → Native Cubism 研发支线验证原生渲染（不在正式用户界面冒险替换）→ 手机实际对照 → 通过再设默认。当前低幅度自然眼动和口型平滑属于兼容阶段的先行小改动，不能宣称已经实现全套架构。
