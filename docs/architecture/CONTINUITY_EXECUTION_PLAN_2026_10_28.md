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
