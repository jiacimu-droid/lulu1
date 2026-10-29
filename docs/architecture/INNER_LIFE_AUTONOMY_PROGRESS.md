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
