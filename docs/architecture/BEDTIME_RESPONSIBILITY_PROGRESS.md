# 主动关心与哄睡监督：执行核查

## 2026-11-18 跨日睡眠周期：前夜提醒动作和持续结果责任分离

主人反馈：23:00 角色已执行睡前提醒，日程可能标记完成，但主人没有睡着，次日 03:00 再叫醒角色时，角色因新一天的 23:00 日程而没自然接续前夜督睡。此前 11-02 修复了 WaitingForFeedback 跨日丢失，但「已完成的 delivery-only 日程/已滚动的每日任务」依然容易从正常人物上下文中消失。**不是新增固定凌晨闹钟**。

- `CommitmentTask.recentDailyOutcomeContext`：对于 16 小时内的上一轮 daily attempt，将真正未确认的结果，或仅完成投递的动作历史，带回同一任务的阅读上下文。结果已核实成功的 outcome 轮次不复活；取消任务不进入活动责任；新 23:00 时刻保持原样。
- `PersistentResponsibilityAttention`：有超过 12 个活跃约定时，为有时效的上一轮未核实结果/纯投递回执保留最多 2 个注意力位置；`ActiveCommitmentAttention` 区分旧轮次已送达、生活目标未知和下一次约定，不把 03:00 变成新的每日 23:00。
- `RoleDayPlan.recentExecutionReceiptContext`：展示 16 小时内真实已执行并有 resultEvidenceId 的个人日程动作；无论一次性已完成还是每日已滚动，不因自然日变化吞掉回执，绝不由工具送达推断主人已睡着。
- `CommitmentDecisionProtocol` 同一场景模型提示明确 23:00 已提醒、03:00 主人又亲口说明未入睡的因果关系。若**此前实际接受**持续督睡，角色继续按原责任和可得证据选择合理下一步；若只承诺一次投递，不未经同意擅自提升为强制持续监督。行动/静默/下一次复查时刻由同一角色选择，复用 `schedule_commitment_review`；不引入固定每分钟 API、无限催睡或无许可查屏。
- 新增 `ProactiveQuietDecisionTest.lastNightDeliveredReminderStaysInRoleAttentionAfterDailyRollover` 与 `RoleDayScheduleStoreTest.yesterdayExecutedPlanReceiptDoesNotProveOwnerSlept`：模拟 23:00 发送、03:00 继续讨论、次晚日程仍保留、旧轮次未证实与已结案区分，以及取消、过期、无证据门禁。
- 生产提交：`040779ea`、`c3c4de01`、`07d50d91`；测试：`89206c65`、`b9602de1`。首次对应新主分支 [APK/JVM #44158433831](https://github.com/jiacimu-droid/lulu1/actions/runs/44158433831) 检查时为 pending；并行 push 可能取消该次运行，需核含上述提交的后继 main APK/JVM 与索引，不能以 pending/cancelled 当成功。本轮未取得手机安装版/原人物任务存档与真实模型行为的实机验证。
- 待验：真正角色只对已接受的持续督睡形成跨日跟进，不强制睡眠结论；03:00 主人再次发言进入同一人物决策后，角色自然理解昨晚和今夜的区别，若选择稍后再看必须有真实 review/plan 的持久回执；再次确认入睡后停止无需的额外打扰。原生屏幕/通话/通知权限保持既有授权门禁。


## 2026-11-02 本轮

用户场景：23:30已提醒，用户还要学习、洗澡；角色要求用户准备好再主动告知，随后仅在心声里关心、怕打扰，没有继续落实监督。截图仅证明当时的展示与心声，未取得用户手机任务库、实际安装提交或运行日志，不能据此认定所有原因已复现。

### 已证实的公共机制缺口及修改

- `CommitmentAlarmDelivery` 的 finally 无条件调用每日滚动；`advanceDailyAfterAttempt` 原来允许 WaitingForFeedback/Blocked 在没有未来复查时转成明天的 Scheduled，覆盖本轮未完成责任。现要求原任务已经 Completed 才滚动。提醒弹窗、电话接通、静默、执行失败均不构成结案；不更换原taskId，不补拨。
- 重启的 `restoreMissedDailyCalls` 原来仍会将 Blocked 滚到明天。现只恢复 Completed 或明确错过执行窗口的 Expired；已尝试但效果未知/执行失败的责任保留，Blocked同WaitingForFeedback进入原有有界交接恢复。错过初始执行的历史事实不伪装履约。
- 原后台回合收尾仅在 silent且无steps时检查未交接责任。发过“准备好再告诉我”或执行了无关工具会绕过恢复。现在所有行动类型收尾均检查现有权威任务与实际后续安排；通话中交原通话处理。复用现有每角色每小时至多一次的异常恢复通道，没有新增常态付费轮询，系统不替角色选下一次联系时间或媒介。
- 原同次决策输入明确：已接受的监督无论本轮是否说话，都需要继承真实后续安排或同轮本人安排复查；学习/洗澡是进度变化，不能自动理解为取消或必须等用户报备。没有改用户人设、语气或私人记忆。

### 回归与证据界限

新增原Store回归：每日提醒已尝试但无复查、执行受阻、跨重启仍保留原时刻/状态/执行token/次数，仍进入未交接上下文；可靠结案后仍按原每日时钟滚动同一任务。更新原提示组装回归，覆盖回复后也应交接。

本地缺少Gradle及Android SDK，不能声称Android单测或APK已经通过。以本次提交对应既有Lulu APK流程（包含testDebugUnitTest）结果为准；手机后台持续运行、截图授权与锁屏路径、真实模型是否按人设主动联系仍待真机验证。

### 下一轮重点

1. 读取本轮提交CI实际结果，若失败按日志修复，避免重复实施以上改动。
2. 核实用户安装版本及任务真实状态：是否只有motive/concern/个人日程而缺少已接受责任task；跟踪私聊/电话commitmentChanges进入同一Store及既有有限补正。
3. 检查个人日程弹窗结束后原目标/挂心是否带回原ID和真实回执、选择的review是否已登记；不要用新增关键词脚本替角色判断。
4. 设备亮屏、锁屏、前台App、截图失败只提供线索，不等于睡着/没睡。沿已授权的现有capture_screen及UserDevicePerception核对事实、失败回执与同一角色后续判断。

已设一个每两小时的本专项任务；旧开发任务保持停用。以本文件和最新main为续改入口，完成后不为制造进展而改代码。
