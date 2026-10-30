package com.jiacimu.lulu.data

import com.jiacimu.lulu.system.LuluAlarmSystem

/** User interaction with incoming-call UI; answering does not prove wakefulness. */
internal object CommitmentCallFeedback {
    fun onResponse(taskId: String, answered: Boolean) {
        val task = CommitmentTaskStore.snapshot().firstOrNull { it.id == taskId } ?: return
        if (task.status != CommitmentTaskStatus.WaitingForFeedback) return
        // A response is one delivery fact. Completion is evaluated against the
        // original result condition by the same evidence gate as every other goal.
        val keepReview = task.keepsFollowUpAfterCallResponse(answered)
        if (!keepReview) task.linkedAlarmId?.let(LuluAlarmSystem::cancel)
        CommitmentTaskStore.update(taskId) { old ->
            old.copy(
                status = if (!answered && !keepReview) CommitmentTaskStatus.Blocked
                    else CommitmentTaskStatus.WaitingForFeedback,
                linkedAlarmId = if (keepReview) old.linkedAlarmId else null,
                nextCheckAt = if (keepReview) old.nextCheckAt else null,
                followUpKind = if (keepReview) old.followUpKind else "",
                lastActionResult = if (!answered)
                    "用户拒接本次来电，不继续自动拨号；目标是否达成仍未知"
                else "电话已实际连接（${java.time.Instant.now()}）；仅证明接通，未核实目标结果",
            )
        }
    }

    /** Playback of a local cue is evidence of the attempt, not wakefulness. */
    fun onInCallRingtone(taskId: String, callId: String, started: Boolean) {
        CommitmentTaskStore.update(taskId) { old ->
            if (!old.isWakeResponsibility() ||
                old.status !in setOf(CommitmentTaskStatus.Running, CommitmentTaskStatus.WaitingForFeedback)) old
            else old.copy(
                wakeAttemptHistory = (old.wakeAttemptHistory +
                    "通话${callId.take(12)}：本轮本机铃声${if (started) "已真正启动" else "未启动"}；主人是否醒来仍未知").takeLast(6),
            )
        }
    }

    fun onVoiceStarted(taskId: String, callId: String) {
        CommitmentTaskStore.update(taskId) { old ->
            if (old.status != CommitmentTaskStatus.WaitingForFeedback || !old.isWakeResponsibility()) old
            else old.copy(lastActionResult = "叫醒语音开始播放（${java.time.Instant.now()}；会话=${callId.take(12)}）；尚未播完，也未确认用户醒来")
        }
    }

    fun onVoiceDelivered(taskId: String, callId: String) {
        CommitmentTaskStore.update(taskId) { old ->
            if (old.status != CommitmentTaskStatus.WaitingForFeedback || !old.isWakeResponsibility()) old
            else old.copy(
                lastActionResult = "本次叫醒语音已完整送达播放器（${java.time.Instant.now()}；会话=${callId.take(12)}）；尚未收到用户明确已醒的反馈",
                wakeAttemptHistory = (old.wakeAttemptHistory +
                    "通话${callId.take(12)}：本轮叫醒语音已完整播放；并不证明主人已醒").takeLast(6),
            )
        }
    }

    fun onVoiceFailed(taskId: String, callId: String, error: String) {
        CommitmentTaskStore.update(taskId) { old ->
            if (old.status != CommitmentTaskStatus.WaitingForFeedback || !old.isWakeResponsibility()) old
            else old.copy(status = CommitmentTaskStatus.Blocked,
                lastActionResult = "电话已接通，但本次叫醒语音播放失败（${java.time.Instant.now()}；会话=${callId.take(12)}）：${error.take(110)}；不算成功叫醒",
                wakeAttemptHistory = (old.wakeAttemptHistory +
                    "通话${callId.take(12)}：本轮语音播放失败（${error.take(90)}），不能当成已听见或已醒").takeLast(6),
            )
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
