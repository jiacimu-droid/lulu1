package com.jiacimu.lulu.data

import com.jiacimu.lulu.system.LuluAlarmSystem

/** User interaction with incoming-call UI; answering does not prove wakefulness. */
internal object CommitmentCallFeedback {
    fun onResponse(taskId: String, answered: Boolean) {
        val task = CommitmentTaskStore.snapshot().firstOrNull { it.id == taskId } ?: return
        if (task.status != CommitmentTaskStatus.WaitingForFeedback) return
        task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        CommitmentTaskStore.update(taskId) { old ->
            old.copy(
                status = if (!answered) CommitmentTaskStatus.Blocked
                    else if (old.isWakeResponsibility()) CommitmentTaskStatus.WaitingForFeedback
                    else CommitmentTaskStatus.Completed,
                linkedAlarmId = null,
                nextCheckAt = null,
                lastActionResult = if (!answered) "用户主动拒绝本次来电，已停止自动重拨"
                    else if (old.isWakeResponsibility()) "用户点击接听，未确认已醒；等待明确反馈，不继续自动重拨"
                    else "用户点击接听，已收到本次电话",
            )
        }
    }

    /** Connected is not the same as audible; keep these independent facts. */
    fun onVoiceStarted(taskId: String, callId: String) {
        CommitmentTaskStore.update(taskId) { old ->
            if (old.status != CommitmentTaskStatus.WaitingForFeedback || !old.isWakeResponsibility()) old
            else old.copy(lastActionResult = "来电已接通；本次叫醒语音开始播放（会话=${callId.take(12)}）；尚未播完，也未确认用户醒来")
        }
    }

    fun onVoiceDelivered(taskId: String, callId: String) {
        CommitmentTaskStore.update(taskId) { old ->
            if (old.status != CommitmentTaskStatus.WaitingForFeedback || !old.isWakeResponsibility()) old
            else old.copy(lastActionResult = "本次叫醒语音已完整送达播放器（会话=${callId.take(12)}）；尚未收到用户明确已醒的反馈")
        }
    }

    fun onVoiceFailed(taskId: String, callId: String, error: String) {
        CommitmentTaskStore.update(taskId) { old ->
            if (old.status != CommitmentTaskStatus.WaitingForFeedback || !old.isWakeResponsibility()) old
            else old.copy(status = CommitmentTaskStatus.Blocked,
                lastActionResult = "电话已接通，但本次叫醒语音播放失败（会话=${callId.take(12)}）：${error.take(110)}；不算成功叫醒")
        }
    }

    fun onMissed(taskId: String) {
        val task = CommitmentTaskStore.snapshot().firstOrNull { it.id == taskId } ?: return
        if (task.status != CommitmentTaskStatus.WaitingForFeedback) return
        CommitmentTaskStore.update(taskId) { old ->
            old.copy(lastActionResult = if (old.linkedAlarmId != null)
                "来电超时无人接听，等待已安排的有限重试"
                else "来电超时无人接听，未确认目标完成；无后续自动重拨计划")
        }
    }
}
