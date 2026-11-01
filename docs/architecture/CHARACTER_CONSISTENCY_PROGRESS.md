# 人物一致性：整体因果链核查与修复

## 任务范围（2026-11-02 用户澄清）

哄睡是暴露问题的场景之一，不是唯一开发目标。要让本人稳定人格、关系与经历参与理解、动机、取舍、行动和后续，而不是只有台词或心声像本人。不能把所有角色统一改为温柔/热情/强势监督，也不能用脚本替本人作决定。

只在既有统一人物主循环和权威 Store 上修复，不另建人格、状态或模型审核中心；没有新增常态模型调用。现有每两小时任务改为本整体专项，保留上一轮634e162中已证实的责任状态修复作为子项。

## 现行链路与本轮证据

| 环节 | 现行归属 | 核查/修改及证据边界 |
|---|---|---|
| 身份与稳定人格 | CharacterRuntime.definition；原角色/profile Store | 原来把对象关系约定拼进persona，再标为稳定行为先验。改为同一快照中的独立relationships投影，纳入原configurationKey，稳定人格与对象关系分别输出；不迁移、不覆盖用户原设定。 |
| 场景与知识边界 | CompanionModelGateway effectiveMode | Full/CharacterAndScenario保留原世界关系，PersonaAndScenario只继承稳定人格，Isolated/Ensemble仍按原边界。原来由于关系已经混入persona，跨世界模式会继承原世界关系；本轮接回共用快照的分项投影。 |
| 理解、心理与行动选择 | CharacterDecisionProtocol；ProactivePerceptionRuntime；LuluDeviceToolBridge | 继续由同一人物在已有回合选择0/1/N动作。没有加第二次人格判定、语义词表或强制主动行为。公共提示与本人人设的冲突仍要逐项核查，不能靠“你要像真人”宣告解决。 |
| 规划到表达 | CharacterExpressionGuide；CharacterDecisionProtocol.expressionContext | 原表达专用请求仍注入要求重新理解现场/选择行动的personalityOrigin，与已作出选择的rendererInstruction混用。现分离决策与渲染的职责：表达继承本轮已经选定的立场/意图/行动边界，保留措辞自由。 |
| 决策交接完整性 | expressionContext；GroupChatTurnPlanner | 原stance/uncertainty/restraint等字段按80–240字符截断，可能丢掉后面的否定、条件；群导演只读取人设前1000字符，后续稳定字段可能缺席。现交接完整模型已选字段与完整用户稳定人设；不扩大历史召回、不新增调用。 |
| 请求压力恢复 | RelayRequestPressurePolicy | 新拆出的当前人物关系仍像原persona一样完整保护，防止拆分后被默认4000字符预算截掉；已有保护和回执不变。 |
| 行动与结果 | ToolRouter、CommitmentTaskStore、RoleDayScheduleStore | 634e162已修复未知结果/失败时每日责任提前滚到明天，以及仅silent才检查未交接。详见BEDTIME_RESPONSIBILITY_PROGRESS.md；不能把它当作全部OOC原因。 |
| 跨轮召回 | CharacterRuntime.personalityRuntimeContext、MemoryWorkingSet、连续性/心理/成长Store | 已追踪到共用入口；工作集按行投影、旧心声对后续判断的影响、前后台与工具结果的同源交接，仍需下一轮根因级调查，不声称已经验通。 |

## 回归与验证

- 原定义测试新增：改对象关系不改稳定persona，但配置更新；关系可供原世界、可从隔离场景排除，另一角色不继承。
- 原真实网关MockWebServer测试新增：实际组装并发出的Full规划、Full表达及PersonaAndScenario请求，对人格与关系的携带遵守各自边界；表达请求没有重做规划的personalityOrigin。
- 表达交接测试新增：长立场与否定/条件位于尾部时完整传递，渲染/修复共用继承已选意图的指导；普通自主决策仍可自己理解与选择。
- 请求压力测试新增：新独立关系段在精简时完整保留。
- 这些验证的是来源、请求组装与契约，不证明真实模型一定更像本人。本地没有Gradle/Android SDK，Android单测和APK以本轮main对应既有CI为准，真机与真实模型体验待验。

## 下一轮按整链推进

1. 先取本轮和634e162的CI结果；按真实失败修复，避免重复开发。
2. 审查MemoryWorkingSet对连续性、当前目标、主观印象等是否保留完整来源/条件/时间层级；避免旧的“怕打扰”被当成永远有效的策略。
3. 追踪私聊、电话、群聊、自主感知、日程到期与工具结果：同一次本人选择和真实回执是否都进入下一轮，是否丢失本人原目标/关联ID。
4. 审查所有有效模型决定后的程序分支与fallback：哪些是事实/权限/幂等，哪些仍在代替角色决定；仅修已证实的真实路径。
5. 验收覆盖主动与克制等不同人设、关系变化、拒绝后的调整、目标中断/失败恢复、跨场景连续性；真实模型质量需单列证据，不能用假模型的台词冒充。

完成项保留源码与提交证据；未验证项保留待验状态，不以增加提示词数量、改动行数或额外收费轮次当作进展。
