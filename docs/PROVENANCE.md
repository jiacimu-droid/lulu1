## 2026-10-30：digital-life-core 融合第二批（工具／注意力／诊断）

- **参考**：https://github.com/mashangshui/digital-life-core ，`main` 快照 `02fdd9383314dd84705775e13c68963286301f4c`，原仓库 `package.json` 标注 MIT。
- **来源标签：ORIGINAL**。依据权限内工具隔离、情绪注意力、状态可追溯的一般设计理念，在 Lulu1 原生 Kotlin 架构上独立开发。没有复制对方 TypeScript/README 代码、提示词、UI、生命状态参数、模型、图片、字体或任何资产；没有新增 DSH/Node 依赖。
- **具体修改**：`ToolRouter.kt` 将已保存执行 ID 冲突/失效来源统一进入既有拒绝回执；`RelevantMemoryRecall.kt` 为同一角色当前情绪的同源、已相关记忆增加有界只读排序；`CharacterLifeDiagnostics.kt` 及 `CompanionPresenceDialog.kt` 用现有真实目标、决策与运行记录呈现状态。相关回归测试在 `CharacterUnfinishedActionContextTest.kt`、`MemoryAffectAttentionTest.kt`、`CharacterLifeDiagnosticsTest.kt`。
- **不采用**：独立 Gaps/激素记账、自动将失败映射成愤怒、非受控自写代码器官、每轮多次付费分析、绕过用户授权或 VPN 的网络策略。详细实现范围、局限见 `docs/architecture/DIGITAL_LIFE_RESEARCH_ADOPTION.md`。
- **验证**：代码提交、静态核查、JVM 回归实际运行、GitHub APK 构建、Android 真机体验是不同等级；未取得的测试结果不可填写为通过。


## 2026-10-30：digital-life-core 的主动唤醒与真相复核思路

- **参考项目**：`mashangshui/digital-life-core`，默认分支快照 `02fdd9383314dd84705775e13c68963286301f4c`。源仓库 `package.json` 标注 MIT，来源：https://github.com/mashangshui/digital-life-core 。
- **标签**：ORIGINAL（根据一般系统设计原则独立修订原 Kotlin 实现），不是 THIRD_PARTY 代码移植。本仓未复制第三方源码、配置、模型、提示词或资源，没有新增 DSH 运行依赖。
- **实际修改**：`CharacterMotiveReview.kt` 的原有按计划指纹的 WorkManager 复核、忽略已错过计划的标记；`CharacterInnerLifeStore.kt` 延续已有权威 `motives` 和下一次复查机会；`ProactivePerceptionRuntime.kt` 对被其他事件抢占的非决定返回独立标记、禁止把早退记成目标已复查。相关测试在 `CharacterMotiveReviewTest.kt`。
- **暂不采用**：第二套 Gaps 账本、程序自动对目标意义打分、激素阈值强行控制行为、任意 TypeScript 自编译器官和重复付费决策调用。其余分阶段取舍见 `docs/architecture/DIGITAL_LIFE_RESEARCH_ADOPTION.md`。
- **验证边界**：需要项目原生 JVM/Android 自动测试、APK 构建及真实手机跨场景观测。代码修改与静态审查不代表这些验证已完成。

## 2026-10-25：AstrBot Agent 架构经验对照

- 参考：https://github.com/AstrBotDevs/AstrBot （AGPL-3.0）。
- 阅读了事件总线、管道调度、ToolLoopAgentRunner、Cron 唤醒和插件管理的公开源码，用于理解事件驱动、工具结果回流、能力状态与执行诊断的职责分工。
- 露露机本轮修改仅复用已有 Kotlin `ToolRouter`、`ProactivePerceptionScheduler`、`CapabilityRegistry`、`CharacterDevelopmentRuntime` 和感知检查点机制，独立实现已退役能力拦截、归因、generation 竞态防护与 SQLite 精确去重；**未复制 AstrBot Python 源码或移植其插件资产**。
- AstrBot 的通用 Agent 人设、会话调度与露露机持续人格/内心/成长不等价，未用第三方通用人格替代露露机人物因果契约。若未来复制/改编任何 AGPL 代码，需在引入前另行审查许可证义务。

## 2026-10-22：ears 思路借鉴 · 本机客观声学线索

- 参考：https://github.com/eveacla11/ears ，MIT；**独立 Kotlin 实现**，未复制 Python 算法、模型或项目文件。
- 只在现有 16 kHz PCM 电话转写链中计算本轮录音电平、部分可估计基频、强度起伏及可能停顿。无需新增网络服务、识别模型或收费情绪分类调用。
- 仅在提供商返回非空且经电话防回声门禁接受的语音识别文本时，将即时声学提示加入同一角色模型的本轮 turnContext。原始用户文本与存储的电话消息不拼接技术文字，不创建长期情绪或声纹记录。
- Scribe Realtime 复用 onFrame；本地 Whisper、Groq、MiniMax、Scribe v2 使用 STT 同一录音分块。Android 系统 SpeechRecognizer 不提供本应用 PCM，此版不在该线路假造分析结果。
- 声学量受录音增益/环境噪声影响，不代表主人的主观情绪、身份或真实语速。过短、静音或识别失败不产生额外角色发言。开关在通话语音设置，下一通电话生效。
- 真机收音、耳机/扬声器回声、低声与嘈杂环境需要 APK 验收。

## 2026-10-22：本地 Whisper Tiny / Base 离线识别

- 目标：替换设置页旧 Android `SpeechRecognizer` 普通通话输入，保留云端 Groq / MiniMax / ElevenLabs 作为显式手选方案。
- 源码：JNI 绑定、Kotlin 模型安装管理和 UI 为露露机独立实现。上游原生引擎通过 CMake `FetchContent` 构建固定 `ggml-org/whisper.cpp` 版本 `v1.9.4`，遵守其 MIT 许可；源代码与原始许可见 <https://github.com/ggml-org/whisper.cpp/tree/v1.9.4>。
- 模型：Tiny 和 Base 均为多语言 ggml 权重，按需从 <https://huggingface.co/ggerganov/whisper.cpp> 直接下载到应用私有目录；不内置模型权重；不强制用户购买云端转写。
- 路由：旧 `system` 设置迁移到 `local`；每通电话读取一次所选 Tiny/Base；本地缺失模型时提示安装，不回退收费 ASR。16 kHz PCM 沿用原 `CallAudioInput` 自然停顿分段，后续上下文、角色人格、ElevenLabs TTS 路线不变。
- 验收边界：需以 GitHub Actions 完整 APK 构建核对 CMake / NDK 链路，并在 Android ARM64 真机验证模型下载、中文转写、延迟、耗电、回声与打断；静态接线不代表以上测试已通过。

## 2026-10-26 数字生命研究清单的机制对照

- 本批为原 Kotlin 架构上的独立实现，未复制第三方代码、提示词、权重或资产，未新增运行依赖。
- 研究 SocioMind 默认分支 `135e266990df31cfbb207a76affa505cadc773a5`（Apache-2.0）的心理层级、短长期动机及事件后的主观延续；只采用同一人物状态的连续性思想，不采用强制情绪变化、额外量表生成调用和独立 Python 心理 Store。
- 参考 M2-her 官方多轮角色对话文档的语境与表达一致性；参考 Reconsider 陪伴记忆研究的“何时、怎样提起过去”问题，采用原调用内可选择的回忆使用指导，未复制论文程序或宣称实测达到其效果。
- 实际改动、逐项不采用的理由和来源见 `docs/architecture/DIGITAL_LIFE_RESEARCH_ADOPTION.md`。原始研究入口：https://github.com/AlanJiang98/DLP-SocioMind 、https://platform.minimax.io/docs/guides/text-chat 、https://arxiv.org/abs/2610.09470 。

# 代码与设计归属账本

## 2026-10-26：独立表达路由与Jev原生多方向参考

- 标签：ORIGINAL。`OptionalModelRolesSettings.kt`、`JevDirections.kt`、原网关/作用域选择、原ToolRouter与回执回流及测试，均依据主人“按职责选择模型、分别考虑多个方向”的要求独立实现，无新增第三方运行依赖。
- Jev只读取 https://docs.typesafe.ai 的官方models、system-one、noul、quickstart、errors与list-models接口事实；未复制SDK、厂商提示词或安装外部skill。没有采用厂商示例中的程序概率阈值/路由/情绪认定，最终判断仍由原角色模型承担。
- 新增世界、声音、记忆和评估候选仅列官方入口与工程取舍，未复制任何代码、权重、音色、训练集、论文图像或资产；后续实际迁入前须分别核验代码/模型/资产许可及设备表现。
- 清单、成本与边界见 `docs/architecture/DIGITAL_LIFE_EXPANDED_ROADMAP.md` 和 `JEV_DIRECTIONS_GUIDE.md`。本地HTTP夹具/Android测试不能冒充真实Jev质量、手机自然度或云GPU测量。

## 2026-10-26：数字生命清单第二批局部吸收

- 原 Kotlin 模块上的独立实现，未复制外部代码、论文提示词、模型或资产，未新增依赖与付费模型轮次。
- AI Town（MIT）`main` 快照 `8e05997f2409275669c8344b84a51692e83f3f33`，阅读 `ARCHITECTURE.md` 的世界输入校验、权威状态与结果记录职责；修正原 `DigitalWorldActivityStateStore` 失败动作过早结束现有活动的问题。来源：https://github.com/a16z-infra/ai-town/blob/8e05997f2409275669c8344b84a51692e83f3f33/ARCHITECTURE.md 。
- ProMemAgent 原论文第4节的情境线索检索与后续判断分工，仅用于改进原 `CharacterMotiveState` 工作集及共享人物上下文。原有 `waitingFor/nextStep` 仍是本人所写计划，命中只表示检索相关，不证明条件满足或授权行动；不移植其持续感知、嵌入生成或训练流程。来源：https://arxiv.org/html/2610.27400v1 。
- ReasoningBank（Apache-2.0）快照 `ed80611788292ea739f1effd31f16c53823b8a0d` 的 README 与 `WebArena/memory_management.py`，核对成功/失败经验与当前任务检索的分工。独立修复原 `CharacterLearningLedger` 条件检索遗漏与反复评分；未复制 Python 源码、任务评测、提示词、嵌入模型或模型推理文本。来源：https://github.com/google-research/reasoning-bank/tree/ed80611788292ea739f1effd31f16c53823b8a0d 。
- 实际边界、取舍与回归见 `docs/architecture/DIGITAL_LIFE_RESEARCH_ADOPTION.md` 第二批记录。

这份文件用于记录 `lulu1` 中每个重要模块的来源，避免把第三方代码误写成原创，也避免以后忘记某段实现从哪里来。

## 判断标签

| 标签 | 含义 | 处理方式 |
|---|---|---|
| `ORIGINAL` | 用户提出并由新项目独立设计、实现 | 可作为露露机原创模块维护 |
| `THIRD_PARTY` | 明确来自第三方项目或库 | 保留许可证、署名和来源链接 |
| `REWRITE` | 旧项目中存在类似功能，但新项目重新设计和实现 | 不复制旧实现，只参考需求与行为 |
| `UNKNOWN` | 暂时无法确认来源 | 不进入正式源码，先调查 |

## 当前已确认内容

| 内容 | 标签 | 归属与说明 |
|---|---|---|
| “露露机”产品名称与整体产品方向 | `ORIGINAL` | 由项目所有者提出 |
| 手机桌面式 AI 伴侣体验 | `ORIGINAL` | 由项目所有者持续设计和迭代 |
| 角色长期记忆、关系连续性、责任与承诺清单 | `ORIGINAL` | 由项目所有者提出的产品规则 |
| 学习系统、番茄钟与抽卡激励机制 | `ORIGINAL` | 由项目所有者提出并持续调整规则 |
| 主动来电、陪伴电话体验与角色主动联系规则 | `ORIGINAL` | 由项目所有者提出 |
| 旧仓库中的 RikkaHub / OrangeChat 基础聊天骨架 | `THIRD_PARTY` | 不直接搬入新项目；旧仓库 README 已明确其继承关系 |
| 旧仓库现有聊天、供应商、渲染及工具代码 | `UNKNOWN` / `THIRD_PARTY` | 需要逐文件确认来源；默认不复制 |
| 旧仓库中后加的露露机功能 | `REWRITE` | 先提取需求、数据模型和交互规则，再在新项目独立实现 |

## 考研模块迁移基准

- 需求与行为基准仓库：`jiacimu-droid/lulu`
- 基准分支：`master`
- 本次核对的最新代码快照：`727d740985db5ff97f4936f51aee9725d11d645a`
- 抽卡规则实现对应文件：`StudyRules.kt`，核对时 blob 为 `cb5b3c017f325ba57897ba8f89e8f9d6f185a3a2`
- 番茄钟界面与配色对应文件：`StudyPage.kt`，核对时 blob 为 `c4764e510f44f8cd11a5b5eea882e781e2f6ccd6`
- 迁移标签：`REWRITE`
- 迁移方式：只参考最新行为与数据规则，在 Lulu1 中独立实现；不复制旧页面布局。

### 当前抽卡规则

- 蓝色画卷专属碎片：92.15%
- 紫色：6%，其中抖音时长券 5%、剧场碎片 1%
- 金色：1.5%，其中游戏畅玩券 1.2%、视频解锁卡 0.3%
- 彩色番剧兑换券：0.35%
- 连续 30 抽没有紫色、金色或彩色结果时，第 30 抽直接产生紫色结果。
- 每套画卷需要 10 枚自己的专属碎片；已满后仍展示抽中结果，但不重复增加。

### 明确排除的旧逻辑

以下内容不得重新引入 Lulu1 的考研模块：

- 独立“学习币”货币体系；`kudos` 在产品界面中统一称为夸夸值。
- 盲盒及按 25 分钟发放盲盒的奖励。
- 月光池、经典池或其他并行卡池。
- 单独的保底抽入口或保底抽券；保底直接体现在普通抽卡结果中。
- 万能普通、万能稀有、万能史诗等通用碎片。
- 已经废弃的旧奖励兼容页面。

### 当前番茄钟配色

仅保留源仓库最新版本中的两套配色：

- `CLOUD`：云雾原版
- `MIDNIGHT`：深夜墨蓝

颜色值以该快照的 `focusPalette` 为准，不能凭印象重新配色。

## 新文件进入仓库前的检查

任何从旧仓库迁入或参考后实现的文件，都要回答以下问题：

1. 这个功能最初是谁提出的？
2. 新文件是否复制了旧源码或只参考行为？
3. 是否包含第三方代码、图片、字体、声音或模型资源？
4. 对应许可证是否允许当前使用方式？
5. 是否需要保留原作者版权声明？
6. 本文件中是否已经登记？
7. 读取的是源仓库最新版本，还是已经废弃的历史实现？

## 迁移记录模板

```text
模块：
目标路径：
标签：ORIGINAL / THIRD_PARTY / REWRITE / UNKNOWN
需求来源：
代码来源：
第三方许可证：
迁移方式：直接使用 / 修改使用 / 独立重写
验证人：
日期：
备注：
```

## 原则

- 不因为“改了很多”就自动取得原代码的完整归属。
- 产品需求、交互规则和用户自己的内容，可以作为新实现的依据。
- 第三方代码能否迁入，由对应许可证决定，不由 README 的措辞决定。
- 无法确认来源时，宁可重新实现，也不模糊处理。
- 每次迁移前先读取源仓库当前默认分支的最新提交；历史代码只能作为历史资料，不得覆盖当前产品规则。

## 2026-10-26：数字生命第三批 · 视觉证据时序

- 目标路径：`Live2DCallVisionState.kt`、`Live2DFullscreenVideoCall.kt`、`LuluScreenAwareness.kt`及相应测试；标签：ORIGINAL。
- 需求来源：主人要求按二十项研究清单筛选适合本仓的原则，改善真实感知、陪伴与行动依据。
- 参考：ProV-Mem论文 https://arxiv.org/abs/2610.23998 （CC BY-NC-ND 4.0）。只借鉴视觉证据时序与职责分开的一般思想，独立修复本仓采集时间、授权代际与执行后权限检查；未复制第三方代码、提示词、文字段落、图像、数据或模型资源，无新增第三方依赖。
- 未迁入论文触发策略、生成先验、相似性筛选、额外模型请求或论文效果结论。模型比较文档采用Google/MiniMax官方公开接口事实，结论明确为待实测的选型推断，不修改用户模型。

## 2026-10-17：小红书自主搜索与哄睡气声

- 目标路径：`XhsReadingEngine.kt`、`xhs-reader.js`、`LuluExternalMcp.kt`、自主学习衔接及 `VoicePerformance.kt`。
- 标签：REWRITE；依据本仓现有实现及用户明确需求独立修订，未复制外部实现或引入第三方依赖。
- 研究参考：xpzouying/xiaohongshu-mcp 当前 `xiaohongshu/search.go` 的真实网页搜索/状态读取；imoonkey/openweb 的 `src/sites/xiaohongshu/DOC.md` 搜索→详情流程；Android `WebView.getSettings()` 的 UI 线程校验；ElevenLabs 官方音频标签文档的 `[whispers]`。
- 链接：https://github.com/xpzouying/xiaohongshu-mcp/blob/main/xiaohongshu/search.go 、https://github.com/imoonkey/openweb/blob/main/src/sites/xiaohongshu/DOC.md 、https://github.com/aosp-mirror/platform_frameworks_base/blob/main/core/java/android/webkit/WebView.java 、https://elevenlabs.io/docs/help-center/product/core-capabilities/text-to-speech/how-do-audio-tags-work-with-eleven-v3-and-v4 。
- 验证边界：网页夹具、线程约束的 Android 工具整链路回归和 APK 构建分别验证；不以模拟页面通过冒充用户手机登录会话的实网验证，也不承诺操作系统后台持续渲染网页。

## 2026-10-30：合作游戏沉浸场景与营地原画

- 标签：ORIGINAL。全屏游戏场景、伙伴自主选择与回执适配，以及小岛蘑菇、蝴蝶、萤火和云影均在本仓独立实现，没有复制外部游戏代码或素材。
- `app/src/main/res/drawable-nodpi/game_relic_camp.webp`：使用内置 ImageGen 生成的原创遗迹营地插画，经本地 WebP 压缩供 APK 离线使用；没有新增图片服务或运行时联网下载。不把静态插画当作可行走地图，活动对局仍按真实 RelicRun 绘制。
- 生成提示：production background illustration for a cozy cooperative Android exploration game; ancient ruins expedition camp; polished atmospheric 16:9 landscape; warm campfire, canvas tent, traveling supplies, ancient mossy stone arch, faint turquoise runes, winding path and blue mist, moonlit mountains, pine silhouettes, ferns and fireflies; painterly premium indie adventure art; deep teal/slate and amber firelight; no people, text, logos or watermark.
- 伙伴沿用已有游戏模型、本人上下文及本人音色配置。云语音只在游戏语音开启且现有总开关、个人开关和音量允许时合成，按现有服务计费；后台、关闭语音和离开场景会停止排队播放。
