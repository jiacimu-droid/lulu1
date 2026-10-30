package com.jiacimu.lulu.data

import com.jiacimu.lulu.system.LuluAlarmSystem
import java.time.Instant

/** A model-selected result check retains the original goal, date and attempt budget. */
internal fun scheduleCommitmentReview(characterId: String, draft: CommitmentTaskDraft,
    now: Instant = Instant.now()): CommitmentDraftOutcome {
    fun failed(note: String) = CommitmentDraftOutcome("review", draft.goal, null, note)
    val task = CommitmentTaskStore.active(characterId).firstOrNull { it.id == draft.targetTaskId }
        ?: return failed("没有找到本角色仍未完成的原任务")
    val at = draft.dueAt ?: return failed("结果复查缺少角色选定的明确ISO时间")
    if (!at.isAfter(now) || draft.verificationReason.isBlank())
        return failed("结果复查需要未来时间及本人的具体理由")
    if (task.status == CommitmentTaskStatus.Running)
        return failed("原任务执行器仍在运行，等待实际回执后再安排复查")
    if (task.followUpKind == "verification" && task.nextCheckAt == at &&
        LuluAlarmSystem.list().any { it.id == task.linkedAlarmId &&
            it.characterId == characterId && it.commitmentTaskId == task.id })
        return CommitmentDraftOutcome("review", task.goal, task.id, "相同结果复查已保存，没有重复创建")
    val name = MigratedDomainStores.characters.get(characterId).displayName
    val alarm = LuluAlarmSystem.create(characterId, name, at,
        "${task.goal.take(90)}（角色自主复查）", silentCallback = true,
        commitmentTaskId = task.id).getOrElse {
        return failed("复查未能安排：${it.message.orEmpty().take(150)}；原有任务及后续安排保留")
    }
    val updated = CommitmentTaskStore.update(task.id) { current ->
        if (current.revision != task.revision || !current.status.isActive()) current
        else current.copy(status = CommitmentTaskStatus.WaitingForFeedback,
            linkedAlarmId = alarm.id, nextCheckAt = at, followUpKind = "verification",
            lastActionResult = "角色已安排${at}再次核查；理由：${draft.verificationReason}；目标尚未确认")
    }
    if (updated?.linkedAlarmId != alarm.id) {
        LuluAlarmSystem.cancel(alarm.id)
        return failed("保存复查时原任务已改变；未覆盖新决定，也未取消原有安排")
    }
    task.linkedAlarmId?.takeIf { it != alarm.id }?.let(LuluAlarmSystem::cancel)
    CommitmentLexiconSync.syncTask(updated)
    return CommitmentDraftOutcome("review", task.goal, task.id, "已保存静默结果复查；不会自动重拨，未改原约定时间或重置尝试次数")
}
