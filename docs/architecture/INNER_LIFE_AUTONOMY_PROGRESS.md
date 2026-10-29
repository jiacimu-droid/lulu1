# 内驱与想法专项进度

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
- **提交/CI**：源码与回归 [4000157](https://github.com/jiacimu-droid/lulu1/commit/4000157f1a90017a94889600578da8f708ef403f)。原索引生成工作流 [#40853357228](https://github.com/jiacimu-droid/lulu1/actions/runs/40853357228) 已触发（初查 in_progress）；[Lulu APK #40853357502](https://github.com/jiacimu-droid/lulu1/actions/runs/40853357502) 初查 pending，尚未验证 Android JVM/assemblePhone/Release。当前工具容器无 Android Gradle 工程与 SDK，不能本地宣称测试成功；真机和跨日多次唤醒仍待验。只读回退不改变真实执行结果，也不恢复早已永久丢失的历史。
