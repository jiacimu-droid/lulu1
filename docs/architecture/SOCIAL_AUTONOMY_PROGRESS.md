# 自主社交专项进度

## 2026-10-29 S4：本人看见评论后能真正定向接话

- **分类与基线**：7d2e3257 的索引 #40839986477 success、APK #40839986524 cancelled；本人在场候选修复在最新 main，不重做。S1/S2 有独立关系与邮箱回归历史证据，完整自然表现仍待真机；S3 已有约定活动身份门槛，762fd9c4 对应 APK #40868016796 现已 cancelled，不计验通；S4 已有动态、好友 ACL、本人手机红点与已读快照。多年群体自然表现为待验/可选增强，不重建状态中心。
- **真实缺口**：CharacterPhoneInbox 只提供评论正文、没有 commentId；comment_moment 只收 postId/text，MomentsStore 对同一角色在一帖下已有顶层评论直接拒绝。已有的回复字段/UI/持久化只能由用户回复通道写入，角色与居民自己的正常决定无法准确回复另一条评论，也无法在首次评论后继续原话题。
- **实际接线**：原手机上下文显示真实 commentId、作者与回复引用；原 comment_moment 允许可选 replyToCommentId，由同一帖子实际评论解析目标作者，不接受模型虚构目标身份。MomentsStore 沿原评论集合写入 replyToCommentId/replyToCharacterId，引用不存在或不属本帖则失败；不降级为顶层发言。本人明确的后续评论不再受“一人一帖一次”的固定限制，原显式呼唤自动反应的重复顶层保护保留。
- **事实与执行**：原 CompanionActionRuntime 返回实际评论 evidenceId，经原 ToolRouter 请求 ID 幂等和回执恢复；不改 ToolRouter/主网关/统一人物存储。仅本人写入自己的评论行动事实，其他好友仍待原正常手机感知，第三人无权限不会看到。不增加自动回复循环、模型请求或唤醒全体好友。
- **回归源码**：新增 MomentConversationContinuationTest，沿实际发布→手机评论 ID→ToolRouter 评论/回复→原请求重放→旧快照不吞新活动→本人再次接话→已有 Store 重载，另验跨帖/虚构目标、撤好友后拒绝、第三人不可见与后续顶层选择合法。角色间无需互为好友，只需对原帖有真实查看权限，保持现行“作者好友可看所有评论”契约。
- **验证边界**：本机没有 Gradle/Android SDK，新增 JVM 尚待本提交原 CI 执行，不能按测试文件存在统计通过。旧 MomentsStore 的 apply 持久化与 ToolRouter 存在进程死亡间隙，本轮没有宣称跨 Store 原子事务；强杀恢复、真实模型愿意接话及多日自然社交仍需单独真机验收。最小跨文件接线限 CharacterPhoneInbox、MomentsStore、CompanionActionRuntime 及独立回归；其他专项文件不动。

## 2026-10-29 S3：约定活动不能被同一对象的另一件事冒充完成

- 开工并提交前核对最新 main 与其他专项；本轮从上轮未推进的 S3 着手。已有日程会唤醒本人重新决定，ToolRouter 执行前调用 canBegin，真实成功回执再调用 completeFromReceipt；不新增人物循环、关系中心或模型调用。
- 真实缺口：完成门槛仅比较工具、到期时间和人物，明确安排 `continue_episode + incidentId` 后，对同一居民的一次 `greet` 也能满足，导致共同活动尚未执行就投影为已完成。
- RoleDayScheduleStore 的同一权威门槛现核对原参数中明确给出的 worldAction、activityId、incidentId、itemId、targetCharacterId。不同活动/事件不能借用原 planId 执行或完成；不解释标题，不比较自由文字，不替本人决定想做什么。无身份参数的旧日程保留原门槛。
- 同步本人到期上下文：说明身份引用、准备动作不携带最终 planId、想换活动由本人取消原项再安排；非身份参数仍由本人在原决策轮调整。公共 ToolRouter 无修改，最小接线只在既有日程的入口与回执共用门槛内。
- 新回归覆盖出发、打招呼、另一段共同事件、另一角色均不能完成原活动；相同活动/事件可提交本次新的真实文字；旧空参数兼容。截图中的小红书服务端登录超时另在原 MCP 适配与部署脚本修复，细节见 tools/xhs-xpzouying/README.md。
- 本地小红书脚本回归 67/67 通过，含旧登录补丁升级、重复运行、未知接口拒绝覆盖及实际生成网页探针。JVM/APK 以本次提交 CI 为准；本机无 Gradle/Go/Android SDK。S3 双方邀约、拒绝、改期、到场、共同活动和事后关系仍未完整真机验通，不宣称整项完成；S4 群体互动仍待自然体验验证。

## 2026-10-29：S1/S2 同场社交对象归属

- 已核对大纲与最新 main、统一契约、成长框架和旧执行清单；好友申请已通过 CharacterPhoneInbox.requestContext 进入后台与居民决定，不能重复称为未接线。
- 真实缺口：CharacterEverydaySocialContext 仅按地点筛选居民，没有排除当前观察者，原住民会在“此处确实在场的居民”中看到自己作为可交互对象。
- 修复沿已有 ResidentCharacterBridge 将居民ID映射成角色ID，再排除本人；同场其他居民保留，异地居民不进入。未修改人物语义、关系存储或请求频率。
- 在 ResidentDailyContinuityTest 增加双向观察回归，覆盖本人排除、同场他人保留、异地排除和世界位置不变。
- 已运行原索引生成器与 git diff --check。本地没有 Gradle/Android 构建环境，JVM与APK等待本提交对应的既有CI；真机未验。索引由既有工作流同步。
- 后续优先内驱 D1/D2/T1-T3，避免重复本次社交候选修复；再验邀约双方独立回应、实际到场与关系变化完整链。

## 工作分配

- 基础与面对面任务：原 P0/P1/P4 及 L1-L3 交替推进。
- 社交与内驱任务：S1-S4 及 D1-D2/T1-T3 交替推进。
- 账号最多5个定时任务，保留3项学习任务，因此开发使用2个每小时任务；各自读取最新main，提交前整合新变更，不强推、不重建已有模块。

## 2026-10-29 S2：并发私信不能互相覆盖，首次联系必须有真实人物和场景

### 基线与职责

- 核对 main 索引提交 7bbcc49、现行 AGENTS/统一契约/成长框架、旧连续性清单、内驱与 Live2D 独立进度；本轮只改 ResidentSocialMailbox 与独立邮箱回归，不触碰其他专项。四项开发任务已各自独立，前文“两项轮换”的工作分配属于历史。
- 7d2e3257 的索引 #40839986477 已 success，APK #40839986524 被后续提交取消，不能报告它独立构建通过；本人在场候选修复仍在源码，不重复实施。
- S1 已有独立关系视角及私信刺激归属回归，完整自然互动/真机仍待验；S2 已有真实信箱/唤醒/独立回应，发现下列保存与首次联系缺口；S3 邀约/到场仍需专项完整实测；S4 已有朋友圈/群聊入口，群体自然表现属待验/后续增强，不能因此重建状态中心。

### 根因与源码

- send 原先未同步：两个并发发送都从同一旧 messages 列表构造新列表，即使两次 commit 都成功，后一次仍可能覆盖另一封；双方时间线已存在但收件箱/手机联系人找不到那封信，破坏后续交流。
- 发送现在与已有 inbox/pendingIncoming/acknowledgeIncoming 共用邮箱对象锁，覆盖消息持久化和双方原始观察写入。读者不会在较早一封信尚无观察事件时先确认较晚的一封，避免跨过未完整交接的记录。仍使用同一偏好存储、原时间线与唤醒器，无新队列或额外模型请求。
- canContact 原来只验证收件者存在，且把默认“现实世界”标签相同当作空间相遇；未知发送者或没有数字现场的两个新角色可能凭空成为联系人。现同时验证双方角色存在，仅数字人物才能用当前位置作为首次接触依据；已有真实私信、见面、共同群聊及确认好友依旧沿原事实来源允许联系，不替双方判断关系。

### 回归与边界

- 新增 ResidentMailDeliveryAtomicityTest，直接拦截真实 SharedPreferences 的第一笔提交并发起第二笔发送，验证第二笔不能越过第一笔、两封信均持久保留、双方可追溯观察、第三人不可见、重载后均未读及逐封确认。另验持久化失败不产生送达事实、未知发送者/默认现实标签拒绝、确认好友后正常首次联系。测试不用模型或额外网络。
- 已运行 scripts/generate_code_index.py 和 git diff --check；索引由仓库原有工作流同步。执行环境没有 Gradle，新增 JVM 必须以此提交的既有 CI 为准。真机多线程/杀进程未验；此锁修复不是“偏好存储与 SQLite 跨进程事务”声明，真实进程在两个存储之间死亡的恢复仍交基础专项。
- 发现当前基线 APK #40862044670 实际 failure：1947 项测试完成，CharacterExplorationContinuityTest.eachOrderedToolCarriesItsQuestionAndAnUnseenResultCannotMarkItSatisfied 的 exploring/satisfied 断言失败。属于内驱探索状态契约，未在社交任务中偷改断言或掩盖。新构建需区分这项既有阻断与本轮邮箱回归；整体 APK 未通过时不得报发布成功。

### 本轮实际验证结果

- 源码与回归提交：[51f70e33](https://github.com/jiacimu-droid/lulu1/commit/51f70e331a948efc57110c6f1c9759140142d0a6)。索引 [#40863097284](https://github.com/jiacimu-droid/lulu1/actions/runs/40863097284) completed/success。
- [Lulu APK #40863097204](https://github.com/jiacimu-droid/lulu1/actions/runs/40863097204) completed/failure，实际已编译并跑完1950项JVM测试，1项失败。新增 ResidentMailDeliveryAtomicityTest 的3项回归均通过；唯一失败仍是原基线的 CharacterExplorationContinuityTest.eachOrderedToolCarriesItsQuestionAndAnUnseenResultCannotMarkItSatisfied（expected exploring / actual satisfied）。没有改旧断言来冒充通过。
- 该既有失败阻止后续 assemblePhone/收集/发布；不能报告新APK成功。社交的源码与JVM证据已取得，真机并发发送、后台唤醒与强杀恢复仍分别待验。下一轮继续 S3 双方邀约及到场链，先核对基础/内驱专项是否已经消除这一公共构建阻断。

### S3 本轮提交与验证记录

- 源码提交 [762fd9c4](https://github.com/jiacimu-droid/lulu1/commit/762fd9c4029391440f3c577ec4b88fdbb01fe30c)；索引 [#40868016881](https://github.com/jiacimu-droid/lulu1/actions/runs/40868016881) completed/success，索引提交 c0b9c6b。
- [APK #40868016796](https://github.com/jiacimu-droid/lulu1/actions/runs/40868016796) 已进入 Test and build phone APK，记录时 in_progress，尚无 JVM 总结果或 APK 发布证据，不能按通过统计。
- 额外验证固定 v2.5.0 的真实 login.go：原版 → V2 → V3、再次应用 V2/V3 均成功，保留账号/扫码辅助函数；这只证明源码转换与幂等，不冒充 Go 编译或真机登录成功。

### S4 首轮 CI 实证与重载回归环境修正

- 源码及回归提交 [cb10e513](https://github.com/jiacimu-droid/lulu1/commit/cb10e5134dee7c5ef9945d485cb0be2115ac39db)；原索引 [#40871402522](https://github.com/jiacimu-droid/lulu1/actions/runs/40871402522) completed/success，CODE_INDEX 基准已核对指向该 SHA。
- [APK #40871402521](https://github.com/jiacimu-droid/lulu1/actions/runs/40871402521) completed/failure：1966 项 JVM 测试中 1 项失败，新增三项中的两项通过；完整回复链的发送、目标、幂等、红点、隔离断言均已执行，唯一失败在最后重载时找不到原帖。APK 没有生成/发布。
- 定位测试的静态 MomentsStore.prefs 跨 Robolectric 测试应用复用：此前写入可能使用上一测试的偏好实例，末尾却从当前 Application 重新打开文件。测试现每项开头重新绑定 MomentsStore 到当前应用，并在重载前明确检查当前 backing file 已包含最后一条评论 ID；保留原重载后回复对象相等断言，不修改生产解码或删掉断言。修正仍须新 CI 证实。
- 额外核对上轮 S3 的 #40868016796 完整日志：UTC 11:52 的 testDebugUnitTest 阶段结束后已进入 compilePhoneKotlin，随后取消；该轮 JVM 阶段已完成，phone APK/发布仍未完成。不能把整轮 cancelled 当作 APK 成功。

### S4 本轮最终核对（2026-10-29）

- 测试隔离修正提交 [f2b04208](https://github.com/jiacimu-droid/lulu1/commit/f2b04208858b3e653666c13fa1e9d44dd2f70ad3)，索引 [#40872881804](https://github.com/jiacimu-droid/lulu1/actions/runs/40872881804) completed/success。其 [APK #40872882104](https://github.com/jiacimu-droid/lulu1/actions/runs/40872882104) cancelled，日志只到 debug 编译，不能声称修正后 JVM 通过。
- 并行提交 [6eb8d39f](https://github.com/jiacimu-droid/lulu1/commit/6eb8d39f800e081b51d8d0036efb0a15caa638df) 改同一个 MomentsStore.mutate 为同步 commit 成功后才发布 StateFlow。已重新读取并确认本轮回复字段、目标核验及原工具入口保留；没有用本地旧文件覆盖它。该改动补强前文提及的 apply 耐久性边界，但跨 Store 强杀恢复仍需设备验证。
- 包含双方改动的 [APK #40873243256](https://github.com/jiacimu-droid/lulu1/actions/runs/40873243256) 又在 debug 编译期间 cancelled。最新核对到 main 的 [c516a450 APK #40873792513](https://github.com/jiacimu-droid/lulu1/actions/runs/40873792513) in_progress，索引 #40873792608 success；该提交仅增加其他专项测试，定向回复与修正后的重载回归仍在 main。
- 当前结论：源码接通，首轮 1966 项 JVM 中 1965 通过、1 项新重载回归失败后已修测试隔离；修正后完整 JVM/APK 尚无通过证据。无新 APK 可宣称发布，真机与跨天自然互动未验。下一轮先追包含 f2b04208 和 6eb8d39f 的最新 completed CI，若重载仍失败继续定位真实 backing file，不删除断言或反复重建回复功能。

## 2026-10-29 续验：S4 重载失败已通过后续完整 JVM 阶段

- 重读最新 main、社交及其他三项进度；AGENTS、统一人物契约、成长框架、10-28 连续性计划与上一轮逐字相同，沿用已完整阅读的约束。现有代码索引基准 3ac0c806；不重复本人在场候选、好友申请接线或定向回复源码。
- 已下载核对包含 cb10e513、f2b04208 和 6eb8d39f 的后续实际 job 日志：c516a450 的 [#40873792513](https://github.com/jiacimu-droid/lulu1/actions/runs/40873792513) 在北京时间20:35执行 testDebugUnitTest，20:36进入 compilePhoneKotlin；d1bec4ac 的 [#40874727954](https://github.com/jiacimu-droid/lulu1/actions/runs/40874727954) 在20:43执行单测、20:44进入phone编译；d761bea0 的 [#40875897832](https://github.com/jiacimu-droid/lulu1/actions/runs/40875897832) 在20:50执行单测、20:52进入phone编译。三轮均没有 testDebugUnitTest FAILED，均在 phone 编译之后被新 main 取消。既有工作流单 worker、未开启 --continue，因此这证明修正后的完整 JVM 阶段通过，不能把取消的整轮报为 APK 成功。
- S4 本轮的三项回归（完整回复/重载/原请求幂等/旧快照红点/独立证据；无效引用及撤权；无固定顶层评论配额）仍保留且已随上述 suite 执行。此前“修正后 JVM 尚无通过证据”的记录由本节补足；真实人物自主选择、真机、多日社交仍未验。
- 当前 [3ac0c806 APK #40877079954](https://github.com/jiacimu-droid/lulu1/actions/runs/40877079954) 最后核对仍在 Test and build phone APK；索引 #40877079824 success。最新实际 release 仍为 v6609（c10614b9），不把它当作包含本轮社交改动的安装包。未取得新APK，不提供虚假下载地址。
- 截图的 check_login_status/context deadline exceeded 再核对仍对应已有 V3 服务端修复：arm64-proot.sh 在 repair-login 路径升级原/V2 源码、保留Cookie/Token、复用运行实例DISPLAY并成功编译后重启。手机端原120秒预算和网页账号状态等待已有，不能以再次改手机超时替代更新实际服务。重新运行脚本67项回归、bash语法检查及差异检查均通过；真实手机服务升级/Go编译/登录与搜索正文仍须现场验。
- 本轮完成上轮未确认的自动回归验收并提供服务器升级步骤，不制造重复源码变更。S1/S2既有回归基础、S3完整邀请/拒绝/改期待验、S4自动回复链通过与真机体验未验继续分开记录；无新增模型请求、定时任务、PR或Issue。
