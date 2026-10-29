# 内驱与想法专项进度

## 2026-10-29 D2/T2：旧工具迟到回执不能抢走已改问法的新兴趣

- **分类核对**：原好奇心观察、真实工具回执、后续本人满意/放下、跨窗口召回已有源码和历史 CI；真机三次跨天唤醒、强杀及真实模型自然选择仍待验。此次真实缺口在同一兴趣改问法后旧回执的来源归属；多年大数据压力为可选增强，不重建状态中心。
- **根因**：`recordOutcome` 原来只在已关闭的兴趣上核对新 inquiry 的来源。对仍 exploring 的线程，`matching` 按稳定 threadId 选中新 pending，即使回执来自旧观察，仍覆盖当前问题、清除新 pending 和新失败反馈。即使新问题的真实动作已经完成、pending 已消耗，旧回执仍可把问题文本改回旧版本。
- **源码修复**：仍在 `CharacterCuriosityRuntime` 的唯一事实门禁中，已有线程出现新的 pending 时要求工具动作的明确 `sourceEvidenceId` 和问题与当前有效 inquiry 对应；否则该旧动作只保留原 `SharedExperienceTimeline` 工具事实，不再污染当前兴趣投影。新 inquiry 真正取得回执后，把已核验观察 ID、revision、提问时间留在原 thread，之后来自更早观察的迟到回执不能把新问题回滚。已关闭兴趣保留原有迟到回执归档及本人明确重新开启的门禁；首次无 ID 的旧协议仍可建立首次真实步骤。不新建 Store、付费模型轮次、固定唤醒或公共执行器。
- **回归源码**：`CharacterCuriosityRuntimeTest` 新增真实观察→第一次阅读→同 ID 新观察/失败→旧动作迟到→新问题仍待办、失败保留→新动作成功→重载→旧动作再迟到不回滚；另测同一观察修订问法后旧动作不得抢走新 pending。回执原始事实保留。源码提交与测试文件不等于 JVM 通过。
- **验证边界**：本次仅修改内驱专项运行时、对应回归和本进度；原 `Refresh code index` 自动生成索引，不手改 `CODE_INDEX.md`。提交后须核对对应 main CI 的完整 JVM、APK/Release；本执行环境无 Gradle/Android SDK，真机、跨天与杀进程仍未验。其它专项文件不改。


## 2026-10-29 D2/T3：撤回探索回执不应把已结束兴趣重新激活

- **四类基线**：原有兴趣观察→工具真实回执→后续本人明确满意/放下的实现及测试源码已存在；历史 CI 曾出现前置 satisfied 错误，已由 a7e01638 修正但本次不把旧测试/取消构建冒充本轮验通。跨天、强杀、真机仍为已有待验；多年历史压力与真实模型的自然选择属可选增强。本轮真实缺口在原有 `CharacterCuriosityRuntime.invalidateEvidence`，不新建记忆/心事中心。
- **根因**：一条兴趣有两次实际探索后，本人明确选择 `satisfied/dropped`。若后一次工具回执被撤回，旧撤回逻辑把仍有效的上一条工具步骤中的 `status=exploring` 覆盖到整个线程，抹掉本人更晚作出的结束选择；若全部步骤撤回，又直接丢弃线程，使迟到的新工具回执能当成未关闭的兴趣重新创建。回执真实性和人物意愿是不同事实，不能互相代替。
- **源码**：撤回仍移除被撤回的步骤并保留其他真实步骤；未结束的线程继续回退到上一条有效进展；本人已明确结束的线程则保留 `status`、结束原因、结束时间和最后的本人问题。全部步骤撤回时保留关闭墓碑，迟到回执不能借此自动重开；若明确原始观察/父来源本身被撤回且没有独立步骤，则仍按原来源失效规则移除。原 `SharedExperienceTimeline` 事实、角色归属、执行器和模型选择不改，不增加调用或唤醒。
- **回归源码**：`CharacterCuriosityRuntimeTest` 覆盖两次真实阅读→本人满意→撤回最新结果→旧有效结果仍可核验但满意状态不倒退→撤回全部步骤仍保留关闭决定→新迟到回执不得复活；另覆盖明确放下后的部分撤回。测试写入不等于 Android JVM 已通过。
- **验证层级**：修改前核对 main 的原索引和基础/社交/Live2D 专项进度；索引继续交原 `Refresh code index` 工作流自动生成，不手工修改索引。提交后须以实际 main CI 检查 JVM/assemblePhone/Release；真机、重启及三次跨天自然唤醒仍待验。只改内驱专项源码、回归及本文件，不碰公共 ToolRouter、人物网关或其他专项。


### 本轮 main / 索引 / CI 实证

- 源码、回归与本专项进度同一 main 提交 [d1bec4a](https://github.com/jiacimu-droid/lulu1/commit/d1bec4aca4cdfebaafdf8800dbb8ed252058d8e1)，修改文件仅为 `CharacterCuriosityRuntime.kt`、`CharacterCuriosityRuntimeTest.kt`、本进度。提交前已核对最新 main 及其他专项进度，没有覆盖并行改动。
- 原有 [Refresh code index #40874727908](https://github.com/jiacimu-droid/lulu1/actions/runs/40874727908) **completed/success**；自动索引提交 [e142a2f](https://github.com/jiacimu-droid/lulu1/commit/e142a2f314b48862c5dde9e18c7fc949b339755d)，CODE_INDEX 基准确认为 `d1bec4aca4cdfebaafdf8800dbb8ed252058d8e1`。未手工改索引。
- [Lulu APK #40874727954](https://github.com/jiacimu-droid/lulu1/actions/runs/40874727954) 对应本次源码提交，末次核对仍为 **pending**，上一轮 #6707 仍 in_progress；不能把源码、回归文件或索引成功当成 Android JVM/assemblePhone/Release 成功。当前执行容器没有 Gradle/Android SDK，未本地执行 Android JVM。真机、强杀和三次跨天唤醒仍待独立验收。

## 2026-10-29 T2：明确下一步的依赖条件不能在去重时丢失

- 根因：CharacterThoughtActionHandoff.actions 合并 nextAction 时，仅比较工具与参数，没有比较 requiresPreviousSuccess。相同工具/参数若一项明确依赖前步成功、另一项独立执行，旧代码会吞掉后者或把它关联到错误条件，导致本人明确的下一步丢失或失去条件。sideActions 合并原来已比较该字段，两条入口不一致。
- 修复：nextAction 去重也核验明确的依赖条件；相同条件仍去重，不同条件保留原顺序与独立步骤。不改变模型选择或自动根据心声派动作。
- 回归：双向覆盖独立/依赖步骤合并，验证顺序、原模型JSON不变、相同条件只执行一份。
- 已运行索引生成和差异检查；本地无Gradle，JVM/APK待此提交CI，真机未验。
- 开发任务已拆成4项：S1-S4社交、D1-D2/T1-T3内驱、L1-L3面对面、P0/P1/P4基础，各自独立进度并读最新main。

## 2026-10-29 T2 深入复核：副动作顺序与探索回执归属

复核了在线/后台 ProactivePerceptionRuntime、前台/电话 LuluDeviceToolBridge 的共享 CharacterThoughtActionHandoff 入口，以及 OrderedCharacterActions→ToolRouter→CharacterCuriosityRuntime 的实际回执路径。上一笔 c10614b9 的依赖条件比较仍保留；其 CI #40840507021 的 build job 已成功（测试、assemblePhone、发布成功），不能用于证明本次新增改动通过。

本次确认并修复：
- sideActions 中非对象项原来被静默丢弃，导致下一项 requiresPreviousSuccess 可能继承上一个不相邻的成功状态。现在保留原失败槽位交原执行器处理，失败与后续独立动作仍分开；不编造工具回执。
- steps、sideActions、nextAction 归并共用同一身份比较：除了工具、参数、依赖，还核对双方已明确填写的 motiveId/concernId/experienceId/curiosity/learningApplication。冲突项不再覆盖或吞掉，兼容重复项只合并缺失关联，并保留 learningApplication。相同参数的不同明确关联仍是不同模型选择，不按程序语义猜成同一目标。
- curiosity/curiosities 中明确的 nextAction 携带所属问题的原始字段到现有 ToolRouter 回执路径，去掉递归 nextAction；若动作自带明确 curiosity 则优先保留，不覆盖模型另选的问题，不从普通文字产生动作。

新增回归既含共享解码，也含真实 OrderedCharacterActions/ToolRouter 执行：错误槽位阻断依赖休眠但允许独立休眠；问题→明确下一步→媒体状态工具→问题进展→同请求重放不重复计数；兼容重复、冲突归属、学习关联保留、JSON不被修改、单项和数组问题。

已运行索引生成器、git diff --check。当前执行器没有 Gradle/Android SDK，本次 JVM/APK 必须等待既有 CI 对应新提交的结果，真机未验。没有新增模型轮次、人物存储或调度。此次未把所有长期自主性目标宣布完成，跨日兴趣体验和电话真机仍需分别验证。

## 2026-10-29 D2/T3：旧兴趣从轮换上下文中退场后仍能自主修订或放下

- **已证实根因**：好奇心状态按本人长期保留，但 CharacterExplorationContinuity.rememberVerified 仅接受本轮输入及 continuityEvidenceIds 的最近 3 条待探索、2 条已实践证据。旧兴趣仍由 CharacterUnfinishedActionContext / 人物自主回顾提供稳定 threadId 和来源时，模型明确选择 satisfied/dropped 或继续修订，却因原来源不在短窗口内被静默忽略；多日累积后心事无法自然整理。
- **源码修复**：CharacterCuriosityRuntime.ownedContinuityEvidenceId 仅按本人的显式 threadId 读取仍有效的待探索原观察或已实践真实回执；rememberVerified 仅为本轮明确提到的旧线程扩展该证据，不开放任意模型声明的来源，不扩大日常上下文，也不替人物选择行动。沿原 questions/threads Store 保存状态；已撤回、版本失效、已结束或跨角色来源不能借用。
- **回归源码**：6 条独立观察超出 3 条轮换后，模型仍能按真实来源结束第一条，重启后保留其他 5 条；无效 ID/跨人物不可借用。4 条真实搜索回执超出 2 条轮换后，旧线程可放下且后续召回不再重复；不重跑工具、不增加模型轮次。
- **验证分层**：本轮源码与回归写入 main 后由现有 code-index 工作流执行原生成器；Android JVM、APK、真机三次跨日唤醒与真实人物自主选择，须分别以对应 CI/设备结果核实，不能以测试编写宣称通过。旧历史已经丢失或原来源失效的记录不能自动恢复。
- **main/CI 实证**：源码与回归提交 [`cf22080`](https://github.com/jiacimu-droid/lulu1/commit/cf22080abe990454fb2c83cd02a7471aad6c5ab0)。原索引工作流 [#40843545295](https://github.com/jiacimu-droid/lulu1/actions/runs/40843545295) 已 success，自动生成的索引提交为 `40210afb`，CODE_INDEX 基准明确指向 `cf22080`。对应 [Lulu APK #40843545409](https://github.com/jiacimu-droid/lulu1/actions/runs/40843545409) 在本次核对时仍为 in_progress，Android JVM/assemblePhone/Release 尚未通过，真机未验。

## 2026-10-29 D2/T3：修订过的最新回执不得污染旧兴趣的有效进展

- **分类核对**：已有验通的是原 T2 多动作交接的一次历史 CI（#40840507021），不代表本轮已验；已有待验的是上轮按旧 threadId 整理兴趣（cf22080，先前 APK 仍在运行）；本轮真实缺口是“旧回执失效后依然在本人后续上下文显示失效成果”。未来可选增强是多年历史规模、跨日三次实际选择和真实搜索供应商复测，不因它们未验而重新造中心。
- **真实根因**：CharacterCuriosityRuntime.lastRealStep 已按本人来源 ID、修订号、真实动作核验并能回退到较早的有效回执，但 activeThreads 先按主题/问题去重再过滤，失效的旧兄弟记录可能抢占唯一名额；briefContext 和 promptSection 又直接拿 steps 最后一项的 receipt/question/nextStep/updatedAt，即使它已经失效，仍向电话/前台/自主感知提供错误的“最新真实成果”。这不是模型决定问题，而是只读投影越过了既有事实门禁。
- **源码修复**：在原 CharacterCuriosityRuntime 只读 activeThreads 投影先过滤无效证据、再去重；若末项回执已失效，回退到 lastRealStep 的实际问题、下一步和时刻；briefContext/promptSection 统一使用经核验的真实回执。保留完整原 threads/steps 供回溯，不改模型意愿、真实执行、授权、持久化 schema、调度或收费次数。CharacterUnfinishedActionContext 继续复用同一个 activeThreads 投影。
- **回归源码**：CharacterExplorationContinuityTest 新增同一线程两次实际搜索后最新回执 revision 失配，验证重启后仅旧有效成果进入私聊/电话精简上下文与自主决策提示、历史两步仍保留、另一人物不借用；另测同问题两条线程中失效旧记录不会挡住另一条有效记录。该测试需要 Android JVM 实际跑通，写入源码不等于通过。
- **提交/CI**：源码与回归 [4000157](https://github.com/jiacimu-droid/lulu1/commit/4000157f1a90017a94889600578da8f708ef403f)。原索引生成工作流 [#40853357228](https://github.com/jiacimu-droid/lulu1/actions/runs/40853357228) 已 success，自动索引提交 [cd66ca8](https://github.com/jiacimu-droid/lulu1/commit/cd66ca889f)，CODE_INDEX 基准指向 4000157；[Lulu APK #40853357502](https://github.com/jiacimu-droid/lulu1/actions/runs/40853357502) 最后核对为 in_progress（build 刚启动），尚未验证 Android JVM/assemblePhone/Release。当前工具容器无 Android Gradle 工程与 SDK，不能本地宣称测试成功；真机和跨日多次唤醒仍待验。只读回退不改变真实执行结果，也不恢复早已永久丢失的历史。

## 2026-10-29 D2/T3：已结束兴趣不被迟到工具回执重新激活

- **分类**：历史 T2 回执交接已有 CI 成功记录，不等于本轮验通；旧兴趣跨窗口修订与最新有效回执回退已有源码/回归但真机跨天待验；本轮真实缺口是「本人已 dropped/satisfied，晚到的成功工具结果又把状态设为 exploring」；长期历史规模与三次跨天自主选择属可选增强/待实测。
- **根因**：ToolRouter、ProactivePerceptionRuntime、CharacterConversationAgency 把真实执行回执交给 CharacterCuriosityRuntime.recordOutcome；该入口无条件覆盖 thread.status，且首次回执前结束的 pending question 只从 questions 删除，丢失关闭状态。旧动作迟到不是本人重新选择继续。
- **实现**：仍使用原 CharacterCuriosityRuntime questions/threads。已关闭的兴趣接收真实回执时只追加经过证据核验的步骤，不改 dropped/satisfied、结束理由、原问题与下一步；只有关闭之后由本人明确产生、以真实观察为来源的同 threadId 新 inquiry 才能重新探索。首次回执前结束的 pending question 在原 threads 留无步骤终态；撤回其来源只移除对应终态，不误删其他独立问题。重复 evidenceId 幂等，不新增 Store、模型轮次、定时器或公共 ToolRouter 改动。
- **回归源码**：CharacterCuriosityRuntimeTest 新增「已探索→放下→晚到真实结果→保留终态及真实步骤→重放幂等→真实新问题恢复」「pending→满意→首个回执迟到仍满意」「撤回一个无步骤终态不删除其他终态」三组。测试写入源码不等于 JVM 已通过。
- **main 提交**：[7a8aabb](https://github.com/jiacimu-droid/lulu1/commit/7a8aabbe27246a1714af29b8568dc93e6a650fc0)、[6d8653b](https://github.com/jiacimu-droid/lulu1/commit/6d8653b61a56cfd01c02c7cdd05734cef6521012)。原索引生成已自动触发；并发推送令本次 [索引 #40859572420](https://github.com/jiacimu-droid/lulu1/actions/runs/40859572420) 与 [APK #40859572497](https://github.com/jiacimu-droid/lulu1/actions/runs/40859572497) cancelled，不能算测试或 APK 通过；需核对包含这两个提交的后续 main CI。真机、强杀恢复与三次跨天唤醒仍未验。

### 同轮追加：新问题出现后，旧回执也不能抢走新问题

- 继续审查发现：仅凭「新 inquiry 的时间晚于 dropped/satisfied」就允许重开仍不够；同一 threadId 的旧工具回执可能恰好在新 inquiry 写入后返回，错误地借新问题解锁，并清掉新 pending 与失败反馈。修复提交 [199b45a](https://github.com/jiacimu-droid/lulu1/commit/199b45ae76560e1a3290a752540ca1e3678f2330)：重开必须同时满足本次动作明确引用新 inquiry 的 sourceEvidenceId、本人该观察仍存在且 revision 有效、观察时间晚于原关闭时间；不符合时真实结果仍归档但不消耗新问题或失败反馈。新增回归 [4f2d3b1](https://github.com/jiacimu-droid/lulu1/commit/4f2d3b1ac98eb49faeec0d6f8494689fb48cabec) 模拟「已放下→新问题保存→旧动作迟到→新问题仍待办→真正引用新来源的动作完成才重开」。此处只做事实/来源校验，不以程序推测本人语义；仍须等对应后续 CI/JVM/APK 和真机验收。

## 2026-10-29 D2/T2：工具前置的“已满意”不能抢先结案（真实 CI 阻断）

- **基线与分类**：原 nextAction/steps→OrderedCharacterActions→ToolRouter→CharacterCuriosityRuntime 回执链已有实现；社交专项核对的 main APK #40863097204 已运行 1950 项 JVM 测试、仅 1 项失败，失败是 `CharacterExplorationContinuityTest.eachOrderedToolCarriesItsQuestionAndAnUnseenResultCannotMarkItSatisfied`（预期 exploring，实际 satisfied），因此不是仅待真机的主观问题，而是当前可复现的自动测试阻断。其他 D1/D2/T1/T3 目标仍需真机跨日验收。
- **根因**：角色在执行前的 `steps[].curiosity.status=satisfied` 被 `recordOutcome` 直接带到已成功工具回执的 thread.status；工具成功只能证明动作发生，不能提前证明本人已读懂、分享或满意。这个旧字段还会让尚未看见结果的兴趣退出后续召回，阻断本人对结果的重新决定。
- **源码修复**：原 `CharacterCuriosityRuntime.recordOutcome` 在真实工具结果到达时只记录 verified step、保持 `exploring`（已由本人关闭的旧线程仍保留关闭状态）；只有后续本人在原模型轮次对真实结果作出 `satisfied/dropped` 的明确选择，才由既有 `CharacterExplorationContinuity.remember → settleInterest/releaseInterest` 结案。真实回执、来源版本、人物归属、独立动作幂等均沿旧链，不新增 Store、模型调用或定时器。
- **回归**：保留此前失败的真实 `OrderedCharacterActions.execute` 断言，并补「同一真实回执进入下一轮本人明确 satisfied→结案→原动作重放不得复活」；覆盖先行动、后看结果、再选择、重放的完整链。提交源码与回归不等于 JVM 已通过。
- **验证边界**：本次直接提交 main 并交原 `Refresh code index` 生成 `CODE_INDEX.md` / `code-index.json`，不得手工伪造。须核对本提交对应 JVM/assemblePhone/APK/Release；真实手机、杀进程与三次跨天唤醒未验。其他专项源码、公共 ToolRouter/主网关/统一存储均未修改。

### 本轮实际 CI 核对（a7e01638）

- 源码、测试和本专项进度的原子 main 提交：[a7e01638](https://github.com/jiacimu-droid/lulu1/commit/a7e01638a60a44b114bb46f23382e4dd1e6423eb)；后续 main 对比仅有其他专项文件和自动索引变更，本专项修复仍在 main。
- 原有 [Refresh code index #40864330068](https://github.com/jiacimu-droid/lulu1/actions/runs/40864330068) 已 **completed/success**，自动索引提交 `1374e67b`，CODE_INDEX 基准指向 `a7e01638`；未手工修改索引。
- 原 [Lulu APK #40864330013](https://github.com/jiacimu-droid/lulu1/actions/runs/40864330013) 因后续并发 main 提交而 **cancelled**，不能算 JVM 测试通过、APK 构建成功或发布。后续 main 的 APK #40864759507 在本次核对时仍 pending；须等待包含此修复的后续 completed run 证实原失败断言已通过，真机仍待验。

## 2026-10-29 T2/T3：旧失败回执不得污染新兴趣或已结束的整理

- **四类核对**：旧 nextAction→真实 ToolRouter 回执、兴趣 source/revision 校验和已结束兴趣的迟到成功回执门禁已有源码；最新 main 的 [APK #40903765474](https://github.com/jiacimu-droid/lulu1/actions/runs/40903765474) 已 completed/success，包含 Live2D、JVM、phone、APK 发布步骤，但不是本次变更的验收。此前 D2/T3 的真机强杀与跨三次自然唤醒仍待验；多年数据量/真实模型自然兴趣选择为可选增强。**本轮真实缺口**：失败回执路径未同步成功回执的来源门禁。
- **根因**：`CharacterCuriosityRuntime.recordOutcome` 已拒绝旧来源/旧问法抢占新 pending，`recordFailure` 却仅按 threadId 找到当前线程，把迟到的旧失败作为最新问题的失败并覆盖 repeats/reason；已经 dropped/satisfied 的线程也可新增失败。原 `promptSection` 无条件显示最近三项 failures，使已关闭兴趣仍通过「最近失败」进入后续自主选择，形成无真实意愿的旧问题反复浮现。
- **正式源码**：只改原 `CharacterCuriosityRuntime.kt`。失败回执归属核验当前 pending 的有效 source、问法与本角色归属；已实践线程核验当前问题/来源并拒绝已关闭兴趣的迟到失败。仍允许旧协议在尚无线程时记录未关联的真实失败；不删除历史失败或工具时间线。新失败保存原有可选 sourceEvidenceId，后续 `promptSection` 只显示仍属于有效待探索问题/线程的已关联失败；不借另一人的来源，也不为失败自动重试、重开或收费。
- **回归源码**：`CharacterExplorationContinuityTest` 两条测试覆盖旧工具失败晚到→新 inquiry 与新失败不被污染→真实新工具成功清除当前失败→本人明确结案后迟到失败不能复活；另覆盖结案前真实失败仍保存在原 Store、重启后不再出现在自主决策提示。原执行事实仍由时间线保留，回归文件提交不能代替 JVM 实跑。
- **提交**：源码 [af32d94](https://github.com/jiacimu-droid/lulu1/commit/af32d94f66991dcc95ed11ab61291fba6d5fece9)，回归 [410c3ef](https://github.com/jiacimu-droid/lulu1/commit/410c3efc11d5ea80f9e382c78fca1a7a45ed3447)。仅修改本专项运行时、对应测试和本专项进度；不碰基础/社交/Live2D 的公共文件，不新建 Store、调度或模型轮次。
- **验证分层**：源码提交后原 [Refresh code index #40907424813](https://github.com/jiacimu-droid/lulu1/actions/runs/40907424813) completed/success，自动索引提交 `45bf6bb`；回归提交后原 [Refresh code index #40907490706](https://github.com/jiacimu-droid/lulu1/actions/runs/40907490706) 最初为 queued。源码单独 [APK #40907424998](https://github.com/jiacimu-droid/lulu1/actions/runs/40907424998) 因后续回归提交被取消；包含回归的 [APK #40907490830](https://github.com/jiacimu-droid/lulu1/actions/runs/40907490830) 最初为 pending。执行环境无 Android SDK/Gradle，**不能宣布本轮 JVM、APK、Release 或真机通过**；须按最终 run 状态继续核对。真实设备的强杀、权限变化和三次跨天唤醒仍未验。

### T2/T3 本轮索引复核

- 回归提交 `410c3ef` 对应原 [Refresh code index #40907490706](https://github.com/jiacimu-droid/lulu1/actions/runs/40907490706) **completed/success**，自动索引提交 [9f8226d](https://github.com/jiacimu-droid/lulu1/commit/9f8226d377b8)；实际 `CODE_INDEX.md` 基准为 `410c3efc11d5ea80f9e382c78fca1a7a45ed3447`，已覆盖源码和回归。无需手工重写索引。
- [Lulu APK #40907490830](https://github.com/jiacimu-droid/lulu1/actions/runs/40907490830) 复核时仍 **pending**，尚无 Android JVM/phone/Release 通过证据。旧 [#40903765474](https://github.com/jiacimu-droid/lulu1/actions/runs/40903765474) 的成功仅作修改前基线。真机与跨日验收仍待。

## 2026-10-30 D2/T2：首次探索回执及失效线程的事实归属

- **四类核对**：已有验通的是此前同一角色多步骤 ToolRouter 回执及旧兴趣修订的源码/历史 CI；已有待验的是跨三次自然唤醒、真机强杀与真实模型取舍；本轮已证实缺口是**首次行动尚无 explored thread 时，旧来源回执可吞掉新的 pending 问题**，以及显式失效 threadId 的失败被当成无关联失败塞回自主提示。多年兴趣量/设备性能属于可选增强，不另造中心。
- **根因**：`recordOutcome` 的迟到来源校验只在 `selected != null` 时生效，首个 pending inquiry 没有历史 thread，旧动作只要复用其 threadId/问法就能写入新进展并消费 pending；`recordFailure` 的 `matching` 未找到显式 threadId 时退成空 ID，`promptSection` 又把空 ID 视为历史兼容失败，令已删除或其他人物的失败成为新的重试线索。
- **正式修复**：只在原 `CharacterCuriosityRuntime` 事实门禁核验首次 pending 的明确 `sourceEvidenceId`、当前问法及仍有效的观察；明确指向未知线程的失败不再伪装为旧协议无 ID 反馈。旧协议真正没有 threadId 的失败仍可保存，真实工具回执仍在原时间线，正确来源的首次成功正常消耗 pending、写入原 threads Store。没有自动重试、第二套状态、常态模型轮次或新唤醒。
- **根因回归源码**：`CharacterCuriosityRuntimeTest` 新增「首次新问题→旧来源真实回执晚到→pending 与 threads 不受污染→新来源真实结果正常结转→重新绑定后保留」及「显式无效 threadId 失败不能变成自主重试线索→本人真实失败仍持久保存」两组。未将测试源码视为 JVM 已通过。
- **main 提交**：源码 [fed16e8](https://github.com/jiacimu-droid/lulu1/commit/fed16e8a51db172b207034eeecf7b0548930ad9a)，回归 [9ccae8a](https://github.com/jiacimu-droid/lulu1/commit/9ccae8a185c371e6c6dec2b66a069b982394b5fa)。并行 main 仅使用原文件当前 blob SHA 更新，不覆盖社交/基础/Live2D 专项文件。
- **验证边界**：原有 `Refresh code index` 自动生成 `CODE_INDEX.md` / `code-index.json`，不得手工伪造。需核对包含回归提交的 Android JVM、assemblePhone、APK/Release 真实完成结果；真机、权限变化、强杀与三次跨天自然唤醒仍待验。

## 2026-10-30 D2/T3：旧模型结案决定不得关闭已修订的新问题

- **核对分类**：原 ToolRouter→兴趣成果→后续本人决定已有实现，旧来源回执/失败归属也已有专项修复与回归；这些历史提交的源码接通不等于本次真机验通。已待验：真实模型跨三次自然唤醒的取舍、进程强杀、权限与长期规模。可选增强：多年兴趣规模性能测量。**本轮真实缺口**是同一 threadId 被本人修订后，旧模型的 `satisfied/dropped` 结案响应仍可关闭新问题。
- **根因**：`CharacterExplorationContinuity.rememberVerified` 已核验结案响应引用的事件真实存在且属于本人，但 `CharacterCuriosityRuntime.releaseInterest/settleInterest` 只用稳定 threadId 匹配 pending/threads，未对照该响应所依据的来源与当前问法版本。旧响应晚到时可移除新 pending，或把尚未处理的新问题记为本人已满意/放下。工具结果的迟到门禁不覆盖这条纯主观结案路径。
- **正式修复**：在原 `CharacterCuriosityRuntime` 为两种终态共用事实归属门禁：有显式 sourceEvidenceId 的结案先核对本人真实有效来源；与当前观察或最近有效回执相符且问题未被修订，或者确有晚于当前 inquiry 的新真实事件，才允许记录本人结案。旧来源+旧问法不得跨修订关闭；无来源的旧直接调用保留兼容（真实 `rememberVerified` 路径始终附核验来源）。这是来源/版本校验，不由程序推断角色是否该放下。保留原 Store、时间线、意愿选择与所有历史，不增加模型调用、调度或自动重试。
- **回归源码**：`CharacterExplorationContinuityTest` 新增同一观察改问法后旧 `satisfied` 晚到不结案、新本人决定可结案，以及新观察改问题后旧 `dropped` 晚到不结案、新本人决定可结案；均覆盖原 `remember` 路由、重绑原 Store 后的持久状态。未声称 JVM 已实跑。
- **main 提交**：源码 [c33c481](https://github.com/jiacimu-droid/lulu1/commit/c33c481d5647c4b3e3416339ad703360589f294c)，回归 [20ba12e](https://github.com/jiacimu-droid/lulu1/commit/20ba12e0b347214945857bae726617510af3a637)。原 [Refresh code index #40948059574](https://github.com/jiacimu-droid/lulu1/actions/runs/40948059574) **completed/success**，自动生成索引提交 `337042b`，实际 `CODE_INDEX.md` 基准为 `20ba12e`，未手工伪造索引。
- **CI/设备边界**：本次 [Lulu APK #40948059461](https://github.com/jiacimu-droid/lulu1/actions/runs/40948059461) 初次核对仍 pending，JVM/assemblePhone/Release 未验；上次基线 [#40935673198](https://github.com/jiacimu-droid/lulu1/actions/runs/40935673198) 已 completed/failure，阻断是社交/在线公共 `CompanionOnlineStore.kt:410` 的 nullable Boolean 传给 Boolean，不能归为本轮兴趣逻辑测试失败。本轮不越界修改公共入口。真机、强杀恢复、三次跨日唤醒仍待实测；源事件在同一 ID 下发生历史修订而旧模型没有携带 source revision 的极端竞态仍需跨入口版本水位协议才能彻底覆盖。
