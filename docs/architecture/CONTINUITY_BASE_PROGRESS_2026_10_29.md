# 统一人物连续性 · 基础闭环专项进度

本文件只记录 P0-A/P0-B/P1/P4 基础执行恢复、来源版本、跨入口回执诊断和长期数据生命周期的增量工作。总约束仍见 [CONTINUITY_EXECUTION_PLAN_2026_10_28.md](CONTINUITY_EXECUTION_PLAN_2026_10_28.md)。不覆盖社交、内驱想法和 Live2D 专项进度。源码已提交、CI 成功、APK 构建成功、真机通过是四种独立证据。

## 2026-10-29 · P0-A 居民被拒绝日程不得因权限恢复而偷排

- **真实根因**：`ResidentDailyDecision.saveAgenda` 记录结构合法但现场权限/资源不满足的行时，使用稳定 `native-daily-...-agenda-rejected-N` 原始事件 ID；但下一次恢复同一已付费模型决定时，只核对原已保存日程，未把该行的既有拒绝回执作为终态。联系人后来出现、群成员权限变化或资源恢复后，原来已被拒绝的行可能变成新日程；同一拒绝再次写入还可能因为新的 `occurredAt` 改写 revision。即使主人撤回拒绝事件，原始墓碑仍应阻止旧决定复活。
- **源码修复**：在唯一 `SharedExperienceTimeline` 增加只读 `hasRecordedOrWithdrawnEvent(eventId)`，查询现有 `timeline_events` 与 `deleted_timeline_events`，不新建状态中心；`ResidentDailyDecision.saveAgenda` 先核对本次稳定拒绝 ID，已拒绝/已撤回的行不再安排或重新写入回执。其他从未被拒绝的行仍可按原保存决定继续恢复；原先已安排的行仍沿权威 `RoleDayScheduleStore` 幂等恢复。
- **回归源码**：`ResidentDailyContinuityTest.rejectedAgendaNeverBecomesExecutableAfterContactRestoredOrReceiptWithdrawn` 使用两位真实居民和实际世界位置，先拒绝未具联系权限的私信日程，同时保存独立动态日程；对方随后真实到场，重放旧决定不得新建私信日程，拒绝 revision 不增加；撤回原拒绝后墓碑仍阻止复活；新一天的本人新决定在权限满足时仍可独立排入。
- **main 提交**：`59b5d6b03343205ff73868a607075e6c2d959a85`（原始账本只读查询）、`a165ee965907665cde53dc47a0488a73fc80639f`（居民原决定重放门禁）、`380d117539fc869175b329fa1b824417b7508eb5`（根因回归）。
- **验证边界**：源码与测试已提交；CI/JVM/APK 及真实手机强杀、权限变化与多日连续性须以对应提交的工作流及设备实测单独核验，不能据测试文件存在宣布通过。不会因拒绝自动重购模型或让程序替居民选新的行动。原始拒绝事实可由本人下一次正常模型轮次看到，但是否重新安排只能由新的本人决定。
- **并发协作**：本轮只改 `SharedExperienceTimeline.kt` 的最小只读查询、`ResidentDailyDecision.kt`、`ResidentDailyContinuityTest.kt` 和本专项进度；未触碰社交、内驱、Live2D 文件，也未修改总清单以免与其他任务发生覆盖。源码索引由现有 Refresh code index 工作流生成，不能手工伪造。
