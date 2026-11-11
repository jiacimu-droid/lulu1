# 人物一致性：整体因果链核查与修复

## 2026-11-11 P-08d.2b.2 实际模型请求回归（待 CI）

- `717cc0d` 在 `AutonomousActionBoundaryIntegrationTest` 增加 `failedWakeVoiceSurvivesReloadIntoActualNextModelHttpInput`，沿真实 `CommitmentCallFeedback.onVoiceFailed` → `CommitmentTaskStore` 冷重载 → 用户新气泡 → `ProactivePerceptionRuntime.runDueCycle` → MockWebServer 捕获真实请求体，检查失败任务 ID、播放失败事实、无虚构已醒及不重复 POST。该用例尚未取得 APK/JVM 运行结果；生产代码未修改。
- 本轮无法通过现有连接器取得 main push 的 Actions head SHA 结果，不能声称通过。下一轮先查 `717cc0dedaccc08dcf84c1464ffb1f4091113272` 的 JVM 首错和索引，再接本人下一决定的实际执行回执；真机声音、权限、真实模型仍外部待验。


## 2026-11-11 Issue #868 与夜间改动回查

- `52f0624` 的 [APK/JVM #42924717735](https://github.com/jiacimu-droid/lulu1/actions/runs/42924717735) 在 `RelationshipPreferenceGatewayTest.kt:222` 失败，期望新约定而重载后为空。用例在配置关系前遗漏 `CharacterLifeStore.initialize(context)`，沿用了之前测试的存储实例。`bfd7158` 补齐正式启动顺序，并直接检查编辑前后真实 SharedPreferences 中的完整原文。
- 同路径发现生产接口在存储未初始化时仍返回 true。`500cc83` 改为明确拒绝本次写入，并通过 `relationshipEditRequiresAnInitializedDurableSource` 检查未写入时旧数据保留、初始化后重试与重载。既有 `commit` 保存失败继续暴露实际错误，完整标题和约定保持保存。
- 编辑入口另有配套缺口，关系编辑框仍按 70/1600 字截断，编辑已保存长约定时会丢失末尾。`f85ddb1` 移除输入截断；保存失败或存储未就绪时保留草稿和弹窗并提示重试，成功后才关闭。
- 回查范围从已验 `c666055` 至今早美术交接 `c67dc44`，另复核后继美术修正 `3a79dfc`。该范围的关系正文保留、已发现路线绘制、宝物渲染与地表贴图选择没有新增 AI 请求、重写物理世界或删改历史。已核新的宝物绘制函数接入实际开箱和藏品共用入口。未发现需要回滚的其他生产逻辑，性能和真机画面尚无测量证据。
- `500cc83` 的 [APK/JVM #42931549522](https://github.com/jiacimu-droid/lulu1/actions/runs/42931549522) 及包含编辑入口的 `f85ddb1` [完整 APK/JVM #42932289426](https://github.com/jiacimu-droid/lulu1/actions/runs/42932289426) 均成功。后者 2691 项通过、1 项跳过，长约定重载、失败电话后的新设定注入、初始化拒写用例均通过；原索引 #42932289408 成功。已发布 [Lulu v8596](https://github.com/jiacimu-droid/lulu1/releases/tag/lulu-v8596)，#868 已由成功构建自动关闭。真机编辑、实际模型自然度及帧率独立待验。

## 2026-11-11 S-02.4 十项联动回归与 UI-02.2 写入失败

- [7b262da](https://github.com/jiacimu-droid/lulu1/commit/7b262da4b05f1ce60ab803c8831d5285f56f26f2) 将居民本人提出的兴趣关联到真实来源和修订版本，后续计划、到点活动和社交可继续使用。`ResidentDailyContinuityTest.awakeResidentsCanReadAndReplyAfterZeroAgendaSlots` 已在 #42860437140、#42861721064 通过，覆盖零行程、真实来信、兴趣续接、本人选择的私信和朋友圈以及撤回来源。
- [2c8ca29](https://github.com/jiacimu-droid/lulu1/commit/2c8ca2991633b6a74d63b7fdc020bbd08ee3aaab) 修复短时间问句召回和咖啡角可接待状态；更正原话及两项时间条件在 #42861721064 的实际网关请求和注入追踪测试中通过。置顶先持久化再更新列表，[5f81f73](https://github.com/jiacimu-droid/lulu1/commit/5f81f73db0d0887fcb2248b4b3e3b430352b86af) 将实际界面写入移至后台并提示失败。
- #42861721064 共 2682 项测试，2 项失败，1 项跳过。失败是拒写模拟的流式接口返回了原 Editor、咖啡角用例沿用了前一个 Robolectric 应用实例。[a145528](https://github.com/jiacimu-droid/lulu1/commit/a145528570f42d7bb05bbb99f03af883bace6fd4) 修正用例，保留实际业务断言。[#42864191655](https://github.com/jiacimu-droid/lulu1/actions/runs/42864191655) 已完成且成功，咖啡角、置顶拒写、零行程社交和更正原话用例均通过，合计 2681 项通过、1 项跳过，已发布 [Lulu v8577](https://github.com/jiacimu-droid/lulu1/releases/tag/lulu-v8577)。真机操作、进程恢复和真实模型理解独立待验。

## 2026-11-10 UI-02.2 权威聊天身份字段严格保护

- 测试 [a191bd3](https://github.com/jiacimu-droid/lulu1/commit/a191bd372cbd9edd631fd7309ec04397a248a5b6)：旧 state_v1 消息缺 ID 时，正式 ChatStore 元数据修改和重启不能静默把随机新 ID 写回原始快照。
- 正式源码 [449fd8d](https://github.com/jiacimu-droid/lulu1/commit/449fd8d583eadac319cf5b1b5e762ac69222fac5)：权威 decode 要求原始会话/消息 ID、正确所属会话、有效 sender/status，避免宽容字段默认值将来源身份/送达状态伪造为新事实。无格式迁移，未修改其他宽容导入。
- CI：当前连接器的 commit workflow 查询仅返回 PR runs，对 main push 返回空列表；本轮未获取 `449fd8d` APK/JVM 或 Refresh code index 的 head SHA 成功证据，不标通过。下一轮直接打开 `ChatHistoryLegacyCompatibilityTest.kt` 和 `MigratedDomainStores.kt` 的 decode，先核 Actions 首错，再做时间/结构字段和写入失败回退。真机、100k、强杀仍待验。

## 2026-11-10 UI-02.2 局部损坏旧聊天快照保护

- 前轮完整损坏快照修复 `6e5bd7d` 的 [APK/JVM #42809824951](https://github.com/jiacimu-droid/lulu1/actions/runs/42809824951) 与 [索引 #42809825080](https://github.com/jiacimu-droid/lulu1/actions/runs/42809825080) completed/success，build job `129808829371` success。
- 新发现 `decodeObjects` 宽容跳过非 JSONObject/解析异常，旧 `state_v1` 局部损坏仍会在后续全量重写时无声丢记录。新增 [61edfb8](https://github.com/jiacimu-droid/lulu1/commit/61edfb8932b43b9f60ffbd0b3d70f3c8fa49a362) 的真实 Robolectric 局部损坏、元数据写入、重启保护测试；[0816307](https://github.com/jiacimu-droid/lulu1/commit/0816307cba77e8aac08546ff93cf7019377704be) 在权威 ChatStore 解码改为严格数组成员解析，解析失败沿既有不可覆盖保护，不改变其他宽容导入调用或历史格式。
- 新代码的 JVM/APK/索引结果仍待对应 head SHA 查证；没有声称新测试已通过、真机恢复已验或增量迁移已实现。下一固定 UI-02.2：先查新回归 CI，再覆盖字段级损坏与写入失败，后续才实施事务增量存储。



完整跨窗口体验路线与优先级见 [LULU_EXPERIENCE_ROADMAP.md](LULU_EXPERIENCE_ROADMAP.md)：人物活人感第一，角色社交第二，再推进游戏与视频。本文件记录人物专项的真实证据与待验状态。


## 2026-11-09 S-02.1 物理回退 POST 的居民额度归属

- **确证根因**：网关首个模型 POST 已领取居民每日额度；流式协议拒绝后的第二次非流式 POST 在 `f432110` 仅补了请求尝试统计，未再领取额度；同一网关的 503 负载保护回退也存在相同漏领。居民每日硬上限可能被单次 `generate` 的多次物理 POST 绕过。
- **正式修复**：[`23aa1c9`](https://github.com/jiacimu-droid/lulu1/commit/23aa1c9104b84957db22d5f740b358996f161a42) 为流式回退引入仅在真正再次发送前触发的额度领取回调，503 第二次 POST 同样领取居民额度；不足时拒绝第二次发送，不替角色增加总预算，不修改人物内容或既有第一请求。
- **测试**：[`8244036`](https://github.com/jiacimu-droid/lulu1/commit/8244036ea58d2750a3b4a5d2b6ee24522551ebaf) 的 `ResidentDailyPolicyTest.oneResidentCannotSpendAnUnclaimedFallbackRequest` 检验额度 1 的第一次成功领取与第二次拒绝。此测试未走真实网关网络链，不能声称已证明 MockWebServer 实际 POST 次数。
- **验收**：源码已提交，未取得新 head SHA 的 APK/JVM、Refresh code index 或真机证据。GitHub REST Actions 在本轮不可访问，GitHub 连接的 commit runs 仅查 PR，不能凭空列表推断成功。下轮 S-02.1 先核 CI，补真实网关 MockWebServer 流式协议拒绝后额度 1/2 的 POST 数、无额拒绝、回执归属；进一步核 BackgroundModelRequestBudget 与居民额度协调。请求尝试、成功生成、实际服务商计费独立，400 次旧账仍无法准确分摊。

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
- 代码提交 `826730f7833d3a19a8d1012081c5c8c19043d93e`；原索引 [41797232128](https://github.com/jiacimu-droid/lulu1/actions/runs/41797232128) completed/success，自动生成索引已发布。JVM/Android/APK [41797232153](https://github.com/jiacimu-droid/lulu1/actions/runs/41797232153) 当前 pending，前轮 `41795220233` 仍在运行；没有把排队视作通过。真机、真实模型与跨场景质量待验；P-08b/c/e 仍保留。


## 2026-11-04：江渡 v28 亲密互动偏好

- 本人 care 字段新增用户明确授权的 switch、Dom/sub、小狐狸/小狗两面：主动调情与自愿交出主导可由同一人物自然选择，不按轮切换，也不变为通用角色规则。责任与原关系仍承接；无新字段、Store 或常态模型调用。
- 九型与大五保留可选，具体动机/稳定特质是可读先验，类型不当作固定行为脚本；尚无真实模型对照证明标签本身提升质量。
- v27 字段保持不可变对照，仅旧默认 care 或缺失项更新；手改/空白保留。新增 v27→v28 默认、正式注入、关系不变和他人隔离回归；前代迁移测试更新当前目标 v28。
- 本次 CI/索引随源码提交触发，真机与真实模型待验；没有以示例台词宣称体验通过。


## 2026-11-04：江渡 v29 精简人设与亲密单场景

- 用户已批准精简候选：人设全部第三人称“用户”，14个本人字段共1483字符（含标点、英文，不计空白）；保留Ni/Fe/Ti/Se与补充四维、美/责任、守护驱动与担忧、九型/大五、依恋、switch、审美气质、表达及兴趣。身份与关系原记录保留，核心简介仅作简短入口；没有对其他角色写入江渡性格。
- 选画旧稿保留为不可变v28迁移对照，新单场景“靠近”正文706字符；成年恋人数字世界相处，以连续对话、心理、环境表现主动调情和自愿交出主导；非真实经历、无固定轮流行为与新收费调用。对话中的“你”属于人物台词，人设字段全部用“用户”。
- 默认字段/核心/示例迁移到v29；手改、留空、已有关系与其他人物隔离回归保留，新增v28→v29正式注入及定制保护回归。
- 追查前轮CI：v27 APK/JVM41797232153与v28 41797850151均failure；v28日志126467248113显示4条失败：模板新增字段未纳入江渡默认字段完整性契约、3条旧迁移测试仍断言已替换的清俊/温柔旧文案。本轮补齐默认字段，更新为当前获批文案的断言，保留原迁移、手改、隔离断言。索引前两轮均success；本轮单测/APK/索引随提交触发，真机与真实模型仍待验。

## 2026-11-04 · P-05/M1 单条长事件的多个原文条件曾只注入第一个片段

- **接手证据**：读取最新 AGENTS、CODE_INDEX、唯一大纲、人物专项、统一因果契约及 `UnifiedMemoryOrchestrator → MemoryEvidenceSelection.render → OriginalEvidenceSpans.ranked`。v27 Android/JVM/APK run [41797232153](https://github.com/jiacimu-droid/lulu1/actions/runs/41797232153) completed/failure（2371 项中 4 项失败）；并行 main 的 v29 `911c73c` 已修正相关默认字段和旧断言，本轮不重复修改该处。
- **已证根因**：`OriginalEvidenceSpans.ranked` 已能按当前检索焦点返回长事件的多个精确原文区间，包含无匹配时的首/中/尾候选；但 `MemoryEvidenceSelection.render` 直接 `firstOrNull()`，即使证据预算充足，仍只向同一个人物交付首个区间。同一长事件中相隔较远的许可、否定或取消条件会在渲染层丢失，M1 的候选能力不等于实际注入能力。
- **实际代码**：`2fc2b594294a15b43b5599c0e8202c7f343b48ef` 修改现有证据渲染：按原有候选优先级使用剩余预算接纳多段，最终按原文位置输出，各段保留 `eventId@revision`、字符范围与「其余未注入」标记；超出预算仍跳过而不是虚构连续性。只读取同一权威事件，不新增 Store、常态模型调用或选择模型语义的规则。
- **回归**：`6c8a934ef422f0aec741750cae6b9320365b203a` 新增无词面焦点长事件首/中/尾三处约定与撤销条件测试，覆盖充足预算多段、狭窄预算仅首段且明确截断、revision 8 修改不复活旧尾段。此处仅证明代码提交，尚无本提交完整测试通过结果。
- **CI 分层**：[原代码索引 41802053558](https://github.com/jiacimu-droid/lulu1/actions/runs/41802053558) completed/success；[Android/JVM/APK 41802053512](https://github.com/jiacimu-droid/lulu1/actions/runs/41802053512) 最后查询 pending，**没有通过验收**。`2fc2b59` 的旧 [41802008330](https://github.com/jiacimu-droid/lulu1/actions/runs/41802008330) cancelled，不作为成功。真机、真实模型、跨日仍待证实。
- **下一轮**：先查 `41802053512` 的最终单测与 APK 产物，如失败取首条真实失败；其后继续 P-05/M1 的同事件远距约束是否进入最终模型输入，并复核 P-06 时空人物联合检索、经历展开的来源和任务回执，不把片段选中等同于模型已使用或目标已完成。

## 2026-11-04：v29 迁移测试失败收敛（接力）

- **核验**：先读最新 AGENTS、CODE_INDEX、唯一大纲、人物专项、UNIFIED_CHARACTER_CONTRACT、成长框架及江渡迁移测试/正式字段；`911c73cef75fd1305d3d2346b137ee1f614df098` 的 [Android/JVM/APK 41801645241](https://github.com/jiacimu-droid/lulu1/actions/runs/41801645241) 已 completed/failure，日志 job `126478599595` 为 **2373 tests completed, 6 failed**。不是 Android 编译或 VPN 故障，失败均为断言 `AssertionError`。
- **六条明确失败**：`BundledJiangDuImportTest.kt:49` 仍要求旧“选画”场景；`CharacterLifeStoreTest.kt:561/610/655/707/903` 仍检查过时的 `AI前沿` 字面是否出现在合成 prompt。当前 v29 人设、字段、示例已更新，旧断言并不能可靠证明迁移结果。修改测试而非恢复旧人格台词。
- **实际修改**：`abf6d7a3be6eed5a22fe95756500d4621e479604` 将五条旧提示词字面断言改为核对 `CharacterLifeStore.state` 中的 `jiangDuV29Overrides["interests"]` 完整值；原测试中角色隔离、主人手改/留空、原意图和迁移版本断言保持。 `f47d5290821c336e42f1ec408d5a4b85291010b7` 将旧场景标题检查换成当前正式“靠近”，其他人物示例隔离断言保留。**没有修改正式人格、语义决策、Store 或添加调用**。
- **验证边界**：[新 APK/JVM 41802904855](https://github.com/jiacimu-droid/lulu1/actions/runs/41802904855) 与 [索引 41802904784](https://github.com/jiacimu-droid/lulu1/actions/runs/41802904784) 提交后已排队，未取得 success；旧 `abf6d7a` 单测 run `41802893951` cancelled，不计成功。上轮长事件多片段 [41802053512](https://github.com/jiacimu-droid/lulu1/actions/runs/41802053512) 查询时 in_progress。下一轮先查这两个实际结果，若失败取最新日志的精确首错，再继续 P-08 人物因果真实证据；真机/真实模型仍待验。


## 2026-11-04：日常相遇小剧场身份交接、本人整理与后续执行

- 用户纠正：本轮“小剧场”是数字世界居民/角色日常相遇及其文学生活片段，不是阅读里的单篇番外。本轮没有提交MiniTheater阅读修改。日常线路沿用系统导演→同场人物各自决策→真实交流/工具回执→合场文学与各自后续；不新建随机故事或常态生成请求。
- 已证根因1：ProactivePerceptionRuntime在afterCausalRecord内无条件消耗当天自我回顾，即便模型没有确认回顾、工具还没执行。改为既有appraisal.lifeReview明确确认及理由、执行器整轮success后才消耗；不回顾或失败保留下一次普通感知机会，不增付费唤醒。
- 已证根因2：场景提供plan_personal_activity，但居民CapabilityRegistry拒绝该工具。开放原工具，原RoleDayScheduleStore追加居民单次/能力/每日新增日程额度检查，原native due worker与请求预算不变；相同已保存选择仍幂等，拒绝保留真实失败而不装作主动放弃。
- 已证根因3：CharacterCuriosityRuntime把成功的计划工具tool回执也计为探索成果。新增独立supportsExploration检查：计划/改期/取消/元数据编辑仍是实际执行，却不能当取得资料。原curiosity问题保留，真实搜索/阅读回执才继续。澄清satisfied可由本人据真实结果判断已弄清，无须先向用户分享；不将日程成功冒充研究完成。
- 已证根因4：相遇者模型对同场人只拿姓名；合场文学只拿persona.take(350)，丢失居民明确gender/identity与后部设定。新增无状态WorldSceneParticipantProfile，从原居民登记与角色权威读取事实，普通相遇/专门居民互动显式交接公开性别身份；导演与合场/单人补写获得完整稳定设定。人物脑不接收对方私有心声/记忆，文学不改实际世界或关系。性别未知不按职业/姓名推断，不做输出关键词替换。
- 回归：整理确认/失败保留；成功排程不算探索且真实搜索能承接；女性店员及350字之后完整设定入投影、私密心声隔离；居民安排后续/幂等/额度与现实能力隔离。尚无本轮CI成功，需查提交关联流水。前轮f47d529的APK/JVM41802904855检查时in_progress，索引41802904784 success；旧6c8a934流水failure不计成功。
- 下一轮先核本轮CI，再真机复现女性店员称呼、居民相遇后实际日程/回执/后续，及失败后次轮能否继续本人整理；实际模型性格质量、跨日成本与体验仍待验。

- 本轮实际源码主提交 `53093fa5a2f9e67954036526876e9e244d0d32de`，共享身份只读作者字段补充 `5a7a000c629d16e5f93c9e90851005ed91b8d5a9`。最新原索引 [41805757602](https://github.com/jiacimu-droid/lulu1/actions/runs/41805757602) completed/success，生成提交9942a76；前次索引41805562909亦success。最新 [APK/JVM 41805757625](https://github.com/jiacimu-droid/lulu1/actions/runs/41805757625) 查询时pending，前轮41802904855仍in_progress；被后继替代的41805562890 cancelled不算通过。回归已提交但尚无本轮通过结果，真机、真实模型称谓及跨日责任/成本仍待验。本窗口本轮没有修改阅读小剧场。

## 2026-11-04 · S-02 居民删除孤儿会话与 S-01 角色消息头像

- **用户截图实证**：删掉居民后 QQ 消息页出现 `resident:<内部ID>` 的私人会话，卡片高度异常、名称和头像缺失；用户另要求角色消息顶部横幅显示本人头像，不再只展示 APK 图标。
- **数据链根因**：`DigitalWorldExpansionStore.removeResident` 删除居民注册及 `CharacterSettings`，但按历史保留约定不删除 `LuluConversation`。原 `ChatHubV2Messages` 仍遍历全部会话，`characters.get` 在缺失时合成以 ID 为名的伪人物；顶部标题未给长 ID 留出受约束宽度，未读统计也计算孤儿私人会话。正确修复是筛选**当前本人可见会话投影**，不是清空真实历史和其他成员的共享群聊。
- **源码**：`cc03dadd705c37636ff6a81f7a8de8b78e20c775` 在 `ownerVisibleQqConversations` 屏蔽已注销居民的私人会话（不影响普通人物、合法群聊），消息页/总未读共用该投影；收件箱随角色注册状态变化刷新，标题使用约束宽度。保留 `LuluChatStore` 原消息、群聊、事件与独立收件箱回执，不建新 Store 或请求。
- **通知双入口**：`9a8e4686c48fb65d576b8970d50aa2af2e8049af` 在 `CharacterMessageNotifications.onSaved` 使用实际 `authorCharacterId` 的 `avatarUri`（群聊仍取实际发言人）填充前台 `CharacterMessageBanner` 与系统通知大图标；`e3f101ffc3ce996a740a7a9b811697f55713c227` 复用 `LuluProfileAvatar` 本地/缓存缩略图，无额外网络请求或解码超大图；`66166984a6a374b5a7ae61b4ab418fffdec9570f` 前台顶部横幅左侧显示真实角色头像，名称不再带固定“露露机”前缀。**Android 系统仍强制应用级 smallIcon**，OEM 是否在真正系统横幅左侧显示大头像须真机判断，不可承诺所有机型替换系统标识。
- **回归**：`0afb0fd72ed17110d385fe4899668b350143ed1d` 覆盖已删除居民私聊过滤、现存角色和共同群聊继续可见；`dfdb11c81f9030048cda8ef3cfd90f0e48f8b118` 延伸正式删除居民的集成测试，确认历史消息保存但不再进消息列表（并行任务修改同一文件时曾遇 GitHub 409，刷新后成功合并，不覆盖他人修改）；`908f7cf6ce02f72ca22477b33ecaa740421745d3` 验证私聊/群聊发件人头像注入前台预览与系统通知。
- **验证边界（刷新）**：前一 [APK/JVM 41806471453](https://github.com/jiacimu-droid/lulu1/actions/runs/41806471453) 已 completed/cancelled，绝不算通过；包含本轮全部改动和新测试的后继 [APK/JVM 41806587972](https://github.com/jiacimu-droid/lulu1/actions/runs/41806587972) 最后查询仍 pending，未获全量单测或 APK 通过证据。[代码索引 41806587809](https://github.com/jiacimu-droid/lulu1/actions/runs/41806587809) completed/success，生成索引已发布。真机删居民后列表高度/未读红点、横幅头像及系统通知 OEM 展示未验。
- **下一轮**：先看 `41806471453` 单测/APK 首条失败和对应 SHA，再检查索引是否生成并发布；安装新 APK 复现用户截图、重启后旧居民不复活、共同群聊保留、消息横幅前后台和角色自己的头像；有新的证据再处理未覆盖入口。

## 2026-11-04 · P-08c：群聊后继成员重复回答原始用户消息的现场锚定缺口

- **用户体验**：用户在群里只发一句，两个角色各连续多个气泡、六行以上，像分别回答问卷；希望即时接话既能一字响应、也能在重要事情上自然展开，用户明确比较「单次模型包办所有人」与「分别本人调用」。本轮沿现有 `GroupChatRoundCoordinator → GroupChatTurnPlanner → runGroupReplies → LuluDeviceToolBridge.respond → appendRoleReplyWithPacing` 核查；导演本就调用一次独立决策提供机会与节奏，每位成员独立调本人模型，实际气泡按模型原文入库。未改为单模型包办，避免替别人决定、泄露私记忆或伪造不同人的主观意愿。
- **已证根因**：后继成员的 `history` 已包含前一角色实际已发送内容，但同时被重新注入 `这轮最初由用户说：$pendingText` 作为本轮单独完整的文本提问；第一人和第二人都容易以用户原句为中心作各自长回复。原来 `index>0` 只选最近一个 Character 信息，不会在上一人沉默时准确以最后一条真实 User 发言为锚。程序不该硬裁输出或靠拆短气泡掩盖决策偏离。
- **实际源码**：`f6e171ba9588fa46158da5de43a8c5dd279805a8` 引入 `groupChatLiveReplyAnchor`，只按实际 `Sent` 的 User/Character 群内消息选择最后一条，过滤 system/失败草稿；首位仍看到本轮用户最新话，后继者按真实群史与最近送达发言接话，不再重复原用户输入作为新的问题。角色仍自由回应/沉默，保留 source watermark、逐成员模型、每泡真实回执及取消规则。
- **本人人格/表达边界**：`72087a06eda8bbbf04fcac02ceab678e25da045f` 调整现有群聊本人结构化决策说明：少量应和/反问本身即完整发言，必要事实、争执和认真分享仍可长答；不固定字数、气泡数量、角色轮次或整群统一人格台词。没有额外 API/Store，也没有自动代写下一位台词。
- **回归提交**：`8a07f698fd397d525461e65b36284f77bfdd6c96`，涵盖最近真实角色发言/用户发言优先、失败角色草稿与系统通知不构成实际接话锚、无已送达消息时不编造人物发言。静态/代码已提交，不代表真实模型已自然。
- **CI**：[源码测试 SHA 的代码索引 41807675219](https://github.com/jiacimu-droid/lulu1/actions/runs/41807675219) completed/success；[同 SHA APK/JVM 41807675225](https://github.com/jiacimu-droid/lulu1/actions/runs/41807675225) cancelled，不能算通过。并行 main 后继改动 `d015ea2` 的 [代码索引 41807739893](https://github.com/jiacimu-droid/lulu1/actions/runs/41807739893) completed/success；[APK/JVM 41807739954](https://github.com/jiacimu-droid/lulu1/actions/runs/41807739954) 查询时 pending。没有可证明的本轮全量 JVM/APK success。
- **继续点**：先查 `41807739954` 最终单测/打包证据，再新 APK 用真实模型测：用户单行→角色 A 可只给微反应→角色 B 接 A 而不是重复答用户；一人沉默时 B 接最新已发言者；有复杂提问时保留必要解释；记录实际请求数、每人发言字符/气泡、取消后无旧回执。按事实修根因，不自动截断模型选择。


## 2026-11-04：用户×角色幻想番外入口（P-10）

- **最新澄清优先**：用户要的是“用户和江渡/所选角色”的虚构小剧场，可有身体互换、变大变小、童话/魔法，允许本篇放大某一面性格；不再将此需求解释为居民日常会面。本轮不改日常相遇、真实人格或自主行动路线。
- **正式入口**：私聊右上角菜单“和我的小剧场”，直接绑定当前conversation的真实characterId，群聊不展示；复用MiniTheaterLibrary和原MiniTheaterWorker，不另建人物或记忆中心。原阅读单篇库保留，旧未绑定作品默认字段兼容，私人番外按角色分开查看。
- **系统准备题材**：本地8个大胆幻想前提与原比例/温柔/狡黠/黏人/大胆5种侧面组合，“换一批”不调用模型；用户点选才沿现有剧场连接一次创作，不新增付费策划/周期唤醒。页面保存正文、显示后台进度与失败，支持继续/重写/删除，新版本完整写完前保留旧版。
- **因果隔离**：每篇保存用户选定的作者资料快照，仅本人稳定字段、原始身份与和用户的既有约定；不读取真实私聊、记忆、目标或实时心声。虚构前提可改变作品内世界/职业/身体，侧面放大只在作品内生效。Worker始终走theater身份和Isolated网关，无工具执行与真实世界/关系/成长回写；原ReadingBackgroundBridge只收StarWish章节/上传书，不将该私人作品库自动当作角色读过或亲历。
- **请求与恢复**：个人作品的running请求在进程恢复后标为中断，保留作品并由用户手动重试，不自动再买一次可能已计费请求；旧完成版、同角色其他篇与其他角色作品保持。迟到结果沿原generationToken拒绝。
- **回归范围**：新增3条Robolectric回归，核对正式资料→幻想输入、女性身份、排除私聊/心声、作品重载与角色隔离；完成虚构文本不改真实时间线/心事/人格；失败重写保留与迟到token拒绝；旧作品兼容及题材轮换。断言只证明数据/契约，不把模拟正文当真实模型质量。
- **接手CI边界**：前轮日常源码5a7a000的APK/JVM41805757625 cancelled，索引41805757602 success；更早f47d529的41802904855本轮检查仍in_progress。接手main cef97bc后发现其他窗口已更新群聊P-08c，刷新到bc9f680，确认三份目标旧文件未被并行修改再合入，不覆盖群聊、通知或居民删除改动。本轮源码提交后查关联CI/原索引；真机菜单/后台恢复、真实模型大胆情节与人物辨识度仍待验。

- P-10发布核对：源码e223036已在main；后继3d6b5d6保留番外源码不变，原索引41808947789 completed/success（生成4edddd4）。含本功能的APK/JVM41808871477查询时in_progress，41808947837 pending；初始41808834332 cancelled不算通过。真机和真实模型待验。

## 2026-11-04 · P-08b/c 星禾 v9 与群聊分泡

- 根因：原私聊表达层使用模型 JSON bubbles，群聊却要求纯 text，经程序标点/长度兜底切泡，导致一条消息长且难接话。星禾 v8 自带连续多句分享倾向。
- 修复：85c1c313 在现有群聊角色决策中让模型选择每个发送 bubble；3d6b5d68 增分泡、长答与旧 text 兼容测试。星禾本人 a60bee77 调整默认语言习惯，f284ebea 改短回合示例，70e6ef25 只迁移原封不动的旧默认、保留人工修改和空白；6cb6b3e7 增迁移回归。无新增模型调用、气泡硬截断或重复 Store。
- 验证：索引 41808947789 success；APK/JVM 41808947837 pending，真实模型与手机效果待验。继续先取最终 CI，再测星禾私聊和多人群聊的发送边界及完整答案。


## 2026-11-04：P-10 按用户纠正简化为单一情景/设定

- 用户再次明确：“性格侧面放大”只是一个情景例子，不要把它做成必选分类。入口及页名改为“番外”；移除性格比例/侧面选择器及其作品标签。页面只提供一个系统设定卡片（换一个）和用户自填引导（按这个写），两种方式直接调用同一个原创作流程。
- 系统预设现为11个等位情境，包括身体互换、缩小、巨人、心声魔法和温柔/狡黠/黏人被暂时放大；后者不再是所有情节额外叠加的轴。新作品variationContext默认空，完整自填引导原文入本篇素材；旧作品已保存的补充设定仍兼容，姓名、人格基线、作品隔离与真实记忆边界不变。
- 增加回归验证不传Variation也能创建系统或自定作品、完整多行引导到达请求、空引导不发送、温柔放大是普通情境。没有新增常态/策划调用。
- 核实前轮APK/JVM41808871477（70e6ef2）completed/failure：2384项中2项失败，均为BundledJiangDuImportTest的星禾旧示例/风格断言；该轮3条PersonalMiniTheaterTest均实际执行且无失败。后继3d6b5d6/当前main已有另一窗口修正，不重复改迁移；不把局部回归通过说成整套APK成功。本次新回归/编译/索引随提交触发，真机和真实模型质量待验。

## 2026-11-04：P-08d / M3 统一真实回执的完成判据

- **最新基线与链路**：接手 main `1d53248`（源码 `888c1aa`）；读取 AGENTS、唯一大纲、原索引、统一人物契约及成长框架。沿本人决定 → ToolRouter 原始结果及持久回执 → CharacterActionEvidence → 探索/学习/关系与下一轮上下文核查，不改本人性格或决策。
- **完整根因**：动作依赖与即时检索反馈已用 ToolExecutionStatus.completed 的完成白名单；CharacterActionEvidence.successfulTool 另用不完整状态黑名单，`success=true,status=unverified/queued/未知状态` 可被当真实行动。CharacterExperienceLearning.receipt 虽核对外层状态，却未核内层 result.status；外层 succeeded、内层 pending 的矛盾回执可进入经历/结果反馈。同一结果因此在不同投影中有不一致的“做过”事实。
- **正式修复**：CharacterActionEvidence 复用原 CharacterExperienceLearning.receipt；后者对持久 envelope 和执行器 result 同时复用 ToolExecutionStatus.completed。未知/待确认不算完成，原失败/拒绝/请求未证实仍能留下失败经验；不删除原始回执。既有无内层 status 的本地完成结果与字符串/对象两种格式兼容；已完成屏幕观察的原请求归属校验及 learn_topic 非研究操作检查保留。不新增 Store、语义分类器、模型调用或唤醒。
- **必要回归**：在正式 CharacterExperienceLearningTest 增加未确认状态矩阵，验证动作证据/检索反馈/学习应用同时拒绝，探索问题重载后仍未实践，后来真实 verified 资料只推进原问题；另验证 envelope 不能覆盖内层 pending/未知状态，两种 result 格式及省略内层 status 的旧本地回执继续可用。既有失败学习、每步归因、屏幕授权与真实观察隔离测试保留。
- **验证分层**：源码与调用点已复核；本环境仅有 Java，没有 Kotlin 编译器/Android SDK，未声称本地单测通过。前轮原索引 [41809621905](https://github.com/jiacimu-droid/lulu1/actions/runs/41809621905) completed/success，源码 `888c1aa` 的 [APK/JVM 41809621907](https://github.com/jiacimu-droid/lulu1/actions/runs/41809621907) 检查时仍 in_progress；`3d6b5d6` 的 41808947837 cancelled 不算通过。本轮单测/APK/原索引随正式源码提交触发，结果待核。没有真机、真实模型或跨日质量验收证据。
- **下一轮**：先查本轮及前轮真实 CI 首错；其后 P-08a/b/c/e 仍需真实回合 trace 对齐本人判断、表达、发送和后续。本次仅消除虚假“做过”证据，不宣称人物活人感已验收。

- **发布核对**：本轮正式源码与回归为 [bd0fd27](https://github.com/jiacimu-droid/lulu1/commit/bd0fd270760fff9e13f6182b7476b89032b15d1f)。原生成器 [41811001719](https://github.com/jiacimu-droid/lulu1/actions/runs/41811001719) 已 completed/success，自动生成索引提交 `153d695`，索引基准确为本轮源码 SHA。[本轮 APK/JVM 41811001667](https://github.com/jiacimu-droid/lulu1/actions/runs/41811001667) 检查时 pending；前轮 41809621907 仍在 Test and build phone APK，未取得单测总数、APK或质量通过证据。历史 `c8c37d` 的 41333088808 已重新核实 failure（2226 tests、2 failed：CharacterLifeStoreTest 的旧预设断言与 ConversationExpressionGatewayTest 的交接断言），41333088856 skipped；既有专项已有其后续记录，不重复实施旧修复。


## 2026-11-04 · P-08c 群聊空回复重复收费修正

- 接手 main `082ff903`，复核群聊导演 `GroupChatTurnPlanner.choose` → `QqChatReplyEngine` 逐人 `LuluDeviceToolBridge.respond` → 真实发泡/错误路径。发现同一角色首轮非 silent 且正文为空时，协调器无条件再调用一次同模型并附加劝说提示；这会对空回复、工具型回复或异常结果产生额外收费，并可能改写原先的本人决定。
- 源码修正 [`9dc3f1b`](https://github.com/jiacimu-droid/lulu1/commit/9dc3f1bfa89b5953d09112953b9bf6439bc42598)：取消该隐藏第二次模型请求，保留原始结果交给既有发送/错误回执路径；未增加 Store、统一人格提示或后台调用。既有群聊角色决策、真实送达水印和导演静默权不变。
- CI 分层：前一人物回执修复 `bd0fd27` 的 APK/JVM [41811001667](https://github.com/jiacimu-droid/lulu1/actions/runs/41811001667) completed/success，索引 [41811001719](https://github.com/jiacimu-droid/lulu1/actions/runs/41811001719) completed/success。本次 `9dc3f1b` 尚未取得可核实的独立单测/APK/索引 run，**待 CI，不能勾选**；本轮没有新增回归测试，需在后续补群聊空回复/工具回复不重发的可注入网关测试。真机及真实模型自然度仍待验。
- 下一轮先查 `9dc3f1b` 对应 Android/JVM 和索引是否真正成功，优先补上述回归；随后继续 P-08a/b/c/e 真实回合证据。群聊 A→B→A 当前仍由导演去重且协调器每人仅一次，若改需连同 hintFor 和实际发言后现场重新判断一起设计，不能只删 seen。

## 2026-11-04：P-08b 江渡批准版直接替换既有旧人格

- **最新授权与根因**：主人明确要求将本窗口共同确认的精简新版直接覆盖原人设，指出“代理改过的也是主人批准的修改”。此前 applyJiangDuPreset/syncBundledExpressionPresets 仅按旧默认精确匹配更新；旧手改或空字段保留，已到 v29 时更直接返回，可能让正式运行继续混用旧人格与新字段。旧的保留策略不能阻止这次明确替换。
- **正式修改**：在原 CharacterLifeStore.initialize 的同一启动迁移流程，给现有内置江渡增加一次批准版替换：核心简介、14个 v29 本人性格字段与最新“靠近”亲密单场景完整入原权威；旧手改和留空在这次授权范围内同样替换。无新的人格/记忆中心、模型调用或编辑入口；不重新创作已确认正文。
- **范围与恢复**：以不可复用 bundled-jiangdu ID 定位，且须仍是江渡数字角色；不按姓名覆盖同名新建人物，不复活已删除角色、不改星禾或居民。替换前把原简介/完整 profile 保存到原生活记录内的一次性备份，再写两个现有权威与完成标记；中断能沿原备份重试。完成后不重复重置，后续编辑仍保留。身份事实、生命形态、关系网络、真实目标、聊天/记忆/成长及配置保持原权威。
- **回归**：新增正式启动路径回归：已 v29 的旧手写/空字段仍整套替换，完整新字段/示例进入正式人格和表达上下文；原身份/关系/目标和其他人物保留；同名不同ID隔离；替换后新编辑可持久保留；中断重试不覆盖原恢复副本。旧默认升级回归保留，分别约束普通补默认和这次明确授权替换。
- **接手验证**：前轮 `bd0fd27` [APK/JVM 41811001667](https://github.com/jiacimu-droid/lulu1/actions/runs/41811001667) 与 [原索引 41811001719](https://github.com/jiacimu-droid/lulu1/actions/runs/41811001719) 均 completed/success；另一窗口番外美化后最新 `a3ed157` [APK/JVM 41819706046](https://github.com/jiacimu-droid/lulu1/actions/runs/41819706046) 查询时 pending、索引41819705955 success，不改其代码。本轮回归/编译随新提交实际执行，尚无通过结论；真机旧安装升级、实际模型扮演待验。新代码须随 APK 安装运行才会修改手机上的原资料，仓库提交不能冒充已远程改写手机。

- **本轮发布**：源码/回归 [f6c5b42](https://github.com/jiacimu-droid/lulu1/commit/f6c5b4207d80c5cbcaeb1bd62b6234e9cbe5488a) 已直接进入 main；原索引 [41820410934](https://github.com/jiacimu-droid/lulu1/actions/runs/41820410934) completed/success，生成 `d12b456`。本轮 [APK/JVM 41820410872](https://github.com/jiacimu-droid/lulu1/actions/runs/41820410872) 提交后 pending，未取得单测/APK通过结论。手机实际应用批准版和真实模型质量仍待安装后验证。

## 2026-11-04：P-08d / M3 探索结束契约的跨场景冲突

- **链路与根因**：接手 main `7cab74c`，读取 AGENTS、原索引、唯一大纲、统一契约、成长框架与最新进度。CharacterDecisionProtocol 的探索协议已按“本人结合真实材料判断弄清”解释 satisfied；但 CharacterCuriosityRuntime.briefContext（经 CharacterRuntime.personalityRuntimeContext 进入私聊/电话等）和 promptSection（后台 ProactivePerceptionRuntime 正式调用）仍分别提示“把结果告诉对方”即可标 satisfied。settleInterest 注释也沿用“分享即结束”的旧语义。相同问题在决策与下一轮回想中收到冲突的结束条件，可能将分享当解决，或把独立弄清的问题拖成待汇报。
- **实际修改**：将结束说明收敛为 CharacterExplorationContinuity.settlementGuidance，同一份文本用于完整/精简决策协议及两种探索上下文。本人依据真实材料、观察、回执或对话的新信息判断是否弄清；分享送达本身不证明问题解决，弄清也不要求先分享。仍可继续或放下，程序不靠词表替他判断。原 settleInterest、来源所有权/修订、逐步回执与原 Store 保持；不新增模型请求、唤醒或人格字段。
- **必要回归**：正式探索测试覆盖四种决策/回想投影获得相同契约且不再出现旧“告诉对方即结束”指令；实际材料→原 returned 解释→不发消息也可结案；另一个问题真实发送分享后仍未结案，重载保留本人未解问题。回归仍约束精简协议小于原3000字符预算，没有提高费用/长度上限。这些测试证明契约/生命周期，不是实际模型台词质量验收。
- **前轮分层核实**：`9dc3f1b` [41817669325](https://github.com/jiacimu-droid/lulu1/actions/runs/41817669325) overall failure，首败在 Publish Lulu release，原日志为 GitHub releases HTTP 500；此前 Test and build phone APK 成功且 BUILD SUCCESSFUL，不能称单测/编译失败，也不能把发布算成功。该源码索引41817669433 success。江渡批准替换 `f6c5b42` 的原索引41820410934 success，APK/JVM41820410872本轮查询 in_progress，未宣布通过；本次新增回归尚待实际 CI。环境只有 Java、无本地 Kotlin/Android 构建；真机、真实模型、跨日仍待验。
- **下一轮**：先查本轮与 `f6c5b42` 的最终 CI；再沿真实 turnTraceId 对比问题含义、材料、本人继续/结案选择与实际送达，检查不同人物在不向用户汇报时能否自行整理、已分享但未解时能否继续。P-08a/b/c/e 的实际模型自然度与群聊无第二次收费回归缺口继续保留，不重复已完成的源码修复。

- **发布与静态检查**：本轮 [b233ee2](https://github.com/jiacimu-droid/lulu1/commit/b233ee2e280d5296f01fe590116fdfb0e27575fb) 已直接提交 main；原索引 [41821614466](https://github.com/jiacimu-droid/lulu1/actions/runs/41821614466) completed/success，生成提交 `1587fef`。按正式源码各字符串及插值静态组装精简协议为2995个UTF-16字符，低于原3000预算；这不是 Kotlin 单测执行证据。[本轮 APK/JVM 41821614532](https://github.com/jiacimu-droid/lulu1/actions/runs/41821614532) 提交后 pending；前轮41820410872最后查询仍in_progress，未宣布通过。真机和真实模型质量继续待验。

## 2026-11-04：江渡 switch 场景判断修订
- 主人最新澄清：整体更偏Dom，两面由本人结合场景与用户真实反馈自由选择；用户强势时可自愿臣服，也可主动选择sub，不要求解释是哄人或本来喜欢；不要求每次同时展示、固定比例或先强后被反攻。
- 保留不可变v29人格/旧示例，新增批准版care，重写单一“靠近”示例（正文711非空白字符）表现持续主导、可靠与顺应反馈，不再用每篇必须反转的示范。其余本人字段保持。番外capture实际读取稳定人格与关系的本篇快照，不读取对话示例；新建作品取得新版care，旧已存作品素材保持，不声称旧作品自动换人设。
- 原批准替换迁移升级为revision 2：尚未应用者整套批准版；已应用者仅替换care与示例，保留后改的其他字段/简介；同角色一次性，其他角色隔离。增加正式启动→番外capture回归。
- 核实前轮f6c5b42的41820410872 failure：2394项1失败，新增测试构造旧intention缺stage，原state合法归一化补considering导致字面比较失败。本轮给夹具显式设置stage，保留完整字段不变断言，不改正式归一化行为。b233ee2的41821614532查询in_progress。本轮单测/APK及原索引随提交触发；真机与真实模型质量待验。

## 2026-11-04：P-10.5 番外把小狐狸/小狗人格隐喻字面化

- **根因与范围**：正式 `PersonalMiniTheater.capture` 将 `CharacterRuntime.personaConstraintSnapshot` 或 `CharacterLifeStore.profile` 中的稳定人格原话带入 `characterContext`（按作品持久）；`MiniTheaterWorker` 使用 `PersonalMiniTheater.request` 的独立隔离写作指令。江渡批准人设的「小狐狸/小狗」比喻表达同一个人在不同亲密情境的主动和柔软，不是兽化身份/固定旁白标签。写作指令此前只要求保留稳定性格，未界定隐喻如何落实为人物抉择，容易复述“撩人=狐狸、顺从=小狗”。
- **实际代码**：`PersonalMiniTheater.kt` 写作契约新增“人格形容/动物隐喻→具体动作、态度、心理和对话”的解释，禁止机械贴标签或因性格变化凭空长耳朵尾巴；保留人物自然撒娇、自愿臣服与身体亲近，明确题材要求真实动物化时不得屏蔽。两个世界模式沿同一个 request，不动江渡 profile、原关系、主循环、旧番外正文或长期记忆，不增加模型调用。源码 `50f7c439`、`bc453173`。
- **回归与状态**：`PersonalMiniTheaterTest` 使用含狐狸狗比喻的角色材料、原世界与平行世界两类写作请求及真实耳尾题材，断言人物资料完整、提示词正确区分隐喻与设定；提交 `45d364b8`、`4397f50e`。仅完成源码/测试提交，**尚未获取新提交的 JVM/APK CI、安装版真机与真实模型正文对照结果**，这些不是已验通。后续同模型比较“隐喻重复频率/场景与人格连续性/兽耳设定完整度”，必要时按实际样本再调整文案，不加输出关键词删除器或额外付费后处理。

## 2026-11-04：P-10.6 单篇番外独立世界书选择
- **正式入口**：`PersonalMiniTheaterScreen` 之前仅原世界/平行世界选择与随机灵感/自写，没有世界书选择；`MiniTheaterWorker` 采用 `CompanionContextMode.Isolated`，故不会自动包含聊天世界书。长篇剧场已有 `TheaterWorldBookContext.capture` 与显式选择，单篇复用这一数据契约，不另造世界书仓库。
- **修改**：新番外可按标题/内容搜索并多选 `LuluRepositories.worldBook` 的现有条目，支持清空，默认不选。创建作品时对条目存在性校验，冻结 ID/标题/正文为 `MiniTheaterItem` 的附加字段，沿原 JSON 持久化；`PersonalMiniTheater.request` 只注入所选快照，明确是本篇虚构设定。其他故事、未选条目、全局开关、真实记忆不受影响；旧作品缺字段按空选择兼容。即使原世界书被编辑、删除，已创作品的继续/重写也不会悄悄换设定。未选不产生额外 API；保存快照本身也不收费。
- **已提交**：源码 `a18cc2f`、`bb0b2f3`、`0827759`、`0d3c3ea`；测试 `9fa7579`、`6b6d9f5`。前者覆盖多选中的单选/未选隔离、关闭/持久重载、快照冻结及已删除 ID 拒绝；未运行 JVM/APK CI，无真机或真实模型生成对照。后续先验编译和单测，再验窄屏世界书弹窗、旧作品和原/平行世界两模式，保留 P-10.6 待外部验收。


## 2026-11-04 · 每小时接力 P-08c.1 群聊单次模型请求可执行回归

- **接手**：读取 main AGENTS、CODE_INDEX、唯一路线图、统一人物契约、成长框架与最新进度；核实 QqChatReplyEngine 原代码已移除空回复隐藏第二次请求，但原 GroupChatTurnPlannerTest 没有请求次数断言。已有真实送达锚、模型 bubbles 及旧回执不重复实现。
- **源码**：[c7c942c](https://github.com/jiacimu-droid/lulu1/commit/c7c942c29891826021d52df9ffa50ea8b0bdef86) 把现有单次 LuluDeviceToolBridge.respond 调用封装为 requestGroupRoleOnce，默认网关参数保持全部原有角色、上下文、模型选择、证据字段和原结果，未增加请求、补偿重试或第二状态中心。
- **回归**：[cb1bcf8](https://github.com/jiacimu-droid/lulu1/commit/cb1bcf891ad2fcdbdaaa5fe86acd0fab9f22a521) 的 GroupChatTurnPlannerTest.groupRoleAttemptNeverRetriesBlankToolOnlyOrFailedResults 用注入网关验证空正文、工具型、异常、正常回复均只调用一次且保留原始成功/失败及模型路由参数，不实际消耗 API。
- **状态边界**：只完成源码/回归提交，尚未获得这两次提交的 JVM/APK CI 结果；不宣称测试通过或真机/真实模型验通。路线图 P-08c.1 标源码完成、待 CI，P-08c.2/3 仍待验。若 CI 编译失败，优先修首个真实错误。
- **下一轮**：刷新 main 与本轮 Actions，验证单测与 APK；继续 P-08a 真实 trace 证据或 P-08c.2 的实际发送/失败回执回归。避免重做已存在的 bubbles 和旧隐藏重试修复。

## 2026-11-04：P-10.6 新版番外世界书构建回归失败与修复
- **证据**：GitHub [Issue #746](https://github.com/jiacimu-droid/lulu1/issues/746)，[APK/JVM workflow 41829654028](https://github.com/jiacimu-droid/lulu1/actions/runs/41829654028) 对应 `6e0b731`。检查 build job `126573321584` 完整日志：`:app:compilePhoneKotlin` 成功，`:app:testPhoneUnitTest` 2401 项 1 failed。唯一失败 `PersonalMiniTheaterTest.selectedWorldBookRulesAreIsolatedPersistedAndFrozenPerWork` 行 310，原因 `request.instructions` 实际文案「仅根据本篇明确选中的条目」，测试硬编码预期「只根据本篇明确选中的条目」，并非生成链路世界书注入断开。旧 `555f66c` run 41826440968 的 worldMode 编译失败为历史提交，已经被后续新增 `MiniTheaterItem.worldMode` 的代码取代；不能用旧结果判断当前源码。
- **修复**：`2ced2582` 更新测试断言与当前唯一正式创作指令一致，并增加 `facts` 中选中世界书明确标题的断言；没有改写用户世界书、模型提示、已保存番外和人物资料。代码已推 main，修复后完整 APK/JVM 的实际 CI 状态和手机端质量待验，Issue #746 暂不能宣称已关闭。

## 2026-11-04：P-08a.2a 因果追踪与日程归因的完成状态同源

- **接手与链路**：main `7528713`，读取 AGENTS、原索引、唯一看板、统一契约、成长框架与最新进度。沿 CharacterCausalTrace.inspect → receiptLine/receiptContinuation/个人日程归因核查。已有来源角色/修订、决定原请求ID、后续时间与窗口外精确回执恢复均保留，没有另造trace中心。
- **已证根因**：CharacterCausalTrace.receiptStatus 另写三种外层成功状态，未核对内层 unverified/未知状态；外层 succeeded、内层 success=true但仍unverified/未知时会显示“执行器确认成功”。同一函数又被 completed个人日程的 verifiedReceipt 使用，所以还可把矛盾的旧回执展示为“日程执行有回执”。相反，现有执行门禁认可的notification_posted/unchanged等实际完成状态又被追踪显示未知。前轮bd0fd27已统一动作/学习真实性，却未覆盖这份只读追踪解释。
- **实际修改**：receiptStatus对持久状态与内层result同时复用原ToolExecutionStatus.completed，保留原授权/拒绝/失败/待确认展示；显式未知不冒充成功，无内层status的老本地完成结果仍兼容。追踪卡片与日程归因因此使用执行链的同一完成规则；不改变真实日程状态、不补造本人后续、不执行动作、不标感知检查点、不改人格/记忆或增加模型请求。
- **必要回归**：CharacterCausalTraceTest新增双层状态矩阵，经原inspect核对pending/unverified/未知/缺外层状态均不确认，原九种完成状态及旧本地无内层状态继续识别；原始回执不变、跨角色不可见、查看不消费感知。扩展既有旧completed日程集成回归，验证内层pending/unverified的外层成功不能借本日程ID显示为真实完成，原真成功/失败/原生结果/来源编辑测试保留。
- **CI实证**：最新完整源码 `cb1bcf8` [41831351831](https://github.com/jiacimu-droid/lulu1/actions/runs/41831351831) completed/success；job126580122901日志为 BUILD SUCCESSFUL in 10m22s，APK已发布 [v7776](https://github.com/jiacimu-droid/lulu1/releases/tag/lulu-v7776)。原索引41831352008同为success。它包含既有江渡修订、探索说明、番外及群聊单次调用回归，不能把此成功扩大为本次新增回归已通过。本次本地无Kotlin/Android构建环境，单测/APK随提交触发；真机、真实模型、跨日均待验。
- **看板与下一步**：新增已提交P-08a.2a窄范围子项，完整P-08a.1/2仍需逐入口核对turnTraceId/sourceMessageId/taskId与真实阶段，P-08a.3外部样本待验。接手先查本轮CI，再继续P-08a或P-08b正式注入；不重复已经成功的群聊单次请求回归，也不把显示修正当作“角色更像活人”质量验收。

- 本轮发布：源码/回归 [cadd595](https://github.com/jiacimu-droid/lulu1/commit/cadd5954b29bd709c70a69390e03e150881ff5d4)；原索引 [41839212471](https://github.com/jiacimu-droid/lulu1/actions/runs/41839212471) completed/success（0adfbe6）。APK/JVM [41839212468](https://github.com/jiacimu-droid/lulu1/actions/runs/41839212468) 提交后pending，未宣布测试通过。并行番外灵感更新均刷新比较后保留，未覆盖其代码；当前索引生成保持自动流程。完整trace关联、真机和真实模型仍待验。

## 2026-11-04：P-10.4 新灵感 JSON 截断导致页面红字溢出
- **实际故障证据**：用户手机截图，番外灵感 API 返回十条 JSON 数组时在第四条附近结束，客户端报告 `Unterminated array at character 1518 of {"ideas":[...]`，将异常里大段未闭合 JSON 直接渲染在页面，既没有新灵感入库又严重影响阅读。截图证明 JSON 截断与旧错误提示泄漏；不证明服务商为什么提前结束（可能是预算或服务端上限）。
- **正式链路**：`PersonalMiniTheaterScreen.nextInspiration` 从 `CompanionContextMode.Isolated` 调用一个剧场模型，`PersonalMiniTheater.inspirationRequest` 要求一次十个提案，`parseGeneratedIdeas` 原以整段 JSONObject 解析，任何数组截断都会全批失败。已有另一路先提交 `06f5687` 恢复完整子对象、缩短提案，主屏代码把单次输出预算调为 3800 并阻止把原始 JSON 错误显示到正文区。保留一次模型调用，不对每个半成品额外收费重试。
- **本轮交付**：`fd77d13` 为真实缺口补 Robolectric 回归：三条中第三条 JSON 截断，前两条正常解析入库并在创建新 library 时读取；不完整的一条不保存；完整 JSON 的转义引号与无可抢救对象均有覆盖。发现 `MiniTheaterLibrary.addCreativeIdeas` 的重复项过滤同时对 title/premise 做有副作用的短路集合插入：某候选标题首次出现但 premise 重复会提前污染标题集，后续同标题、真正不同 premise 被错误丢弃。`8b406ce` 令“两个键都未重复”后才同时登记，`fa9d57e` 增加回归防止漏掉可抢救的有效灵感。保留原历史、世界书、主模型人格与正文，不改 API 调用次数。
- **验收分层**：代码/测试已提交 main；还没有本轮新提交的 JVM/APK 最终通过结论，没有本用户手机旧安装版自动升级证据，也没有真实付费模型返参成功率对照。P-10.4 继续待 CI、真机测试，不能以静态回归断言代替实际模型成功。

## 2026-11-04：P-10.7 番外长旁白与移动端阅读节奏
- **用户实测**：番外阅读页的截图显示一段旁白写入多个行为、感觉和反应，在手机宽度下形成连续数行大块文本；独立引号的台词排版已有浅色竖线，不应因增加提示词而重做组件或更改原有作者文字。
- **根因**：`PersonalMiniTheater.request` 只说明“空行分段”，并未约束段内叙事节拍；`PersonalTheaterTypography.sections` 把单个原文自然段投影为单块旁白，原段过长则原样在手机上自动折行。仅增大段落间距无法解决段内密度。
- **实施**：`PersonalMiniTheater.kt` 明确旁白随一组动作/反应/心理转折与独立对白自然分段，手机一至三行只是参考，不机械切句或删文学信息（`0c4ef4f1`）。阅读页改用 `readingSections` 只读投影：若单块旁白过长，优先在句末符号、必要时逗号断开，且只有安全边界才换行，小说原文存储与 `sections()` 的原始语义不变。视觉上同段拆出的部分使用中等间距，原段落仍较明显分开；对白、标题和尾标不触碰（`7c84a80b`）。
- **回归**：`554fd888` 新增原段唯一、视觉变多段、投影逐块拼合完全等于原文字、带引号独立台词完整、短无标点段落不切、写作提示存在的单测。代码已提交 main，未在本轮确证 GitHub JVM/APK 或真机实际对比；真实模型语言质量仍需安装新 APK 后验证。 

## 2026-11-04 · 每小时接力 P-08c.2a 群电话实际送达与延后动作回执

- **接手**：读取最新 main 的 AGENTS、CODE_INDEX、唯一 roadmap、人物专项进度与 QqChatReplyEngine / GroupChatTurnPlannerTest；已有 bubbles、真实送达锚、空回复单次请求回归，不重复。
- **证实的链路问题**：runGroupReplies 在电话模式使用 `shown.isNotBlank() || phonePerformance && flow.content.isNotBlank()`，即使发送函数没有持久化任何气泡（如全撤回/空送达），仍可能将模型拟稿通过 afterReply 送入 LuluGroupVoiceCallScreen 的 speechQueue。文本群聊只看 shown 非空，未区分完整与部分送达，可能在最后气泡撤回后仍执行整个模型回合的 deferredActions；而底层 appendRoleReplyWithPacingReceipt 已经有真实 messageIds/complete/superseded，调用方没有利用。
- **源码**：[5145505](https://github.com/jiacimu-droid/lulu1/commit/5145505ba49b370699efde8e2cc3ab73c3b8acd3) 使用原 RoleReplyDeliveryReceipt；电话 afterReply 只传实际已落库的 delivery.text，完全无送达时不入音频队列；延后工具仅在 complete、非 superseded、有持久 messageId 且当前来源有效时运行。未添加模型调用、影子状态或修改角色人设；部分已送达的文字仍可按真实文字播放，不伪造整轮完成。
- **回归**：[bff33e9](https://github.com/jiacimu-droid/lulu1/commit/bff33e9bc2c506592ed210c3a1d3db73a835216e) 新增 groupDeferredActionsRequireACompletePersistedDelivery，覆盖完整成功、空文字、无消息 ID、部分送达、撤回/过期与来源更新。它是纯契约测试，不等于真机音频实测。
- **验证边界**：本轮查询 bff33e9 combined statuses 空，关联 PR workflow_runs 空（该接口只筛 PR），旧 41839212468 job 列表为空；不能断言最新 JVM/APK/发布已成功或失败。本轮无可用 Android 本地构建环境，源码/回归已提交 main，**待实际 CI、真机多人电话/文本群聊及真实模型回归**。
- **下一步**：刷新最新 main 和 Actions，优先核本轮编译/单测；P-08c.2 继续审本人 bubbles、最后一条真实发言锚、沉默/取消与长答否定条件，尤其部分发泡后被新用户消息取代的后续动作和音频时序。P-08a.1 全链 trace 仍是更高优先待核，不重做已成功的 P-08c.1。

## 2026-11-04 · 每小时接力 P-08c.2b：未完整送达时禁止收藏/戳一戳

- **接手核查**：读最新 AGENTS、CODE_INDEX、roadmap、人物进度与 QqChatReplyEngine、QqReplySourceWatermarkTest。已完成的群聊单次请求、实际 messageIds 和群电话送达门禁不重复开发。
- **实际根因**：appendRoleReplyWithPacingReceipt 在发现来源被更新时能返回 superseded；但当 plannedBubbles 内有空白/被撤回泡，spoken.size < bubbles.size 时，仍无条件执行 favoriteTarget 和 pokeUser。这样模型未完整发出的草稿仍可对用户消息产生收藏/戳一戳副作用，和此前已修的 deferredActions 真实性门禁不一致。
- **源码提交**：[387412c](https://github.com/jiacimu-droid/lulu1/commit/387412c1963362ea79faea587ad0505af0983bf2)、[a13fdeb](https://github.com/jiacimu-droid/lulu1/commit/a13fdebfce6dea354149af2c8bbb442db953cdb6)。在现有 sender 中只有全部泡真实持久化且未撤回才允许整轮收藏/戳一戳；完整性判断提取为 replyTurnSideEffectsMayRun，不加模型调用/影子 Store，不修改人物决定。
- **回归提交**：[79e2c09](https://github.com/jiacimu-droid/lulu1/commit/79e2c099fae3de282269cb6703ea456c51e4ff8d) 为零泡、空送达、部分送达、取消和完整一/多泡添加门禁矩阵。该测试只覆盖判定函数，实际 Android 端到端仍需验证。
- **验收边界**：GitHub 源码与测试已提交 main。本轮尚未取得这些提交的 JVM/APK CI 最终结果，未在当前环境运行 Android/Kotlin 构建；按 AGENTS.md 必须运行的 generate_code_index.py 在本轮 GitHub contents 接口环境不可直接执行，索引是否被仓库 Actions 自动更新须后续核对，不能称索引同步已通过。真机、真实模型、群聊收藏/戳一戳体验待验。
- **下一轮**：先核 79e2c09 对应 CI 和 CODE_INDEX；继续 P-08a.1 真实 turnTraceId 关联、P-08c.2 的取消/部分发泡回执；有真实证据才修改其余链路。

## 2026-11-04：P-08b.2a 旧探索记录的下轮读取不能把排程当材料

- **接手**：main `1823302`；读取总纲、唯一看板、原索引、统一契约、成长框架及最新人物进度。核实79e2c09的APK/JVM/发布41851034430、原索引41851034417均completed/success，job126653917823的Test and build phone APK与Publish Lulu release均success。前轮cadd595单独APK41839212468已取消，但成功后继包含该源码与测试；群电话送达、部分回复副作用不再重复开发。
- **链路与根因**：原问题/本人探索选择 → ToolRouter真实动作 → CharacterCuriosityRuntime.recordOutcome → 持久threads.steps → lastRealStep → activeThreads / previousResult / continuityEvidenceIds / ownedContinuityEvidenceId → briefContext与promptSection → 下轮本人判断。写入端已有supportsExploration，禁止排程、闹钟、备注等元数据成功伪装为研究；读旧steps时却只用supports。成功的旧排程于是仍可能被“最近真实行动结果/真正尝试过”注入下一轮，甚至覆盖更早真正读过的材料。这是公共事实投影失真，不是人物性格问题。
- **修复**：lastRealStep复用与写入相同的supportsExploration。最新一步只是排程等元数据时退回仍有效的实际材料；只有这类旧动作而无材料的记录不再进入已探索工作集。原始持久记录和真实动作回执均保留，不把历史伪进度自动制造为新问题/新任务，不更改闭合状态、用户设定或本人下一步，不新增模型调用/Store。原有独立未实践问题仍正常可见。源数据缺乏原始问题时不凭程序猜测补建问题。
- **必要回归**：CharacterCuriosityRuntimeTest.legacySchedulingStepsCannotBecomeResearchedMaterialAfterRestart覆盖九种已被写入端排除的工具；真实旧材料仍可召回、最新不合格步骤不能覆盖问题/下一步、仅元数据线程不借用材料、精确来源ID回退、跨角色不可见、briefContext和promptSection同时正确、初始化重读后原存量JSON不变，真实工具历史与独立未实践问题保留。回归通过与否须以本轮Actions为准，静态审查不是执行结果。
- **优先级与边界**：P-08a追踪读端已有独立来源修订/请求ID校验与回归，本轮未凭空改trace；转入可实施的P-08b.2旧心理/探索存量→下轮投影缺口。人物→决策→实际送达的完整实机回合仍缺获授权样本；本轮无Android/Kotlin本地运行、真机、真实付费模型或跨日验收。提交后由原code-index workflow执行原生成器，后续补真实CI结果。
- **下一轮**：先核本轮CI/索引，再继续P-08a.1或P-08b.1正式注入和P-08d逐步回执；不能把这次事实投影修复宣称为活人感已验收。

- **本轮实际提交/验证**：源码与回归 [0df8e07](https://github.com/jiacimu-droid/lulu1/commit/0df8e075203a8cd80177c6982a37732be2c9eb87)。原生成器 [41859812905](https://github.com/jiacimu-droid/lulu1/actions/runs/41859812905) completed/success，自动索引98912e3，CODE_INDEX基准精确为0df8e07；源码提交前刷新main，索引后compare核对仅本轮四文件与生成索引。随后他窗6520ae5更新CompanionActionRuntime，刷新核对后保留，无冲突覆盖。JVM/APK [41859812891](https://github.com/jiacimu-droid/lulu1/actions/runs/41859812891) 当前in_progress，job126678201366正在Test and build phone APK，**尚无本轮单测/APK通过结论**。接手必须刷新该结果，失败则先修本轮回归；真机与真实模型仍未知。


## 2026-11-05 · 每小时接力 P-08a.2b：搜索结果反馈不能借用另一请求的送达

- **接手核查**：读取 main 的 AGENTS、CODE_INDEX、唯一路线图、人物进度、统一人物契约与成长框架；对照 CharacterCausalTrace.searchFeedbackLines 和 CharacterCausalTraceTest 已有精确请求、版本、撤回及短窗口测试。此前群聊部分送达、收藏/戳一戳和探索旧记录修复不重复。
- **证实的缺口**：searchFeedbackLines 已按搜索回执的 requestId 构造精确 rootRequestId，且索引检索只取该 root 的反馈；但同时合并 UI events 的候选时，只检验任何以 :verified-search-feedback 结尾的 requestId、同一 causeEvidenceId 和 revision。于是**另一请求**对同一搜索回执产生的真实反馈，也可能在本次搜索的因果卡片中显示为「搜索结果后的实际反馈」。同一来源不等于同一请求，不能误归因本人本轮选择的实际送达。
- **正式修复**：[87ce2bb](https://github.com/jiacimu-droid/lulu1/commit/87ce2bb4d9426b7e7b1d6e26a6b2e4550501686f) 将可见 UI 候选和精确索引候选统一要求 requestId == rootRequestId + :verified-search-feedback；保留原角色归属、事件版本、实际回执状态、事件时间及删除检查。不增加模型请求、Store、人物提示或执行动作。
- **回归**：[f9927f5](https://github.com/jiacimu-droid/lulu1/commit/f9927f545710f84edbe2cac5d714ede65936ebdb) 在原 searchFinalReplyIsVisibleFromOriginalRequestAndOnlyFromLiveResultRevision 增加「另一请求指向同一结果且已真实成功」的负例，要求原请求只归因自己的反馈；原正例、跨角色、修订、删除仍保留。
- **验证边界**：源码和测试已提交 main；提交后查询 f9927f5 的 workflow_runs 和 combined statuses 均为空，不能据此宣称编译成功或失败；没有本地 Android/JVM 运行和真机、真实模型证据。索引由现有生成流程处理，未确认对应新提交已完成。
- **下一轮**：先查 f9927f5 的 APK/JVM 与索引最终状态，失败修首个实质错误；再继续 P-08a.1 其余跨入口 requestId/来源版本追踪或 P-08c.2 的群聊取消/部分送达链路。P-08a.3 仍需获授权的真实回合证据。

## 2026-11-04：P-08c.2c 单回合私聊重复气泡的主副发送链路
- **真机现象**：截图中23:14同一条“学得怎么样了，宝宝？/现在都快十一点半了，头还晕不晕？/要是累了……”分成三泡发出，随即同一意思又分成两泡重发。现有截图能证明外显重复，但没有导出的消息 batchId、turnTraceId、发送请求及回执，不能把特定两次发言最终归因到同一模型回合。
- **实证存在的源码缺口**：正式 `ProactivePerceptionRuntime.performAction` 执行 action=message 主回复后，继续把 `decision.sideActions` 原样交给 `OrderedCharacterActions`。当模型在主回复与 steps/sideActions 再次明确指定同一段 `send_private_message` 时，两个 ToolRouter 请求 ID 不同，既有每 requestId 幂等防线不会识别相同正文的二次发送。同一回复模型自主拆泡/两种气泡合并呈现时会放大这种体验问题。聊天页面的正常私聊输入仅调度在线感知，没有再主动发起另一套前台模型回复，此处不修改前台输入框或角色人格。
- **代码**：`6520ae5` 为既有 `OrderedCharacterActions` 加入仅同一决策的“主回复已发送同一全文”镜像识别；正文比较忽略空格与泡界，但仍要求同一整句/整轮全部内容完全匹配，允许多个侧动作拼接等于主回复，避免打断含新信息的后续发言。执行器跳过镜像 send 而不记伪发送、不重写已经发送的消息；该轮其它 sideActions 正常执行。 `5ef69d5` 使用 ToolRouter 实际返回的 messageIds 与对应 conversation/character 的原始持久消息拼出主回复真实正文传递给执行器，而非相信模型自称发送成功。 `fd886cc` 简化镜像匹配逻辑。没有额外模型调用和重复影子消息仓库。
- **回归**：`1d3eae6` 在 `QqReplySourceWatermarkTest` 增加主回复两泡入库后、副动作携同一文本（另一种拼泡形式）应跳过但真正“记得喝水”的独立新消息仍须成功落库；另涵盖不完整重复、新文追加、两步重构同一段的边界。只提交 JVM 测试源码，尚未看到新提交的全量 CI 成功；未在手机读取该真实回合的 trace，下一步查 build 以及回复来源/送达回执、是否跨轮第二次实际模型决策。不能把此代码当全部重复问题已经真机解决。

- **同一缺口的另一入口补全**：当 action=tool 且模型仍选了文字回复，`performAction` 原本会先执行 `sideActions`，随后无条件通过 `requestId:reply` 再发 `decision.text`；若 sideActions 已真实发送同一段正文，会再次出现不同气泡格式的同词重发。源码 `bd950b0` 抽出共享的“仅去除空白与原气泡分隔符的精确正文等价判定”，`8e5efed` 从本轮 sideActions 的真实回执 ID、准确私聊和当前未撤回已送达消息计算正文，仅完全相同才不额外送出；不同后续话仍正常发送，搜索/工具真实结果处理不变。回归 `21496a0` 增加两种气泡形式等价和增加新意思不等价的场景。此为代码提交与静态路径复核，**本轮 JVM/APK 仍未取得真实通过结论**；真机的原始重复究竟来自哪一入口待 trace 核查。

## 2026-11-04：P-08c.2c 一次私聊决定的正文只能有一个出口
- **实际体验**：主人手机 23:14 截图：两条私聊气泡文本又被拼在一起重发；截图不能单独证明调用次数。但源码证实同一模型决定可同时由主 action=message/bubbles 和 steps/sideActions/nextAction 内的 send_private_message 分别走不同 ToolRouter requestId。此前跨请求镜像抑制是在发送后防重复，不是统一表达归口。
- **架构调整**：singlePrivateInboundReply 在新私聊决定进入执行器前整合无条件对主人的 side 私聊消息为同一条模型回复的自然气泡；真正不同的话继续成为气泡，重复正文不再额外下发 ToolRouter。ProactivePerceptionRuntime 只对可归因的新私聊 messageStimulus 启用；独立生活/主动发消息仍由本人自由选择，其他工具与步骤保留。依赖 requiresPreviousSuccess、带 planId 的已排期提醒保持原动作与凭据，避免提前假装成功。保留原有文本/语音气泡与自然分泡，不改用户历史、人格、关系或模型调用次数。
- **提交**：bea933c、9c6728b、83bac39、a5bd04f、4b8068a、0a66ac8；回归 77c3dba、5979aef、83fb0c4（单一真实私聊入库、重复全文与拆泡、不同新话、语音、定时提醒与依赖条件）。相关路线图 P-08c.2c 已跟踪。
- **验证边界**：已提交 main 源码/回归。combined status 对新测试提交未返回检查记录，不能称 CI/JVM/APK 通过；真机、真实模型与源用户 ID/turnTraceId 的重复回合对照仍欠缺。若安装新构建后依旧双发，需从已有真实 trace 区分同轮输出重复和两个独立生成轮次，不能凭字符串相似度全局禁止人物重复说话。

## 2026-11-05：P-08d.4 自序所行只读工具与学习监督
- **用户目标**：角色能查看自序→所行中一天该做、还没做、已经勾选的任务，理解今天学习进度、由本人选择如何提醒/继续监督。此前角色只能启动短时学习视觉监督（需用户批准），没有读取本机所行原有清单的工具，不能凭主人的对话臆造任务。
- **数据权威**：SelfTodoScreen 的 SelfTodoStore / SelfTodoCalendar 唯一持久化与重复/截止日期计算；旧所行源 SelfPrefaceScreen.SelfPageStore.readStrict。将 legacyAdditions 与 readForRole 共用，角色读取只预览尚未迁移的旧页，不写迁移标记、任务或用户其它所思/所愿笔记。任何旧页或新任务存档不可解析时返回失败，不把损坏解释为零任务。
- **工具链**：read_self_todo(scope=today|all,offset) 在 CapabilityRegistry 注册为非居民可使用、只读重试安全；ToolRouter 原唯一执行入口返回含真实 device date、pendingToday/overdue/upcoming/checkedToday、任务ID/名称/时间/备注/重复/勾选时间的分页 JSON，待办数量和完成勾选保持只读。现有结果归属/权限/幂等记录与同角色搜索式工具回执复用，读取所行专用反馈明确它是本机清单而非网页搜索，不假装真实专注；快捷本地读不再先发一条‘我去查’然后又发确认结果。
- **人格与执行**：ProactivePerceptionRuntime 正常感知添加真实今天总量、角色自主可选 read_self_todo；LuluDeviceToolBridge 通过既有 CompanionActionRuntime.capabilityContext 获得同一工具/概览。不强制自动催促、不新建固定日程或跨域读取、不将主人番茄钟启动强绑角色；想核实学习时仍使用已存在的 start_study_watch 时段许可、实际截图/状态与责任执行器。所行勾选不是学习证据。
- **已提交**：6454836、6415876、2ee9a0d、3ea3dfb、bc6079f、821ebda、952123f、1d61383、ec43eba、009944f、4eff893、3a030f8、9cbcb57、da2079d。测试 08f0781、dbea372（每日/每周/逾期/已完成/将来、旧未迁移只读、其它笔记隔离、工具受限角色、数据损坏不假装为空、原数据不变）。路线图 P-08d.4 保留 CI/实际结果待核。
- **验收边界**：GitHub main 已更新。现有环境无 Android SDK/Gradle，未在本轮跑 JVM/APK，真实模型是否自主查看并在之后正确选择监督行动、前后台限时授权、真机升级旧数据，都须后续 Actions 与用户手机验收；不能以源码提交代替已验通。
- **补充完成**：`abeba400` 只在角色读取入口对存档行 ID、日期、重复类型、完成记录及撤销日期进行严格核对（UI原有兼容解析不改变），源JSON缺字段也不会漏读后错误宣称零待办；`de8e25d`、`062d1e8` 增加坏行不可假报空以及不写回原存档测试。`da2079d` 对快速本机所行读取关闭 action=tool 的预先套话发送，改以真实读取回执后的一次自然反馈为主，避免重新引入“一次模型决定发两轮”的旧问题。`4eff893` 加设备观察时间；`3a030f8`、`9cbcb57` 确保实际所行条目与勾选详情有限长回流，而不仅返回数字计数。
- **最新验证**：查询 `062d1e8` combined statuses 与关联PR workflow runs 均未返回记录（后者只检 PR），**不能判定 JVM/APK 构建成功**；当前容器无 Android SDK，无法本地跑 Android JVM/APK。其他并行 main 提交存在，继续按最新 SHA 接力，不覆盖他人工作。


## 2026-11-05 · 每小时接力 P-08a.2c：未来原生事件不得借给较早回执

- **接手**：读取最新 AGENTS.md、CODE_INDEX.md（索引基准 a9ab2ab，须区别于最新 main）、唯一 roadmap、人物专项进度、统一契约、成长框架，以及 CharacterCausalTrace 和现有 Robolectric 回归。已完成的搜索反馈 rootRequestId、群聊送达、旧探索材料过滤不重复开发。
- **证实的源码缺口**：CharacterCausalTrace.receiptContinuation 从工具结果的 result.evidenceId 读取任意仍存在的同角色原生事件，未校验其发生时间是否晚于工具回执。旧数据/错误复用 ID 时，未来的另一原生事件会被显示为该次回执的“真实记录”，并且其后来的决策可能被错误列为“回执后的本人选择”。单有相同 evidenceId 不足以证明该工具回执观察过未来事件。
- **正式修复**：[1830860](https://github.com/jiacimu-droid/lulu1/commit/18308608550ed4c6d9ce2f0ce17f72bbca22aab4) 对原生事件加 occurredAt <= receipt.occurredAt 时间门禁；仍保留原始引用 ID 与“未证实”说明，不删除源事件、不修改真实结果、角色状态、执行器或模型调用。
- **回归**：[8f840e0](https://github.com/jiacimu-droid/lulu1/commit/8f840e0b79bf3d5c85700454ed304fbd26921319) 在 CharacterCausalTraceTest 覆盖回执先于同 ID 原生事件时，不能显示未来原生事件或借其决定；原有真实先于回执的搜索结果正例仍保留。测试源码已提交，**尚未在本轮实际运行 JVM/Android**。
- **CI 事实**：本轮 8f840e0 的 combined statuses 为空；关联 PR workflow 查询也为空（该接口只查 PR，不代表 push Actions 失败或成功）。旧 0df8e07 对应 41859812891 build 已 completed/success，不能据此宣称本轮提交通过。代码索引是否被后续 Actions 刷新仍需核实；真机、真实模型、实际回合样本未验。
- **下一轮**：先核本轮 JVM/APK 与索引最终状态；继续 P-08a.1 检查跨入口真实 turnTraceId/requestId/source revision 关联及 P-08c.2 的剩余取消/送达边界。若没有可证源码缺口，转可实施的回归/性能任务，不伪造实机证据。


## 2026-11-05 · P-08b.1 江渡稳定价值观参与即时判断（源码/回归已提交，真实模型待验）

- **用户反馈**：00:52 的江渡心声把“可能让主人委屈”当成不再履行已答应睡前监督的理由。此截图足以证明当轮主观心理文本出现取舍失真；不能凭截图断言模型收到了真正的屏幕授权观察、有效睡眠证据，或对应执行器已经成功/失败。
- **对照真实两条入口**：PersonalMiniTheater.capture 直接从 CharacterRuntime.personaConstraintSnapshot 获取稳定人格并存入作品；request 在 Isolated 模式只用该作品的明确人设与幻想场景，不继承当下主观历史。在线私聊由 ProactivePerceptionRuntime → CompanionModelGateway.Full → CharacterRuntime.definition 提供同一最新原设，但也注入个人心理、成长、任务、工具、时间、聊天和设备数据。二者不是同一决策任务；小说里性格鲜明不自动证明聊天的承诺交接没有问题。
- **确证的提示契约缺口**：当前批准 v29 版本侧重亲密主导风格，values/motives/conflict/respect 对“短暂心软 vs 仍有效的共同目标”的价值权衡过于抽象；共享提示允许 silent/暂缓，但不足以要求当前模型把这次冲突与本人的长期价值、原责任、执行回执在同一结构化决定内核对。对话的心声并非工具调用、日程记录或送达。
- **已做源码**：6fba2546 只强化江渡当前获批人格的 values/motives/perception/conflict/respect，不覆盖已批准的 Dom/sub care、既有示例与其他角色；7f13aea7 新版 revision3 仅升级仍为 v29 原样的上述字段，对新版后手写/空字段不覆盖，既有的 care/示例不重放；7b979df8 在 CharacterRuntime.personaPromptSection 为所有各自不同的人设做同轮隐式价值→心理→行动→台词一致性核对，不增加模型调用、不建新决策器、不用关键词删掉模型有效选择；fc56278f 回归验证 v2→v3、手写字段不变、性格同源、小剧场同源、星禾隔离与之后编辑保留。
- **验收边界**：只已证实 main 源码提交；本轮未取得对应 JVM/Actions/APK 成功、设备当次责任任务原记录与行动回执，亦未做真实模型前后同样条件对照。不能承诺每句心理都无 OOC。下一步仍按 P-08b.1/B.2 用授权后的脱敏真实决策、有效责任及同轮 action/innerThought/实际回执，核查是否依旧以怜惜代替已约定的行动，并进一步追承诺→执行→下一轮，而不把睡觉例子做成一条固定强制话术。


## 2026-11-05 · P-08d.2a 多责任拆分、心事与跨日（源码提交，待验收）

- **真实症状**：主人一次托付起床、学习、休息等各自事项，截图只见一个综合长期责任及一条已取消的每日学习任务；其心事却没有各项责任。不能据截图认定已取消任务是谁取消或是否有未展示的后台计划。
- **已证链路**：模型通常在一次 commitmentChanges 决策中可输出多项。原 applyModelRecovery 只要任一活跃任务共享来源消息就过滤其它 create；兼容扫描把 draft 转回 JSON 时遗漏 repeat/permanent/证据条件；按同钟点与关键词大主题去重也可能吞不同任务。[7be1f2b](https://github.com/jiacimu-droid/lulu1/commit/7be1f2bdae44e1673cb16359d800d302a503ed14) 修正三处并补回归。原 CharacterLifeVisibility.matters 只有主观挂心、motives、探索与个人日程，没有把 CommitmentTaskStore 长期责任显示在心事页；这不是角色真的没有关心的证据，而是视图数据源缺口。
- **本轮心事修复**：直接以现有 CommitmentTaskStore 的同角色 active undated/daily 责任投影为「长期责任」卡片；不另存心理、不将一项旧综合约定自动分裂为多个伪事实。页面订阅原状态变更、取消沿原任务取消和关联安排。每日 CharacterLifeSelfReview 同样读取这些责任但不强制产生 emotions/pendingConcerns；模型是否自己真挂心留给同一人物判断。新增 role 隔离、取消排除、单次提醒排除、重复ID去重的只读回归。
- **验收边界**：本轮尚未得到新提交的 JVM/APK 成功回执、旧混合记录拆分的真实模型回合、日期更替与安卓后台提醒及角色主观挂心证据；不得将源码/单测提交当作真机通过。下一轮沿 P-08d.1/2/3 对真实原话、任务列表、pendingConcerns、planId、到点实际回执和第二天重排逐项对照。


## 2026-11-05 · P-08d.2b 每日责任次日执行与前轮未知结果解耦（源码已提交，CI/真机待验）

- **输入与根因**：主人明确反馈长期监督不是仅今天生效。原 CommitmentTaskStore 以一份 dueAt/linkedAlarmId 同时代表每日行动与上次结果复查；当上一次 WaitingForFeedback 或 Blocked，advanceDailyAfterAttempt 拒绝滚动到明天，手机虽然保留了“尚未证实”，却丢失未来日程。截图只能证明现有显示，不证明具体系统闹钟已执行。
- **原 Store 结构修复**：CommitmentTask.kt 增 dailyNextDueAt/dailyNextAlarmId 和分日期 DailyCommitmentAttempt，Encoder/Decoder 保留旧字段默认兼容。CommitmentTaskStore 让新一日执行时刻与仍待核验的上轮任务各持有独立 token，但一条总责任只保留原 taskId；到次日只重置本轮尝试次数并将旧状态/结果按原日期存档，不把“闹钟送达/用户无回复”标记为完成。CommitmentAlarmDelivery 的旧日期 review 回调只作观察和角色再决策，不覆盖今日闹钟；重启恢复跳过已真正错过的旧电话但建立未来重复，保留仍处于原两分钟可执行窗的 alarm。用户/角色通过任何有效取消入口撤回关联待办、双闹钟与旧复查，不删实际经历。源码节点 f23eaf1、8aef038、7740d26、2a7672e、f0bde63、2f2dc52、902d426、48ecc7e。
- **同轮解释与 UI**：CommitmentDecisionProtocol 消除“可独立完成的多项生活责任可合为总责任”的矛盾示例，提示模型分别考虑各项约定与每日/跨日证据（5e9f3cf）。Committed 状态/心事投影与日程 ScheduledCommitmentCard 明示下次每日时刻与上一轮待核实（8eb02b2、bca8631、abd19ba）。不改变角色本人的主观情绪，也不设第二套任务库或增加日常模型调用。
- **证据边界**：CommitmentOutcomeVerification 以任务实际时区的当天零点排除昨日证据，同时允许角色判断同一天早于闹钟发生的真实结果，不由程序仅按闹钟分钟判定无效（cfc6077、f31053f）。工具成功、通知送达和睡眠猜测依旧不证明最终效果；旧回执原文仍在 SharedExperienceTimeline 另可核实。
- **回归已提交、未宣称运行**：CommitmentAlarmRecoveryTest 扩充旧结果未知但次日有闹钟、次日原任务 ID 与旧复查并存、重启漏次如实记录、两分钟执行窗口内仍允许领取；DailyCommitmentRecurrenceTest 验双闹钟/历史 codec 与日期投影；CommitmentOutcomeVerificationTest 验昨日证据不能为今日结案、同日提前完成的候选不会被拒。提交 e897995、8aaa4eb、1ae09a1、dbf076e、9020684、3f11cd4。源文件修改后由仓库原 Refresh code index workflow 更新索引，不手改造假生成结果。
- **当前 Actions 边界**：接手/修改期间大量并发提交触发 queue，最新 902d426 的 APK run 41878588585、索引 run 41878588646 为 queued；较早 f23eaf1 的 APK run 41876701402 在查验时为 in_progress。已取消的同内容前序 CI 不代表成功；无该次最终 JVM/APK/真机结果，不保证编译及通知与系统后台行为已经验通。
- **下一步**：核最新 main 对应 APK/JVM 全链结果；有失败按第一个失败步骤修源码；真机连续三天分别核原时刻、前一日结果核查、次日独立闹钟、状态/手机屏幕授权与应用重启、用户取消/改期、角色自然跟进及真实 API 次数。旧综合责任不按关键词自动拆；仍需同一角色读原始约定后以结构化变更明确拆分和关联心事。

## 2026-11-05 · P-08a.2d 原生事件先于回执时的后续归因

- **接手与依据**：接手 main e6ce026；已阅读 AGENTS、CODE_INDEX、ROADMAP 和本文件最近记录。继续检查 CharacterCausalTrace.receiptContinuation；此前 1830860 已阻止「晚于回执的原生事件」被引用，本轮没有重做。
- **确证缺口**：原生事件发生在 t=5，工具回执发生在 t=10，角色对原生事件的决定发生在 t=7 时，旧 unverifiedReason 仅检查决定不早于原生事件，因而错误标成「回执后的本人选择」。这会把尚未收到的回执当作已经参与判断，破坏「感知→判断→执行→回执→再判断」时间因果。
- **正式修复**：[0db87d4](https://github.com/jiacimu-droid/lulu1/commit/0db87d46c5add8c655920c22d6b7e8526eb4b9eb) 在既有来源版本与时间门禁后，要求所有被称为「回执后的本人选择」的决定时间也不得早于该工具回执；过早的真实决定仍显示为「关联选择 · 后续关系未证实」，不删事件、不改变执行结果或本人决定。
- **回归**：[b14c167](https://github.com/jiacimu-droid/lulu1/commit/b14c167456b97285f985ff1c3eb39acabcfbc599) 增 Robolectric 测试：原生事件 +5、本人决定 +7、回执 +10 不得归为回执后；真正 +20 决定仍可归因。测试已提交但**本轮未实际执行**。
- **CI 边界**：旧 902d426 的 APK/JVM 41878588585 和索引 41878588646 本轮查询均 queued；不属于本次源码提交，也不证明本次成功。此工具没有拿到本次 push 的 Actions run，JVM/APK/索引、真机、真实模型、跨日均待验。依 AGENTS 原代码索引生成工作流应在 Actions 运行后核实，不手写伪造索引。
- **下一步**：先核 0db87d4/b14c167 后继的真实 JVM/APK、索引；若测试失败按首个真实错误修复。继续 P-08a.1 的跨入口 requestId/source revision 归因或 P-08c.2 剩余取消/部分送达；若只剩外部样本，转可执行的回归与性能任务。

## 2026-11-05：P-08b.1c 去除关系标题关键词对本人设定的二次覆盖

- **接手与避让**：main e6ce026，先读AGENTS、CODE_INDEX、唯一大纲、统一契约、成长框架及最新进度；查近期提交。并行窗口正修改跨日责任、心事、重复发送与trace，刷新到b0fe49b后保留0db87d4/b14c167等新改动，不重复开发。
- **前轮CI实证**：0df8e07的APK41859812891、原索引41859812905均completed/success。最新完整成功基线fc56278的APK41873143222及原索引41873142543success，job126725641840测试构建与发布均成功。历史c8c37d的41333088808实际failure，job124927310890日志为2226tests/2failed，分别CharacterLifeStoreTest.jiangDuPresetIsScopedBackedUpAndDoesNotOverwriteLaterEdits和ConversationExpressionGatewayTest.renderAndRepairKeepTheSameVoiceWithoutReceivingASecondPlannerContract；旧断言及后继修复不能混为该历史提交通过，当前后继完整基线已成功，不重演旧开发。
- **完整链路证据**：用户编辑CharacterLifeStore.relationshipAgreements → CharacterSocialRelationship.promptSection → CharacterRuntime.definition.relationshipPromptSection → CompanionModelGateway的Full/CharacterAndScenario（含精简聊天表达与电话） → 同一角色判断/表达。原链路完整保留当前约定，但baseRules另外调用PeerRelationshipContext；后者仅凭title.contains("闺蜜")并details非空，就附加「你和对方是平等朋友，不是她的助手、仆人、恋人」及不得主动转话题等固定脚本，还把details截到500字符。这与用户明确填写「闺蜜兼恋人」或自主约定的其他关系直接冲突，而且同一词命中的任何角色都会受影响。是公共装配覆盖用户设定，不是某一角色需要更温柔/更负责。
- **结构修复**：移除PeerRelationshipContext及网关第二注入入口，只沿原有完整关系投影，不新增解释器、关系标签、Store、关键词或模型轮次。星禾本人既有「平等闺蜜、不把关心变管家/恋人告白」仍在原人设和原关系字段；江渡与所有角色资料、事实历史、权限和具体工具决定均不改。抽象性格场景与Isolated仍不继承原世界关系。
- **回归**：把旧模板存在性测试改为CharacterRelationshipAuthorityTest，从权威关系读取验证星禾本人边界、其他角色不套用、完整长约定与末尾条件、用户改关系/清空实时生效、稳定人格与另一角色不变。RelationshipPreferenceGatewayTest新增通过MockWebServer捕获六次实际网关请求，验证Full私聊、Full精简表达、CharacterAndScenario电话有完整当前约定且无关键词覆盖，PersonaAndScenario/Isolated无原世界关系，编辑后旧约定消失，每次只调用一次。模拟服务器仅证明请求组装与隔离，不能证明真实模型自然度或行为质量。
- **验证与剩余**：本地仅Java，无Gradle/Kotlin/adb，未本地执行Android测试；提交后使用仓库原JVM/APK与scripts/generate_code_index.py工作流，必须查真实状态，不把排队当通过。真实模型对自定义关系的取舍、实际行动与跨轮连续性仍须相同模型/事实对照。P-08a仍缺获授权真实回合；本轮完成可实做的P-08b.1公共注入缺口。
- **下一步**：优先核本轮CI/原索引和并行跨日责任构建；若源码正常，继续P-08b.1其余入口及P-08d逐步回执，不复原这个被明确证明冲突的公共关系模板。

- **本轮发布与核验**：源码/回归 [1814b36](https://github.com/jiacimu-droid/lulu1/commit/1814b36d4513c0483e8ffd2e37854560860c49ce)。提交前核对并行main，对比确认只改网关旧入口、删除旧解释器及替换相关回归，不写人物资料。索引已由原bot生成提交16b8ea5，CODE_INDEX基准精确为1814b36，删除/新增测试均进入现行索引；该流程会从最新main生成，不要求索引job触发SHA与生成基准相同。本提交自身[索引41879637363](https://github.com/jiacimu-droid/lulu1/actions/runs/41879637363)和[APK/JVM41879637273](https://github.com/jiacimu-droid/lulu1/actions/runs/41879637273)查询仍queued；较早索引41877701823的run与jobs状态暂不一致，不用它冒充本轮CI成功。新增测试尚无运行通过结论，真机/真实模型/跨日未验。下一轮先核本轮与每日责任后继实际结果。

## 2026-11-05 P-08b.1c / 公共协议 CI 跟进：6 项 JVM 失败中的五项

- **证据**：1814b36 的 [APK/JVM run 41879637273](https://github.com/jiacimu-droid/lulu1/actions/runs/41879637273) 确认为 failed：2441 tests completed, 6 failed；代码构建本身已进入单测，失败非编译/NDK。与人物公共链相关的五项分别是 `CharacterDecisionProtocolTest.subjectiveInterpretationCanBeRevisedWithoutCreatingNewCognitionStore`、`CharacterExplorationContinuityTest.theSameSettlementContractReachesDecisionAndBothLaterProjections`、`CharacterExplorationContinuityTest.bothLivePrivateChatAndLegacyProtocolReceiveTheSameOptionalExplorationContract`、`CharacterMotivationOwnershipTest.runtimePrinciplesKeepActionEvidenceAndRelationshipSeparation`，四项都因 `CharacterDecisionProtocol.runtimePrinciples.length < 3000` 失败；以及 `ConversationExpressionGatewayTest.bestFriendIdentitySurvivesPrivatePlannerAndLeanRendererWithoutSharingOtherRolesStyle` 仍断言此前已正式删除的 `PeerRelationshipContext` 固定模板词「平等朋友」。第六项是社交邮箱测试合法联系前置条件问题，见 SOCIAL_AUTONOMY_PROGRESS.md。
- **源码根因及修复**：公共协议同时重复展开身份记录边界、工具学习、探索连续性，达到约3086字符；并非缺少角色心理能力。提交 [3ec761c](https://github.com/jiacimu-droid/lulu1/commit/3ec761c95bc3c78a33bd85c33bd9164901c88bf4) 将重复的记录归属文案压缩，并简化目标字段重复说明，保留原有长/短期心事、原日程任务引擎、学习/收藏/回执及完整 `CharacterExplorationContinuity.runtimeGuidance` 等原结构；按 Kotlin trimIndent 与嵌套常量拼装计算新长度为约2953字符（未用 Gradle 跑此版本）。不移除原测试的长度和语义断言、不增加第二轮模型调用。
- **人物设定不反弹**：1814b36 已明确删除按关系标题关键词全局附加的 `PeerRelationshipContext`，旧测试却仍索要这一删除模板的逐字「平等朋友」「不是她的助手、仆人、恋人」「不代表她在要求哄睡」等文案。提交 [8c5bf66](https://github.com/jiacimu-droid/lulu1/commit/8c5bf66184bc279806da67e3349bfda6d9c2a74e) 把真实网关请求测试改为对照当前 `CharacterSocialRelationship.agreement("lulu", "user")` 的完整角色本人关系原文，并断言旧模板不得重新出现，保留「最好的闺蜜」、同音字、星禾本人表达字段、江渡隔离和两次真实网关模式下的检查。角色现有身份/关系/人设原文未改。
- **验证与下一步**：原索引在 3ec761c 上 #41882143607 success；JVM/APK #41882143434 上次查询 pending。8c5bf66 的后继完整 JVM/APK 尚无可确认的 success，不能用 1814b36 的失败报告或别的成功基线掩盖。下次优先核包含全部三个纠正提交的最终 CI，然后再按新的失败日志定向修复；真机与真实模型的关系取舍仍未验。


## 2026-11-05 · P-08a.2e 同任务不同来源的回执归因

- **接手**：读取 main AGENTS.md、CODE_INDEX.md、唯一 ROADMAP 与人物进度；最新已核验完整 JVM/APK 41882293649 的 build job completed/success，不能据此宣称本次后继提交通过。
- **确证根因**：CharacterCausalTrace.inspect 对 UI 窗口中的 tool-router 回执以 `causeEvidenceId == current.id || event.taskId == current.taskId` 匹配。一个 taskId 下两个不同用户来源时，即使回执明确声明另一个 causeEvidenceId，仍会错误显示为当前事件的行动回执；只看 taskId 会覆盖更精确的因果归属。
- **源码**：[231a557](https://github.com/jiacimu-droid/lulu1/commit/231a557851fef8fe6e448bd5683125ccf71f5d68) 优先使用回执明确的 causeEvidenceId 和可用的 causeEvidenceRevision；只有旧回执没有 causeEvidenceId 时才允许沿原 taskId 回退。保留旧数据，不改变执行/角色本人选择，不新增模型调用。
- **回归**：[0980eef](https://github.com/jiacimu-droid/lulu1/commit/0980eef8f19fcd9b7f2bbca275eb4eb52452fbd9) 同 taskId 两来源、明确另一来源回执、正确来源回执、无 cause 的旧回执及跨角色隔离。测试已提交但未运行，不能标通过。
- **CI/设备**：查询后继提交的 workflow runs 未返回记录（该接口只枚举 PR 触发，不能据此断言 Actions 未触发）；本次 JVM/APK/索引、真机、真实模型均未证实。下一轮先核真实后继 Actions 和测试日志，再继续 P-08a.1 的其它来源/版本/时间精确关联；若无可证缺口转 P-08c.2 或下一可执行子项。

## 2026-11-05：P-08d.2c 持续目标条件在写入时截断，导致下轮语义已丢失

- **接手与CI**：main b156653。读取总纲、原索引、大纲、统一人物契约、成长框架与最新进度并查近期提交。前轮1814b36原APK41879637273确为failure（六项，已有其他窗口具体修复）；含协议长度与旧模板断言修正后的8c5bf66，APK41882293649与原索引41882293653均success，job126760322141的Test and build phone APK/Publish Lulu release均success。不重复关系模板、trace或跨日责任开发。
- **完整因果路径**：模型innerLife.motives的start/revise/pause/resume → CharacterInnerLifeStore.observe → CharacterMotiveProgress.applyPlan → 同一motives持久字段/planEvidenceId → 重启snapshot → CharacterMotiveProgress.brief → compactContext/context/CharacterMotiveDeliberation → 同一人物下轮取舍。原applyPlan保存nextStep/successCondition/stopCondition时take(180)，waitingFor时take(120)；因此尾部「未获准不联系」「没有回复不等于同意」「通知送达不算完成」等条件可在入库时永远丢掉。后续读全字段也只读得到残文。同样前缀但尾部改变的真实修订还会因clean相同而漏更新planEvidenceId。
- **源码修复**：只取消这四个计划语义字段的前缀裁剪，保持trim、reviewAfter有效时刻校验、completionKind枚举、权威Store、同角色ID、原调度与结果核验不变。没有关键词代判断、不新增强制工具/模型请求/人格或目标中心。存储完整原决定与上下文选取是不同职责，现有工作集预算仍按原机制选择；不另设无测量依据的文本上限，不冒充每次所有目标都已注入。原已丢尾部的旧记录不会自动恢复，须同一人物从真实原话形成新修订。
- **必要回归**：CharacterMotiveContinuityTest.completePlanConditionsSurvivePersistenceBothContextsAndTailOnlyRevision，沿真实observe写四个超出旧阈值的字段，检查原文与末尾条件、同ID、重启、compact/full/取舍投影一致、只读不改状态、另一角色不可见、明确复查时间与effect类型不变；再修改共享长前缀之后的完成条件，确认完整新值及新来源生效，撤回仍沿原来源使计划失效，原目标ID保留。它证明保存和契约，不证明真实模型行为质量。
- **边界与下一步**：本地仅Java，无Gradle/Kotlin/adb，JVM/APK随源码提交进入原Actions，原generate_code_index.py由已有workflow运行。真实设备/模型/跨日待验；本轮不改变aim/why或其他心理字段，不声称所有截断已清零。先核本轮测试/索引，再续P-08a.1/P-08b.1或P-08d其余真实回执和条件交接。

- **同根因的投影补齐与实际发布**：主修复/回归 [555ade6](https://github.com/jiacimu-droid/lulu1/commit/555ade65ab50d042b70ad3a15bcb3e59bad415f6)。继续核查发现CharacterMotiveDeliberation已用brief提供完整下一步，却在真实结果之后再次take(130)复述，仍可能把尾部条件截掉；[8d6cf2e](https://github.com/jiacimu-droid/lulu1/commit/8d6cf2ef6c25676a5441e18050c0978cf3c3ca4d)删除这份重复投影，并增强同一回归为每次只交接一条完整下一步。源数据与执行器未另改。原生成器工作流41898046889、41898269211均completed/success；自动索引提交7d7cd67/d861172，CODE_INDEX最新基准精确8d6cf2e。APK/JVM41898046890查验in_progress，后继41898269295 pending；尚无本轮单测/APK通过结论。提交前后刷新main并compare，仅本轮源码、测试、文档与原生成索引变更，无他窗覆盖。真机/真实模型/跨日未知，下一轮先核最新完整CI结果。


## 2026-11-05 · P-08a.2f 同任务旧回执的时间与类型边界

- **源码根因**：CharacterCausalTrace.inspect 从 UI 事件窗口按同 taskId/显式 cause 收集 tool-router 候选时，原过滤未要求 ToolResult 类型，也未排除发生在当前来源之前的记录。旧无 causeEvidenceId 回执共享 taskId 即可出现在本来源的行动回执列表，造成时间倒置或将非结果记录冒充执行结果。
- **修复**：[78089e7](https://github.com/jiacimu-droid/lulu1/commit/78089e7498035e7a56ffc32914678380b746b9eb) 在已有候选过滤中要求 evidenceKind=ToolResult 且 occurredAt 不早于当前来源；保留显式 cause 优先、旧 taskId 回退及其他独立精确 requestId 检索路径。不改角色状态、历史数据或模型调用。
- **回归**：[a1e6ba9](https://github.com/jiacimu-droid/lulu1/commit/a1e6ba9c31f6ccedf08f49d192695edd61fdc97d) 新增同任务早于来源的真实工具回执、晚于来源但非 ToolResult 的记录、有效旧回执与跨角色隔离测试。
- **验证边界**：两个提交已到 main，但未在本轮取得其 JVM/APK/索引成功证据；后继提交的 workflow 查询接口仅返回 PR 触发，空结果不代表未运行。先前 41898269295 查询仍 in_progress，不能证明本次代码。此运行环境没有仓库本地工作树，尚未手动运行 scripts/generate_code_index.py；应核自动索引是否在后继 main 生成并检查其基准 SHA，未完成前不得声称索引同步。真机/真实模型/跨日均未验。下一轮优先检查本次 CI/索引，继续 P-08a.1 的其他正式入口或 P-08c.2 取消与发泡链路。

## 2026-11-05 · P-08a.2g 精确请求的来源—决定—回执时间校验

- **证据**：本轮核最新 AGENTS、CODE_INDEX、ROADMAP、人物进度、统一人物契约和成长框架。前轮 APK/JVM [41898269295](https://github.com/jiacimu-droid/lulu1/actions/runs/41898269295) completed/failure，日志中 CharacterCausalTraceTest 的 searchResultHasSameFollowThroughFromOriginAndReceiptWithoutClaimingGoalCompletion 与 sharedTaskIdDoesNotClaimDirectCauseWithoutMatchingSourceId 失败；不能把旧成功基线冒充本轮通过，后继须重查。
- **独立根因**：CharacterCausalTrace 的精确 requestId 恢复只核回执晚于选择，持久回执 provenanceProblem 同样未核 **选择本身是否早于其所声称的来源事件**；同 requestId、causeId 和版本齐全仍可能将来源出现之前的模型决定错误归因给后来的消息。
- **实际源码**：[7c589ed](https://github.com/jiacimu-droid/lulu1/commit/7c589ed6d7755d4b64b766b4d98fed778863e5f2) 精确恢复要求 source <= decision <= receipt；[3494474](https://github.com/jiacimu-droid/lulu1/commit/3494474c0d99d4a0a9ae072bad267bb7f1514d80) 在持久回执归属复用相同时间边界，未删除真实回执、未改变角色决定或新增模型调用。
- **回归**：[0c2b4a3](https://github.com/jiacimu-droid/lulu1/commit/0c2b4a31bde12431568a890c3d15b9726858590a) 新增未来来源、较早选择、后到真实回执的测试；精确回执不得被误作该选择的成功，跨角色隔离仍保留。
- **验收边界**：本轮尚无 JVM/APK/索引成功证据；当前环境无本地仓库执行脚本，必须核原 Actions 索引是否生成并以源码 SHA 为基准，不手工伪造。真机/真实模型/跨日未验。先检查后继 CI 的首个失败及上述两条旧失败是否仍在，再续 P-08a.1 / P-08c.2。

- **后继索引核对**：原生成索引已在 main 自动更新，CODE_INDEX 基准提交为 `0c2b4a31bde12431568a890c3d15b9726858590a`（对应本轮全部源码与测试）；此事实只证明索引文件同步，尚未取得 Android/JVM/APK 通过证据。

## 2026-11-05：P-08a.2h 先修真实CI：晚到任务关联与历史来源回执

- **接手与真实失败**：main ebdd371；读取必读规范、原索引、大纲、人物进度与近期提交。8d6cf2e的APK41898269295实际failure，不能延续上一轮pending表述；后继0c2b4a3的APK41908471007同为failure，job126853253924日志直接给出三条CharacterCausalTraceTest失败：searchResultHasSameFollowThroughFromOriginAndReceiptWithoutClaimingGoalCompletion:508、linkedTaskReceiptMustBeActualResultAfterItsSource:100、sharedTaskIdDoesNotClaimDirectCauseWithoutMatchingSourceId:349。原索引41908470912success不等于APK通过。
- **已证第一根因/链路**：原始消息先入SharedExperienceTimeline.record（无taskId）→同文同时间后补任务关联→现有早返回分支只补session/kind/channel，丢掉taskId→inspect无法按同任务恢复合法旧回执。不是让测试凭空指定任务：正式record接受此元数据参数，本应与session一样可补齐。现仅在原taskId空且新值非空时补task_id，沿原owner/expectedRevision/tombstone校验；不变正文、时间或revision，不重跑抽取，不让重复回调重绑既有任务。
- **已证第二根因/链路**：来源编辑→revision递增→inspect候选先要求回执causeRevision等于当前版本→后面的「历史版本的行动回执」分支永远拿不到旧版实际回执。现精确causeId相同且0<旧版本<当前版本时，仅进入历史告警，不调用receiptContinuation，不把旧执行/后续算成对新文字的回应；老回执可早于本次编辑，但未知版本、未来版本、当前版本仍要满足原时间门禁。明确指向其他来源的回执继续排除，无cause的旧同任务回退继续保留。
- **旧断言纠正**：sharedTaskId旧测试仍要求显示causeEvidenceId明确属于别处的结果，与231a557及sharedTaskDoesNotAttachAnotherSourcesExplicitReceipt冲突。更新为本卡不能借该回执，同时在实际回执自身卡仍显示原执行失败；不恢复错归因、不删测试或真实历史。
- **必要回归**：TimelineLedgerTest.lateTaskMetadataEnrichesCanonicalEventWithoutRevisionOrReassignment经原SQLite记录路径覆盖空关联补齐、stale expectedRevision/跨角色拒绝、同请求重放/另一任务不可抢占、原文版本不变、备份和删除不复活。CharacterCausalTraceTest.sourceEditKeepsOnlyOlderExplicitReceiptAsHistoricalWithoutBorrowingFollowThrough覆盖编辑时间晚于旧回执、当前真实新回执、未知早回执/未来版本拒绝、不得借用旧后续、只读不消费与角色隔离；原三条失败及其正例保留。
- **验收边界与下一步**：本地无Gradle/Kotlin/adb，不能本地跑Android测试。提交后核真实JVM/APK与原generate_code_index.py流程；优先等本轮结果，有失败继续按日志定位，不再在红CI上叠加trace功能。人物设置、本人决定、原数据与模型调用次数不改；真机/真实模型/跨日尚未验收。

- **源码发布/原索引实证**：[ab1dac1](https://github.com/jiacimu-droid/lulu1/commit/ab1dac13d6a2d68c646724289c3319ccc9ab5862)已提交main，提交前刷新并比较无他窗冲突。原索引[41916963410](https://github.com/jiacimu-droid/lulu1/actions/runs/41916963410) completed/success，job126879072365日志明确执行python3 scripts/generate_code_index.py并从ab1dac1生成推送；自动索引61d553b，CODE_INDEX基准精确ab1dac1。新[APK/JVM41916963280](https://github.com/jiacimu-droid/lulu1/actions/runs/41916963280)实际in_progress，job126879071900的Test and build phone APK尚未完成，不能声称新增回归/构建通过。
- **超时证据单独保留**：复查旧41908471007完整日志，三条断言之后测试仍继续，20:51:46 UTC最后可见ResidentDailyContinuityTest.withdrawnOrEditedResidentDecisionCannotResumeUnsentEffects STARTED，21:26:30 UTC收到“Test and build phone APK has timed out after 40 minutes”。最后STARTED不等于已证明该测试是死锁源；缺线程栈/结束记录，不能只把失败归成三条断言。若新构建仍卡住，先取真实线程/测试进度证据再修，不随便延长超时、删除测试或改角色行为来掩盖。源码本轮未添加新的常态模型调用；真机、真实模型、跨日均未验。


## 2026-11-05 · P-08a.2i：拒绝来源出现前的同版本本人决定（只读追踪）

- **接手与既有 CI 核实**：先读最新 main 的 AGENTS、CODE_INDEX、路线图、人物进度及 CharacterCausalTrace/SharedExperienceTimeline/CharacterCausalTraceTest 正式代码。上一轮 ab1dac1 的 [41916963280](https://github.com/jiacimu-droid/lulu1/actions/runs/41916963280) build job126879071900 已 completed/success，Test and build phone APK、Collect APK、Publish Lulu release 均成功；原索引 [41916963410](https://github.com/jiacimu-droid/lulu1/actions/runs/41916963410) success。此前 41908471007 失败不再当作新版本失败，也不能用此成功证明本轮新提交。
- **根因**：inspect 的精确请求回执匹配已校验决定时间不得早于原消息，但用于卡片展示的 relatedDecisions 只按 causalEvidenceId 从本人档案和最近窗口聚合，仍可能把「当前来源版本且时间早于来源发生」的旧归档选择直接显示成这条消息触发的本人选择。UI 只读投影与精确回执时间校验的因果契约不一致。
- **正式代码**：[3420e794](https://github.com/jiacimu-droid/lulu1/commit/3420e794b3cea69baae23744b55aae5970865cfb) 在同一 CharacterCausalTrace.inspect 投影处仅过滤「明确当前 revision 且可解析时间严格早于当前来源」的决定；旧版决定仍按历史标记、无时间/无版本旧档案保留不确定性。原 CharacterInnerLifeStore/时间线不修改，不新增模型调用/任务或新 Store。
- **回归**：[642b0df](https://github.com/jiacimu-droid/lulu1/commit/642b0df830e3f13e6fbfaff31f414bf22ffe4511) 增 CharacterCausalTraceTest.currentVersionChoiceCannotPredateItsSourceEvenWithExactEvidenceId：前于来源的当前版本选择不可显示；来源之后的决定可见；旧无时间无版本选择标记不确定；只读历史不变、跨角色隔离。测试源码已提交，但**本轮未获得针对 642b0df 的 JVM/APK 实际运行成功**，不能标为测试通过。
- **下一步**：核 642b0df 的后继 APK/JVM 与索引，失败先看首个真实日志；之后 P-08a.1 查其他正式入口关联或 P-08c.2 的取消/部分送达边界。真机、真实模型与跨日因果质量仍未验。


## 2026-11-05 · P-08a.2j 修复红 CI 的时间归因诊断回归

- **接手与真实 CI**：读取 main 四份入口文档、CharacterCausalTrace 和现有 CharacterCausalTraceTest；最新 642b0df 的 [APK/JVM 41919182717](https://github.com/jiacimu-droid/lulu1/actions/runs/41919182717) completed/failure，job 126889651883 的 Gradle 日志显示 2448 tests completed, 1 failed，唯一失败 CharacterCausalTraceTest.exactReceiptCannotProveDecisionMadeBeforeItsSource:1298。索引 [41919182996](https://github.com/jiacimu-droid/lulu1/actions/runs/41919182996) completed/success，不能替代 JVM。
- **真实根因**：3420e794 为防止早于来源的当前版本选择被冒充本人选择，将其从 relatedDecisions 过滤；同时意外移除了此前 executionRequestLines 对该不可能时间归因应展示的「主动作 · 回执归属未证实」诊断。测试第一条 assertTrue 因此失败。不是执行器真正成功，也不是测试本身错误。
- **源码修复**：[3ba3281](https://github.com/jiacimu-droid/lulu1/commit/3ba32816ad34c006afa24ea9f91b397d8f59f3e7) 把同来源当前版本且时间早于来源的选择单独分流：仅展示未证实诊断，不进入精确 requestId 回执恢复、真实送达或成功归因；其他当前/历史/无时间选择沿原路径。权威人物档案、消息、回执均不变，不增加模型请求。已有失败回归 exactReceiptCannotProveDecisionMadeBeforeItsSource 同时断言诊断可见、成功不冒认、独立回执不借用、跨角色隔离，保留并直接用于后继 CI。
- **验证边界**：本轮新提交的 JVM/APK 尚未取得成功结论，不能以 642b0df 的失败或索引成功代替；真机、真实模型、跨日未验。下一轮先核 3ba3281 的 Actions；若通过，继续 P-08a.1 其他入口的真实关联审计。


## 2026-11-05 · P-08a.2k 心声时间来源归因

- **先核真实 CI**：前轮 3ba3281 的 APK/JVM [41927755186](https://github.com/jiacimu-droid/lulu1/actions/runs/41927755186) completed/success；索引 [41927754781](https://github.com/jiacimu-droid/lulu1/actions/runs/41927754781) completed/success。仅覆盖前轮代码，不代表本轮测试通过。
- **根因**：CharacterCausalTrace.inspect 的 selectedThoughts 按 evidenceId/stimuli 及 stimulusRevisions 投影心声，旧版和来源修订有提示，但当前版本的想法若记录时间早于其引用来源，仍显示为「本人想法 · 仍被保留」，与同文件决定/回执的时间因果校验不一致。此处只修解释投影，不修改原始心声、人物决定或数据存储。
- **正式修复**：[80ab933](https://github.com/jiacimu-droid/lulu1/commit/80ab933e868491e83f686fe4bee36808c262f768) 对可解析的当前版本时间矛盾显示「因果未证实 · 来源时间不符」，保留原文以供诊断；正常后续、历史版本及缺时间的旧档案继续原有路径。没有新模型调用、关键词语义判断或角色人格变更。
- **回归**：[acb8582](https://github.com/jiacimu-droid/lulu1/commit/acb8582522eca3b4dc7a1a6c5f2ce2f6cb9371f7) 增加早于来源/晚于来源/无时间旧档案、只读不删、跨角色隔离。新增测试已提交但本轮尚无针对该提交的 JVM/APK 成功证据；真机与真实模型未验。
- **下一轮**：先查 acb8582 之后的 APK/JVM、原代码索引是否完成；若红灯优先取失败日志并修首个实质问题。然后继续 P-08a.1 审计实际私聊、群聊、后台的 turnTraceId→送达→工具回执→后续；仅发现可证缺口才改。注意其他窗口可能并行推进，写入前刷新 main 与文件 SHA。

## 2026-11-05：P-08d.2d 本轮新目标的独立动作与真实回执归属

- **接手与CI**：main 9f876bd，先读AGENTS、原索引、唯一大纲、人物进度、统一契约与成长框架，查近期提交。ab1dac1的41916963280与41916963410均success；最新完整成功源码3ba3281的APK41927755186、原索引41927754781均success，job126916536623的Test and build phone APK、Publish Lulu release均success。旧41908471007超时没有在这两次成功构建中复现；没有据此猜测旧超时根因。其他窗口继续改trace，已刷新并保留其源码/文档，当前轮不再叠追踪补丁。
- **完整实际链路/根因**：CharacterThoughtActionHandoff.actions解码innerLife.motives[].nextAction → CharacterInnerLifeStore.observe把start.ref保存为creationRef并生成真实UUID → OrderedCharacterActions/电话副动作 → ToolRouter执行与projectCausalLinks → recordActionResult及个人日程motiveId → 下轮本人的同一motives。旧解码只从父项id取motiveId，start尚无id，明明有ref却被丢；去重causalLinks也漏motiveRef，把不同新目标选的相同工具合为一个。两条执行入口只传motiveId，未在目标保存后解析ref。另查到ProactivePerceptionRuntime.performAction只把onlineUnread.messageIds交给执行器，无用户消息的自主决定虽有causalEvidenceId却未带下去，主/副回执都可缺原始来源。
- **源码修复**：父项明确start且没有step显式归属时继承ref；把motiveRef加入同动作兼容性比较与merge。CharacterMotiveProgress.bindStepMotive复用原resolveMotiveId，按已有本人motives、明确ref与程序提供的本轮来源ID唯一绑定，不从文字或最近目标猜；复制动作，保留原JSON，日程args与回执使用同一目标ID。前台observe之后统一绑定，供电话、沉默工具与延后文本步骤共同使用；Ordered执行时以同角色Store再绑定尚未绑定的引用。后台performAction补充既有causalEvidenceId参数，只作缺失用户来源时的真实回执回退，也供独立步骤解析；不冒充用户发言/回复送达/任务完成。
- **必要回归**：CharacterThoughtActionHandoffTest扩展不同因果所有者矩阵到motiveRef，新增两个父级新目标相同工具不得合并、ref继承、显式归属保留、原决定不变。CharacterMotiveContinuityTest新增纯绑定边界矩阵（不同来源/空角色状态/暂停/多目标歧义/显式ID）及nestedNewGoalActionsReachRealScheduleAndIndependentReceiptsOnce：真实原时间线观察、原observe生成目标ID、两项Ordered本地工具（个人日程/发圈）、原计划ID与逐步ToolRouter回执/来源归属、未冒充目标达成、原请求重放不重复写日程或目标结果。没有付费模型测试，模拟或组装回归不能证明自然度。
- **范围和诚实缺口**：本项修明确ref的独立steps/nextAction，不生成缺省ref、不把任意念头自动变任务，不建立新目标Store或收费理解轮次。顶层action=tool与nested nextAction完全相同的旧提前return仍需专项复核主动作元数据合并，当前不能声称所有目标关联变体已验通。原始角色人格、权限、条件、请求幂等、预算和完成证据规则不变。已有完整上下文与协议可选择不同人物行为，模型是否这样做仍需真实对照。
- **交接**：本地无Gradle/Kotlin/adb；提交后核原JVM/APK与generate_code_index.py结果。路线同步最新完整CI，已验通的P-08a.2a–j施工流水移回专项历史，完整trace的P-08a.1/3与他窗未验条目保留，避免继续重复开发已通过的诊断。下一轮先查本轮CI，再核P-08d.2d真实模型/手机回执，或复核明确尚缺的主动作镜像归属。

- **实际发布/并行核对**：主修复与回归 [24781fa](https://github.com/jiacimu-droid/lulu1/commit/24781fa04b828973ba6b6f6cec3d98133bb938a7)；[1a17c74](https://github.com/jiacimu-droid/lulu1/commit/1a17c74e6967469c1770b4f19e591abbd9c820cc)使Ordered仅对确有未绑定motiveRef的步骤读取本人的心理Store，普通步骤不增加这份本地读取。提交前比较并保留他窗CharacterCausalTrace/回归及文档新改动；未覆盖用户角色设定。原索引41936194698/41936349357均completed/success，自动提交2ec3d35/0bfc8c9，CODE_INDEX基准精确1a17c74。旧APK41936194961已cancelled，后继[41936349268](https://github.com/jiacimu-droid/lulu1/actions/runs/41936349268)查询pending，**本轮回归/JVM/APK尚无通过结论**；不可用3ba3281的成功代替本轮，也不可把这里的测试源码描述当成已运行成功。真机、真实模型和跨日仍未验。下一轮先核41936349268，有失败按实际日志修，不复写已验通的旧trace补丁。

## 2026-11-05：P-08a.CI-01 · 全量测试静默取证（可执行 CI 工程修复）

- **接手事实**：先读最新 AGENTS、CODE_INDEX、ROADM​​AP、人物进度、统一契约/成长框架及真实 CI。旧 APK/JVM [41936349268](https://github.com/jiacimu-droid/lulu1/actions/runs/41936349268) build job 126960351667 completed/failure；在 00:27:18–00:27:23 UTC 日志快速列出大量 ResidentDailyContinuityTest STARTED 后无完成事件，到 01:02:18 UTC 才被原 40 分钟步骤超时终止。无法仅凭末个 STARTED 归因特定测试、同步锁、模型网络或 GC；不能删测试、延长计时或冒称整项已经修好。
- **已证检测缺口**：原 Gradle testLogging 只输出 STARTED/FAILED，不显示 PASSED；Actions 仅记录拼接日志，长时间静默时没有 JVM 线程/进程数据，持续红 CI 后无法判定真正阻塞位置。
- **实际工程提交**：[`63431ea`](https://github.com/jiacimu-droid/lulu1/commit/63431eab74674f3eb5eddbeb317dc3594d8641a1) 新增静默观察脚本 `scripts/ci_gradle_stall_watch.sh`；[`5d46f2d`](https://github.com/jiacimu-droid/lulu1/commit/5d46f2d29001753edfd12e8091371c85a90306ed) 增模拟活跃日志和静默触发的 shell 回归；[`00186e5`](https://github.com/jiacimu-droid/lulu1/commit/00186e5d2807d12010d4180fd65d3e699d7d062d) 在原 `release.yml` 的完整 JVM/APK 步骤旁启动/停止观察器，不接管或中止 Gradle，静默 180 秒采集不含命令行凭据的进程资源/JVM Thread.print，诊断进入既有 ci-logs 失败产物；[`1ba310b`](https://github.com/jiacimu-droid/lulu1/commit/1ba310b3ac785778ec58f420ee293fc019621dd8) 增 Gradle PASSED 事件。原 40 分钟超时、单元测试集合/断言及原发布流程保持。
- **验证分层**：在独立 shell 环境执行等价脚本的 `bash -n` 和模拟「持续心跳→持续静默」回归，结果通过；这是脚本级冒烟，不是 Android JVM/APK 运行成功。GitHub 文件内容与 workflow 集成已再次 fetch 确认；并行窗口已在 main 提交居民记忆费用优化，未覆盖其内容。最新 Actions 未取得包含这四项提交的可核验完整结果，不把上游或旧成功当新版本通过。
- **下一轮**：优先获取包含观察器的新 CI：通过即记录本版未复现且返回 P-08a.1；再现静默则读取 `ci-logs/gradle-stall-diagnostics.log`，以具体线程堆栈、锁等待或资源指标定位真正根因，再修正式源码/测试。不得把这次取证能力升级描述成已彻底修复 40 分钟超时。真机、真实模型、跨日未验。


## 2026-11-05 · 后台 SSE 写出断流与居民记忆额度（根因源码已提交，待 CI/真机）

- **09:44 现场诊断**：PerformanceLog 的江渡「后台主动感知·后台定时」报告 transport=sse、contextMode=Full、systemChars=79179、currentChars=41428、historyMessages=0、images=0、bodyBytes=263226；约 12.925 秒后写 POST 正文报 timeout，阶段 WRITE_BODY。无法证明服务端已经完整接收或完成计费，故仍不自动重发同一可能收费的请求，不归因 VPN。原 RelayRequestPressurePolicy 大于280000才提前保护，请求低于阈值。c1fd18a 将阈值调整为220000（基于本次实际 263KB 丢传），保护任务规则/人设/关系/世界书/核心记忆/未完成责任与本轮用户输入，只精简可选较早投影，必须实际 savings >4096 才使用；a731013 回归，17e982e 保留真实尝试投影字节审计，不持久化隐私原文。压缩并不保证第三方断流不会再发生。
- **09:48 独立预算现象**：居民「记忆·连续记忆提取」提示每日AI决断预算耗尽、0ms、未发送模型请求。ResidentDailyPolicy 实际按 resident: 的所有模型请求计量（包括记忆与修正）；旧网关在生成提示词/确认配置之前先扣每日次数。为维持主人配置的每位居民费用上限，不给记忆隐藏例外或额外收费：3f84731 将原子 claim 放到网络调用交接前，避免本地 prompt 组装失败消耗预算；1e6b316 增 read-only allowance，ef8e981 让 LocalMemoryRepository 两条提取循环在预算耗尽时正常延期、保留未处理批次/原始事件，避免0ms空拒绝泛滥；73cf293 补额度边界与跨日回归；f39dc8a UI 显示“每日AI请求总上限”并解释记忆提取、社交回应等均计入。
- **验收界限**：源码提交和静态检查并不代表 JVM/APK、实际远端 SSE 和居民持续记忆都已成功。后续以最后合并的 main sha 对应 Actions 的首个真实失败步骤为准；真机侧验证大请求在本轮是否真的被精简、内容与角色策略完整、请求中断不重复扣费、居民日额度前后没有多发送，以及次日原记忆批次有没有被正常调用处理。CI 取消不算通过，截图是旧安装版观察，不等于新包真机结果。


## 2026-11-05 · S-02.1b 居民记忆预算续接队列误结案

- **源码根因**：旧 LocalMemoryRepository 遇居民额度用尽时仅刷新提示并返回；ChatMemoryAutomation.drain 据此错误地 complete 记忆提取 job，虽然未处理原文仍在 SQLite，却丢失后续自动唤醒的脏水位。仅靠页面不报错不代表下一日会继续。
- **修复**：无额度抛非网络的 ResidentMemoryBudgetDeferred，经 ChatMemoryAutomation 分流为 MemoryExtractionJobStore.defer（不计失败/不消除队列）；保留原 id/revision 与并发新消息，存 deferredUntil 到下个本地自然日零点；到期后后台常规队列再处理。手动 flushNow 亦登记相同延期。不绕开用户总请求额度、不新增请求模型、不删原始数据。
- **测试**：提交 f3590d3、0a4e011、263375a、a218430、0963140、1687bc3、600c340、861334e、392013a、be8a344，测试真实 resident 0 额度事件、手动 flush 持久队列、次日触发时间、入队并发改版和冷启动续接。旧输入预算修复基线 APK/JVM 41952677579 completed/success；本批仍需自身同 SHA APK/JVM 结果与真机隔日验收，旧 cancelled 不作为结果。

## 2026-11-05 · P-08d.2d 主动作镜像归属续修（待 JVM/APK）

- **接手基准**：main 30703d7；先读 AGENTS、CODE_INDEX、唯一路线、人物专项、统一契约/成长框架，核实际 CharacterThoughtActionHandoff、AutonomousActionWireProtocol、前台/电话与后台主动作解析。并行窗口的居民额度/持久队列改动保留。
- **源码根因**：原 Handoff.actions 的 nested nextAction 与顶层 action=tool 相同工具/参数时提前 return，避免重复执行，但因此丢弃内层 start.ref→motiveRef 和挂心 threadId→concernId；主动作 ToolRouter 只取顶层 Decision 的归属，导致真实执行成功却不属于明确目标/挂心。单纯删除 return 会重复调用有副作用的工具。
- **本轮代码**：[6a2a878](https://github.com/jiacimu-droid/lulu1/commit/6a2a878ed0bfc0dfc30b899b166b87f610a5a121) 增只读复制的 withPrimaryActionOwnership，按同一工具、相同规范化 args、依赖标记及兼容归属，将确实镜像的内层目标/挂心显式归属并到顶层；冲突/歧义不猜。 [c67ee0c](https://github.com/jiacimu-droid/lulu1/commit/c67ee0cb4404c9723328e48f35b01f4b5b03beb5) 在既有 AutonomousActionWireProtocol.normalize 主入口使用，供后台和前台/电话共享，不增加收费模型调用、不动角色原设定、不会派第二份镜像动作。
- **新增回归**：[53858af](https://github.com/jiacimu-droid/lulu1/commit/53858af087e5fc06a9e1fa78a427c53fcfea26df) CharacterThoughtActionHandoffTest.mirroredPrimaryToolKeepsNestedMotiveAndConcernWithoutDuplicateEffect，覆盖 start.ref、挂心 threadId、原模型 JSON 不变、同动作 sideActions 为空、不同显式归属不覆盖且仍保留独立动作。**本轮未取得该 SHA 的 JVM/APK 通过结果**；GitHub commit combined statuses 空列表不等于 CI 通过，真机、真实模型与跨日均未验。
- **下一轮**：先核含 53858af 的 Actions 是否成功或首个编译/断言失败，失败优先修；再检查含本次归属字段的真实主动作 ToolRouter 回执与重放幂等，必要时补全链 Robolectric 回归。P-08a.CI-01.2 静默诊断亦继续核新运行；不能用旧 success 替代新构建。

## 2026-11-05 · P-08d.2e 主工具镜像问题归属（源码与回归已提交）

- **接手**：main c9012f4，按 AGENTS/原索引/唯一路线/人物进度、统一人物契约与成长框架读取；检查 CharacterThoughtActionHandoff、AutonomousActionWireProtocol 与现有镜像目标/挂心测试，避免重复 P-08d.2d。
- **已证根因**：actions() 已将 curiosity/curiosities[].nextAction 的原 threadId/sourceEvidenceId 投影到执行步骤，并对与主工具镜像的动作提前 return；但 withPrimaryActionOwnership() 只收集 decision、pendingConcerns、innerLife.thoughts/motives，遗漏 curiosity 两种槽位。结果主工具本身未继承问题归属，后续动作还可能被当成独立效果。不能靠删除去重解决，否则同工具可能产生重复副作用。
- **正式修复**：[50b32d3](https://github.com/jiacimu-droid/lulu1/commit/50b32d3f8e84537e357e0af6885416c5d6b82843) 复用同一显式 question 投影（去 nextAction 的只读副本），在 primary 合并前纳入 curiosity 和 curiosities[]；保留原 sameAction 的工具/规范化参数/依赖/归属兼容性检查与多目标冲突不猜原则。没有改原始 JSON、存储、人设或模型调用。
- **回归**：[c2e98fc](https://github.com/jiacimu-droid/lulu1/commit/c2e98fc67166156231f3021e240d479699e0c2fc) 增 mirroredPrimaryToolRetainsExplicitQuestionAndNeverExecutesTwice：单一 question 的 threadId/sourceEvidenceId 归属进入主工具、无镜像 side action；与显式其他 question 冲突时保留独立动作；两项不同 question 不冒认唯一主归属；原始模型输出保持不变。测试源码已提交，不宣称 JVM 运行通过。
- **CI 与下一轮**：GitHub commit combined statuses 对 c2e98fc 返回空，fetch_commit_workflow_runs 亦无可核验 run（此接口只筛 PR 触发），不代表构建成功或失败。旧 41936349268 completed/failure 是先前版本 40 分钟超时，不用于本轮验收。下一轮先核含 c2e98fc 的 push APK/JVM 真实日志，若红先修编译/断言；随后 P-08d.2e 补真实 ToolRouter 主动作 receipt/重放幂等集成回归，再继续 P-08a.1 跨入口链。真机/真实模型/跨日未验。


## 2026-11-05 · P-08d.2f 主动作镜像归属导致 CI issue 769/770

- **实测失败**：run 41975920197 为 2457 tests completed, 1 failed，CharacterThoughtActionHandoffTest 中 mirroredPrimaryToolKeepsNestedMotiveAndConcernWithoutDuplicateEffect 缺 motiveRef；run 41982399574 为 2458 tests completed, 2 failed，再加 mirroredPrimaryToolRetainsExplicitQuestionAndNeverExecutesTwice 因歧义步骤被一起吞掉。对应 CI issues #769/#770，单测/生成 APK 未通过，不是 Gradle 编译失败。
- **源头**：AutonomousActionWireProtocol.normalize 会将部分真实注册工具从 action=tool 变成对外友好 action=moment/message/call/journal，旧 CharacterThoughtActionHandoff 只有 action=tool 才合并根主动作同一 motive/concern/curiosity 归属；共享工具参数的不同 question 没有被主动作继承，仍被粗去重吞掉。
- **修复与回归**：7a3f82a 添加已注册友好工具别名与实际主工具同一性检查；47ba849 在主归属和 steps 去重共用；85006c2 对内层动作必须先由主动作继承全部显式 causalLinks 才可省略第二次执行，否则保留独立归属；be1ca97 将相同只读归属合并引入电话/前台原 parsePlan。0ad901e 和 6d69965 补朋友圈/消息/日记/电话、不同主归属、原 JSON 不变和双发拦截回归。保持原唯一心理/事实来源，不加模型调用，不偷改人物。
- **验收边界**：已推 main；新 JVM/APK run 41984588215 在提交时为 pending。索引 success 不是 APK 成功，旧 failure issue 在后继测试通过前不要手动关闭，实机真实工具和回执仍需对照。

## 2026-11-05 · P-08d.2f 后继 CI 单失败与主回执归属兼容性

- **实际 CI**：[41984588215](https://github.com/jiacimu-droid/lulu1/actions/runs/41984588215) job 127105049514 completed/failure，2461 tests / 1 failed，非编译错误或超时；唯一失败 `CharacterThoughtActionHandoffTest.friendlyPrimaryToolAliasesKeepOwnershipAndCannotDoubleExecute` 在第174行缺 `motiveRef`。此前两条镜像回归已 PASSED，故不是所有主工具都失效。
- **本轮修改**：[feb2ae1](https://github.com/jiacimu-droid/lulu1/commit/feb2ae103a96ed5d493c717ed5a903d863fd9769) 在原 `CharacterThoughtActionHandoff.withPrimaryActionOwnership` 内对候选按主动作已累积的明确因果 owner 逐个校验和合并，允许同一真实回执同时带不同字段的 motive/concern，但同字段冲突仍不能覆盖或猜测。沿用现有回归 `friendlyPrimaryToolAliasesKeepOwnershipAndCannotDoubleExecute` 与 `mirroredPrimaryToolKeepsNestedMotiveAndConcernWithoutDuplicateEffect`；没有增加模型调用或新 Store。
- **验证限制**：本提交尚未取得对应 JVM/APK 运行结果，不能声称该失败已修复。若仍失败，须读取新运行同一断言与 normalized JSON，追 `normalizeStep` 参数投影或 `isPrimaryToolChoice` 的实际差异，不能仅因源码推理勾完成。原有 40 分钟静默问题本次未复现（5m50s 结束），不代表根因已消除。真机、真实模型、跨日未验。
- **下一轮**：先查 feb2ae1 及后继主线 CI 的首个失败；通过后继续 P-08d.2f 真实 ToolRouter 回执/重放幂等，再 P-08a.1 全链。

## 2026-11-05 · P-08d.2g 部分兼容归属被后续冲突整批抹除

- **接手与证据**：main 278cebd；已读 AGENTS、CODE_INDEX、唯一路线、人物进度、统一人物契约/成长框架及实际 Handoff/normalizeStep/相关测试。前轮 [41984588215](https://github.com/jiacimu-droid/lulu1/actions/runs/41984588215) 为 2461 tests / 1 failed，不能拿来判断后继 feb2ae1 是否成功。最新主线在读取时尚未取得可核验的对应完整 APK/JVM 运行结果。
- **已证根因**：withPrimaryActionOwnership 原本逐项合并与主工具相同效果的嵌套动作。先成功合并 concernId 后，若后续候选 motiveRef 与主工具已有 motiveRef 冲突，旧 `return decision` 把已合并的兼容 concernId 一并丢掉。应当只拒绝冲突候选，不撤销已证兼容归属；独立冲突动作仍由 actions() 保留，不推测它和主工具是同一目标。
- **正式修复**：[e00edd1](https://github.com/jiacimu-droid/lulu1/commit/e00edd1bc0b9ed851574cabab4ef19e86e000863) 在原归属聚合器跳过不兼容候选，继续保留此前兼容归属；[52c914c](https://github.com/jiacimu-droid/lulu1/commit/52c914c4af8ef4f9dcecdce10bbe7e48dcd70d04) 增 laterConflictingGoalCannotEraseEarlierCompatiblePrimaryConcern 回归：原始主目标、先合并挂心、后冲突新目标的独立步骤、输入不可变。没有增加收费模型调用、修改人格或清理历史。
- **交接**：roadmap [93ddf8e](https://github.com/jiacimu-droid/lulu1/commit/93ddf8ef7ce293546c60609a1fd7c14152841d2d) 标明源码/回归提交和 CI/真机未验；下一轮先核含 52c914c 的 JVM/APK，失败按实际首个失败修，再做 P-08d.2g ToolRouter 主/独立步骤回执与幂等集成验证，随后继续 P-08a.1。不能把源码提交当 JVM/APK 通过。

## 2026-11-05 · P-08d.2g 镜像主动作真实日程/回执/重放集成回归

- **接手与证据**：最新 main 起始 dd46ecd，按 AGENTS、CODE_INDEX、唯一路线、人物进度、统一契约/成长框架读取，核 CharacterThoughtActionHandoff、AutonomousActionWireProtocol、OrderedCharacterActions、CharacterMotiveContinuityTest 和旧 41984588215（该 run completed/failure，2461 tests / 1 failed，属于旧 SHA）。e00edd1/52c914c 已修部分兼容归属并有纯解析回归；尚缺同一目标新建后真正工具执行、持久日程、回执及重放的连接验证。并行窗口在本轮未被覆盖。
- **新增可执行回归**：[99830ef](https://github.com/jiacimu-droid/lulu1/commit/99830ef00454bb010d3f84e3c8ead406dbb808d0) 在 CharacterMotiveContinuityTest 加 mirroredPrimaryPlanCarriesNewGoalIntoDurableReceiptAndReplayIsIdempotent：用已存在的真实数字世界来源创建同轮 start.ref，模型顶层 plan_personal_activity 与 nested nextAction 完全镜像；通过正式 normalize 把 motiveRef 合到主工具、确认无第二份副动作；observe 生成真正目标 ID，再经原 OrderedCharacterActions→ToolRouter 写入一条个人日程和一条带同一目标/来源的工具回执，同 requestId 重放后不增加日程/回执/目标 outcome，输入 JSON 和执行 steps 均不变。使用本地工具，不发收费模型请求。此测试检验共用执行器的镜像主步骤，不代表私聊或后台主工具适配器已经真机验通。
- **CI 与下一步**：此提交尚无可核验的对应完整 JVM/APK 结果，不能宣称回归已运行成功；旧 41984588215 failure 不适用于本次提交。下一轮优先查含 99830ef 的 push CI 的首个失败，若失败修正式源码/测试；若通过，再做前台/后台主 ToolRouter 实际入口、冲突独立步骤回执/重放的集成验收，然后回 P-08a.1。真机、真实模型、跨日仍未验。


## 2026-11-05 · P-08c.3a 私聊短气泡应由人物自己选择

- **体验反馈**：用户以截图中「四点了……下午……脑子是不是……」为例，希望人物像真人聊天一样在自然停顿处分开真消息，而不必每一泡先完成一个新话题。用户明确强调：决定权在角色，不是程序按逗号自动拆，也不是系统教所有角色固定三泡模板。
- **根因与边界**：结构化回复原本已由 ModelStructuredOutput.completedReplyBubbles → characterOutgoingBubblePlan(preserveAuthoredBubbleBoundaries=true) → appendCharacterMessageIfSourceCurrent 按模型每个 bubbles 元素逐条送达，展示层不会按标点二次拆；问题在多个模型提示入口含「只有独立意群才另起气泡」的过强限制。前台 lean 表达又没有带上本人已经学习的 UserPreference/UserHabit，实际渲染模型无法据已知对方阅读偏好自主决定停顿。
- **纠正先前偏差**：31630ae 的统一三气泡示范已按用户最新要求由 3bc541e 恢复为非示范化人物自主表达；没有数字配额、标点拆分器或角色通用语气模板。CharacterExpressionGuide 仅澄清同一意思里可自由选择口语停顿；ProactiveDecisionPrompt、LuluDeviceToolBridge、TransientConversationStyle 移除「一泡必须完整独立意群」误导（4368fd4、1591f11、48729ee）。CharacterRuntime.leanExpressionContext 从原 CharacterDevelopmentStore 选择当前角色真实有效的用户偏好/习惯并在 CompanionModelGateway 的 lean 表达调用注入（acc4140、a1c6a45），不绕开人物原有学习审核、不另建内存库或收取额外请求。
- **回归源码**：CharacterKnowledgeIsolationTest 的 2019780 核仅本角色有可追溯用户偏好，其他人不知道，表达提示不含模板；ConversationExpressionGatewayTest 的 caf247c 以 MockWebServer 验证真实请求携带该角色的阅读偏好而另一角色没有。不硬拆旧聊天、语音或结构化半句。源码和测试已提交；对应 SHA JVM/APK/发布与真机角色真实输出需单独验收，不能把 CI success 当成模型会自动按某个准确泡数输出。这个反馈发生在 ChatGPT 讨论中，不能伪装成角色已在其私聊中听到过；只有本人见过的真实来源才写入角色档案。

## 2026-11-05 · P-08d.2h 显式主工具镜像

- **接手**：读取最新 AGENTS、CODE_INDEX、唯一 roadmap、人物进度、统一人物契约、成长框架与 CharacterThoughtActionHandoff/CharacterMotiveContinuityTest/已有纯解析测试。main 初始 2b42bd2（并行更新中）；此前 99830ef 的 nested nextAction 主工具日程/回执测试已存在，不重复添加。历史 41984588215 为旧 2461 tests / 1 failure，不能代表新提交。
- **证实的源码缺口**：原 withPrimaryActionOwnership 仅 collect(decision/挂心/innerLife/curiosity) 的 nextAction，不收集模型在 steps/sideActions 显式重复列出的主工具；actions 又原样加入显式 steps/sideActions，顶层主工具已由入口执行后仍会把同效果步骤交 Ordered 执行，或让明确的 motiveRef/concernId 留在重复动作上而非主工具回执。
- **正式修复**：[c47fb4a](https://github.com/jiacimu-droid/lulu1/commit/c47fb4a618de1b5c4a7715b094b243790ecaafa1) 在现有归属聚合器纳入显式 steps/sideActions 的规范化动作，只按原 sameAction/累积兼容 owner 规则合并；actions 对已由主工具覆盖全部明确归属的完全镜像跳过，冲突归属独立保留。不新增 Store、收费模型、硬编码意图或清理历史。
- **回归**：[d746e9a](https://github.com/jiacimu-droid/lulu1/commit/d746e9a0e195116cb1109bc0a6b7f2a2d320a0ee) explicitStepsAndSideActionsMirroringPrimaryDoNotExecuteTwiceOrLoseOwners：显式主步骤目标 ref、侧步骤挂心 ID、同效果冲突目标分别验合并、保留和原始 JSON 不变。测试源码已提交，**本轮没有可核验的对应 JVM/APK 完成结果**，不能声称通过；尚未覆盖真实外部工具副作用或手机。
- **下一轮**：先查 d746e9a 后继 push Actions 的首个真实失败或成功（PR-only 查询/combined statuses 为空不代表通过）；若失败按日志修。随后做 P-08d.2h 前台/后台真实主动作→ToolRouter 回执与重放集成，若新代码已有他窗实现则只记录证据。P-08a.CI-01.2 静默诊断继续按新 run 核实。真机、真实模型、跨日未验。

## 2026-11-05 · V-01.3a 视频触摸不应抢话并额外收费（待 CI / 真机）

- **根因**：LuluVoiceCallSession.onLive2DTouch 原先合并 950ms 触摸后，若 replyGeneration 未变即将真实触摸证据送到 onExternalToolResult。CallExternalResultPolicy 只在空闲状态下放行，而队列会继续等待。角色正在回复时的触摸因此可能在 TTS 结束后单独触发第二轮模型响应，抢在主人下一句话之前并造成额外 TTS 与打断费用。replyGeneration 相等不能证明现在需要一轮新的发言。
- **正式代码与提交**：[c7dccae](https://github.com/jiacimu-droid/lulu1/commit/c7dccae53486b35c522e3966b9ff853876402d04) 移除触摸→额外工具回执排队的直接调用。依旧记录本次真实接触（部位、方式、力度、总时长）到 InteractionSignalBridge→SharedExperienceTimeline，触摸动画不变。handleUserSpeech 在真正用户语音或电话内已入库文字回合前主动 flush 尚未到期的同通话触摸批次，随后由原 CharacterPerceptionContext.pending 在本来就会发生的模型请求里合并理解；不新增轮询、规则解释器或影子记忆。
- **回归**：[545e588](https://github.com/jiacimu-droid/lulu1/commit/545e5887e2f533d8c0a88ffffcb4a7722f16ace0) Live2DTouchAndNativeProbeTest 扩充：真实触摸可进入同一角色未处理感知，已有真实用户轮次可接入并认领，同一证据不再被重新算作新刺激。**本轮已提交源码和测试，未取得本次完整 JVM/APK 成功、真机调用次数或第三方实际费用记录**。
- **交接**：路线 V-01.3a 保持待验，先确认含源码/测试的实际 push Actions JVM/APK 是否成功，再用真机执行「角色思考中触摸→角色念完→主人尚未讲话」「连续触摸→立刻讲话」「讲话与触摸交错」「切电话/挂断」四组；检查实际模型/TTS 请求、触摸后下一轮感知及播放器停止/取消。其他外部工具回执、既有哄睡续讲、叫醒承诺和电话安静定时观察不在本次禁用范围。

## 2026-11-05 · P-08d.2h.1 友好主动作别名的显式镜像回归（源码提交，JVM/APK 待验）

- **接手与去重**：先读 main 最新 AGENTS、CODE_INDEX、唯一路线、人物进度、统一契约与成长框架；核 CharacterThoughtActionHandoff.actions/withPrimaryActionOwnership、AutonomousActionWireProtocol.normalize、CharacterThoughtActionHandoffTest、CharacterMotiveContinuityTest。既有 c47fb4a/d746e9a 已处理 plan_personal_activity 的显式 steps/sideActions 镜像，99830ef 已覆盖主工具写真实日程与重放；不重做原修复。并行窗口已在 main 修改居民请求额度、POST 写入超时和视频触摸，不覆盖其代码。
- **本轮可执行缺口**：现有友好工具别名测试仅对 innerLife.nextAction/pendingConcerns 镜像作归属校验，显式 steps/sideActions 镜像仅测试普通 plan_personal_activity。发布朋友圈、发私信、电话主工具经 normalize 变为 moment/message/call，主动作走原专门送达入口，重复副动作则可能产生外部副作用；缺该组合的明确回归。此项是测试矩阵缺口，不把未复现的实际双发声称为已证生产故障。
- **实际交付**：[0e71fea](https://github.com/jiacimu-droid/lulu1/commit/0e71feac331d3c2b1077f94d82329f737df1dbf5) 在原 CharacterThoughtActionHandoffTest 新增 friendlyPrimaryAliasesSuppressOnlyFullyOwnedExplicitMirrors，逐项验证 publish_moment/send_private_message/start_call 的友好 action 映射、显式 steps 目标 + sideActions 挂心归入同一主工具、无第二份 Ordered 动作；冲突主目标仍保留独立步骤，输入 JSON 不变。没有改正式执行策略或增加收费模型调用。Roadmap [10663cf](https://github.com/jiacimu-droid/lulu1/commit/10663cfe6108b39947d8214e49855739715fb28d) 将该子回归源码标记提交，P-08d.2h 整体验收仍未勾。
- **CI 边界**：旧 [41984588215](https://github.com/jiacimu-droid/lulu1/actions/runs/41984588215) completed/failure 是旧 2461 项 / 1 失败，不能代替本次结果。对 0e71fea 查询 commit combined statuses 为空、PR-only commit workflow runs 为空；这些接口无法证明 push Actions 状态。新测试尚无实际 JVM/APK 通过凭据；真机、真实模型、跨日与真实外部发送均未知。
- **下一轮**：先查包含 0e71fea 的最新 push APK/JVM，失败按第一条实际断言/编译错误修；通过后继续 P-08d.2h 前台/后台主动作→ToolRouter 或专用消息送达的真实回执与重放幂等集成，随后回 P-08a.1 全链。若本项已由并行提交完成只记录证据，不重造。 


## 2026-11-05 · Resident paid budget admission and SSE POST body write timeout (source committed; CI and device pending)

- **Observed root cause**: the resident settings expose a daily paid-request cap (default 2) and an independent future agenda cap (default 5). The original daily planner spent one request but could create five appointments, each requiring another paid role decision at due time. The gateway eventually rejected exhausted calls, while non-memory wake entry points had no quota-aware scheduler preflight. A one-shot due worker could return success with an unresolved plan, and local semantic memory maintenance could also try paid batches after quota exhaustion.
- **Source change**: `fbf12b7` adds `remainingModelRequests` and `affordableNewAgendaItems`. `b92dd25` uses the real remaining request budget (including already-planned due decisions) to limit NEW future agenda rows, while allowing any immediate actions chosen in the same model response. New paid daily/due/social decisions preflight allowance, without marking an attempted call or a model choice when none was sent; catch-up scheduler avoids empty-budget wake storms. `5ca0152` appends a durable next-budget work item for a due resident plan without altering its ID, original date, status or real receipt. `40cc3bf` preflights optional weekly semantic memory review, preserving candidate/cursor state. `31aa26d` clarifies the cost relationship in the existing resident-management screen. `3ebb5e7` tests remaining quota, budget 0/1/2 and planned future wake cost. Atomic `claimModelDecision` remains the authority for concurrent paid calls; no unbounded retries or hidden extra quota are permitted.
- **Separate transport cause**: a subsequent 18:26 real log showed `bodyBytes=198664`, `WRITE_BODY` timeout after about 19.765s. It was below the configured >220000 byte precompact condition, so compaction was not triggered by that threshold. `ModelPostTransport` had explicit connect/read budgets but no explicit body write timeout, leaving OkHttp's shorter default; `53b6bcf` sets a bounded 60s write timeout while retaining `isOneShot`, disabled redirects and no transport retries; `2e20001` adds a regression assertion. Nothing changes `RelayRequestPressurePolicy` or trims persona, current user input, relationship, commitments, core memories, or TOP/NORMAL/BOTTOM world books.
- **Follow-up same-turn changes**: `27cebe0` adds a Robolectric persisted resident-plan regression to ensure a quota-deferred wake never changes the plan ID, original dueAt or empty completion receipt; `06ee9d4` schedules the existing quota-aware daily catch-up after the owner finishes adjusting the resident AI cap, so increasing it after 08:00 need not wait until tomorrow for a missing same-day plan. No new paid call is forced; the existing scheduler, owner-claim and active-role checks still decide eligibility.
- **Validation boundary**: GitHub connector source/commit inspection and test source additions only. The actual post-change JVM/APK, Actions index generator completion, Android WorkManager defer/resume across real local midnight, live model quota races, relay upstream receipt and billing need evidence from the corresponding latest main commit and installed APK. A failed 198KB request does not prove that content was too large or that the relay or VPN was responsible. The next follow-up is run-aware CI failure triage first, then 0/1/2 resident budget device matrix and 198KB+ POST logging without blind paid replays.


## 2026-11-06 · P-08d.2h.2 跨间隔重复副动作不得被静默吞掉

- **接手 main**：e013970a72aef0834aa0cebc9352bd69f4453a56。核 AGENTS、CODE_INDEX、LULU_EXPERIENCE_ROADMAP、本文与 CharacterThoughtActionHandoff、OrderedCharacterActions（CompanionActionRuntime）、既有测试；确认之前 c47fb4a / d746e9a / 0e71fea 的主工具镜像修复和回归已存在，不重复开发。
- **真实缺口**：CharacterThoughtActionHandoff.actions 的 sideActions 合并在完整 result 中搜索同一工具+参数，允许「搜索 A → 阅读 B → 再搜索 A」把最后一步并入最早步骤。OrderedCharacterActions.execute 按顺序执行并依赖 previousCompleted，因此此类跨步骤合并既吞掉明确第二次尝试，也改变后续 requiresPreviousSuccess 所依据的前驱执行结果。此项独立于尚未确证的「主工具镜像被过滤」风险。
- **正式修复**：[06809cd6](https://github.com/jiacimu-droid/lulu1/commit/06809cd6a29fb2f9c36ec410717b99d152fecb4d) 限定 sideActions 只与结果末尾紧邻的兼容动作合并；不改变主工具归属、其他动作来源或原始模型决定。[dcfb3ba](https://github.com/jiacimu-droid/lulu1/commit/dcfb3ba98489fb131c5f8457e26cfa377b495221) 新增 nonAdjacentSideActionIsNotCollapsedAcrossAnInterveningStep，覆盖跨间隔重复保留、相邻去重、独立理由和原 JSON 不变。
- **验证**：旧 APK Actions [42029112180](https://github.com/jiacimu-droid/lulu1/actions/runs/42029112180) completed/success 只覆盖 84d0d2d；新 [42066569144](https://github.com/jiacimu-droid/lulu1/actions/runs/42066569144) 为 06809cd6 的 in_progress（查询时），不能宣称 dcfb3ba 或文档后继 HEAD 的 JVM/APK 通过。无本地 Android 构建结果；真机、真实模型、跨日及实际 ToolRouter 副作用未验。没有额外收费模型调用。
- **下一轮**：先刷新 main 与新 push Actions，修首个实际失败；再按 P-08d.2h 检验主工具镜像被过滤时的 requiresPreviousSuccess 前驱与主工具成功/失败回执，补执行器级失败用例，避免只做解析断言。之后追前台/后台真实入口与重放幂等。


## 2026-11-06 · P-08d 旧晚复查在心声中、未证实已执行

- **用户真机观察**：11月5日23:35心声写“23:45复查、洗漱后尝试哄睡电话”，23:30后无可见联系。所附日程选中11月6日，23:30为次日定时责任，不足以直接证明11月5日23:45是否曾保存复查。私人心声文字不是可执行动作；实际模型JSON和工具回执尚未导出。
- **已证实控制流缺口**：`ProactivePerceptionRuntime` 在线窗口到期时，只有本人选择sleep且`CompanionSleepHandoff.ensure`失败才会触发有界恢复。角色选择continue但未能续租、或在线决定不符合结构时，即使本人的正式责任仍没有未来复查，原路由也可能就此结束。后一日的每日闹钟并不等于前夜未知结果的复查。不能仅凭截图断言当晚必经该分支。
- **提交至main**：`OnlineContinuationPolicy.needsCommitmentRecovery`在窗口真正结束、未被新窗口接管且`CompanionSleepHandoff.uncovered`仍有未交接正式责任时允许原调度器补救；`scheduleUnfinishedCommitmentRecovery`仍为原WorkManager六分钟延后和每角色每小时一次的有限同角色决策机会，保留旧队列触发兼容；不是自动将心声词句注册为23:45、拨电话或判定主人已睡。代码提交：`49893e7`、`da5698d`、`4480730`、`4f808ae`。
- **回归已写，待CI**：`OnlineContinuationPolicyTest`覆盖续期失败/新窗口覆盖/已有排期的恢复判定；`OnlineChatExperienceTest`以真实明日闹钟和当晚独立结果复查验证同一任务跨日不混淆（`633fe8c`、`1d83fd6`）。未取得Android单测、APK或真机通过证据，不能标记已验通。
- **后续**：核实最终head的CI，并获取23:35真实模型`onlineDecision/steps/commitmentChanges`、工具`schedule_commitment_review`回执及`nextCheckAt/linkedAlarmId`，区分模型未选择、工具未保存、闹钟未交付或窗口退出未交接；不凭心声关键词代人物作语义决定。


## 2026-11-06 · P-08d.2b/2 重启后错过一次性结果复查也必须续接

- **接手来源**：主人11月5日23:35心声提及23:45复查，实际后续未见；前轮已修在线租期结束时没交接正式责任的补救，截图不能证明是否有真实`schedule_commitment_review`模型指令/回执。
- **新证实的源码断点**：`CommitmentTaskStore.reconcileAfterRestart` 对 `WaitingForFeedback` 已错过的静默结果复查，只清理失效 `nextCheckAt/linkedAlarmId` 并保留“未知”，没有像原到点主动作错过那样交给 `scheduleMissedCommitmentReview`。重启恢复的其他无后继任务也只针对 `repeat=daily` 安排有界补救：一次性承诺若曾执行但效果待证实，会留在 Store 而丢失主动再次考虑的机会。
- **直接改动 main**：`CommitmentTask.needsUnverifiedCommitmentRecoveryCandidate` 将一次性/每日、到期、仍等待反馈或Blocked的**恢复候选**归一，仅描述客观状态；`CompanionSleepHandoff.uncovered` 随后用现有真实Alarm/关联日程检验是否仍缺后继，避免把第二天独立每日执行当作旧结果复查。重启时已错过的verification计入原`missedByRole`并沿`scheduleMissedCommitmentReview`立刻交回同一人物决策（不是自动重拨或判定完成），其他旧结果未证实且没有后继的once/daily任务走已有每小时一次、六分钟后的有界恢复。已有真的后续、其他窗口已接管、正在运行的执行器仍不重复收费唤醒。更新错过事件文案，使原执行和结果复查不混为一谈。源码 commits `aa6cef4`、`bbcad13`、`12a35fe`。
- **回归源码**：`DailyCommitmentRecurrenceTest.oneTimePromiseWithUnknownOutcomeIsAlsoEligibleForRestartRecovery` 覆盖一次性未证实/Blocked、未来/完成/未到期边界；`CommitmentAlarmRecoveryTest.coldStartKeepsMissedOneOffVerificationUnfinishedAndEligibleForRecovery` 检查真实Store重启恢复之后原ID、未知状态、过期闹钟清理和后继缺口仍可识别；提交 `585342f`、`0dd4e42`。没有增加影子Store、关键词解析、私人心声自动任务、额外常态模型轮次。
- **CI界限**：旧push `06809cd6` 的 APK Actions `42066569144`（job `127382156671`）已 completed/failure，2481项单测1项失败：`CharacterThoughtActionHandoffTest.stepsAndSideActionsBothExecuteOnceAndKeepTheirIndependentReceipts`。**该失败发生在本轮提交前且不直接证明本轮复查修改有错**；当前新提交的 JVM/APK、设备跨午夜/后台闹钟与原23:45模型实际选择尚待对应run验证。不能把旧 success 或已写测试当作现版通过。
- **下一步**：优先用对应head的Actions和失败日志核对新测试，必要时追真实重启恢复Worker/AlarmManager回调与工具receipt；采集23:35那轮 `onlineDecision/steps/commitmentChanges`、到点闹钟和`linkedAlarmId`，区分未提交、未保存、没触发或后台不再决策，不能从心声硬推已经安排。


## 2026-11-06 · P-08a.CI-01.4 编译阻断：恢复调度器旧名称

- **接手 main**：25ab7c9605442c88e3e3765786411781d25dfe86；核 AGENTS、CODE_INDEX、roadmap、本文最新记录，及 ProactivePerceptionRuntime / ProactivePerceptionScheduler 正式源码和 push Actions。
- **实际失败**：[42069227302](https://github.com/jiacimu-droid/lulu1/actions/runs/42069227302) 对应 0dd4e42，build 在 `:app:compilePhoneKotlin` 失败，日志明确 `ProactivePerceptionRuntime.kt:2537:42 Unresolved reference 'scheduleUnfinishedSleepHandoff'`；APK 未生成、JVM 全量测试未跑完。恢复调度器已改为 `scheduleUnfinishedCommitmentRecovery`，执行回执处残留旧调用。不是网络、模型或真机故障。
- **正式源码**：[e12149c](https://github.com/jiacimu-droid/lulu1/commit/e12149c1317dab200d654423ec3d050594c554dc) 将旧调用改为既存恢复入口；不新建恢复器、不增加收费模型调用、不修改角色选择或历史。Roadmap 记录 [1bcc98a](https://github.com/jiacimu-droid/lulu1/commit/1bcc98ada38b75c996a9adfbe1c13e3a1e0d8754)。
- **验证边界**：本环境无法访问 github.com 克隆仓库，未运行 `scripts/generate_code_index.py` 或本地 Gradle；需原 Actions 索引生成和新 push APK/JVM 确认。修复是明确符号引用错误，但未宣称编译通过。真机、真实模型、跨日、外部回执均未验。
- **下一轮**：刷新 main 和同文件 SHA，先核 e12149c 后继 Actions 首个失败；通过后回 P-08d.2h 主工具镜像成功/失败、`requiresPreviousSuccess` 与真实入口幂等，不能仅靠解析测试关闭整项。


## 2026-11-06 · P-08d.2h.3 有序动作去重与 CI 失败断言

- **接手**：main 92e1bdd91bb55174bd2c263bb69adb49e5fce70b；核 AGENTS、CODE_INDEX、roadmap、本文、CharacterThoughtActionHandoff 正式源码及测试、最新 push Actions。此前 e12149c 已修复 Kotlin 未解析引用，不重复修改。
- **实际 CI**：[42076430945](https://github.com/jiacimu-droid/lulu1/actions/runs/42076430945) 对应 e12149c completed/failure，build job 127417729369 的原始日志显示 2487 tests completed, 1 failed：CharacterThoughtActionHandoffTest.stepsAndSideActionsBothExecuteOnceAndKeepTheirIndependentReceipts，expected 2 but was 3。失败在 testPhoneUnitTest，未完成 APK；旧 Kotlin 编译阻断已不再出现。代码索引 [42076431019](https://github.com/jiacimu-droid/lulu1/actions/runs/42076431019) success。
- **根因与正式源码**：原 06809cd6 已使 sideActions 仅合并紧邻同一动作，旧测试却仍期待「search→journal→search」被压成两步；此外 nested nextAction 的 add() 仍全列表找重复、跨中间步骤吞掉角色再次选择的工具。源码 [1beed430](https://github.com/jiacimu-droid/lulu1/commit/1beed4303f9f87d9ce2e1c5d1414c0a9705ebdae) 使嵌套动作也只与紧邻且条件/归属兼容的步骤合并。测试 [a8afe867](https://github.com/jiacimu-droid/lulu1/commit/a8afe867532070486fa5b8f2c0d1ab03a14064d9) 更新旧断言为三步，挂心归第二次搜索，并新增嵌套跨间隔再执行回归；原模型 JSON 不变。
- **验证边界**：源码/测试已提交，未获得新 HEAD JVM/APK 结果，不能宣称通过；尚未真机验证主工具失败/成功、条件副动作、外部 ToolRouter 独立回执及重放幂等。不新增模型调用，不改角色人设或历史。
- **下一轮**：刷新 main 与 [Actions](https://github.com/jiacimu-droid/lulu1/actions)，优先修新 push 首个实际失败；成功后继续 P-08d.2h 前台/后台主工具镜像及 requiresPreviousSuccess 执行器级验证，随后 P-08a.1 全链。


## 2026-11-06 · P-08d.2h 顶层主工具镜像与间隔后的再次行动

- 正式源码 `CharacterThoughtActionHandoff.actions` 过去对每一个显式 `steps/sideActions` 都独立判断是否与顶层主工具相同，未考虑顺序。因此顶层 A、显式镜像 A、独立 B、再次 A 中最后一个 A 也被删除，导致本人已选择的第二次执行消失。
- 源码提交 `12b712269a09ea529d11ebb9b22df093bc1ae536`：在显式步骤解码时记录已经承接的主工具镜像以及其后不同工具的间隔；只有存在前一个镜像和中间不同工具时保留再次 A。原本冲突归属与无间隔镜像行为保持；不增加模型调用。
- 测试提交 `9c684d4d923b1238ca34658e9cdaeb0535093fd4`：顶层 A / 显式镜像 A / B / A 的独立第二次执行和原模型 JSON 不变。**测试仅提交，尚未取得 JVM/APK 成功结果**，不标记完成。
- 尚待：对应 main 的 Actions、主工具失败/条件依赖与 ToolRouter 实际回执集成测试；`withPrimaryActionOwnership` 对显式重复动作的归属收集还需进一步检查是否可能错误归到第一次回执。真机与真实模型未验。
- 下一轮：先核对应提交 CI，失败先修；再沿 `withPrimaryActionOwnership` 的顺序归属与 `requiresPreviousSuccess` 验证，确认后补根因级测试。不要将本次局部修复标为 P-08d.2h 整体通过。

## 2026-11-06 · P-08d.2h.5 主工具后非镜像重复执行与回执归属

- 核对最新 main 四份必读文档、正式 `CharacterThoughtActionHandoff` 与回归；`9c684d4` 对应 APK/JVM Actions 42105819153 和索引 42105818973 已 completed/success，但只是前轮基准。
- 根因：顶层主工具 A 后显式 B→A，旧 `suppressExplicitMirror` 只在已遇到一次镜像后才记住 B，因此第二次 A 仍被删除；`withPrimaryActionOwnership` 还会扫描全部显式步骤，将 B 之后 A 的 owner 提前归入顶层首次回执。
- 正式源码 `76951454`：不同工具出现即标记间隔，后续相同工具保留为独立执行；主工具归属只吸收间隔之前的显式镜像，不改原模型 JSON、不增加收费模型调用。测试 `287c9305`：B→A 仍两步，后一次 motiveId 不污染顶层，原决定不变。
- 新 JVM/APK 与真机/实际 ToolRouter 回执尚未确认；主工具失败、requiresPreviousSuccess 及嵌套后续仍待独立验收。下一轮先核最新 Actions 失败，再继续 P-08d.2h 执行器级依赖/重放，随后 P-08a.1。


## 2026-11-06 · P-08d.2h.6 嵌套行动在显式异动作后的独立执行

- 接手最新 main 49323a5（索引自动提交），核 AGENTS、CODE_INDEX、roadmap、人物进度与 CharacterThoughtActionHandoff/OrderedCharacterActions/既有测试。前轮 7695145/287c930 已覆盖显式 B→A，不重复实现。
- 新确认源码断点：顶层 A → 显式 B → 嵌套 motive.nextAction A 时，actions() 不顾 B 无条件过滤嵌套 A；withPrimaryActionOwnership() 先收集嵌套 owner 后扫描显式 B，又把后一次 A 的 motiveId 错记在顶层首次回执。这与此前显式重复动作的修复不同。
- 正式源码提交 91b23e1e09061407667efd1bccc25a722c25fd4c：嵌套主工具镜像仅在没有显式异工具间隔时过滤；主工具归属收集保留显式镜像候选，但在显式异工具出现后不再吸收随后才执行的嵌套 owner。模型原 JSON 不变，不加模型调用。
- 回归 5a8fa37744700d7e3418361ba342055413075dd1：顶层 web_search、显式 read_book、嵌套同参数 web_search，第二次必须保留且 motiveId 仅归第二次；原决定不变。Roadmap f7bfca9c06bd979afcbb67129aa529d67f215d1b。
- **验证边界**：目前只确认 GitHub 提交成功，未获得本轮完整 JVM/APK、Actions 成功、真实 ToolRouter 回执/幂等、失败条件依赖及真机结果，不能称通过。GitHub commit combined status 返回空 statuses，不能据此判定 Actions 成功或失败。
- **下一轮**：先查包含 5a8fa37 的 push Actions 和第一条实际失败，失败先修；通过后针对主工具失败/成功、requiresPreviousSuccess、嵌套与显式混排的执行器级回执和重放幂等补集成回归。继续人物因果优先，不重复修改已验项目。

## 2026-11-06 · P-08d.2h.6 CI 失败测试契约修正

- 复核最新 main 495b395、四份必读文档、CharacterThoughtActionHandoff.actions/withPrimaryActionOwnership 和原 JUnit 测试。Actions [42123821611](https://github.com/jiacimu-droid/lulu1/actions/runs/42123821611) 在 5a8fa37 执行 2491 tests / 1 failed；失败点 CharacterThoughtActionHandoffTest.kt:526，旧断言期待顶层 web_search → 显式 write_journal → 嵌套 web_search 只剩一个副步骤，和已修复的非相邻重复执行顺序语义冲突。
- 测试提交 [60dbac0](https://github.com/jiacimu-droid/lulu1/commit/60dbac01382e05f3cb2b434096594c09eeb450b8)：改为明确断言两个副步骤 write_journal、web_search，避免将真实第二次工具选择误写成重复镜像。**本轮没有改生产代码**，因为失败是旧测试期待值，当前源码已保留第二次行动；不能以此宣称实际工具回执与幂等已验。
- 新提交的 JVM/APK 和索引待 Actions 最终结果；真机、真实模型、主工具失败/条件依赖及回执幂等仍待验。下一轮先核 CI；若成功，选 P-08d.2h 的主工具失败、requiresPreviousSuccess 与真实 ToolRouter 执行器级回归，再推进 P-08a.1。

## 2026-11-06 · P-08d.2h.7 非法有序槽后的主工具重试

- 接手 main 99dc60b4，复核四份入口文档、CharacterThoughtActionHandoff 正式源码及已有回归。旧 42130874590（60dbac0）completed/failure，不能视作最新 main 失败；42138079257（0b51892）查询时 pending。
- 真实断点：actions() 对 steps/sideActions 中 JSON null 保留失败槽，却没有更新主工具镜像的中断标记；后续同参主工具选择可能被当作已执行镜像删除。此时执行器失去失败槽后的独立动作机会。
- 正式源码 e44e20774a866356bfd8a25282b88b78e0852d80 在两类非法槽上标记中断；回归 5ebf6b1737abe8c4aa882f53efff4b3e6911298f 检查 null 失败槽与后续同参工具均保留，原决定不变。Roadmap e2a13f8d35efb585b2bf116ed5b68f9f992c3f2d 记录该子步骤。没有新增模型调用、历史删除或人设修改。
- 验证：尚未取得本提交对应的 JVM/APK 或索引成功结果；实际主工具失败、条件依赖、重放幂等与真机/真实模型仍待验。下一轮先核最新 Actions，再补执行器真实回执回归或修首个 CI 失败。

## 2026-11-06 · P-08d.2h.8 失败前驱与独立重放集成回归

- 复核 main 四份必读文档、OrderedCharacterActions.execute 与 AutonomousActionBoundaryIntegrationTest。已有失败主工具/独立副步骤覆盖，但尚缺未知有序步骤失败→依赖跳过→独立成功→相同 requestId 重放的完整测试。
- 回归提交 `477fcc2f0adabba2fb2f8347708f02cf0f064579`：通过原 OrderedCharacterActions 与真实 ToolRouter/MomentsStore 验证失败前驱不触发依赖发布，独立发布成功，同请求重放不产生第二条发布。未修改正式执行器，尚未证明该新增测试通过；如 CI 失败先查第一条真实断言，不为绿灯削弱执行契约。
- Roadmap 更新 `302778e463c56f9aa618c50c703e437558f17a98`。本轮没有取得新增提交的 JVM/APK 最终结论，真机、真实模型、后台主工具失败与跨日仍待验。
- 下一轮先查包含 `477fcc2f` 的 Actions，若通过继续主工具成功/失败的条件副动作真实回执及前后台场景；若失败按真实日志修复。P-08d.2h 整体仍未验收。


## 2026-11-06 · P-08d.2h.9 主工具失败后的条件依赖与独立恢复回归

- 接手 main `e46e824748ad92180eef5499d8fa75e0ca80f75c`；复核 AGENTS、CODE_INDEX、roadmap、进度和 `OrderedCharacterActions.execute`、`AutonomousActionBoundaryIntegrationTest`。上一轮 `477fcc2` 对应 APK/JVM Actions 42145130918 与索引 42145131120 均 completed/success，不重复开发未知步骤失败的既有覆盖。
- 仍缺的独立边界：已有回归使用 `initialCompleted=true`，未覆盖调用方主工具真实失败后传入 `initialCompleted=false` 的条件首步。新增 `failedPrimaryResultSkipsDependentStepButIndependentSuccessUnlocksItsOwnFollower`：主工具失败后依赖步骤不得发布，独立步骤成功后自己的后继依赖应恢复执行，同一 requestId 重放不能重复产生朋友圈副作用。沿原 `OrderedCharacterActions → ToolRouter → MomentsStore`，无模型调用或生产语义修改。
- 测试提交 [1cd26c0](https://github.com/jiacimu-droid/lulu1/commit/1cd26c01a8ffa14c22638a401d9a75d2cf56268f)。**新提交 JVM/APK 尚未确认**，不得把上轮成功扩称到本轮；真机、真实模型、前后台主工具失败真实回执仍待验。
- 下一轮先查 `1cd26c0` 对应 Actions，失败先定位第一条真实错误；通过后继续 P-08a.1 跨入口 turnTraceId/sourceMessageId/taskId 关联审计，P-08d.2h 入口真实回执与设备验收仍保留待验。

## 2026-11-07 · P-08a.1a 缺失权威事件时不再按正文猜测因果来源

- 接手前已读取 main 的 AGENTS.md、CODE_INDEX.md、Roadmap、人物进度及 LuluDeviceToolBridge/QqChatReplyEngine 正式入口。起始 main 为 372a3073c8cc4db6f0daf24341d987cc3951677b；Bridge blob SHA 3ae43aa62f19b5c892f80479d4ebe889f0aa24ba。QqChatReplyEngine 的普通前台回复已有 userEvidenceIds，故不将所有前台聊天判为缺失来源；此修复针对未携带权威事件的兼容入口。
- 已证根因：Bridge.respond 先用最近 40 条 UserStatement 的正文包含关系猜 groundingEvidenceId，再把它传入 commitmentEvidence、心理记录和工具因果参数。相同原话、引用、截断片段都不具备消息身份，可能让旧用户事件冒充当前原因；不能用模糊内容匹配确定事实归属。
- 正式源码提交 [55a273b](https://github.com/jiacimu-droid/lulu1/commit/55a273bb30faea081a52b9787dcf939dc96ea55d)：统一选择明确重考虑事件、电话静默观察或已按 roleId/UserStatement 过滤的入口事件 ID；否则空归属，不新造 Store、模型调用、角色决定或用户消息。回归提交 [2ae52bb](https://github.com/jiacimu-droid/lulu1/commit/2ae52bb737342f7efd1d1edc1776d8f34eeae0d0)：同文不同消息 ID、缺来源 ID、重考虑/静默优先级。
- 路线图记录提交 [aebea38](https://github.com/jiacimu-droid/lulu1/commit/aebea388498e785e2a8056aff76086083b8c7469)。本轮 GitHub 内容 API 提交成功；运行环境无法解析 github.com 克隆仓库，未在本地执行 Gradle。新提交的 JVM/APK、Actions 最终结果、真实消息送达、真机与真实模型均**未验证**；旧 42160450440 success 只覆盖此前 0c7428b，不代表本轮通过。
- 下一轮：先核新 main 的 push Actions，若有编译或测试失败先修第一条真实错误；随后继续 P-08a.1 的电话/后台来源与落库回执链，并检查 direct-xhs 在无事件 ID 时使用 userText.hashCode() 的跨轮 requestId 冲突，先验证真实入口再修。P-08a.1 整体不勾。


## 2026-11-07 · P-08a.1b 直接小红书读取的跨轮幂等身份

- 接续 P-08a.1a，复核 AGENTS、CODE_INDEX、roadmap、人物进度与 LuluDeviceToolBridge 正式入口。原 directXhsResult 在无权威来源时将 userText.hashCode() 作为 ToolRouter requestId；相同正文的独立请求可复用上一轮的幂等身份，即使并非同一用户事件。正常前台 userEvidenceIds 传递保持原状，不把所有入口认作缺失来源。
- 正式源码 [e63c9ac](https://github.com/jiacimu-droid/lulu1/commit/e63c9ac4f73bcb4b59058e4ef9569e051a11df59) 使用经过校验的 groundingEvidenceId，否则使用 respond 现有唯一 turnTraceId 作为本次调用身份；不按正文猜测、不新建持久 Store、不增加模型调用。相同已验证事件重试继续使用相同 requestId；无来源的独立同文调用不会互相吞并。无来源时若整个 respond 被外层重启且未传 diagnosticTurnTraceId，无法证明跨进程重试的同一逻辑身份，仍待传输层权威事件覆盖。
- 回归 [6c756b5](https://github.com/jiacimu-droid/lulu1/commit/6c756b5dc403a5da6d1ef14bf37200cb7e79f6a7) 检查同文不同调用、同调用同 trace 重试、真实事件跨 trace 重试及不同事件隔离；Roadmap [c4bcc3e](https://github.com/jiacimu-droid/lulu1/commit/c4bcc3e9716dd1b50fc541e800bbb6c59de2e771) 记录子步骤。
- GitHub 文件提交成功；未获得本 SHA 的 JVM/APK Actions 通过证据，也未本地执行 Gradle，不能将测试已写等同测试通过。真机、真实小红书读取、持久消息回执、跨轮心理归属仍待验。
- 下一轮刷新 main/相关 blob SHA 和 push Actions，若失败修第一条真实编译/断言；通过后继续 P-08a.1 电话/后台的权威事件→决定→实际送达/失败→持久回执→下轮召回全链，优先复用既有身份而非新建 trace。

## 2026-11-07 · P-08a.1c 承诺来源校验边界

- 复核最新 main 四份必读文档、Bridge 的 `originalUserEvidence`、`commitmentEvidenceIds` 与 `CommitmentDecisionRuntime.apply`。发现虽已按 roleId/UserStatement 从 SharedExperienceTimeline 解析真实消息，但原始 `userEvidenceIds` 非空时仍原样进入承诺记录与去重请求键；其中未被解析的跨角色、失效 ID 可能冒充本轮承诺来源。
- 正式源码 [`ddd5e82`](https://github.com/jiacimu-droid/lulu1/commit/ddd5e82c0152a5bcac5f8b78d6f7041a25d8ddf7) 将承诺来源限制为已经由 timeline 解析的事件 ID；保留明确重考虑与电话静默观察回退。测试 [`1c2ea58`](https://github.com/jiacimu-droid/lulu1/commit/1c2ea58252fa910eba774e8ef593d303d4d1385f) 验证空、重复及不接受无效 ID 的纯函数边界。没有额外模型调用、数据删除或人物人设修改。
- **验证边界**：本轮 GitHub 内容 API 提交成功，但尚未获得对应 SHA 的完整 JVM/APK/Actions 成功结果，也未执行本地 Gradle；纯函数回归不能代替真实 Timeline→CommitmentDecisionRuntime 的入口集成测试。下一轮先核新 main Actions，随后补混合有效/跨角色 ID 的入口级持久任务回归，检查电话静默观察的真实归属、执行结果及下轮召回。P-08a.1 不宣称整体完成。

## 2026-11-07 · P-08a.1d 混合来源的真实 Bridge→承诺持久化回归

- 针对 P-08a.1c 仅纯函数校验的缺口，在既有 Robolectric `CommitmentSemanticPersistenceTest` 增加真实 `SharedExperienceTimeline.record`、`LuluDeviceToolBridge.respond`、`CommitmentDecisionRuntime` 与 `CommitmentTaskStore` 联动回归：输入同文但不同角色事件、缺失 ID 和本角色有效事件混合列表，断言持久任务只保留本角色有效来源，其他角色不产生任务，且处理幂等只归有效来源。MockWebServer 返回固定两阶段模型 JSON，不触发收费外部模型。
- 测试提交 [6818ecec](https://github.com/jiacimu-droid/lulu1/commit/6818ecec01479d4aba65366f34a8114b13719786)。**本轮仅确认提交，未取得新 JVM/APK 成功证据**；若新 CI 失败须按第一条真实日志修正，不能把测试源码视为测试通过。电话静默观察、后台回执与跨轮召回仍待核查；P-08a.1 整体未完成。
- 下一轮：先查该提交后继 push 的 JVM/APK 与代码索引；随后按 P-08a.1 继续电话/后台来源→决定→发送→持久回执→下一轮召回，或在已有代码覆盖充分时转 P-08b.1。


## 2026-11-07 · P-08a.1e 后台主动发送回复归属的真实送达时间边界

- 核对最新 main 四份必读文档、ProactiveSendAttribution 正式源码/测试与 Actions。前轮 P-08a.1d 的 APK/JVM 42281544845、索引 42281544782 均 completed/success；这仅覆盖此前的入口承诺回归，不覆盖本轮。
- 发现已持久的成功发送回执与实际 Timeline CharacterStatement 的时间可能不一致。原归属只判断 UserStatement 晚于 receipt.sentAt；若发送回执时间早于真实送达事件，则在两者之间的用户消息被误算作对尚未送达消息的回复。另原纯函数只按 sessionId 匹配，混合角色事件或把非角色发言 ID 当作发送锚时可能产生错误归属。
- 正式源码提交 8957421f3e72123da603dc29bcca4464232725b0：只以真实 CharacterStatement 为送达锚；回复必须晚于 max(回执时间, 送达事件时间)，且用户事件属于同一角色和 session。无法确认送达锚则不计入可归属样本。保留原始历史、发送回执和人物自主决策。
- 回归提交 9db2a1b921492e9aa58ab5fc7b3762766a951dd5：覆盖回执时间早于实际送达、送达前用户发言、真正送达后回复、非发送事件锚与跨角色同 session 误计。仅使用 Robolectric 本地事实，无收费模型调用。
- **验收**：源码/测试已提交，尚未取得对应 SHA 的 JVM/APK 成功证据；真机后台发送、电话静默、进程重启与下一轮召回仍未知。下一轮先核 Actions 第一失败或成功，再继续 P-08a.1 的后台/电话完整回执和跨轮召回；不以该纯函数回归冒充端到端通过。

## 2026-11-07 · P-08a.1f 主动发送回执与实际送达事件身份一致性

- 复核 main 四份必读文档、`ProactivePerceptionRuntime` 成功发送记录及 `ProactiveSendAttribution` 的读取路径。运行时以 `proactive-send:<actionEvidenceId>` 记录成功发送，且 `causalEvidenceId=actionEvidenceId`。此前统计只检成功状态和时间，没有验证两者一致；若保存记录错配到另一条真实送达事件，会把另一条消息的回复算在本决定上，缺失来源则不应归属。
- 正式源码 [ea3bf370](https://github.com/jiacimu-droid/lulu1/commit/ea3bf370cac722024e7b743bef1e5df6ff4cd3db) 增加读取时的身份一致性门禁，不改变原始历史、人物决策或成功发送逻辑；测试 [ae28fa76](https://github.com/jiacimu-droid/lulu1/commit/ae28fa76d8cc1de641bc339d03c2479ac5bd3f6b) 为原 Robolectric 回归补错配/空来源负例，正常成功回执仍保留。
- **验证**：已提交生产源码与测试，尚未取得对应新 HEAD 的 JVM/APK、Actions 成功结果；真机、真实发送失败、重启后持久回执和下一轮人物召回未知。下一轮先查新 CI；若成功继续 P-08a.1 的电话静默与后台发送结果到下一轮召回入口级回归，若失败修第一条真实错误。不能将本轮源码提交等同测试通过。


## 2026-11-07 · P-08a.1g 成功发送但缺真实送达锚的回执门禁

- 新 CI 核验：`ae28fa76` 对应 APK/JVM [42313244834](https://github.com/jiacimu-droid/lulu1/actions/runs/42313244834) 与索引 [42313244835](https://github.com/jiacimu-droid/lulu1/actions/runs/42313244835) 均 completed/success，覆盖 P-08a.1f 的测试；不能代表后续新提交。
- 正式源码检查 `ProactivePerceptionRuntime`：`actionEvidenceId` 由真实时间线解析，可在 `primarySuccess=true` 时仍为空；此前仍持久写入 `proactive-send:` 成功回执（无真实送达锚），后续统计只能排除这种无效记录。源码提交 `06420830ca2cbf1826c9cd185441fe87de9e7866`：只有实际成功且解析到非空送达事件 ID 才写主动发送成功回执；不改变工具执行结果、不推断用户回复、不新增模型调用。已有 `ProactiveSendAttributionTest.receiptsKeepOnlyDeliveredProactiveSendsWithParsableTime` 覆盖读取时无来源过滤，但**尚缺生产入口空锚集成回归**，不能声称本次写入门禁已测试通过。
- 新提交 JVM/APK、真实发送、重启持久回执及下一轮召回尚待验。下一轮先核新 Actions，补生产入口空锚/成功锚的真实持久化回归；再继续电话静默观察与后续召回。

## 2026-11-07 · P-08a.1g.1 主动发送成功回执写入门禁回归

- 刷新 main、AGENTS、CODE_INDEX、Roadmap、人物进度及 ProactivePerceptionRuntime/ProactiveSendAttribution 正式代码与测试；确认 06420830 已有非空送达锚门禁，不重复改业务规则。对应 push APK/JVM [42319463747](https://github.com/jiacimu-droid/lulu1/actions/runs/42319463747) completed/success。
- 为避免只测读取过滤而遗漏写入门禁，将现有布尔机制抽为可测试的 `ProactiveSendAttribution.shouldPersistDeliveredSend`（63308f8），正式写入调用同一函数（e2bf9dd），新增四种门禁回归（c0601ed）：成功无锚不得写、失败有锚不得写、非私聊有锚不得写、成功且有锚可写。没有新增模型调用、修改人设或清空历史。
- **验收边界**：这是生产门禁的单元级回归，不是实际执行器与持久 Store 的入口集成；本轮提交的 JVM/APK/索引尚需后继 Actions 证明，真机、重启、电话静默观察和下一轮召回仍未知。下一轮先核新 CI，若失败按第一错误修；再补真正 ToolRouter→Timeline→Store 的写入集成，避免将纯函数测试当作全链验收。


## 2026-11-07 · P-08a.1g.2 后台送达候选的会话隔离

- 复核四份必读文档及 AutonomousActionTrace、CharacterActionEvidence、ProactivePerceptionRuntime 和现有回归。确认 `newestExecutedEvidence` 在传入 conversationId 时仍因 `speaker == "角色"` 直接接受另一私聊会话的同角色 CharacterStatement，可能在主动作无直接证据时被当作当前会话的送达候选。此前同轮主副动作归属风险仍未全部排除。
- 正式源码 [5164725](https://github.com/jiacimu-droid/lulu1/commit/5164725b8ac75240654ca764ac7edeaa174a22ac) 将本人 speaker 校验与会话 ID 校验同时要求；无 conversationId 的既有其他动作回退保持原样。回归 [fce539f](https://github.com/jiacimu-droid/lulu1/commit/fce539f49e7bbcf5e59e6fbc8ccdd10b54204436) 新增同角色跨会话拒绝与同会话接受。未更改人设、历史、收费模型调用。
- 本轮仅取得 GitHub 提交证据；JVM/APK、真实 ToolRouter→Timeline→Store、重启与下一轮召回未验，不能标记完整入口通过。下一轮核新 Actions；继续检查同会话并行副动作的送达身份，补真实入口持久回执测试。固定 P-08a.1g 仍待集成验收。

## 2026-11-07 · 施工看板清理与群聊昵称回归审计

- **核对基线**：`main` 在 `c0601eded6` 的 [Lulu APK/JVM 42330530414](https://github.com/jiacimu-droid/lulu1/actions/runs/42330530414) 和 [索引 42330530343](https://github.com/jiacimu-droid/lulu1/actions/runs/42330530343) 均 completed/success，覆盖该提交及之前的源代码和已纳入 JVM 的测试；不代表后来 `5164725`、`fce539f` 和本次修复已过 CI，也不等于真机/真实模型合格。
- **已证实的负优化与修复**：`5164725` 为排除同角色跨 session 旧语句，把 `AutonomousActionTrace.newestExecutedEvidence` 的 speaker 白名单也强加于明确 session 的 `CharacterStatement`；群聊里使用昵称的合法已送达消息因此被漏算。[#802/Run 42352684114](https://github.com/jiacimu-droid/lulu1/actions/runs/42352684114) 实际 2503 tests / 1 failed，`AutonomousActionTraceTest.sharedReceiptsRecognizePrivateCharacterVoiceButNeverUserStatements` 预期 group、实际空。生产修复 [03a490d](https://github.com/jiacimu-droid/lulu1/commit/03a490d0bb4e0d7ad10d243b5181f0c636d26e0e)：明确 session 时依据本角色时间线 + 准确 session 选 CharacterStatement，无 session 时才要求公认本人说话者；保留 UserStatement 排除。回归 [5dbaead](https://github.com/jiacimu-droid/lulu1/commit/5dbaeadb1785936541d7553d37c360fee835665e)：新增无 session 的未知昵称、同 session 的用户消息均不可当作本人已执行动作。另一失败 [#803/Run 42352688260](https://github.com/jiacimu-droid/lulu1/actions/runs/42352688260) 也是 2503 tests / 1 failed，且相同群昵称断言，不是新的独立根因。本轮新 CI 未得成功证据，不可关闭此回归。
- **看板收敛**：此前路线图累计 23 条勾选的工程阶段记录混入活动清单（其中 4 条被字面量 `\\n` 嵌在其他清单行内，现已恢复正常换行），部分标注“CI 待验”实际上已经有后继 `c0601ed` 成功基线。现将这 23 条“已提交/已运行”的**工程阶段记录**退出活跃待办；清理后按清单行统计 20 个仍待完整验收的主项目、84 个待办子步骤，保留在此专项日志、此前文档 Git 历史和源码提交；P-08d.2h / P-08d.2g、P-08a.1d/e/f/g、P-08a.CI-01、P-08c.1/2a 等**未完成的真机、模型、执行器入口与跨日验收仍在唯一看板**。绝不因为有 [x] 或 CI 成功就注销 20 项主项目。
- **仍需评估的潜在回退边界**：`AutonomousActionTrace` 的 ToolResult 后备候选尚未使用会话 ID 过滤；但是工具执行器可能合法地产生不含 session 的回执，不能无证据一刀切禁用。后续应以主工具真实 evidenceId 为优先并在同会话并发、群聊昵称与跨角色工具混排时验证“事件属主、实际消息送达、幂等回放”，确认缺陷再改。屏幕/电话跨场景的活人感、额外模型费用、传输与性能尚缺同版本真实测量。
- **接力**：核最新 commit 的 APK/JVM 和索引第一错误；如失败优先修。继续 P-08a.1g 生产 ToolRouter→Timeline→Store 持久回执与下一轮召回集成，再推 P-08d.1 真实心事/承诺后续。不得用过期旧 CI 状态重复做已经完成的实现。

## 2026-11-07 · P-08a.1g.3 同轮副工具回执遮挡主消息送达证据

- 接力读取 main 四份必读文档及 AutonomousActionTrace、CharacterActionEvidence、ProactivePerceptionRuntime 与原 Robolectric 测试。已知旧 P-08a.1g.2 群昵称回归曾失败，后继 03a490d/5dbaead 修复；本轮未重复更改会话 speaker 规则。
- 证实源码选择顺序缺口：主私聊 CharacterStatement 已产生，后续同轮副工具 ToolResult 较新；newestExecutedEvidence 只返回最新副工具 ID，随后 CharacterActionEvidence.supports(primary message) 拒绝，却不再检查此前真正送达的主消息，导致成功发送回执可能缺锚。此处是可构造的源代码因果缺口，不声称已在真机复现。
- 生产源码 baad0dba00a895a22c58571b471eef3d2cec1b45 在原候选选择器添加可选动作证据谓词；65defe65af1fe0cb278de407ba188b3e676ce416 让后台主动作回退使用同一 CharacterActionEvidence.supports 规则。保留直接 evidenceId 优先、旧通用调用默认行为及原始时间线，不新增模型调用或状态 Store。
- 回归 fe4fac14a4ef38803ac6517d5d59abe495c7701c 覆盖先送达主消息后出现无关工具回执、无匹配候选；Roadmap efa7f6babe73acbe72f3ac584612fdb1ffc92a01。尚未运行本地 Gradle，也尚未取得新提交 JVM/APK Actions 成功证据，不能宣称测试已通过。未验真实 ToolRouter→Timeline→Store 持久回执、重启/下一轮召回、同会话同动作并发和真机/真实模型。
- 下一轮先核包含 fe4fac1 的 JVM/APK 与索引 CI；失败按首条日志修复。再补同会话同类副消息与主消息的精确身份归属入口回归；不能把只按 action 匹配当作已验证主副隔离。P-08a.1g 继续待验。


## 2026-11-07 · P-08a.1g.4 显式跨会话 ToolResult 不能冒充当前回执

- 接手 main b4b2a75，读取 AGENTS、CODE_INDEX、Roadmap、人物进度及 AutonomousActionTrace/CharacterActionEvidence/ProactivePerceptionRuntime 与回归。原 newestExecutedEvidence 在有 conversationId 时对 ToolResult 不检查 sessionId：另一会话明确归属的较新工具成功事件可被当作当前会话候选；当主动作也是 tool 时支持谓词可能接受它。与此前已修的 CharacterStatement 跨会话问题不同。
- 生产修复 [52fc4fb](https://github.com/jiacimu-droid/lulu1/commit/52fc4fb9a05b0dbcb4b976c103b589858a0c2087)：显式标记另一 session 的 ToolResult 不再作为当前会话候选；sessionless 工具回执仍保留，避免误伤合法无会话工具。回归 [4b76293](https://github.com/jiacimu-droid/lulu1/commit/4b7629362d3f54b33edcfc0674f8965f3c4cf9b1) 覆盖跨会话拒绝、同会话接受、无 session 保持。
- 本轮 GitHub 内容 API 已提交，**未运行本地 Gradle，未取得这些新 SHA 的 JVM/APK 成功证据**。这只是候选筛选边界，不是同会话主副同类消息精确身份映射、ToolRouter→Timeline→Store 重启及下一轮召回的端到端验收；真机与真实模型仍未知。下一轮先查后继 push CI，再补同会话主副消息身份映射集成回归。P-08a.1g.3 整体保持未完成。


## 2026-11-08 · P-08a.1g.5 主动私聊主/副同类送达身份

- 复核 main 的 AGENTS、CODE_INDEX、路线图、专项进度及 ProactivePerceptionRuntime、CompanionActionRuntime、SharedExperienceTimeline、CharacterActionEvidence 与回归。Timeline 的私聊事件以原聊天消息 ID 记录，执行器返回本次主动作实际送达的 deliveredMessageIds；原后台 fallback 只按角色/会话/动作类型选最近事件，同轮副动作发送同类消息时可冒充主发送。
- 生产提交 d7b57ee（主消息 ID 证据选择器）、fa7dba0（后台主私聊改用精确 ID，不再回退猜同类副发送）、28eccc6（提取可单测的候选过滤）；测试 771ebd1（同会话主副消息、主消息 ID 缺失、跨会话与拒绝动作）。保留非私聊动作原回退，未改人设、历史或模型调用。
- GitHub 内容 API 提交成功；771ebd1 的 commit workflow runs 与 combined statuses 查询均为空。前者接口只覆盖部分 PR 运行，不能认定 push CI 成败。本轮未运行本地 Gradle，JVM/APK、真实执行器→Timeline→持久 Store、重启、下一轮召回、真机和真实模型均未验证。
- 下一轮先核新 main 的 push Actions 与首条失败；随后补实际 ToolRouter→Timeline→CharacterInnerLifeStore 入口持久化及重启回归，特别是主动作失败但副发送成功、同 requestId 重放、部分送达。P-08a.1g 保持待验，不提前勾完成。

## 2026-11-08 · P-08a.1g.3 真实 Timeline→InnerLifeStore 持久回执回归（局部）

- 已刷新 main 四份必读文档及 ProactivePerceptionRuntime、ProactiveSendAttribution、SharedExperienceTimeline、CharacterInnerLifeStore、现有 Robolectric 回归。确认 P-08a.1g.5 的精确主送达 ID 门禁已存在，不重复改生产逻辑。
- 新增测试提交 [9f6f1b2](https://github.com/jiacimu-droid/lulu1/commit/9f6f1b255a7f72c644fd9cb0eefdb65280eaa752)：在真实 SharedExperienceTimeline 写入同会话主/副 CharacterStatement，以生产 primaryDeliveryEvidence 解析本次主 ID，经过 shouldPersistDeliveredSend 写入真实 CharacterInnerLifeStore，检查 summarize 从持久快照及时间线重新读取、同 ID 重放不重复、无主 ID/跨会话不认领、未把副消息登记为主回执。测试使用 Robolectric 本地数据，不调用收费模型；隔离角色 ID 并清理测试内心 Store。
- **验收边界**：这是 Timeline→Store 的本地集成回归源码，未运行 Gradle/JVM/APK，也未取得该 SHA 的 Actions 结果；未模拟真正 ToolRouter/CompanionActionRuntime 的失败和部分发送、未证明进程重启后持久 SQLite/SharedPreferences 恢复，更不代表真机或真实模型通过。不得将本测试勾为 P-08a.1g 整体完成。
- 下一轮先查 9f6f1b2 后继 push CI 的第一条真实失败；若测试失败按日志修。随后补真实执行器入口主失败/副成功、部分送达及跨进程重新初始化的回归；若只能依赖真机，转向 P-08a 其他可实施子项。

## 2026-11-08 · #806 主动私聊送达归属测试环境修正（待 CI/真机）

- [Issue #806](https://github.com/jiacimu-droid/lulu1/issues/806) 对应 9f6f1b2 的 [APK/JVM run 42397397265](https://github.com/jiacimu-droid/lulu1/actions/runs/42397397265)：`:app:testPhoneUnitTest` 共 2531 tests、1 failed，首个失败为 `ProactiveSendAttributionTest.persistedPrimaryReceiptSurvivesStoreReadAndNeverClaimsSideOnlyDelivery` 第 191 行（期望主消息 ID，实际空字符串），APK 后续步骤未执行。
- 实查根因：此新增测试只用 `SharedExperienceTimeline.record(source="message")` 人工制造角色发言，没有往唯一权威 `MigratedDomainStores.chat` 写入 `Sent` 消息。现行 `TimelineStatementOwnership.original` 正确要求会话、原消息 ID、作者、送达状态、内容与时间匹配，故 `CharacterActionEvidence.supports(message)` 必然拒绝这条孤立伪事件；**不是生产送达门禁坏了**，不可放松该门禁或只修改断言。
- 已直接提交 main [b64f565](https://github.com/jiacimu-droid/lulu1/commit/b64f5655588206bda885ed5d8ac84858b6acdbf6)：集成回归改走现有真实私聊创建/appendCharacterMessage→自动时间线投影→主 ID 选择→InnerLifeStore 决策持久化→summarize；额外断言仅伪造时间线而无聊天送达的行绝不可以冒领。沿用原主/副消息、跨会话、幂等、清理检查；未改生产逻辑和其他测试。
- **验证边界**：本轮已核对失败日志、真实代码路由、GitHub 文件更新提交；新提交对应的 push Actions 尚未拿到实际成功结果（GitHub commit status / PR-run 查询为空，不可推断未触发），没有运行本地 Gradle/APK 或真机。#806 与 P-08a.1g.3 须待新 JVM/APK success、真机以及实际执行器/跨重启/下一轮召回验收再考虑完成。

## 2026-11-08 · P-08a.1g.3 CI 复核与部分送达回归补强

- 核实 b64f565 的 [Lulu APK/JVM #42399152640](https://github.com/jiacimu-droid/lulu1/actions/runs/42399152640) completed/success，含 Test and build phone APK、上传与发布步骤成功；索引 [#42399152677](https://github.com/jiacimu-droid/lulu1/actions/runs/42399152677) success。后续 2b0debd 的 [#42403163778](https://github.com/jiacimu-droid/lulu1/actions/runs/42403163778) 也 success，涵盖此前测试修正；此前 9f6f1b2 的 2531 tests / 1 failed 已被后继修正与成功 CI 取代。
- 新增 Robolectric 集成回归 [1cca29a](https://github.com/jiacimu-droid/lulu1/commit/1cca29a354f84ba18eb2b7310bc9403a68287ffc)：在真实 ChatStore→Timeline→InnerLifeStore 路径检查部分主送达 ID（先无效后有效）仍能找到实际主消息，以及只有副发送而没有主送达 ID 时不产生主成功回执。没有改生产语义或人物设定。
- **未验**：1cca29a 新增断言尚无本 SHA 的 JVM/APK 结果；没有真正运行 ToolRouter/CompanionActionRuntime 的失败与部分执行，没有跨进程重启、下一轮角色模型召回或真机验收。下一轮先核新 CI 首错；继续补实际执行器与重启边界，P-08a.1g.3 保持未完成。

## 2026-11-08 · P-08a.1g.3 主动送达跨角色归属负例

- 复核 main 四份必读文档、真实 ChatStore→Timeline→InnerLifeStore 回归和 push Actions。最新基线 66e9f432 的 APK/JVM [42417989012](https://github.com/jiacimu-droid/lulu1/actions/runs/42417989012) 与代码索引 [42417989014](https://github.com/jiacimu-droid/lulu1/actions/runs/42417989014) 均 success；该成功早于本轮提交。
- [33fac8a](https://github.com/jiacimu-droid/lulu1/commit/33fac8ae30f5b322f71db0a019a9ea476036afe1) 在真实 ChatStore 创建/投影主消息的既有 Robolectric 集成回归中补跨角色归属负例：即使 conversation 与实际送达 ID 完全一致，其他角色不得认领。未改生产决策、人设、历史或收费调用。
- 本轮新测试尚无 Actions 成功证明；没有执行真正 ToolRouter、进程重启和下一轮模型召回。P-08a.1g.3 继续未完成；下轮先核 33fac8a 后继 JVM/APK，随后补执行器失败/部分成功与重新初始化的回归，无法安全实施则转 P-08b.1。


## 2026-11-08 · P-08a.1g.3 撤回/删除原件后不得沿旧时间线误算回复

- 核 main 12e809f、四份必读文档、ProactiveSendAttribution、TimelineStatementOwnership、CharacterActionEvidence 与 Robolectric 测试；33fac8a 对应 push APK/JVM [42421953256](https://github.com/jiacimu-droid/lulu1/actions/runs/42421953256) 和索引 [42421953257](https://github.com/jiacimu-droid/lulu1/actions/runs/42421953257) 均 completed/success，旧跨角色回归已验通。
- 已证根因：统计入口 summarize 从 InnerLifeStore 回执与 Timeline 快照直接调用 repliedByDecisionId，仅校验事件种类、角色、session 和时间；而正式聊天原件可能被删除、改写或变为非 Sent，旧投影不能继续证明送达/回复。现有 TimelineStatementOwnership 已具备基于原 ChatStore 的状态、内容、时间、作者校验，不应新增状态源。
- 正式源码 [69840a1](https://github.com/jiacimu-droid/lulu1/commit/69840a1f626a05f7dca228ec3f6d6e10a8eeb3c8) 让 summarize 对角色送达与用户回复均通过现有原件归属门禁；纯函数保留默认调用兼容，新增可注入有效性谓词。回归 [d07a594](https://github.com/jiacimu-droid/lulu1/commit/d07a594108de88e75eb4662f7179ac1dc923e069) 覆盖送达原件失效不得归属、用户回复原件失效不得算已回复。未更改角色决定、原始历史或收费调用。
- **验收**：源码/回归已提交；新 SHA 的 JVM/APK 尚待 push Actions，未本地编译，未真实删除后重新启动、ToolRouter 失败/部分执行或下一轮模型召回；P-08a.1g.3 不勾完成。下一轮先核新 CI 第一错误，再补真实 Store 原件删除/修改与跨进程恢复的入口回归。

## 2026-11-08 · P-08a.1g.3 真实 ChatStore 原件变更回归

- [ac7c628](https://github.com/jiacimu-droid/lulu1/commit/ac7c6286d09207a3b05f033d27bd589e5961353f) 在现有 Robolectric 集成测试中追加真实 sendUserMessage → editMessage/deleteMessage 以及角色原发送 editMessage，检查 summarize 不沿失效原件计回复或有效送达；复用原有 ChatStore/Timeline/InnerLifeStore，不更改生产人格与决策。
- **验证**：仅测试源码提交；本提交的 JVM/APK/索引 Actions 尚未获得完成结果，本地 Android 编译未运行。仍缺 ToolRouter 实际失败/部分成功、真正进程死亡后的重新加载、下一轮召回与真机模型验收。下一轮先核本提交 CI 首错，失败则修测试或生产真实缺口；通过后优先执行器入口回归。P-08a.1g.3 不勾完成。

## 2026-11-08 · #809/#810 主动消息回复归属的编辑语义回归

- 两个 CI 故障工单：[ #809 / 42440002599](https://github.com/jiacimu-droid/lulu1/actions/runs/42440002599)（ac7c628）及 [#810 / 42440383322](https://github.com/jiacimu-droid/lulu1/actions/runs/42440383322)（3fcdde3）均在 `:app:testPhoneUnitTest` 运行 2540 项、仅 1 项失败，失败测试 `ProactiveSendAttributionTest.persistedPrimaryReceiptSurvivesStoreReadAndNeverClaimsSideOnlyDelivery` 第 277 行：期望 `replied=0`、实际 `1`。前序 APK 构建步骤尚未执行。
- 检查真实 `InMemoryLuluChatStore.editMessage`：原 `Sent` 消息编辑时保持消息身份/时间/送达状态，并同步以同 ID 修订 `SharedExperienceTimeline`；`TimelineStatementOwnership.original` 正确按原聊天与实时投影核实。一次编辑不会撤回过去的发送或用户已经回复的事实。测试原来把“内容修改”误当“消息不再送达”，与正式契约矛盾；**不应修改生产代码让实际已送达回复被误报未回复**。
- 代码提交 [98c2e782](https://github.com/jiacimu-droid/lulu1/commit/98c2e782abdf53d100836a62caa835c2e7076897)：对应回归更正为用户真实回复后编辑仍 `replied=1`；同时增加真实 `markFailed` 状态转移后的 `replied=0` 断言，保留删除消息后仍 0、角色发送原件失效和跨角色/跨会话门禁；无额外模型调用，未删除测试或放宽其他断言。
- 验证边界：已获取两次失败日志与准确源代码路径，提交及 diff 经 GitHub 核验；`98c2e782` 本轮未有完整新 JVM/APK CI 通过证明（GitHub status 和 PR-only workflow 查询为空不代表 push Actions 结果），未做真机/模型复测。#809/#810 保持待成功后继构建确认；P-08a.1g.3 的 ToolRouter 真实主副动作、跨进程重启、下一轮模型召回及真机仍未验。

## 2026-11-08 · P-08a.1g.3 #809/#810 后继 CI 验收

- 复查 main `46ebffe73f154bf905bb3bf6a926894b252adfae` 的四份入口文档、`ProactiveSendAttribution.kt` 与 Robolectric 集成回归；#809/#810 的测试编辑语义错误已由 `98c2e782abdf53d100836a62caa835c2e7076897` 修正，未重复开发。
- 核实 [Lulu APK/JVM #42441234402](https://github.com/jiacimu-droid/lulu1/actions/runs/42441234402) 为 `completed/success`，head SHA 正是 `98c2e782`，2026-11-08 03:17 UTC 更新；这是旧 2540 项/1 失败的后继成功验证，不代表真机、真实模型或进程死亡恢复通过。
- 本轮没有证实额外生产缺口，不为凑提交增加重复测试；仅将已确认 CI 结果回填 roadmap。后续优先 P-08a.1g.3 真正 ToolRouter 主失败/副成功→Timeline→Store 的入口级回归和重新打开持久 Store；若测试环境无法重建进程，则转 P-08b.1 跨入口人物注入可执行回归。仍不得勾 P-08a.1g.3 整体验收。

## 2026-11-08 · P-08a.1g.3 ChatStore 重新实例化存储回归

- 新增测试 [`804b1b2`](https://github.com/jiacimu-droid/lulu1/commit/804b1b22fe82032208cc9c9a48b9e3a5a89f476e) `ProactiveSendAttributionTest.canonicalChatDeliverySurvivesFreshStoreInstanceAndFailedReplyDoesNotRevive`：真实 Store 发送角色消息与用户回复、将回复标 Failed，再新建 `InMemoryLuluChatStore` 读取 SharedPreferences，核实角色 Sent/authorCharacterId 与用户 Failed/sender 不会因新实例丢失。未使用模型或伪造 Timeline。
- 仅为真实存储重新打开的最小回归；尚未取得该提交 JVM/APK/索引成功证据，未执行真实 ToolRouter、进程死亡、InnerLifeStore 重建或下一轮角色召回。下轮直接打开 `app/src/test/java/com/jiacimu/lulu/data/ProactiveSendAttributionTest.kt` 和 `ToolRouter.kt`，核 `804b1b2` 对应 push CI，再补主失败/副成功的执行器级回归；若不能接入，转 P-08c.2。P-08a.1g.3 不勾完成。

## 2026-11-08 · P-08c.2 实际执行器部分发泡取消回执

- 复核 main `849716a`、四份入口文档、`CompanionActionRuntime.send_private_message` 与 `QqReplySourceWatermarkTest`；原测试只覆盖首泡前取消，缺少已发送部分气泡后的独立回执核验。
- 新测试 [`19bc51e`](https://github.com/jiacimu-droid/lulu1/commit/19bc51ef7c630e12a218045e243377c4b26ea441) 使用真实执行器与 ChatStore，首泡真实入库后发送新用户纠正，核对 `success=false`、`deliveredMessageIds` 与实际存储 ID 相等、`evidenceId` 指向最后已发送泡、旧回复不继续。未改生产代码、人设或收费模型调用。
- 提交时该 SHA 的 APK/JVM 与 Refresh code index 尚未返回成功证据；取消时序可能暴露测试竞争，须先看 CI 首错，不得提前勾完成。下一轮直接打开 `QqReplySourceWatermarkTest.privateActionPartialDeliveryReportsOnlyPersistedBubbleIdsAfterCorrection` 和 `appendRoleReplyWithPacingReceipt`，核 Actions 后按失败事实修复。真机、真实模型未验。

## 2026-11-08 · P-08c.2 部分发泡取消回归后继 CI

- 接手 `main`：`827b5196b1819ddd1e44ac185df43e9361787393`；复核 `QqReplySourceWatermarkTest.privateActionPartialDeliveryReportsOnlyPersistedBubbleIdsAfterCorrection`、`CompanionActionRuntime.execute` 和 `QqChatReplyEngine.appendRoleReplyWithPacingReceipt` 的已提交执行器/ChatStore 路径，未重复增加同类断言。
- **实测 CI**：测试提交 [`19bc51e`](https://github.com/jiacimu-droid/lulu1/commit/19bc51ef7c630e12a218045e243377c4b26ea441) 对应 [Lulu APK/JVM #42476629762](https://github.com/jiacimu-droid/lulu1/actions/runs/42476629762) `build` job 128719326366 与 [Refresh code index #42476629754](https://github.com/jiacimu-droid/lulu1/actions/runs/42476629754) `refresh` job 128719326340，均 completed/success。测试覆盖真实首泡入库、用户纠正、后续停止和 `deliveredMessageIds` 与实际存储一致；不能外推至进程死亡或模型效果。
- 本轮无新生产代码或测试提交；仅回填成功证据，不把文档改动冒充修复。下一工程工作包仍为 P-08c.2：先核重启后部分发送回执消费与取消结果；若已覆盖则切 P-08a.1g.3 的真实 ToolRouter 主失败/副成功。真机/真实模型/跨日均未验。

## 2026-11-08 · P-08c.2 群聊部分发泡取消执行器回归

- 在 `main` 读取四份必读文档、最近 10 提交，复核 `CompanionActionRuntime.send_group_message`、`QqChatReplyEngine.appendRoleReplyWithPacingReceipt` 与 `QqReplySourceWatermarkTest`；私聊部分送达已有 `19bc51e` / APK/JVM #42476629762 success，群聊原有纠正测试仅覆盖首泡前取消，不能代替首泡后回执。
- [cffcd760](https://github.com/jiacimu-droid/lulu1/commit/cffcd7608a576d0da64f15943a0784728ce36d22) 新增 `groupActionPartialDeliveryStopsAfterUserCorrectionAndKeepsOnlyRealReceipts`：真实群聊入口执行多泡，等待首泡真实入库，再发用户纠正；检查 `success=false`、实际存储 ID 与 `deliveredMessageIds` 一致、末泡 `evidenceId` 一致、后续旧泡停止。仅新增测试，未更改生产代码/人设/历史/收费调用。
- 验证：提交后尚未取得 `cffcd760` 对应完整 JVM/APK 与代码索引成功证据，本地 Gradle/真机/真实模型均未运行。下一轮优先查该 SHA Actions 及测试首错；通过后继续 P-08c.2 的部分送达回执消费/重新实例化，不能将群聊部分发泡误算整轮成功；若执行器证据足够则转 P-08a.1g.3 主失败副成功。整体 P-08c.2 不勾。


## 2026-11-08 · P-08c.2 群聊取消后的下一轮真实送达

- [`6c84878`](https://github.com/jiacimu-droid/lulu1/commit/6c84878c66e778046747c0f10fe21738fd1c07a9)：沿 `cffcd760` 已有真实 `CompanionActionRuntime.execute(send_group_message)`→ChatStore 回归，增加用户纠正后以新来源发起下一轮的验证。断言旧批已送达泡不被改写/续发、新批可成功送达且独立 `deliveredMessageIds/evidenceId` 与权威消息一致；不是另造语义规则或角色台词机制。
- 仅测试改动；没有运行本地 Gradle、真机、收费模型。新提交对应 JVM/APK 与 Refresh code index 待核，不可提前勾选。下轮打开 `QqReplySourceWatermarkTest.groupActionPartialDeliveryStopsAfterUserCorrectionAndKeepsOnlyRealReceipts`，先查 `cffcd760`、`6c84878` 的 Actions head SHA/首错；通过后推进 P-08c.2 跨实例部分回执消费，未验进程死亡/真实模型。

## 2026-11-09 · P-08c.2 群聊部分发送取消后的 Store 重载

- **真实提交**：[`ad2817f5c589298882e962515a5f5b86bd788a4d`](https://github.com/jiacimu-droid/lulu1/commit/ad2817f5c589298882e962515a5f5b86bd788a4d)，仅修改 `app/src/test/java/com/jiacimu/lulu/QqReplySourceWatermarkTest.kt`。原真实 `CompanionActionRuntime.execute(send_group_message)` 测试继续验证首泡入库、用户纠正、旧批取消及新来源独立送达；新增从同一 SharedPreferences 重建 `InMemoryLuluChatStore`，核持久化消息 ID/正文、旧泡不复活、下一批回执只指向新泡。
- **限制**：未修改生产源码；本地环境无法解析 github.com，未运行 Gradle；PR-only commit workflow 查询为空，不代表 push CI 无构建。提交后 JVM/APK、Refresh code index 尚未取得 `head_sha=ad2817f5...` 的完整成功证据；真正进程死亡、下一轮人物模型召回、真机和真实模型均未知。
- **下一轮固定入口**：P-08c.2，先核 `ad2817f` 的 main push Actions（Lulu APK/JVM build 与 Refresh code index）首错；若通过，检查部分送达回执的消费者重建/重复消费边界，已有覆盖则切 P-08a.1g.3 `ToolRouter` 主失败、副成功的真实入口测试。直接打开 `QqReplySourceWatermarkTest.groupActionPartialDeliveryStopsAfterUserCorrectionAndKeepsOnlyRealReceipts`，不要重复建立同类断言。

## 2026-11-09 · P-08c.2 群聊取消回归 CI 首错与夹具修复

- 按 `main` push Actions head SHA 查证：`cffcd760` 的 [APK/JVM #42486376988](https://github.com/jiacimu-droid/lulu1/actions/runs/42486376988) 和 `6c84878` 的 [APK/JVM #42491394396](https://github.com/jiacimu-droid/lulu1/actions/runs/42491394396) 均 failure；后者 2545 tests、1 failed，唯一失败为 `QqReplySourceWatermarkTest.groupActionPartialDeliveryStopsAfterUserCorrectionAndKeepsOnlyRealReceipts`，异常 `IllegalArgumentException: 群聊至少需要两个角色`，发生在测试建群时（`QqReplySourceWatermarkTest.kt:252`），尚未进入发送链路。两次对应索引 workflow 均 success。`ad2817f` 的索引 [#42496527323](https://github.com/jiacimu-droid/lulu1/actions/runs/42496527323) success，APK [#42496527330](https://github.com/jiacimu-droid/lulu1/actions/runs/42496527330) cancelled，不能算通过。
- 修复提交 [`9527f181`](https://github.com/jiacimu-droid/lulu1/commit/9527f181e25aedc512c1447587c502966474d3f3)：仅修改该测试夹具，第二群成员使用与主角色 ID 派生的明确不同 ID，并写入真实 CharacterSettingsStore，避免 Robolectric 测试环境中相邻 `UUID.randomUUID()` 意外重复后被 `createGroupConversation` 的 `distinct()` 折叠为一人。未修改生产发送、人格、历史或收费调用。此修复尚未获得对应 SHA 的 JVM/APK 成功证据；不能据此断言后续发送断言通过。
- 下一轮固定 P-08c.2：按 `head_sha=9527f181...` 查 Lulu APK/JVM `build` 与 Refresh code index `refresh`，失败读取首错；若成功，再检验重建后的部分回执消费者，或切 P-08a.1g.3 真实 ToolRouter 主失败副成功。真机、进程死亡与真实模型未验。

## 2026-11-09 · P-08c.2 重建后旧来源不得复活

- **前序 CI 真实结果**：`9527f181e25aedc512c1447587c502966474d3f3` 对应 [Lulu APK/JVM #42501020762](https://github.com/jiacimu-droid/lulu1/actions/runs/42501020762) `build=completed/success`，及 [Refresh code index #42501020685](https://github.com/jiacimu-droid/lulu1/actions/runs/42501020685) `refresh=completed/success`（通过 Actions jobs 读取）。仅证明该 SHA 的工程验收，不等于真机/模型验收。
- **本轮代码提交**：[`60c2243df180448dcb1c75bd2e144160cbd2cde3`](https://github.com/jiacimu-droid/lulu1/commit/60c2243df180448dcb1c75bd2e144160cbd2cde3)，仅修改 `app/src/test/java/com/jiacimu/lulu/QqReplySourceWatermarkTest.kt`。在既有真实 `CompanionActionRuntime.execute(send_group_message)` 首泡→用户纠正→新批→重建 ChatStore 回归中保存旧 source watermark，重建后验证旧 watermark 失效，旧批不能通过 `appendCharacterMessageIfSourceCurrent` 写入，权威存储消息 ID 不变。不是重复测试单纯的消息列表重载，也没有改变正式发送逻辑。
- **未验**：新 SHA 的 main push JVM/APK 与代码索引尚无本轮成功证据；没有本地 Gradle、真机、真实模型或真正进程死亡验收。P-08c.2 整体不勾。下一轮直接检查 `60c2243` 的 Actions head SHA/首错，再审部分回执消费者重建/幂等；若已覆盖转 P-08a.1g.3 ToolRouter 主失败副成功。API 400 次问题尚无服务商账单与旧 HTTP 逐次记录，不能据此归因；请求尝试、成功响应、计费请求是三种不同计数。

## 2026-11-09 · P-08c.2 ToolRouter 群聊回执重放/Store 重建

- 接手 main `ab5b2e7f588f645fb8d29769d2aac40e5fcac906`，核 `QqReplySourceWatermarkTest` 已覆盖部分发泡、纠正、下一轮和 Store 重新实例化，避免重复这些断言。
- 新测试 [`bf111c58d06a5f6cc402f4b241ebb56f1c6ad5df`](https://github.com/jiacimu-droid/lulu1/commit/bf111c58d06a5f6cc402f4b241ebb56f1c6ad5df) `completedGroupToolReceiptReplaysAfterStoreReopenWithoutDuplicateBubble`：真实 `ToolRouter.execute(send_group_message)` 首次成功→使用同一 SharedPreferences 重建 `InMemoryLuluChatStore`→相同 requestId/不同 `_turnTraceId` 重放，核回执一致、真实气泡仅一条、live/reopened Store ID/正文一致。生产源码未改，不调用付费模型。
- 本轮无对应 SHA 的 JVM/APK 与 Refresh code index 成功证据，**测试尚未验通**；不把 Store 重新实例化称为真实进程死亡，也不把 ToolRouter 幂等误称为所有下游回执消费幂等。下一轮先核 `bf111c58` 的 main push Actions，失败修首错；成功后沿回执消费方补独立副作用幂等回归或转 P-08a.1g.3 主失败/副成功。真机与真实模型未验。

## 2026-11-09 · S-02.1 四居民独立 1+2 请求预算回归

- 新增 [`608fbfdef8f1a6e5cfe42931ecf379eb6843a92c`](https://github.com/jiacimu-droid/lulu1/commit/608fbfdef8f1a6e5cfe42931ecf379eb6843a92c)：`ResidentDailyPolicyTest.fourResidentsIndependentlyFundOnePlanAndTwoDueDecisions`。四角色各自 3 次请求额度，测试各自一次计划与两次到点决策的原子领取、额外领取拒绝、12 次总计和次日独立恢复。复用已有 `claimModelDecision` 正式实现，不改用户额度或角色自主性；该测试验证预算账本，不证明四位居民实际生成日程或执行工具。
- **CI**：当前连接仅提供 PR 触发筛选的 commit runs，不能确认 main push JVM/APK/索引；容器访问 GitHub DNS 失败，本轮未本地编译。新 SHA 尚无成功证据，不勾工程完成。
- **下轮 S-02.1**：先按 `608fbfd` 查询 main push Actions 与测试首错；若通过，沿 `CompanionModelGateway`、`LocalPerformanceRepository` 的 HTTP handoff 统计补 MockWebServer 失败/重试请求数回归。400 次账单归因仍缺服务商原始记录，不能混同尝试、成功与计费。

## 2026-11-09 · S-02.1 预算回归 CI 首错修复

- `608fbfdef8f1a6e5cfe42931ecf379eb6843a92c` 对应 [Lulu APK/JVM #42521395829](https://github.com/jiacimu-droid/lulu1/actions/runs/42521395829)：2591 tests、1 failed；新增 `ResidentDailyPolicyTest.fourResidentsIndependentlyFundOnePlanAndTwoDueDecisions` **PASSED**，失败是另一条 `OnlineChatExperienceTest.interruptedReadingRetainsDeadlineAndCompletingOldReplyKeepsNewMessagesPending`，expected 12000 but null。代码索引 [#42521395832](https://github.com/jiacimu-droid/lulu1/actions/runs/42521395832) success。
- 根因：测试在旧读取仍 claimed 时用 `OnlineChatBatchStore.next` 代表新消息，正式实现正确保留旧 revision；只有真正 `onUserBubble` 才会新建 revision。修复测试提交 [`77aca0e`](https://github.com/jiacimu-droid/lulu1/commit/77aca0ed802d7ebd40bbaefd2f7cd8532592b10e) 将假输入改为真实用户气泡事件；未改生产代码、预算、人物或历史。
- 同时核 `bf111c58` 的 [APK #42516202886](https://github.com/jiacimu-droid/lulu1/actions/runs/42516202886) 为 Kotlin compile failure，不能当作 P-08c.2 通过；该方向待后续专门处理。
- **未验**：`77aca0e` 新 SHA 的 JVM/APK、索引与真机；未做中转站账单归因。下轮 S-02.1 先核 `77aca0e` 后继 Actions，再补真实 HTTP handoff 失败/重试 MockWebServer 回归，不能把请求尝试等同成功或计费。

## 2026-11-09 · S-02.1 中转站 503 单次真实 POST 回归

- 测试提交 [`8d8389a30f9241d8a72f43ed5eb3932505d2cdd3`](https://github.com/jiacimu-droid/lulu1/commit/8d8389a30f9241d8a72f43ed5eb3932505d2cdd3)：仅 `ModelPostTransportTest.kt` 新增 `ambiguous503DoesNotRetryOrCountAsSuccessfulGeneration`，MockWebServer 首次返回 503、预排第二响应；断言第一响应状态/错误正文保持、实际只收到一个 POST，body 未被更改。现有 401、连接前重试、正文送达后断线测试不重复。
- 正式 `ModelPostTransport` 已关闭 OkHttp 自动失败重试/重定向；本轮没有证实此路径生产缺陷，因此没有凑生产补丁。新测试尚无 JVM/APK CI 成功证据，不能勾工程通过；容器访问 github.com DNS 失败，连接器 commit workflow 仅检索 PR 触发，不能据此推断 main push 无 CI。
- **下一轮固定 S-02.1**：核 `77aca0e` 和 `8d8389a` 对应 Actions 的 head SHA、build/refresh；然后沿 `CompanionModelGateway.openAiCompatible` 的 stream 400/422 协议回退检查第二次 POST 是否遗漏 `recordModelHandoff` 和背景/居民额度，先写真实 MockWebServer 失败复现，再修改生产代码。未读取中转站账单，400 次费用无法精确归因；尝试、成功、计费分开。

## 2026-11-09 · S-02.1 Stream fallback POST audit
- Production f4321104242117e75223b88ffc5934722d999bbf: explicit stream protocol rejection now records a separate handoff before the second non-streaming HTTP POST. No persona or resident quota changes.
- Test 66cc31a61886b8e3375473e018d3c43a57f07e60: ModelVisibleOutputTest.explicitUnsupportedStreamOptionMayFallbackOnceToNonStreaming uses MockWebServer to assert two POSTs and a +2 attempted-request delta.
- Engineering CI for this new SHA, Android device, real model and provider billing remain unverified. Attempted requests are not successful or billed requests. Next S-02.1: inspect main push APK/JVM and code-index runs, fix first failure, then audit other AI HTTP routes and whether stream fallback requires resident budget accounting. Do not increase quotas.

## 2026-11-09 · S-02.1 居民流式回退真实网络额度回归（本轮）

- [测试提交 `47ab990`](https://github.com/jiacimu-droid/lulu1/commit/47ab9906671ff49ad6dd2110518adf9774c954a3)：`ModelVisibleOutputTest.residentStreamFallbackCannotSendAnUnclaimedSecondPost` 使用真实 `CompanionModelGateway.generate` + MockWebServer，分别设置单居民每日额度 1/2；模拟流式协议明确拒绝并预排成功的非流式响应，要求实际 POST 数与领取额度一致。额度 1 必须在第二次物理发送前失败；额度 2 可收到回复且仅第二次无 stream 字段。测试还检查已用额度，恢复测试前的政策偏好。无额外真实模型调用，不提高应用默认额度。
- 本轮只补真实网络回归，生产网关额度修复仍为先前 `23aa1c9`；**未取得本测试 SHA 的 JVM/APK 或代码索引成功证据**，不得勾工程验收。本地 GitHub DNS 失败，连接器按 commit 查询只覆盖 PR 触发，不能把空结果当作无 CI。
- 下一轮固定 **S-02.1**：直接打开 `ModelVisibleOutputTest.residentStreamFallbackCannotSendAnUnclaimedSecondPost`，按 main push Actions 的 head SHA 核 JVM/APK/Refresh code index 首错；若通过，审查 503 负载保护回退在同样额度 1/2 下的真实 POST 与后台去重门禁。中转站旧账/真机/真实模型仍待验，请求尝试≠成功生成≠服务商计费。

## 2026-11-09 · S-02.1 503 memory guard 居民额度网络回归

- 测试 [`3022fdc`](https://github.com/jiacimu-droid/lulu1/commit/3022fdceb81cfdc52f414fbf4f55aaac4b957081) 在 `ModelVisibleOutputTest.residentMemoryGuard503FallbackCannotExceedPhysicalPostQuota` 使用正式网关 + MockWebServer：503 Relay request-body memory protection 后，对居民额度 1/2 验证物理 POST 数、领取次数、错误或回复；额度 2 额外验证精简请求体仍包含本轮原话。生产代码未改，原额度不提高。
- 本轮未取得新 SHA 的 Android/JVM/APK 或 Refresh code index 成功结果，测试**已提交但未证实通过**。下一轮直接打开该测试并核 Actions 首错；若测试失败按真实错误修同一工作包，若通过再审后台请求去重/重启门禁。真机、真实模型与中转站账单仍待外部验证；HTTP 尝试≠成功≠实际计费。


## 2026-11-09 · S-02.1 可选记忆 503 不重复付费网关回归

- [测试 `2f14bb75`](https://github.com/jiacimu-droid/lulu1/commit/2f14bb75f6efb536d3d24922c7887c0d647e06dc)：`ModelVisibleOutputTest.optionalMemory503NeverBuysFallbackOrImmediateDuplicate` 使用真实 `CompanionModelGateway.generate` + MockWebServer。对可选记忆来源 `记忆/连续记忆提取`，第一 POST 收到 Relay memory-protection 503，断言不发送压缩回退 POST；相同原始证据立即重试必须得到 `BackgroundModelBudgetDeferred`，物理 POST 仍只有一次。与此前仅覆盖 `后台主动感知` 的 503 回退测试不同，验证两个来源政策边界；没有改动居民额度或人物。
- **根因复核**：`RelayRequestPressurePolicy.canRecover(source)` 当前仅允许 `后台主动感知`；因此上轮怀疑“可选记忆 503 第二次 claim 被自身去重拒绝”在当前源码下不可达，不应为此放宽 15 分钟防重。此次补正式网关回归锁定边界，不伪称已发现生产缺陷。
- **验收**：本轮新提交的 JVM/APK、Refresh code index 尚须以 Actions head SHA 复核；真机/真实模型/服务商计费未知。下一轮固定 S-02.1：检查本测试 CI 首错；通过后检查网关不同来源 503 的失败回执与延期队列重启恢复，避免重复付费但不丢来源。修改测试后本环境无法运行 `scripts/generate_code_index.py`（GitHub 容器 DNS 不可用），需核 Refresh code index 自动生成的真实产物。


## 2026-11-09 · S-02.1 记忆延期队列在飞行中追加事件与冷启动回归

- [测试 253e9d4](https://github.com/jiacimu-droid/lulu1/commit/253e9d446803c7be6cb34e0763e0e6fae793ea99)：新增 `MemoryExtractionCheckpointTest.deferredInFlightMemoryFailureKeepsLaterEventsAcrossRestart`。旧 revision 的后台请求尚在飞行时，新事件写入同一角色队列；旧请求随后被预算延期。测试要求新 revision/sourceReplyId 不丢、旧完成回执不能删新工作、延期截止前不重试、冷启动后仍保留并可在到期时领取。复用正式持久 Store，不购买模型请求。
- 本轮生产代码未改；该测试只验证队列状态机与重建，不冒充真实 HTTP 503 到队列完整集成。前轮 [APK/JVM #42563238412](https://github.com/jiacimu-droid/lulu1/actions/runs/42563238412) 的 build job 已查询为 completed/success；新测试提交的 JVM/APK、Refresh code index 仍待独立 head SHA 验收，真机/真实模型/服务商计费仍未验。
- **下一固定 S-02.1**：打开 `MemoryExtractionCheckpointTest.deferredInFlightMemoryFailureKeepsLaterEventsAcrossRestart` 并查询 `253e9d4` 的 main push Actions；若通过，补 `ChatMemoryAutomation` 实际失败/延期到 Store 的纵向回归，或在已覆盖时转 P-08a.1g.3。

## 2026-11-09 · S-02.1 真实 ChatMemoryAutomation.drain 延期/重建回归

- 接手 main 7d424c141af7987b8588fef88c11682f68abbbc1；读 AGENTS、CODE_INDEX、ROADMAP、此进度、ChatMemoryAutomation、MemoryExtractionJobStore 与 MemoryExtractionCheckpointTest。此前 253e9d4 Store 状态机测试已有后继 [APK/JVM #42570057063](https://github.com/jiacimu-droid/lulu1/actions/runs/42570057063) 成功基线，避免重复同一层测试。
- 生产边界提交 [171ff517](https://github.com/jiacimu-droid/lulu1/commit/171ff5172b9b7bccc48028b4195d1497a5b35861)、[70d51ce](https://github.com/jiacimu-droid/lulu1/commit/70d51ce32961342e59eefc855c63f13434b4908e)：只使 drain 的策略、summarize 和 now 可注入，线上默认仍走原 LuluRepositories.memory，不新增收费调用。
- 测试 [6861bc38](https://github.com/jiacimu-droid/lulu1/commit/6861bc381999a6b0e044b1da8475b94376ff05dc)：真实 drain 接到持久 MemoryExtractionJobStore，预算异常延期，飞行中新事件 revision 保留，Store 重建后截止前不执行、到期恢复清队列。假 summarizer 非真实 HTTP/进程死亡。
- 新提交对应 APK/JVM、Refresh code index 尚未得到 completed/success，工程验收不勾；真机/真实模型/服务商账单未知。请求尝试、成功与计费分别核对。
- 下一轮固定 S-02.1：查 6861bc38 的 main push Actions head SHA，失败修首错；成功后补 Gateway MockWebServer→drain→持久队列的网络纵向回归。若已由其他窗口覆盖则转 P-08a.1g.3。

## 2026-11-09 · S-02.1 drain 网络失败与持久重试回归

- 提交 [2dbe6bf](https://github.com/jiacimu-droid/lulu1/commit/2dbe6bf22c04d29aa2ccbdfa72ed27243d655fd5)：`MemoryExtractionCheckpointTest.realDrainNetworkFailureRetainsNewEvidenceAndWaitsThroughRestart` 使用真实 `ChatMemoryAutomation.drain`，注入模拟 IOException，期间入队新来源；断言旧请求失败计一次、修订与来源不丢、重建 Store 后 15 分钟内不重试、到期恢复且队列清空。仅测试改动，未发现可证实生产缺陷，不动正式逻辑。
- CI：本次容器访问 GitHub REST DNS 失败，PR-only workflow 查询不代表 push；未取得本测试 SHA 的 APK/JVM 或索引 success。真实 MockWebServer→summarizer→drain 及服务商计费/真机未验。下一轮固定 S-02.1：核 2dbe6bf 后继 Actions 首错；通过后补 Gateway 503 与持久队列的纵向网络回归，无法安全实现则转 P-08a.1g.3。

## 2026-11-09 · S-02.1 Gateway 503 → durable drain network regression

- Test commit [`c3ed58b`](https://github.com/jiacimu-droid/lulu1/commit/c3ed58b148c25ff5dc47cc1cf8a5e9b5dc38758c): `MemoryExtractionCheckpointTest.realGateway503FailurePersistsDirtyMemoryWithoutImmediateSecondPost` wires real `CompanionModelGateway.generate` and MockWebServer HTTP 503 into production `ChatMemoryAutomation.drain`, enqueues a later source revision during the request, verifies exactly one physical POST and persisted failure/backoff, reopens `MemoryExtractionJobStore`, asserts no early retry, then uses a controlled successful summarizer at the due instant to clear the retained work. This last step is not a second real HTTP request and does not establish provider billing or real-model behavior.
- This is a newly submitted test, **not yet a passed test**; no production logic changed. Main push APK/JVM and Refresh code index for this SHA must be checked; the connector's commit workflow endpoint is PR-only and direct github.com DNS is unavailable in the container. Do not mark engineering complete without actual CI evidence.
- Next S-02.1: check `c3ed58b` main push CI first; fix compile/test first failure if any. If passed, audit whether `LocalMemoryRepository.summarizeNow` can be exercised with a non-billed local connection override without compromising existing role configuration; otherwise switch to P-08a.1g.3 ToolRouter main-failure/secondary-success integration. Attempted POST ≠ successful generation ≠ provider billing.

## 2026-11-09 · S-02.1 drain cancellation durable checkpoint regression

- [Test a50b5dc](https://github.com/jiacimu-droid/lulu1/commit/a50b5dc17d72b00d350b9a94472254302d038b99) adds `MemoryExtractionCheckpointTest.cancelledDrainKeepsInFlightEvidenceWithoutChargingASecondAttempt`. Real `ChatMemoryAutomation.drain` is cancelled after a newer source arrives: cancellation must propagate, neither mark completion nor charge a failed extraction attempt, and persisted new revision must survive store reopen and resume. No production code or paid model calls changed.
- JVM/APK and Refresh code index for a50b5dc are **not yet verified**. GitHub commit-workflow connector lists PR runs only; direct GitHub REST and git from container fail DNS. Do not mark [x]. Previous c3ed58b Gateway 503 network test also still requires main-push Actions evidence. Next S-02.1: inspect the exact main-push JVM/APK and index run head SHAs for c3ed58b/a50b5dc, fix first test failure; if green, inspect LocalMemoryRepository.summarizeNow's real gateway injection or switch to P-08a.1g.3 ToolRouter integration. Billing remains externally unverified.

## 2026-11-09 · S-02.1 HTTP 503 跨 revision 退避根因修复（待新 CI）

- 直接查询 main push Actions：`c3ed58b` 的 [Lulu APK #42603366215](https://github.com/jiacimu-droid/lulu1/actions/runs/42603366215) 和 `a50b5dc` 的 [Lulu APK #42610569928](https://github.com/jiacimu-droid/lulu1/actions/runs/42610569928) 均失败；两个 Refresh code index 分别 [#42603366068](https://github.com/jiacimu-droid/lulu1/actions/runs/42603366068)、[#42610569915](https://github.com/jiacimu-droid/lulu1/actions/runs/42610569915) 成功。JVM 日志唯一失败：`MemoryExtractionCheckpointTest.realGateway503FailurePersistsDirtyMemoryWithoutImmediateSecondPost` 第 301 行 `failed.attempts` 预期 1 实际 0；HTTP 物理 POST=1 的上一条断言已通过，取消用例亦通过。
- 根因：`MemoryExtractionJobStore.failed(expected,error)` 原先只将 `IOException` 视为跨 revision 仍须退避的网络异常；`CompanionModelGateway` 对 503 抛出 `ModelHttpException : IllegalStateException`。在飞行期间追加新事件后，`failed` 的旧 revision 校验拒绝写入失败次数，导致下一轮立刻重试，可能重复计费。
- 正式修复 [f3a9ec5](https://github.com/jiacimu-droid/lulu1/commit/f3a9ec5237838f5f45054f583da360f110662f24)：HTTP 5xx 和 IOException 均保留跨 revision 退避；HTTP 4xx 仍只作用于原 revision，避免旧输入错误污染新证据。回归 [29c251a](https://github.com/jiacimu-droid/lulu1/commit/29c251aebc6642d30055774fe3d6ca7ad6e85c81)：503/400 对照及既有 MockWebServer→Gateway→drain→Store 重启纵向用例。
- **本轮修复提交的 APK/JVM 与 Refresh code index 尚未验通，不能打 [x]**。下一固定 S-02.1：按 `29c251a` head SHA 查 main push Actions 的 JVM 首错，失败继续修同一根因；成功后更新 Roadmap 工程验收证据，真机/真实模型/中转站账单仍独立待验。请求尝试≠成功回复≠服务商计费。

## 2026-11-10 S-02.1 工程 CI 核实与 P-08a.1g.3 最小纵向回归

- `29c251aebc6642d30055774fe3d6ca7ad6e85c81` 对应 main push [Lulu APK #42619476897](https://github.com/jiacimu-droid/lulu1/actions/runs/42619476897) 和 [Refresh code index #42619476889](https://github.com/jiacimu-droid/lulu1/actions/runs/42619476889) 均 completed/success。证明 503/400 对照回归与构建在该提交通过，不代表真实供应商计费或设备验收。
- P-08a.1g.3 在 `AutonomousActionBoundaryIntegrationTest.kt` 新增 `realPrimaryRejectionDoesNotEraseIndependentReplyOrItsDurableReceipts`：真实 ToolRouter 未知主工具拒绝，依赖副步骤跳过，独立私聊回复和动态经真实执行器发送；检查 ChatStore、MomentsStore、Timeline 独立回执，再以相同 requestId 重放防二次送达。提交 `c0a9913`、修正实际消息字段 `06c5271`；测试未取得 CI 通过证据，不称已验。
- 下一轮固定 P-08a.1g.3：按 `06c5271` 查询 APK/JVM 和索引；若失败先修测试首错，若通过再覆盖回复取消/重启后的持久因果，避免与 P-08b/P-03、G-02/G-03 重叠。真机/真实模型与中转站账单仍未验。

## 2026-11-10 · P-08a.1g.3 新用户输入打断副动作的持久回执回归

- 测试提交 [ed111f4](https://github.com/jiacimu-droid/lulu1/commit/ed111f4f2191dddb12719e6c9f65b49e07e3c263)：`AutonomousActionBoundaryIntegrationTest.newerUserBubbleDefersOnlyUnstartedActionsAndReplayDoesNotDuplicateDeliveredSteps` 使用正式 `OrderedCharacterActions.execute` 与 `ToolRouter`，第一步真实发布动态后由 `shouldDeferRemaining` 模拟收到更新用户气泡，第二步必须暂缓、无工具回执；由调用方明确恢复同 requestId 后，已送达第一步不得重复，第二步真实执行且两条独立回执可查询。该恢复调用是测试显式模拟，不等于运行时会自动重放旧轮。
- 本轮生产代码未改，测试未本地执行；`ed111f4` 的 main push APK/JVM、Refresh code index 尚未取得 head SHA 成功证据，不能勾工程验收。连接器 commit workflow 仅 PR 触发筛选，容器访问 GitHub DNS 失败；此前 `06c5271` 的 JVM/APK 也尚未得到可信通过证据。
- **下一固定 P-08a.1g.3**：先核 `ed111f4` 的 main push APK/JVM 与 Refresh code index，失败修首错；通过后检查正式 `ProactivePerceptionRuntime` 如何将新用户输入打断与下轮重新决策衔接，避免把测试显式重放当作产品自动行为。真机/真实模型待验；不新增收费调用或修改人设。

## 2026-11-10 · P-08a.1g.3 新输入触发的新决策与旧动作隔离

- 旧 ed111f4 对应 APK/JVM #42657981675 和索引 #42657981490 均 completed/success；旧 06c5271 对应 #42647784263/#42647784174 也 success，已排除前轮“CI 未知”的阻碍。
- 新增 [f5307df](https://github.com/jiacimu-droid/lulu1/commit/f5307dfe1fb323a827f884b5a9e370d5cac09710)：在 AutonomousActionBoundaryIntegrationTest 中模拟真实聊天新消息、旧轮第一动作送达/第二动作暂缓、不同 requestId 的新决定执行，验证旧未执行动作不会被自动重放、已完成旧回执仍在、新动作重放幂等。没有更改生产代码、人设、额度或模型调用。
- 本测试只证明执行器和 Store 的显式新决定路径；不证明 ProactivePerceptionRuntime 自动启动新轮或真实模型语义决定。f5307df 对应 APK/JVM 与 Refresh code index 尚待验，真机、跨进程、账单亦未验。下一轮固定 P-08a.1g.3：先核本 SHA 的 CI 首错，随后从 ProactivePerceptionRuntime 新消息批次调度入口补真正下一轮衔接测试，或转 P-08c.2。


## 2026-11-10 · P-08a.1g.3 正式在线批次的两轮模型网关回归（待 CI）

- [68b9973](https://github.com/jiacimu-droid/lulu1/commit/68b997322cc21fe9843b88242163bcfb08a4eee0) 新增 `AutonomousActionBoundaryIntegrationTest.freshUserBatchRunsAnotherRealModelDecisionWithoutReplayingPriorPost`：真实聊天用户输入 → OnlineChatBatchStore 新 revision → ProactivePerceptionRuntime.runDueCycle → MockWebServer 本地模型选择 → 真实 publish_moment → MomentsStore；第二次用户输入触发不同 revision，旧 revision 重放不得重复 HTTP 请求或旧动态。只新增测试，没有改生产代码或人设；不增加远端收费请求。
- 当前 GitHub 连接器的按提交 workflow 接口仅检索 PR runs，不能据空结果宣称 main push CI 通过/失败。本 SHA 的 APK/JVM、Refresh code index、真机、真实模型及服务商计费均待验。下一轮直接打开该测试，查 `68b9973` push Actions 首错，失败修测试或正式根因；成功后测试执行中取消与下一轮上下文回执，而非继续只验证显式新决定。

## 2026-11-10 · P-08a.1g.3 新批次模型输入承接旧回执断言（待 CI）

- [测试 0cac523](https://github.com/jiacimu-droid/lulu1/commit/0cac5231b351ffd62ce0b67510ac36c28d3491e3) 在 `AutonomousActionBoundaryIntegrationTest.freshUserBatchRunsAnotherRealModelDecisionWithoutReplayingPriorPost` 捕获 MockWebServer 实际第二次 HTTP 请求体，新增断言：第二次本人模型决策必须看到第一轮已真实发布的 `firstPost`。这验证跨批次因果上下文，而不只是两次模型调用和两条动态；若断言失败，优先检查正式 `ProactivePerceptionRuntime` 时间线召回/提示词组装，不靠关键词补丁。生产代码未改。
- 本提交尚无按 head SHA 核实的 JVM/APK 或 Refresh code index 成功结果，断言是否通过未知；真机/真实模型与供应商账单仍外部待验。容器 GitHub DNS 不可用，连接器仅能查询 PR 触发的 commit workflow runs，不能将空结果当成 push CI 结果。
- **下一轮固定 P-08a.1g.3**：先核 `0cac523` 的 main push APK/JVM 首错，若第二次模型请求体缺旧回执，追查 `ProactivePerceptionRuntime.evaluateCharacterWithActivity` 及 `SharedExperienceTimeline` 的实际上下文来源，补正式修复和反例；若通过再覆盖执行途中取消与新消息接管。

## 2026-11-10 · UI-02.1 真实 ChatStore 长历史写入测量夹具
- 提交 [`cd170a5`](https://github.com/jiacimu-droid/lulu1/commit/cd170a5ea0706debff009e63bfb6305cf2f48831)：新增 `ChatHistoryPersistenceBaselineTest.kt`。按正式 `state_v1` JSON 播种 1k/10k 消息，真实 Store 初始化→置顶元数据更新→再次初始化，断言消息不丢、置顶持久化并输出字节数和阶段耗时；100k 为 opt-in heavy。测的是端到端操作，**不能单独归因 JSON 编码、磁盘 fsync 或锁等待**。
- 正式 `MigratedDomainStores.kt` blob `5c52404f561c5c164f55a9c1aa112da5d3eb8e94` 的 `persistLocked` 仍全量 encode 后 `apply/commit`；本轮不改变生产格式、不删历史。CI 尚未取得 `cd170a5` 的 head SHA 对应 APK/JVM/索引结果；容器访问 `api.github.com` DNS 失败，未运行本地 Gradle。**不勾工程回归**，没有真实耗时数据。
- 下一固定 UI-02.1：先核 `cd170a5` main push JVM/APK 和 Refresh code index 首错，收集 1k/10k 计时，100k 在资源足够环境单独跑；再按证据决定 UI-02.2 的增量存储/无损迁移。P-08a.1g.3 与真机/真实模型仍待验；服务商计费数未知。


## 2026-11-10 · P-08b.1 / P-03.1 电话本人决定到可听台词的完整交接

- **接力依据**：main 的人物专项入口仍是 P-08b.1。现有 `RelationshipPreferenceGatewayTest` 已覆盖 Full/精简表达/CharacterAndScenario/PersonaAndScenario/Isolated 的关系投影及 Ensemble 私有资料隔离。本轮沿其未覆盖的正式电话入口，进入 P-03.1 的决定→表达交接。原 c8c37d 的 APK/JVM #41333088808 failure、索引 #41333088856 skipped；已修后继 8c5bf66 的 APK/JVM #41882293649、索引 #41882293653 均 success。上次 1a17c74 的索引 #41936349357 success，APK/JVM #41936349268 failure，日志显示 Test and build phone APK 到达 40 分钟超时。接手时最近可核的成功 APK 为 809f1e0 的 #42760365403；e3f27fe 的 #42761415401 为 in_progress。
- **源码证据与修复**：[4a8f729](https://github.com/jiacimu-droid/lulu1/commit/4a8f7291482506f771b2e0b946d4f558a747c0a0)。`LuluDeviceToolBridge.respond` 经同一角色 Full 网关提交电话决定。模型选 `reply` 并填 `speechIntent`、但缺 `text` 时，既有补写把已选意图截为 600 字、用户原话截为 700 字，并在 `CompanionModelGateway` 再获统一决策协议。现在沿用既有补写请求、电话连接及角色资料，完整传递选定意图与原话，使用 `CharacterExpressionGuide` 中已有表达交接原则及电话呈现规则。补写只返回口语，条件、否定及不确定性按原决定传递。正常回复仍用原决定直接交付，无新增常态请求。
- **回归与范围**：`ConversationExpressionGatewayTest.phoneIntentRecoveryKeepsTheCurrentPersonAndCompleteBoundaries` 通过正式电话入口捕获两个独立人物及编辑后的请求，覆盖正常电话、缺台词恢复、超过旧截断点的原话和意图、实际请求身份/性格/关系归属与隔离负例。正式私聊、后台、群聊、日程、工具执行及回执仍由各入口和原 Store 负责。该测试证明请求与分工契约，手机发声及真实模型对否定、条件与自主取舍的遵循需独立验收。
- **本轮 CI**：[Lulu APK #42762942772](https://github.com/jiacimu-droid/lulu1/actions/runs/42762942772) pending，[Refresh code index #42762942809](https://github.com/jiacimu-droid/lulu1/actions/runs/42762942809) completed/success。静态对照通过，当前环境无本地 Gradle/Android 测试或设备；实际单测、APK 结果以该源码提交的 Actions 为准。
- **下一轮固定 P-03.1**：直接打开上述测试，先按 `4a8f7291482506f771b2e0b946d4f558a747c0a0` 查 APK/JVM 和原索引。若红，读取首错并修同一工作包。若绿，核 `LuluDeviceToolBridge.respond` 的电话补写取消与真实交付边界，再做电话/私聊同样已选意图和副动作的对照；真实模型与真机仍待样本。

### 2026-11-10 UI-02.1 接力：长历史跨会话保全

- 正式 Robolectric `ChatHistoryPersistenceBaselineTest` 在 [d9a8675](https://github.com/jiacimu-droid/lulu1/commit/d9a8675f3e0343502a8c05f2cbae672a3bea0dd9) 加入独立第二会话：1k/10k 主会话元数据置顶后，断言另一会话在 SharedPreferences JSON 和重新初始化 Store 中仍保留原文，防止未来增量迁移只保留活跃会话。原测试 `cd170a5` 的索引 [#42757077702](https://github.com/jiacimu-droid/lulu1/actions/runs/42757077702) 成功、APK/JVM [#42757078292](https://github.com/jiacimu-droid/lulu1/actions/runs/42757078292) 失败；后继 APK/JVM [#42761415401](https://github.com/jiacimu-droid/lulu1/actions/runs/42761415401) 成功（head `e3f27fe`），覆盖原测试源码但没有可读取的计时输出。新增断言的 CI 仍待对应 head SHA 验证。
- 本轮没有生产格式迁移、收费模型调用或删除历史。下一固定 UI-02.1：检查 `d9a8675` 的 `ChatHistoryPersistenceBaselineTest` APK/JVM 首错与 Refresh code index；取得 1k/10k stdout 基线，100k 在大内存环境单独运行，之后才评估 UI-02.2。P-08a.1g.3 真机/模型独立待验。


## 2026-11-10 · UI-02.1 重复元数据写入成本与跨会话保全

- `d9a8675` 的 [APK/JVM #42763652639](https://github.com/jiacimu-droid/lulu1/actions/runs/42763652639) 与 [索引 #42763652738](https://github.com/jiacimu-droid/lulu1/actions/runs/42763652738) 均 completed/success（head SHA `d9a8675f3e0343502a8c05f2cbae672a3bea0dd9`）。这验证已有 1k/10k 两会话测试的工程执行，不提供真实设备 ANR 或数值基线。
- [233af13](https://github.com/jiacimu-droid/lulu1/commit/233af13f6b7df160632f5b9c3c12c84395f15fe7) 在同一 Robolectric 测试中计时 true→false→true 三次元数据写入，每次核 Store 状态，最终核原 JSON 两会话与冷恢复。避免只凭单次冷启动/CI 抖动判断全量序列化开销；未设任意性能阈值。正式 Store 未改、历史未清空。
- **下一固定 UI-02.1**：核 `233af13` main push APK/JVM 和 Refresh code index，取得 1k/10k 三次写入 stdout；若无 stdout 则不能声称测得耗时，转 UI-02.2 旧 `state_v1` 无损增量迁移回归夹具。100k、真机、真实模型和服务商计费均独立未验。


## 2026-11-10 · P-03.1 电话补写被打断时的副动作交接

- 上一轮 [4a8f729 的 APK/JVM #42762942772](https://github.com/jiacimu-droid/lulu1/actions/runs/42762942772) 已完成且成功。构建步骤、APK 收集和发布均通过；[原索引 #42762942809](https://github.com/jiacimu-droid/lulu1/actions/runs/42762942809) 同样成功。该提交的角色资料、关系修改和完整条件投影回归已随构建运行，真机和真实模型待验。
- 通话打断沿 `LuluVoiceCallSession.interruptSystemReplyForSpeech` 取消旧 `replyJob`。此前 `LuluDeviceToolBridge.respond` 会在等待补写台词之前登记延迟音乐、写其真实尝试回执并应用约定改动。用户在这次等待中继续说话时，旧台词被取消，前置副动作却已经开始。
- [bb1d54b](https://github.com/jiacimu-droid/lulu1/commit/bb1d54ba3dd416fdb3e9eb1252aa719c80f3ce23) 把原有缺台词补写移到该轮副动作开始之前，并在补写返回与进入副动作之间检查取消。仍有效的决定沿原权限和执行器处理，补写普通失败时独立动作仍能得到原执行回执。用户媒体更正与已实际交付的声音仍由原流程处理。模型调用次数和人设不变。
- 新回归 `ConversationExpressionGatewayTest.interruptedPhoneIntentRecoveryDoesNotStartOldSideActions` 通过正式 Bridge 和实际 HTTP 入口挂起补写，在请求已到后取消，验证旧轮无媒体尝试回执、后继有效轮有真实尝试回执。它复现任务取消时序；音频设备实际打断和远端计费待验。
- 本轮 CI：[Lulu APK #42775719430](https://github.com/jiacimu-droid/lulu1/actions/runs/42775719430) in_progress，[Refresh code index #42775719412](https://github.com/jiacimu-droid/lulu1/actions/runs/42775719412) completed/success。已核对两个修改文件的 blob SHA 和变更范围；本地无 Gradle/Android 设备。下一轮沿 P-03.1 先按 `bb1d54ba3dd416fdb3e9eb1252aa719c80f3ce23` 核对回归和索引；成功后比较同一份已选意图及独立副动作在电话和私聊的交付回执，优先查看 `LuluDeviceToolBridge.respond` 和本测试所在文件，不重复测试已经验证的角色隔离。

## 2026-11-10 · P-08a 真实叫醒承诺：睡眠时长问答→有声执行（待 CI／真机）

- 用户真机反馈：角色先问睡多久，主人回答“20分钟”，最终只用私聊文字“叫醒”，熟睡场景实际不可用。截图不足以单独确认手机当次是否存有闹钟，先核正式源码：`CommitmentTaskApplier.createCommitmentTask` 原本已算出 `wakeChoiceMissing` 却未并入 `needsClarification`，使绕过解析器的前台结构化任务仍可变成 `Scheduled + send_private_message`；`updateCommitmentTask` 可把旧有声渠道改期时降级为普通文字，且未知条件改期可能保留旧闹钟ID、却让状态变为 NeedsClarification，妨碍原到点执行。
- 源码修复 [c4d3c05](https://github.com/jiacimu-droid/lulu1/commit/c4d3c05ea63aeae280616e11f26650a44fb39e97)：创建时拦截定时叫醒的纯文字渠道；改期前先验证条件，非法修改不碰原闹钟；原有有声渠道在模型漏填或误选文字时继承，不擅自拨新电话。失败留独立回执，不假称已经叫醒。
- 上下文修复 [512d996](https://github.com/jiacimu-droid/lulu1/commit/512d9969448e3a6f47b75196e6f47e5f9bebb185)、[4d6912a](https://github.com/jiacimu-droid/lulu1/commit/4d6912ade2fc2ceb191bf04cc1d09f6fa70c1368)、[8cdec95](https://github.com/jiacimu-droid/lulu1/commit/8cdec952930d3fc4c24242714d676e9bb539eee5)：只把“上一轮角色问睡多久＋主人给明确时长＋本轮角色确实答应到点叫醒”作为一次廉价语义补救资格提示，模型而非关键词负责截止时间与来电/闹钟决定；未真正注册响铃的文本待定任务不能误被当作已覆盖。一般午睡闲聊、角色拒绝、模糊时长不能自动建闹钟。同步完善 `CommitmentDecisionProtocol` 的问答承接：[9569a5d](https://github.com/jiacimu-droid/lulu1/commit/9569a5db24b7a493a97f38df7225406596c268be)。
- 回归已提交、**未宣称运行成功**：[29650c6](https://github.com/jiacimu-droid/lulu1/commit/29650c6866374ab9385711b1212dbe8b86129430) 的 `CommitmentSemanticPersistenceTest` 验证前台非法文本叫醒不能成功排期、旧响铃改期不可降级/无效条件不毁旧闹钟；[aef4470](https://github.com/jiacimu-droid/lulu1/commit/aef4470e067fd1c7d82947d6bae21b758b21c44e) 的 `ChatAutomationRoutingTest` 验证时长问答、无承诺、拒绝、模糊时长的区别。
- 此连接器 commit workflow 返回的 PR runs 为空、combined-status 为空，**不代表 main push APK/JVM 成功或失败**；本地没有完整 Android 工程。下一轮先核最后生产 SHA `8cdec95` 及后继 main push Actions 的 JVM/APK 与索引首错，失败修根因；通过后做真机两次对照：“你睡多久→20分钟→角色答应叫醒”的可听见来电/闹钟/权限/时刻/反馈，以及只是询问睡眠时长不设闹钟。未核实当次设备真实送达、模型选择与中转站费用，不勾整体 P 项。


## 2026-11-10 · P-03.1 电话与私聊的独立日程步骤归属

- 上一轮 [bb1d54b APK/JVM #42775719430](https://github.com/jiacimu-droid/lulu1/actions/runs/42775719430) 和[原索引 #42775719412](https://github.com/jiacimu-droid/lulu1/actions/runs/42775719412) 均成功。已确认测试、构建和发布步骤通过。真实电话设备打断与真实模型仍待验。
- 对照 `LuluDeviceToolBridge.respond` 的同一结构化回复与独立副动作。私聊的 OrderedCharacterActions 将显式 `steps[].concernId` 填入缺少该值的日程参数。电话原来只把同一值交给 ToolRouter 的回执元数据。实际 `RoleDayScheduleStore.arrange` 只按工具参数校验所属本人及活跃状态，因此电话可能留下写着心事 ID 的回执，却另建一条无关联的日程。
- [66f1600](https://github.com/jiacimu-droid/lulu1/commit/66f1600bed184760c57300e6b14401143c21c353) 在现有电话副动作交接补齐显式链接，已有的参数优先，沿用当前权限、真实日程校验及独立回执。保留既有请求 ID 与模型决定，未添加模型调用或改动共享执行器。历史无关联日程不会被猜测补绑。
- `ConversationExpressionGatewayTest.phoneAndPrivateChatBothRejectAnUnownedConcernInAScheduleStep` 用同一模型 JSON 走电话及私聊 Bridge，私聊延后步骤交现有 OrderedCharacterActions。验证表达可交付且私聊表达收到完整否定条件，两边都以真实失败回执拒绝无归属心事且不创建日程。该用例不启动手机 UI、播放音频或测试真实模型语义。
- 本轮静态核对源码、文件 SHA 与作用范围。CI [Refresh code index #42791608402](https://github.com/jiacimu-droid/lulu1/actions/runs/42791608402) completed/success，[Lulu APK #42791608418](https://github.com/jiacimu-droid/lulu1/actions/runs/42791608418) in_progress。下一轮保持 P-03.1，先查本 SHA 对应的新回归。若成功，直接在同一测试文件补本人有效心事的正向绑定及同请求重放对照，再视证据进入其他媒介，勿把本次拒绝用例当作全入口已验收。


## 2026-11-10 · UI-02.2 旧 state_v1 编辑/删除冷恢复夹具

- [4b2be0c](https://github.com/jiacimu-droid/lulu1/commit/4b2be0cf6f1391ebb0a7120fb6773140c53fffcf) 新增 `ChatHistoryLegacyCompatibilityTest.legacyEditAndDeleteRemainDurableAcrossReopen`：真实旧双会话快照 → Store 初始化 → 编辑与删除 → SharedPreferences 落盘核对 → 新 Store 冷恢复；重复删除不应复活，另一会话及否定条件保持。未修改生产存储、历史或人物设定。
- UI-02.1 三次写入 run #42769855635 对应 `233af13` 为 completed/cancelled，无可用计时结果；此前 1k/10k 双会话回归 #42763652639 success，不等于性能验收。
- **下一轮固定 UI-02.2**：先查 `4b2be0c` 的 main push Lulu APK/JVM、Refresh code index 首错；通过后补真实增量迁移失败/中断/回退夹具，再按证据修改正式 Store。100k、真机 ANR、真实模型和中转账单独立待验；不可勾整体 UI-02。


## 2026-11-10 · P-03.1 本人心事跨媒介正向绑定与回执重放

- 上一轮 [66f1600 APK/JVM #42791608418](https://github.com/jiacimu-droid/lulu1/actions/runs/42791608418) 的测试、构建和发布均通过，[原索引 #42791608402](https://github.com/jiacimu-droid/lulu1/actions/runs/42791608402) 成功。电话与私聊拒绝无效心事 ID 的对照已纳入该版本。
- 路径：本人原话与挂心 → 同一 Store 中的有效 concernId → 电话或私聊本人明确选择的日程步骤 → 原 RoleDayScheduleStore → ToolRouter 独立持久回执 → 本人心事的上次实际尝试。ToolRouter 重放已完成请求会重新投影已有回执。原心事只在当前回执 ID 完全相同才跳过，旧电话回执 A 经过更新的私聊回执 B 后再次投影，可能把 B 覆盖回 A，虽然不会重新建日程。
- [e7dfa47](https://github.com/jiacimu-droid/lulu1/commit/e7dfa472aa91503aaa242d03aab94b3f08b575ed) 在原心事投影比较同角色的真实回执时刻。工具持久回执优先使用原执行结束时间，普通证据用原事件时间，旧字段缺失时保留现有兼容时间。迟到的旧投影不会覆盖较新的实际尝试，两个原始回执都仍可查询。沿用原 Store 与执行器，未改写模型选择、权限或调用次数。
- `ConversationExpressionGatewayTest.phoneScheduleReplayKeepsTheNewerPrivateChatOutcomeOnTheSameConcern` 从真实心事状态出发，分别经电话和私聊交接有效链接，创建两个不同时间的日程及独立回执，再重放电话决定。断言保留两条原日程与原回执，心事仍指向更新的私聊回执。重放由测试显式触发，用来验证执行器收到同请求时的行为。
- 静态核查文件 SHA 和改动范围。CI [Lulu APK #42808715035](https://github.com/jiacimu-droid/lulu1/actions/runs/42808715035) in_progress，[Refresh code index #42808715132](https://github.com/jiacimu-droid/lulu1/actions/runs/42808715132) completed/success。下一轮先按 `e7dfa472aa91503aaa242d03aab94b3f08b575ed` 核该测试及索引，失败修同一工作包。若通过，P-03.1 继续对照群聊或后台的条件步骤与真实回执，优先查看 `GroupChatTurnPlannerTest` 及 `AutonomousActionBoundaryIntegrationTest` 的既有覆盖；整个 P-03.1 仍待跨媒介与真实样本验证。

- **2026-11-10 UI-02.2 损坏快照防覆盖**：失败复现 [`65e3029`](https://github.com/jiacimu-droid/lulu1/commit/65e3029d92a47f318eb131f310c1adf0840d223a)；正式修复 [`6e5bd7d`](https://github.com/jiacimu-droid/lulu1/commit/6e5bd7deb9eebcc11b62267b74a96fd63cd524b4)。旧 `state_v1` JSON 无法解析或缺核心结构时标记不可写，拒绝后续默认会话写回覆盖原始快照；有效旧格式照常读取。**新提交 JVM/APK/索引、真机尚未验；UI-02.2 不勾完成。** 下一轮直接查 `ChatHistoryLegacyCompatibilityTest.malformedLegacySnapshotMustNotBeSilentlyOverwritten` 的 CI 首错，再补格式局部损坏/重启恢复反例；后续事务迁移仍待做。


## 2026-11-10 · P-03.1 群聊成员的原始证据交接

- 上一轮 [e7dfa47 APK/JVM #42808715035](https://github.com/jiacimu-droid/lulu1/actions/runs/42808715035) 的测试、构建和发布均通过，[原索引 #42808715132](https://github.com/jiacimu-droid/lulu1/actions/runs/42808715132) 成功。已核实电话和私聊的有效心事绑定、旧回执重放工程回归。
- `runGroupReplies` 先把成员专属的 `messageId:group:roleId` 给本人规划，送达后的同轮步骤却收到原始群消息 ID。ToolRouter 会解析并存入成员专属 causeEvidenceId，但默认 curiosity.sourceEvidenceId 仍读取传入值，重放比较也会将原始 ID 与已保存的成员证据比较。同一决定在表达与动作两边因此有两种来源。
- [d79cc50](https://github.com/jiacimu-droid/lulu1/commit/d79cc50a533d7e1fbe951795bbbd195b563c03ff) 在群聊适配器生成一次成员专属证据，规划与已送达后的独立副动作共用。消息发送水位仍用原始聊天 ID，原工具权限、依赖规则、独立回执、模型调用次数不变。历史数据不猜测补绑。
- `ConversationExpressionGatewayTest.groupDeferredStepsKeepTheSpeakersEvidenceAndActualDependencyResults` 从正式群导演和本人 HTTP 入口开始，核实际群发言与真实收藏失败回执、依赖步骤不执行、独立朋友圈真正发布、回执和好奇来源都归发言者的群证据。该测试不证明真实模型语义或手机界面体验。
- CI [Lulu APK #42826571378](https://github.com/jiacimu-droid/lulu1/actions/runs/42826571378) in_progress，[Refresh code index #42826571444](https://github.com/jiacimu-droid/lulu1/actions/runs/42826571444) completed/success。静态核对目标文件 SHA 及差异。下一轮按 `d79cc50a533d7e1fbe951795bbbd195b563c03ff` 查上述回归和索引，失败修同一工作包。若通过，打开 `AutonomousActionBoundaryIntegrationTest.freshUserBatchRunsAnotherRealModelDecisionWithoutReplayingPriorPost`，继续 P-03.1 后台同轮依赖步骤和下一轮真实回执注入的交接，避开已覆盖的群聊送达取消。


## 2026-11-10 · UI-02.2 权威时间字段不可猜测回归

- 接手 main `fc95d06fa8f474ca2f2d04b806a6068c16641546`，先读 AGENTS 全文、CODE_INDEX/ROADMAP 对应段及正式 Store 和历史兼容测试；双游戏近期提交未覆盖 UI-02.2。源码发现 `decodeMessage` 的宽容时间回退可能将无效 `createdAt` 解释为当前时间并在下一次全量写入中永久替换原始证据。
- [测试 d67228d](https://github.com/jiacimu-droid/lulu1/commit/d67228d4ac67928123e0814ea2c874db1672dfc4)：`ChatHistoryLegacyCompatibilityTest.malformedMessageTimestampMustNotBeRewrittenAsCurrentTime`，损坏原始时间→真实 Store 初始化→置顶→重开→再次置顶，断言原始 `state_v1` 字节不变。
- [正式修复 3a01b80](https://github.com/jiacimu-droid/lulu1/commit/3a01b8070d0f699395f6fb291cde3fb10f011814)：仅在权威快照读取时要求 `Instant.parse(createdAt)` 成功，避免宽容解码自动补时间；复用既有不可覆盖保护，不更改非权威导入、人设、居民预算或收费模型调用。
- **CI 未验**：本轮没有得到 `3a01b80` 对应 main push APK/JVM、Refresh code index 的 completed/success，亦未运行本地 Gradle；真机、强杀、事务迁移仍未知。不能勾 UI-02.2。下一轮直接核本 SHA 的 Actions 首错，随后调查会话 `updatedAt` 的时间回退与写入失败的持久回滚，只有测试揭示缺口才继续修改生产代码。


## 2026-11-10 · P-03.1 后台新批次承接本人实际失败回执

- 上一轮 [d79cc50 APK/JVM #42826571378](https://github.com/jiacimu-droid/lulu1/actions/runs/42826571378) 与[原索引 #42826571444](https://github.com/jiacimu-droid/lulu1/actions/runs/42826571444) 均成功。群聊副动作的成员专属证据对照已随 CI 运行。
- 原链路：后台本人决策 → 原工具与有条件步骤 → ToolRouter 真实回执 → CharacterInnerLifeStore 心事 lastOutcome → CharacterUnfinishedActionContext → 下次实际模型输入。工作集之前只投影 lastOutcome.summary，缺动作、成败及证据 ID，且不核验回执是否仍可追溯。成功的独立动作与同轮失败容易在下一轮被混读。
- [637f3c5](https://github.com/jiacimu-droid/lulu1/commit/637f3c58ec6312b7a49e2e0fa5297cee1062e01f) 复用已有同角色时间线查询，在心事工作集中交接上次真实尝试的动作、成败、原回执 ID 与摘要。查不到原回执时明确说不可用。已保存事实、注意力预算、共享执行器、人物选择及请求次数沿用原实现。
- 扩展 `AutonomousActionBoundaryIntegrationTest.freshUserBatchRunsAnotherRealModelDecisionWithoutReplayingPriorPost`，由同一后台决定选择发布、失败的收藏、仅在收藏成功后发布的依赖步骤。核真实失败回执、依赖发布未发生，以及下一次同角色 HTTP 请求同时收到真实发布和带来源的失败；原过期批次不得重复调用的断言保留。该测试覆盖契约及实际本地工具链，真实模型与设备体验待验。
- 本轮静态核查文件 SHA 与改动范围。CI [Refresh code index #42845477645](https://github.com/jiacimu-droid/lulu1/actions/runs/42845477645) in_progress，[Lulu APK #42845477728](https://github.com/jiacimu-droid/lulu1/actions/runs/42845477728) in_progress。下一轮沿 P-03.1 先查本 SHA 的首错或通过结果。通过后先验来源撤回导致回执不可用时的输入边界，优先打开上述测试及 `CharacterUnfinishedActionContext`。工程子项的完成证据交总大纲归并。


### 2026-11-11 · UI-02.2 会话 updatedAt 权威时间保护（待 CI）

- 回归 `9a2e88b84e1d9c96db50751c49320364e5856c7c`：`ChatHistoryLegacyCompatibilityTest.malformedConversationTimestampMustNotReplaceOriginalSnapshot` 以旧 `state_v1` 会话无效 `updatedAt` + 合法消息复现，覆盖置顶写入、重建及再次写入，要求原快照字节文本不被替换。
- 正式修复 `6639d80a293b541f967e80947ffd545b9f4986a9`：权威 `decode()` 在 `decodeConversation()` 的宽容 `toInstantOrNow()` 之前严格 `Instant.parse(updatedAt)`，无效则沿既有不可覆盖状态保护原文。未改普通导入、人物、预算、存储格式或收费模型调用。
- 本轮 GitHub main push Actions 查询未取得可用结果；**未运行本地 JVM/APK，不勾工程通过**。下一轮固定 UI-02.2：先核 `6639d80` 对应 APK/JVM 和 Refresh code index 的 head SHA/首错；通过后优先覆盖 SharedPreferences 写入失败与重启回退，再决定事务性增量存储。真机强杀、10万条性能与供应商计费独立待验。


## 2026-11-11 · P-03.1 撤回后统一心事回执投影

- 上一轮 [637f3c5 APK/JVM #42845477728](https://github.com/jiacimu-droid/lulu1/actions/runs/42845477728) 与[原索引 #42845477645](https://github.com/jiacimu-droid/lulu1/actions/runs/42845477645) 均 completed/success。路线图已划掉后台带回真实成败和 ID 的子步骤，撤回边界继续待验。
- P-03.1 根因：`CharacterUnfinishedActionContext` 已验证回执仍存在，但共享完整/精简心理上下文与电话/私聊的已有心事输入仍直接读 `lastOutcome` 旧副本。工具回执撤回后，原心事与其用户来源可以继续有效，旧尝试却可从另一段提示被重新当成已证实的成败。
- [6186744](https://github.com/jiacimu-droid/lulu1/commit/618674423d95d09f02806d5c04f820a8c38da7e8) 在原心事运行时统一可见回执投影，同角色时间线可验证才给动作、成败、ID 和摘要，原回执缺失则只标不可用。后台复用现有批量证据，完整/精简心事上下文和电话/私聊补正也走同一规则。原心事、其他实际行为、原 Store 和模型调用次数不变。这一修复限定心事的上次尝试字段，独立历史行动摘要不在本工作包内。
- 续接 `AutonomousActionBoundaryIntegrationTest.freshUserBatchRunsAnotherRealModelDecisionWithoutReplayingPriorPost`，实际完成独立发布、产生收藏失败回执、在下一轮看到回执，然后撤回该回执并捕获第三轮请求。核心事仍在、不可用提示出现、两种心事投影均无原成败及 ID、独立发布仍可召回；并补纯投影正反例。
- 静态核对文件 SHA 与改动范围。CI [Lulu APK #42865903439](https://github.com/jiacimu-droid/lulu1/actions/runs/42865903439) pending，[Refresh code index #42865903302](https://github.com/jiacimu-droid/lulu1/actions/runs/42865903302) in_progress。下一轮先按 `618674423d95d09f02806d5c04f820a8c38da7e8` 核第三轮输入回归及索引。通过后此撤回子步骤可归并到大纲，再沿 P-08b.1 检查正式入口的最新人物设置与隔离关系，优先打开 `RelationshipPreferenceGatewayTest`，找仍缺的编辑后跨轮反例。真机与真实模型待验。

## 2026-11-11 · P-08d.2b.2 有声失败事实跨重载与下一轮输入
- `15ef0a99dfb972566980e6e8334d70608394e2e0` 的 main push [Lulu APK/JVM #42898573257](https://github.com/jiacimu-droid/lulu1/actions/runs/42898573257) 和 [Refresh code index #42898573526](https://github.com/jiacimu-droid/lulu1/actions/runs/42898573526) 已核 completed/success；此前重载闹钟测试工程验收通过，真机/模型仍未知。
- 新增回归 [6d64e6b](https://github.com/jiacimu-droid/lulu1/commit/6d64e6bd42715c58fe2907782628ba6537ca672b)：`CommitmentAlarmRecoveryTest.failedWakeVoiceReceiptSurvivesColdReloadAndRemainsVisibleToNextDecision`。使用真实 `CommitmentCallFeedback.onVoiceFailed` 写入播放失败，重建 `CommitmentTaskStore`，检查 Blocked、原失败回执、尝试次数及 `ActiveCommitmentAttention.render` 下轮投影；禁止把连接/播放失败等同已醒。测试待本 SHA 的 main push JVM/APK/索引，未修改生产逻辑。
- 下一轮固定 P-08d.2b.2：核新回归 CI 首错，若通过继续验证正式角色模型输入包含失败事实以及新决定/取消的实际执行；真机 TTS/权限、真实模型与跨日独立待验。


## 2026-11-11 · P-08b.1c 长关系约定的完整保存与编辑后跨入口投影

- 上轮 [6186744 APK/JVM #42865903439](https://github.com/jiacimu-droid/lulu1/actions/runs/42865903439) 与[原索引 #42865903302](https://github.com/jiacimu-droid/lulu1/actions/runs/42865903302) 已核 completed/success。心事回执撤回后的第三轮后台输入工程回归通过，真机与真实模型仍待验。
- 本轮按 P-08b.1 进入最新人物定义的编辑后反例。调用链为角色/关系编辑 → CharacterLifeStore.setRelationshipAgreement → 原 SharedPreferences → CharacterSocialRelationship → CharacterRuntime → 正式电话或精简表达网关。保存函数先把 title 截为 70 字、details 截为 1600 字，末尾条件或否定在进入任何模型前便永久丢失。现有网关长约定夹具没有超过 1600 字，未覆盖这一保存缺口。
- [52f0624](https://github.com/jiacimu-droid/lulu1/commit/52f0624bd6d02f184e28ada9e9d9c5c0ecb1bc17) 移除上述两处保存截断，完整保留用户当前编写的关系标题与说明，继续使用同一 Store、归属校验和清空语义。没有修改任何角色预设、共享执行器、取舍原则或模型调用次数。旧版本已丢失的文字无法凭程序恢复，需要重新保存原文。
- 新增 CharacterLifeStoreTest.authoredRelationshipKeepsCompleteBoundariesAcrossReloadEditAndClear，覆盖超限标题与说明真实落盘、重新加载、替换、清空和另一角色不受影响。新增 RelationshipPreferenceGatewayTest.editedLongRelationshipSurvivesFailedPhoneTurnAndReachesNextExpression，以江渡/星禾差异化样本走真实电话 Bridge 和网关 HTTP。先返回 401，再修改身份、人格、语言习惯、示例和长关系约定，重载后核后继电话及精简表达的实际请求携带完整新条件且只出现一次，另一角色和 Isolated 无串线，清空后不补旧约定。固定响应只用于检查投影与调用契约，不作真实语言质量证据。
- 修改前已核 main 与三个目标文件 blob SHA，提交以 expected_sha 租约推进 main，保留并行游戏及承诺工作。本地只有 Java，未运行本地 Gradle/JVM/APK。原生成器已由既有 [Refresh code index #42924717775](https://github.com/jiacimu-droid/lulu1/actions/runs/42924717775) 完成，CODE_INDEX 基准确为 52f0624；[Lulu APK/JVM #42924717735](https://github.com/jiacimu-droid/lulu1/actions/runs/42924717735) 仍 in_progress，新增测试及 APK 不能写作已通过。真机、真实模型和长时负载待验，不勾整体 P-08b。
- 下一轮固定 P-08b.1c，先按完整 head_sha 52f0624bd6d02f184e28ada9e9d9c5c0ecb1bc17 核上述两个新增测试的 CI 首错或通过结果。通过后接着打开 RelationshipPreferenceGatewayTest，补 PersonaAndScenario 在人物编辑后的跨世界投影对照，验证保留最新稳定性格与表达、排除原世界身份和对象关系。已有五模式覆盖不重复重写，任何新生产修改须由该反例证实。ROADMAP 顶部交总接力归并。


## 2026-11-11 · P-08b.1c 正式跑团的多人设定交接

- 前轮 `52f0624` 的 [Lulu APK/JVM #42924717735](https://github.com/jiacimu-droid/lulu1/actions/runs/42924717735) 实际失败，2689 项中 1 失败、1 跳过。首错为 `RelationshipPreferenceGatewayTest.editedLongRelationshipSurvivesFailedPhoneTurnAndReachesNextExpression` 重载时约定为空。并行窗口的 `bfd7158` 已补齐正式初始化和真实落盘检查，`500cc83` 拒绝存储未就绪时的虚假成功，`f85ddb1` 修正编辑框截断和保存失败后的草稿保留。两份后继 APK/JVM [#42931549522](https://github.com/jiacimu-droid/lulu1/actions/runs/42931549522) 与 [#42932289426](https://github.com/jiacimu-droid/lulu1/actions/runs/42932289426) 及各自索引均通过。路线图已有该工程子步骤的验收证据。
- 平行世界实际入口检查定位到 `FormalRoleplayCampaignV2`。主持裁定及正文都是多人主持任务，原先却给网关传队首角色 ID 并用 PersonaAndScenario，系统提示因此要求以队首本人参与，同时只有队首获得完整分层设定。调用方给其他成员的材料只有角色简介，缺该成员最新的分层人格和语言习惯。调整出场顺序即可改变哪个成员获得完整设定。
- [6a4b2fa](https://github.com/jiacimu-droid/lulu1/commit/6a4b2fa5a9f19d77d6563459fb0c86b740950d21) 在正式跑团以现有 `CharacterRuntime.personaConstraintSnapshot` 按成员 ID 读最新稳定设定。同一轮裁定、正文和已有重试共用这份名单。主持请求使用现有 Isolated 投影，仅携带明确提供的角色设定和战役资料，结构化原世界身份、对象关系和日常记忆不进入主持请求。保留现有裁定结果到叙事的交接和调用次数。
- `RelationshipPreferenceGatewayTest.campaignHostUsesEachMembersEditedPersonaAndOnlyCampaignFacts` 捕获正式主持适配器发出的三次 HTTP 请求。江渡排在星禾之后，编辑江渡人格和表达后核两阶段各角色块、完整条件与否定、原世界身份和关系隔离及无多余调用。静态核对本轮两文件及并行修改，原索引 [#42939624809](https://github.com/jiacimu-droid/lulu1/actions/runs/42939624809) 已通过。Lulu APK/JVM [#42939624813](https://github.com/jiacimu-droid/lulu1/actions/runs/42939624813) completed/failure，2694 项中 1 失败、1 跳过。完整日志确认本轮新增跑团用例 PASSED，整套 JVM 被 `AutonomousActionBoundaryIntegrationTest.failedWakeVoiceSurvivesReloadIntoActualNextModelHttpInput` 第 665 行 `NoSuchElementException` 阻断，APK 未完成。该首错已在本轮之前的 `717cc0d` [#42934042626](https://github.com/jiacimu-droid/lulu1/actions/runs/42934042626) 同样出现，交 P-08d 所属窗口处理。真机跑团、真实模型的角色差异及取舍仍待验证。
- 下一轮沿 P-08b.1c 核带本次改动的后继完整 CI。若 P-08d 首错尚未修复，单独保留 APK 待验并继续可实施子步骤，打开 `RelationshipPreferenceGatewayTest.currentAuthoredRelationshipReachesRealWorldModesWithoutKeywordRoleOverlay`，核正式单角色 PersonaAndScenario 的下一轮是否继续使用编辑后的稳定人设。ROADMAP 顶部由总接力归并。


## 2026-11-11 · P-08b.1c 平行世界隔离原世界对话示例

- 前轮 `6a4b2fa` 的跑团用例在后继 `35d7521` [JVM/APK #42948321217](https://github.com/jiacimu-droid/lulu1/actions/runs/42948321217) 仍为 PASSED。该后继索引 #42948321298 成功，整包仍被 P-08d 的叫醒回执重载用例阻断，报 `Wake task missing after store reload`。
- 复查 `ApocalypseSurvivalV4Engine.writeApocalypseV4Scene` → `CompanionModelGateway` → `CharacterSpeechIdentity`。PersonaAndScenario 原来排除了独立身份及对象关系，却无条件读取并注入完整对话示例。角色自己的示例可以包含原世界地点、称呼、关系和具体互动，这些内容随系统消息进入跨世界场景。稳定人格和表达习惯本来已由权威设定单独提供。
- [34341e5](https://github.com/jiacimu-droid/lulu1/commit/34341e58b4b0855cf8720f0f6d5db2de6b773d75) 在既有表达投影中按场景跳过原世界对话示例，并将从交流学到的表达习惯限定在原世界模式。平行世界继续收到当前稳定人格和用户设定的说话习惯。原世界私聊保留用户编辑的示例，调用次数未增加。本轮范围为网关的表达投影，用户自己写在自由人设或显式场景素材中的内容仍由原输入负责。
- `RelationshipPreferenceGatewayTest.crossWorldKeepsEditedTemperamentWithoutOriginalWorldDialogueScenes` 使用 V4 正文的网关调用配置捕获真实 HTTP 请求。核江渡编辑前后、星禾插入回合、完整长条件和否定、原世界身份与关系和示例的隔离，以及原世界精简表达仍收到新示例。用例在 [Lulu APK/JVM #42954102373](https://github.com/jiacimu-droid/lulu1/actions/runs/42954102373) 日志确认为 PASSED。完整 JVM 2699 项，1 失败、1 跳过，唯一失败仍为前述 P-08d 重载用例，APK 未产出。原索引 [#42954102416](https://github.com/jiacimu-droid/lulu1/actions/runs/42954102416) completed/success，基准 `34341e58b4b0855cf8720f0f6d5db2de6b773d75`。真机及真实模型待验。
- 下轮固定 P-08b.1c 先核包含本提交的后继全量 JVM/APK，整包成功后交总接力归并此工程子步骤。若仍卡 P-08d，继续检查 `CompanionModelGateway` 的 Isolated 定义读取和头像准备是否仅由显式场景素材触发，先找正式调用与可复现反例，再定生产改动。
