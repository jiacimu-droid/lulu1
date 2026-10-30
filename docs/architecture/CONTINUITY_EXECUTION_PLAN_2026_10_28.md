# 统一人物连续性实施清单（2026-10-28 主分支核对版）

## 2026-10-29 P3：台词/动作成对计划、实际音频进度与原生 motion 衔接（本轮待 CI/真机）

- 本轮将上一版逐句锚点补漏提升为 `Live2DDialoguePlan`：同一模型 reply 用唯一 speech 数组同时选择 id/text/motion/transition，原角色心理/工具流程保持；现有 Cue 临时携带同一份片段，按最终口语精确一致校验直接入 TTS，不靠重复原文和标点搜索。普通回复与真实工具结果反馈共用同一视觉契约/发布入口；纯语音、哄睡长段和旧 beats 兼容。
- AudioTrack 首音与进度改读已消费帧，系统缓存语音读 MediaPlayer.currentPosition，经同一语音事件的 utteranceId/visualSequence/segmentId 向既有 stage 报告。真实位置到来后节拍不靠墙钟越过欠载；没有真实位置的兼容通道不谎称词级同步。
- 动作 continue 避免同一实际 clip 跨句重启；blend 从上一帧已呈现参数淡入新 Motion3，时长/控制通道来自实际 model3/Motion3，跨动作混合在触摸/表情/PCM 层之前完成。口型、触摸、眼型依旧沿原控制权；中断/模型切换取消旧过渡。移除通话执行路径的轮换点头兜底，不用程序选动作掩盖契约失败。
- 已新增 JS/JVM 根因回归：重复台词/声音标签无锚点、有效计划零补正、重写失配不复用、真实模型资源/时长/掩码→Cue→语音身份、旧进度/逆向时间拒绝、continue不重播、实际时钟欠载、只混合真实控制关节。JS parameters/touch-hit 本地通过；JVM、APK、索引与真机需按最终提交 CI 核验。
- **保留边界**：原16段协议未扩容；词级重音、原生动画逐采样重定时、任意关键帧长时间保持、不同 TTS 服务跨段音色连续性仍未实证/未实现，不把片段级同步说成逐字同步。没有真实 fade 元数据的模型仍依赖原播放器；真机应观察三句话连演、插话、摸脸交还、低帧率和真实首音/补正率。

## 2026-10-29 P3：视频逐片段 Motion3 契约与播放状态竞争（本轮待 CI/真机）

- 真实断点：视频指令同时包含「不必每轮动」与「尽可能每句动作」，没有检查正常 reply 是否只给开头选了一个 Motion3，原 liveness floor 也只有整轮一个动作。新增 `Live2DSpokenMotionContract` 按真实 `CallReplyStream` 发声片段核对句首锚点与当前模型真实 group/index；只检验结构和资源，不从台词/情绪猜动作。
- 正常非哄睡视频使用现有同一角色、同一已选聊天连接：首轮完整动作零新增模型请求；真实漏项/无效选项最多一次动作元数据补正。原台词、心理、日程和工具决定不重做；补正仍失败保留原语音/有效动作，并通过现有通话错误状态与感知记录说明动作未配齐，不谎称每句已执行。没有 motion 的模型不购买无效补正；纯语音/静默和哄睡长段不强制此契约。现有单轮16拍边界未扩张，超长文本仍可能无法配齐，不能承诺任意长独白全覆盖。
- 声音标签边界：`[warmly]句首` 以前因前一个字符为 `]` 无法绑定，空格版还会切出只有标签的 TTS 请求。锚点现在把合法英文声音标签与其后原句保在同一实际音频片段，不伪造词级时间戳。
- 播放竞争：Compose 的粗粒度 speaking/PCM 状态与真实 utterance 回调属于独立 effect；迟到的 speaking=false 曾清空真实 onStarted 刚启动的动作。带 visualSequence 的动作仅由真实 utterance 结束事件停止；迟到 speaking=true 也不能提前播放下一轮 pending motion。旧无版本预览继续兼容。
- 本地已验：Live2D `parameters.mjs`、`touch-hit.mjs`（包含真实 stage 函数的 conversation-performance 回归）通过，覆盖迟到 false 不吞当前动作、迟到 true 不提前播放下轮，以及原触摸/打断/多分句动作。新增 JVM 回归覆盖首段动作不足、有效选择零补正、无效资源一次补正、失败保留、无 motion 不请求、声音标签保留。JVM/APK 与实际 Android 视频尚未验收，需按本轮最终 main CI 核实。真实补正率与延迟也须真机测量，不能把源码契约当成模型和画面已成功。

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


## 2026-10-28：居民未知请求的显式恢复与跨日活动复议

- 原不确定请求仍不自动重买。已有居民设置页增加今日规划和到期活动的恢复入口，区分请求未知、结构补正中断、无效输出与接口拒绝；确认提示可能重复计费。页面恢复后刷新原规划/行程，不新建任务中心。
- 普通 worker 和手动恢复共用每日/日程版本的 KeyedWorkMutex；同一请求尚在运行时先等待它结束，再比较 UI 当时显示的 phase/revision。已有有效决定、旧确认、被改期或取消的日程不重新收费。新请求前保留旧错误和无效输出归档；重试失败仍有下一次明确手动恢复入口，不把失败记成自愿沉默。
- 每日规划确认在取得锁后重新读取实际日期，跨午夜的旧确认失效。较早仍未完成的个人行程继续显示，未知到期请求可在明确确认后按当前事实重新选择执行/改期/取消/等待，绝不自动补发过去计划的消息或动态。已有有效决定依旧走原稳定 requestId 恢复，不借手动恢复重执行。
- 新增状态/跨角色/陈旧确认/跨午夜单测，以及 MockWebServer 的一次今日手动重试、昨日活动由本人新决定取消、第二次同确认不买请求、旧错误仍可追溯与不补发旧动态的集成回归。Android 单测、APK 与 UI 真机分别验收，未运行成功不能称通过。
- 本项补齐的是居民每日和到期未知请求的用户显式恢复；所有跨场景状态版本、跨日有效旧决定的复杂副动作恢复和多年数据仍需继续逐项核对。


## 2026-10-28 P0-B：原始时间线详情中的只读因果检查（第一段源码已提交，CI/真机待验）

- **实际根因**：已有原始时间线卡片的「查看详情」仅显示一条事件文字和人格影响，CharacterInnerLifeStore 的 decisions/thoughts、ToolRouter 的 causeEvidenceId/requestId/status/result、PerceptionStimulusLedger 的精确已处理检查点虽然分别持久化，却没有在原记录页面连接。用户看到「想分享」「搜索成功」时无法区分本人想法、已选择动作、执行器实际完成、等待授权、执行失败、结果仍待感知、持续目标尚未结案。ToolRouter 的拒绝回执原来还丢失了 causeEvidenceId，导致拒绝无法追到触发它的那条原始输入；已有成功回执只保存来源 ID，来源编辑后可能把旧行动错认成针对新文本。
- **本轮源码**：新增纯查询 CharacterCausalTrace.inspect，只从同一 characterId 的权威时间线、本人内心历史、工具回执、动机状态及感知 ledger 投影，不保存第二份因果状态、不新增 LLM 或调度。既有 CharacterRememberedThingsScreen 的「原始时间线→查看详情」展示：想法仍保留/已放下、确切关联的决定、工具请求/拒绝/等待/失败/成功、原生执行证据、感知检查点与持续目标当前状态。无记录时明确「未找到」而不是谎称本人没想法或已执行；感知检查点不冒充模型理解/目标完成。回执源事件经 SQLite 本人归属和 revision 再校验，已撤回的行不重现。ToolRouter 新的成功与拒绝回执都记录属主校验后的来源 ID+当时 revision；旧记录不伪造版本，编辑后的来源与旧决定分开说明。
- **只读保证**：PerceptionStimulusLedger.hasSeen 会迁移旧 hash 并插入精确已读，不能用于纯诊断。新增 peekSeen，只查已有精确行或有效旧迁移标记，不调用 claim、不写已读或改动人物状态；诊断 UI 使用 peekSeen。
- **回归**：CharacterCausalTraceTest 覆盖 A/B 混合输入仍只显示 A 的想法、决定、回执和感知；执行器真实拒绝仍有来源与版本；编辑来源不能将旧回执当新决定；pending/failed/waiting_user 不当成功；原生事实撤回时依照原时间线撤销依赖回执；PerceptionStimulusLedgerTest 证明诊断不暗中 claim/migrate。新增与修改：CharacterCausalTrace.kt、CharacterRememberedThingsScreen.kt、ToolRouter.kt、PerceptionStimulusLedger.kt 及两份测试。
- **提交**：387267ab、5669ffba、14a79d04、69fa4f69、14afeedb、cb010ec7、0c9cc34a、3dc738f8、aea23fc9、026b63ee；按仓库已有代码索引 workflow 自动刷新，不能以 workflow 启动代替索引完成。Android JVM、APK、真机结果按最新 main 独立核对。
- **明确剩余 P0-B**：本轮是原时间线中「有精确来源 ID 的动作/回执」诊断，不声称所有聊天、电话、群聊或居民决定都已提供精确来源；旧记录缺 causeEvidenceId/revision 时不编造关联。后续核查各入口源 ID 传递、非工具的消息真实送达/等待同意/独立回执、搜索结果进入下一轮心理及目标是否达成、跨日数据量下分页查询与性能。不得把「已展示执行器成功」等同「已感知/已理解/目标完成」。

- **本轮尾段加固**：`b022f3b7`、`9bda1716`、`f0ab8c17` 让心声版本更新后诊断刷新，且只有展开的时间线卡片才进行数据库关联查询；`bca86aa2`、`82fd60bd` 区分外层成功但内层仍 pending 的异步回执；`b11b33d7` 校正原生阅读删除引发的回执撤回测试；`b4709a90`、`35a542bc` 明确同 taskId 仅是相关记录而非直接因果，不把共享任务当作源事件触发证据。上述提交均仍需最新 main 的 CI/真机核对。

## 2026-10-28 P0-B：居民日决定、到点选择与每项工具回执的精确因果接线（源码已提交，CI/真机待验）

- **根因**：已有 P0-B 原时间线详情只能关联带精确 `causeEvidenceId` 的工具回执；但居民 `ResidentDailyDecision.runOnce` 的直接发动态/私信、`executeWakeSteps` 的多步动作和 `runDuePlan` 的主活动全部未传本人已保存的决定依据。共享 `OrderedCharacterActions` 只会从 `userEvidenceIds` 推断来源，居民没有新用户消息时永远为空。更深一层，`ToolRouter` 原本只允许 UserStatement/Observation 作为回执的因果依据，不接受已经存在的本人 CharacterStatement 决定。因此即使有真实发布/失败回执，用户在居民决定详情中仍看到“没有后续”，也无法区分未选择与已执行。
- **修复**：在 `OrderedCharacterActions` 的既有执行器增加可选、仅作来源证明的 `causeEvidenceId`（有明确用户原话时仍以真实用户事件优先），不改变工具权限、幂等请求 ID 或执行顺序；`ToolRouter` 在角色归属、有效版本的原校验下允许本人 CharacterStatement 作为因果来源，仅用于回执追溯，不代表执行成功。居民每日决定直接动态/私信及多步工具都传本人 `native-daily-...` 原事件；到点模型决定在产生副作用前以 `native-wake-<planId>-<原dueEpoch>` 写一条稳定的、明确“决定尚未执行”的本人原时间线事实，主活动和独立步骤统一引用它。保存后重启沿同一 ID 继续，不重写原时间戳/revision；原证据被删除后墓碑仍禁止重建，也不会借他人的决定作依据。旧已执行工具回执不会被改写或重放，旧缺失来源的记录仍明确未知。
- **同时修正诊断筛选**：原记忆页搜索时传给因果检查的是**按搜索词过滤后的时间线**，使搜到决定但未搜到另一种措辞的工具回执时假报“无后续”；现在搜索只过滤可见卡片，展开卡片的因果检查使用已加载的完整本人时间线，不另做收费调用或状态写入。
- **回归**：`ResidentDailyContinuityTest` 扩充真实 MockWebServer 日决定→直接动态/私信、已保存到点决定→主活动、主动作后中断→独立副动作的来源 ID、版本与只读诊断验证；再次恢复不得增加发布数量或改变原始决定 revision。`CharacterCausalTraceTest` 覆盖本人 CharacterStatement 产生拒绝回执仍可追踪、A 不能把 B 的决定冒充自己的原因、原依据撤回后只显示已撤回。实际源码提交：`57ec6456a0b62088c29bc3822f724b7a158503fc`、`60463e9e3336bad217e448a1607e272b59412107`、`0adc5ce592039f2fe073a6ff9b5fca72081ad9dc`、`41d4637b1c86160a6ef0d782639a0d66d52ecc0c`、`6e446e5f802bc571820010057593cee71b3ee82a`、`ebceab7274fc6ec815f988b0c86e3ee8498b05ae`、`9fcb591ef8c8b8a3c00d38319d81c88bdf066d93`、`2d54e7cb24f9edf8cf8f61aabb019973c2fd6e88`。
- **边界/待验**：目前为源码与测试提交，不等于本轮 CI、APK、真机已通过；自动索引由原有 Refresh code index 工作流运行并单独核对。仍待验证前台/电话/群聊中非工具消息送达的精确因果关系、结果已感知但目标未完成的诊断，以及长期全量时间线查询的性能；不能把搜索命中或工具返回成功当作人物已经理解/目标完成。

- **补充同一断点**：每日规划在 `saveAgenda` 阶段已经会留下 `resident-agenda-rejected` 角色专属拒绝证据，但原 `CharacterCausalTrace` 只投影 `tool-router`，仍可能显示“没有后续”。现将与本人的原每日决定 ID 精确匹配、仍处于有效 revision 的拒绝日程加入同一详情，明确标为**未排入、未执行**，撤回后不再显示，旁人无法查看；不把排程拒绝算作动作失败后的真实经历或已完成。源码 `83c7d6f999191538e9eca4046d442db1b7f6ca11`，回归 `ae872962f0c43f679420246744c5af434993ee92`。仍需最新 main CI/APK/真机确认。
- **来源修订保护**：已保存的居民决定在恢复时，若原始日/到点决定事件已经被编辑到 revision>1，仍沿原稳定 requestId 执行或恢复真实动作，但不再把新版文字当作旧决定的因果依据；删除/旧主键冲突同样不伪造来源。提交 `386823780076b43af8681880dcb00fbb6af18186`，`6801df82bb91996ba4a61481502953a44d6864e2` 加入编辑前后、真实执行、零重复发布的回归。CI/真机继续独立验收。


## 2026-10-28 P0-B：居民成功排入的未来日程也能从原决定追溯（源码与回归已提交，CI/真机待验）

- **根因**：原每日决定的直接动态/私信和到点工具已有精确 causeEvidenceId；未排入的 agenda 也有拒绝证据。但**成功排入的未来活动**只在 RoleDayScheduleStore 保存标题、时刻和工具参数，没有当时原决定的事件 ID/版本。原时间线详情因此把“已经安排但尚未到点”的活动误显示成“没有后续”，也无法区分“计划已保存”和“动作已执行”。不能靠相同标题、时刻或 taskId 猜来源。
- **修复**：RoleDayPlan 新增向后兼容的 originEvidenceId/originEvidenceRevision，可选且默认空/0；既有存取、改期和撤销沿同一计划保留来源。居民 saveAgenda 以已有稳定 native-daily-<residentId>-<date> 和首版 revision 写入，不创建新时间线或第二套状态中心。CharacterCausalTrace 只读列出同角色、精确来源的计划，区分尚待执行、已取消、已完成但执行证据已撤回/不可见、已完成且原证据仍存在；来源修订后明确标记历史版本，不能拿新版文字解释旧决定。旧计划无来源时保持未知，不做猜测。
- **测试**：CharacterCausalTraceTest 覆盖原决定→真实持久日程、角色隔离、来源编辑与撤回、计划不冒充成功；ResidentDailyContinuityTest 在真实 MockWebServer 无效嵌套 agenda→本人补正→保存日程的集成路径核验来源 ID/版本、原详情能找到尚未执行的计划、模型请求与发布次数不增加。原有工具幂等与权限执行不变。
- **提交**：`33c67878bf1f1e2839c7ba316d1ec98f68247f28`（Store）、`4c1897094f3959cef92302a45a31d368af658380`（居民入口）、`821da1589862189bcc875df037847b6ada574b68`（只读诊断）、`90bce7eafbaab8678b7ba8c01102d18c18931e92`、`88baa3e8131efad52338bd2ae4db32bdc637865d`（回归）、`98e17c8c857777e1e10df5a125ebe2a4e97425f5`（已有日程时不显示误导性“无后续”）、`8bce41414347f8bf0c59271dad53d242c8d2e4d5`（使用真实 resident-daily 来源）、`8b6748ad5f51533e1fe2a5a2303b59782674ab29`（Worker 测试初始化）、`cc7aa0075da8292654c4ebcec62b568dc69fbb22`（改期/撤销仍保留来源）。**代码提交不等于 Android CI/APK 或真机成功**；索引交给现有自动 Refresh code index 工作流并核对生成提交。后续仍需 P0-A 的跨日有效决定复杂恢复，以及 P0-B 非工具消息送达和结果感知后的目标进展证据。


## 2026-10-28 P0-A：主动作成功而附加步骤未完成（源码与回归提交，CI/真机另验）

- **根因**：居民到点 execute 忽略 `executeWakeSteps` 的 completedCount，主动作成功后即写 finished，导致副步骤失败或尚待完成时失去 WorkManager 与重启恢复机会。
- **修复**：execute 只有主动作与所有附加步骤完成才写 finished；否则保留 needs_recovery，沿既有 WorkManager、OrderedCharacterActions 和 ToolRouter 的原 requestId 做最多两次自动恢复，不重发已完成主动作。预算耗尽后在原居民日程卡允许主人**最多一次明确授权的已保存决定续接**，不再请求模型；校验人物、原 dueAt、状态及已完成主动作的真实回执，改期/撤销或证据失效不恢复。失败仍保留原工具拒绝回执。
- **回归**：主动态成功而旧副动作失败、自动预算耗尽、人工恢复不重复发布；已完成主动作只补执行缺失的副动态；日程改期后旧恢复确认失效。**源码提交不代表 CI、APK 或真机通过**。

- **跨日 UI 补充**：居民个人行程原来只列当天和较早的 planned/due，过去已 completed 但副步骤仍需人工续接的行程无法看到按钮；现仅把确实具有已保存恢复资格的旧 completed 行程保留在原日程卡，并使恢复后列表刷新。跨两天完成主动作的回归覆盖旧 dueAt 与不重复主动态。


## 2026-10-28 P0-A：等待复议的原定时钟与独立步骤部分失败（源码提交，CI/真机另验）

- **根因**：`runDuePlanLocked` 在同一已保存 `wait` 决定恢复时重新用当前时间计算缺省 `reviewAt`，导致每次崩溃重放都可能把复议推迟一天；明确 `reviewAt` 若已过期，则原决定被误判为无效而无法排下一次复议。另一个独立断点是 `cancel/reschedule/wait` 的附加步骤完成数被忽略，主选择成功但独立动作失败时仍写 `finished/waiting` 并返回成功。
- **修复**：首次接收合法等待决定时，原模型 JSON 与实际复议时刻在同一持久提交中保存；旧版已保存决定只从原 `dueAt` 还原缺省复议时钟，不根据恢复时间重算。若设备断电错过复议，沿原 WorkManager 日程在约90秒后尽快唤醒进行**新的本人决定**，绝不自动执行原活动或重新购买旧响应。已执行的独立动作仍沿 `OrderedCharacterActions` 原 requestId 幂等。非执行型主选择不因独立动作失败而撤销，但明确标记 `partial_side_steps/waiting_partial`，保留真实工具拒绝回执，不谎称全部完成，也不对结果不确定的私信盲目重发。
- **回归**：跨两天旧版等待决定、明确复议时间已经过去、取消/等待主选择成功但附加步骤拒绝、旧版本不能再次执行；不额外引入常态模型调用。自动测试、APK 与真机验收状态须按对应工作流核实，不能仅凭本记录判定成功。
- **剩余风险**：已取消/改期的旧版本附加步骤如果失败，仍没有独立的人工续接入口；当前保留部分失败事实而不擅自复活原日程。等待条件变化后的主动提前复议另需沿本人已有感知入口设计，不增加无条件付费轮询。


## 2026-10-28 P0-A：居民每日规划的直接行动与多步回执一致性（源码/回归已提交，CI/真机另验）

- **根因**：原 `ResidentDailyDecision.runOnceLocked` 只核验每日模型 JSON 和日程保存，不读取本轮直接发动态、直接私信与 `executeWakeSteps` 的执行结果。即使私信因不认识收件人被真实执行器拒绝，或独立步骤未完成，仍写 `phase=finished` 并返回成功，既有当日恢复入口无法再续接；反之已发出的动态也不能被当成“整轮全部完成”的证据。
- **修复**：每日规划的三个独立动作路径均使用 `ToolExecutionStatus.completed` 或 `OrderedCharacterActions` 的实际完成数量核验，只有全部完成才写 `finished`；否则写 `partial_side_steps`。日程/心理决定仍只保存一次，不重新购买模型响应；重放沿 `native-daily-<resident>-<day>-post/message/extra-N` 原请求 ID，已成功的动态不会再次发送，权限拒绝/结果未知仍保留真实执行回执。原居民日程卡直接显示部分未完成状态；两次自动恢复预算耗尽后，只允许主人在**同一天、同一居民、同一保存版本**明确授权一次“续接已保存的行动”，授权先持久化，不会创建新模型决定或循环重试。
- **回归**：`ResidentDailyContinuityTest.dailyImmediateActionFailureKeepsSavedChoiceAndNeverRepeatsSuccessfulPosts` 以 MockWebServer 验证合法模型决定中直接动态与独立动态成功、陌生人私信被拒绝；重复恢复和一次人工续接后模型请求始终为一次，两个动态各一次，拒绝回执保留、过日或旧确认不能再续接。代码提交 `ad2692ba`、`585d42fb`、`4c60a426`、`7d612967`、`03db6365`、`1d10ec59`、`12c6e20e`。以上为**源码与测试提交**，不等于最新 main Android 单测/APK/真机通过；索引需由既有 Refresh code index 工作流生成并核对。
- **WorkManager 补充**：原 catchup 始终使用同一个 KEEP 名称，正在运行的恢复 Worker 无法为下一次恢复再入队。现在在原定每日 catchup 上使用按已保存决定恢复计数分代的唯一工作名（0/1），每次延迟120秒，恢复预算耗尽不再安排；纯函数回归验证不同恢复代不冲突，仍需 Android WorkManager 实际运行/真机后台调度验收。追加提交 `03367e34`、`bb81ff56`、`c5e4b504`。
- **仍有风险**：真正已发送但服务端结果未知的外部动作不得自动重发；相同请求 ID 的失败结果可能继续保持失败，主人续接并不保证完成。已取消/改期日程的独立失败步骤仍需单独设计不复活旧计划的恢复。跨午夜未完成的“当日即时动作”不自动补发，避免隔日擅自发送过期消息；应由角色在新一日的原有感知/决策中自主复议。真机须核验前台关闭、进程中断、权限变化、重启、重复点击和跨日。


## 2026-10-28 Live2D 视频通话模型与动作接线排查（本次源码提交，未真机验收）

- 模型库与视频通话本来已有上传、全局删除、复用已上传模型和角色单独选择；不重复实现第二个库。修正 `Live2DAssets.libraryModelHealth` 用本机 file URL 而非 Stage 实际 HTTPS 模型 URL 判断校准的错误，要求真正非空校准区域才显示“已校准”；两入口仍通过同一模型设置路径读取原触摸区。
- `LuluVoiceCallSession` 原在分段 TTS/哄睡场景无法生成追加切段方案时，将空的锚点映射传入 `Live2DVisualActionBus.bindSpeechAnchors`，因此删掉模型明明已选择的全部带 anchor Motion。复用已排好的真实 TTS 片段，在 `Live2DSpeechAnchorPlan.bindExistingSegments` 中仅绑定唯一、真实分句起点的锚点，不拆新片段、不猜片段内时间、不追加模型调用。无法定位的锚点仍不执行，不虚构成功。
- 新增校准键一致性/空记录测试、已有 TTS 分段精准匹配/重复与非分句起点拒绝测试。代码索引须依仓库现有工作流生成；Android CI 和真实视频动作效果必须单独核验，不能因静态修复宣称真机已通过。


## 2026-10-28 P0-A：居民结构补正中断的原始请求复用（源码/回归已提交，真机未验）

- **根因**：`ResidentDailyDecision` 原已把第一次无效结构化输出与错误保存到 `invalid/due-invalid`，但人工恢复 `retryDayDecision/retryDueDecision` 无论何种失败都撤掉初始 claim，重新购买一整次“第一次决定”，再可能额外购买补正。第一次答案已经真实收到且不合法时，这会重复收费，也让同一居民的新选择覆盖原来等待补正的决定。自动路径虽然不盲目重试，但人工恢复没有复用已经持久化的补正依据。
- **修复**：每日规划与到点计划共用同一恢复原则：仅在主人显式确认、原始无效答案和校验原因都已持久化、且仍是当前日期/同一日程版本时，保留原请求 claim，直接调用本人既有的一次结构补正请求；不再调用第一次决策。旧答案和原因保留，补正前没有日程、发动态或私信副作用；成功才保存本人完整选择并沿原 `ToolRouter` 请求 ID 执行。中途再次断电仍保持 `repair_requesting`，不会被普通 Worker 自动重买。若第一次请求结果本来未知且没有无效原文，原有显式人工重新决定流程不变。没有新增正常情况下的 LLM 调用。
- **修改**：`app/src/main/java/com/jiacimu/lulu/data/ResidentDailyDecision.kt`；回归 `app/src/test/java/com/jiacimu/lulu/data/ResidentDailyContinuityTest.kt` 增加“到点两次无效→人工只补正一次→独立动态恰好一次、原活动不执行、旧确认失效”与“每日补正阶段中断→沿原始无效答复继续→不提前发动态、只请求一次补正”。源码提交 `f26ab5162be119a5a41fc8308cdd65d03ba13419`，测试提交 `b7d50ed60c256bc0e693de825c77708a01e0c792`。
- **验证层级**：已核实 GitHub 主分支接收上述两项提交；Android CI、APK、索引最终状态以对应 workflow 的实际结果为准，不能把测试代码提交当作单测通过。未有真机复现证据；需在居民日程失败卡用“恢复原决定”操作核验真实请求计数、手机消息/朋友圈回执、重启和改期/撤回。剩余 P0-A 风险：自动确定拒绝与人工补正之间的 UX 说明、失败独立副动作在原日程改期/取消后能否安全单独续接、未知结果的服务端确认，以及跨日未完决定的可见复议；不自动重复发私信。

## 2026-10-29 P0-A：取消、改期、等待后的独立步骤安全续接（源码/回归已提交，CI 与真机待验）

- **根因**：居民到点选择 cancel/reschedule/wait 后，即使独立步骤真实失败，原日程已经取消或更换 dueAt，正常 Worker 与 savedDueRecovery 必须拒绝旧版本。因此 partial_side_steps / waiting_partial 有失败回执却没有可达的续接入口，不能简单恢复整个旧决定，否则可能复活原活动或重发主动作。
- **修改**：ResidentDailyDecision 在非执行型主选择已真实生效但独立步骤未完成时，将当时的日程状态、dueAt、rescheduleCount、原决定时刻及步骤数量与 partial phase 同一事务持久化；仅在本人原始决定首版证据仍有效、当前日程仍是该决定产生的版本、原决定未完成且未授权过时，允许一次人工续接。恢复前持久化授权，直接调用原 executeWakeSteps 和原 native-wake-...-extra-N 请求 ID，不重新购买模型、不执行主活动、不改期。旧证据编辑/撤回、后续用户再次改期、重复点击均失效。既有 ResidentDecisionRecoveryControl 和 CharacterSettingsScreenV2 的个人行程卡显示该入口，包括较早的取消/改期记录；不新增状态中心或自动轮询。
- **根因级回归**：ResidentDailyContinuityTest 新增真实 MockWebServer 取消、改期、等待三种主选择+成功动态+被权限拒绝私信，验证每轮只买一次模型决定、已发动态不重复、原主活动不执行、旧确认只消费一次；另测再次改期与撤回原始决定后不允许续接。提交 ba5f9ccc742d02b089c1a095aec60dfeb532dd7d（执行/测试）、9437ffcab03bea143f6559d9d1934b5b23e7f71d（现有 UI 接线）。
- **待验与边界**：上述仅为源码和测试提交，Android JVM、APK、真机尚须按最新 main 工作流确认；索引由原有 Refresh code index 工作流生成并核实。若进程恰好在日程变更成功与部分失败阶段提交之间被杀死，没有足够原始证据安全确认由哪次决定变更，保守不开放人工续接；结果未知的外部私信仍只能依原 ToolRouter 幂等/回执判断，不保证投递成功。取消/改期后的独立步骤不自动重试。

## 2026-10-29 P0-A：结构补正 HTTP 确定拒绝不得触发首次决定重买（源码与回归已提交，CI / 真机待验）

- [x] **根因定位**：每日规划与到点复议第一次模型答案已收到、结构无效时，原始答案和错误持久化并进入一次同角色补正。但补正 `generate` 随后直接 `.getOrNull()` 丢失 HTTP 401/413 等异常，失败阶段误写 `uncertain`，错误说明仍展示首次 JSON 错误。更严重的是：如果补正确定被拒绝而正确记为 `definite_rejected`，旧自动恢复资格只看阶段与连接摘要，用户换模型后会把**已经付过费的第一次决定**重新购买，覆盖待补正的原始选择。
- [x] **原有请求链修复**：`ResidentDailyDecision.runOnceLocked/runDuePlanLocked` 保留补正请求的 `Result`，分别识别初次请求和补正请求的确定 HTTP 拒绝、未知网络失败与仍不合法的结构输出；失败记录优先显示实际补正网络错误。每日与到点的 claim、配置变化后的安全重试检查同时核验是否已有原始无效回答：存在时普通 Worker **一律不重买首次决定**，仅保留原居民设置页的显式授权补正；没有原始答案的首次确定拒绝仍保持原有「配置改变后最多一次」自动恢复能力。未知请求和已经成功的动作继续沿原安全边界，不添加常态 LLM 调用。
- [x] **跨入口回归已编写**：`ResidentDailyContinuityTest` 新增 MockWebServer「第一次结构无效→补正 401→切换模型→后台/到点入口不再请求→主人明确恢复只补正一次→原始动态/计划不提前执行」的每日、到点两条端到端用例；校验失败阶段、错误原文、原始选择、请求数量、旧确认失效、朋友圈不重复。原有首次 401/413 改连接可重试、502 未知结果不重买的测试继续保留。
- [x] **已提交 main**：源码 `61f8a509d38652dc9d805e22a26dc22fd9dc8d10`，回归 `0500c56235079ceac3e1b3768e619629a2f1094f`。代码索引遵循原有 `Refresh code index` 工作流，必须以自动生成提交/实际 workflow 结果确认，不能手工假造索引。
- [ ] **尚未验收**：本轮 Android JVM / phone APK / 真机及进程被杀、真实供应商 401/413/5xx、后台 WorkManager 的实际恢复行为，须核对最新 main 对应运行结果。下一项 P0-A 优先调查原日程变更与独立副动作回执之间的进程死亡间隙，并验证跨日结果未知时是否有安全的只读确认途径；不能以新的自动重试代替模型决定。


## 2026-10-29 P0-A：日程主变更已落盘、独立回执阶段未落盘的进程中断（源码/回归已提交，CI 与真机待验）

- [x] **根因**：cancel/reschedule/wait 的原决定及独立动作 requestId 已持久化，但 RoleDayScheduleStore 成功修改日程后与 ResidentDailyDecision 的 `due-primary-applied` / phase 是两次不同 SharedPreferences 提交。若在间隙被杀，旧 dueAt 的 Worker 被版本门禁拒绝，新日程又没有恢复标记，导致真实失败的独立步骤永久不可达；仅凭相同时间或标题无法安全归属。
- [x] **源码修复**：RoleDayPlan 增加可选 `lastMutationRequestId`，仅由程序把原 due 决定稳定 key 与取消/改期/等待的日程变更在**同一次日程持久化**写入；普通用户再次改期/取消会清空旧标记。ResidentDailyDecision 的既有居民行程卡恢复入口同时识别原有完整 marker 和“日程已变、phase 仍为 decided”的确切原决定。仍须验证本人原始首版证据、原 choice、日程状态/版本、未用过的人工授权；只续接独立 steps，绝不复活旧活动、重买模型或盲目补发。人工授权与 partial phase 在执行前一起持久化，进程再次中断不谎称 finished。
- [x] **回归源码**：ResidentDailyContinuityTest 用 MockWebServer 覆盖 cancel/reschedule/wait 三类真实模型决定→成功动态+被拒私信→模拟主日程已提交但 phase/marker 丢失→恢复只执行原 requestId，已发动态恰好一次、旧主动作零次、模型请求不增加；另核对后续用户改期、撤回原决定后旧恢复资格失效。
- [ ] **尚待实证**：GitHub Android JVM 测试、APK 构建、索引生成工作流、真实设备进程死亡注入与重启/后台调度；代码提交或 Robolectric 测试编写不能冒充通过。旧版本没有日程内 mutationRequestId 且恰好死在该间隙时，仍无法安全倒推出原决定，保守不自动恢复。

## 2026-10-29 P0-A：跨日未知结果与独立行动历史只读核对（源码待 CI/真机）

- [x] **根因**：原居民行程卡只用当天 `attempt:<resident>` 与当前日程版本寻找恢复按钮；日期推进后，昨日的 `uncertain`、结构补正中断及 `partial_side_steps` 虽仍保留在 SharedPreferences，但从原入口完全不可见。用户无法分辨「角色没选择」还是「模型请求结果未知」，可能误把昨天的选择当作今天要自动补做。
- [x] **修复**：在 `ResidentDailyDecision.historicalAttempts` 只读投影原始每日 `phase:<resident>:<date>` 与本人仍持有的 `due-phase:execute:<planId>:<dueAt>`；按原阶段区分模型结果未知、无效原文、已保存决定及独立动作未完，保留原错误类别。现有 `ResidentDecisionRecoveryControl` 居民卡展示最近六条历史，明确只供核对、不授权补发；只显示确有本人日程归属的到点请求，避免跨角色数据泄漏。只限制屏幕展示数，不删除原历史，不创建状态中心、模型调用、自动补发或跨日重试权限。
- [x] **回归已编写**：`ResidentDailyContinuityTest.pastResidentRequestsRemainReadOnlyAfterMidnightAndAcrossPlanChanges` 覆盖跨午夜请求未知、已保存到点决定、另一居民无效答案隔离、当前日不获得旧日恢复资格、后续改期不复活旧活动/授权。提交与 CI 结果以实际 main SHA 和工作流为准。
- [ ] **待验**：Android JVM/phone APK、真机居民行程卡跨日展示、进程重启、外部供应商未知计费结果仍需核对；历史快照只是原状态与错误的可见性，无法从本机推断供应商是否已经收费或远端消息是否送达。P0-B 应进一步关联原请求 ID 的独立工具回执与本人后续感知。

## 2026-10-29 P0-B：工具原始结果与下一轮选择的同源因果接续（源码/回归已提交，CI/真机另验）

- [x] **根因**：原始时间线详情已有工具回执和本人感知 checkpoint，但只在“直接打开工具回执”时展示 result.evidenceId 的原生结果、持续目标；从触发事件打开时只看到单条回执，无法追踪搜索/阅读之后是否产生新决定。即使后续本人 decision 的 causalEvidenceId 精确指向工具回执，也未接入来源卡。“搜索后没后续”会误以为没有任何选择。旧版没有保存 causeEvidenceRevision 的回执还被直接标成当前修订事件的行动，容易误认编辑后的新文字触发了旧执行。
- [x] **源码**：CharacterCausalTrace 复用已有 ToolRouter 原回执、原生结果 evidenceId、CharacterInnerLifeStore.decisions/motives 和 PerceptionStimulusLedger.peekSeen：来源卡与回执卡均可只读展示同一原生结果、下一轮本人选择、持续目标；已感知但当前历史中未找到精确后续时明确“未检索到”，不冒充主动沉默/目标达成。仅 causeEvidenceRevision 与当前原始事件完全一致时沿因果链接；旧版无版本的回执标为未证实，不沿它串接新决定。不同角色/撤回的原生结果不能串入。无新增模型调用、调度、写入或影子状态中心。
- [x] **回归已编写**：CharacterCausalTraceTest 新增来源卡/回执卡搜索结果一致性、下一轮决定来源、诊断不修改感知 checkpoint、原文编辑后旧回执不解释新文字、已有感知但缺后续不伪造“放弃”、旧无版本回执及跨角色隔离。Android JVM/APK 与真机需按本次提交对应 CI 核实，不能以测试编写代替测试通过。
- [ ] **待验**：真实前台/后台搜索与阅读后的后续决定是否实际保存了精确 causalEvidenceId，历史快照默认最近200条可能不包含更早的决定；需要真机长历史与不同入口检验。此处只读解释事实，不替人物决定是否分享。


## 2026-10-29 P0-B：搜索完成到最终私聊反馈的真实送达回执接线（源码与回归已提交，CI/真机另验）

- [x] **根因**：前台/在线同一角色搜索经 ToolRouter 已持久保存真实搜索回执，后续 `deliverVerifiedSearchFeedback` 虽以独立稳定请求 ID 发送模型写出的结果，却没有传入真实搜索来源。原时间线从用户请求能查搜索回执，但无法判断最后的答复是否真正送达；从搜索回执也无法精确看到该回复。副步骤搜索与主搜索均有同一缺口。
- [x] **修复**：沿现有 `SearchReceiptFeedback` 记录主工具或有序副步骤真实保存的同角色回执 ID（拒绝时也保留真实拒绝 ID），不伪造未落盘来源；原 `ToolRouter` 只对真实已完成的搜索/阅读/屏幕结果回执开放后续动作的来源证明，继续核对本人、版本与原权限。最终结果反馈以原 `:verified-search-feedback` 幂等请求 ID 关联实际搜索回执，不改变其发言内容、模型决定或重试预算。原 `CharacterCausalTrace` 在用户来源卡只读追踪「搜索回执→最终消息回执」的精确来源和 revision，区分送达、失败、等待；搜索回执卡仍按原直接子回执查询。多个结果只标识最后一条有真实持久回执的直接来源，不把其余结果编成精确因果。
- [x] **回归已编写**：原始请求→搜索回执→最终回复、跨角色拒绝、旧搜索结果修订、最终消息撤回与只读查询；检验 ToolRouter 的真实拒绝可把终态搜索回执作为来源，不接受另一人物的回执。
- [ ] **验收**：Android JVM/phone APK、真实搜索提供商及真机在线私聊、搜索副步骤、被新消息打断后的恢复、最终发送失败仍待独立核实。旧版已送达但没有来源 ID 的反馈不回填/猜测，不新增常态 LLM 调用。
- **CI 发现并修复的旧回执诊断缺陷**：#40767210740 的 `CharacterCausalTraceTest.pendingFailureAndNativeResultAreNotMisreportedAsCompletedGoals` 在第174行失败；原先对缺少 `causeEvidenceRevision` 的旧回执，标题被替换成「来源版本未证实」，意外抹掉真实 `pending/failed` 执行状态。现在标题同时保留「来源版本未证实」和实际回执状态，不把来源不确定误报为结果不确定；原测试继续覆盖旧版本/未完成。该修正和本轮搜索反馈改动须以新的 main CI 重新验证，不能用旧失败或仅写测试宣称通过。


## 2026-10-29 P0-B/P4：历史决定不再在第 61 次后永久丢失（源码提交，CI/真机另验）

- [x] **根因**：CharacterInnerLifeStore.recordDecision 原来只保留最近 60 条 decisions，CharacterInnerHistoryArchive 未收纳该字段；超过 60 次决定的原始选择/失败/原因被永久覆盖，旧事件详情即使有精确 evidenceId 也查不到后续选择。诊断默认 200 条快照也无法解决更久远的缺口。
- [x] **修复**：复用同一人物私有 SharedPreferences 的 CharacterInnerHistoryArchive 分页，将 decisions 作为 60 条热窗口 + 全量冷页保存；旧回放 ID 同时核验冷页，避免再次追加相同决定。新增按本人精确 causalEvidenceId 的倒序分页查询，仅在展开原事件因果卡时按需读取，模型普通提示词仍只读取热窗口。新决定同时记录真实来源修订版本，编辑后的文字不能冒充旧决定的原始依据；旧版无版本的决定明确标为来源未证实。
- [x] **生命周期**：忘记单条决定、清除私人心理和删除来源证据时同步处理已归档的 decisions；不动其他人物的原始选择、外部 ToolRouter 回执或长期时间线。
- [x] **回归已编写**：270+ 决定跨页、旧事件按精确 ID 找回、冷页重复 ID 不重放、跨人物隔离、单条撤回、原证据失效、清空心理后不复活。以上为源码和测试，Android JVM/APK/真机仍须按对应提交的 CI 结果核对；原有历史在本次修复前已被 60 条裁掉的部分无法无中生有恢复。

## 2026-10-29 P0-B：长期心念来源诊断与上一轮编译失败修复（待 CI/真机）

- [x] **真实 CI 根因**：上一轮 cce43bdc 的 Android 构建 #40783655315 实际失败在 `CharacterCausalTrace.kt:34`：Kotlin `String.ifBlank` 的 fallback lambda 不接受参数，旧写法 `{ id -> ... }` 造成 `compileDebugKotlin` 编译错误。已改成无参 fallback，并明确外层 decision 变量，避免作用域误判；此前的 APK/单测不能视为通过。
- [x] **因果断点**：`CharacterCausalTrace.inspect` 只读 200 条心理快照中的 thoughts，早期“想分享”虽在 `CharacterInnerHistoryArchive` 归档，展开原始事件时仍可能误报没有心念。现由 `CharacterInnerLifeStore.thoughtsForEvidence` 按本人、精确证据 ID（包括真实重复刺激）分页查原归档；不新建心理 Store、模型调用或动作。
- [x] **来源修订**：新观察的 thoughts 保存当时 `evidenceRevision` 与逐刺激 `stimulusRevisions`；原事件编辑后诊断显示历史版本而不把新文字当旧想法的原因。旧数据缺版本仍可只读查看，但明确“来源版本未证实”。单次删除心念仍使用现有 `forgetExactThought`，归档不复活。
- [x] **回归源码**：`CharacterCausalTraceTest` 新增 90 次后冷页心念召回、没有执行回执不冒充已发动态、编辑后的版本隔离、本人删除后不复活、重复心念多刺激来源、跨人物隔离。仍需 CI JVM、APK、真实设备及更大规模历史数据实测；归档修复前已丢的旧心念无法恢复。
- [ ] **待核验**：本次 Android CI 与原索引自动工作流的最终结果；旧版 thoughts 的第二刺激无修订水位，只能标为来源未证实。心念不是工具请求，不能仅凭文字认定模型已经选择发布或已发送。


## 2026-10-29 P0-B：编辑原始来源时保留历史心念/决定，撤回时真正清除（源码提交，CI/真机另验）

- [x] 根因：上一轮 #40790770149 已完成 1882 项 JVM 测试但失败两项。CharacterCausalTraceTest 的历史版本断言失败，不是诊断 UI 的字符串问题，而是 SharedExperienceTimeline.record 编辑事件时调用 CharacterInnerLifeStore.invalidateEvidence，把原来依据旧版本作出的 thoughts/decisions 连同当前投影一起从热数据和冷归档删除；因此根本没有历史记录可供追踪。另一个 ResidentDailyContinuityTest 使用 UTC CI 当天未来 08:00 创建角色，却让真正发送器以系统当前时刻发信，时间线按出生时刻拒绝保存，历史投影正确地显示 0 封。
- [x] 源码：编辑原始事件时仍撤销全部当前心理/关系/成长投影和感知检查点，但将当时已存在的本人 thoughts/decisions 只放入原 CharacterInnerHistoryArchive 冷页（与热状态同一 SharedPreferences 事务）；普通模型 snapshot 不再包含旧版本心理，展开原事件仍能看到精确来源和历史修订号。真正 deleteEvent/forgetExactThought/clear 仍从热/冷全部撤回；重复刺激引用也参与删除校验，不能借次要刺激复活已删除来源。没有新人物中心或模型调用。
- [x] 回归：原先历史心念编辑测试继续执行；新增本人旧想法+未执行的选择在编辑后仅冷归档可查、不同人物隔离、撤回后全部清除；居民真实私信补正回归使用真实当前时刻而非未来虚拟出生时间，另核对反向联系权限。CI 1882 项中的其他通过结果不能代替本次新构建。
- [ ] 待验证：新的 Android JVM 全套、APK、索引生成、真机长历史/编辑/撤回。历史归档之前已被永久裁掉的旧数据无法恢复；多次编辑及历史页大规模查询仍需专项压力测试。

- CI 校正：#40797339580 在 compileDebugKotlin 失败。根因是新增 retiredHistory 默认参数被放到 save 的最后，破坏原有 Kotlin trailing-lambda 调用形式（如 save(role, root) { field, entry -> ... }），编译器把归档删除 lambda 当成 Map。已恢复原 save 签名，只有需要冷归档追加的编辑路径调用单独 saveWithRetired；不得把这次失败当作测试已通过。下一次 CI/APK 与真机仍待验。


## 2026-10-29 P0-B：工具回执后的决定必须核对时间与原始版本（源码/回归提交，CI/真机另验）

- [x] **根因**：原 `CharacterCausalTrace.receiptContinuation` 仅按 `causalEvidenceId` 找本人历史决定，直接标为「回执后的本人选择」。即使决定的 `at` 早于工具回执 `occurredAt`，或者选择来自编辑前的旧结果、旧版记录根本没有时间/修订版本，也会被误认成搜索后新的行动决定。原始事件卡与工具回执卡共用这段错误解释；结果可能让主人以为「搜索后已经选择分享」。
- [x] **修复**：仅当选择来源版本等于当前真实回执/原生结果版本，且可解析的决定时间不早于该结果的实际发生时间时，才标「回执后的本人选择」。同源但早于结果、缺时间、缺版本或属于旧版本的决定，仍作为本人只读历史显示「关联选择 · 后续关系未证实」并说明具体原因；已有感知检查点但无已证实后续时不把它推断为自愿放弃。复用原 `CharacterInnerLifeStore` 热/冷归档与 `SharedExperienceTimeline` 版本；没有改变模型语义、工具执行或新增付费调用。
- [x] **回归源码**：`CharacterCausalTraceTest` 用真实本人 `recordDecision` 覆盖工具返回前的晚写决定、结果后的有效决定、从原请求卡和回执卡双入口一致展示、跨角色隔离、旧记录缺时间/版本、结果编辑和撤回；原搜索后续模拟决定补上实际时间与版本，避免靠虚构缺失元数据宣称精确因果。
- [x] **main 提交**：源码 `02ec6fe8832c32dc6d1c9bb44db15f2e266dab70`，回归 `08bdccddb51faa192703d5d6820ecad555ec8a89`。索引由仓库既有工作流自动生成，不手改索引。
- [ ] **待验**：本轮 Android JVM 全套与 APK、原有索引工作流、真机原始时间线展开、结果编辑/撤回及多年归档规模；测试提交不等于测试通过。当前只读诊断仍不能替人物决定是否继续分享，也不能推断远端未知请求的真实送达。


## 2026-10-29 P0-A：已保存工具结果必须先于当前权限门禁恢复（源码与回归提交，CI/真机待验）

- [x] **根因**：ToolRouter.execute 原先在读取 `lulu_tool_executions` 的同角色同 requestId 已保存执行结果之前，先检查当前能力是否仍存在、是否停用及此刻授权。一次真实成功的设备读取/外部动作在角色权限被撤回或能力退役后重放，会被错误写成新的“等待授权/停用”拒绝，原回执丢失的进程中断间隙也无法修复。旧结果是历史事实，不是要求再次执行。
- [x] **跨入口修复**：在原请求级互斥与能力级互斥内，先读取持久化请求、核验原工具/参数 fingerprint 与 motive/concern/experience 关联，再只读重放已完成结果及修复原 ToolResult 回执和必要感知唤醒；无结果的非 retrySafe running 保守报未知。仅新请求及 retrySafe 的未完成请求进入当前 capability/权限/日程门禁，权限撤回不能借旧 ID 执行新副作用。复用原 ToolRouter，无额外模型调用。
- [x] **回归源码**：CharacterUnfinishedActionContextTest 覆盖设备读取已保存结果→自主权限撤回→两次跨入口重放且只补一个原回执；新请求仍拒绝；已退役日记历史重放而新请求不可执行；retrySafe running 在撤权后不可继续执行；同 ID 不同参数不被重放。真实设备权限变更、进程死亡及 Android 全套 JVM/APK 仍须单独验收。
- [ ] **待验**：当前 main 的完整 CI、APK、真机重启和历史回执投影；已保存结果重放不等于证明远端未知结果成功。原请求若已损坏或旧版没有保存结果，仍需人工核实，不得盲目重发。


- **前一轮 CI 失败及回归契约校正（2026-10-29）**：Lulu APK #40803125576 已完成且 failure（1885 项 JVM 测试中 1 项失败）。`CharacterCausalTraceTest.editedResultAndLegacyChoiceNeverBecomeVerifiedFollowUp` 原测试以同一个 tool-router ID 改写执行回执，再要求该回执继续存在，与原生结果修订后依赖回执撤回的事实生命周期不符。回归改为编辑原生搜索结果：验证旧 ToolRouter 回执被撤回、历史决定只读标旧版本、无版本选择不能冒充当前结果、删除后全部失效。旧失败不能视为通过，须以新 main CI 重新核实。


## 2026-10-29 P0-A/P1：工具请求重放绑定原始来源与日程时间版本（源码与回归提交，CI/真机另验）

- [x] **源码根因**：`ToolRouter.execute` 的持久请求去重只校验 `requestId + tool/args fingerprint + motive/concern/experience`，没有校验当初真实的 `causeEvidenceId` 或计划 `expectedPlanDueAt`。跨入口迟到回包/重复 ID 即使带着另一条聊天输入或新改期时间，也可能恢复旧成功结果，误以为本次决定已执行；对于 `retrySafe` 的 `running` 请求，来源编辑/撤回后仍可能沿旧请求重新执行，违背当前版本事实。
- [x] **统一执行层修复**：保留 ToolRouter 的唯一执行与幂等中心，不加收费模型调用。新请求将 `expectedPlanDueAt` 与原有已验证 `causeEvidenceId/causeEvidenceRevision` 一起持久保存；重放先比较当初请求的来源 ID、日程时间版本与原有参数/目标指纹，不一致返回 `waiting_user`，不重建旧回执也不执行新副作用。仅当原请求是 `running` 且工具声明 `retrySafe` 时，进一步要求原证据仍存在且 revision 未变；已完成的旧结果仍可在原请求身份下恢复，不因后来编辑/撤权/停用而改写历史。旧版未存新增时间版本字段的记录继续按旧身份回放，不能推断它具有新的精确日程版本证据。
- [x] **根因级回归源码**：`CharacterUnfinishedActionContextTest` 新增同一工具/参数/请求 ID 被不同来源或无来源重放时拒绝、正确原来源可恢复一次历史回执；另以真实 publish_moment 验证已发布动态在另一输入重放时不会再次发布、旧请求仅恢复原回执；原来源修订/撤回后 `retrySafe running` 不执行；改期后的 `expectedPlanDueAt` 不得借用旧已完成结果，旧时刻仍可只读恢复。既有撤销权限后旧结果重放、退役工具历史、相同参数幂等回归继续保留。
- [ ] **分层验收**：本轮新 main 的 Android JVM 全套、APK、原有 code-index workflow 与真机私聊/电话/居民跨入口重复请求、改期和进程中断分别核对；源码/测试提交不等于真机通过。历史记录未持久化来源字段的旧版请求无法补造精确来源；未知远端结果仍不得盲目重发。

## 2026-10-29 P0-B：自主选择先持久化，再执行多步动作并独立核对请求（源码已提交，CI/真机待验）

- [x] **真实根因**：`ProactivePerceptionRuntime` 原来在 `performAction` 完成所有主动作、副步骤及搜索反馈之后才调用 `CharacterInnerLifeStore.recordDecision`。若进程在 ToolRouter 已保存某项请求/回执之后死亡，独立动作可能真实发生，但人物的“本人选择”记录根本不存在；只看心理记录会误以为没有选择。即使最终正常保存，旧决定仅有模型动作签名而无原 ToolRouter 请求 ID，诊断只能按来源事件粗略并列回执，无法区分同一次决定的主动作和第 N 个步骤。
- [x] **源码接线**：在现有自主模型返回且执行前，按原 `shadowRequestId` 稳定身份将真实选择、理由、来源 revision、主动作存在性和有序步骤数量存入本人原 `CharacterInnerLifeStore.decisions`，初始 `executionState=pending`；随后仍由唯一 `ToolRouter` 与 `OrderedCharacterActions` 执行。执行器返回后只补写同一决定的 `reported` 状态与结果，不改写原选项；迟到回包遇来源编辑/撤回不能完成旧版本。冷归档中待完成的决定也能在原页原地原子更新，避免 60 条热窗口挤走后永远停留 pending。无新 Store、强制模型调用或副作用补偿。
- [x] **只读诊断**：原 `CharacterCausalTrace` 沿选择记录的 `executionRequestId` 精确查询本人主动作与 `:step:N` 原工具/拒绝回执；结果成功、执行前拒绝、running/结果未知、持久请求已保存但回执缺失、尚无任何执行请求分别显示。旧记录缺精确 ID 不猜测；来源历史版本、回执时序缺失不冒充当前执行。仅查看详情不会重试或触发感知。
- [x] **回归已编写**：`CharacterDecisionArchiveTest` 覆盖 85 条后冷页 pending→reported、旧结果二次写入拒绝、角色隔离、来源编辑/撤回后不允许迟到回包完成；`CharacterCausalTraceTest` 覆盖同一真实决定的主请求被拒、独立第1步有成功回执、第2步持久 running、本人选择结果回写、另一人物隔离、只读诊断不消费 running。原 ToolRouter 重放和动态恰好一次回归继续保留。
- [ ] **仍待实证**：Android JVM 全套、phone APK、既有索引工作流、真机后台进程死亡注入和在线私聊/静默感知的真实多步执行。模型返回后、选择持久化前的极短中断尚无决定证据；旧版决定没有 executionRequestId 不能反推原请求；UI 当前只读展示，不会替人物自动补做。完整的前台/电话入口预提交一致性仍是 P1 待办。


## 2026-10-29 P1/P0-B：电话与前台决定预提交、真实步骤 ID 追溯（源码/回归待 CI 与真机验收）

- [x] **根因**：后台自主感知已在执行前保存本人选择，但电话 `LuluDeviceToolBridge` 的逐步手机动作只保存心理状态，真实工具调用前没有可追溯的本人步骤选择；前台 silent+steps 虽记录“自愿保持安静”，却在工具执行前写成 reported，导致进程中断后误以为决定已总结。电话现有请求 ID 为 `reply-side-<source>-<index>`，而 P0-B 诊断只假定 `:step:<index>`，无法精确核对真实电话回执。前台单工具动作同样在执行前缺少选择记录。
- [x] **源码**：在原 `LuluDeviceToolBridge` 电话 sideActions 逐步执行之前保存本人步骤选择及全部既有稳定请求 ID，完成后补写真实执行总结；不改旧 ID、不重放副作用。前台 silent+steps 改为 pending→实际 OrderedCharacterActions 回执后 reported；单工具选择也在原 ToolRouter 前持久化，完成后回写，不把后续表达成功当作工具成功。唯一 `CharacterInnerLifeStore.recordDecision` 增加可选 `executionStepRequestIds`，原 `CharacterCausalTrace` 优先按该列表读取本人真实回执，旧数据仍沿 `:step:N` 兼容；没有额外 LLM 或第二套状态中心。
- [x] **回归源码**：`CharacterCausalTraceTest` 验证电话旧 ID 两步成功/失败、跨角色隔离、编辑/撤回与迟到完成拒绝；`CharacterDecisionArchiveTest` 验证冷页 pending→reported 后原请求 ID 列表不丢失。原有 ToolRouter 重放及前台有序动作测试保留。
- [ ] **未验收**：最新 main 对应 JVM 全套、APK、代码索引生成及真实电话/前台私聊/群聊、多步中途强杀、改写来源后的迟到回执；模型返回至首次持久化的间隙仍需单独处理。电话动作仍沿现有音频场景的执行分支，未改为新的强制执行器；旧记录缺精确步骤 ID 不猜测补链。


## 2026-10-29 P1：群聊迟到回复与逐气泡发送的来源水位（源码与回归已提交，CI/真机待验）

- [x] **源码根因**：`runGroupReplies` 在群成员调用模型前取出待回复用户消息，但模型返回后未重新校验这些消息是否被编辑、撤回，或期间是否出现用户的新纠正；`appendRoleReplyWithPacing` 在每个气泡前有真实的打字延迟，却只检查协程是否活跃。结果可能在主人已经纠正后继续发送针对旧原话的长回复，群聊的后续成员与附加工具也可能跟着旧决定继续。旧消息即使编辑后恢复同样文字，其原时间线 revision 仍已变化。
- [x] **共享只读水位**：`QqChatReplyEngine.kt` 新增 `ReplySourceWatermark`，在群成员模型调用**之前**保存当前会话、本人角色、用户消息 ID/原文及其 `SharedExperienceTimeline` 真实 revision、最后一条用户消息 ID。按当前私聊所有权或群成员身份核验；模型返回后、逐个气泡发送前都只读重新检查。旧来源编辑、删除、新用户消息、群成员退出都使旧结果失效；其他角色已送达的群聊消息不会凭空作废同一用户原话。前台/在线通过原共用逐气泡发送器至少在开始打字和每个气泡前检查真实来源；未带来源的自主独立发言不强行变成用户回复。未增加影子 Store、模型调用或语义关键词。
- [x] **中断与副作用边界**：旧回复失效时不继续追加气泡、不收藏、不戳人、不执行群聊旧决定的 deferredActions；已真实发出的前几个气泡仍保留原消息历史，不撤回已送达事实，也不将整轮标记为完整送达。下一条新消息仍由现有聊天入口自行感知处理，不因旧结果失效自动再购买一次请求。
- [x] **根因回归源码**：`QqReplySourceWatermarkTest` 覆盖私聊原文编辑后恢复仍失效、新用户纠正、非用户角色消息不影响水位、不同群成员独立证据修订、群成员退出/跨角色隔离、撤回，以及真实逐气泡打字延迟期间新增用户纠正而零旧气泡送达。
- [x] **跨入口回执修复**：共享发送器失效后返回未完成标记；原 `CompanionActionRuntime` 的 `send_private_message` 与 `send_group_message` 曾忽略返回值，只要调用没有抛异常就写成功。现在核对真实新增消息 ID 和完整发送结果：零送达报失败，部分气泡已送达但被纠正打断时保留真实消息并报部分失败，不把它当成整轮成功，也不继续显示计划提醒。新增动作执行器回归，验证在打字延迟中主人纠正后零气泡送达不能返回成功。源码 `c02ee1f2`、回归 `38b28c2a`；Android CI 与真机仍独立验收。\n- [x] **群聊继续发言门禁**：主人在已送达第一个气泡与后续气泡之间纠正时，除停止旧气泡，还停止原群轮次的后续成员和 afterReply 回调，电话场景也不能以未送达的旧文本继续触发回调；追加中途纠正保留第一条真实消息、阻断第二条的时序回归。源码 `44e0f9b9`，测试 `fcf55f1f`。\n- [ ] **分层验收**：本轮 Android JVM/APK 与原 code-index 工作流需核对对应 main 提交；真机测试群聊在模型思考期间编辑/撤回原话、发新纠正、两气泡间插入新消息、角色退出群后迟到回复。群聊未带用户消息来源的纯角色自主交流不在此门禁范围；前台 planner 在进入共用发送器**之前**的所有非工具回复水位尚需继续排查，不能宣称 P1 已全面完成。并发发送的微小检查/写入间隙仍需后续在持久消息提交层加原子版本门禁。


## 2026-10-29 P3 分段表演发布验收

- 正式源码提交 `10bb37bfeb620b554c5983d8e16024e2f5ab2327` 已进入 main；已合并保留同期聊天/群聊来源修订修复。既有索引工作流 #40833765638 completed/success。
- Android 完整工作流 [Lulu APK #40833765696](https://github.com/jiacimu-droid/lulu1/actions/runs/40833765696) completed/success：Live2D JS/打包、Android JVM 测试、phone APK 构建、APK 字节码核验及正式发布均成功。不能把这项 CI 成功表述成已完成 Android 真机体验。
- 已发布 [Lulu v6605 ARM64](https://github.com/jiacimu-droid/lulu1/releases/download/lulu-v6605/Lulu-arm64.apk)，构建来源为上述源码 SHA，产物 28,732,888 字节。
- 真机仍待验：同一段三句话连续动作、重复句首/声音标签、插话取消未播动作、触摸后交还、15/30FPS、真实首音与跨 TTS 片段的音色连续性；词级时间戳、任意动作关键帧保持与动画曲线逐采样重定时没有在本轮实现。

## 2026-10-29 P1：来源水位校验与聊天落库的原子提交（源码已提交，CI/真机待验）

- [x] **根因**：群聊/私聊的逐气泡发送器在打字延迟后验证用户原话 revision，但从 `isReplySourceWatermarkCurrent` 返回到 `appendCharacterMessage` 实际落库之间没有共同锁。用户可能恰在此间编辑、撤回或补发纠正，旧回复仍被保存；单纯再加一次检查不能消除竞态。
- [x] **唯一权威层修复**：在现有 `InMemoryLuluChatStore` 的同一消息锁内完成最后的只读水位核验与真实消息提交，逐气泡发送器改走 `appendCharacterMessageIfSourceCurrent`；同一会话的用户发送/编辑/删除/群成员变更均使用该锁。先完成纠正则旧气泡不落库；先完成发送则该气泡保留真实历史，后续气泡继续重新校验。模型、语义、消息 ID、原时间线、ToolRouter 与独立回执均不另建中心。
- [x] **根因回归源码**：旧消息修订、新用户纠正时提交门禁拒绝；两线程以锁内栅栏重现“验证和写入之间试图编辑”的并发，核对编辑只能在已落库气泡之后完成且旧水位随即失效；原有群聊逐气泡中断、来源撤回及角色隔离回归继续保留。
- [ ] **待验证**：新 main 的 Android JVM/phone APK/索引生成与真机打字延迟中编辑、撤回、第二气泡前纠正；底层消息持久化仍有可见会话使用异步 SharedPreferences.apply 的既有耐久性差异，进程强杀与长会话持久化应另测。本轮未新增常态 LLM 请求、后台重试或影子状态。

- **同锁版本补强**：`editMessage` 原来先在聊天锁内改文字、退出锁后才更新 `SharedExperienceTimeline` 的 revision；连续编辑又恢复原文时，另一线程可能在 revision 尚未更新的空隙把旧模型回复当成有效。现将聊天正文修改和来源 revision 提交放在同一消息锁内；撤回/新消息原有存储锁保持不变，避免“原文恢复但证据版本未变”的竞态。

## 2026-10-29 P0-A：居民已保存每日规划的原时刻恢复与拒绝回执（源码/回归已提交，CI/真机另验）

- [x] **根因**：每日决定已在副作用前保存，但 `saveAgenda` 重放时用恢复执行的当前时钟重新解释模型给出的 HH:mm。若原 08:05 选择 09:00、09:00 已入日程，10:05 进程恢复可能把原计划误记为「时刻已过」；若某项结构合法却被当前联系人/群成员/资源事实拒绝，旧 `runOnceLocked` 只看直接动态/私信/steps 成功便把整轮标 `finished`，把「本人选了但没排上」冒充全部完成。
- [x] **真实接线**：接受模型决定时在原 `lulu_resident_daily_decisions_v1` 同一持久事务保存 `decision-at:<nativeId>:<day>`；后续重放按原决定时刻解析 HH:mm。先在权威 `RoleDayScheduleStore` 精确匹配同人物、同来源及 revision、原时刻、动作、标题、动机和参数的历史计划（包括主人后来取消的计划），只读复用而不重新创建闹钟；未入库且已过时的动作明确留下本人原始日程拒绝事件，不能补发、自动改期或替模型选新时间。旧版缺 `decision-at` 的记录不补造过去时间，继续保守处理。
- [x] **结果事实**：每日最终阶段纳入 `agenda` 的实际保存数量；直接动作尚未完成仍是 `partial_side_steps`，独立动作完成但有日程拒绝则是 `finished_with_agenda_rejections`，在历史只读诊断中保留拒绝数量与原始事件。此阶段不自动重购模型、重排已过时动作；`hasSavedDayToResume` 仅对已保存且真正可继续的 `decided/partial_side_steps` 开放。仍复用原模型选择、唯一日程 Store、原工具幂等和事件详情。
- [x] **回归源码**：`ResidentDailyContinuityTest` 增加原 08:05 计划 09:00、10:05 重放只复用原计划 ID、主人取消后不复活；结构合法但当前不允许联系的日程被拒，不能标 `finished`、不能再次收费，原角色拒绝事件与次日历史状态可查询。
- [x] **main 提交**：`72ba6bdb0978c178e2ace4b560b41318334ad956`（源码）、`21e1d56dfe55cfe3a88498e6cd110164bbb5f3c9`（回归），`6797e6290ad760c8ca57dc263f03f79c1152f774`（补充强杀后未入库且过期的计划不得补排）；索引遵循现有 code-index 自动工作流，不手工伪造。
- [ ] **待验**：本次 HEAD 对应 Android JVM 全套、APK、自动索引最终结果及真实设备强杀/过时计划/撤权后恢复。无法恢复旧版本从未保存的原始决定时刻；对已过期且原本没有成功入库的计划，只记录失败并交给下一次本人模型轮次决定，不擅自补执行。


## 2026-10-30 P4：删除居民日程后的原到点请求历史归属（源码已提交，真机待验）

- [x] **根因**：旧到点请求虽然仍保留 `due-phase:execute:<planId>:<dueAt>`、错误和模型决定，但 `historicalAttempts` 只通过当前存在的本人日程反查属主；用户删除日程后，原请求的未知计费/失败历史从本人界面消失。旧请求 ID 本身不能证明归属。
- [x] **根因修复**：在原 `ResidentDailyDecision.claimDueRequest` 的 claim/phase 同一持久提交中保存本人 `characterId`、原 `planId` 和 `dueAt`；只读历史校验来源元数据与请求 ID 一致后恢复已删除计划的原请求。没有元数据的旧请求仍只能凭存在的本人日程确认，不猜测、不新建状态中心、不恢复已删除计划或重复购买模型决定。
- [x] **回归源码**：真实 MockWebServer 502 未知结果→删除原日程→隔日历史仍归本人→另一居民不可见→只读查询不修改状态、旧动作不重发；旧版无来源元数据的删除情形保持保守。源码 `c26a9df50423c371e6b3ebf779f0963e06328205`，回归 `ce57e3eb750a27bd04e4ad3d94c81f686ed3b4f7`。
- [x] **索引**：原 Refresh code index [#40968872611](https://github.com/jiacimu-droid/lulu1/actions/runs/40968872611) completed/success；生成物已进入 main，未手工修改。
- [ ] **Android JVM/APK 与真机**：本轮回归提交的 Lulu APK [#40968872681](https://github.com/jiacimu-droid/lulu1/actions/runs/40968872681) 被并发提交取消；源码提交的 [#40968831378](https://github.com/jiacimu-droid/lulu1/actions/runs/40968831378) 最后核实时仍在运行且未进入 Android 测试。需要等待包含本轮代码的后继 main 构建完整通过，并真机验证 502/强杀/删除日程/跨人隔离。长期数据规模与旧版已删计划无归属的历史仍是明确边界。
- **协作**：本轮仅基础连续性源码/回归及本专项进度，不修改社交、内驱或 Live2D 专项；详细记录见 `CONTINUITY_BASE_PROGRESS_2026_10_29.md`。


## 2026-10-30 P1/P4 增量验收：当天已终止的居民决定历史诊断（源码已提交，CI/真机待验）

- 基线复核：原来源版本/撤回门禁、日程拒绝回执、跨日 `historicalAttempts` 和本人到点请求来源持久化均已有正式实现，不重复开发。最新已完整成功的先前主线构建为 [Lulu APK #40977895938](https://github.com/jiacimu-droid/lulu1/actions/runs/40977895938)（`fa116576`）；本轮不借其证明新增回归通过。
- 真实缺口：今日 `source_unavailable`（每日/到点）和 `finished_with_agenda_rejections` 在旧历史投影被“必须早于今日”的筛选漏掉，又没有对应可重试控制；原始失败事实当日不可见，次日才出现。改为当天只读展示这些终止/拒绝事实；其他进行中或计费未知请求仍走原今日恢复入口，未来键、异人归属及旧日程权限边界不变。
- 源码提交 `88cb1f34`、`9bdb4f07`；回归提交 `2ef3c0ed`、`6e3185e7`。用原 `ResidentDailyContinuityTest` 覆盖今日每日撤回、今日到点撤回且原计划已移除、今日日程拒绝、今日未知计费仍单独恢复、未来/异人不可见、跨日只读无副作用。只改本专项文件与现有诊断文案，不增加模型调用或独立状态中心。
- 2026-10-30 08:55（北京时间）对应 [索引 #40982737035](https://github.com/jiacimu-droid/lulu1/actions/runs/40982737035) 与 [Android/APK #40982736974](https://github.com/jiacimu-droid/lulu1/actions/runs/40982736974) 尚在队列，**源码/回归已提交不等于自动测试或 APK 通过**；索引由原工作流生成，不手工改生成物。真机当天撤回后页面刷新、强杀、删除计划及多年数据性能仍待验；细节与后续状态见 `CONTINUITY_BASE_PROGRESS_2026_10_29.md`。

- **2026-10-30 08:57 真实工作流复核**：上述 [索引 #40982737035](https://github.com/jiacimu-droid/lulu1/actions/runs/40982737035) 已 `completed/success`，自动索引提交 `55eb6035` 进入 main；[Lulu APK #40982736974](https://github.com/jiacimu-droid/lulu1/actions/runs/40982736974) 已 `completed/failure`，build job `123826434592` 日志明确为同期 Live2D `exp3` 的 JS 回归断言 `real current exp3 failure releases eye ownership` 失败，Android JVM/assemblePhone 尚未执行。该阻断属 Live2D 专项，不在基础专项越界修改；待其修复后的包含本轮代码的新 main CI 才能验收本轮 Kotlin 回归/APK。真机未验。


## 2026-10-30 P0-B/P4：个人日程完成记录的原生结果归属诊断（源码与回归已提交，CI/真机另验）

- [x] **真实缺口**：旧 `CharacterCausalTrace.inspect` 只要日程状态为 completed，且 `resultEvidenceId` 在本人时间线仍存在，就显示「日程执行有回执」。用户自己的消息、另一日程成功回执、失败工具回执、旧时间事件也可能被误当成本计划的执行证据。已有日程、ToolRouter 与时间线不重复开发。
- [x] **实际修复**：只读个人日程卡要求 `tool-router` 真实成功 `ToolResult` 的 `planId`、动作和到期时间匹配，才显示独立核对的执行回执。其他记录保持历史和链接，但只能显示「日程状态待核对」；原生动态/观察的真实记录可查，不再把其存在自动等同于本日程执行成功。保留旧来源版本警告、取消状态与原存储，不自动恢复/重发/额外购买模型。
- [x] **根因回归源码**：`CharacterCausalTraceTest.completedScheduleCannotBorrowUnrelatedOrFailedResultAsVerifiedReceipt` 覆盖本人用户消息、他计划成功、工具失败、本计划真实成功、原生结果未独立归属与跨角色隔离。源码 `f5d211b9`；测试 `00d1ff49`、`05cf4581`。基础专项详细进度见 `CONTINUITY_BASE_PROGRESS_2026_10_29.md`。
- [x] **已有索引证据**：源码提交对应 [Refresh code index #40994662294](https://github.com/jiacimu-droid/lulu1/actions/runs/40994662294) completed/success；测试更新后的生成物需继续核对原工作流。
- [ ] **仍待验证**：包含最终测试提交的 Android JVM 全套、phone APK/发布、真机真实发布与旧日程详情、进程强杀/撤回以及多年历史性能。前一个主线 [APK #40993494707](https://github.com/jiacimu-droid/lulu1/actions/runs/40993494707) 的实际失败属于并行内驱 `CharacterCuriosityRuntimeTest`，不越界修改；失败/取消/排队不能当作本轮成功。
