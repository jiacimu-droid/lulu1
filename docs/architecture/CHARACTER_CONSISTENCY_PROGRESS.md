# 人物一致性：整体因果链核查与修复

完整跨窗口体验路线与优先级见 [LULU_EXPERIENCE_ROADMAP.md](LULU_EXPERIENCE_ROADMAP.md)：人物活人感第一，角色社交第二，再推进游戏与视频。本文件记录人物专项的真实证据与待验状态。

## 当前整链状态（2026-11-03；历史轮次详见后文）

| 环节 | 当前权威入口与已修边界 | 验证/真实缺口 |
|---|---|---|
| 本人身份与对象关系 | CharacterRuntime 同一快照中的稳定 persona 与 relationships 分项；原世界/隔离模式沿同一网关投影 | 已含于后继成功 CI；不同真实模型是否稳定承接各自人设待验。 |
| 召回与完整含义 | MemoryWorkingSet 按完整上下文块筛选；心事持久所有权与当轮注意分开；本人主观历史完整交接 | 前轮 Android/新 APK 已通过，不重复实现；连续性短预览与固定最近窗口仍待核查。 |
| 理解、情绪与取舍 | CharacterDecisionProtocol、CharacterInnerLifeStore 与既有主动感知；表达承接原选择而非重新决策 | 心声/理由不冒充执行；跨入口程序代决策仍按证据逐项审查，未宣称全部验通。 |
| 本人/他人发言 | TimelineStatementOwnership 从正式 chat Store 核实群作者；连续性、表达参考、消息证明和成长共用 | 初版完整 CI `ea5e22b` 成功；并发及文学修复已随 9dc4271 完整 CI 通过；旧存量成长影响待查。 |
| 多动作与真实结果 | 原 ToolRouter、任务/日程 Store、角色决策/独立回执主循环；634e162 的责任状态修复保留 | 待继续追原 motive/concern/experience/task ID 经每步回执返回下一轮；请求、送达、目标达成保持区分。 |
| 跨角色社交与呈现 | 正式邮箱/群聊/关系图、各方独立接收；文学段落是只读展示 | 消息发送前截断、上下文残句和跨日话题后续待查；文学解析本地9项通过，真机样式与真实模型社交质量待验。 |
| 游戏、电话与视频 | 继续复用原人物主循环和正式状态/音频/表演入口 | 此轮未改游戏/视频玩法，完整路线见大纲；待验项不能视为新增能力已完成。 |

下一轮先核实最后完整 CI，再沿原目标/真实结果关联、旧成长依据与上下文完整性推进；不要把所有角色统一变热情，不重复已通过的结构修复。

## 任务范围（2026-11-02 用户澄清）

哄睡是暴露问题的场景之一，不是唯一开发目标。要让本人稳定人格、关系与经历参与理解、动机、取舍、行动和后续，而不是只有台词或心声像本人。不能把所有角色统一改为温柔/热情/强势监督，也不能用脚本替本人作决定。

只在既有统一人物主循环和权威 Store 上修复，不另建人格、状态或模型审核中心；没有新增常态模型调用。现有每两小时任务改为本整体专项，保留上一轮634e162中已证实的责任状态修复作为子项。

## 首轮链路核查与证据（2026-11-02）

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

### 后续提交与验证跟踪

- `7b8771b`（成长归属延伸）的 run `41577138787` / job `125723683715`：2266 项单测中 1 项失败，仍为上述旧群回执夹具；新增成长来源回归通过，APK 未生成。
- `ea5e22b` 修正原回归夹具并保留原验证目标，run `41577543972` / job `125725920862` 已核实成功：完整 Android 单测、APK 打包上传和发布均成功，`BUILD SUCCESSFUL in 11m 37s`，发布 `lulu-v7370`。包含本轮发言归属及成长源码与四项新增来源回归。真机及真实模型质量仍待验。
- 下一轮继续从原目标/回执关联及持久化/上下文的完整性追起；社交源码另已看到 `ResidentSocialMailbox.send` 保存前 `take(650)`，`CharacterEverydaySocialContext` 对待处理私信另有短预览。需要区分发送协议真实限制与展示预算，核实是否会丢失否定、条件与后半段语义；本轮没有宣称已解决此项，也不靠提高一组固定阈值冒充根因修复。

### 同轮锁顺序复核：作者证明读取不拿聊天写锁

源码复核发现本轮初版 `TimelineStatementOwnership.original` 复用了 `chat.messages`，后者会获取聊天 mutation lock。`CharacterInnerLifeStore.observe/recordActionResult` 等持心理锁时会校验行动证据；而 `chat.editMessage` 持聊天写锁调用时间线撤回，又需要心理锁，构成反向锁依赖。上一次 CI 通过不代表这个并发交错安全，不能把它留给真机暴露。

沿原权威 messageStates 将容器改为 ConcurrentHashMap，原写入和存档流程继续使用既有 mutation lock；增加仅读取已存在消息的 `recordedMessage`，通过原 StateFlow 的不可变 List 快照读取，不创建默认会话/消息、不复制第二份缓存。作者校验改用此入口，避免持回执/心理锁时获取聊天写锁；失败/删除的源消息仍立即失去证明资格。

新增有界并发回归：主线程持有实际聊天 mutation lock 时，另一线程仍必须完成作者核验；再验证编辑、失败和删除反映在原消息读取中。这验证读取不逆向取锁，不宣称覆盖整个项目全部并发时序。此项后续提交 CI 待验。

提交前已同步保留其他窗口关于居民日活动预算、社交机会和文学呈现的 main 改动；它们的独立构建状态与本轮 `ea5e22b` 成功分开记录，不能以本轮 APK 代表之后新增源码都已验通。

### 并行 main 的真实 CI 阻塞与呈现归属修复

- `4c126dd` 的 run `41579634284` 尚未开始便被更新的 main 排队运行替换，结论 cancelled，不能算通过。后继 `9c18fab` 保留本轮并发读取修正，其 run `41579749559` 为后继验证入口之一；后续以包含修正的实际完成构建为准。
- 并行 `4f08237` 的 run `41578779009` / job `125730074175`：2271 项单测中 1 项失败，为 `WorldLifeEpisodeParagraphsTest.naturallyQuotedParagraphIsDialogueButInlineQuoteStaysNarration`；APK 未生成。这是新增文学呈现中的真实失败，不混作本轮作者来源测试失败。
- 源码 `namedQuote` 把任何 1–18 个字的冒号前缀都认作名字，导致“他说：……”被显示成姓名对白；明确【叙述】标签也可能被这个猜测覆盖。现无标签姓名对白只匹配原角色/居民登记中的实际显示名，未知前缀保留整段原文为叙述；明确叙述/环境/动作标签保留原类别。显式对白标签继续兼容，完整引号段仍可独立展示对白。
- 新增未知前缀原文保留、明确叙述优先和自定义角色名“他说”的回归，不加“说/问”等语义黑名单，不据此写任何人物事实/记忆或行动。本改动仅决定已有文学原文的样式；姓名集合不代表角色观察到了登记中的其他人。
- 本地使用仓库同版本 Kotlin 2.0.21 和 JUnit 4.13.2，逐字提取生产 Reader 文件中的 paragraph enum/data class/parser（排除 Compose UI）并编译原 `WorldLifeEpisodeParagraphsTest`：9/9 通过，含原失败场景和两项新增回归。此结果仅验证纯 JVM 文学解析，不代表 Compose 真机、并发来源测试、完整 Android 单测或新 APK；后者仍以 main CI 为准。

### 含并发修正的后继验证

`9c18fab` 的 run `41579749559` / job `125732182807`：2272 项测试中 1 项失败，仍只有上述文学引述分类用例；新增作者读取并发回归及其他人物来源回归通过，APK 未生成。不能因为这一项不属于人物 Store 就把整个失败 CI 记成通过。

`9dc4271` 已合入文学呈现修复；原段落解析断言保留，并新增两项。run `41580661801` / build job `125736324750` 已核实 success：正式 Android 单测、APK 打包、上传与发布全部通过，含全部人物作者、成长、并发修正、文学修复及已保留并行 main 改动。真机、真实模型质量和跨日表现仍待验。


## 2026-11-03 记忆专项检查与大纲记录

按用户最新要求仅检查并记录，已对照作者 eMEM 论文/仓库和本项目正式记忆链路。结果与 M1–M6 路线写入 LULU_EXPERIENCE_ROADMAP.md，并在 AGENTS 添加入口。已有语义/时间召回、关联链、来源修订、整理及工作集必须复用；新确认的接口缺口、长事件截断风险与待测性能成本分别标注，本轮没有接入 eMEM 或修改运行时代码。

## 2026-11-03 续轮：连续性锚点的双方原话完整性

最新 main 的 CharacterContinuityRuntime.render 对上一条用户原话和角色本人原话都使用 `take(180)`，却仍以“真实说过”作为完整陈述。原话末尾的否定、许可边界和条件因此可能在进入现有工作集预算之前消失，改变下一轮本人判断。现完整交接两方原话；过长时仍由 MemoryWorkingSetPolicy 按原有上下文预算选择完整辅助块，不增加模型调用、不改持久数据或角色选择。预算不足可整块省略，长期召回和更细粒度的可追溯长事件分段仍待后续 M1 工作，不宣称此轮解决全部长记忆问题。

新增原连续性测试：双方发言超过原限制、尾部含否定与条件，渲染结果必须完整包含两段；静态差异检查与原索引生成器已运行。完整 Android CI、真机和真实模型表现待核实。前轮 `9dc4271` 的 run `41580661801` 已通过 Android 单测、APK 上传和发布；其他窗口后续 main 变更的 CI 需分别核实。

## 2026-11-03 续轮：想法、电话副动作与表达意图的所有权

用户要求优先检查活人感，沿同一人物“主观想法 → 选择 → 真实行动 → 表达/回执”追正式私聊与电话入口。本轮在最新 main 上证实两处跨角色机制断点：

- `CharacterDecisionProtocol.speechIntent` 在规划 JSON 解析时截取前 800 字，然后前台私聊表达模型只接收这一份已裁过的选择；位于尾部的否定、必要答案或条件可能在工作集预算前无标记丢失。现保留本人完整意图，仍由既有表达模型用本人人设和事实回执组织台词；没有增设模型轮次。
- 电话 `LuluDeviceToolBridge` 原先对 `innerThought/text/responseAim` 运行 `CharacterPhoneFavoriteIntent` 关键词规则，推断 `phoneFavorite` 副动作并挑最新消息执行；另会自动把“那句我收好了”拼进实际声音。`CharacterDeferredFavoritePolicy` 还把心声/台词里的收藏字眼自动写成长期挂心。这些都把“想了一下/说了打算”变成程序代选动作、心事或本人台词。现仅明确的 `phoneFavorite`/已有 `tool`/`steps` 选择可交执行器；准确消息 ID、可见范围和来源由现有 ToolRouter 核验，独立保存选择与执行回执，失败不伪作成功，台词保持本人原文。模型可用既有 `appraisal.pendingConcern` 明确留下真实未完意愿；不再由词表替他开挂心。保留原始历史，不自动重写旧主观记录。

新增协议回归验证长意图尾部条件；MockWebServer 正式入口回归验证前台表达模型实际收到完整意图，以及电话心声不能触发收藏、明确副动作可收藏、播报不拼固定台词。已重新生成原代码索引并通过 `git diff --check`；本轮 Android 单测/APK、真机、真实模型自然度和跨轮真实回执召回仍按各层后续结果单列，不用固定假模型台词宣称体验验收。前轮 `7510f07` 的 APK run `41585401392` 被并行 main 更新取消；`34828ef` 后继 Lulu APK run `41585940797` 在本轮查询时 pending，且包含该修复，完成状态须另查。

下一轮继续审查后台自主感知的心理交接、真实副动作与目标 ID，以及工具后回复是否把完成/失败交回同一人物；尤其检查程序语义规则是否覆盖有效模型选择。不同角色对同一情境的真实语言与主动性需要真机真实模型对照，不能只靠提示词/模拟 JSON 断言。

## 2026-11-03 续轮：同文不同来源的原始证据去重归属与接力机制

- 接力方式：统一状态看板见 LULU_EXPERIENCE_ROADMAP.md 第七节；AGENTS.md 新增每轮必读、勾选与交接契约，不再依赖窗口记忆或重复重列大纲。先对照最新 main 与后继 CI，按固定 ID 续修。
- P-04 已证实根因：MemoryWorkingSetPolicy.select 的 `payloads` 仅按归一化文字去重，未校验 `sources`。不同 eventId 的原话即使内容同字，仍代表不同时间、身份和事件；后一个被错误省略，影响下一轮本人判断与来源追溯。
- 本轮源码候选：将去重键改为“文字 + 来源集合”；无来源的重复文本依旧去重；全来源已经由完整原文证明的派生摘要，仍保持现有去重策略。新增一条纯 Kotlin 单测覆盖同文不同事件、同源派生摘要与无来源重复。未修改心理、用户原始记录、工具执行和决定权限。
- 验收分层：提交后等待索引自动工作流执行原 `scripts/generate_code_index.py` 并刷新两份索引；Android 单测及 APK 构建结果以对应提交 Actions 为准；真机/真实模型与跨日仍未验证。CI 通过后只代表 P-04 源码级边界验收，不代表人物活人感全线已验收。
- 下轮入口：核实 P-04 的 CI，并优先继续 P-05 长记忆中段完整含义与 P-08 后台自主感知的原目标→真实回执交接，避免重做已有 P-01/P-02。

- 本轮提交证据：实际功能/测试/接力大纲提交 [`e6f423d`](https://github.com/jiacimu-droid/lulu1/commit/e6f423d90e67b8862cf2e26b6c0fae00a45b432c)，修复提交 [`ba1b161`](https://github.com/jiacimu-droid/lulu1/commit/ba1b161d3b98ac35e849875751fcc93e86b9f3e7)。后继索引 run [41588367622](https://github.com/jiacimu-droid/lulu1/actions/runs/41588367622) completed/success，已按原生成器刷新 `CODE_INDEX.md` 和 `code-index.json`（索引基准 `ba1b161`）；对应 Android APK run [41588367637](https://github.com/jiacimu-droid/lulu1/actions/runs/41588367637) 后来被后继 main 新构建替换而 cancelled；不是构建成功或失败，仍须核实包含本修复的后继完整 CI，不能打勾或宣称 APK 成功。P-04 保持 `[ ]`，待该层真实结果。


## 2026-11-03 接力续轮：P-05 长原始记忆中段证据（本轮源码候选，尚未 APK/真机验收）

- 根因：完整网关 MemoryWorkingSet 在大于 3000 字时固定只传首 2200/末 650，紧凑网关 MemoryEvidenceSelection 固定只保留约 1200 字前缀；它们都在原有事件被检索后、本人判断前遗漏中部否定、条件及后续修订，不是长期时间线丢数据。
- 实现：新增只读 OriginalEvidenceSpans，沿正式 UnifiedMemoryContext.retrievalFocus 复用当前输入/任务语境中的检索词，仅用于原始片段排序，绝不推断主人意图或代选动作。长事件保留精确字符区间、eventId@revision、说话者、来源种类和缺段提示；首/中/尾作为无相关词时的回退候选，原始持久记录不动。完整工作集按现有类别/总预算选择分段，未纳入的范围留在原时间线且有分段 omitted ID；紧凑入口对长事件选当前最相关的一段并明确其范围，不再只看无标记开头。无新增模型费用或存储权威。
- 补源级单测：超过旧截断阈值的中部“未确认不得执行”、事件 revision 变化、紧凑版精确中段与缺段标记、无检索焦点时首中尾候选及原文 offset 恒等。单测/Android CI、索引与 APK 必须以实际提交后的 workflow 为准；已有 P-04 的旧取消 run 不能充作通过。
- 尚未声称实现：完整自然语言指代消歧、把任意长消息一次全塞进模型、独立范围读取工具、所有场景真机召回正确性或 M2/M3 结构化查询。下一步核对 P-05 CI 与旧源撤回回归，再继续 P-08 后台感知→动作→真实回执跟进。

### P-05 提交与 CI 交接（2026-11-03）

- 业务与首轮回归提交 `df7047d4bfbf57ce2f715cc7120565f8148ee3cf`；修正原文精确字符位置、短文本兼容、无关键词首中尾候选提交 `e63379a055f438c96387874a5ed1b3f18f7c4033`；新增预算压力、来源撤回与角色隔离回归提交 `f739f79c1021d541a47ef04c9f68b6002757afb9`。
- Kotlin 独立片段选择冒烟测试（隔离版，并非仓库完整 Android 单测）通过。现有索引工作流 `41590827498` 已 completed/success，使用原脚本刷新索引到 main。Android 单测与 APK 的后继运行 `41590827327` 本轮检查时仍 pending；取消/排队不可视为通过。
- P-05 保持 `[ ]` 待验：提交证明已修候选代码，不意味着全部场景、真实模型、长工具回执与源事件修改/删除生命周期均已通过。下一轮先查该 CI 的第一失败步骤或成功证据，再决定是否缩小待验范围；后续继续 P-08 后台感知至真实回执的目标关联。

## 2026-11-03 P-08 表达支线：现代聊天世界书与句末感叹号密度

- **真实反馈**：江渡普通调侃连续多条气泡以感叹号收尾，语气像持续喊话。截图能证明表现问题，**没有当前手机实际模型请求原文**，不能断言是单独某条世界书造成；尤其不能对所有角色做句末标点的程序清洗。
- **生效链路**：`InMemoryWorldBookRepository.initialize` 首次写入全局开启、`BOTTOM` 的《现代聊天与自然表达》；`CompanionModelGateway` 在 `Full/CharacterAndScenario` 按世界书自身开关和角色覆盖注入模型系统提示末尾。它是全角色可见的表达*参考*，并非江渡本人稳定人格；未强制每句感叹号。原示例“等等等等！”、连续气泡及情绪化标点说明，可能给普通轻松聊天不必要的高情绪示范。
- **已提交的候选修复**：将内置正文改成不同情绪/标点均可自然出现的场景式参考，明确普通解释、猜测和轻逗并非每泡都需要兴奋；保留完整答疑和每个人自己的语言习惯。只改内置书，没有替所有角色加“禁用感叹号”文本、后处理正则、硬频率或额外模型调用。旧版书只首次写入的缺口以 `human_chat_world_book_revised_v2` 一次升级解决：仅当稳定 ID、原始标题与旧内置正文**逐字相同**时换成新版；用户修改的正文/标题、开关、角色覆盖、位置、文件夹和既已删除的状态不被覆盖或复活。GitHub main 提交：[`df1f20c`](https://github.com/jiacimu-droid/lulu1/commit/df1f20c449d494820dfbfe86b9f60af7e7825137)、[`2ebdca9`](https://github.com/jiacimu-droid/lulu1/commit/2ebdca9a3719dec9a2c655869e744ac391827e2a)、[`29f2d13`](https://github.com/jiacimu-droid/lulu1/commit/29f2d1392c81e03015d83db90c56fadcfef2af50)。
- **回归源码**：`WorldBookFolderRepositoryTest` 更新新内置正文断言，新增旧库存完整正文升级与开关/覆盖/位置保留、用户自改后重启不覆盖、删除不复活、改名不覆盖。**测试已提交，不等于测试已运行通过**；原仓库 `code-index.yml` 应按 `app/**` 推送自动执行索引脚本，索引产物、完整 APK CI、真机真实模型和跨场景自然度均须由后继实际运行证据核验。
- **后续观察**：新 APK 中分别对普通解释、轻逗、真惊讶做多轮私聊，比较标点和说话节奏；若仍有全局高能量语气，收集当轮实际请求的世界书、人物表达偏好、对话示例和模型输出，再确定是否存在另一重复风格指令。此项属于 P-08 活人感体验待验，不勾选整体架构验收。

## 2026-11-03 P-08 补验：世界书文化参考与接词小游戏的回合感

- 用户真机截图：轮流接词场景中，江渡一次连续输出多条夸张旁白、解释及替对方设想的下一轮，不符合他原有松弛、机灵但有分寸的人设。截图未包含完整实际请求，也未能证明手机已安装此前最新世界书版本；不能据截图直接断定新书已生效或某模型输出原因唯一。
- 已证实的提示组装事实：`CompanionModelGateway` 的 `Full/CharacterAndScenario` 读取本人完整分层稳定人格与关系、按条目开关注入世界书；这本内置全局书初次启用时位于 `BOTTOM`，其说明位于系统提示靠后。它本应描述社会交流习惯，但旧内置版本包含高情绪台词示例和“怎么表达”的建议，可能给不同人物注入同质 few-shot 风格；不能据此修改所有角色的个性或设置固定短答限额。
- **本轮 main 源码候选**：`CharacterExpressionGuide` 在本人统一决策和原表达渲染中明确“文化参考≠人格”，在现有 `casualChatRhythm` 为共同活动补上真实回合单位的语义指导：角色自己理解规则、选择本轮贡献、可以只出一个词并让对方继续；规则解释/多项求助和长谈按需充分展开。渲染时在**已有同一调用中**内部对照完整分层人格和已选意思，不生成外显检查报告，不追加独立付费核查模型；没有用关键词判定游戏，也没有执行器裁字数。
- 内置《现代聊天与自然表达》v3 改为现代即时通讯/轮流互动的**描述性文化参考**，不再提供固定对话台词或情绪范本。仓库的旧 v1、v2 正文保留为精确比较夹具；首次初始化或版本升级只在稳定 ID + 原标题 + 完全相同旧正文时更新正文。旧开关、角色覆盖、顺序/位置、用户重命名或编辑、删除状态不修改，升级 `human_chat_world_book_revised_v3` 是可持久重启的一次操作。
- **提交**：[`3d73cb8`](https://github.com/jiacimu-droid/lulu1/commit/3d73cb890559cc7e1ab93da309685b136e9fccd3)（表达回合）、[`a8f05ca`](https://github.com/jiacimu-droid/lulu1/commit/a8f05ca6e4fa489702cde56aa01dcc84712c96d1)（文化书）、[`c996c37`](https://github.com/jiacimu-droid/lulu1/commit/c996c37812a0b1cc445735083701a4a880e175ac)（存储升级回归）、[`812f529`](https://github.com/jiacimu-droid/lulu1/commit/812f52925ba5b497e423adcc619a730dd3fdb49c)（全入口表达契约回归）。
- **验证分层**：已重新读取 main 正式文件，静态比较原 v1/v2 完整正文与测试夹具均字节级等价（此处为文本内容比较），新书/表达指导存在；原自动索引工作流已将 `docs/architecture/code-index.json` 的基准 `commit` 刷新到 `812f529`。新增 Android/Robolectric/JUnit 测试**尚未获得完整 Actions 通过证据**，APK、真机真实模型质量及跨日仍待独立验收，P-08 保持 `[ ]`。
- 下一轮：优先检查包含 `812f529` 的 Actions 全套 Android 单测/APK 是否通过，并在安装后对比游戏每回合单词、纠正规则、认真谈心三个场景；若仍持续过长，取同一轮实际模型请求中的本人配置、世界书、已选 `speechIntent/dialogueMove` 和模型原文确认偏差来自哪个环节，而非继续叠加硬标点或硬短句规则。

## 2026-11-03 P-08 再修：共同建构对话，而非助手式一次性回答

- **用户澄清的真正目标**：不是限制回复字数、气泡数或要求每个角色安静，而是现代熟人聊天在一个具体的回应、接梗、反馈或活动动作完成处自然让出话轮，让双方根据下一次真实回应共同建构后续理解。AI 助手倾向一轮覆盖整个问题的所有信息；人物真正交谈时，理解可以完整、说出的贡献却由当前互动决定。独立主动分享、认真求助、长叙事、重要行动与深谈仍可充分表达。这是公共的会话组织方式，不改变单个角色长期人格。
- **现有架构定位**：私聊模型规划的 `privateConversationInstruction` 复用 `ConversationDominantImpulse.planningGuidance`；前台双阶段渲染使用 `CharacterExpressionGuide.forGeneration` 和 `casualChatRhythm`；原世界书以 `CompanionModelGateway` 的 `Full/CharacterAndScenario` 注入；人物分层设定仍是独立权威 `CharacterRuntime.definition`。因此只在共享的**已有**决策/渲染语义和世界书背景动手，而不是再建第二个长度控制器或每次付费检查调用。
- **本轮主代码**：`CharacterExpressionGuide.casualChatRhythm` 由“记得少说/多留白”的结果倡导改为“听懂两人正在共同做什么→当前一拍自然完成点→交还未知下一拍”的社会协作说明；保留先前测试契约和原有人设首位、游戏回合、跨场景主动联系、完整答疑。原 `rendererInstruction` 只让已选的交流动作落成台词，不需要把整个话题的所有可能信息一次讲尽。`ConversationDominantImpulse.causalRule` 在本人决策处加入协作与共同语境：一轮的用户反馈尚未发生，就不能代替她补完后续故事；本人既可短接话，也可自发热烈分享与长谈。原模型独立决定权、记录与动作回执保留。
- **v4 内置世界书**：讲清人类自然话轮的共同语境、行动完成点、逐次确认与个人差异，保持**描述性背景知识**而非固定句式/人物气质命令。迁移额外识别完整原装 v1、v2、v3 正文；只有原 ID、原标题和逐字相同旧正文才更换内容，`human_chat_world_book_revised_v4` 单次持久保存。保留人物覆盖开关、全局开关、所在文件夹、TOP/NORMAL/BOTTOM 位置、用户手改/删除/重命名及全部原始记录。没有增加世界书新入口。
- **已提交到 main**：[`2034cbe`](https://github.com/jiacimu-droid/lulu1/commit/2034cbe98df025120765370e9705b74f7c37ead1)（共享表达与回合）、[`0ff5445`](https://github.com/jiacimu-droid/lulu1/commit/0ff54453371eac5e6d3b9b4113d2cd6e8b0541a6)（本人决策）、[`fa5ce88`](https://github.com/jiacimu-droid/lulu1/commit/fa5ce886e9a51e3962fceaedfcccd35cd8dc044d)（世界书 v4）、[`5f6b2f8`](https://github.com/jiacimu-droid/lulu1/commit/5f6b2f84de40dd7c3002e0eef9ed272550fd9eed)（库存迁移回归）、[`e9a34b2`](https://github.com/jiacimu-droid/lulu1/commit/e9a34b2f22607de5a9041dff8c82dfd42ee50f65)、[`ffa5935`](https://github.com/jiacimu-droid/lulu1/commit/ffa59356d906639a5ffc9561b9aebb2caeb9e784)（语义提示词契约回归与修订）。
- **静态核验**：重新取 main 文件核对 v1/v2/v3 三份旧正文与测试 fixture 内容完全一致，v4 更新标记、新的原理提示、保留开关等分支在正式源码中；原自动代码索引已刷新到基准提交 `ffa5935`（只证明索引生成，不代表单测/APK 通过）。
- **未验**：完整 Android/Robolectric/JUnit 测试、APK 产物、安装后的真实模型多回合风格、长期不同人物的差异与接词响应仍需对应 Actions/真机证据；没有实际该版本模型请求不能宣称过长聊天已经解决，P-08 保持 `[ ]`。下一轮先核实最新构建，再比较短游戏、普通问候、自然玩笑、认真求助、深度谈心与角色独立主动分享；若仍不对，再查看当轮真实 prompt、`speechIntent/dialogueMove` 与最终输出，而非进一步增加感叹号/字数禁令。

## 2026-11-03 P-08 补充：即时直觉反应与审慎说话共存

- **反馈与理论边界**：用户指出人物不是每句话都经过完整的情绪归因/理性分析后才说出口；熟悉的关系与现场刺激可能引发直接的笑、接梗、短反驳或关心，也能先停顿再认真考虑。双加工（直觉/审慎）只是解释认知差异的一类理论，不是左脑/右脑的性格分工、两套实际脑模型，更不是让模型固定按“先冲动→再逻辑”演戏（参阅 De Neys 2021、2025 双加工讨论及脑半球分工研究）。不将模型模拟的即时语气声称成真实神经反射。
- **源码核查根因**：原 `CharacterDecisionProtocol.runtimePrinciples` 第 6 条统一写成“形成关注点与立场，再选择愿意说的意思”，与此前强调整体因果、审慎人设核对及前台计划→表达的组合，容易让即时接话也倾向事后形成一套分析叙述；`SpontaneousInnerVoiceGuide` 已允许一闪而过的直觉，但主要落在**未说出口**的心声指导，缺少对已直接说出口反应的明确归属。此为可确认的提示契约倾向，不等于已证明真机过长回复的唯一根因。
- **main 候选修复**：`ConversationDominantImpulse.causalRule` 让同一角色自行选择即兴接话或审慎回应；瞬间感受不要求情绪定义、利弊报告或强制附加表演。已有 `expressionRule` 传递所选意图，不许把短反应重新润色成详尽心理报告。`CharacterExpressionGuide` 的人物起点、渲染检查、私聊轮次保持本人语感，允许直觉式自然接话的停顿与临场感、审慎回应的必要事实/边界；人格核对不等于把每句话打磨得完美圆满。统一运行第 6 条允许直觉与认真权衡共存，`appraisal.expression` 仍可选，正常临场反应不强制填写心理表。心声指南新增已说出的直觉不再另写 `innerThought`，只有真正未出口的新变化才写。
- **不改变的边界**：同一模型角色与真实人格、现有 `speechIntent/dialogueMove/action` 选择、既有权限/执行器/结果回执、可选心声和持续目标不变；没有双独立脑模型、关键词划分“快慢脑”、随机语气、强制先快后慢步骤、每轮新增付费调用或固定长短限额。内置世界书仍仅解释社会交流背景，非人物内在认知机制；避免不断叠加新的全角色“说话模板”。
- **源码与测试提交**：[`959981f`](https://github.com/jiacimu-droid/lulu1/commit/959981f2a58de2c358c44678de6c0fac6fcfa50d)（主导交流）、[`b9ad7a7`](https://github.com/jiacimu-droid/lulu1/commit/b9ad7a7c46f128efdfcdf136ac1c8957a48db521)（表达落地）、[`4020a8e`](https://github.com/jiacimu-droid/lulu1/commit/4020a8e629b201e5a326ef566667080358af58e1)（统一决策第 6 条）、[`3a4a7ec`](https://github.com/jiacimu-droid/lulu1/commit/3a4a7ecb83e1f2a58be747f7affc100a40122007)（外发与心声归属）、[`07de67a`](https://github.com/jiacimu-droid/lulu1/commit/07de67ae2f08a60ec53667ae1545ac45d70beb24)（提示契约回归）。
- **已核验/待验**：提交后再次从 main 正式源码确认上述五处变更均存在；`code-index.json` 已由原索引机制刷新到至少包含本轮改动的后继提交 `0506c230`。目前**仅有静态源码核对与已提交测试**，未核实完整 Android 单测、APK/真机与真实模型“即时反应”表现，故不能宣称活人感验通，P-08 继续 `[ ]`。后续可用同一角色分别对照意外调侃、自然游戏回合、重要事实纠正、复杂求助，检查即时接话没有被转换成冗长总结，认真任务没有被短句化；对比模型实际结构化选择及所发文本，寻找具体失败层。

## 2026-11-03 P-08 Issue 复核：会话动作的程序默认值与三气泡示范

- 用户回复 `issue` 在当前连续修改上下文里表示按 `AGENTS.md` 检查并修复本轮活人感代码，而非开 GitHub Issue。
- 核对正式路径 `LuluDeviceToolBridge.respond` → `DialogueMoveEngine.resolve` → `expressionConstraint` → 同一个人物 `CompanionModelGateway` 表达：已证实 `resolve` 对无 `dialogueMove.type`、不认识的 type 与 legacy interactionMove 都无明确类型时，使用 `DialogueMoveType.ANSWER` 兜底，这由程序代替人物将即兴接话变成“回答”。同时前台“重新回复”的结构补全提示固定范例 `type=answer,maxBubbles=2`；在线私聊 `ProactiveDecisionPrompt` 范例固定给出**三条气泡，其中两条 voice**，可能为普通接话提供不必要的模型模仿锚点。这些是真实源码缺陷/风险，不将其宣称为已经在真实 API 中唯一致因。
- 已修：对无明确有效会话动作使用 `DialogueMoveType.UNSPECIFIED`，表达提示明确“未知，保留本轮 speechIntent”，不冒充人物选择 `answer`、不重做人物选择；若模型明确选择 `answer`、`tease` 或 legacy 已知动作，保持原动作与内容，真实执行/记忆/权限不变。补全提示改为**本人按场景选**有效 dialogueMove 类型，不再示例固定 `answer` 或两气泡。在线私聊结构示例改为单条 `text` 或按真实意思选择 `bubbles`，清掉三条语音的默认模仿示例；没有给程序新增字数、句数或气泡上限，也没有增付费模型回合。
- **已提交**：[ `c9867ba`](https://github.com/jiacimu-droid/lulu1/commit/c9867baf4524ffa0eff75d4486cfb5bdc819ea46)（未知动作不当回答）、[`29f2600`](https://github.com/jiacimu-droid/lulu1/commit/29f26005a54bb657327a3d167fd1f64c64a9134a)（在线示范）、[`9713ab1`](https://github.com/jiacimu-droid/lulu1/commit/9713ab1e94e91336df383c18b223c97c9e46cd09)（补正模板）、[`8d6d064`](https://github.com/jiacimu-droid/lulu1/commit/8d6d0645c501c32ff7277ef4619a3cc474015694)（模型动作归属回归）、[`6b8b009`](https://github.com/jiacimu-droid/lulu1/commit/6b8b0090813de5539bc398a2f6bc35cdec346063)（气泡示范契约）、[`0ad0f21`](https://github.com/jiacimu-droid/lulu1/commit/0ad0f21157d4a1fdb56d65909fd1595066b920ba)（模拟模型请求端到端断言）。
- **验证层级**：从后继 main 重新读取正式源码，`UNSPECIFIED` 兜底、表达提示、三气泡模板移除、补正模板移除、两项单测及一个前台双阶段 MockWebServer 回归均静态可见；代码索引生成的 `code-index.json` 在本次查看时基准 `7ef42f9`，尚未包含所有后继提交。GitHub 连接的 workflow-run 查询只暴露 PR 触发的运行，因此对上述 push commits 返回空列表**不能证明无构建或失败**。完整 Android 单测、后继 APK 与真机模型效果仍未获得验收证据，P-08 继续 `[ ]`，不擅自关闭。
- 下一轮：先查能够覆盖 `0ad0f21` 的实际 Actions 构建与测试结果，必要时修对应失败；真机分别验“即时接梗只接一拍 / 接词轮流 / 认真求助详细答复 / 缺失 move 时保留原 speechIntent / 自发主动分享仍可多气泡”，如果仍出现兴奋长独白，收集模型实际 planner/renderer 提示与回应归因，不继续叠加针对截图字词的硬编码规则。

## 2026-11-03 P-08 Issue 续验：真实 CI 四处单测失败修复

- **真实 CI**：[Lulu APK run 41604264642](https://github.com/jiacimu-droid/lulu1/actions/runs/41604264642) (head 7b4c0b5) 在 app:testPhoneUnitTest 上运行 2306 tests, 4 failed；非 Gradle 编译失败。读取 job 125811396981 的完整日志，失败为 CharacterDecisionProtocolTest.kt:25、CharacterExplorationContinuityTest.kt:215、CharacterMotivationOwnershipTest.kt:59、PositiveExpressionGuidanceTest.kt:96。前三处是共享 runtimePrinciples.length 小于 3000 的预算断言，第四处是测试引用原话少写了“的”字。严禁为了绿灯删除测试或放宽预算。
- **代码修复**：合并精简 CharacterDecisionProtocol.runtimePrinciples 第 4/6/8/9 条的重复描述，仍保留原先内心直觉与认真权衡、人物本体、行动/目标 schema、真实权限/回执、作品和用户意图隔离及未完成目标跨轮复核。使用实际源码的 trimIndent、recordOwnership、CharacterExperienceLearning.runtimeInstruction、CharacterExplorationContinuity.runtimeGuidance 和 CharacterThoughtActionHandoff.runtimeGuidance 合成，静态核算最终 **2932 字符**（限制严格小于 3000），且所需关键字段和直觉/审慎说话契约都存在。
- **测试修复**：PositiveExpressionGuidanceTest 对共同建构会话采用实际源码语句“对方的下一步反应仍是未知”，保留其语义要求，没降低测试门槛。
- **main 提交**：[7b72264](https://github.com/jiacimu-droid/lulu1/commit/7b72264685eb91ba95cbcf9f4d813ec727efc063)、[f42ac41](https://github.com/jiacimu-droid/lulu1/commit/f42ac41637dfb255bfff6386bf22921d2f20aafe)、[01e4853](https://github.com/jiacimu-droid/lulu1/commit/01e48532f70edeb49a815cc6c2279eed0b005d78)、[1dda540](https://github.com/jiacimu-droid/lulu1/commit/1dda540ea188452981a03c3cc519c97f3fd1b5d5)。
- **验证状态**：已对照真实 push Actions API，而不是只能返回 PR 的 commit workflow wrapper。旧 7bfd373 的 APK 在查询时运行中，f42ac41 的 [run 41606057097](https://github.com/jiacimu-droid/lulu1/actions/runs/41606057097) 在查询时 pending；后继 1dda540 的最终 Android 单测、APK 产物及真机自然度仍须真实验证，不能把静态校验当作运行通过，P-08 继续保持 [ ]。
- **下一轮**：先读取包含 1dda540 的后继 Actions 最终结论、若失败则精确追第一失败日志；若 CI 通过再核对 APK 及真实角色即兴接话、接词、详细求助、自主分享的对照效果，确保无程序代决定和统一人设风格。

## 2026-11-03 P-08/S-01 续轮：本人选定私信的完整语义到收件人

- **基线及前轮 CI**：本轮从 main `100128c` 开始。`1dda540` 的 push [Lulu APK run 41606278395](https://github.com/jiacimu-droid/lulu1/actions/runs/41606278395) 状态 success，含前轮四项失败后的代码修正；索引 run `41606278301` success。此层级证明 Android 工作流完成，尚无真机真实模型自然度证据。
- **根因**：`CompanionActionRuntime.execute(send_character_message)` 对模型明确选定的 `args.text` 取前 650 字，`ResidentSocialMailbox.send` 再对所有发送者取前 650 字并把残句持久化为真实私信和双方时间线观察。`CharacterEverydaySocialContext.render` 对待处理信只给收件人前 230 字，却让其决定是否回复。用户代发手机编辑器还静默拒收第 651 字。后半段的否定、时间条件或问题可能没有送达/没有进入本人决策；属于公共内容所有权与证据边界错误，不是单个角色不够热情。
- **候选修复**：执行器与唯一邮箱保留发信人完整原话，双方的真实时间线仍由原邮箱写入，ToolRouter 权限、联系人可见性、消息 ID、撤回、收件唤醒不改；待处理来信在既有本人社交上下文完整交接，旧最近通信仍只是预览；用户代发编辑器也不静默切断输入。未新增 Store、模型调用、自动社交或统一语气。
- **回归及边界**：`ResidentPhoneWakeIntegrationTest` 通过正式 `send_character_message` 发出超过旧阈值、末尾有否定和条件的信，断言双方邮箱、收件人原时间线与下一轮本人待处理上下文均保留尾部。原索引生成器及 `git diff --check` 已运行。本轮 Android 单测/APK、手机收件和真实模型如何理解仍待提交后的实际结果；大规模多年邮箱存储/上下文预算需另作测量，不能以本次测试宣称长期性能验收。下一入口仍追 P-08 的目标 ID、多步骤回执与工具后本人表达。
- **提交与工作流接力**：源码与回归已在 main [`fc94099`](https://github.com/jiacimu-droid/lulu1/commit/fc9409985094175260c3d4559edc42efcca03afc)，索引后继提交 `b6b558f`。索引 [run 41612476419](https://github.com/jiacimu-droid/lulu1/actions/runs/41612476419) success；APK/单测 [run 41612476493](https://github.com/jiacimu-droid/lulu1/actions/runs/41612476493) 最终 completed/success，其 Test and build phone APK、Collect APK、Upload temporary APK、Publish Lulu release 步骤均 success。公开 API 一度限流，之后通过已授权仓库接口复核最终结论。真机、真实模型、跨日质量尚无本轮证据，P-08/S-01 继续 [ ]。

## 2026-11-03 P-08/S-01 续轮：居民每日选择的完整社交正文

- **最新源码证据**：上一轮修复了正式私信执行器与邮箱，但 `ResidentDailyDecision.runOnce` 在每日模型决定已经持久化之后，仍对直接 `message`、`post` 使用 `take(500)` 再交给正式 ToolRouter。动态路径还在 `CompanionActionRuntime.publish_moment` 和 `MomentsStore.publishCharacter` 二次截到 2000 字。居民本人选择若把否定、邀约条件或不确定性写在后半段，实际投递/时间线会变成另一句话；这是每日入口与公共执行/持久化职责断开，而非模型选择不主动。
- **本轮改动与回归**：保留每日决定原本已保存的完整正文，交原 ToolRouter 执行；角色动态执行与唯一动态 Store 也保留角色实际选定的正文。沿已有 `ResidentDailyContinuityTest.dailyDecisionKeepsIdentityMotivesAndMailAndSavesIndependentReceipts` 的真实每日模型响应、收件/发圈/回执路径，把私信尾部条件和超过 2000 字动态的末尾不确定性一起断言。用户自己发动态的入口规则不在本轮修改。无新 Store、自动发言或常态模型调用。
- **验证边界与下一轮**：前轮 `fc94099` 的索引及 Android APK 工作流均已核实 success。本轮静态检查、原索引生成器及 CI 结果以下方提交记录为准；假模型回归仅验证选定文本到真实投递的契约，不证明真实模型自然生成这些表达。真机长文、动态 UI、真实模型与跨日关系发展待验。继续查 P-08 目标 ID、逐步回执在前台/电话/后台的接续；P-08/S-01 仍 [ ]。
- **提交及 CI 接力**：本轮源码/回归已在 main [`b34df2e`](https://github.com/jiacimu-droid/lulu1/commit/b34df2ecc812e62cd7c6c7997fa30538d5ac6d74)。索引 [run 41628871485](https://github.com/jiacimu-droid/lulu1/actions/runs/41628871485) completed/success；Android [run 41628871572](https://github.com/jiacimu-droid/lulu1/actions/runs/41628871572) 截至本次查询仍在 Test and build phone APK，未判定成功。此前并行 Live2D 的 [run 41617409662](https://github.com/jiacimu-droid/lulu1/actions/runs/41617409662) failure，失败产物 `gradle-build.log` 在 `ResidentDailyContinuityTest` 运行中中断；该工作流步骤有 40 分钟超时，不能据此归因本轮源码或宣称某个断言失败。下一轮先核实本轮 CI 的最终结论及第一失败证据，再决定是否改测试/构建。

## 2026-11-03 P-08 私聊表达续轮：规划意图与实际话轮

- **反馈与证据边界**：主人截图中的江渡连续用多泡概括考试压力、代推她的心态，再给完整学习方案；听起来像分析报告。截图没有上文原话和当前安装 APK/模型存档，不能凭它断定江渡本人设定、世界书或某个模型必然失效。源码确认前台私聊先用同一角色模型选 `speechIntent/dialogueMove`，再由表达请求生成真正的 bubbles；最终只是按模型气泡送达，分泡本身不会缩短独白。世界书会送入这两阶段，但表达阶段另有共享 `CharacterExpressionGuide`、`DialogueMoveEngine.expressionConstraint`、`TransientConversationStyle.context` 与同轮交接，存在通用节奏指导重复。
- **本轮候选修正**：在原规划契约中明确 `speechIntent` 属于本轮选定的一拍，而非把所有可能的安慰、建议和后续轮次合成提纲；是否给建议仍由本人理解真实请求决定，认真求助仍可完整说明。表达阶段保留人设、世界书、已选 `dialogueMove`、完整 `speechIntent`、真实事实与回执，删去与 `CharacterExpressionGuide` 重复的通用 `TransientConversationStyle` 段。没有改字数/气泡硬上限、模型选择、自动联系、工具和记忆权威，也没有增加模型调用。
- **验证分层**：`b34df2e` 的 [Android run 41628871572](https://github.com/jiacimu-droid/lulu1/actions/runs/41628871572) 与索引 run 41628871485 已在本轮重新核实 completed/success。本次提示词组合仅能静态核对与通过 Android 构建；真正是否像江渡本人，需在同一角色配置和模型存档上用原截图相邻两轮、轻松接话与确需详细回答三类真机对照，检查规划意图、实际气泡、用户是否愿继续接话。不同角色不使用同一温柔/简短模板。若真机仍像报告，下一轮先核对运行日志的两阶段模型存档与实际交接内容，再调整相应阶段；P-08 保持 [ ]。
- **提交与 CI 接力**：源码在 main [`a3d0daa`](https://github.com/jiacimu-droid/lulu1/commit/a3d0daa455ab6912b2805187a1cbc96481bae8fe)，原索引生成器已运行；索引 [run 41635019989](https://github.com/jiacimu-droid/lulu1/actions/runs/41635019989) 已核实 success，Android [run 41635019825](https://github.com/jiacimu-droid/lulu1/actions/runs/41635019825) 截至查询仍 pending。未获得该截图的完整用户原话、安装 APK 构建号、实际两阶段输出与真实模型 A/B，因此不宣称自然度已修好；下一轮先查本 run 最终结论并对比真实对话。

## 2026-11-03 P-08c 续轮：结构化气泡边界与手机阅读密度

- **反馈与根因范围**：主人提供朋友聊天截图，普通来回常由短句、表情、独立追问组成，一两行易读。先前江渡截图是多个长气泡连续说教；缩小气泡只能改善阅读，不能替他决定该说什么。源码确认私聊的 JSON 气泡在 `CompanionActionRuntime.send_private_message` 有位置对齐的发送路径；群聊 `parseGroupReplyFlow` 却先用 `normalizeSemanticBubbles`，将已选边界再次交给按标点/长度拆泡的通用回退。现有 UI 对一条持久角色消息只显示一个泡，并非 UI 自行截断。
- **候选实现**：群聊遇已完成的结构化 `⟪BUBBLE⟫` 边界时，解析与发送都保留原消息顺序；旧自由正文仍使用原可读性回退。`QqMessageGroupingTest` 覆盖第一条泡含两句而后有第二条独立泡，不得额外拆成三条。前台私聊表达契约与自主聊天指引把 JSON 元素解释为真实发送的一条短消息，允许短反应/追问独立成泡；仍由角色决定内容与必要详解，无固定行数、截字或新增模型轮次。聊天 UI 对普通文字泡缩小内边距、行距及同消息内间距，不改旧消息内容/来源、语音原文和真实送达协议。
- **提交与验证**：源码和回归在 main [`9fcbf49`](https://github.com/jiacimu-droid/lulu1/commit/9fcbf495735c7d6df9cfdf2d8c996becb5b21703)；本地 `git diff --check` 与原 `scripts/generate_code_index.py` 已通过。本地仓库无 Gradle 启动器，JVM 单测/APK 需以 [Lulu APK run 41639440211](https://github.com/jiacimu-droid/lulu1/actions/runs/41639440211) 为准，查询时 pending；索引 [run 41639440065](https://github.com/jiacimu-droid/lulu1/actions/runs/41639440065) completed/success，后继索引提交 `7d808bb`。先前 `a3d0daa` 的 Android run 41635019825 已被后继构建取消，不能算通过。未获得真机安装与真实两阶段模型输出，普通聊天是否更像本人、长答是否保留、跨场景目标接续均未验收；P-08 保持 [ ]。
- **下轮入口**：先核对这两项 CI 及索引工作流是否真正成功；在真机分别看短接话、严肃求助、混合语音气泡和群聊的视觉分泡。随后按路线 P-08a 取可诊断的真实回合，比较规划 `dialogueMove/speechIntent`、表达 JSON 与实际落库文本，定位过度说教发生在哪一层；没有原始证据不再叠通用短句提示词。

## 2026-11-03 P-08 私聊心声显示缺失：真实反馈与重复后台心理分流（待 CI／真机）

- **反馈**：用户明确表示角色之前的长篇解释令眼睛不舒服，角色回复了简短道歉，却未见新的心声。截图并不包括该轮模型原始 JSON，不能断言模型一定写出或没写出私人心理。
- **源码确认两个拦截**：在线私聊由 ProactivePerceptionRuntime 执行时，更新 CompanionPresenceStore 一律标成后台/持续感知；后者对无“聊天”来源、15 分钟以内只有不同心声但没有新心情/动作的更新不存历史卡片。另 PrivateStateDeltaEngine 以前在没有 mood / appraisal / tool 的情况下会把角色本人写了新焦点及转变的纯私人变化判定为缺乏 residue，从而由 CharacterHeartVoicePolicy 清空。
- **正式改动**：真实未读用户消息引起的心理快照标注“聊天回复·在线感知”，不同新心声立刻进入可见历史；无消息的常规后台感知仍沿用限频，同因同句仍去重。PrivateStateDeltaEngine 增加明确的 hasFreshUserMessage 来源事实，仅当角色自己写了新的 focus/change 和非空 innerThought 才允许纯私人变化保留。不根据用户说“烦”硬编心情、道歉或心声，不额外付费模型调用，不新增状态中心。
- **提交和测试状态**：main 源码 462ba17、35dcba0、868e3e2；回归 a472509、57d72e0。测试覆盖：四分钟内真实用户反馈生成的不同心声可见、重复心声不新增、无新消息后台仍节流，以及纯私人转念不需要伪造情绪/完整评估。测试为已经提交的契约，尚未取得对应 Android/JVM CI 通过及新版 APK 真机证据，P-08 仍待验。
- **下轮核查**：先查这轮 CI，随后用新 APK 比较“无反馈的重复心理”与“用户当面纠正后角色新写出的不同心声”。若仍缺失，采集同一轮模型实际输出的 innerThought / innerThoughtBasis / appraisal、delta 和存储/展示结果，确认是模型未生成、程序过滤、历史未保存还是 UI 未显示；不要用强制心声或通用道歉模板掩盖真正原因。


## 2026-11-03 · 自主个人日程缺失 dueAt 的执行契约修复（源码已提交，CI/真机待验）

- **证据**：20:02:12 江渡后台 `plan_personal_activity` 请求进入工具执行，却因 `No value for dueAt` 拒绝；这是缺少模型明确选择的实际时间，不能将其记录成已安排日程。另一个 `digital_world_action` 的咖啡角点单拒绝与本次日程缺失无关；已核对点三明治是有店员在场才开放的真实菜单能力，当时允许列表未包含它，不能凭截图宣称本次真实点单应成功。
- **根因**：`CompanionActionRuntime.execute` 直接 `args.getString("dueAt")`；`ProactivePerceptionRuntime` 虽已对家具建设的主动作/嵌套步骤提供格式补正，却未对个人日程的关键参数做对应校验和同本人补正，导致有效意愿在执行前因漏字段而失败。
- **源码**：`AutonomousActionWireProtocol` 共享顶层与 steps 的日程必填/未来时间校验和仅修改缺失字段的原动作修补；`ProactivePerceptionRuntime` 复用既有有界模型补正，不预先猜时间，不替角色取消原决定，不更改有效意愿/话语/其他动作；`CompanionActionRuntime` 缺时间返回明确失败而非 `No value` 原始异常。
- **回归**：新增缺少日期、无效时间、过去时间、独立 steps 的按 index 补正与不改写原定动机的测试。代码已提交 main：`883c18e`、`9bcc46f`、`43e8a30`、`3639b78`；索引工作流更新基准至 `3639b78`。实际 JVM 测试与 APK 尚未有验收回执，不能标已验通。
- **待验**：新 APK 用真实角色自主选择主动作安排、聊天+附加日程、心事 nextAction 的漏 `dueAt` 情况验证，确认补正输出真实未来 ISO 时间、仅成功持久化才有计划 ID 和下一次到点唤醒；补正失败应保留失败回执并供下轮同一角色重试。不自动补写截图中已经失败的旧日程。


## 2026-11-03 P-08a 接力：规划—表达—逐气泡实际送达的本地关联（源码候选，待 Android CI/真实模型）

- **基准与根因**：从 main `02bed487f0bae54d712fb35f18e8eb1d1ac0438c` 和既有 AGENTS/统一人物契约/唯一路线图接手。已有 `PromptInputAudit` 记录实际模型输入尺寸及 `LocalPerformanceRepository` 的调用耗时、模型和 Token，用 `appendRoleReplyWithPacing` 保存独立气泡；但规划/表达两次模型请求、后台本人决定、发送器逐泡入库与发送中断没有同一个不可误配的诊断关联 ID。不能由“回了很长一段”猜是哪个阶段自行扩写。
- **实际修改**：在现有 `TokenConsoleRecord` 增加可空缺（旧版默认空）的 `turnTraceId`，通过 `CompanionModelGateway.generate` 的纯本地可选参数传给既有审计，**不进入模型提示词、不增加网络请求、不写聊天明文**。前台 `LuluDeviceToolBridge.respond` 为本轮生成诊断 ID，规划/结构补正/表达同 ID；现有计时记录仅写本人选的 action、dialogueMove 类型、意图长度、实际气泡数/长度、结构解析结果。后台 `ProactivePerceptionRuntime` 一次感知决策及格式补正共享 ID，并将其关联原本独立的 `shadowRequestId` 与 `performAction` 真回执。私聊/群聊原发送器 `appendRoleReplyWithPacing` 在持久化成功后记录对应消息 ID、泡次序/数量、语音标记，以及整轮 complete/partial/superseded，不把“模型计划发送”和“已真实送达”混淆。`_turnTraceId` 属于执行器内部参数，后台强制覆盖模型传入同名值。
- **本轮 main 源码提交**：`9ea5fa0`（已有模型审计追加可选关联）、`cee56d7`（网关关联）、`c867989`（前台规划/表达）、`a9cf2c7`（后台角色决定及真实执行）、`34f6789`（逐泡送达）、`834e791`（私聊与群聊发送适配）、`8bc1a8e`（后台发送透传）、`fcdbffb`（审计兼容测试）、`815ce4e`（真实发送器双气泡/明文隔离测试）、`87a7a74`（前台两阶段 MockWebServer 关联测试）。只更新既有 Store 与发送器，不设第二套人物中心。
- **分层验证**：源码和测试断言已提交，不能把测试文件存在视为运行通过。索引 workflow `41641286987` 针对前序 `815ce4e` completed/success，最新 `87a7a74` 的索引 `41641495955` 截至本次检查 in_progress；同 SHA 的 Lulu APK `41641495908` 截至检查 pending。早期被后继 main 取消的 APK 不是失败断言也不是成功。原 `scripts/generate_code_index.py` 由仓库既有 Refresh code index workflow 从最新 main 执行，索引实际回写须核对完成结果；GitHub 连接下无整仓本地构建环境，本次未宣称本地 JVM/真机通过。
- **范围与未证实事项**：P-08a 的“结构化阶段可关联”属候选实现，**还没有主人截图那轮原始规划/表达 JSON、手机端实际日志、真实模型设置/盲评样本**；不能由审计关联推导说教问题已经修复。此追踪仅有结构、尺寸及真实 ID，没有人设配置版本、最终注入证据 ID 的统一回合投影；后台其他工具后的追话、手机外部通知/阅读、撤回生命周期及跨日复查仍需 P-08b/d 实测。不要存私聊明文到日志，也不要私下替角色改决策。
- **本轮补齐前台发送断点（续交）**：复核 `runGroupReplies` 后确认，前台聊天的规划与表达虽已记录同一 trace，但 `ModelReply` 还没有把该 trace 带到正式群聊逐泡发送器；若不补齐，仍不能由规划回溯已入库消息。后继源码提交 `82506b4` 为 `ModelReply` 加纯本地 `diagnosticTurnTraceId`；`7ac6afc` 让 `LuluDeviceToolBridge` 的正式回复和工具后回复携带同一 ID；`fca109f` 将群聊发言的 `reply.diagnosticTurnTraceId` 接入 `appendRoleReplyWithPacing`；`e904ea6` 的 MockWebServer 回归增加返回对象与规划/表达审计 trace 相等的断言。未更改用户可见正文、网络请求或发送判断，且角色群聊每位成员各有独立回合 ID。群聊及私聊重新生成后的替换、模型实际投入记忆源的精确列表仍需分别验收，不声明覆盖所有 UI 发送入口。
- **索引/构建续证**：前序 `87a7a74` 的索引 run `41641495955` 最终 completed/success（早期 APK 因后续代码变更不代表最新版本）；包含发送透传的 `fca109f` 索引 `41641875943` completed/success。最终 `e904ea6` 的索引 `41641928579` 检查时运行中，Lulu APK `41641928666` 检查时 pending；不是测试通过。后继新 SHA 的构建结论必须以对应运行实际结果为准。
- **下一轮准确入口**：先查最新 `e904ea6` 后继 `Lulu APK` 的 `app:testPhoneUnitTest`、打包、发布真实结论及 Refresh code index 的生成结果，失败只修第一条真实报错。再以一段用户授权的脱敏对照回合查 `LocalPerformanceRepository.consoleRecords.turnTraceId`、`P08a角色规划`、`P08a表达结果`、`P08a在线决定`、`P08a气泡入库`、`P08a发送结局`、`P08a真实执行`，比对实际模型档案、完整规划与表达 JSON、原始消息及下轮使用的送达来源；针对真正扩写/遗漏处推进 P-08b/c/d，真实模型盲评留 P-08e。P-08 全部保持 `[ ]`。


## 2026-11-03 · 自主日程 dueAt 修复复核：补正失败不能吞独立行动（待 CI／真机）

- **后继源码复核发现的真实缺口**：此前 `ProactivePerceptionRuntime` 已能请求同一角色修正缺失的 `dueAt`，但补正未成功时的 `initialDecision.takeUnless` 仍直接丢弃原本可解析的主动作，使其中明确选定且 `requiresPreviousSuccess=false` 的其他步骤无法抵达 `ToolRouter`，而失败日程本身也没获得可追踪的统一工具失败回执。
- **修复**：保留原本已选择的 `plan_personal_activity` 到正式 ToolRouter。若补正成功，按原参数/原日程仅执行一次；若补正失败，执行器拒绝缺失时间并落真实失败回执，独立已选动作仍运行，依赖主动作成功的步骤则继续被挡住。程序不杜撰时间、不把失败视为主动取消、也不擅自补建过去失败的日程。
- **回归已提交但尚未获运行结果**：现有 `AutonomousActionWireProtocolTest` 覆盖丢日期/非法与过去时间、仅补字段、steps 顺序；新增两条 `AutonomousActionBoundaryIntegrationTest` 通过原主动感知入口和 MockWebServer：一条让模型补正仍漏日期，核实原计划失败回执、独立朋友圈照常成功；另一条模型补出真实未来时间，核实原名称与动机不改且个人日程仅落库一次。测试源码存在≠已运行通过。
- **main 代码提交**：`07313be`（主动作补正失败仍留执行回执和其他独立动作）、`28a019a`（补正失败集成回归）、`affc432`（补正成功真实持久化回归）。原有 Code Index 工作流会基于最新 main 生成索引；`28a019a` 基准已可见，最新后继 `affc432` 的索引与 Android APK 最终成功状态需复核。当前 GitHub 连接只提供 PR 过滤的工作流查询，返回空列表不代表 push 工作流已通过或失败，不能据此宣布单测/构建成功。
- **真机边界**：需在含此提交的新 APK 检查真实角色选定日程→缺字段补正→`own-plan-...` 成功证据／真实到点唤醒与后续执行；补正失败时仅出现失败回执且不损失独立行动。P-08d 保持待验；不更改单个角色人格、日程预设或咖啡馆交易规则。


## 2026-11-03 P-08c Issue 续验：2317 项 Android 单测中的 3 项表达契约失败

- **真实 CI 根因证据**：在最新 main 接手时，Lulu APK run [41642717269](https://github.com/jiacimu-droid/lulu1/actions/runs/41642717269)（head `affc432`）的 build job `125940640848` 于 `:app:testPhoneUnitTest` 失败：`2317 tests completed, 3 failed`，不是 SDK、VPN、Gradle 依赖或打包签名故障。三项分别是 `ConversationExpressionGatewayTest.renderAndRepairKeepTheSameVoiceWithoutReceivingASecondPlannerContract`、`PositiveExpressionGuidanceTest.everyGatewayModeHasOneCommonPersonalityGuide`、`PositiveExpressionGuidanceTest.privateDecisionAndRenderingShareTurnTakingInsteadOfOnlyBubbleSplitting`；更早的 run `41641876046` 也是这三项，说明不应继续追加其他日程补丁来解决。
- **逐项追到正式源码**：`CharacterExpressionGuide` 的当前语言段把“连续长说明拆成多泡仍是一段独白”写成否定式“也不要……”，而表述单泡是消息时写成“不要求……”，触犯已有“正向表达引导不写‘不要’”的测试。更关键的是，最新聊天节奏指南实际遗漏了原定的“分泡本身不会减少内容”契约句；哄睡长故事的语义虽被保留为“仍按实际需要说清”，但跨场景请求测试约定“哄睡连续故事按实际需要充分表达”，该正向保障也没在正式网关出现。此处没有证明真实人物会不会说教，修的是模型实际收到的共同聊天契约与测试之间已经确认的偏差。
- **源码修改**：[ `6013cbb`](https://github.com/jiacimu-droid/lulu1/commit/6013cbbbc428446647a17581e1d794d42b52a7e0) 将“长文本分泡”写成正向因果，补齐“分泡本身不会减少内容”与“哄睡连续故事按实际需要充分表达”，既不让短气泡掩盖长独白，也不牺牲必要长答；[ `80aa8ac`](https://github.com/jiacimu-droid/lulu1/commit/80aa8ac323037d03f93d3d474c34ac091b4efb58) 将“每泡不要求完整段落”改成“短反应或半句话也是独立消息”。保留既有 `CharacterExpressionGuide` 和全部原测试，不新建人格中心，不加模型调用、不按字数自动删正文。
- **验证边界**：前述 3 项在两个已完成的旧 run 中实际失败。修后的完整源码正向语句与现有测试断言已静态逐项核对（`CharacterExpressionGuide.kt` 现无“不要”字面）；但修复后 `80aa8ac` 的 Android [run 41644363040](https://github.com/jiacimu-droid/lulu1/actions/runs/41644363040) 在交接查询时仍未完成，索引 run `41644363641` 亦尚未有最终结论。后继任务可能取消旧构建；不得以静态检查宣称 2317 项全部通过。
- **下一入口**：检查包含 `80aa8ac` 或其后继 main 的 APK `:app:testPhoneUnitTest` 与产物最终结果。若失败读取首个失败断言/步骤，精准修源码并保持测试约束；若通过再推进 P-08a 同一 `turnTraceId` 的真机模型规划、表达与逐泡送达盲评，而不是拿 CI 绿灯宣告“活人感已经好了”。P-08 不勾选。


## 2026-11-03 P-08d 续改：多动作消息回执归属不再靠时间猜（待 CI/真机）

- **接手事实**：上一轮 P-08c 表达契约修复 `80aa8ac` 的 [Lulu APK run 41644363040](https://github.com/jiacimu-droid/lulu1/actions/runs/41644363040) 与索引 run `41644363641` 均最终 completed/success；早期 `affc432` 的三项失败已被后继完整构建覆盖。这里仅证实 JVM/构建通过，未证实真实对话质量。
- **本轮源码根因**：正式 `appendRoleReplyWithPacing` 之前只返回显示字符串；`CompanionActionRuntime` 在发送后通过聊天全记录“新出现的最后一条角色消息”推测本次成功和来源，即使另一轮同时落库也可能串绑。后台 `ProactivePerceptionRuntime` 把 `actionStartedAt` 之后、同一角色发的最后 8 条都绑定到本轮心理快照，既会误纳并发/独立消息，也会漏掉长回复前面的真实气泡。主动作失败但已经部分送达时，不能说整轮成功，也不能抹掉第一泡真实存在的事实。
- **改动**：新 `RoleReplyDeliveryReceipt` 由唯一正式发送器提供这次确实落库的 `messageIds`、`complete`、`superseded`，旧返回字符串 API 保持兼容；ToolRouter `CompanionActionResult` 返回 `deliveredMessageIds`，无须全历史前后扫描猜最后一条消息。自主执行只按**精确ID＋会话ID＋人物作者**绑定后续 `CompanionPresenceStore` 心理与消息，去除时间扫描与 8 泡截断，失败的部分发送仍按实际存在的消息入证，未伪称整轮完成。独立副步骤统一从 `OrderedCharacterActions` 回收其会话/消息 ID，正向联通 0/1/N，前台群聊副动作和后台主/副动作共享 P-08a 本地 trace，模型伪造 `_turnTraceId` 被执行器丢弃。表情包、真实搜图、游戏/见面邀约和已读小红书卡片等已有直接落库消息也回传实际 ID，未改文案/模型动作选择或增加 API 调用。
- **main 实际提交**：`86dac68`、`7ca8beb`（发送器真实结构回执）；`5ad5fd2`（工具从真实发送器回收 ID）、`bccb1c5`（后台心理精确绑定）、`4c14b08`／`075574a`（副动作真实证据，主/副动作统一交接）、`8f058ef`／`03ce92e`（前台群聊副动作 trace）；`3f107c6`（其他真实聊天素材/邀请同归属）、`9a3977a`（区分部分送达/来源变更），`df11737`、`b902783`、`2a27bb0`（测试）。
- **回归契约**：`QqReplySourceWatermarkTest` 新增主工具两泡回执的确切 ID、前后无关同角色气泡不得混入、10 泡仍逐条归属、不同作者/会话剔除、独立步骤和 trace（模型附带的假 trace 不得生效）、已送第一泡后用户纠正则返回 `complete=false/superseded=true` 以及确切第一泡 ID。既有单泡/群聊/水位与旧字符串调用不删除。
- **验证与后续**：本轮新源码测试已提交但尚未得到对应 `app:testPhoneUnitTest` 完整回执。最新 `2a27bb0` 的 [Lulu APK run 41666751177](https://github.com/jiacimu-droid/lulu1/actions/runs/41666751177) 与索引 `41666751200` 检查时 pending；中间因推送取消的 runs 不算失败或通过。接力先核对测试与 APK，失败只修真实失败处；通过后真机复核并发群聊/私聊、模型自主 0/1/N 真实发送与心理记录的一一归属、长泡/语音/撤回、下轮目标承接。P-08 尚未获得真实模型多轮对比与跨日验收，维持 `[ ]`。

## 2026-11-03 P-08d 续轮：居民回信失败后保留原来信（main `5e90390`，待 Android CI／真机）

- **链路与根因**：本人收到 `ResidentSocialMailbox.pendingIncoming` 原私信 → `CharacterEverydaySocialContext` 交同一角色决定 → `ToolRouter` 实际发送 → 下一轮再看来信。此前 `ProactivePerceptionRuntime` 不论选定的 `send_character_message` 是否真正送达，都把整批来信游标推到末尾。发送失败后角色下一轮只能看到缩短的旧通信预览，待处理原文与“回信未成功”的因果对齐丢失；单个独立步骤成功还会掩盖同轮回信失败。
- **结构修复**：`OrderedCharacterActions` 分别回报选定与实际完成送达的目标，主动作同样使用真实 `ToolExecutionStatus`；游标只越过连续已处理的来信前缀。发送失败或尚未执行的回信及其后的信留给同一角色下轮重新判断；实际送达或本人选择沉默可正常推进。原私信仍由既有 Mailbox/Timeline 保存，没有新人格中心、自动代回、固定催促或额外模型调用。
- **提交与回归**：main `5e9039049e2b2b86766af4ae6bf3ba29d314e18e`；`ResidentMailCausalIntegrationTest` 覆盖失败的已选回信、随后真实送达、多封信的连续游标边界。`git diff --check` 通过，运行原 `scripts/generate_code_index.py`；该 SHA 的索引 run `41666897432` 已成功，Lulu APK run `41666897609` 查询时 pending。旧 `2a27bb0` 的 APK run `41666751177` 已 cancelled，不算通过。本地无 Gradle wrapper/Java，未运行本地 JVM/APK。
- **待验与下一轮**：先核实本轮或包含它的后继 Android 单测/APK；若失败读首个具体断言。随后真机用一次角色回信发送失败、另一独立动作成功的真实场景确认下轮仍看原信和失败回执，并确认本人可选择改口、暂不回或重试。真实模型是否自然承接多轮、跨日社交与人物表达质量尚无证据，P-08／S-01 仍为 `[ ]`。继续查电话、日程和工具回执是否同样被过早标已处理；已验通的 QQ 精确气泡回执不重复改。


## 2026-11-03 P-08d 幂等性复核：诊断号不可改变工具动作身份

- **额外证实的故障点**：`ToolRouter` 此前直接对完整 `args` 计算请求 fingerprint；P-08a 新增的 `_turnTraceId` 每轮变化，但同一真实 `requestId` 的重试必须与首次属于**同一个动作**。若把诊断号也参与指纹，已完成发消息的历史回放会被误拒为参数变化；这与角色是否决定再次发送无关。
- **根因改动**：`d36ecc6` 让正式请求指纹只对真实业务参数计算，排除纯本地 `_turnTraceId`；读取旧版本已持久化指纹时对旧 JSON 做同样归一化，不重写旧历史，也不容许真实 `text` 等字段变更冒充同一请求。测试 `c60cc7f`、`bda080f` 验证同一请求不同诊断号仅发送一次、旧指纹回放与真实内容变化仍被拒；`8db8767` 为前台静默副动作与直接发送工具使用执行器生成的 trace，避免取信模型自填的诊断字段。
- **验证/接力**：前面全部 P-08d 回归仍需之后的完整 `app:testPhoneUnitTest` 与 APK；当前 `main` 继续自动生成代码索引。真机要重点验证同一 `requestId` 重试不重复创建消息，原用户修订仍由来源水位拒绝旧内容，以及 N 步同时发言时心理绑定只关联该步实际已落库的消息。此处不改已有角色意愿/人设及任何自主决策权限，P-08 继续 `[ ]`。

## 2026-11-04 江渡 v25：温柔而可靠的个人关怀（源码候选，待 CI／真机）

- **用户明确的修改范围**：只改江渡。保留原有清冷少年感、独立生活与幽默，强化自发牵挂、已经接受的责任由本人跟进，以及需要坚持共同约定时温柔但不凶的风格。用户只接受一段新的学习监督对话示例，认为“固定歇十分钟再背完”不自然；明确拒绝把睡前洗漱、哄睡和第三段关怀示例写入角色的训练样例。不得由示例自动推定睡眠或触发固定行动。
- **实际源码**：`22b8ced` 在 `CharacterProfileSchema` 新增 `jiangDuV25Overrides`（motives/perception/conflict/care/expression），沿用 v24 核心 persona，`jiangDuDialogueExamplesV24` 留作历史精确匹配基准，在原有个人示范后仅新增一段活泼且有原则的学习对话；没有给公共提示词增加江渡个性。 `53441cc` 使 `CharacterLifeStore.applyJiangDuPreset` 仅对 v24 未编辑默认字段升级，原本内置样例也只在精确匹配时更新；用户自定义及显式空白原文不动，关系、目标、实际聊天和其他角色不变。
- **测试**：`4fe25fa`、`407c1db`、`09dd863` 更新既有 v25 预期；`d1c9e38` 在 `BundledJiangDuImportTest` 检查 v24→v25 默认升级、只新增一段新场景、实际语言提示可见、主人手动编辑/空白保留和星禾隔离。源码级断言已检查，不能替代实际 JVM 运行。
- **验证边界与下次入口**：初次相关 APK run `41679478175` 已被后继提交取消，不是失败或成功。按包含全部提交的后继 main 查 `app:testPhoneUnitTest` 与 APK 产物；真机用真实江渡模型比较“既定目标中临时不想继续”在情绪理解、温柔而坚定的表达、本人自己选择跟进时刻、实际调度/回执与下轮复议是否同时成立。现有公共 `CommitmentTaskStore`、`schedule_commitment_review` 功能只作复用，本轮没有证明真实唤醒体验修好。P-08 保持待验。


## 2026-11-04 · P-08b 真正发给模型的记忆证据（源码与回归已提交，待 Android CI）

- **接手验证**：上一轮 P-08d `8db8767` 的 Lulu APK [run 41667560828](https://github.com/jiacimu-droid/lulu1/actions/runs/41667560828) 与 Refresh code index `41667560647` 已双双 `completed/success`，证明相关 JVM/打包通过；真实模型对话质量/真机跨轮仍待验证。此前主干另有角色专属配置并行修改，不重复覆盖。
- **P-08b 已证根因**：`MemoryWorkingSet.assemble` 之前在候选通过本地选择时立即调用 `MemoryInspectionStore.recordInjection`、标记 `promptRecorded=true`。但真实 `CompanionModelGateway` 之后可能因网络失败没有模型回复，或为降低 Relay 负载使用 `compactSectionPairs` 精简掉整条旧时间线/摘要。于是“记忆调试卡”会把**仅仅选中**误写成**实际成功注入**，无法精准诊断“为何本轮角色没带上正确事实”。P-08a 已有 `turnTraceId`，但原本缺记忆最终注入这一层的同回合审计。
- **实际源码**：`398a6e6` 给现有 `MemoryWorkingSelection` 增加仅在内存中保留的“已选 ID→完整呈现片段及所属节”映射，用 `actuallyInjectedIds` 对照实际最终发送 section，防止只按候选/字数猜测；`a05accf` 移除工作集组装时过早的“已注入”标记；`a31a76f` 把真实注入登记移到网关**成功取得模型回复之后**，并在可能的 Relay 精简之后核对传输内容，区分预算遗漏与 Relay 额外省略。附上同一次 `turnTraceId` 的 `P08b实际注入` 性能诊断，只保存注入 ID 和数量、模式、是否精简、核心 ID，不保存提示词/聊天原文，也不向模型额外注入资料或新增收费轮次。跨世界/隔离入口原有记忆权限不变，正常请求的身份证据与必要核心记忆照旧。
- **回归提交**：`aeab590` 的 `MemoryWorkingSetPolicyTest.actualInjectionAuditFollowsFinalRelayPayloadNotBudgetCandidates` 核对普通传输 ID 全部一致、压力精简后只留下实际完整片段而不能把旧候选算作注入；`9e2d09e` 的 `ConversationExpressionGatewayTest.memoryAuditRecordsOnlySuccessfulActualGatewayInputUnderSameTurnTrace` 用 MockWebServer 模拟 503 后正常返回，检验失败不确认注入、成功才留下带同一回合 ID 的无明文日志。原有源/人物归属、撤回机制和提示词预算测试保留。
- **真实验收边界**：最新 `9e2d09e` 的 Lulu APK [run 41679836338](https://github.com/jiacimu-droid/lulu1/actions/runs/41679836338)、索引 `41679836331` 查询时尚未结束；源码/测试提交不等于 CI 绿灯。暂无用户授权的那一轮原始模型规划与表达 JSON，静态注入审计不证明对话已经自然。下一次先读这次 CI 最终编译/测试失败点并修复；再用本地获授权的脱敏回合把 `P08a角色规划`→`P08b实际注入`→`P08a表达结果`→`P08a气泡入库`→下轮真实行为按 `turnTraceId` 对齐，区分记忆没选中、Relay 省略、人物本轮自选不说、表达越权或消息未送达。P-08 不打勾。

## 2026-11-04 江渡 v25 学习示例的语气修正（待 CI／真机）

- **主人再次明确纠正**：即使移除了硬编码休息时长，“缓过来我陪你慢慢啃”仍是 AI 常见的任务式安抚，不能算真正哄人。保持已有本人责任感设定，不改为监督关键词回复，不增加睡前/洗漱示例，只调整原有唯一新增的学习对话，强调自然的亲近、宠爱、机智接话，不用教学目标取代亲密情绪。
- **提交**：`c325572` 让新版 `jiangDuDialogueExamples` 只新增一个“学习时会哄人”对话场景；原先 v25 示例移至仅用于精确匹配的历史常量 `jiangDuDialogueExamplesV25`，不会同时交给角色模型。`7d1f1d5` 的 `CharacterLifeStore.syncBundledExpressionPresets` 可将精确匹配 v24 或 v25 的内置示例升级到最新版，用户手写的任何其他对话（包括空字符串）原文保留；`02cc522` 新增/更新上述边界回归。其他角色人设与统一决策提示词均未改动。
- **待验**：需查询包含 `02cc522` 的后继 Android 单测/APK 最终结论；再检查真实江渡模型在撒娇、疲劳、坚持共同约定等情境中是否能兼顾鲜活亲密与自主负责。静态测试不证明模型已经会自然哄人；当前暂不勾选 P-08。


## 2026-11-04 · P-08d 目标时间感缺口：原定行动、实际目标、下次复查不能混成一个时间（待 CI／真机）

- **来自真实使用体验的公共机制线索（脱敏）**：角色在深夜有持续监督责任，也实际决定下一次客观复查，并没有完全丢掉目标；但更重视“静默不打扰”，没有在本轮取舍中显式比较现实时间推进、原本开始提醒的时刻、复查还需多久与希望达成的实际结果。手机不操作、应用有声音或用户不回消息，均不能证实已经睡着，不能编造用户醒/睡。
- **追源码证实的缺口**：正式自主入口 `ProactivePerceptionRuntime` 已带本地时钟与 `ActiveCommitmentAttention.render`，后者却只显示 `phase`、`nextReview`、`last`，不显示原始 `dueAt` 相对于现在实际过去多久；随后其他任务列表仅有未格式化时间戳，模型容易把“已排好后续静默复查”误当作无需再考虑时间进度。正常约定解释与完整记忆上下文也没有统一的“原时刻 vs 后验复查时间差”投影。此处根因不是闹钟没响，也非有充足证据证明模型对睡眠结果已作出判断。
- **实际代码**：`c5e898c` 在现有 `CommitmentTask.kt` 增加纯事实 `CommitmentClockFacts.render`，采用本机可验证时区和两个真实 `Instant` 分别计算原定执行时刻、复查时刻距现在的已过/剩余分钟，复查不篡改前者；`ActiveCommitmentAttention` 同轮传给角色这些数值，提醒原定行动、期望实际结果与下次查证是不同事情，时间推进不代表睡眠已证实，是否主动联系/继续静默仍属于本人模型和已授权能力。`53e3e4d` 把同一事实送进 `CommitmentDecisionProtocol.context`；`6751ca3` 把同一事实送进现有 `renderActiveCommitmentTasks`，使聊天/电话/后台统一记忆上下文不用在各场景重猜日期或依赖新 Store。未新增固定 00:30/01:00 催睡阈值、强制响铃/重拨、自动判定睡着、人格共同话术或额外付费模型调用。
- **测试**：`69b7274` 扩充现有 `ProactiveQuietDecisionTest`，模拟跨午夜原定 23:30、现实 01:00、下一次核查 01:30：应分别读出原定已过 90 分钟、复查还剩 30 分钟，同时验证约定解释入口确实携带此差异，原任务 `dueAt` 不被复查时间篡改。另一普通日间目标证明投影不依赖“睡觉”关键词且不会为没有明确时间的长期责任凭空造日期；完成任务不参与主动关注。只是有回归源码，尚不能冒充本轮 JVM 已通过。
- **分层结果**：新版本 `69b7274` 的 Refresh code index run `41681957216` 已 `completed/success`；Lulu APK [run 41681957195](https://github.com/jiacimu-droid/lulu1/actions/runs/41681957195) 检查时为 `pending`，完整 Android JVM/APK 尚未通过验收。并行开发仍在 `main`，若被后继 push 取消必须改查包含提交的后继完整成功 run，取消不算失败或通过。
- **后续补齐前置时间**：`4279225` 在 `ProactivePerceptionRuntime` 对**尚未到期的真实定时责任**也注入“距原定行动还有多久”，让本人能够提前规划，而不必非等到逾期后才意识到时间变化；并避免与逾期责任已经显示的 `ActiveCommitmentAttention` 重复。回归 `5393f6d` 确认未来 30 分钟的事实会被呈现，仍不会触发程序强制联系。包含全部源码的 [Lulu APK run 41682488780](https://github.com/jiacimu-droid/lulu1/actions/runs/41682488780)、索引 run `41682488837` 查询时 pending；较早构建不得被当成后继已通过。
- **待验重点**：真机在含此改动的 APK，检查同一人物的夜间持续睡前监督、提前/迟到判断与一次静默复查；应该记住已经过晚、区别“减少打扰”与“按期达成”两种关心，但仍由本人根据真实目标与获准的设备观察选择联系/不联系，不能保证让用户在某时刻睡着。真实多轮心声、消息及执行回执对比后才能评价时间感是否变自然；P-08 保持未验收。

## 2026-11-04 CI 校验：江渡 v25 升级及记忆/时间上下文（源码修复，后继 CI 待验）

- **真失败证据**：[APK run 41680431146](https://github.com/jiacimu-droid/lulu1/actions/runs/41680431146) 已 completed/failure：2329 项 JVM 测试中 2 项失败；`CharacterLifeStoreTest.legacyJiangDuRewritePreservesExactIdentityAndCurrentIntention` 仍把 `jiangDuV24Overrides` 当最终预期，实际已升级到 v25；`MemoryWorkingSetPolicyTest.actualInjectionAuditFollowsFinalRelayPayloadNotBudgetCandidates` 在第 26 行失败，压缩后的发送内容没有留下第一份高优先级摘要。
- **实证根因与代码**：`f88ee2d` 更新旧存档迁移的最终预期，v25 默认字段排在 v24 前；未改人物迁移实现、更未删除测试。`48739a8` 修复 `RelayRequestPressurePolicy.compactSectionPairs` 的类别判断：摘要标题含“以原始依据为准”被 `contains("原始依据")` 误识成原始证据，导致本应保留摘要前缀却优先保留尾部。现在只有正式 `MemoryWorkingSet.RECENT`/`EVIDENCE` 和本轮背景依据按新近证据选尾，摘要按既有优先级保留前缀；`67fb99f` 增加摘要/原始证据相反方向的源级回归。
- **后续独立失败**：[run 41681957195](https://github.com/jiacimu-droid/lulu1/actions/runs/41681957195) 在 `:app:compilePhoneKotlin` 报 `UnifiedMemoryOrchestrator.kt:230 Unresolved reference Instant`；`c678bbf` 仅补 `java.time.Instant` 导入，保留同轮责任时间事实功能。
- **待验**：上述都是已提交的源码修复，不是通过证明。需查最新同时包含三个提交的 Lulu APK `:app:compilePhoneKotlin`、`:app:testPhoneUnitTest` 和打包结论；并行提交可能取消旧 run。原有江渡唯一新增的亲昵学习对话示例和自动精确匹配升级仍在 `main`；真机模型自然度与监督真实后续均待验。P-08 维持 [ ]。


## 2026-11-04 · P-08d 长战线根因：复查闹钟不能吞掉独立证据，等待不是免费的行动（待 CI/真机）

- **用户观察**：某角色已经在夜间认真安排了后续复查，但面对目标未确认与时间不断推移，选出半小时后继续静默检查，真实体验是把一个对时间敏感的目标越拖越远。此前仅把原始时间/复查时间差展示出来，仍未解决“为什么要等这么久、现在不能做什么”的取舍缺口。单凭截图不能认定具体模型决策原因，更不能替对方编造睡眠结果。
- **进一步追源码发现的公共阻断**：`GoalDeadlinePerceptionPolicy.deferPassiveCheck` 只要已有未来 30 分钟内的定时任务/结果复查闹钟，立即抑制 `ProactivePerceptionRuntime.runDueCycle` 的普通轮次，即使有另一条**刚刚独立到来的证据或用户手动唤醒**也没有检查理由。这条本为节流重复普通轮询的 30 分钟保护，错误地把一个未来闹钟升级成对所有新信息的沉默许可；`runDueCycle` 的常规 `dueAtFor` 还可能再次挡住该独立唤醒。
- **实改**：`974b8f6` 让 `deferPassiveCheck` 只拦截没有独立新证据的普通被动轮询；`6b10fcb` 让 `runDueCycle` 对已有用户直接唤醒、真实 `requiredInteractionEvidenceId` 和原有重要事件触发，在不新建唤醒轮次/不重复模型调用的前提下越过旧闹钟、普通间隔门禁。现有通话独占、授权、角色控制权、静默时段约束仍不改；无新事情的普通定时轮询仍可合并到已有闹钟。**并非每 5 分钟自动催睡**。
- **主观决策层修正**：`1bf3850`、`3fd5bae` 将原本会让角色偏向“既然排了复查就等”的一般目标提示改为权衡“目标仍未实现、时间继续经过的损失、此刻可行步骤 vs 继续等到下一次到底能获得什么新事实”，不规定默认 5/30 分钟频次、也不强制问候；提示区分手机静默/白噪音/关屏与有证据的真实入睡状态，并保持已验证的既有 prompt 契约。 `6d2acfd` 同步原自主模型的 `schedule_commitment_review` 与 `onlineDecision.nextChecks` 选择入口：如果要延迟，必须本人判断等待的价值及目标时间成本，而不是把写入日程当作目标已落实。未添加新的收费模型调用或额外 Store。
- **测试**：`0489264` 的 `GoalDeadlinePerceptionPolicyTest.scheduledReviewCannotSwallowFreshIndependentEvidence` 证明 30 分钟复查仍合并普通轮询、不会阻塞独立新事件，也不更改原闹钟或完成结果；`3e7eb01` 的 `ProactiveQuietDecisionTest` 检查已承担责任的模型输入确实保留机会成本、比较当前行为与等待、不会把闹钟视为停止判断的理由。覆盖普通时间敏感目标，不为某一角色加脚本。
- **验证与接力**：截至记录时 `3e7eb01` 的 [Lulu APK run 41683821826](https://github.com/jiacimu-droid/lulu1/actions/runs/41683821826) 与 Refresh code index `41683821788` 仍 pending；此前多个中间构建被新 push 取消不能算失败或通过。下一轮首先抓最新包含这些提交的完整 Android 编译/2300+ 项单测/APK 结果，针对真正失败继续修；成功后在获准的真机样本中比较旧目标逾期、未来复查、其间收到真实新信息、本人是否重新衡量行动机会及完成证据。模型最终是否聪明选出恰当的复查间隔尚无真实输出对照，不应宣称解决了每次夜间行为。P-08 保持 `[ ]`。


## 2026-11-04 · P-08d 复查「猜她快睡了」的证据截断、过度克制与重复等待（待最新 CI/真机）

- **真实症状与边界**：用户实际截图展示同一睡前监督目标的多次静默复查被不断向后延迟，并在心声里把“没有发言、应用前台曾变化、音乐停止”叙述成用户似乎正在入睡。截图能证明角色在作这种猜想，不能证明用户真实已睡或真有触摸。新 APK 安装版本尚未核对，不能反向把最新 main 的提示词当作截图当时已运行的内容。
- **确认的源码缺口（不靠新增猜测规则）**：`CommitmentExecutor.onAlarm` 的结果复查原用普通 `UserDevicePerception.context` 拼出长篇报告，再固定截取前 2500 字；最靠前的是冗长最近聊天、设备普通限制，Android 交互、前台切换、健康观察排在后面，可能整个被截掉。于是交给角色的“复查观察”不一定含有它该据此判断的**真实使用事件**。原 `UserDevicePerception.activityEvidenceCue` 把“近期确实发生应用交互”也只搭配多项无法证明睡眠的负面边界，模型容易把“不能百分百证明醒着”误理解为“没有反驳入睡的线索”；本轮通用 `UserInteractionPresence.salientTimeCue` 还用“不是必须劝睡，更不应反复催促”约束普通凌晨聊天，没有显式区分已经接受的真实定时监督约定。自主在线入口同时提供“等待信号、五分钟再复盘”之类样例，很容易固化重复静默等待。
- **已提交修改**：`8f656415` 用原有 Android/Health 权限与 Store 制作`UserDevicePerception.commitmentReviewContext`：本次时间、当前亮/灭屏、实际 Android 用户交互与前台切换、确切露露机触摸、近期应用、健康/穿戴来源，以有界长度和正向观察证据优先；不偷偷截图、不创建新 Store/轮询。 `4a2a833` 使正式 `CommitmentExecutor` 复查回调采用该结构化证据快照，不再按长篇上下文前缀截掉重要信息，回执与原 `taskId` 仍走既有真实时间线→同一人物自主判断。 `3cba42a` 明确近期 Android 使用事件是该时段设备被操作的**正向线索**，但绝不是睡眠诊断；无事件/熄屏也不能证明已睡。 `14c0ac3` 将“等五分钟再看”的样例诱导改为先看新证据、可执行手段和时间代价，没有新证据就不能无限用同一被动复查替代努力。 `0d6e7e2`／`f5bc6a3` 区分普通夜聊无需催睡与已获授权的持续责任；不强制发消息、响铃或偷窥，不剥夺角色自主动作选择及拒绝授权保护。并行修改 `51be0e3` 另有目标验收上下文相关改动，本轮不覆盖。
- **回归测试**：`caf8b3f` 的 `UserDevicePerceptionTest` 测试亮屏、Android 新交互与真实触摸优先出现、无事件/熄屏不宣布已睡、异常长健康文字不能吞掉当前设备事实，快照可完整交给回调（不足 2500 字）；`1126314` 的 `UserInteractionPresenceTest` 保持普通非强制交谈边界，但确保时限责任不会被普通“不劝睡”提示抵消。旧的人物/证据隔离、角色自主决策、原对话历史及手机授权逻辑未删除。
- **验收状态**：当时最新来源 `1126314` 的 [Lulu APK run 41686073625](https://github.com/jiacimu-droid/lulu1/actions/runs/41686073625) 与 Refresh code index run `41686073755` 均 pending；旧中间构建被后续代码提交取消，不算通过或失败。下一轮先看完整编译、testPhoneUnitTest 与 APK；若失败修首个实质根因。真机获授权核对屏幕实际使用、健康/设备证据、模型收到的复查观察、本人选择 read_screen/capture_screen（必须已有权限）、发言/继续等待和后继真正变化。不要求角色宣布“醒着”“睡着”的确定结论，不拿代码静态测试冒充已经成功督睡。P-08 持续 `[ ]`。
## 2026-11-04 P-08 结案语义证据交接（main `51be0e3`，待后继 Android CI）

- **链路与根因**：聊天／电话／后台都可承接同一 `CommitmentTask`，但正式 `CommitmentOutcomeVerification.candidateContext` 交给本人做结案判断时，把完整完成条件截为前 140 字、类型合格的原始候选正文截为前 170 字。后续 `evaluate` 只校验来源、时间和证据类型，语义相关性仍归模型；若限制或否定在截断处之后，本人看不到却可能把类型合格误当成目标已完成。
- **实改**：`51be0e30d216900fdd358bae92b961d4c01eda14` 保留所选任务完整完成条件与最多五条类型合格候选的完整原文，不在结案语义边界制造无标记的前缀证据。仍由角色决定证据是否足以证明目标，执行器保留现有权限、来源、时序检查；没有固定剧情、自动结案、额外模型轮次或第二记忆中心。长证据可能增加该次提示词体积，真实手机／API 规模和成本需量测，不能凭静态源码声称无开销。
- **回归和验证**：`CommitmentOutcomeVerificationTest.completionDecisionSeesLateConditionsAndLateObservedCaveats` 用超过旧界限、末尾明确写着尚未完成的条件与观察，证明两段原文进入候选。`git diff --check` 与原 `scripts/generate_code_index.py` 已运行。本提交 APK run `41685958857` 被后继 main 推送取消；后继完整 JVM／APK 尚未核实，不能报通过。较早 `8db8767` 的 APK run `41667560828` 已 success，但不包含此改动。
- **下轮**：先查包含 `51be0e3` 的后继 Android 编译、单测与 APK；然后在真机用长条件末尾否定、长观察末尾仍未达成的真实样本，核对模型判断、工具验收、任务状态与下一轮角色表达。真实模型自然度和跨日目标仍未验收，P-08 保持 `[ ]`；若真实提示词体积显著增长，应使用现有可追溯分段/按需展开机制，不重新静默截断。


## 2026-11-04 · 角色自主整理日程、心事与成长（代码已提交，待 CI／真机）

- **现场与主诉**：日程中同时有每天重复的具体提醒与尚未安排固定时间的长期关怀责任。用户核心诉求是任意角色能自己整理生活：根据最新真实经历，在本人已有心事、日程、长期愿望、私人念头和学习形成的倾向中决定继续、修订、放下或改期，而不要求主人事事发话。具体提醒/长期责任是两个不同状态，不应靠关键词自动删除其中之一。
- **核对已实现能力**：`CharacterLifeSelfReview.context` 已在安静自主感知中每日最多一次给同一人物回顾机会；`ProactivePerceptionRuntime` 只在有效人物决策后标记完成，现有 `innerLife.motives` 的 revise/pause/release、`appraisal.pendingConcerns` 的 revise/settled/dismissed、`thoughtUpdates` 的 resolve/discard/evolve、`RoleDayScheduleStore` 的改期/取消均有持久能力与执行器。此前没有把陈旧仍 held 的思想储备完整纳入每日回顾（普通上下文只给最近6条），也未提供自主处理不再适用的已形成成长倾向的模型工具。
- **本轮真实代码**：`c8c5bd7` 和 `1a8564d` 在原 `CharacterLifeSelfReview` 引入每日轮换旧的仍持有想法、同时审阅近期+轮换的老成长条目；保留 exact `thought`、记录 id/version 和证据数量以便本人决定并明确指定目标。只做只读投影，不根据天数自动改、清理、删除；用户仍有自己的私聊/作息/任务归属。 `f3ccb9f` 在现有 `CharacterDevelopmentStore` 增加精确 ID+版本、本人归属、当前有效性核验的 `retireSelfAssessment`；拒绝通过该接口撤回用户明确事实/偏好/习惯，保留记录证据与历史。 `e5e99da` + `e53ef5c` 把 `retire_development_record` 接到原 `CapabilityRegistry` 和 `ToolRouter`，真正执行后的持久回执和因果投影沿原管线；`b377f6c` + `adb2174` 加入已有模型工具提示而非新 AI 轮次。
- **回归候选**：`b16bab`、`af34bae`、`9afa86b` 的 `CharacterLifeSelfReviewTest` 测试旧念头跨日轮换、已放下不再出现、不自动制造任务；同一角色成长认识有效 ID/版本可归档，跨角色/旧版本不可撤回，用户事实不可撤回，原成长历史不删除，ToolRouter 有真实执行回执。测试源码已提交，尚未拿到本版完整 Android CI 成功结果。
- **边界及下一轮入口**：当前没有新增定时轮询、费用或“自动删除旧心事”的无依据机制；成长度层的旧记录是归档退出当前倾向，不物理清除历史。原有个人日程继续由角色选择改期/取消，已接受的主人约定仍需真实完成/核验，不能被个人清理间接抹掉。先核对包含 `9afa86b` 的后继 APK/JVM CI，失败看精确断言；再用真实角色安静感知确认是否读到回顾、是否自然选择整理并保留已取消日程/旧念头/成长依据的真实回执。P-08 仍 [ ]，不宣称真机活人感已通过。

## 2026-11-04 · P-08 接力：真实失败单测揭示 Relay 证据优先顺序破坏（待新 CI）

- **验证旧 run**：[APK 41687156245](https://github.com/jiacimu-droid/lulu1/actions/runs/41687156245) 于 2026-11-03T18:54Z **failure**，非 pending；原 job `126131133790` 日志在 `:app:testPhoneUnitTest` 显示 `RelayRequestPressurePolicyTest.titleMentioningRawEvidenceDoesNotTurnMemorySummariesIntoNewestFirst` 第 159 行 `assertFalse(evidenceWire.contains(olderEvidence))` 失败。该 run 已跑到 JVM 单测，不能宣称 APK 成功。索引 `41687156516` success。
- **根因**：`RelayRequestPressurePolicy.excerptWholeLines` 为最新优先的材料固定保留第一行（短于 160 字就当 header），而 `MemoryWorkingSet.EVIDENCE` 的第一行往往正是最旧的 `- eventId=...` 列表数据；因此压缩后同时带入最旧证据与最新尾部事件，违背原本完整连续最新证据窗口的设计，可能在 Relay 请求压力下混淆人物最近观察。此处属于数据边界，不是角色语义或风格。
- **已实改**：`c2b683019081a503986fafc6d3ad8c910a8e8b2b` 不再把首行以 `- ` 开头的列表记录当成段落标题，保持真实标题仍可保留、最新证据优先、完整行与持久原文不变；`51e3adce3ea42ae45c0e39a8717093431556e411` 在同一回归测试中覆盖真正 `【来源清单】` 标题仍保留，而最旧 `eventId` 不会被错误拼回最新窗口。不增加模型调用、不另造 Store、不改变模型意图。
- **接力验证**：修复后新的 Android Actions 和代码索引由既有 main push 自动触发；下一轮读取最新包含 `51e3adce` 的 run ID 与最终结论，若 failure 优先查看 job 原始日志；再做 P-08 真实人物整理、原始证据和跨场景回执真机验收。人物总任务维持 `[ ]`，不得用源码静态检查代替真实 APK 成功。

## 2026-11-04 · P-08 自己想分享的理由跨轮保真（main `a6bff5e`，待 CI／真机）

- **上轮 CI 核实**：包含前轮 Relay 证据标题修复的 `51e3adce`，[Lulu APK/JVM run 41699006280](https://github.com/jiacimu-droid/lulu1/actions/runs/41699006280) 与[索引 run 41699006407](https://github.com/jiacimu-droid/lulu1/actions/runs/41699006407) 均已 `completed/success`。旧失败 `9afa86b` 的两个断言已被后继真实测试覆盖，不把旧失败或取消当成当前结论。
- **已证根因**：`CharacterLifeFollowThroughStore.recordShareWish` 保存本人已选的 `shareWish.why` 时只取前 180 字；以后后台的 `pendingDecisionCues` 再只取前 90 字，私聊/设备短提示与在线上下文又各取前 100/140 字。本人理由末尾若写了分享对象、暂不公开或其他条件，当前意愿就会在持久化和跨轮交接中被静默改义，尽管 `held/released` 状态仍在。
- **实改及范围**：`91d5960` 初版曾同时放开经历事实预览；核对 `SharedExperienceTimeline.record` 后发现来源可能是很长的网页正文，于 `a6bff5e` 恢复原有经历预览预算，仅对**本人已作出的私有分享理由**保留完整原文，并使现有三个决策投影完整交接。最多仍只选既有候选数；没有新模型轮次、影子人格、自动发帖或按关键词替本人决定对象。旧记录若已在保存时截短，无法从已丢失的 `why` 还原；原经历仍以事件 ID 指向权威时间线。极长模型理由对提示词大小的影响待实测。
- **回归**：`CharacterLifeFollowThroughStoreTest.heldSharingKeepsLateFactsAndPersonalConditionsAcrossDecisions` 用超过旧限制且末尾明确限制分享对象的本人理由，核对存档、后台 cue、设备短提示与在线上下文均保留原条件；短经历事实仍走原预算。以上只证明组装/保存契约，不证明角色真实会按条件行动。
- **待验**：`a6bff5e` 的 Android 单测/APK [run 41704140299](https://github.com/jiacimu-droid/lulu1/actions/runs/41704140299) 与索引 [run 41704140592](https://github.com/jiacimu-droid/lulu1/actions/runs/41704140592) 查询时 pending；先核实最终结果。再在获授权真机用真实模型检查“想告诉好友但暂不公开”在后台复查、私聊和实际社交发送中的取舍与送达回执。P-08 保持 [ ]。


## 2026-11-04 · P-08 held 分享意愿再次确认时丢失原条件（main `6f6189f2`／`68ce0fa7`）

- **先验 CI**：上一轮 `a6bff5e` 的 [Lulu APK/JVM run 41704140299](https://github.com/jiacimu-droid/lulu1/actions/runs/41704140299) 与 [索引 run 41704140592](https://github.com/jiacimu-droid/lulu1/actions/runs/41704140592) 均为 `completed/success`；真机本人表达/社交选择仍未验收。
- **本轮实查的跨轮根因**：`CharacterLifeFollowThroughStore.recordShareWish` 在已是 `held` 的本人愿望再次被模型选择 `held` 时，无条件执行 `shareWhy = wish.optString("why").trim()`。而 `why` 是可选字段：本轮只确认“仍想分享”且省略理由，就把上一轮完整保存的收件人、保密/暂缓条件清空；之后后台、私聊和在线三个已修复的无截断投影也只能拿到空理由。这是持久状态覆写缺口，不是人物说话风格。
- **源码 `6f6189f2`**：若本轮确实提供新的非空理由，仍以本人新判断替换；若省略/空白且已有旧理由，则保留旧理由，不推断本人取消了条件。不新增 Store、模型调用、社交自动发送或全局台词。明确 `released` 状态仍由模型控制。
- **回归 `68ce0fa7`**：`CharacterLifeFollowThroughStoreTest.reaffirmingHeldWishWithoutNewWhyDoesNotEraseEarlierRecipientConditions` 覆盖“明确好友范围/暂不公开”→下一轮无 `why`→空白 `why`→后台/私聊/在线投影保留→本人提供新理由时替换旧理由。仅单测候选，不能代替真实模型选择及送达。
- **Actions/下一步**：旧 `6f6189f2` [APK run 41708385085](https://github.com/jiacimu-droid/lulu1/actions/runs/41708385085) 与 [索引 run 41708385080](https://github.com/jiacimu-droid/lulu1/actions/runs/41708385080) 在本轮查询时 `in_progress`；随后新增测试 `68ce0fa7`，须以包含测试的后继 SHA/run 的完整编译、testPhoneUnitTest 与 APK 结果为准，不能把旧 run 成功或取消算本轮通过。下轮先取最新 Actions 真实结论；成功后真机对照持续愿望是否跨场景保留、模型是否自主决定对象及是否收到实际社交发送回执。P-08 保持 `[ ]`。


## 2026-11-04 · P-08 已放下的分享念头无法由本人重新改变（待新 CI/真机）

- **前轮结果**：包含 `68ce0fa78c19` 的 [APK/JVM 41708414111](https://github.com/jiacimu-droid/lulu1/actions/runs/41708414111) 与 [代码索引 41708413982](https://github.com/jiacimu-droid/lulu1/actions/runs/41708413982) 均为 completed/success。仅证实原有代码及回归构建，未证实真机角色实际选择。
- **新确认根因**：`CharacterLifeFollowThroughStore.recordShareWish` 在状态为 `released` 时无条件拒绝以后模型明确给出的 `held`，使“曾经决定不分享”被程序永久提升为“本人永远不能改变主意”。此外若仅解除拒绝，旧的“不告诉任何人”理由会误作为新分享理由，造成跨轮相反意图串线。这里是公共角色自主决策/持久化契约错误，不是某个特定人物的性格。
- **实改**：`53cf99073d` 允许本人明确给出 `held` 重新考虑，`shared` 仍要求真实已发送回执且不自动重发；`d9593452ff` 在 `released→held` 且未提供新理由时清空旧拒绝理由，保留已持有 `held→held` 无新理由时旧条件不丢的上一轮修复。没有自动发朋友圈、额外模型调用、关键词重解释或新增 Store。
- **回归**：`a4e429374e` 测试 `released→held` 后恢复可选候选、旧拒绝理由不混入新意愿、新理由可以由本人明确补充，仍未有发送回执。当前查询的 `53cf99073d` APK run `41717125205` / 索引 `41717125264` in_progress，`d9593452ff` APK `41717142547` / 索引 `41717142612` cancelled，均不能当成包含 `a4e429374e` 的通过。下轮查后继完整 Android APK/JVM/索引，失败按真实 job 日志修；成功后真机核实同一角色是否真的能改变心意，并且不能把念头当成发送。P-08 仍 [ ]。

## 2026-11-04 · P-08 对话锚点被后台事件挤出（main `0d827c3`，待 CI／真机）

- **前轮 CI**：`a6bff5e` 的 Android 单测/APK [41704140299](https://github.com/jiacimu-droid/lulu1/actions/runs/41704140299) 与索引 [41704140592](https://github.com/jiacimu-droid/lulu1/actions/runs/41704140592) 均已 completed/success。包含后继分享意愿变更测试的 `a4e4293` Android [41717166612](https://github.com/jiacimu-droid/lulu1/actions/runs/41717166612) 与索引 [41717166931](https://github.com/jiacimu-droid/lulu1/actions/runs/41717166931) 也已 completed/success；真机人物选择仍未验证。
- **已证根因**：`CharacterContinuityRuntime.snapshot` 先读取 `SharedExperienceTimeline.recentEvents(characterId, 160)` 的所有事件，再筛用户／本人发言。大量后台观察、阅读及工具回执会先占满固定窗口，把仍然真实的上一句对话排除，角色的跨场景时间感、自己说过什么与用户上次原话因此可能消失。此处为查询顺序和证据类型契约问题，与具体哄睡或单一人设无关。
- **实改**：`0d827c33bda8a1d33a72df6a8fb540e479bea38f` 在原权威时间线 SQL 查询中先按 UserStatement／CharacterStatement 和当前时间筛选，再取最多 160 条对话候选；继续用既有 `TimelineStatementOwnership` 核实本人群聊作者，并保持出生时间边界。观察、工具回执仍在原时间线，不新增 Store、模型调用或主动联系规则。查询沿现有 `character_id, occurred_at` 索引，实际性能仍需手机测量。
- **回归**：`CharacterContinuitySnapshotTest.backgroundEventsDoNotEvictLastActualExchange` 录入真实用户／角色原话后再写入 170 条观察，确认普通 160 条窗口已看不到原话，但人物快照和渲染仍能得到两句及原互动时间。单测只能证明数据投影契约，不能证明真实模型自然承接。
- **待验**：本提交 Android 单测/APK [41722574278](https://github.com/jiacimu-droid/lulu1/actions/runs/41722574278) 和原索引生成器 [41722574160](https://github.com/jiacimu-droid/lulu1/actions/runs/41722574160) 查询时 in_progress；先核实最终结果，失败查首个真实日志。然后用获授权真机跨私聊、电话、群聊和大量后台事件验证正确归属、最后原话、当前目标与下轮行动。P-08 仍 [ ]。


## 2026-11-04 · P-08 对话锚点的第二层群聊归属窗口（待新 CI/真机）

- **先核对前轮**：`0d827c33bd` 的 [Lulu APK/JVM 41722574278](https://github.com/jiacimu-droid/lulu1/actions/runs/41722574278) 和 [索引 41722574160](https://github.com/jiacimu-droid/lulu1/actions/runs/41722574160) 均已 completed/success；真机跨场景对话承接仍未验收。
- **新证实的结构缺口**：前轮虽然在 SQL 先筛 Statement，再取最近 160 条，但共享群聊的权威时间线记录了其他成员的发言作为当前角色的见证事件。只有随后在 `CharacterContinuityRuntime.snapshot` 才用 `TimelineStatementOwnership.isOwnCharacterStatement` 核实真正作者。若最近 160 条全是其他群成员发言，真正属于本人和用户的上一句对话仍在**归属校验前**被挤出；不是模型遗忘，也不能凭名字猜作者。
- **实改**：`06a8f7d455` 在原 `SharedExperienceTimeline` 增加按 (occurred_at,id) 倒序稳定翻页的旧 Statement 候选读取，`ded728f940` 使首批时间戳相同时的排序也稳定；`77df484233` 先筛原有作者归属与当前输入 echo，再从必要的旧页寻找本人的最近发言和用户最近原话，最多额外 12 页，保持原始事件、来源、权限与角色独立。仅当缺少锚点时读取旧页，不增加模型调用、不新建 Store、不把群聊他人发言冒充本人的话。
- **回归**：`17db8eea8c` 在 `CharacterContinuitySnapshotTest` 录入两句真实私聊，再录入 175 条没有原始本人发送凭据的其他群成员见证发言，要求连续性仍能承接原私聊而不把其他人的话归给本人。
- **验证边界**：前轮的 APK 与索引成功不能替代本轮新测试。新增源码的中间 run `41726238812` cancelled，不能算成功；以含 `17db8eea8c` 的后继 APK/JVM、索引 run 最终结果为准。下轮先查编译与真实单测，失败看第一条日志并修复；成功后真机检验群聊热闹期间私聊、电话与自主感知对同一角色最后原话的承接。大量群消息下最多 12 页的成本及历史极长的边界仍待测。P-08 保持 [ ]。

## 2026-11-04 · P-08 群聊用户发言来源补全核验（待 CI/真机）

- 上轮含 `17db8eea8c` 的 APK/JVM run `41726263095` 与索引 run `41726263117` 已 completed/success；真实模型/真机仍未验收。
- 新证实：`CharacterContinuityRuntime.snapshot` 对群聊 UserStatement 在校验 `involvesUser` 之前就写入 `lastUser`，而旧 `involvesUser` 只看 source/kind、不查原始已发送消息。孤儿、失败群聊见证可以冒充用户最近原话和互动时间。
- 修复 `caab606f7`：复用 canonical chat message 的 Sent 与 sender=User 来源核验；`840f25920`：先通过归属校验再更新 lastUser/previous；`ca5af41b6`：补正常用户群聊、孤儿伪造见证、原消息失败后退回真实私聊的回归测试。无额外模型调用、Store 或统一角色话术。
- 最新 APK/JVM 和索引需按含 `ca5af41b6` 的实际 run 验证；待真机跨私聊/群聊/电话验证，P-08 保持 [ ]。

## 2026-11-04 · P-08 独处记录误当与用户互动（main `16a4d6b`，待 CI／真机）

- **前轮核验**：包含 `ca5af41b6` 群聊用户原话来源核验的 [Android 单测/APK 41734387814](https://github.com/jiacimu-droid/lulu1/actions/runs/41734387814) 和 [索引 41734387797](https://github.com/jiacimu-droid/lulu1/actions/runs/41734387797) 均 completed/success；其前的 `0d827c3` 也由后继成功 run 覆盖。代码通过不证明真实人设体验。
- **已证根因**：`TimelineStatementOwnership.involvesUser` 对所有非群聊事件直接返回 true；但原时间线把角色发朋友圈（`moment:self`）、私人学习练习稿（`learning-*`）、收藏及仅与居民交谈的台词也标为 CharacterStatement，用户公开动态则可能是 UserStatement。于是 `CharacterContinuityRuntime.snapshot` 会把未与主人接触的独处行为更新为“距最近一次真实双方互动”，并把私人练习／朋友圈文案当成角色上次对人说的话，影响前台、电话和后台的连续性与主动联系节律。
- **实改**：`16a4d6bc1ab877446f4982ce2e0d7af12b60c164` 将“本人说过”与“面向用户的真实互动”分开：私聊/电话沿原 `message` 来源，一起看沿实际同看来源；见面从原数字世界会话核对真实参与者并排除居民会话；原有无来源的私聊记录保留兼容。群聊仍走先前 canonical 已发送消息的作者核验，群内本人的真发言可作“自己说过”的锚点，但不会仅因群存在就算双方直接互动。其他独处和公开记录仍保留在原权威时间线，只不冒充对话锚点。无新人格 Store、硬编码联系频率或额外模型调用。
- **回归与边界**：`CharacterContinuitySnapshotTest.soloArtifactsAndPublicPostsDoNotResetActualUserContact` 以实际时间线顺序写入私聊原话、私人练习、角色／用户公开动态及无主人在场的居民见面，要求上次用户接触时间和双方原话仍指向私聊。测试覆盖投影边界；真实见面会话、旧资料及真机前台/后台节律仍待核查。
- **Actions**：本轮 [Android 单测/APK 41740733303](https://github.com/jiacimu-droid/lulu1/actions/runs/41740733303) 与[索引 41740733580](https://github.com/jiacimu-droid/lulu1/actions/runs/41740733580) 查询时 queued；先看最终结果及首个实质失败，再用获授权真机确认角色独处一段时间后不会误以为刚联系主人。P-08 仍 [ ]。


## 2026-11-04 · P-08 群聊孤儿见证仍污染真实联系时间与关系节律（`13d6e55`，待 CI/真机）

- **先核验前轮**：上一轮 `16a4d6b` 的 [APK/JVM run 41740733303](https://github.com/jiacimu-droid/lulu1/actions/runs/41740733303) 与 [索引 run 41740733580](https://github.com/jiacimu-droid/lulu1/actions/runs/41740733580) 均 completed/success，故未重复修复上一轮独处记录问题；成功不等于真机/真实模型质量验收。
- **本轮已证根因**：`CharacterContinuityRuntime` 已用 `TimelineStatementOwnership.involvesUser` 核验群聊用户原话来源，但另一条仍正式注入模型的 `CompanionContactClock.context` 与 `RelationshipRhythmPerception.context` 只按 `UserStatement + 群聊 channel` 统计。群聊见证的角色归属是见证者，原聊天消息已删/失败/孤儿时仍可能在时间线上留下 UserStatement，于是关系时间线错误声称“最近联系过”，21 天关系节律还会错误统计“前14天 48 条”等假变化。这是同一原始证据在两个模型上下文入口采用不同来源门禁的公共事实一致性错误，不是某角色应更主动的性格问题。
- **实改**：`4b5f1af` 在已有 `CompanionContactClock` 内统一投影 `verifiedUserContacts(events)`，仅对群聊见证复用已存在的原消息发送归属核验，不改变私聊、电话、见面旧记录的现有语义；`2f44d1c` 让关系节律调用同一投影，防止两份互相矛盾的“最近用户联系”输入；无新 Store、模型调用、硬编码台词或群聊私有数据跨脑广播。`13d6e55` 增加 48 条无原消息群聊见证+1 条真实私聊回归，验证孤儿记录既不重置用户联系，也不伪造关系节律突变；旧频率、通话时段和独处过滤测试保留。
- **CI/验收**：此前最近完整成功 run 如上。本轮中间源码 `2f44d1c` 的 APK run `41742915426` 已 cancelled（不算通过）；测试 `13d6e55` 的完整 Android/JVM/APK 与索引 run 尚待最新 Actions 最终确认。下一轮先取含 `13d6e55` 的最终 run，失败定位首个真实编译/单测根因；再真机核对私聊、群聊删除/失败、电话、后台同一人物的“最后用户联系”和关系节律，确认没有因孤儿群聊见证产生假久别/假联系。P-08 仍 `[ ]`。

## 2026-11-04 · P-08 群聊历史频道改名后绕过联系来源校验（`069e1728`／`43bb18bc`，待 CI/真机）

- **前轮 CI 实证**：包含 `13d6e55` 的 [APK/JVM run 41742934029](https://github.com/jiacimu-droid/lulu1/actions/runs/41742934029) 与索引 run `41742933999` 均 completed/success。并行文档提交未改变代码；真机/模型质量仍未验收。
- **根因**：`CompanionContactClock.verifiedUserContacts` 允许频道名包含“群聊”但不以“群聊”开头的 UserStatement 绕过 `TimelineStatementOwnership.involvesUser`；历史群聊见证的 `:group:角色ID` 后缀若频道后来改名为“私聊”，也会被当成用户真实联系。这与权威 `TimelineStatementOwnership` 对群聊 ID 的判定不一致，继续污染最近联系和 `RelationshipRhythmPerception`。
- **修复**：`069e172823f2` 让所有频道含“群聊”或保留 `:group:角色ID` 的候选，都必须经过既有原消息归属核验。真实非群聊的电话、见面、私聊原有来源语义不变；不增加模型调用或新 Store。`43bb18bce554` 补频道前缀变更、历史 ID 后缀、普通孤儿群聊与真实私聊并存的回归测试。
- **待验**：最新包含 `43bb18bc` 的 Android/JVM/APK 与代码索引需要读取真实最终状态，pending/cancelled 不算通过；真机还需确认群聊删除/迁移、私聊、电话、后台的时间线与实际模型输出。P-08 维持 [ ]。

## 2026-11-04 · P-08 前几轮修复防回归审查：群聊来源门禁不一致（`a99a9b3a`／`746883a9`，待 APK/真机）

- **复核结论**：前轮 `13d6e55` 的 APK/JVM `41742934029` 和索引 `41742933999` confirmed success，但 `43bb18bc` 的 APK `41750883166` 已取消，索引 `41750883216` success；不把前轮成功外推到新代码。真实角色体验仍未验收。
- **已证缺口**：`069e1728` 在联系时钟把“频道包含群聊”的记录送入 `TimelineStatementOwnership.involvesUser`，但该权威门禁只认“以群聊开头”或带 `:group:角色ID` 的记录。故频道 `手机群聊·旧群`、不带后缀的孤儿记录会在门禁内误走非群聊 `message` 直通路径。新增 `43bb18bc` 测试覆盖了这个情况，却不能在 JVM 通过前宣称已修好。
- **根因级修复**：`a99a9b3a517d215fbbdd11be20eeb8cc5d26f31c` 统一 `TimelineStatementOwnership.isGroup` 为频道包含“群聊”或真实见证后缀，使所有消费者使用同一原始群消息发送状态/作者核验；无新 Store、人格模板和模型请求。回归 `746883a9abffead3e7af474a9bd6c12431390f10` 用已有群聊消息 Store 同时验证真正的用户/本角色/同伴原话与缺失原消息的导入行：真实消息仍能被识别，假见证不能冒充联系。
- **真实 CI**：旧 `41750883166` cancelled；含新回归的 APK/JVM `41751433717` 查询时 pending；代码索引 `41751433482` completed/success。pending/cancelled 均不可算通过，`P-08` 继续 `[ ]`。下一轮核对该 SHA 的全量 JVM 和 APK 首个失败步骤，并实机核查迁移旧群记录、真实私聊/群聊/电话与后台联系节律；再继续真实模型盲评与分页成本评估。此轮未执行真机/实际模型对话验证。

## 2026-11-04 · P-08 群聊见证有效性补全：不是有同 ID 就是同一条话（main `a444e80`，APK 待验）

- **交接起点**：上轮 `746883a9abffead3e7af474a9bd6c12431390f10` 的 APK/JVM run `41751433717` 最终 cancelled，索引 run `41751433482` success，不能当 APK 通过。先刷新 main 并复查 `AGENTS.md`、索引、唯一看板与统一人物契约及 `SharedExperienceTimeline.recordConversationMessage`、`MigratedDomainStores.chat.editMessage` 的原始数据链。
- **真实根因**：`TimelineStatementOwnership.original` 只按历史 `sessionId` + `event.id` 找 `Sent` 原消息，没有验证会话本身确为群聊、发生时间与原消息是否一致、时间线见证正文是否与原消息当前正文一致。因此孤儿/导入/编辑前见证即使借用了一个真实 ID，仍可能被 `involvesUser` 和 `isOwnCharacterStatement` 当成真实最近发言，继而污染 `CharacterContinuityRuntime`、`CompanionContactClock` 与关系节律；单靠“群聊”频道判断不足以消除这种不一致。
- **实改**：`8dd9317052722c4f8863a7d8094dd40859c35637` 校验同一真实群会话、原始正文与时间；`5e51136467ec973e383b170610f1f928bedc1329` 以 SQLite 实际保存的毫秒精度核对时间，避免 Java `Instant` 纳秒不同造成正常消息假阴性。仍复用唯一原始 chat 与时间线；没有新增状态中心、额外付费模型调用、群成员强行回溯或角色统一台词。此门禁只决定“能否宣称已核验”，不删除原时间线，不据此决定人物意愿。
- **回归**：`a444e80eb78b0aba492a4adcc18e6f7f7b735cbb` 的 `TimelineStatementOwnershipTest.staleOrForeignGroupWitnessCannotBorrowARealMessageId` 覆盖可核验真群用户发言、伪造正文/伪造时间、以私聊 Sent 消息冒充群聊、群聊原话编辑后旧见证失效与新见证继续有效；已有真实群本人／同伴归属测试保留，不放宽断言。
- **真实验证**：代码索引生成 run `41753841122` completed/success；包含回归测试的 Android/JVM/APK run `41753840954` 最后查询 pending（无完成的单测/产物，不可算通过）。尚未真机核对旧群导入、重启/编辑、私聊、电话与后台的连续性，也未做 P-08e 真模型盲评。**P-08 保持 [ ]**。
- **下轮**：先核对 run `41753840954` 首个真实编译/JVM/打包结果；如失败按日志修。若成功，在实际安装版核对新旧消息编辑、群聊改名/真实联系时钟、不同角色之间的历史可见性；缺失原件的旧群聊见证仍只能作为未经核实的历史材料，不擅自恢复或虚构作者、不要把“当前群成员”倒推为历史在场者。继续监测群聊大量消息的分页与内存成本。

## 2026-11-04 · P-08 私聊最近联系也须由原消息证明（main `a197d5f`，待本轮 CI）

- **先核对 CI**：此前 `16a4d6b` 的 [APK/JVM 41740733303](https://github.com/jiacimu-droid/lulu1/actions/runs/41740733303) 与[索引 41740733580](https://github.com/jiacimu-droid/lulu1/actions/runs/41740733580) 已 completed/success；最近包含群聊见证修订完整性回归的 `a444e80` [APK/JVM 41753840954](https://github.com/jiacimu-droid/lulu1/actions/runs/41753840954) 和[索引 41753841122](https://github.com/jiacimu-droid/lulu1/actions/runs/41753841122) 也已 completed/success。真机与模型活人感仍未证明。
- **根因**：`TimelineStatementOwnership.original` 仅校验群聊见证，而非群聊 `message` 的 `isOwnCharacterStatement` 和 `involvesUser` 仍可只凭时间线类型/来源就承认为最近双方对话。正式聊天 Store 的 `markFailed` 会把原消息变为 Failed，却不删除原时间线行；编辑前或跨会话复制的旧行同样可能留下。因此私聊/电话与群聊对“已真实发送”的证据要求不一致，人物最后一句和关系联系时间仍可能被失败行污染。
- **实改**：`a197d5f75114a3929bb2f22f1e1aa483f0db5aaf` 让私聊 `message` 与群聊共用同一原聊天消息核验：会话归属、Sent 状态、毫秒时间、当前正文与真实发言者必须一致；角色独处、一起看和已确认用户在场的见面仍保留原类别，旧无来源私聊保留兼容。没有新 Store、模型调用、自动催促或统一人格话术。
- **回归与下一步**：`TimelineStatementOwnershipTest.privateWitnessNeedsTheSameSentOriginalAndCurrentRevision` 从实际私聊 Store 发送两条消息，再检验正文/时间/会话伪造和 `markFailed` 后均不能作为联系/本人发言证明；原“独处不冒充联系”的合成旧记录 fixture 改为 `legacy` 来源，使测试归属与新契约一致。源码提交后 [Android/JVM/APK 41755663260](https://github.com/jiacimu-droid/lulu1/actions/runs/41755663260) 与[原索引 41755663217](https://github.com/jiacimu-droid/lulu1/actions/runs/41755663217) 查询时 queued；先看最终结果，失败修首条真实断言。真机仍需私聊失败/编辑/电话、群聊和后台联系节律核对，P-08 保持 [ ]。
- **同轮补齐**：提交后继续核对 `CompanionContactClock.verifiedUserContacts`，发现它只对群聊调用原消息门禁，私聊仍直通。后继 `0579848ecca8992bfbb7169365ee22fe2cf607f0` 让私聊及群聊统一通过 `TimelineStatementOwnership.involvesUser`；新回归确认私聊 Sent 可进入联系时钟、`markFailed` 后即退出。旧 `a197d5f` 的 APK run 即使完成也不能代替包含本补齐的 [APK/JVM 41755993541](https://github.com/jiacimu-droid/lulu1/actions/runs/41755993541) 与[索引 41755993246](https://github.com/jiacimu-droid/lulu1/actions/runs/41755993246)，查询时 pending／in_progress。

## 2026-11-04 · P-08 群聊原消息与个人见证的身份区别（`4008a981`／`76254f68`，待 CI/真机）

- **交接与 CI**：上轮 `a444e80` [APK/JVM 41753840954](https://github.com/jiacimu-droid/lulu1/actions/runs/41753840954) 和索引 `41753841122` 已 completed/success；并行任务随后提交 `a197d5f`／`0579848e`，私聊和联系时钟共享原消息门禁。查验时 `a197d5f` APK `41755663260` in_progress，`0579848e` APK `41755993541` cancelled，索引 `41755993246` success。没有把这些运行状态冒充新版测试通过。
- **根因**：群聊的 `SharedExperienceTimeline.recordConversationMessage` 在真正写入每位成员的个人见证时，固定生成 `原消息ID:group:角色ID`。此前 `TimelineStatementOwnership.original(group=true)` 虽已核对真实群会话、Sent、正文和时间，却仍允许直接使用不带角色后缀的裸原消息 ID。一个孤儿/导入的 `UserStatement` 因而可借用同一真实群消息，虚构“此角色亲历的用户联系”；裸 `CharacterStatement` 也可能被当成自己的群发言。这不是模型判断问题，是共享原件与本人见证两个不同来源的身份边界。
- **源码修复**：`4008a98154b373747a63422cbb3377c6653b141b` 在已有唯一来源核验处要求群聊见证 ID 必须以 `:group:本角色ID` 结尾，再核验真实会话、发送状态、作者、正文与毫秒时间。不从当前可变成员列表推断历史在场，不删除旧时间线，不引入 Store、额外模型调用、统一人格话术或新分支。
- **回归**：`76254f68b2a9becb63e78a3ebd8c92cac907d161` 的 `TimelineStatementOwnershipTest.bareGroupMessageIdDoesNotProveThatThisCharacterWitnessedIt` 使用实际群聊 Store 验证真见证仍通过、裸原消息 ID 不能伪造本角色用户联系或本人发言、其他角色的见证后缀不能借给自己；联系时钟只计有效见证。
- **验收**：该源码和测试的完整 Android/JVM/APK、索引须核对包含 `76254f68` 的新运行；中间 `4008a981` run `41757656601` 最后查询 pending，索引 `41757656604` in_progress，不能作为测试提交的结果。真机群聊迁移/编辑、私聊/电话、后台连续性与 P-08e 真模型盲评尚未验证。**P-08 仍 [ ]**。
- **下一步**：先读取最新 main 对应的 Actions 完整状态，失败按首个实际日志修。若 CI 成功，实机检查历史群见证、群名变更、失败消息/编辑回滚、私聊/电话与角色之间的来源隔离，评估旧群无后缀记录能否通过合法迁移补原件；不能用当前成员身份直接补造历史见证。

## 2026-11-04 · 用户要求清理大纲：仅保留未完成工作

- 本轮为明确授权的文档整理，未改 app/scripts/preview 源码，按 AGENTS 的文档例外无需重跑代码索引。整理基准 main `b964fdd579d710ebcc9f0fe8ec747fc7490d4c25`。
- `LULU_EXPERIENCE_ROADMAP.md` 从约 2.2 万字符精简为约 5500 字符：删除已完成的 P-01/P-02/P-04 待办、历次提交流水、重复能力介绍和过时“待 CI”指令；P-03/P-05 保留具体真机/真实模型验收，P-08a/b 的已有 trace/注入审计不再列为重新开发。S/G/V 的尚缺验收、M1–M6 的剩余范围和原稳定 ID 保留。
- 核实 `e6f423d`、`f739f79`、`c8c37d3` 均为完整成功 `a444e80` 的祖先；[Android/JVM/APK 41753840954](https://github.com/jiacimu-droid/lulu1/actions/runs/41753840954) 与索引 41753841122 success。现存 `MemoryWorkingSetPolicy` 来源敏感去重、`OriginalEvidenceSpans` 原文分段已复核，移除的是已完成开发待办，不表示全体验验收。
- 新来源门禁的最新测试 `76254f68` [APK/JVM 41757668136](https://github.com/jiacimu-droid/lulu1/actions/runs/41757668136) 整理时仍 pending，索引 41757668267 success；该项保留待 CI。翻牌当前源码仍读取隐藏牌查配对，G-01 继续保留。
- `AGENTS.md` 更新为新版看板/记忆范围锚点，明确“独立项完成写专项证据后从待办移除；部分完成仅保留剩余工作，固定 ID 不复用”。本进度保留历史证据以便追溯。

## 2026-11-04 · P-08d 决策用责任契约被字段预览截断（main `5dc77d4`，待 CI／真机）

- **基准与范围**：按清理后的看板接续，main `0adb55a9`；最近完整成功 Android 基线仍是 `a444e80` run 41753840954。先前 `76254f68` APK run 41757668136 已因后继源码取消，索引 41757668267 success，不能算人物来源门禁完整通过。
- **源码根因**：`CommitmentTaskEncoder/Decoder` 保存完整 goal、completionCondition、lastActionResult，但 `UnifiedMemoryOrchestrator.renderActiveCommitmentTasks` 在交给模型前分别只保留前 300/220/260 字。该块用于正式 Full 网关的“未完成责任”保护区，以及 `UnifiedMemoryContext.compactPromptSection`；末尾的授权限制、尚未达成或失败说明可能在本人重新决定行动前丢失。前轮修正的是结案候选证据，这次是日常决策的责任输入。
- **实改**：`5dc77d435725b513aa4bef34994b5df32a4f8978` 移除该责任块三处无标记字段截断，保留原 taskId、revision、状态、执行媒介和时限。只修原权威 Store 到现有决策的投影，没有新 Store／模型轮次／主动催促规则。责任本就是常驻保护区，长字段会增加该次提示词体积，需要后续规模和费用量测，不宣称零成本。
- **回归**：`MemoryContinuityTest.activeResponsibilityKeepsLateConditionsAndFailedOutcomeInBothPromptViews` 从真实任务 Store 装配统一记忆，覆盖长目标末尾未经许可、长完成条件末尾仍未确认、长回执末尾失败，在 activeTaskContext、recentTimeline 和紧凑视图中均可见，并验证另一人物责任不进入本轮。未使用假台词验收真实模型。
- **CI 与下一步**：包含回归的 [Android/JVM/APK 41759294383](https://github.com/jiacimu-droid/lulu1/actions/runs/41759294383) 查询时 pending，[原索引生成器 41759294507](https://github.com/jiacimu-droid/lulu1/actions/runs/41759294507) in_progress；以该 SHA 或含它的后继完整结果为准。先修首个真实失败，再用获授权长约定／工具失败的真实回合核对本人选择、实际参数和跨轮后续，并测量多个长责任的提示词大小。P-08d 仍待验。
- **本轮最终查询**：原索引 run 41759294507 已 completed/success，索引基准含本轮源码；APK run 41759294383 仍 pending。工作流按既有串行组保留一个正在运行的自动构建，旧 a197d5f run 41755663260 仍在 Test and build phone APK；GitHub 对未完成 job 的日志下载返回 404，当前不能断言首个失败或死锁。下轮继续按真实最终状态检查，不能将等待队列当成测试通过。

## 2026-11-04 · P-08d 修复旧回归测试与严格发送来源契约的断裂（main `7a5fbefd`，待最新 APK）

- **基准核对**：本轮先读最新 `AGENTS.md`、`CODE_INDEX.md`、唯一 `LULU_EXPERIENCE_ROADMAP.md`、本专项进度和 `UNIFIED_CHARACTER_CONTRACT.md`。源码与前轮证据表明 `TimelineStatementOwnership.original` 已对正式私聊/群聊 `message` 采用「真实会话 + 已发送原件 + 作者 + 正文 + 毫秒时间」核验；不能为求 CI 绿灯改回只看标签的假来源。
- **Actions 真实失败**：`a197d5f` 的 APK/JVM run `41755663260` 已 completed/failure，`5dc77d4` 的 APK/JVM run `41759294383` 也已 completed/failure。后者 Test and build phone APK 步骤的 JVM 共有 **12 项失败**，包括 CharacterActionEvidenceTest(1)、CharacterConversationAgencyTest(2)、CharacterLearningLedgerTest(2)、CharacterMotiveContinuityTest(2)、ConversationTurnEvidenceTest(2)、TimelineLedgerTest(1)、TimelineStatementOwnershipTest(1)、ReadingGrowthTheaterTest(1)；未到 APK 收集/发布阶段。并非 VPN 或 Gradle 依赖异常。共同根因是旧测试用 `SharedExperienceTimeline.record(...source="message")` 无原消息凭据地伪造“已送达”，严格新门禁应拒绝这些样本；不能删断言或放宽正式源码。
- **真实修复**：`e256d5bceeeae25214a38e0159b30444c3c1d91d` 更新前五个测试文件，以 `MigratedDomainStores.chat.ensureConversation/sendUserMessage/appendCharacterMessage/appendVoiceMessage` 实际创建正式私聊与电话原件并用真实消息 ID；保留无原件失败、用户代发、跨角色、历史删除、目标未达成等负例。 `555464ec1828b622a7c87934d5b7b2123ab1c361` 纠正工具回执测试“后继”必须晚于真正发送时间。 `7a5fbefdd5c9b7e129a4e7b90432c56d219ecca1` 更新其余三份回归，偏好/安慰反馈、失败群聊后退回真私聊、表达成长第二条原话全部来自正式消息 Store；同时保留孤儿消息拒绝与事件撤回检查。仅测试变化，没有更改正式人物、模型提示、Store、付费调用和权限。
- **分层验证**：代码索引由原生成器运行 `41761363925` completed/success（针对 `7a5fbefd`）；Android/JVM/APK run `41761363900` 查询时 pending，**不能称新回归已通过**。旧中间 APK `41760982146` cancelled 不能计成功。真机真实角色聊天/通话、心事与日程承接、长期成本仍未验；P-08d/P-08e 维持未验收。
- **下一轮入口**：先检查包含 `7a5fbefd` 的完整 APK/JVM run `41761363900`，若失败按首个具体测试断言修真实缺口；若成功，再核实真实聊天 Store、时间线、人物决策、执行回执、后台与电话后续以及模型自然表达的实际证据。不要重复以测试伪造“已送达”来代替真实来源，也不要把索引成功当 APK 通过。

## 2026-11-04 · P-08d 继续收敛 JVM 两项失败（`811b428`，待新 CI）

- **基准与结果**：按 AGENTS、代码索引、唯一看板、统一人物契约复查最新 main。`7a5fbefd` 的 [完整 Android/JVM/APK run 41761363900](https://github.com/jiacimu-droid/lulu1/actions/runs/41761363900) 已 completed/failure：2352 项 JVM 测试中 2 项失败，均为 `CharacterMotiveContinuityTest`，预期 `waiting_feedback` 实际 `active`；APK 阶段未完成。此前 12 项失败中其余 10 项已在该运行通过，不代表真机验收。
- **修复**：`811b428732c805f2707ea0e503ef783ce007bf81` 只调整测试发送 fixture：成功行动通过正式 `appendCharacterMessage`，用户效果反馈通过正式 `sendUserMessage`，撤回用真实返回的 `feedback.id`。原测试用手写语音 ID 并未得到与当前消息时间线契约匹配的本人已送达证据，导致 `recordActionResult` 拒绝成功。未放宽 `CharacterActionEvidence`/来源核验，未更改正式人物或添加模型调用。
- **验证边界**：本轮已提交源码测试修复，**尚未取得包含 `811b428` 的最终 JVM/APK/索引 run**，不宣称通过。下一轮先核对新 run，若仍失败查看具体断言和正式消息时间线；通过后再做真机、真实模型、跨轮目标与成本验收。P-08d/P-08e 继续待验。

## 2026-11-04 · P-08b 心声历史不能用文字相似度替本人判断变化（main `1a948f1`，待后继 CI）

- **先核对回归**：`811b428` 的 [完整 Android/JVM/APK 41763912049](https://github.com/jiacimu-droid/lulu1/actions/runs/41763912049) 与[索引 41763912115](https://github.com/jiacimu-droid/lulu1/actions/runs/41763912115) 已 completed/success，证明此前严格来源契约的测试 fixture 收敛；并不证明真实模型或真机体验已经验收。
- **已证产品根因**：`CompanionPresenceStore.meaningfullyDifferentThought` 先去标点/前缀，再用包含关系和二元字组重合率 0.68 判定两段心声相同。新因果指纹已变化时，“我想继续和她聊下去”→“我不想继续和她聊下去”仍被当作重复；原句后补重要条件也会因字符串包含关系被压掉。当前 state 虽已更新，但历史快照的 innerThought/momentReflection 和下游 presence 时间线可能为空，造成心声历史看不到真实心理变化。该缺口能解释一类症状，不证明用户先前截图必定由它独自造成。
- **实改**：`1a948f1210a1b0b8d0bdf3d243a607321d65dcca` 将文字层判重限定为已清洗原文 trim 后完全相同；保留标点、否定词、时间词和新增条件。原因果指纹、新鲜依据、聊天/后台时序及近期完全重复控制继续沿既有管线；不同 wording 的意义由本人已有评估负责，不增加二次语义模型、收费调用或另一个心理 Store。
- **必要回归**：`CompanionPresenceThoughtHistoryTest` 经真实 presence Store 与时间线写入，测试变因明确的否定翻转、原句追加条件都保留在心声历史／本刻反思／presence 原始事件；变化后的相同原句再次出现仍只记一次心声。按原算法的独立字组计算复现，两组 changed 样本均返回 false；这只是根因静态复现，不替代待跑 Android 测试。
- **边界**：更换措辞的近似念头可能比以前多出历史记录，尚需真机观察质量；保留的因果指纹仍可能有其他漏判，需要真实回合证实后再改。没有凭代码声称能让模型生成更多或更好的心理活动。
- **CI 与接力**：本提交 [APK 41766161850](https://github.com/jiacimu-droid/lulu1/actions/runs/41766161850) 因并行居民代码后继推送 cancelled，不能报通过；索引和后继完整 JVM/APK 按最新包含本修复的 run 核对。已比较后继主干，居民相关修改未覆盖本文件或回归。下一步先查后继单测；通过后在获授权真机对照真实用户纠正、关系分歧、同词否定和补条件，核对模型原输出、因果指纹、当前态、历史及下轮行为。P-08b/e 仍待验。
- **后继 CI 核实**：包含本修复且未改动本文件/测试的 `6510fcc0e71c`，[索引 run 41766211096](https://github.com/jiacimu-droid/lulu1/actions/runs/41766211096) completed/success，已运行原生成器；[Android/JVM/APK 41766210954](https://github.com/jiacimu-droid/lulu1/actions/runs/41766210954) 仍 pending。已核对提交祖先关系；新回归未获完整单测结论，不能宣称通过。

## 2026-11-04 · P-08b 与 S-02 继续实改交接（main `9652f4dbfe`）

- **P-08b 事实缺口**：`CharacterContinuityRuntime.snapshot` 从前把三分钟内字面同于当前输入的全部用户发言都当回显丢弃，导致真实连续两次“好/嗯”的上一句遗忘。源码 `13d129410` 仅跳过最新一条已验真回显，跨群聊分页不反复过滤；测试 `f14133d` 覆盖相同短句与 175 条群聊旁人记录。前置 `811b428` APK/JVM `41763912049`、索引 `41763912115` success；本修复后继完整 JVM/APK 因并行提交尚未独立验收。
- **S-02 设置和移除**：新独立「居民管理」入口；当前居民列表、编辑资料、移除确认、每人日行程上限、人数上限及自动新增开关已接入正式设置入口。现有 `DigitalWorldExpansionStore` 支持标记移除模板及编辑后姓名，防止同一居民再被自动复生；`DigitalLifeProfileStore.retireResident`／`DigitalWorldStore.retireResidentPresence` 仅清理活跃资料/位置/住宅，**保留曾经共同见面的参与者与原始聊天**；正式人物目录不再保留其活跃角色。
- **人物档案**：固定模板有不同的生平、性格/边界、兴趣及明确性别；模型低频提案的新居民必须同时给出 gender、temperament、preferences、background，未完整则不注册。不增加独立模型请求或新的角色记忆 Store。原住民性别随原 Character persona 进入模型，旧档案仅非用户编辑者补缺，不根据性别决定言行。
- **回归与状态**：`NativeResidentLifeIntegrationTest` 的 `b3595845` 覆盖创建→性别与人设同步→确实存在的见面/时间线→安全移除→原记录和参与者仍保留、模板不能复活；第二项测试编辑后角色 persona 及时同步。索引对 `b3595845` 的 `41766304839` 已 completed/success；其 APK `41766305006` cancelled（不算通过）。代码继续由其他自动窗口并行提交，最新新 APK／索引必须按其对应 SHA 最终结论验，不将 pending/cancelled 当 success。
- **未验与继续**：真机入口、删除后的群聊/后台 Worker、重启稳定、真实生成的人物质量、心事/记忆因果及跨日仍待验。下次若 APK 失败先修真实首条报错，继续 P-08b/S-02；两项均不得标记完成。

## 2026-11-04 · S-02 居民人数、AI 费用、档案与删除入口整合（main `f1272dbd` / `2ed5e4af`）

- **用户真实反馈**：居民不断自动增加、好友不聊天、人数太多、找不到独立管理页面和删除入口、居民性格背景单薄及缺少性别，付费费用不可控。先确认正式 app 的既有 `ResidentDailyPolicy` 只限制新增日程行数，不是模型决断调用上限；旧滑块误藏在 API 设置。系统自动演化有每日模板出生与低频额外模型扩张提案。
- **实际代码**：`f1272dbd213e0e3f0159ae1baf8fc518eb07a1e6` 在已有 `ResidentDailyPolicy` 持久增加每位 `resident:` 居民本地日 0–8 次模型请求额度（默认 2），并在已有 `CompanionModelGateway.generate` 发请求前原子领取，额度耗尽不请求供应商，不需要新 Store/额外模型调用；同一次格式补正也计入。默认自动增员已设为关闭。首版独立 UI 因并行提交重复，`2ed5e4afb21e6d9c0081523feee1d988992c88f1` 合并后只留下设置首页一个「居民管理」入口；后继 main 复核已有 `LuluResidentManagementScreen.kt`，提供自动增员、人口上限、真实 AI 决断上限、每日新增行程及逐人编辑/删除。
- **生成与清理**：关闭自动增员后，`DigitalWorldEvolutionRuntime` 不再启动低频模型世界扩张提案，`DigitalWorldExpansionProposalRuntime` 在请求前及异步结果返回后再核实开关。并行主干已有模板/模型新居民姓名、gender、temperament、preferences、background 及相互不同的生活经历要求，档案可编辑，原 `ResidentCharacterBridge` 的 persona 继续同步，不按性别决定性格。删除保留原始私信/共同经历与其他人的记忆，撤销未完成日程、停用实体和角色，已移除模板不自动重新出生。
- **验证边界**：`ResidentDailyPolicyTest.populationSpawnAndActualResidentModelRequestsRespectOwnerLimits` 添加单独日、独立居民、零预算、人口开关与持久设置回归。包含当前后继的代码索引 `41767007418` completed/success；Android APK/JVM `41767007417` 查询时 pending，先前 `41766992738`（`2ed5e4af`）cancelled 不算通过；尚未安装真机，也没有实际模型用量/费用/自然社交盲评。
- **好友不聊天的边界**：已有 `CharacterFriendRequests.notice` 把接受/拒绝送进真实时间线并 `scheduleSocialNoticeReview`，`ResidentDailyDecision.runSocialWake` 接受 `phone:friend`，`CharacterEverydaySocialContext` 提供已确认好友和可联系对象；这不证明实际发了私信。需脱敏实际事件对照「确认好友 → 通知投递 → 同一居民模型选择 → 工具发送回执 → 对方处理 → 后续」。程序不得硬编码“加好友必聊天”；额度为 0 时允许角色暂时不发而非伪造社交。
- **下次接力**：先查最新 SHA 的 Android/JVM/APK 首个失败步骤并修复；随后真机验证独立入口、0/2/5/8 真正供应商请求预算、重启保留、低成本 NPC 生活、删除后后台 Worker 停止、旧聊天保留及新居民性别背景投射。S-01/S-02 仍未验收；P-08 不因为这轮居民代码而打勾。

## 2026-11-04 · S-02 居民管理与并行页面去重修复（唯一入口恢复，APK 待验）

- 用户新增明确要求：设置新增单独「居民管理」，允许减少居民数量、编辑/删除指定居民，新生成居民要有性别、个人性格长短处、具体兴趣和相对丰满的生平。只提交可复用工程实现，不上传用户个人资料。
- 现有权威：`DigitalWorldExpansionStore` 保存原住民ID/生平/所在世界；`ResidentCharacterBridge` 用原角色人物 Store/时间线/社交，没有建立影子角色中心。已与并行窗口合并：`331e8f9` 持久性别/退场模板禁止复生，`d6bf8cb` 要求模型低频提案的 gender/temperament/preferences/background 完整，`e581013` 为原生模板增差异化人设；`3e8eb82` 让性别进本人模型身份。本人用户编辑的人设不能被初始化覆盖。
- 实改：`705016c` / `87c81b7` 统一新增开关+人数上限的持久配置；`231645cc` 对权威注册落点做上限校验，`b648bebb` 使初始、店员、每日居民新增遵循上限，`d3e90b50` 限制模型提案并发新增；`b4c2f4d8` 删除居民后撤销仍未履行的居民日程，保留已执行回执和其他居民见闻；`bfff7fbe` 拦住持旧原住民快照的过期每日 Worker。测试 `51222179` / `855c11a5` 验证开关/人数上限同一配置和正式注册拒绝。
- **并行冲突与修复**：设置首页在 `1ec89844` 增加入口。并行修改先后产生两份同名 `LuluResidentManagementScreen`；`2ed5e4af` 删除独立页面，`fbea180d` 删除旧 API 页面同名实现后，两边同时不存在。实查 `main` 的设置首页仍调用该页面但文件 404；已提交 `190be18fbf14a4daea8c4a977bdd8caf5afe1d20` 在现有 `main` 恢复唯一独立页面，旧 `LuluSettingsScreen.kt` 不包含重复实现。恢复版本有单独开关、居民列表/编辑/删人确认、人数/日排程及每人每日 AI 决断预算。**这一缺陷是并行竞态引入，不能误记为已验收。**
- **真实 CI**：最后确认完整基线 Android/JVM/APK `811b428` 的 run `41763912049` success、索引 `41763912115` success，只证明旧代码；恢复页面后的 `190be18fbf` 索引 run `41767222838` completed/success，Android/JVM/APK run `41767222877` 检查时 pending；旧 `41767007417` cancelled 不算通过。未真机验「新增→编辑→删除→重启」/历史保留与已删居民后台 Worker/群聊，未实际测日模型调用额度和新居民人设多样性。S-02 仍在唯一看板，P-08 不因本轮勾选。
- **下一轮**：先确认 run `41767222877` 最终 Kotlin 编译/JVM/APK；有失败取首个实质报错，修根因。真机查设置入口、删除后重启不复生/不冒充发送、旧聊天和见面仍能在历史访问、每人 0/2/5/8 AI 决断上限与 0/2/5/8 行程分别起效。并行窗口先看当前已恢复的唯一 Composable，不得重复创建或再次同时删除两份。

## 2026-11-04 · P-08b 心声输出前的同义猜测与关键词否决（`fb91c5d`／`bd9babb`，待 CI）

- **交接核验**：前轮 `1a948f1` 解决的是 presence 历史保存阶段文字相似度丢心声；本轮先读最新 AGENTS、看板、人物契约/成长框架与进度。其含代码的后继 `190be18` [APK 41767222877](https://github.com/jiacimu-droid/lulu1/actions/runs/41767222877) 查询时 in_progress，索引 41767222838 success，尚不能将新心声回归报通过。最后确认完整 Android 基线仍为 `811b428`。
- **独立根因**：生成结果先经过 `CharacterHeartVoicePolicy.keepOrBlank`，再进私人状态/历史。它的 `sameMeaning` 对内心与外发台词用去前缀/标点、包含关系和 0.60 字组重合判同义，会把“想继续聊”与“不想继续聊”、带新条件的内心当作已说出口而丢弃；无 structuredDelta 的兼容入口即使有 focus/change/unsaidWhy 和新依据，还会被“先不催、给空间”等词表否决。这里是程序替本人判断念头意义，和上轮持久化门禁不同。
- **实改**：`fb91c5d8af6f0b690679ee495225a56a2624ec1e` 移除词表语义否决，把 outward 去重限定为原文格式归一后的相同内容，并在做展示片段前对完整本人原文比较。因果状态/新鲜依据检查继续走原协议，无新人格中心、模型轮次或统一主动/克制脚本。当前 140/320/600 展示截取策略尚未更动，长原文保真仍需单列核查，不宣称本轮解决任意长心声。
- **共享调用与回归**：首版将方法改名时漏查了想法卡片/历史 UI 的共享调用；`bd9babbf8a3e5e5fa4870546115b31b6e00ace85` 恢复原入口名并共用精确比较。新增 `CharacterExpressionGuideTest` 两项覆盖有运行时因果 delta 的否定/附加条件、明确因果依据的等待选择和无依据仍为空；`CharacterMomentSnapshotIdeasTest` 新增真实想法投影的相反意愿共存与 outward 为肯定时私人否定仍可见。保留已有相同正文去重、空依据和重复 delta 拒绝测试。
- **真实验收边界**：`fb91c5d` APK 41768483477 cancelled，索引 41768483506 success，不能代表补齐版通过。含完整回归的 [Android/JVM/APK 41768606587](https://github.com/jiacimu-droid/lulu1/actions/runs/41768606587) 查询时 pending，[原索引生成器 41768606590](https://github.com/jiacimu-droid/lulu1/actions/runs/41768606590) completed/success。先查完整结果，若有失败修首条真实报错；再真机对照原模型输出→因果依据→过滤后心声→当前状态/历史→下轮行动。更换措辞的近似内容可能比以前多显示，需实测噪声；没有假模型自然度结论。P-08b/e 仍待验。

## 2026-11-04 · P-08b 江渡「现在温柔 + 长远照顾」实际人设注入、居民测试回归止损

- **先核对事实**：江渡 v25 的 `motives/perception/conflict/care/expression` 已按本人默认配置定义在 `CharacterProfileSchema.jiangDuV25Overrides`，`CharacterLifeStore.applyJiangDuPreset` 只迁移原默认字段、保留主人手动修改及空白；`CharacterRuntime.definition` 把唯一 `CharacterLifeStore.profileContext` 接入正式模型角色定义，私聊/电话等 Full-context 场景共用；没证据支持追加统一管教模板或硬编码监督行动。因此没有重写人设。
- **新增直接回归**：`dabe997276430c3903412515fe30f6820a97999d` 扩充 `BundledJiangDuImportTest.jiangDuV25DeepensResponsibilityButKeepsOwnerEditsAndOnlyOneNewScene`，从旧 v24 默认升级后，直接断言 `CharacterRuntime.definition(id).persona` 真正含有「对她长远有益」「温和而坚定地陪她」「已接受的照顾」；同一断言检验星禾没有获得江渡专属关怀；主人手动覆盖 care 后正式模型配置能反映作者原文，旧模板语句不重新出现。仅增加测试，不增加收费模型调用、额外 Store 或决定脚本。
- **旧 APK 构建真实失败**：`41767222877`（`190be18f`） completed/failure，`2361 tests completed, 61 failed`；`41768606587`（`bd9babb`） completed/failure，`2364 tests completed, 61 failed`；日志集中于 `NativeResidentLifeIntegrationTest`、`ResidentDailyContinuityTest`、`WorldInteractionRegressionTest` 与旧居民预算断言，未到 APK 打包阶段。相关正式逻辑将自动新增居民默认关为 false，旧测试直接调用 `registerResident` 却未明确许可，与产品当前要求冲突。**不能把 failed 或 cancelled 当通过，也不能仅凭日志同类数量声称全部已解。**
- **测试准备修复（保留生产约束）**：`8780a875299523669cdef4b0d50c9a73203c6431` 修正默认关自动出生的预算测试；`bacf4f6b74054ef073eca60783a5e00d5b05c1b5` 给 `ResidentDailyContinuityTest` 建立明确 opt-in、人口/决断上限 fixture，结束恢复原 owner preferences；`5dfc77bdd589ac97a7d51971f35cc9ecf62cf7d0` 同样处理 `NativeResidentLifeIntegrationTest`；`6793b33c6abe5af1fbe44f9ea3c43e6c5bfd5f66` 修复 `WorldInteractionRegressionTest` 创建前提并恢复偏好。未在正式 `DigitalWorldExpansionStore` 放宽禁增、增加居民数量或修改永久数据。
- **CI 与下次接力**：带以上测试改动及新江渡人设装配回归的 `dabe997` 对应 Android/JVM/APK run `41769528871` 与 Refresh code index run `41769528863` 在最后查看时均未通过（in_progress），最新后继源码请另查 main SHA、Actions 及实际日志。先抓首条剩余失败和对应堆栈，不能假定 61 失败已全部修复；随后用授权的真实江渡对话核实「眼前安慰/长期利益」「保留对方意愿/认真兑现共同约定」两类行为是否由他本人选择并在电话/私聊/后台行动实际完成。P-08b/e 与 S-02 仍待 CI/真机/真实模型，不勾选。

## 2026-11-04 13:00 JST · CI 10 失败继续收敛

- **真实 CI**：上一轮 `dabe997` 的 `41769528871` 已 completed/failure，2364 tests / 10 failed（从 61 项下降至 10 项），`41769528863` 索引 completed/success。不是 APK 验收通过。具体失败：`BundledJiangDuImportTest` 1、`ScheduledSocialEncounterVenueTest` 4、`ResidentDailyContinuityTest` 5。
- **本轮源码**：`1915a57c6ce470b0056df2c762ece6ddea316c9a` 将江渡实际 persona 回归断言从不存在的「已接受的照顾」校准到正式 v25 原文「已承担的照顾」，不改人物设定；`c4dfdcd00a2f7c35b27ebe62e02d37c9c8ff7e8a` 在社交赴约测试 setup 明确允许创建 fixture 居民、结束恢复自动出生和人口上限，正式默认关闭不变。
- **剩余 5 项**：`ResidentDailyContinuityTest` 的 `manualDueRecoveryCorrectsSavedMalformedAnswerWithoutNewInitialDecision`（2167）、`interruptedDailyRepairUsesSavedOriginalWithoutRebuyOrPrematurePost`（2217）、`changedResidentPlanCanResumeOnlyIndependentStepsOnce`（2354，预期第三次请求实际仅两次）、`definiteDailyRepairRejectionCannotRebuyFirstAnswerAfterModelChange`（2527）、`definiteDueRepairRejectionCannotRebuyFirstAnswerAfterModelChange`（2587）。正式 `ResidentDailyPolicy` 默认每居民每日 2 次请求，测试 fixture 虽显式设 8，仍有这些断言失败；需查实际请求扣费、保存的补正/重试许可和日期状态，**不能为测试变绿直接取消额度门禁或凭猜测修改断言**。
- **后续**：核对本轮新提交 Actions、逐条定位上述 5 项真正的配额与重试语义，补根因回归，再测 P-08b 人设、P-08e 真人语言和社交。看板仍未勾选。

## 2026-11-04 · S-02 剩余 5 项居民重试回归：先分离预算和持久状态（main `716c86ed`）

- **复核结果**：`c4dfdcd` 对应 [Android/JVM/APK 41770635902](https://github.com/jiacimu-droid/lulu1/actions/runs/41770635902) completed/failure，2364 tests completed / 5 failed；[索引 41770635975](https://github.com/jiacimu-droid/lulu1/actions/runs/41770635975) completed/success。原江渡与赴约回归已不在失败清单。5 项全部位于 `ResidentDailyContinuityTest`：`manualDueRecoveryCorrectsSavedMalformedAnswerWithoutNewInitialDecision`、`definiteDueRepairRejectionCannotRebuyFirstAnswerAfterModelChange`、`changedResidentPlanCanResumeOnlyIndependentStepsOnce`、`definiteDailyRepairRejectionCannotRebuyFirstAnswerAfterModelChange`、`interruptedDailyRepairUsesSavedOriginalWithoutRebuyOrPrematurePost`。前四项在尝试第 3 次模型交互时失败/缺请求，最后一项在人工补正返回 false；旧日志没有提供完整预算/失败阶段原因，不能直接断言唯一根因。
- **本轮实际提交**：`716c86ed73bd4fe32a926ea5fa26611d33c52317` 在上述 5 项测试第 3 次请求前断言 `ResidentDailyPolicy.maxModelDecisions(app) == 8`，及该居民已有 2 次实际计费调用（第三项日程循环按索引校验已用额度），使后继 CI 可明确分辨「fixture 设置未生效/角色配额污染」与「人工恢复前置条件、响应校验或执行回执失败」。没有调整 `ResidentDailyPolicy` 正式限额、模型路由、NPC 出生默认开关，也没有通过删除失败测试换绿。
- **下一步**：检查最新 main 和该提交后继 Actions，读取具体新增断言失败行；若预算确实为 8 且已用 2 次，则追 `ResidentDailyDecision.retryDayDecision/retryDueDecision` → `runOnceLocked/runDuePlanLocked` → `CompanionModelGateway.generate` 的请求前置/持久 claim/补正结果，按真实根因改生产代码并补回归。P-08b/c/d/e 与 S-02 均未完成真机/模型验收，不能勾选。

## 2026-11-04 · P-08b/d 同一原因不等于同一心声；居民 CI 应用上下文隔离

- **接手与真实 CI**：基准 main `c6161c1`。重新读取 AGENTS、唯一看板、原索引、统一人物契约、成长框架和最近进度。历史 `c8c37d3` 的 41333088808 实际 failure；后继完整基线 `811b428` 的 41763912049 success。前轮 `bd9babb` 的 41768606587 failure（61 项）不能记通过；后续 `716c86ed` 的 [41776228860](https://github.com/jiacimu-droid/lulu1/actions/runs/41776228860) 为 failure（2364 tests / 5 failed），均在 ResidentDailyContinuityTest，APK 未打包。
- **CI 已证根因与隔离修改**：新增诊断不是“已用完 8 次”，而是在 MockWebServer 已收到 1/2 次调用后，`ResidentDailyPolicy.modelDecisionsUsed(app, actor, now)` 仍为 0。Robolectric 每用例替换 Application，Kotlin `LuluAiServices` 单例却仍持有先前 `ModelConnectionStore.appContext`；owner 设置写新 app，网关领取读旧 app，默认 2 次门禁使第 3 次人工补正/复议被挡。`8b4091ec5f6846642775e3635c7bed4a2da043c6` 复用已有 `MemoryTestEnvironment.release` 在 setup/teardown 释放网关，下一次正式 initialize 绑定当前 app。保留 8 次测试前提、实际计数断言、原生产默认/预算和全部恢复断言；这是测试生命周期问题，不宣称真机也存在多个 Application。该提交 [Android/JVM/APK 41779928520](https://github.com/jiacimu-droid/lulu1/actions/runs/41779928520) 查询时 in_progress，索引 41779928585 success。
- **本轮人物公共根因**：`PrivateStateDeltaEngine.evaluate` 以 responseAim/uncertainty 等判断 appraisalChanged，但 cause fingerprint 只投影部分字段。相同 focus/meaning 下本人改变回应目的、写出相反意愿，仍可有 meaningful/privateResidue=true 和相同 fingerprint；`CharacterHeartVoicePolicy` 接受后，`recordInnerVoice` 又按近 30 分钟相同 causeFingerprint 直接退回，`CompanionPresenceStore.update` 也要求指纹变化才留历史。于是当前状态、因果 transition、innerVoices 与 presence 历史不一致。心理 Store 的旧 sameInnerVoiceMeaning 还用包含关系丢掉新条件。
- **结构修复 `fedf758899fab2f6d67cca7c946ed482bf242bc5`**：原因指纹保留为原有来源元数据，不再作为“这句话已经想过”的唯一身份；心理 Store 仍以原 evidenceId 防重放，并仅对相同原句做既有短时去重，复用共享精确格式比较。presence 以不同本人原文留记录，保留原 15 分钟相同原句去重及后台历史节流。上游已有因果校验/权限/0/1/N 决策继续原样。没有通过补更多指纹字段维持语义猜测，也没有第二人格、常规新调用或硬编码主动风格。
- **本轮链路地图与回归**：本人实际 appraisal/basis → delta meaningful/privateResidue → keepOrBlank → recordCausalTransition/recordInnerVoice（同一权威心理 Store）→ presence 历史与原时间线 → compact/full 后续上下文。新增测试使用真实 delta engine 产生“不同 responseAim、相同原因指纹”，验证相反意愿经过上述存储/投影仍保留、来源重放和相同原句不增条、撤回一条不删除另一条、他人上下文不可见；旧两处测试由“程序认定近义”改成明确原句去重/不同原文保留，新增包含条件回归。测试证明契约与数据流，绝不是模型自然度评分。
- **本轮验证状态**：含全部源码与回归的 [Android/JVM/APK 41780234127](https://github.com/jiacimu-droid/lulu1/actions/runs/41780234127) 查询时 pending；[原索引 41780233960](https://github.com/jiacimu-droid/lulu1/actions/runs/41780233960) completed/success，CODE_INDEX 基准明确为 `fedf758899fab2f6d67cca7c946ed482bf242bc5`。提交前核对并行 main，期间只有索引提交，没有覆盖他窗源码。
- **未验与下一轮**：先等上述完整 CI，失败读首个真实堆栈；再对照授权真实回合的原输出/过滤/心理 Store/此刻历史/下一轮动作。相同原因下改写的近似心声会比以前多保留，需真机测噪声；原本没持久化的旧心声不凭空补造。原有长心声展示截取和后台历史节流未由本轮解决。P-08b/d/e 仍待真机、实际模型、多日，不可打勾；S-02 只在剩余 CI 真通过后移除测试排障，继续真机预算与删除恢复验收。

## 2026-11-04 14:53 JST · P-08b 后台新心声不应被固定历史节流吞掉

- **最新基准与并行检查**：开始时 main `feb5bf64fd7cc7ba5bb903377668861d35507d39`；已读取 AGENTS、CODE_INDEX（基准 `fedf758`）、唯一看板、人物专项、统一契约、`CharacterInnerLifeStore` / `CompanionPresenceStore` 及现有回归。先前 `716c86ed` 的 APK `41776228860` completed/failure（5 项）；`8b4091e` 的 `41779928520` 仍 in_progress，索引 `41779928585` success；`fedf758` 的 `41780234127` 已 cancelled，索引 `41780233960` success。不能把取消/运行中视为通过，未重复改动旧 5 项预算隔离修复。
- **已证公共断点**：前轮 `fedf758` 修复了同原因不同原句被心理 Store / presence 原句去重吞掉；但 `CompanionPresenceStore.update` 还有独立的 `thoughtHistoryDue`：后台感知若 15 分钟内仅心理原句变化、外显状态/情绪不变，尽管上游已有因果验证、`thoughtChanged` 和 `uniqueHeartVoice` 均为真，仍不写历史或 presence 时间线。当前内心与历史/后续跨场景召回产生断层，尤其相反意愿会消失。该时间否决是旧显示节流，不是本人决定，也不是设备/API 资源硬约束。
- **源码修复 `3871031903f6363aecf9a58f6b8461fd48ce3453`**：移除这层针对不同原句的固定 15 分钟历史否决；保留旧的 15 分钟完全相同原句去重、角色归属、既有源事件验证和历史数量上限，后台无新心声时不额外写入；不改变模型选择、增加 API 调用或额外 Store。
- **回归 `9116325107d88e5c838de6c400fe8aabb27abfe6`**：在 `CompanionPresenceThoughtHistoryTest` 中同一后台场景 20 秒内本人从「想继续」转为「不想继续，先处理约定」，验证两个历史心声、后一条 timeline 以及原句重复不再新增。验证范围为源码/测试契约，不是已通过的 Android/JVM 或真实模型质量。
- **后续 CI**：含生产修复的 `38710319` APK run `41782070882` 最后查询为 pending，索引 `41782070720` in_progress；新增回归 `91163251` 与本进度后继运行需重新查 main / Actions。优先看后继完整 JVM/APK 首个失败堆栈，继续核查 5 项居民请求重试；人物 P-08b/d/e 仍待真机与实际模型（需观察新心声数量是否有噪声）。未完成项不勾选。

## 2026-11-04 · P-08b 旧测试仍否决真实不同后台心声：完整 CI 止损

- **接手与真实结果**：核对最新 AGENTS、CODE_INDEX、唯一路线表、人物专项、统一契约与实际 `CompanionPresenceStore.update`、`UserMessageFavoritesTest`、`CompanionPresenceThoughtHistoryTest`。前轮 `9116325107d88e5c838de6c400fe8aabb27abfe6` 的 [APK/JVM run 41782081329](https://github.com/jiacimu-droid/lulu1/actions/runs/41782081329) 已完成且 **failure**：`2366 tests completed, 1 failed`，唯一失败 `UserMessageFavoritesTest.ambientPerceptionStillThrottlesUnrelatedThoughtOnlyEvents`；索引 run `41782081227` success。不能将索引通过视作 APK 成功。
- **根因**：前轮正式源码 `3871031903f6363aecf9a58f6b8461fd48ce3453` 已取消“后台 15 分钟内不同心声不写历史”的旧时间否决，保留的是**完全相同原句** 15 分钟去重。旧 `UserMessageFavoritesTest` 还断言后台 4 分钟内两个不同文本只存第一条，与已更改的来源/心声因果契约矛盾；`CompanionPresenceThoughtHistoryTest` 已有前轮同条件不同意愿应保留的反向断言。对照源码确认问题是旧测试期望过时，不是为了过绿需要放宽生产心声逻辑。
- **实改**：`0fb54a6d85fad3ff863fec10a2193356404d06b6` 将旧用例改为 `backgroundPerceptionRetainsChangedThoughtButCoalescesExactReplays`：断言两条真实不同后台原句皆留历史，且新一次感知完全重播同一句、即使 fingerprint 改变也不加新历史条。保留正式代码、既有原句去重、角色权限、模型决断和费用不变；没有新 Store、固定台词或常态额外模型调用。
- **验收边界**：此 SHA 的 [Android/JVM/APK run 41788133290](https://github.com/jiacimu-droid/lulu1/actions/runs/41788133290) 与 [原代码索引 run 41788133287](https://github.com/jiacimu-droid/lulu1/actions/runs/41788133287) 最后核查均 in_progress，未得出构建/单测成功结论。下一轮先查两个 run 与后继 main 的真实状态，若有失败取日志最先实质断言；通过后还需真机验证背景心声相反意愿入历史、不同近似表达是否引入噪声、下轮本人选择是否真正承接。P-08b/d/e 未完成真机、真实模型或跨日验收，均不勾选。

## 2026-11-04 · G-01 记忆翻牌角色不再偷看背面

- **先核对 P-08**：接手 main `e8af2f7`，读取 AGENTS、CODE_INDEX（索引 `0fb54a6d`）、唯一看板、人物专项、统一人物契约、`CompanionPresenceStore` 和旧测试。上轮 `0fb54a6d` 的索引 [41788133287](https://github.com/jiacimu-droid/lulu1/actions/runs/41788133287) completed/success；APK/JVM [41788133290](https://github.com/jiacimu-droid/lulu1/actions/runs/41788133290) 查询时 in_progress，**未宣称通过**。前一轮 [41782081329](https://github.com/jiacimu-droid/lulu1/actions/runs/41782081329) 为 2366 项、1 项旧断言失败，已由 `0fb54a6d` 更新测试，尚待 CI 证明。P-08 的真机/真实模型验收仍阻塞，未重写已验过的心理 Store。
- **G-01 已证源码根因**：`LuluGamesApp.MemoryMatchScreen` 与 `ImmersiveMemoryMatchScreen` 均在第二次翻牌时直接查 `game.cards[it] == game.cards[first]`，读到从未翻开的背面值；以 68% 概率使用作弊答案伪装正常记忆，且两套 UI 各自复制决策。游戏 Store 原先没有“哪些位置确实被看到过”的状态，无法约束可见知识。
- **正式修改**：`f31d50b7631e74edc63a6eff213ca9c6e8b9be33` 在 `MemoryMatchState` 保存本局已翻开的 `observed` 牌面（从实际开牌入口记录），新增纯选择函数 `chooseObservedMemoryCard`，只用已观察牌找配对，否则在未匹配/未打开牌中随机探索；`eab949a0dbba633b0230abce0504818165e457a6`、`9528d1669ab8886d8e9f7d936c8ba17d6075873a` 让普通/沉浸两入口复用相同策略。无模型调用、无统一人格、无新 Store、无费用；观察事实只属于当前对局，重开即清空。
- **回归**：`017a1a92ba2dd23c35f883346e6907211474bc46` 新增 `MemoryMatchOpponentFairnessTest`：已观察配对优先、隐藏牌位置互换不改变同种随机种子的选择、已匹配和已打开牌不可重复选择。尚未拿到 Android/JVM 完整通过结果，不能当作已通过测试；还需真机试玩核查观察记忆、交替轮次和胜负记录。
- **交接**：唯一看板 G-01 仍为待 CI/待真机；P-08b/d/e 与 S-02 未勾选。先核对本轮 G-01 最新完整 Actions、代码索引与可能的编译错误；若 APK 失败先修首个实质失败，之后再回到 P-08 真实回合/心理后续以及 S 社交。并行 main 以最新 SHA 为准，不重复开发。

## 2026-11-04 · 江渡 v26：Ni 主导的外倾表达、独立八维区域与单场景示例

- **用户本轮明确授权**：保留 INFJ 的 Ni 主导、Fe 辅助、Ti 第三、Se 第四，同时外在主动、有审美、帅气少年感、幽默与魅力；重新融合江渡原有温柔、独立判断、责任和亲密面貌。用户认可选画小说场景，要求示例只展开一个场景，控制 500–1000 字。类型用于本人创作，不作为全角色行为脚本。
- **接入缺口**：现行 `typing` 只在“兼容存档”，被 `groupedFields/profileContext` 排除；仅在那里填 INFJ 不会成为模型当前人格。旧本人对话示例还拼接多场景，UI 草稿入口也固定生成三段。
- **源码提交**：`291dc41b12530d35266e7409567a2c4a655b774c`。在同一 `CharacterProfileSchema` / `CharacterLifeStore.profile` 新增可编辑 `cognitiveStyle`，人格页“认知结构 → MBTI · 荣格八维”独立显示，通过原 `profileContext → CharacterRuntime.definition` 投影到正式角色上下文，不建立新 Store。江渡 v26 核心简介与十项稳定性格重新融合功能主次、外在气质、取舍与原兴趣；Fe 不抢占 Ni 主导，功能名称不要求每轮朗读，直觉须能被当事人原话与新证据修正。
- **八维与示例边界**：主轴明确 Ni/Fe/Ti/Se，其余 Ne/Fi/Te/Si 是补充观察维度，不并列轮流掌权或按压力切换人格。审美、主动与少年感写为本人特质，不从 I/E 或第四功能机械推导。本人 `dialogueExamples` 改为一个虚构数字世界选画场景，包含环境、举止、私人心理、对话与观点调整，经原 `CharacterSpeechIdentity` 完整交接；小说旁白不成为即时聊天输出要求，假设场景不进入真实时间线。
- **实数字数**：前轮会话中的场景正文 1093 字符（含标点、不含空白）/868 汉字；本轮压缩正文 794 字符/610 汉字，连同单场景标题与用途提示共 877 非空白字符，均在请求上限内。回归直接核对保存正文范围和完整交接，不按字数裁切实际聊天。
- **升级与 UI**：版本 25→26 只替换旧程序原文匹配字段，新增区域缺失时补齐；用户手写、故意留空、本人自定义核心简介/示例继续保留，关系、身份、记忆和当前目标不改。已发布三种旧默认示例仅作精确迁移键，不叠入新场景。江渡的“按本人设定生成一个场景”沿原用户点击一次生成、预览后保存，传入完整八维和人格材料，允许小说段落完整结束；其他人物无预设类型，不修改公共表达风格或常规模型轮次。
- **回归范围**：`BundledJiangDuImportTest` 覆盖完整旧默认升级、正式模型人格实际含四功能、原关怀责任保留、单场景 500–1000 与全文交接、新区域可见、三种旧示例迁移、手改/空白与其他人隔离；原 CharacterLifeStore / OnlineChatExperience 迁移测试同步到 v26，仍保留身份/原时间线、旧版本、重启及手改保护断言。
- **真实验证**：接手完整 Android 基线 `017a1a9` 的 [41788637553](https://github.com/jiacimu-droid/lulu1/actions/runs/41788637553) completed/success，索引 41788637587 success。本轮 [原索引 41791681703](https://github.com/jiacimu-droid/lulu1/actions/runs/41791681703) completed/success，CODE_INDEX 基准为 `291dc41b12530d35266e7409567a2c4a655b774c`；[Android/JVM/APK 41791681549](https://github.com/jiacimu-droid/lulu1/actions/runs/41791681549) completed/success；日志包含新 `jiangDuV26MigratesWholeStockProfileAndInjectsCognitiveHierarchyAndOneScene` 执行，`:app:testPhoneUnitTest` 与 `:app:assemblePhone` 均成功，`BUILD SUCCESSFUL in 10m 22s`。发布 [v7694](https://github.com/jiacimu-droid/lulu1/releases/tag/lulu-v7694)，APK 资产 `Lulu-arm64.apk` 与临时 artifact 13303604677 都明确绑定源码 `291dc41`。没有安装用户手机，也没有真实模型人格盲评；模型是否自然领会需新版 APK 与真实回合验收。提交前已核对 main 的六个目标文件无并行修改。
- **下一步**：本轮 CI 已通过；安装 v7694 后，在江渡的人格页核对新区域和完整小说，再以普通接话、审美分歧、被纠正、主动邀约、共同约定失败的实际对话检验“思考深、表达自然、会主动且能改判断”。P-08b/c/e 仍待真机/模型，不用假台词验收。

## 2026-11-04 · P-06/M2–M3 旧时间线重复 ID 的跨角色注解隔离

- **接手与真实基线**：先读最新 AGENTS、CODE_INDEX、唯一看板、人物进度、统一契约及 `NarrativeEvidenceChain` / `SharedTimelineEvent` / `NarrativeMemoryRecallIntegrationTest`。上次正式源码 `291dc41b12530d35266e7409567a2c4a655b774c` 的 [Android/JVM/APK 41791681549](https://github.com/jiacimu-droid/lulu1/actions/runs/41791681549) 与 [索引 41791681703](https://github.com/jiacimu-droid/lulu1/actions/runs/41791681703) 均 completed/success；后续 main 为文档提交，不重复开发已验通的江渡 v26。
- **已证源码断点**：`NarrativeEvidenceChain.annotations` 虽以 `characterId + id` 对混合批次去重，但输出类型是仅以 `id` 为键的 Map；旧存量若不同角色复用同一事件 ID，任务/会话链会先后写入同一键，最后一人的链标签可能覆盖第一人的，甚至在跨角色注解展示中误认事件归属。现有正常单角色调用不能证明混合旧批次安全。
- **实改**：`bb615e53eb0e6706309e71786548444180bbd40a` 在注解投影前发现跨角色复用 ID 时排除有歧义的键，而不是任意挑一位人物；其他事件依旧按本人任务/会话关联，不改权威时间线、不合并角色、不新增 Store/模型调用。回归 `2d33ffcec3c33c9d7b20ec070734445eca022fa1` 构造两人同 ID、不同 taskId 与各自后续事件，检查有歧义 ID 不产生误归属注解，同时 Episode expand 仍严格只取本人。
- **真实验证状态**：上述测试提交的 [APK/JVM 41795220233](https://github.com/jiacimu-droid/lulu1/actions/runs/41795220233) 与 [索引 41795220204](https://github.com/jiacimu-droid/lulu1/actions/runs/41795220204) 查询时均 pending；不能记通过或声称已生成 APK。下一轮先核对这两个 run 的最终状态与首个失败堆栈，再核查 P-06 时空联合检索/任务回执展开剩余接口；P-08b/c/d/e 仍需真实手机、真实模型与跨日对照，不能因为单测成功勾选。


## 2026-11-04：江渡 v27 与通用人格分类

- 用户授权更新所有角色/居民的通用性格填写模板：保留存储键，新加九型动机参考、大五稳定特质、依恋/修复三个可选字段；价值与核心驱动提示补充取舍、担忧。字段由同一 CharacterLifeStore 分组编辑与 profileContext 投影，居民仍沿 ResidentCharacterBridge 的同一 characterId，不创建第二人格或常态模型调用，不为其他人物强制填类型。
- 江渡 v27 保留 Ni/Fe/Ti/Se，以美与责任为价值内核；自信、略张扬的帅气、优雅与少年气，选择性热烈。补入成熟1号倾向、稳定特质和安全型依恋；成长、守护与实际承诺相连。原单一选画场景微调神态、审美主张和主动承担，仍为虚构示例而非记忆。
- 保留 v26 默认人格/字段/示例作为不可变迁移对照，仅旧默认与缺失字段升级；手改和主动留空保留。新增 v26→v27 实际迁移、正式注入、幂等与角色隔离回归，既有前代迁移测试随当前目标更新。
- 本轮代码静态审阅完成；JVM/Android/APK 与原索引工作流等待本次 main 提交触发，CI 未通过前不宣称验通。真机、真实模型与跨场景质量待验；P-08b/c/e 仍保留。
