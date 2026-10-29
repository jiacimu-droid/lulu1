# 自主社交专项进度

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
