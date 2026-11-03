# 人物一致性：整体因果链核查与修复

完整跨窗口体验路线与优先级见 [LULU_EXPERIENCE_ROADMAP.md](LULU_EXPERIENCE_ROADMAP.md)：人物活人感第一，角色社交第二，再推进游戏与视频。本文件记录人物专项的真实证据与待验状态。

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

## 2026-11-02 续轮：上下文限定与内容一起进入决策

### 前轮CI真实结果

- c8c37d3 的 Lulu APK run `41333088808`：主源码编译成功，2226项单测中2项失败，APK未生成。失败位置为 CharacterLifeStoreTest:312（仍断言关系约定属于persona）和 ConversationExpressionGatewayTest:205（仍断言旧版渲染文案）。本轮保留回归目的，改为同时核实关系独立、原世界仍收到完整约定，以及渲染继承已选立场而不再注入规划原则；没有弱化为忽略失败。
- 634e162 的 run `41332393277` 被后续main提交取消，不能单独认作通过。其代码已包含在c8c37d3的编译与2226项测试中。

### 已证实的根因与修改

原 MemoryWorkingSet 把当前状态、跨场景连续性和成长/动机文本拆成逐行候选。预算筛选与重复内容去重均按行独立执行，可能保留标题而遗漏条件、保留正文却丢失说明其是旧心理/推测的标题，也可能把不同来源下相同一句话合并。这样实际输入不再是原投影的完整含义；不等于已经证明用户截图只有这一个原因。

改用原 MemoryWorkingSetPolicy 生成完整上下文块候选，以原渲染器的【标题】作为结构分界，将标题、正文、时间范围、否定和后置条件共同筛选、共同去重。不解释文本意图，不改变角色选择，不建新Store、不增加模型轮次。原统一预算、类别预算、来源原文与遗漏审计保持；整块超过预算会被完整省略，不以残句代表完整状态。权威核心记忆和执行责任仍沿原受保护通道提供。

影响：所有使用Full网关的私聊、后台、电话、群聊及工具后续共享这一修复。无标题文本整体作为一个块。多块间优先级仍沿原auxiliary组排序；超长单块可能比过去少注入部分内容，但避免不完整的限定改变意思，遗漏仍可在原注入审计追踪，未删除持久原文。

### 本轮验证与剩余工作

- 使用仓库同版本Kotlin 2.0.21实际编译生产 MemoryWorkingSetPolicy 与原 MemoryWorkingSetPolicyTest，以JUnit 4.13.2执行：8/8通过（含新增3项）。覆盖旧推测/时间限定在预算下不拆散、相同文字不同证据范围不合并、空行与后置否定/类别预算、无标题块；原预算、原文去重等既有回归一并通过。
- Android/Robolectric网关及预设回归尚待本轮main CI，不把纯JVM8项当作完整单测/APK成功。真机、模型是否更符合本人设定仍待验。
- 下一轮先核实本轮CI，再查各状态渲染器内部是否在进入工作集前已截断原意、工具与日程回执是否保留原目标和本人关联ID。整体任务尚未完成，不重复上一轮改动。

## 2026-11-02 再续轮：心事持久所有权与当轮注意分离

### 上轮完整交付证据

e61f0f5对应Lulu APK run `41342908754`（build job `124956524066`）成功：原testDebugUnitTest、APK打包、产物上传、Release发布均成功，日志记录BUILD SUCCESSFUL；发布为 `lulu-v7262`。这验证了已包含c8c37d3的代码与Android回归，不代表真机/真实模型OOC质量已验收。

### 本轮已证实的公共断点

CharacterOpenConcernRuntime在updateOne结果和retained读取两处都使用最后12条作为存储/读取边界。新心事进入、修订或recordOutcome写回一条真实回执时，较早尚未解决的心事会从原权威openConcerns丢失；它们从后续决策、旧ID修订、来源撤回和回执归因中一起消失，并非角色本人决定放下。canonical focus/why/hesitation/possibleNextStep和resolution保存时还按170–200字符静默截断；lastOutcome的receipt ID截成120字符，可能不再对应真实证据。

修复沿原Store：未结/暂停心事完整保留，保留格式与既有来源撤回、暂停/恢复/settled/dismissed语义，原context与共享工作集负责模型注意预算；不把保存数设成当轮召回数。上述本人语义与真实回执ID/摘要完整保存；不改变本人选择，不自动恢复暂停心事，不自动创建目标或额外唤醒，不改角色人格/历史原文。既有5步心路预览与四项变更协议未改。

### 验证和边界

- 新增原测试类中的两项回归：20条不同未结心事、旧条目暂停/修订/长ID回执写回、JSON持久化往返、明确结案、迟到回执不复活、不误删其他条目；长条件/否定及结案理由完整保存。
- 使用Kotlin 2.0.21、JUnit 4.13.2、org.json 20240303，把新增两个原测试方法及其相同now夹具复制到临时纯JVM测试类（不带Robolectric runner）运行生产源码。修改前2/2失败：实际20变12、长focus被截断；修改后2/2通过。原完整测试类另两项依赖Android协调器，不能把本地两项当成完整Robolectric回归；本轮完整Android/新APK以对应CI为准。
- 无存储格式迁移，现有幸存记录保持；旧版已删掉或截断的内容没有凭空重建。积累大量未结心事后，索引规模、工作集召回完整性和多日真机表现仍待后续检查，不能宣称长期性能已经验收。
- 下一轮先核实本轮CI，再查其他持久状态源是否仍混用展示预览与权威含义，及原目标/关心在跨场景结果后的延续。整体任务继续，已验通项不重复改动。


## 2026-11-03 续轮：本人主观结论的持久化与下一轮交接完整性

### 前轮 CI 核实

- c8c37d3 初次 run `41333088808` 失败：2226 项测试中 2 项旧断言不匹配，未生成 APK；e61f0f5 修正测试契约后，run `41342908754` 的 build job 成功，包含测试、APK、产物和发布。
- c63111f run `41353343520` 的 build job 成功。心事保存完整性已通过该次完整 Android CI；不重复修改。
- 这些 CI 证明源码测试与 APK 构建，不代表真机或真实模型的 OOC 质量。

### 本轮已证实的断点

`CharacterInnerLifeStore.recordCausalTransition` 在保存本人主观记录前，对 meaning/responseAim/uncertainty/commonGroundUpdate、innerThought、innerThoughtBasis 多字段静默截断；`CharacterDeliberationContext.conciseAppraisal` 再次截断可能解释与并存想法，summary 又截短内容。关键否定、条件和后半句可能在持久化前已经消失。另一个交接缺口是 summary 没有输出已保存的 responseAim/interactionMove，也没有把 focus/change/unsaidWhy/spontaneousTurn 纳入可见主观历史；仅有这些字段的有效记录会被 hasPrivateContent 过滤。于是“当时如何理解、想达到什么、为什么犹豫”不能完整回到下一次同一人物决策。

### 本轮源码修复

- 对上述本人撰写的因果解释字段取消静默字符截断，保留原模型输出；结构化动作枚举仍保留字段校验。
- 下一轮摘要补回 responseAim、interactionMove 和完整 innerThoughtBasis 维度；分别标明它们是过去的主观结论，不冒充用户事实或当前状态。
- 每条主观事件独立成为一个工作集候选，按时间由新到旧选择。预算不足时整条事件可被省略，不再把同一条含否定/条件的记录切成残句，也不让一条超长旧记录拖掉其他近期记录。
- 没有增加 Store、模型轮次、自动行动或角色性格规则；原始事件归属与撤回仍走现有来源门禁。

### 回归与待验

- 新增 Android/Robolectric 回归覆盖长字段末尾否定与条件在存储、compact/full 两类上下文中完整往返；另覆盖 responseAim-only 和 interactionMove-only 不再被过滤。源码测试已写入，需等待本次 main CI 执行。
- 真机、真实模型是否因此更能连续承接本人意图仍待实测；工具/日程执行回执是否完整携带原 motive/concern/experience 关联 ID，是下一轮继续追踪的重点。

## 2026-11-03 续轮：群聊见证与本人发言的来源归属

### 前轮结果与本轮范围

- `fcfe48d` 的 Lulu APK run `41573104453` 失败：2262 项测试中 1 项失败，位置为 `CharacterInnerLifeStoreTest.authoredAppraisalAndThoughtKeepTheirMeaningfulTailsAcrossTheNextTurn`，未生成 APK。最新 main 已由另一轮 `9e830a0` 恢复稳定的冲突说明标签；本轮保留，不重复实现或削弱断言。
- `9e830a0` 的 run `41574546147` / build job `125714905394` 已核实成功：原 Android 单测、APK 打包、上传和发布成功，`BUILD SUCCESSFUL`，发布 `lulu-v7367`。包含先前人物修复；不等于真机或真实模型质量验收。
- 用户已授权保存整体体验大纲、在 AGENTS 增加续改入口并继续改代码。新路线文档区分已有能力、已证实缺口和待核查项目；后续按人物优先、社交其次推进。

### 已证实根因与实际修改

`SharedExperienceTimeline.recordConversationMessage` 为每位群成员写入见证事件，事件的 characterId 是见证者，CharacterStatement 只说明“某角色发言”，不保证作者就是这位见证者。原 `CharacterContinuityRuntime` 将任意此类事件标为“角色自己上一次真实说过”；`ConversationTurnEconomy.recentOwnReplies` 把它加入本人的表达参考；`CharacterActionEvidence.supports` 又可把它当成本人已发消息的证明。同一来源归属错误从理解上下文传到表达及真实行动归因，是公共机制缺口，不是某个人设不够热情。

新增只读 `TimelineStatementOwnership`，沿已有 sessionId 与消息 ID 回到权威 chat Store，用原消息 sender/authorCharacterId/onBehalfByOwner 校验本人发言。三处消费者复用同一规则，不用昵称或内容猜作者，不新增 Store、人格中心或模型调用。无作者字段的旧消息沿原 conversation.characterId 语义兼容；群消息原件缺失、非正式消息或用户代发，不能冒充本人的自主发言。私聊等已有非群记录保持原语义。

本人在群里真实说过的内容仍可进入连续性，但标明场景，不等于已向当前对象说过。群里某角色说话也不证明与用户发生了互动；用户真实发言可提供互动时距依据。只读投影不拿当前可变的 ownerIncluded 反推历史听众，避免用户后来入群改变旧联系时钟。没有用户联系记录时也保留自己的社交发言锚点。原见证时间线不删除，仍可召回别人的原话。

### 回归、兼容与剩余边界

- 新增 Robolectric 来源级回归，经正式 chat Store 写群消息再读取真实时间线，覆盖不同作者的相反表达、双方各自连续性、表达参考与行动证明、同昵称/改昵称、原件缺失、旧默认作者、用户代发、无用户群聊、用户后来加入和真正发言后的时距更新。
- 本地 `git diff --check` 通过，运行原代码索引生成器。当前环境无 Gradle/Android SDK，本轮新增 Android 回归和 APK 须等待本提交 main CI；不得把测试已编写记成已通过。
- 无数据库格式迁移，原始群聊历史与见证记录保留。此轮修正上述三条消费链；旧版已据错误作者形成的成长/关系内容没有凭空改写，其他将 CharacterStatement 当成本人表达的成长等消费点仍需继续逐项核查。
- 连续性渲染原有短预览、固定最近事件窗口是否仍丢关键条件/长期相关经历，仍属于已见源码风险，未在本轮宣称解决。下一轮先核实本提交 CI，再沿同一来源语义与原目标/回执关联检查剩余消费者，避免换一个场景重复补丁。
- 真机、多日表现与真实模型的人设一致性、自然主动性仍待验；本轮不宣称“活人感已修好”。

### 同轮延伸：成长消费点也沿同一作者契约

提交 `9cd62ec` 后继续核查同一根因，证实 `CharacterDevelopmentStore.ExpressionHabit` 虽尝试核对群作者，却用带 `:group:<见证者>` 后缀的时间线 ID 直接匹配原消息 ID，因此真实群发言无法通过；Interest 分支则仍把所有 CharacterStatement 当成本人的兴趣表达。现两处共用 TimelineStatementOwnership，VerifiedMethod 的本人发言与相邻本人回复也复用同一契约。原成熟度、独立经历、反馈与人设约束保持，没有放宽成长门槛。

既有低频反思输入保留见证过的他人表达，但为每条证据提供 ownStatement 来源标记；提示明确 CharacterStatement 不是“本人的话”的同义词。他人表达可作为见闻，由模型理解，不能冒充本人兴趣或口癖。没有新增反思轮次或删除旧成长历史。

新增同一来源级测试：三条独立真实观察加两条正式群发言，旁人发言不能形成本人表达习惯/兴趣，本人发言能够通过原门槛；双方隔离、证据不混入旁人 ID。该延伸需等待后续提交 CI。既有旧成长记录不自动重写，其他消费点、长预览与目标回执关联仍按后续证据推进。

### 首次本轮 CI 反馈与来源级回归夹具修正

`9cd62ec` 的 run `41576800640` / job `125722080458`：主源码及测试编译成功，2265 项单测中 1 项失败，APK 未生成；新加的三项来源回归通过。失败为原 `CharacterConversationAgencyTest.groupMessageUsesMemberSpecificEvidenceForDeliveredEffects`，其原夹具只手工写一条没有 sessionId/原始作者消息的群事件，却断言本人已送达。新作者契约要求真实群消息来源，不能为通过这条旧夹具重新接受无来源的群发言。

将原测试改为正式 chat Store 建群、分别发送旁人和本人消息：先验证旁人不能产生本人的决策回执，再验证本人送达、重复回调幂等及成员专属 signature；删除本人来源后心理回执撤回，旁人的见证仍保留。保留原测试目的并增加负例，不删除/放宽断言。原每测试时间线资源释放也补齐，避免跨测试使用旧数据库。后续完整 CI 仍须核实。
