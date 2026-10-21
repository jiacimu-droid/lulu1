# Lulu1 · AIRI 式 Live2D 虚拟视频通话（首版）

## 已落代码的部分

- 私聊点通话图标时先选择「语音通话 / Live2D 视频通话」，直接进入对应模式；仍可随时互切、缩小和恢复，不重新拨号。群聊电话和角色主动来电仍走原来的语音入口。
- 电话 UI 增加「打开 Live2D 视频」/「切为语音」双向切换：语音保留原 UI，视频使用整个手机竖屏，由全屏 Live2D 画面 + 轻量状态/字幕/麦克风/声音路由/挂断控件组成，不再使用 248dp 的小灰框。切换不新建电话会话。
- Live2DAssets.kt 允许按角色导入模型 ZIP，并单独导入官方 Cubism Core JavaScript；具有路径安全、文件数与解压大小校验及模型资源引用校验。
- Live2DCallStage.kt 在隔离的 WebViewAssetLoader HTTPS appassets 域名里加载本地 PixiJS Live2D WebGL 舞台，不使用网络 CDN 和原生 JavaScriptInterface，不向渲染器注入聊天历史或密钥。
- tools/live2d 使用 PixiJS 6.5.10 和 pixi-live2d-display 0.4.0，与 AIRI 的主要依赖版本一致；已有 release.yml 在 Android 构建前使用 esbuild 将资源打包到 APK。
- 云端 ElevenLabs/MiniMax 电话播放的 PCM 振幅回调控制嘴部开合，监听真实播放开始/停止，渲染故障不得阻断通话。
- 角色在视频内能预览所导入模型真正自带的表情/动作；不生成不存在的手势。
- ZIP 的作者完整预览图（例如「小狼.png」）与 Live2D 碎片纹理图分开处理。新导入和旧版已导入模型均可尝试识别未被 model3.json 引用的作者立绘：WebGL 未就绪时展示静态全身/半身预览，保持原像素比例，显示原图宽高和横/竖方向。没有作者预览图则退回原角色头像并说明。
- 仿照 AIRI 的 manifest adapter，扫描未登记的 exp3/motion3 资源，将虚拟修订后的 model3.json 只提供给本地渲染 WebView，绝不修改用户存储的原始配置。
- 角色本人在 **同一次电话决策** 可选附加 live2d 表情、动作或头部 yaw/pitch/roll；只在 WebGL 模型实际报告 loaded 和所支持参数后向电话模型提供允许的动作名单。表情和动作必须匹配模型实际清单，角度只对存在的 ParamAngleX/Y/Z 生效，且本地校验/平滑；视频关闭不执行、不额外请求大模型。
- 视频尚未拨通而模型已成功渲染时，右上角更多菜单可**手动测试**模型真实提供的表情/动作/已确认的头部角度。此测试是主人自选，不进入角色决策或永久时间线；接通后的视觉动作由角色本人自主选择。动态舞台报错或超过25秒仍未完成加载时，展示作者静态预览及明确诊断，而不是无限灰框。

## 准备与使用

1. 打开原电话并导入自己的 Live2D 模型 ZIP；当模型存在且首次准备开启 Live2D 时，应用自动提示阅读并确认 [Live2D 中文专有许可](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_cn.html)。确认后，应用直接下载 [Live2D 官方托管的轻量 Cubism Core](https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js)，无需下载完整 SDK ZIP，不会把 Core 提交到 GitHub 或公开 APK。以后新角色复用该本地 Core，不再反复确认。没有模型则不会发起 Core 下载；如果官方下载失败，页面提供重试与手动导入。
2. 准备拥有使用许可的 Cubism3/4 导出模型，保持模型 .model3.json、.moc3、纹理及可选动作/表情的相对路径，一起打包 ZIP。当前要求包里恰有一个 model3 入口；Cubism2 老模型、VRM/GLB 不属于当前渲染器。
3. 在线 Core 会在首次导入模型或打开视频时自动准备，无需单独点击下载按钮。失败时可重试，或手动选择 SDK 内的 live2dcubismcore.min.js。随后用原有电话模型和声音测试 Live2D。
4. 有需要时在视频画面中测试模型支持的原生表情和动作；返回语音画面不影响正在进行的通话。

官方 SDK 的网页版下载界面可能要求电脑；新版优先使用官网托管的单个 Core 文件（官网注明“Latest”更新可能带来兼容性风险），不下载整套开发包。联网与兼容性仍需真机验证。安装失败应保留旧 Core；不要使用来历不明的破解 Core。

## 重要的未完成与验收边界

- 用户仅有静态头像时，**不会自动变成能动的 Live2D 身体**，需要独立制作或获取正式绑定骨骼及表情的模型。
- 第一版已添加同次电话模型的可选 Live2D 动作选择接口和本地能力校验，但**尚未取得真机模型动作执行回执、全屏 UI 验收或持续视频通话压测**；不能宣称任何购买模型都能实现全部头部角度或复杂全身动作。模型只有作者制作并绑定的参数、表情或动作才能执行。
- 目前只给 ElevenLabs、MiniMax 的此条 PCM 电话播放链路接入振幅数据。系统 TTS 和其他实时语音代理尚无同等 PCM 回调，不能称所有声音均口型精确同步。
- 音量包络是口型近似，并非音素/viseme 对齐；AudioTrack 缓冲延迟、WebView 性能、模型兼容、后台切换以及长时间运行必须真机测试。
- 不涉及 Godot/VRM 3D 漫游、持手机自由摄影机或真实家具物理交互。
- 本文说明源码能力，不宣称 APK 构建与手机实际运行已经验收通过。


## 面向角色自己的视频表达规则（按 AIRI 机制对照）

AIRI 的 Model.vue 将模型原有 motion.update 与表情、手动姿态、眨眼、口型等按 pre/post/final 顺序组成同一动画管线；直接在普通 Pixi ticker 中写入嘴巴或头部参数，会被 Cubism 下一轮更新覆盖。当前 lulu1 的 stage.js 已将口型和头部控制移进 Cubism motionManager.update 的后处理，保留 SDK 原有动作计算。

**谁控制什么**
- 角色：同一次电话决策可选 0/1 个或多个被本地 schema 支持的表情、动作和头部姿态；按自己的真实心理和现场交流决定，允许保持安静、不做手势，不另开模型调用或统一性格模板。
- 本地身体引擎：低强度的待机微动、TTS PCM 嘴型、插值和动作结束后的回中；这些属于渲染，不代表角色产生了新的长期意图。
- 能力校验：只有模型已加载、模型清单中有对应 expression/motion，或实际 Cubism Core 报告了 ParamAngleX/Y/Z 参数时才允许运行。模型无该能力则不做，不能虚构完成状态。
- 控制冲突：模型原始 motions 先更新；外部身体姿态/口型在同一轮后处理。若需要精细优先级、动作中断/完成和表情混合，应继续借鉴 AIRI motion manager 的插件分层。
- 因果连续性：实际执行状态与角色决策区分。当前 JS 尚未向主时间线写入逐个动作的可靠执行回执，不应当作「已经真实执行」的证据。

**人性化表达层的验收**：自然停顿有微表情、眼神会自然漂移但不一直盯镜头；说话的嘴型跟实际声音同时开始/停止；转头、点头动作仅在对应模型参数存在时启用。长时间测试应检查画面/音频延迟、温度、耗电、失焦恢复和 WebView 重建。手机厂商 GPU 限制以及用户购买的模型绑定差异不可能由静态代码审查保证 100% 兼容。

## 用户摄像头（隐私与视觉范围）

- 在全屏 Live2D 视频电话顶部可主动点击摄像头图标。首次需请求 Android CAMERA 权限，默认关闭；选择后仅生成本地前置摄像头画中画。切回语音或关闭视频组合界面释放 CameraX Preview。
- **本机摄像头预览不等于角色实时看见用户**：当前没有 CameraX ImageAnalysis、图像上传或视觉模型调用。预览画面不会发送给外部 AI、不会入库、不会让模型虚构「看见用户」。显示明确「本机预览 · 未分享给 AI」文案。
- 如将来开放「让角色看一眼」，必须增加单独的明确图像分享动作：用户确认后截取有限的一帧，图像经用户配置的视觉模型处理，返回的结果带时间、来源及可撤回记录，再交给当前角色已有的同一通话上下文。不能在用户只打开摄像头时自动连续上传，也不能隐瞒 API 费用和隐私含义。

## APK 与真机验收路径

1. 既有 GitHub Actions .github/workflows/release.yml 的 Live2D JS bundle 必须成功；本地 build 不关闭 stage.html 的 CSP，不允许远程 CDN。
2. Gradle :app:testDebugUnitTest、:app:assemblePhone、APK 产物检查和 Release 发布必须成功，不得把「代码提交」误称为安装通过。
3. 真机必须依次验收：保留已导入小狼资源 → 静态预览可见 → 核心加载成功 → 动态 Live2D 身体实际运动 → 原生模型动作预览 → 角色自选动作 → PCM 语音口型 → 语音/视频互切 → 摄像头权限开/关及释放。
4. 失败时保留静态预览，显示可读错误，不删除现有模型或修改旧通话记录。


## 构建与许可

本地构建与已有 CI 一致，先执行：
- npm install --prefix tools/live2d --ignore-scripts --no-audit --no-fund --no-package-lock
- npm run build --prefix tools/live2d
- gradle :app:testDebugUnitTest :app:assemblePhone

生成的 app/src/main/assets/live2d/renderer.js 属于 APK 构建产物。APK 运行时不用网络 CDN 加载 Pixi；Cubism Core 由用户本地导入。渲染器默认至多 30fps，优先保持通话声音稳定。
AIRI 项目：https://github.com/moeru-ai/airi
Pixi Live2D 渲染库（MIT）：https://github.com/guansss/pixi-live2d-display
官方 Cubism Core 许可说明：https://docs.live2d.com/en/cubism-sdk-manual/cubism-core/

## 2026-10-22 WebView `unsafe-eval` 根因和防复发

**症状**：作者静态预览图可以显示，但 Cubism 模型不能渲染，提示
`Current environment does not allow unsafe-eval, please use @pixi/unsafe-eval module`。

**确认根因**：本项目固定 PixiJS **6.5.10**。该版本
`@pixi/unsafe-eval/src/index.ts` 仅导出 `install`；和 PixiJS 7+ 的自动副作用安装不同，
单独 `import '@pixi/unsafe-eval'` **并不会补丁 ShaderSystem**。导致新包仍然抛同样的异常。
正确用法：
```js
import * as PIXI from 'pixi.js'
import { install as installCspShaderAdapter } from '@pixi/unsafe-eval'
installCspShaderAdapter({ ShaderSystem: PIXI.ShaderSystem })
// 必须在 new PIXI.Application() 之前执行
```

**不会为了让它运行而添加 CSP `'unsafe-eval'`**：该安装程序重写 ShaderSystem 的检查与 Uniform 同步，实现无动态 `new Function` 的着色器同步，保留原本的隔离和 CSP 约束。

**参考证据**：
- https://github.com/pixijs/pixijs/blob/v6.5.10/packages/unsafe-eval/src/index.ts
- https://github.com/pixijs/pixijs/blob/v6.5.10/packages/unsafe-eval/src/install.ts
- https://github.com/pixijs/pixijs/issues/9113

**验证分层**：npm esbuild bundle 成功、Android 单测和 APK 构建成功，均不能代替在用户当前手机 WebView 运行 Cubism 模型的最终验证。只有新版 APK 中见到真实动画、模型原生表情可播放、音频口型跟随、前后切换无丢音，才能声称动态体验通过。静态作者预览只能证明 PNG 可以解码。
