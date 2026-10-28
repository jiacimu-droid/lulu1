# 统一人物连续性实施清单（2026-10-28 主分支核对版）

基线：`main@5407206b57aabbed80ceffd919dd45933aba096f`；核对 AGENTS.md、UNIFIED_CHARACTER_CONTRACT.md、COMPANION_GROWTH_FRAMEWORK.md、CHARACTER_LOOP_AUDIT.md 及相关正式 Kotlin 源码与测试。本文是**未完成工作、验证要求和执行顺序**，不是已通过 Android 真机的声明。后续每轮先读取最新 main 的提交和源码，更新完成情况；不把这份快照当永恒事实。

## 当前已具备，禁止重建

- 原始事件：`SharedExperienceTimeline` 和事件版本；`PerceptionStimulusLedger` 已用精确 (character,event,revision) SQLite 确认；`CharacterPerceptionContext.pending` 已分页读取未处理证据，不能再称其仍受最近160条/24小时候选的永久限制。要验证多日、杀进程和大量回执的真实续接，尤其有部分输入成功、部分失败的情形。
- 原工具后回执、`ToolRouter` 幂等及结果再感知已有；绝不能为每个结果创建另一个人格/记忆/独立循环。
- 原住民 `ResidentDailyDecision` 已保存有效日决策/到点决定，按同一 requestId 做有限恢复；尚未等于所有失败均可重新决策。网络请求未知时目前锁定以避免重复计费。
- `MemoryPatternNotes` 已排除未来事件、校验活跃和来源 revision、对单源提取去重、区分日数及部分量纲；本轮继续修正钟点单位和复合来源频率计数。统计仍只是提示事实，不是自动判定习惯。
- 主动注意、持续动机和好奇分别已有 `CharacterAssociativeRecall` / `CharacterUnfinishedActionContext` / `CharacterExplorationContinuity`；以打通输入与后续行动为主，不另造意愿库。
- 近几轮 Live2D 已处理触摸保有、部分低帧率物理及语音动作时间同步（最后源码提交 `5407206b`）；完整通话体验仍需真机验证。

## 下阶段工作包（按未闭环和故障风险选，不得重复做已合并功能）

### P0-A：居民状态机、等待复议与不确定请求

调查 `ResidentDailyDecision.runOnce/runDuePlan` 的请求前 claim、`requesting/uncertain/invalid_output/decided/needs_recovery/waiting/finished`，对照 `RoleDayScheduleStore`、`ToolRouter` 及调度唤醒：保存有效决定后崩溃必须沿同一 ID 恢复；请求确定失败可在有界预算下重新模型决策，未知是否已经收费的请求不得盲目重发。无效结构化选择按现有角色决策契约做有界修补，不直接当作取消或放弃。等待需持久保存条件/下一次复议机会；确认同一到期计划不会永远停在 waiting，也不会重复付费/执行。先核对有无既有模块可复用，不创造第二个调度中心。

**验收**：超时前、响应后未保存、保存后未执行、单个副动作执行后崩溃、撤回、改期、等待与跨重启；核验请求次数、日程状态、时间线和发动态数量，禁止只做静态测试。

### P0-B：端到端因果诊断（复用现有 ID 与事件详情）

对齐原事件/模型选择/自主 `steps`/请求 ID/每个回执/后续感知的已有字段。扩展现有事件详情或记录页的只读投影，而不是新入口/第二套状态中心；区分“未选择”“选择等待”“权限不足”“尚待同意”“工具执行失败”“结果尚未被本人处理”“已处理但目标未达成”。只展示有证据的已知状态，不能伪造内部推理。

**验收**：“想发表情包却没发”“搜到了却没有分享”应能按同一事件或动机 ID 查到实际断点。历史记录缺字段时显示未知，不推断人物意愿。

### P1：跨场景状态版本、输入去重与自然表达

从在线私聊、前台、电话、后台感知、群聊、居民入口抽取共享的提交与回执交接（避免一次性重写入口）。迟到模型回包不得覆盖较新事件 revision、同一目标或关系认识。审核重复注入、旧目标与当前聊天的候选预算，确保相同人的一次纠正能在电话/自主生活里继续存在。语气由人物设定决定，日常短聊不是强制字数、口癖或气泡配额。

**验收**：长工具等待时主人改口、电话后继续私聊、重复读同一来源、跨角色隔离、原事件撤回，以及相同模型/人物下自然度前后 A/B。

### P2：独立兴趣、社交和世界占用

继续利用已有 motives/curiosities/日程/手机消息/动态：研究问题→真实阅读或探索→独立回执→本人新见解或主动放下；同时区分邀请、对方已读、接受/拒绝、改期、真实到场。避免发送者代替接收者选择，也避免一个人同时占用互斥地点或活动。真实世界状态、授权、个人视角优先于叙事。

**验收**：好友拒绝邀请、改期撞上工作、多人群聊意见不同、独立兴趣跨数日衔接，均有双方独立的可溯源事件。

### P3：Live2D 混合参数、音频打断与真机体验

沿现有渲染/动作机制收敛口型、表情、头部、触摸、motion 参数所有权与优先级；设备音频播放停止、打断、继续、低 FPS 真实时间应控制动作过渡。不要伪造模型不存在的参数，不追加每帧 API。

**验收**：长句停顿、频繁打断、摸脸同时说话、扇打恢复、30/15 FPS 掉帧、设备发热及音画差距，分别报告单测与真机结果。

### P4：数据增长与记忆证据检验

在原时间线、记忆召回、撤回及修订关系上测量一年至五年数据规模，确认查询索引/分页/内存峰值；不自动删用户历史。新增记录统计需区分单一原事件、复合提取、不同发生日、时区、测量单位、未来和失效 revision。

**验收**：重复摘要、跨午夜、未来/撤回来源、不同单位，以及模拟多年数据的启动、召回、写入与重启迁移。

## 每小时轮次的交付契约

每轮必须：①核对最新 main 和上次真实完成 SHA、读 AGENTS 与代码索引；②选择一个未完成架构闭环，明确复现与根因；③修改**真实代码并提交 main**（无需新分支/PR/Issue）及根因级自动测试；④代码变更运行原有索引生成，等待自动索引提交结果；⑤核验 Android CI/构建（运行中不得报成功）并给出真机验收动作；⑥更新本清单的完成/待做状态。不得只“看了一下 APK”“写一份计划”，也不得以未验证的任务宣称完成。若不可修改代码或环境阻断，明确报告阻断原因，下一轮优先继续原闭环，避免重复开发。

统一边界：事实时间线、人物/记忆/动机唯一权威；模型判断语义和意愿，程序只核验事实、权限、版本、时序、幂等和可恢复性。不增加常态付费模型轮次，不用定时强迫发朋友圈、搜索或说话。

## 2026-10-28 P0-A：居民嵌套日程与独立步骤的结构补正（源码/回归已提交，CI 与真机另验）

- **根因（真实源码）**：原 `dayContractError` 只验证 lunch/evening 和 agenda/steps 顶层数组类型；`saveAgenda` 对 agenda 内的无效时间、缺 activityArgs、缺文本、缺目标或不完整家具参数直接 `continue`，甚至对超过8项直接截断。模型可能明确安排了活动，系统却保存「finished」且没有日程或失败回执；`dueContractError` 也未检查 steps 内 args 类型和依赖字段，`AutonomousActionWireProtocol.normalizeStep` 会将非对象 args 默认为 `{}`，造成决定与执行参数不一致。
- **改动**：在现有 `ResidentDailyDecision` 的每日/到点 **同一首次结构验证入口** 增加共享嵌套 steps schema 校验和 agenda 行校验，明确报告哪一行的时间、action、参数对象、目标/正文或必需字段不合法。仅新模型回复走已有**最多一次本人补正**，补正前不产生任何日程/工具副作用；补正后保存真实选择，再沿原 `RoleDayScheduleStore` / `OrderedCharacterActions` / `ToolRouter` 幂等回执执行。提示词明示最多8项与必要字段；旧版已保存决定不被重新验证、不重复购买请求。世界权限/对象实际存在性仍由执行器决定，不让程序替人物猜意愿。
- **测试**：`ResidentDailyContinuityTest` 新增日程嵌套参数错误→MockWebServer 同角色补正→保存一项未执行日程、到点附加动作 args 错误→补正→独立动态恰好一次、结构纯验证（数组上限/依赖布尔/正确格式、缺正文/收件人），以及**连续两次嵌套无效**不安排日程、不第三次收费。既有超时未知不重买、改期版本防护、恢复和撤回回归仍须通过完整 Android CI。
- **提交**：基础源码 `674e5ad23f82c7438df354f9c910e411d733491d`，测试 `968f24b923fff5eac0c673a32b3656accc52909f`；二次无效回归 `2cd27b7f`，附加步骤必需参数加固 `c7b0d61e`，字段测试 `bd0fba36`。本段记录不等于 CI、APK 或真机通过。索引由仓库既有 Refresh code index 工作流生成，需核对最终 HEAD 与结果。
- **仍未完成**：无效结构补正中断的显式恢复入口、结果未知的人工判断、wait 条件变化提前复议、合法但因真实权限/资源变化被拒绝的日程候选的独立可见回执、跨日未完旧决定的处理；本轮未新增付费常态模型调用。

## 2026-10-28 续修记录：居民等待复议（开发待 CI 验证）

- `ResidentDailyDecision` 的 `wait` 以前只写入 `due-phase=waiting`，但 `hasSavedDueToResume` 只接受 decided/needs_recovery；到期 Worker 因此永久停止，没有新复议机会。现保留原计划 ID，按本人明确 `reviewAt` 或旧选择缺省时既有下个每日规划时段，通过 `RoleDayScheduleStore.reschedule` 安排下一次**重新决策**；原动作不自动执行。条件和原理由写入同一计划结果摘要，下一轮模型可看见，原 steps 仍由既有幂等 ID 约束。
- 原 `RoleDayScheduleStore.reschedule` 用 WorkManager `REPLACE` 可能取消正在执行自身的到期 Worker；居民 `wait` 的复议排期改走 `APPEND_OR_REPLACE`，让当前 worker 结束后再唤醒新排期，其他普通改期仍保留原有 `REPLACE` 语义。
- 新增复议时间/旧选择兼容、真正再决策、独立动作不重复的 Robolectric + MockWebServer 回归。**新代码尚需本次 CI / APK / 真机验证**。P0-A 的网络未知结果、无效结构补正、等待条件变化主动提前复议仍未全面完成，下一轮继续。

- 后续加固（第二次提交）：等待的本轮独立副动作先按稳定 requestId 执行，再移动原日程到复议时间；在副动作后、排期前被杀进程时，旧计划仍可用已保存决定恢复副动作回执。复议排期增加旧 `dueAt` 版本比较，避免等待模型回包覆盖用户/其他入口刚改过的日程。新增迟到旧决定与新改期冲突的回归。

## 2026-10-28 续修记录：居民结构化决定的有界补正（待 CI / 真机验收）

- 根因：每日规划 `runOnce` 对可解析但缺少合法地点的 JSON 会自行默认安排；到点 `runDuePlan` 把不合法改期时间保存为“已决定”，随后反复恢复同一无效决定而不能让本人修正。网络请求是否已收费不确定时仍禁止盲目重发。
- 新的**首轮收到响应且确有结构违例**场景：在原居民决策 prefs 中先持久保存原始模型文本、具体错误与 `repair_requesting`，再最多向同一角色、同一已选模型发起一次有界补正。补正成功后先持久保存本人真实选择，再进入原 `RoleDayScheduleStore` / `ToolRouter` / `OrderedCharacterActions`；不替角色选择取消、默认时间或社交动作。正常有效决定不增加调用。
- 若首轮网络结果未知、补正请求中断/失败、补正仍不合约，保留 `uncertain` 或 `invalid_output` 和原始材料，不在重启后重复购买；旧版本已保存的选择沿原 requestId 回放，不因新增校验再次收费。保留各角色原有权限、独立 steps 回执与幂等。
- 补充到点无效→本人修正→独立动态恰好一次、连续两次无效不执行/不第三次请求、每日规划无效地点→本人修正的 MockWebServer 回归。另修复该测试类长期注册居民达到世界 12 人上限造成的 CI 测试污染：Robolectric 每个用例只隔离测试居民列表并在结束后恢复原快照，不放宽正式世界上限。
- **验证结果（2026-10-28）**：功能提交 `4e963d701e1f37a5f05b8f61e557b5f72fcd1d11`，GitHub Actions [Lulu APK #40629868825](https://github.com/jiacimu-droid/lulu1/actions/runs/40629868825) 完整成功（Live2D 打包、Android JVM 单测、phone APK、字节码检查、正式发布）；[代码索引 #40629868798](https://github.com/jiacimu-droid/lulu1/actions/runs/40629868798) 成功并生成索引提交 `51a2fda552655e359bb0ed04813893b26938ac90`。已发布 [Lulu v6079 ARM64](https://github.com/jiacimu-droid/lulu1/releases/download/lulu-v6079/Lulu-arm64.apk)，**真机未验收**。此前 `225c4d1` 的 CI #40628478408 因 Robolectric 测试类居民上限污染失败，已在本提交修复并由本轮完整 CI 通过验证。
- **仍未完成**：网络确定失败与结果未知的细分/可恢复预算、补正中断后的显式人工恢复入口、主动作已改期/取消/完成后副动作中断的独立恢复，以及等待条件变化提前复议。需后续专门测试。

## 2026-10-28 续修记录：日程改期/撤销与独立动作不中断（待 CI）

- 根因：`runDuePlan` 原先先对 `choice=reschedule|cancel` 改写唯一日程，再执行本轮附加动作；改期调用 WorkManager `REPLACE`、取消调用 `cancelUniqueWork`，会取消正在执行的居民唤醒 Worker。若中间退出，副动作还没来得及执行但原计划已经不是 due，原 `hasSavedDueToResume` 无从唤醒。此前 wait 分支已修同类问题。
- 当前对原计划未执行的 `reschedule/cancel/wait` 先用原 `OrderedCharacterActions` + `ToolRouter` 执行附加步骤，原计划依赖步骤不能错误视为成功；其后才改期或撤销。改期沿同一 WorkManager 唤醒链 `APPEND_OR_REPLACE` 且验证原 dueAt 版本；取消保留正在处理的同一个唤醒 Worker 到回执落盘，普通用户撤销仍取消待办。execute 继续先判定主动作成功、再处理依赖步骤。
- 增加居民模型改期与撤销时独立动态确实执行、依赖主动作的副步不误执行、计划状态一致、单次模型请求和无重复动态的端到端 Robolectric 回归。本轮提交后还需要 CI / 真机验收，不能拿静态检查冒充真机结果。


## 2026-10-28 续修记录：模型选择语句位置的 Live2D 动作（源码接线、CI/真机单独验收）

- 根因：已有 `Live2DConversationPerformance` 可以在同一角色模型回复里选择情绪/动作，但默认 0/240/680ms 的整句节拍无法对应第二、第三个分句；各 TTS 提供商不提供统一的逐字时间戳，不能按文本长度臆造播放绝对时刻。
- 在原有 `live2d.beats` 中增加可选的原文 `anchor` 和每拍 `performance`（feeling/movement/intensity/side）；由模型在一次已有决策里选择具体分句和动作，继续经过真实模型骨骼、表情文件、权限和当前通话验证。未指定锚点的旧 `performance` 与绝对 `atMs` 兼容，不从台词关键词硬猜动作。
- `Live2DSpeechAnchorPlan` 只接受明确、唯一且位于真实分句边界的锚点；模型主动选择时最多增加 3 个 TTS 分段，无明确锚点不增加合成请求。分段在 `LuluCallSpeechQueue` 真正开始发声时带 `segmentIndex`、`visualSequence`，由 `Live2DSpeechTimeline` → `Live2DCallStage` → 现有 Pixi `performanceRuntime` 按段启动；口型依旧独立使用原 PCM，没发声就不能表演。停顿是跨语音片段的真实边界，不冒充精确字级时间戳。
- 语音请求/视觉版本不一致、角色或通话变更、旧 TTS 迟到结束、打断、隐藏及重载 Live2D 模型时不得补播旧节拍。新增 Robolectric/JVM 与 stage JS 对锚点、无效/重复锚点、段数量上限、旧版兼容、动作资源及版本校验、不同分句实际开始、打断恢复的回归。
- 相关源码提交：`519fdc4`、`2040f61`、`21c01f6`、`5c84f66`、`d5a9a08`、`653ec3d`、`392d6e3`，后续完善与测试至 `8c9828e`。旧 JS 静态断言曾在 CI #40634015926 失败，已在 `359ff3c` 更新为分段调度断言。**不能把旧失败/取消运行当通过；以本轮最终 main 的 CI 为准。**
- **仍需真机验证**：多句对话中动作是否自然、语音服务分段的顿挫、动态加载时的第一句、15FPS/30FPS 掉帧、打断后的面部释放、触摸和语音并发、TTS 提供商真实首音延迟及耗费。普通文字聊天没有 Live2D 画面时不会凭空播放动作。无提供商词级时间戳，因此暂不承诺句内任意字词毫秒级对齐。

## 2026-10-28 续修记录：居民已完成主动作后的独立步骤恢复（待 CI / 真机）

- 根因：居民到点选择 execute 时，原计划主动作的成功回执先将 RoleDayPlan 标记 completed 并取消同名 WorkManager；若副步骤尚未执行，当前 Worker 可能被自身取消，重启后的 restorePending 和 runDuePlan 又只接受 planned/due，导致已保存的模型决定与依赖步骤永远断链。
- 本轮在原 RoleDayScheduleStore/ResidentDailyDecision 上恢复：有同一原 dueAt、已保存 execute 选择和真实完成 evidenceId 的单次居民计划允许仅续接独立步骤；跳过已完成主动作的再次调用，复用 OrderedCharacterActions 原步骤 requestId 和 ToolRouter 幂等回执。主动作落盘时保留正在运行的居民 Worker；若进程中断，原 restorePending 可安排同一已保存决定的有限恢复。撤销、改期、重复日程和没有真实完成证据的 completed 状态不借此重新授权。原有两次恢复预算仍生效。
- 同时修复 runOnce/runDuePlan 在读取已保存选择之前强制解析模型连接的问题：已有决定的离线续接不再依赖旧模型存档，只有新的付费决策需要连接；不额外发起 LLM 请求。
- 增加真实 ToolRouter 主动态完成→日程 completed→重启恢复附加动态恰好一次、模型存档清除仍可恢复的 Robolectric 回归。**本段为源码变更记录，CI、APK、真机状态以随后工作流结果为准。**

- **本轮完整构建验收（2026-10-28）**：单测写法纠正提交 `c7768e20e46b5d4ca90dd794df7e346a827346e0`；同期居民副动作修复提交 `3675eee68e5d17939fdabdeb6e6841fd87dc377d` 后的合并主分支 CI [#40634865886](https://github.com/jiacimu-droid/lulu1/actions/runs/40634865886) **completed/success**：Live2D 语音动作 JS 测试、Android JVM `testDebugUnitTest`、`assemblePhone` 和发布均通过；[Lulu v6106 ARM64](https://github.com/jiacimu-droid/lulu1/releases/download/lulu-v6106/Lulu-arm64.apk) 真实已发布（28,326,592 字节）。被新 HEAD 取消的 #40634647057 不作为成功证据；**真实 Android 视频多句动作与 PCM 口型仍待手动真机验收**。

## 2026-10-28 增量：原始证据驱动的叙事关联（P4 记忆证据链）

- 本轮针对记忆召回原先只找**同 session + 同频道 + 900 秒**的邻接：多日任务发起→实际动作→失败回执可能断成零散片段。新增只读 `NarrativeEvidenceChain`，在原 `MemoryEvidenceSelection.expand` 找到经当前有效性/权限检查的同角色、同 taskId 证据；有 sessionId 但无共同任务 ID 的仅限真实近邻，跨频道也可关联。不凭关键词判断两个事件属于同一事件，不额外向模型购买摘要，不创独立持久剧情库。
- 接通 `UnifiedMemoryOrchestrator` 的 compact 原始事件渲染、full `MemoryWorkingSet`、自主 `CharacterAssociativeRecall`，所有入口共享 task/session/time 的证据顺序标签，并显式区分 UserStatement/CharacterStatement/Observation/ToolResult。召回候选满额时预留少量后续事实位置、空余返还原始候选；多年原始历史原封不动。自主回忆只在已有 160 条有权限的旧经历里补足链接，不额外扫描永久历史。
- 编辑旧 revision、删除/撤回、角色隔离、未来证据、同会话跨日误连及无关任务均被真实有效候选过滤。新增 `MemoryEvidenceSelectionTest`、`CharacterAssociativeRecallTest` 和 `NarrativeMemoryRecallIntegrationTest`（真实 SQLite→compact/full→删除回执不再进入），不将模型说出口的承诺当成成功证据。
- 实现主提交 `3c6516d12b835ddd65f3fd8edb2da1af487a1461`、回归 `d3d9159cb754c5df1190802e8bde875a9c7f7ee3`、自主联动 `8e53c02c643061551bfceef8745a43916ce0e66c`、文本规范 `321ec144973c42aacffafb09e961bcece92678fe`。**本段仅是源码事实，CI / APK / 真机结果尚待单独核实。**
- 后续待办：没有共享 ID 的跨场景事件需要追查源 ID 传递；主观叙事学习不自动填入事实层；多年数据量应另做索引/性能测量，尤其 `SharedExperienceTimeline.all` 的长期检索。当前改动不能宣称所有事件都自动连成因果，也不能证明模型下一次一定自主执行。

## 2026-10-28：星禾闺蜜私聊：同音歧义与过度演绎（已提交待真机）

- 截图症状：用户用同音字表达近似「闺蜜」的关系玩笑，角色先按小说作品词汇解释，再把最新「不是呀」的澄清演成恋爱式被撩、幻想能力和数码秘密提案。**截图仅证明生成结果，并未取得实际模型原始请求日志，不能无证宣称是某模型参数或 UI 转写错误**。正式源码确认星禾的稳定身份、亲密边界原本就设为最好的平等闺蜜、非恋人；故不是「少写了闺蜜身份」。
- 可复现的源码断点：`CharacterLifeStore.migrateLegacyLuluToXingHe` 一旦存储 `xingHeProfileVersion=2` 就直接 return，仓库中即使更新星禾的口语/理解方式，**现有设备不会收到改动**。这轮给星禾原 v2 默认字段独立留存快照，实现 v2→v3（含 v1 直接连续升级）**逐字段原文精确匹配后才更新**；用户手动修改过的人设、关系约定、理解方式、语气，连空字符串也不覆盖。升级前在原 `CharacterLifeStore` 保存可恢复的版本快照；不动历史聊天、别的人物/江渡、已确认友情与旧消息。
- 星禾自己的 v3 语气/理解方式强调：先从最新原话、两人已确认的闺蜜关系、上一轮被纠正的解释判断同音词是称呼、玩笑还是作品；拿不准可以简短确认。不要从一个词立刻跳成角色扮演/恋爱告白/数字世界旁白，不用过量颜文字、拟声、问句和活动清单表达热情。这是她本人更新，不写进全角色公共模板，也不通过程序把某字词强制改写为「闺蜜」；实际语义仍由她选定的聊天模型判断。
- 修改：`CharacterProfileSchema.kt`、`CharacterLifeStore.kt`；回归 `BundledJiangDuImportTest.kt` 覆盖老存档升级、v1 连续升级、自定义字段/空字段不覆盖、别的角色不被修改；`ConversationExpressionGatewayTest.kt` 用 MockWebServer 检查真实私聊 planner 与简化 expression 两条 API 输入都能拿到该角色亲密关系/语气，别的角色不能看到星禾私有风格。主分支提交 `ae29e780`、`169dfd4f`、`68c780c4`、`6f3789e0`、`57797133`。
- **验收层级**：代码提交/代码索引成功不是 Android 真机的可用结论。CI、APK 和自然对话效果需分别核验；测试验证提示词与版本迁移，不等于能保证任意语言模型每次都消解所有谐音。真实中文语义仍然由同一角色模型自主决定。

## 2026-10-28：居民模型请求确定拒绝与未知结果分流（源码待 CI / 真机）

- 根因：每日 `claim` 与到点 `due-phase=requesting` 先占位后，所有无回复的模型错误都进入 `uncertain`；HTTP 401/413 等明确拒绝生成的请求，与超时、502/503 等可能已经产生费用的请求混为一谈。即使用户修复了模型配置，居民也永远无法重新作出当日/到点决定；到点的 Boolean claim 原先还存在多 Worker 同时读写竞争。
- 本轮仅把网关明确返回的 400/401/402/403/404/413/422 归为 `definite_rejected`，记录原有决策 prefs 内的阶段与**连接摘要（不存 API Key）**；同一连接不重发，用户实际换了模型连接后最多允许再试一次，且通过原 `ResidentDailyDecision` 请求/日程唤醒链。超时、429、5xx、结构补正中断继续锁定为结果未知，不能自动重买。模型仍决定语义，`ToolRouter` 仍拥有动作权限、幂等和独立回执。
- 修复到点请求的原 claim/phase 非原子竞争；每日请求同样用同步原子占位。已有保存决定只续接原请求 ID，不因更换模型重新决策；`RoleDayScheduleStore.restorePending` 可在重启时恢复**确定拒绝且配置已变**的到期居民计划，日计划沿原 catch-up 检查恢复。没有引入周期性额外付费模型调用。
- 回归增加 MockWebServer HTTP 401 到点拒绝→同配置不重试→更换连接后本人取消原活动并独立发动态恰好一次；HTTP 413 每日规划恢复；HTTP 502 结果未知即使更换配置也不重发。**仅为源码与测试提交，CI/APK/真机需要独立核实**。
- 仍未完成：同一连接配置内凭证变更后的即时唤醒（需应用恢复调度）、无效补正的显式人工恢复、wait 条件变化提前复议，以及跨日旧请求的可见处理；不能把本轮归为完整 P0-A。

## 2026-10-28 续修：星禾的日常说话**语气**与颜文字范例注入（v4，待真机）

- 用户指出重点不仅是「闺蜜／诡秘」同音误判，而是截图里持续的拟声「呜哇」、华丽/可爱颜文字、被撩暧昧和抽象数字秘密，整体不像平等熟悉的闺蜜。最新源码确定另一可复现根因：`CharacterExpressionPreferences.selected(..., "kaomoji")` 对所有尚未显式设置的角色默认 `expressive`，模型提示为「偏多，主动考虑」，与此同时 `CharacterExpressionContinuity.guide()` 在每次文字私聊决策中直接灌入十几种颜文字的完整范例列表；被当成语言模仿素材后，即使写「非强制」也容易强化表演式卖萌。旧 `CharacterLifeStore` v3 标记还令修改默认预设无法自动落到已有用户数据。
- **已改源码**：星禾个人角色 `lulu` 的 v3→v4 一次迁移只在 `expressionPreferences.kaomoji` 缺失时设置 `natural`，已有个人选择「多用／少用」均原样保留；迁移先备份旧 expressionPreferences、保留其他字段，v1/v2 历史版本和首次转换同步到 v4。其他角色仍完全使用自己原有的偏好与历史。角色仍可主动用语气、表情、颜文字，不在输出后做关键词屏蔽，也不硬限制字数/气泡。
- `CharacterExpressionContinuity.guide(characterId)` 的长颜文字范例只在**该人自己选了偏多**时展示；普通/少用模式只有表达方式的中性说明，避免每次前台决策/后台自主决策都拿到“萌系台词范本”。两个已有入口 `LuluDeviceToolBridge` 与 `ProactivePerceptionRuntime` 已传本人 ID，网关现有 `CharacterSpeechIdentity` 仍保证同一人设与关系。没有新增 API、第二套记忆、人格中心或消息后自动改写。
- 回归增补 `BundledJiangDuImportTest` v3→v4、用户手动选「多用／少用」保护、重启后幂等，以及 `CharacterExpressionContinuityTest` 对同角色/其他角色的范例门控。单测、APK、模拟用户发「诡秘」/澄清、角色自己是不是自然表达，都须以实际 CI/真机结果区分。
- 新增源码提交：`9d2d17ce`、`9276baf8`、`a46ca8e1`、`ec980268`、`737ba71a`、`efcf1c3a`。

## 2026-10-28 追加：星禾说话站位错成「主人／讨好／主动哄睡」

- 用户进一步明确：问题不是只嫌颜文字、同音词或二次元梗，而是**当前星禾以取悦主人/恋人式照顾者身份说话**。她是独立、有主见的平等闺蜜；用户没有要求她哄睡/照顾，她不能把别的角色的习惯或主仆互动当成自己的默认任务。
- 查到额外**共享提示词串线**：`CharacterRuntime.personalityRuntimeContext` 将对所有角色提供的用户习惯标作「主人的习惯」、`CharacterDecisionProtocol.recordOwnership` 将中性日程事实写作「对主人实际承担」。虽同一网关已有「不得默认主人」说明，实际冲突文本会给平等友情提供错误行为先验。现在只把这些共享的**事实归属用词**修为中性「用户／对方」，不修改江渡等角色的自定义主人/恋爱关系。
- 新增 `PeerRelationshipContext`：从原 `CharacterSocialRelationship.agreement(characterId, "user")` 的**当前可编辑**「闺蜜」约定投射平等朋友事实、情绪/独立自主边界、不能无请求把普通调侃解释为哄睡/恋爱服务；朋友如确实提出邀请或有真实特定约定，角色仍可自主选择是否答应，不用程序一律禁止。通过既有 `CompanionModelGateway` 的 Full 与 CharacterAndScenario 真实通道投送到角色决策、简化表达、电话与自主动作，无新增 API 调用和第二存储。其他角色/其他当前关系约定直接返回空，不用万能「闺蜜模板」，用户编辑关系后立即变化。
- 增加 `PeerRelationshipContextTest` 检验关系事实、修改时的即时失效与其他角色隔离，并扩充 `ConversationExpressionGatewayTest` 的 MockWebServer 检验两阶段真实模型请求都收到独立友情边界。此前同轮的星禾风格 v3/v4 安全迁移、颜文字范例门控仍然有效；用户的实际消息、收藏、原始记忆及江渡关系不变。
- 源码提交 `dfd98d78`、`0b153729`、`f83f9f05`、`f33d70ee`、`06342f23`、`f89e21a9`。仍须依据最终 main CI/Android APK 状态及实际星禾多轮聊天验收；模型语义生成不是确定性程序规则，源码测试通过也不等于能保证每一条语气完美。

## 2026-10-28：每个角色单独形成对用户的认知（真实跨角色信息隔离）

- 新的明确需求：星禾从与用户相处中形成某项偏好，并不代表江渡及其他角色知道它。不能把「同一个应用账户」误读为「所有角色共享同一个大脑」，也不能把「共享事实入口」和「各人的个性、主观判断和记忆」混为一谈。
- 核对正式代码：`SharedExperienceTimeline.all(characterId)` / `eventsByIds(characterId,...)` 已按角色 ID 限制；`CharacterDevelopmentStore.active(characterId)` 仅取该角色成长记录并验证此角色自己的来源版本；`CharacterRuntime.personalityRuntimeContext(characterId)` 与 `CharacterAssociativeRecall` 已是本人视角。群聊真实在场成员各自拥有同一群消息副本是**合法共同经历**，不能误改成群友都不知道群聊内容。保持上述既有隔离，不建重复记忆系统。
- **查到实际全局泄漏**：原 `CompanionModelGateway` 的 Full 模型请求无条件调用 `UserProfileContext.promptSection()`，其曾把账户 `preferred_name`（「希望角色称呼」）、`bio`、出生日期与位置等直接放入所有角色模型的输入。账户在设置页保存这些字段不证明每个角色曾听说、记住或形成同一偏好。现在 `UserProfileContext.promptSection` 仅保留 Android 用户设备事实归属和**账号资料不等于角色已知**的边界；具体个人资料仍完整留在原 SharedPreferences/用户 UI 中，只有某个角色通过现有本人交流、可见事件及明确共享来源取得的内容才在其提示词中出现。原始数据不删除，不改变用户名的 UI 显示入口。
- **查到可复现的统计串线**：`UserRoutinePatterns.detect(events)` 接受事件列表却仅按 source 分组；三名角色各有不同日子的同类型活动时，会被混算成同一「三日重复习惯」。现改为先 `characterId` 后 `source` 分组，角色同一活动的实际日数、来源和学习门槛保持，混合多人列表不再合并出伪习惯。入口 `UserRoutinePatterns.recent(characterId)` 原本就按角色读取，继续保持。
- 成长类型中原先通用「主人的偏好／习惯」显示改为中性的「用户的偏好／习惯」；既有 `CharacterDevelopmentRuntime` 当前角色的独立反思输入更明确「不要把其他角色得知的用户偏好据为己有」。这只是事实归属语言和提示词，不影响角色实际称呼权及有自己人设的角色的私有口吻。
- **回归** `CharacterKnowledgeIsolationTest`：角色 A 私聊原话 → A 自己的 `UserPreference` → A 自己的自主上下文、三日活动观察；角色 B 的时间线、成长、日常习惯及真实 MockWebServer Full/lean 请求均不能包含 A 的学习结果；账户 global `preferred_name` / `bio` 不应出现在任何人私聊模型上下文；源记录撤回则 A 自己的成长事实失效。`UserRoutinePatternsTest` 另验证混合三人、跨多日相同 activity 不产生共享习惯。
- 源码/测试提交：`71c039a3`、`fb9662d8`、`4bd8e2fd`、`00d60943`、`d9268a01`、`b363a312`。 补充：`CompanionModelGateway` 的所有人物决策/表达入口明确区分「本人获知」与「其他角色知道」，容许真实在场群聊和真实转述，但不接受别人私聊的无来源继承；真实 MockWebServer 输入断言已追加。提交 `8cbaacff`、`1cb6d931`。代码索引自动工作流与 APK CI 以最新 main 实际运行状态为准，不把 queued/cancelled 当 success；真机需实际验收 A/B 私聊已知信息差异、群聊共同现场以及撤回原话后 A 的更新。

## 2026-10-28 追加：所有人物私有经历/关系网/多人相遇中的信息所有权（隐私串线 P0）

- 用户明确要求 **A 和 B 的全部个人记忆、生活经历、日记、心理、偏好认识、关系、日程与执行回执均独立**。应用只允许公共物理世界和真正共享参与的可见事件；哪怕 A、B 两人都与 C 相识，也不代表 C 知道 A 与 B 之间的私信或私下见面。不能把另一人的模型推测/私人结论发给全体。
- **源码发现 1：社交关系网的旁观者读权限缺失。** `CharacterSocialRelationship.networkContext(characterId, contacts)` 原本直接对两名联系人调用全局 `snapshot(first,second)`，即使本人不在群聊/见面，也会告诉本人两人有私信关系或曾相遇。改为 `witnessedNetworkSnapshot(viewerId,firstId,secondId)`，只查三人共同可见的群聊成员关系，或者确有已完成、带发言的三人共同会面。其他二人私信和私下面见永不从全局数据库进入旁观者的私聊/心里。本人退出共同群后，新投影即时失效；历史真实曾见到的信息仍由本人原始时间线承接，不删历史。
- **源码发现 2：原共享群场景暴露个人内心**。`CharacterSocialRelationship.contextForGroup(participantIds)` 原来把每对成员的 `perspective(a,b)` / `perspective(b,a)`（个人主观理解、理由、来源）以及两人关系细节注入一个共同场景。已去除全部私有印象输出；三人及以上仅保留全部在场者均可验证的共同交集，二人场景只可知道两人自己的真实往来事实、不含正文和主观认定。
- **源码发现 3：自动多人相遇共用脑袋**。`AutonomousSocialRuntime.generateEncounter` 原来把每名人物的 `SharedExperienceTimeline.all(id).takeLast(8)`（可能包含对用户的私聊）、`CharacterInnerLifeStore.compactContext(id)`、`CharacterRuntime.definition(id).promptSection()`（含本人私有关系/成长），以及以第一名参与者的 `Full` 网关上下文全部喂给同一个多人场景模型。这是最直接的私有记忆跨人物输入污染。现在共享生成器使用原有 **一次** 模型调用，但显式设 `CompanionContextMode.Isolated`，只提供环境、参与者名称与 ID、真实全员共同在场且已结束的过去会面（当场说出的话与公开动作）和全体可见的关系交集；不提供任何人的私聊、日记、心理、稳定用户偏好或专属关系。各人原来的私人状态完全留在自己 Store；生成后仍按各人 ID 保存自己发出的台词及私人反应，不构造统一人格。
- **源码发现 4：虚拟手机与世界位置旁路**。`CharacterPhoneInbox.modelContext` 原先无条件渲染调用方传入的 posts，漏掉一次 viewer ACL，且 `acknowledgeObserved` 只粗查 ID；现统一校验本人当前好友权限、原始帖子的存在性、作者、版本与已显示内容，防止别人可见帖、删除/伪造帖进入本人模型及“我看过”原始记录；新增点赞/评论到来时保留未读变化，不丢原始数据。`CharacterEverydaySocialContext`、`ProactivePerceptionRuntime`、`CompanionActionRuntime` 原先把所有居民当前位置和住处罗列给每个人，现在只把当前地点真正同处的居民和自己确实可见的社会信息供本人决策，其他居民只能沿真实探索/来信认识；世界权威位置仍供工具执行器核验，不是人物脑中的已知事实。
- **回归**：`CharacterSocialNetworkTest` 新增私密双人群/私信不可见、加入三人共同群可见关系、退出后不再暴露、多人场景禁止带走私人相处评价；`ResidentPhoneWakeIntegrationTest` 验证越权帖子/伪造帖子不可注入模型与回执；`CharacterSocialNetworkTest` 全员共同在场会面交集，未结束/部分缺席的会面不进入；`CharacterKnowledgeIsolationTest` 使用真实 MockWebServer 验证 Isolated 场景网关不会从主导角色额外召回私人话题与人设。
- 主要源码提交 `1e5adef1`、`e58b7e36`、`6c98f7d2`；中间编译约束校正 `ae0501eb`；测试 `a7db33a3`、`1088b44d`。 补充 `80e03acb`：原多人场景共用模型不仅看见别人的内心，还把该模型编出的 `innerThought/innerLife/appraisal/decisionReason` 当成每个角色自身的私密心理写入 `CharacterInnerLifeStore`；现多人场景仅记录各参与者实际当场对外说/做的事实，每个人是否在意、怎样理解、后续动机一律留给自己的正常感知与单人模型回合决定，共用场景不再替人物提交内心。**CI / APK 状态必须单独核对最新 main；没有真实通过前不可宣称绝对无污染或已经真机通过。**
- 后续留意：单次中立生成仍可能为人物选出不合其本人风格的台词，因为完全不读取其私有主观状态；这项隐私优先策略不能被误认为已实现完全自主、各角色分别用自身大脑决定每一句。若未来要求每人同时保留私有上下文决策且不让他人模型看到，就需要在现有多方场景框架中安排各人独立的语义生成，并明确模型调用成本。不要偷偷增加常态调用。

## 2026-10-28 续修：居民旧唤醒和迟到模型回包的日程版本防护（待 CI / 真机）

- 根因：原到点复议的 wait/reschedule 已有 expectedDueAt，但 cancel 不核对版本；模型思考时主人或另一入口改期后，迟到的 cancel 可把新时刻的同一计划取消。execute 只向 ToolRouter 传 planId，旧唤醒 Worker 也只携带 planId，不能区分同一计划的不同 dueAt 版本。
- 修改 RoleDayScheduleStore、ResidentDailyDecision、ToolRouter：取消在同步持久化事务内比较原 dueAt；居民主动作携带内部 expectedPlanDueAt，在原 ToolRouter 事实/权限门禁中拒绝旧版本；新 Worker 输入带 dueAt，冷启动初始化前后均核对，markDue 也拒绝旧版本。已安装的旧 Worker 没有版本字段，保持兼容并继续受原时刻/请求幂等门禁保护。
- 模型的旧选择与独立步骤仍有原始记录、稳定 requestId 和独立回执；不能因为用户撤销原计划就把模型同轮明确选的独立分享自动抹去。旧版本主动作未生效时记录 superseded，停止对已改期/取消的旧选择反复恢复；原计划未改变但执行失败继续 needs_recovery。不增加正常 LLM 轮次。
- 回归在 ResidentDailyContinuityTest 覆盖原版本的 canBegin/markDue/cancel 门禁，以及 MockWebServer 在模型响应前真实改期，分别返回 cancel 和 execute：新日程保留、旧主动作不发、独立动态仅一次、原请求不重新收费。当前仅为源码和测试提交，Android JVM / APK / 真机结果以随后 CI 和设备验证为准。
- 剩余风险：外部动作已经进入 ToolRouter 后，用户并发改期不能撤销已经发生的副作用，须按原始回执和幂等证据追踪；老版本没有 dueAt 的排队 Worker 无法推断过去版本。P0-A 的补正中断显式恢复与等待条件变化提前复议仍待处理。

## 2026-10-28 追加：完整人物人生档案隔离（源码待 CI/真机）

- 新要求：每位角色的人格、记忆、私有经历、理解、感情、关系网络、日记、成长、习惯、行动回执和感知已读属于本人，不能因其他角色或 App 共享账户而合并；群聊/共同事件仅能由各自实际目击并独立入账。
- 已存在的单人 stores 和事件主循环保留，不删除/改写任何人的持久原始历史。追查证实感知 integrate 上层传入混合 observed 时缺事件属主校验，以及 compact/full 记忆渲染在最终模型输入端没有防御传入混合角色记忆/日记/事件列表。
- 改动：CharacterPerceptionContext.integrate 仅接纳 characterId 匹配的实际 observed；UnifiedMemoryContext compact 与 MemoryWorkingSet full 只显示所属人物的 core/memory/recent/evidence/lexicon。历史调用缺 owner 时，仅在单属主无歧义时沿用；混合属主 fail closed。以上只控制本轮展示，不删除记忆。
- NarrativeEvidenceChain.expand 对混合 seeds/live 按首名人物边界过滤，仅用本人 task/session ID 查续章，不合并其他人物同名任务。
- A/B 混合真实观察、已读 ledger、两角色共见群聊、compact/full 记忆/日记、空 owner、相同 task/session ID 均已加源码回归，但 CI 和真实手机体验须分别核实。
- 主要提交：48cf4132, 120314e0, 14faf543, 008697ac, ef8e7c41, 719d4e3d；测试：fb879f73, cba7a636, 02fd2613。
- 风险仍存：尚未逐一核验所有一千余处外部入口、备份/恢复、迁移与已拼成纯文本的辅助上下文；本轮不能宣称项目所有数据绝对零串线。下一轮应以现有真实运行路径的归属证据继续审查而不是再新增共享人物状态中心。

### 2026-10-28：角色感知最后一层原始归属防护（A/B 直接感知与输出）

- 在既有 `CharacterPerceptionContext.integrate(characterId,observed,direct)` 已对 `observed` 做属主过滤后，进一步复现**同一外国原始事件被重新包装成 `direct=PerceptionStimulus`** 的漏洞：上层可能只传包装的私聊事件，`observed` 列表为空/未含外国角色行；旧实现仍将 B 私聊读进 A 情绪/思考并可能在 A 的已读 ledger 盖戳。现在先用 `SharedExperienceTimeline.foreignEvidenceIds(characterId, ids)` 针对 canonical SQLite 主键批量只读校验 `direct`：若证据确实存在且归属 B，A 不得当作自己观察；未知于时间线的真实**合成当前通话** stimulus 仍按旧流程保留。不改内容、不新建共享观察中心或模型轮次。
- `CharacterPerceptionContext.render(characterId,events)` 改为**强制接收当前人物 ID**，在最后序列化为 LLM 提示词前再次去除其他人物的观察。已接入 `LuluDeviceToolBridge`（私聊/电话）、`ProactivePerceptionRuntime`（自主感知）、`DigitalWorldMeetingGenerationV2`（本人会面模型）；不允许调用旧的无角色 render 形式。群聊中实际目击的共同事件已有各自的原始事实行，仍能独立呈现。
- 新增 `CharacterPerceptionContextTest`：只以 direct 传入 B 私聊、observed 混合、真实 A 消息与无需持久化的电话事件，验证 A 模型感知/ledger 不能留下 B 的私聊，B 自己仍能收到；最终序列化 mixed events 给 A/B 时只能出现所属事件。
- 提交 `5537b3f8`、`1616d1a6`、`fd6b4b10`、`05eb332c`、`ba4b39c0`、`78f495cb`、`67016293`、`e025542d`、`183fdef2`。**索引 / CI / APK / 真机分别核对，不因提交就称绝对零串线**。剩余专项风险：未遍历全部历史备份恢复、第三方输入以及被提前拼成不可溯源字符串的旧上下文；后续须继续按真实入口审计，而不是全局禁用合法社交。


## 2026-10-28：一人一档的物理归属/生命周期补修（不只是提示词隔离）

- 主人再次明确：每个数字角色是独立人生档案，不仅是「模型看不到其他人的偏好」。原始时间线、亲历事件、长期/核心记忆、日记、本人收藏及原因、人格/成长、心理与关系、个人计划、回执、已读以及删除/备份恢复必须严格以不可复用的 `characterId` 归属。可以复用相同底层数据库，但不能共用一份个人档案或在存储操作里跨角色覆盖/删除。共享群聊的合法共同事实，应分别形成本人证据；只有真实可见、来源有效的公开事件可被各人分别感知。
- 查到原始收藏的具体底层破口：`CharacterMessageFavorites` 曾用全局 `message-${message.id}` 作为 Lexicon ID、全局 `lexicon-favorite-${entryId}` 作为时间线 ID、`pendingEntryIds` 也按此全局键去重。A/B 同时收藏同一条已亲历群聊时，B 的保存可能被 Lexicon 全局 ID 冲突拒绝，或时间线因事件归属不同而拒绝 B 记录；调用方仍可能宣称收藏成功。
- 已修：新收藏采用 `message-<消息ID>:role:<角色ID>`，原始收藏行动证据采用角色专属前缀加单次 UUID（删除再收藏产生新事实）；各角色单独校验消息在其原始时间线确有用户发言，旧版 `message-<消息ID>` 收藏继续识别，删除时仅按当前角色前缀撤回本人新旧证据，清空本人档案时取消其待处理异步收藏。收藏提示词中的通用「主人」改中性「用户」，避免强行给平等朋友套用其他角色的关系。提交 `0c8d6721`、`e23cf4ed`；跨角色同消息分别保存/删除/重新收藏、禁止将他人私聊存入个人收藏的单测已添加。
- 又查到 `LocalMemoryRepository.deleteEverywhereEquivalent(id)` 的历史语义会按文本身份键跨角色删除等价记忆并写入各人的禁用来源，即使两人本来各自亲历。现在虽然保留旧方法名兼容测试调用，但明确只影响目标记忆 `characterId` 的同主题等价记录；新的 Robolectric 测试证实 A 删等价记忆后 B 的相同内容仍可重新加载。提交 `6ebc924f`。
- **未完成/待专项验收**：上述提交是实际源码与测试，不等于最新 main Android 单测/APK 或真机已经成功；CI 以最新 HEAD 为准。尚须逐个审查全部历史导入/备份恢复、长期记忆/日记/收藏的旧版主键冲突、删除/撤回对派生索引与缓存的传播、所有独立人物生活模块及旧 ownerless 文本来源。不能仅因 `snapshot(characterId)` 存在就宣称所有写入/聚合/重置已分开，更不能因使用同一个 SQLite 文件就误称所有人物共享记忆；审查实际记录归属和生命周期。


## 2026-10-28：主人授权按审查大纲分步实施

执行顺序与验收：
1. 相遇与心理依据：保留无台词的真实相遇及本人反应；角色/居民回答前心理不得关联后来回答；先发言者仅关联其已见的公开事实。源码已提交 `407298ea`，自主相遇依据与回归续补；Android CI/真机分别核验。
2. 旧目标与时间连续：兼容 intention 的接续提示也经过现有 motives 去重；不删除历史。后台文学任务携带排程时的心理快照，重试复用同一快照；旧任务未知心理不读取现在补造。快照压缩进入现有 WorkManager 载荷，超出平台10KB时保留事实任务并明确心理未知，不改变人物状态库。源码同上；待 Android 回归及延迟补写实测。
3. 社交接续与恢复：本轮将先发言者的当轮反应绑定其真实输入范围，后来回答仍为原个人感知可见的未确认事实，不增加每场强制复议收费。仍待：相遇无效输出同角色有界补正、完整失败恢复；邀请/婉拒/改期/真实到场的多日链路实测。不能称此步骤全部完成。
4. Live2D 与综合验收：本地执行 parameters、touch-hit、conversation-performance、impact-recovery、physics-frame-control 均通过；本批未据此更换既有动作参数。真机长句停顿、触摸同时讲话、旧回复替换、15/30FPS、发热与音画同步尚未验收；Android 单测/APK 以当前提交的原发布工作流为准。

后续继续完成尚未打勾项；没有用提交或静态检查冒充全大纲完成。历史导入/备份恢复、等待条件变化提前复议、多年数据规模验证仍在原待办范围。


## 2026-10-28：多人相遇结构补正与真实失败区分

- 适配当前 AutonomousEncounterScript 分阶段交流，不恢复旧单句路径。公开阶段、segments、steps/args 与依赖布尔均在产生副作用前检查；旧单段格式保留。违反结构时同一个人、同一个模型、原现场上下文进行最多一次补正，正常决定零补正调用；空响应/未知网络结果不盲目重新购买。
- 两次仍无效时保留网关原始输出和角色专属失败决定，关联本人相遇起始证据，明确未执行，不能当作角色自愿安静；不写入无效心理/公开台词或执行其 steps。quiet 合法决定仍可有私有心理与独立社交动作。
- 移除相遇解码时额外的动机词表否决，结构完整的本人 reason 不因措辞短或简单而丢失公开选择。
- 新增 AutonomousEncounterDecisionTest：正常不补正、非法小数阶段→一次本人补正、二次无效停止且保留两次原文、合法安静和私人感受、嵌套参数/重复阶段、属主不能伪造。源码与索引检查不等于 Android CI/真机通过。
- 后续继续：未知请求的可恢复状态、邀请/拒绝/改期后的真实续接与多日表现；不得宣称全大纲已完成。

## 2026-10-28 P0-A：有效日计划遇到真实权限变化后的独立失败证据（源码待 CI / 真机）

- **根因**：已有 `dayContractError` 验证 JSON/agenda/steps 格式，但结构正确的居民日计划在 `saveAgenda` 阶段仍可能因为当前联系人不认识、好友申请已处理、群聊权限失效、家具被移动/删除、地点不合法或排程持久化失败而 `continue`，导致本人的真实选择既没排进日程，也没有任何失败证据，随后 `runOnce` 却写 `finished`。另外顶层可选 `message/messageTo` 只填一半时原流程静默跳过，无法走已存在的一次结构补正。
- **修复**：不更改模型意愿和原来的权限门禁；每个无法排入的 agenda 行在原 `SharedExperienceTimeline` 写入本人专属、按日期和索引稳定的 `resident-agenda-rejected` 观察回执，说明确切失败类别、明确未执行，并以同一 `native-daily-<residentId>-<date>` taskId 关联原日决定。正常合法行继续排程，回放同一已保存决定不重复增加失败证据版本；撤回原回执后原时间线墓碑禁止重建。超过上限的旧已保存 agenda 也保留未排程证据，但不擅自执行多出的活动。顶层 `message/post/messageTo` 新响应若类型错误或收件人/正文不成对，沿原有**最多一次同角色模型结构补正**，而不是让执行器替其猜收件人或默默丢掉。
- **测试**：`ResidentDailyContinuityTest` 使用真实居民/权限/日程/SQLite 时间线验证“未认识的联系人 + 未加入的群 + 有效的独立发动态计划”同时存在时，前两项各有独立、本人专属且未执行的拒绝证据，第三项仍正常保存但不提前发布；同决定回放无重复日程/证据 revision；删除一条原证据后不会因回放复活。结构回归覆盖顶层 message/messageTo 缺失及 post 类型错误。
- **提交**：`e7b39a16391b7a752dc336b08cd8dea12420ed18`（原权限与排程失败回执）、`33ef6178faa92b17a7482a9caaec5236614386ab`（顶层直接消息完整性）、`d25e6952dc661d7fdb3e0bb08ea1e545a59a07e5`（权限/回放/撤回测试）、`328f1448ae1164fc00d14d1a15f5d825499c21ad`（同角色补正提示词）、`fbc14e5290ea98a1275d8596bd18a5c720d52307`（真实 MockWebServer 两次模型请求→一次私信投递）、`bc72c60f8b641683cf1c9260ceae49ff2e59aabb`（测试中先核验双方实际同处与联系权限）、`1b21f5d2b7fdbecd7fc55c90b099c5e5169c9f90` 与 `fde786c7c8ab62cb94cec462f2cbc3dd6c7226a2`（JSON null 不能当成字面“null”私信文本）。**CI/APK/真机需按最新 main 实际结果核验，提交不等于通过**。
- **剩余**：拒绝后的下一轮模型能看到本人回执，但不自动重新收费或强制改变原决定；等待条件变化的提前复议、未知收费结果人工恢复、模型结构补正中断显式恢复，以及跨日长时连续性仍待专门实现。P0-B 应在既有事件详情中串起这些证据，不另造状态中心。


## 2026-10-28：居民在原有唤醒中提前复议等待日程

- 根因：plannedContext 不显示原等待/改期记录，居民 wakeTools 不含已有的改期/取消能力，executeWakeSteps 又一律移除 planId，导致即使收到来信，原模型也无法调整未来复议时刻。
- 修改：日程上下文显示本人原等待记录和 expectedDueAt；日常/来信/到点已有决策可自行选择 reschedule_personal_activity 或 cancel_personal_activity，不新增常态模型调用、关键词判断或调度中心。提前复议通过本人明确选择的未来时刻沿原日程唤醒，收到消息本身不等于条件满足或对方已接受。
- 新管理步骤保留本人选定的 planId，必须提供原时刻 expectedDueAt 与本人理由；普通社交步骤仍禁止借用 planId。主活动的改期/取消/等待仍由主 choice 决定，不允许副动作修改同一正在复议的活动，防止取消当前 Worker 中断其回执提交。
- 原执行器将版本条件交给原 Store 的同步校验；旧模型回包或他人日程不能覆盖/取消新安排，原无版本调用保持兼容。取消理由只校验非空，不按理由长短否决模型意愿。
- 新增 ResidentPlanRevisionTest：原等待记录召回、本人提前改期、旧版本取消失败、跨人物拒绝、结构缺失补正入口及当前主计划保护。源码/索引检查与 Android CI、APK、真机验收分别报告；尚不能宣称所有等待条件或多日社交均已验收。
