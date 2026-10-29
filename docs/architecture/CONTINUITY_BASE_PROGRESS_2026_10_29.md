# 统一人物连续性 · 基础闭环专项进度

本文件只记录 P0-A/P0-B/P1/P4 基础执行恢复、来源版本、跨入口回执诊断和长期数据生命周期的增量工作。总约束仍见 [CONTINUITY_EXECUTION_PLAN_2026_10_28.md](CONTINUITY_EXECUTION_PLAN_2026_10_28.md)。不覆盖社交、内驱想法和 Live2D 专项进度。源码已提交、CI 成功、APK 构建成功、真机通过是四种独立证据。

## 2026-10-30 · P0-A 日计划先存来源、再排程的撤回并发边界

- **已核实根因**：`ResidentDailyDecision.runOnceLocked` 原来先持久化模型 JSON，再调用 `saveAgenda` 修改唯一 `RoleDayScheduleStore`，最后才写 `resident-daily` 原始决定事件。进程在排程后、写原始来源前终止时，日程虽带 `originEvidenceId`，却暂时没有可核验的源事件；主人在多项排程中编辑/撤回来源时，原来只在整轮开始检查一次，后续行仍可能写入。稳定 ID 和事后补事件不足以保证来源当时有效。
- **源码修复**：先在已保存 JSON 之后、任何日程/个人状态副作用之前，使用原 `SharedExperienceTimeline` 的同步监视器建立 revision=1 的决定证据；其内容明确区分“已选择”与“真正排入/执行”，不再提前声称成功。重放只读取既有来源，不覆盖编辑版本或撤回墓碑。将当时完整的 `SharedTimelineEvent` 传入 `saveAgenda`；逐行检验来源，在每次 `RoleDayScheduleStore.arrange/cancel` 与排程拒绝写入时复用已有 `commitIfEvidenceUnchanged` 监视器，保证主人编辑/撤回与该行持久提交有确定先后。途中失效立即停止后续行、标记 `source_unavailable`，不将剩余行误判成普通拒绝，不重购模型。原先直接调用 `saveAgenda` 的测试/旧入口保留兼容默认参数。
- **根因回归源码**：`ResidentDailyContinuityTest.residentAgendaCannotWriteUnderEditedWithdrawnOrForeignDecisionSource` 先以有效来源安排一项，随后编辑 revision、撤回源事件、改用另一人物的证据，三种情况下均不得补排新行；原先已成功保存的日程仍存在，不被错误删除。旧日程重放、拒绝墓碑与来源失效的恢复测试继续保留。
- **main 提交**：[`877d00e028b31cb774eda01e59059053406ce94f`](https://github.com/jiacimu-droid/lulu1/commit/877d00e028b31cb774eda01e59059053406ce94f)（根因修复）、[`ca0251214f2553ebbd7d3c506df6abcd41fe19ef`](https://github.com/jiacimu-droid/lulu1/commit/ca0251214f2553ebbd7d3c506df6abcd41fe19ef)（Kotlin 回调类型）、[`42795b626e5c5e8c410f0dcaea15be35c497f867`](https://github.com/jiacimu-droid/lulu1/commit/42795b626e5c5e8c410f0dcaea15be35c497f867)（回归）。只改本专项的居民执行器、回归和进度文件，索引由仓库原工作流自动生成。
- **分层验收**：截至本条记录，[Refresh code index #40935673189](https://github.com/jiacimu-droid/lulu1/actions/runs/40935673189) `completed/success`，自动索引已写入 main；对应 [Lulu APK #40935673198](https://github.com/jiacimu-droid/lulu1/actions/runs/40935673198) 仍为 `pending`，JVM/APK 未能宣布通过；早期源码提交的构建被并发更新取消，不算通过。真机强杀、主人编辑与真实 WorkManager 并发、多日规模均未验证。此前其他专项的 `abacc32ac` 构建因 `CompanionOnlineStore.kt:410` 的 Kotlin 可空布尔类型不匹配失败；最新源码已变更，须以最新构建重新判定。
- **剩余风险**：日计划原始证据与日程使用不同持久 Store，跨 SQLite 与 SharedPreferences 的整轮事务尚非单一数据库原子提交；本轮确保来源先落盘、逐行提交与编辑/撤回串行化，但不能将跨存储故障解释为所有动作必然完成。ToolRouter 的执行中断/回执仍沿既有幂等机制，后续需设备故障注入。

## 2026-10-30 · P0-A 原决定来源撤回/编辑后的恢复禁行

- **源码根因**：原居民每日决定持久保存后，`runOnceLocked` 恢复时会先调用 `saveAgenda`，随后即使原 `resident-daily` 事件被主人编辑、撤回，仍可使用空 `causeEvidenceId` 继续发布动态、私信与独立步骤。到点 `runDuePlanLocked` 的 `dueDecisionEvidence` 虽会在编辑/撤回时返回空 ID，执行器仍继续使用无来源的 ToolRouter 请求或修改日程。已有工具幂等只防重复请求，不能把来源撤回当作继续执行的授权。
- **实际修复**：`ResidentDailyDecision.kt` 复用 `SharedExperienceTimeline.eventsByIds` 和原撤回墓碑查询，恢复每日决定时先核对来源归属、source 和 revision=1；已编辑/撤回的原决定不得继续排程或执行未送达动作。到点决定在写入/找回原 `resident-due-decision` 事件后必须取得真实 revision=1 的 ID，才能执行主动作、独立步骤或改期/取消/等待。来源不可核验时保存原 prefs 中的 `source_unavailable` 及错误说明，不删除旧决定和既有真实回执，不再次购买模型决定，也不新建状态中心。
- **根因级回归**：`ResidentDailyContinuityTest.withdrawnOrEditedResidentDecisionCannotResumeUnsentEffects` 覆盖已保存每日决定的原来源编辑、已保存到点决定的原来源撤回，断言不发旧动态、不执行独立动作、不把原日程标记完成、再次恢复不重放、撤回墓碑不复活、旧决定仍保留可诊断状态。原先合法且来源未撤回的保存决定恢复测试继续保留。
- **main 源码提交**：[`4e1798482f3e1130848a11a54f6071d974859382`](https://github.com/jiacimu-droid/lulu1/commit/4e1798482f3e1130848a11a54f6071d974859382)；**回归源码提交**：[`651de1f6a035040b3e51b48ef94bb755a0af9a46`](https://github.com/jiacimu-droid/lulu1/commit/651de1f6a035040b3e51b48ef94bb755a0af9a46)。自动代码索引提交 `b15787f4d96fc192669c73e8fa3d963220e1e07e` 的父提交为本轮测试，仍须以工作流结论为准。
- **分层验证**：本轮测试提交对应 [Refresh code index #40917194990](https://github.com/jiacimu-droid/lulu1/actions/runs/40917194990) 与 [Lulu APK #40917195008](https://github.com/jiacimu-droid/lulu1/actions/runs/40917195008) 已复核：索引工作流 completed/success，自动索引已提交；APK 工作流 completed/cancelled（并发新提交触发取消），未运行至可判定的完整 JVM/APK 结论，不能算通过。此前 `eb729589` 的 [APK #40914143501](https://github.com/jiacimu-droid/lulu1/actions/runs/40914143501) completed/failure，实际 2029 个 JVM 测试中 `ConversationExpressionGatewayTest.bestFriendIdentitySurvivesPrivatePlannerAndLeanRendererWithoutSharingOtherRolesStyle` 失败，属其他角色人设专项，未越界修改。真实手机强杀/撤回并发、Android 后台与多日运行均未验证。
- **保留风险**：日决定原始事件在 `saveAgenda` 后才记录，撤回与排程写入若恰好并发，仍需进一步做原始时间线/日程的原子边界故障注入；本轮先阻止已撤回/已编辑的持久决定在后续恢复中产生新副作用。没有改公共 ToolRouter、社交、内驱或 Live2D 专项。

## 2026-10-29 · P0-A 居民被拒绝日程不得因权限恢复而偷排

- **真实根因**：`ResidentDailyDecision.saveAgenda` 记录结构合法但现场权限/资源不满足的行时，使用稳定 `native-daily-...-agenda-rejected-N` 原始事件 ID；但下一次恢复同一已付费模型决定时，只核对原已保存日程，未把该行的既有拒绝回执作为终态。联系人后来出现、群成员权限变化或资源恢复后，原来已被拒绝的行可能变成新日程；同一拒绝再次写入还可能因为新的 `occurredAt` 改写 revision。即使主人撤回拒绝事件，原始墓碑仍应阻止旧决定复活。
- **源码修复**：在唯一 `SharedExperienceTimeline` 增加只读 `hasRecordedOrWithdrawnEvent(eventId)`，查询现有 `timeline_events` 与 `deleted_timeline_events`，不新建状态中心；`ResidentDailyDecision.saveAgenda` 先核对本次稳定拒绝 ID，已拒绝/已撤回的行不再安排或重新写入回执。其他从未被拒绝的行仍可按原保存决定继续恢复；原先已安排的行仍沿权威 `RoleDayScheduleStore` 幂等恢复。
- **回归源码**：`ResidentDailyContinuityTest.rejectedAgendaNeverBecomesExecutableAfterContactRestoredOrReceiptWithdrawn` 使用两位真实居民和实际世界位置，先拒绝未具联系权限的私信日程，同时保存独立动态日程；对方随后真实到场，重放旧决定不得新建私信日程，拒绝 revision 不增加；撤回原拒绝后墓碑仍阻止复活；新一天的本人新决定在权限满足时仍可独立排入。
- **main 提交**：`59b5d6b03343205ff73868a607075e6c2d959a85`（原始账本只读查询）、`a165ee965907665cde53dc47a0488a73fc80639f`（居民原决定重放门禁）、`380d117539fc869175b329fa1b824417b7508eb5`（根因回归）。
- **验证边界**：源码与测试已提交；CI/JVM/APK 及真实手机强杀、权限变化与多日连续性须以对应提交的工作流及设备实测单独核验，不能据测试文件存在宣布通过。不会因拒绝自动重购模型或让程序替居民选新的行动。原始拒绝事实可由本人下一次正常模型轮次看到，但是否重新安排只能由新的本人决定。
- **并发协作**：本轮只改 `SharedExperienceTimeline.kt` 的最小只读查询、`ResidentDailyDecision.kt`、`ResidentDailyContinuityTest.kt` 和本专项进度；未触碰社交、内驱、Live2D 文件，也未修改总清单以免与其他任务发生覆盖。源码索引由现有 Refresh code index 工作流生成，不能手工伪造。

## 2026-10-29 · P0-B/P4 历史搜索回执与最终反馈跨窗口精确追踪

- **已验源码基础**：原 `CharacterCausalTrace` 已有工具结果、下一轮决定、感知检查点及搜索反馈的只读诊断；`CharacterInnerHistoryArchive` 已保存历史本人决定，`SharedExperienceTimeline.eventsByIds` 已支持按本人、稳定 ID 精确读取。上述不代表真机已验。
- **真实缺口**：原来源卡和搜索结果卡寻找 ToolRouter 子回执/最终反馈时依赖调用方传入的近期 `events` 列表。数千条历史之后，原搜索、本人选择和最终答复都仍在权威 Store，UI 窗口却可能只剩原事件，导致误报没有真实执行或没有最终送达。
- **根因修复**：`CharacterCausalTrace.kt` 在原来源卡按本人保存决定的主/独立步骤稳定 requestId，精确读取既有 `tool-...` / `tool-rejected-...` 原始回执；要求请求归属、同一原事件 ID、当时 revision 和决定时间均匹配。搜索结果卡和来源卡根据原 `:verified-search-feedback` 稳定 ID 查真实回复回执；副步骤 `:step:N` 还原本轮请求 ID，只做格式映射，不猜测行动语义。仍核验真实反馈来源版本、发生时间、人物归属与撤回，不创建状态中心、不重发消息、不额外请求模型。旧无请求 ID/版本的记录继续明确无法精确证明。
- **根因回归**：`CharacterCausalTraceTest.archivedSearchChoiceLinksActualStepAndReplyWithoutRecentEventWindow` 只向诊断传原始事件一个窗口项，检验从本人已保存的步骤选择找到旧搜索与真正送达的最终答复；搜索回执卡在仅有自身一条记录时也能找到最终答复；验证跨人物隔离、结果编辑后旧答复不能冒充新版、撤回后不复活。测试代码提交不代表 JVM 通过。
- **main 提交**：`38cbe96de541c5a0bf1ca5dfb08e16d876cf45ed`、`e86b4a50714922ec957a1a19410c51561ec13a03`（源码）、`757a22481294ebe58cbbec90e39dd5b5e63e2525`（回归）。`Refresh code index` 继续使用仓库原工作流，不手工伪造索引。
- **分层验收**：截至本次核对，对应 `Lulu APK #40861164725`、`Refresh code index #40861164836` 均为 pending，**JVM/APK/索引成功均未确认**；真机、长历史分页、进程强杀及真实搜索服务仍待验。此修复仅提高只读诊断完整性，不替角色决定分享，也不解决旧版从未保存精确 ID 的历史。
- **协作边界**：只改本专项的 `CharacterCausalTrace.kt`、`CharacterCausalTraceTest.kt` 和本进度文件；未修改 ToolRouter、居民社交、内驱想法、Live2D 或并发总清单。未来如需跨入口稳定 ID 写入改动，交基础执行网关统一协调。

## 2026-10-29 · P0-B 工具请求 ID 与本人选择的因果归属再核对

- **源码根因**：`CharacterCausalTrace.executionRequestLines` 原来从本人已保存决定取稳定请求 ID 后，只核对工具回执的 `requestId` 和角色归属，就在原决定卡展示「本人选择对应的主动作 · 执行器确认成功」。若旧版、导入或冲突数据中相同请求 ID 绑定了另一条原始事件或不同 revision，诊断会把真实存在、但不属于这次选择的副作用误归因。缺独立回执时，对 `lulu_tool_executions` 已保存请求也只校验角色和 ID，可能误认另一来源的持久结果。
- **修复范围**：仅在原只读 `CharacterCausalTrace.kt` 的精确请求追踪处核对本人选择的 `causalEvidenceId`、`causalEvidenceRevision`、选择时间与工具回执的 `causeEvidenceId`、`causeEvidenceRevision`、真实发生时间；旧记录缺任一可核对字段、时序倒置或来源不匹配时保留原工具回执事实，但改为「回执归属未证实」，不得显示为这次决定已执行。原执行请求只有持久状态、没有独立回执时也核对来源/版本/请求开始时间；来源不明时只报告不可归因，不自动补回执、不重放副作用。历史已编辑来源仍可保留当时真正匹配的历史执行事实，不把旧版等同于当前版本。
- **根因回归**：`CharacterCausalTraceTest` 新增同 ID 跨消息误关联、同源不同版本、合法同源同版本、跨人物隔离、已保存结果与原选择来源冲突、恢复匹配后仍缺独立回执以及只读检查不生成回执。原有主/独立步骤、电话 legacy ID、搜索结果与最终反馈回归继续适用。
- **main 提交**：`de7203dab1b7df29c63ac0a843b5f4c98021072e`（源码），`370d3e77d8cff1e9db7314aae31b60bf9e98473a`（回归）。不改公共 `ToolRouter`，不抢社交/内驱/Live2D 专项，不额外购买模型轮次。
- **验证层级**：截至 2026-10-29 19:54（北京时间）查询，源码提交的 Refresh code index [#40868840701](https://github.com/jiacimu-droid/lulu1/actions/runs/40868840701) completed/success；回归提交对应索引 [#40868883714](https://github.com/jiacimu-droid/lulu1/actions/runs/40868883714) in_progress，Lulu APK [#40868883613](https://github.com/jiacimu-droid/lulu1/actions/runs/40868883613) pending。测试源码提交不等于 JVM 通过；本次真机及多年数据故障注入未验证。早先源码提交的 APK #40868840523 cancelled，不能算通过。
- **真机验收**：在一人原消息与真实工具结果卡中核对请求 ID、来源和版本；编辑原消息或通过故障注入重用旧请求 ID 后，详情应保留另一个真实回执，但不能写成当前选择的成功；恢复同源同版本的原回执可显示真实状态。断网/强杀后的未确认请求不得因查看详情而重复执行。


## 2026-10-29 · P0-B/P4 居民历史日程拒绝在旧时间线详情中不再失联

- **已核对基础**：居民日决定、逐项排程拒绝和撤回墓碑已经持久写入原 `SharedExperienceTimeline`；角色决定与 ToolRouter 的历史精确 ID 回溯已有实现，不能重建。主观判断仍由居民本人模型负责。
- **真实根因**：`CharacterCausalTrace.inspect` 只从调用方传入的近期 `events` UI 窗口筛选 `resident-agenda-rejected`。日决定与拒绝事实均仍在 SQLite 时，只要旧拒绝离开页面窗口，原日决定详情就可能误报没有后续；用户无法判断是未选择还是确实被执行器拒绝。此前搜索工具回执已修复同类问题，但居民排程拒绝仍漏接。
- **源码修复**：`SharedExperienceTimeline.eventsByIdPrefix` 使用原时间线主键的有界前缀范围只读查询，并复用 `eventsByIds` 的人物归属、历史可见性与撤回校验，不扫描全量历史、不新建 Store。居民决定卡按 `native-daily-...-agenda-rejected-` 原 ID 查询历史拒绝，核验本人 `characterId`、`source` 和原 `taskId`；展示真实拒绝、不误当执行成功。原决定文字后来编辑时标明旧拒绝不能证明针对当前版本；撤回的拒绝及撤回的源决定均不可见。UI 仅展示最近 12 项，但底层历史不截断；兼容旧数据第 8 项之后的拒绝。
- **根因回归源码**：`CharacterCausalTraceTest.residentRejectedAgendaSurvivesShortUiWindowAndHonorsEditWithdrawalAndRole` 仅传入原日决定一条 UI 记录，验证第 1/13 项旧拒绝仍可找回；同前缀但其他 taskId、另一人物的拒绝不串入；源编辑显示历史来源风险；拒绝撤回后不复活；查看不产生感知检查点，源撤回后详情为空。
- **main 源码与回归提交**：`3ac0c80664b903114c80f2693aafa479a4ffbc89`。代码索引交由现有 `Refresh code index` 工作流生成，不手工伪造。首次查询该提交对应索引 [#40877079824](https://github.com/jiacimu-droid/lulu1/actions/runs/40877079824) 为 in_progress，Android JVM/APK [#40877079954](https://github.com/jiacimu-droid/lulu1/actions/runs/40877079954) 为 pending；均不能算通过。真机旧时间线翻阅、撤回及多年数据量压力尚未验证。
- **协作范围**：仅改本专项因果诊断及其回归、原权威时间线的最小只读索引查询；未触碰公共 ToolRouter、社交、内驱、Live2D 或并发总清单。没有新增常态模型调用、定时唤醒、自动重试或副作用。

## 2026-10-29 · P0-B/P4 居民日决定和到点决定的历史工具回执跨窗口恢复

- **核对现状**：`CharacterCausalTrace` 已有本人 `CharacterInnerLifeStore.decisions` 的精确工具回执回溯、历史搜索反馈和居民 agenda 拒绝查询；但居民 `ResidentDailyDecision` 的直接动态/私信与到点主动作/独立步骤，其已付费的 JSON 决定保存在原居民请求 prefs，并非 `CharacterInnerLifeStore.decisions`。打开多年以前的原 `resident-daily` / `resident-due-decision` 卡片时，UI 近期窗口里没有工具回执，就会误报「没有后续」，即使原 ToolRouter 回执仍在 SQLite。
- **源码修复**：仅在原只读 `CharacterCausalTrace.kt` 从原居民决定 prefs 精确恢复当次真正选择的动作请求 ID：`native-daily-...-post/message/extra-N`、`resident-plan-<planId>-<原dueEpoch>` 和 `native-wake-...-extra-N`。复用现有 `executionRequestLines` 对原来源 ID/revision、角色、决定时刻、执行回执及持久执行记录逐项校验；实际回执可继续展示原生结果、感知检查点与下一轮选择。未保存的选择不靠相似文本或可预测 ID 猜测；跨角色/编辑/撤回不能借旧成功。到点 `wait/reschedule/cancel` 的主选择不经过 ToolRouter，仅读 `RoleDayScheduleStore.lastMutationRequestId` 与原 due 版本证明日程确实变更，不能冒充原活动完成；后来再改动时只报版本证据不足。
- **回归源码**：`CharacterCausalTraceTest` 增加居民每日直接动态成功、私信失败、独立步骤未回执、跨角色隔离、短 UI 窗口与完整窗口去重、来源编辑/撤回；到点 execute 主动作和独立步骤分开回执、未保存决定不得补造；wait 主选择从未确认到真实日程变更、后续撤销使原变更不再冒充当前状态。
- **main 提交**：`b544af10e87cfec27eefbc15e12a1809b49d5b82`（源码），`083a6af70211c7913ae6a66a9a19f30adf33682a`（回归）；代码索引由原有自动工作流生成，已观察到索引提交 `aa57f6f2d8e4af44c34cadea45e4e681e37685f7`。自动提交本身不证明索引工作流或 Android 测试成功。
- **验收分层**：GitHub 提交已核实；回归提交对应的原有 [Refresh code index #40896181069](https://github.com/jiacimu-droid/lulu1/actions/runs/40896181069) 为 **completed/success**，自动索引提交为 `aa57f6f2d8e4af44c34cadea45e4e681e37685f7`。同一回归提交对应的 [Lulu APK #40896181806](https://github.com/jiacimu-droid/lulu1/actions/runs/40896181806) 查询时为 **pending**（build job 已 in_progress），不能宣称 JVM 测试或 APK 通过；前一源码提交的 APK #40896116366 已取消，不算成功。真机强杀恢复、真实工具/居民权限变化、跨日历史规模性能均**未验**。此变更只读，不触发模型/工具，不修改其他专项或公共 ToolRouter。



## 2026-10-30 · P4 构建阻断：前台可见性未知时群聊协调器 Kotlin 类型错误

- **基线与归属**：重新读取 10-29 Library 大纲、AGENTS、CODE_INDEX、统一人物契约、成长框架、旧连续性计划及各专项近期 main 提交。原前台群聊单协调器、后台消息投递和 UserInteractionPresence 三态证据均已存在；不重复创建群聊路由或替角色决定回复。
- **真实构建证据**：Lulu APK [#40948163462](https://github.com/jiacimu-droid/lulu1/actions/runs/40948163462) 的 build job `123709185127` 已 completed/failure，`Test and build phone APK` 在 `CompanionOnlineStore.kt:410:26` 因 `Boolean?` 传给 `Boolean` 的 Kotlin 编译错误失败；其 Live2D stage 构建已成功，但 Android JVM/APK 未能继续。源头是 `UserInteractionPresence.appVisible` 按设计可为 null（冷启动/尚无真实生命周期证据），而 `shouldCoordinateGroupTurn` 错误要求非空布尔值。
- **根因修复**：仅将现有群聊协调器传输判定入口 `shouldCoordinateGroupTurn` 的 `appVisible` 形参改为 `Boolean?`，仅 `== true` 才走前台群聊协调；`false/null` 均保留现有后台交付分支。未把未知当作用户已读、前台在线或角色意愿；没有改公共 ToolRouter、人物模型或其他专项状态。源码 main 提交 `51509c2cb8a73fa9ee8e3ed903b72a85d72b3754`。
- **回归源码**：`GroupChatTurnPlannerTest.onlyOwnerGroupInputUsesForegroundCoordinator` 保留真实前台、后台、非用户、非群聊、群通话用例，并补冷启动 `appVisible=null` 时不得启动前台协调。回归 main 提交 `283f823591b27752d367a1723cbb736b775f1920`。测试文件提交不等于已运行通过。
- **验证分层**：已核实旧构建的真实 Kotlin 编译错误与本次源码类型修正；新提交对应 Android JVM、assemblePhone、APK 发布、代码索引自动生成的最终结果仍需核对，未取得完成结论不得记成功。真机冷启动群聊、前后台切换与真实群通话仍待验。现有 `Refresh code index` 工作流负责生成索引，禁止手工伪造；此次修改了 `app/` 文件。
- **设备验收**：强杀应用后直接由后台消息/通知唤醒，确认未知可见性不会触发前台群聊轮次；打开应用后本人发一条群消息应只有一个前台协调器；切到后台、群通话中发送消息应沿原分支，不出现双重自动回复。观察真实发送回执与日志，不以 UI 上出现气泡替代完整执行证据。
