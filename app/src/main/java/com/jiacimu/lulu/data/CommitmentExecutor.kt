package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/** Executes an already-accepted responsibility at its due time. The local alarm is the offline base. */
internal object CommitmentExecutor {
    suspend fun onAlarm(
        context: Context,
        claimedTask: CommitmentTask,
        characterName: String,
        now: Instant = Instant.now(),
    ) {
        val attempt = claimedTask.attemptCount + 1
        val wakeTask = claimedTask.isWakeResponsibility()
        val character = MigratedDomainStores.characters.get(claimedTask.characterId)
        val scheduledCall = claimedTask.deliveryAction == "start_call"
        val useCall = scheduledCall || (wakeTask && attempt >= 2 && character.contactPolicy.proactiveCallsEnabled)
        val action = if (useCall) "start_call" else "send_private_message"
        val wording = when {
            scheduledCall -> "之前说好要给你打电话：${claimedTask.goal}。我现在按照约定来电。"
            useCall -> "之前答应了${claimedTask.goal}，第一次还没有确认结果，所以现在按约定再打一次电话确认。"
            wakeTask && attempt == 1 -> "${claimedTask.goal}。我来叫你啦，醒了告诉我一声，我才会把这件事算完成。"
            wakeTask -> "${claimedTask.goal}。这是最后一次有限重试；醒了告诉我一声。"
            else -> "之前答应你的事到时间了：${claimedTask.goal}。完成或不需要了都可以直接告诉我。"
        }
        val actionResult = CompanionActionRuntime.execute(
            context = context.applicationContext,
            characterId = claimedTask.characterId,
            action = action,
            args = JSONObject().put("text", wording),
            now = now,
        )

        if (scheduledCall && actionResult.success && actionResult.conversationId != null) {
            ProactivePerceptionRuntime.showPromisedCallNotification(
                context.applicationContext,
                claimedTask.characterId,
                actionResult.conversationId,
                wording,
            )
        }

        // Exactly one retry for wake-up responsibilities. A retry alarm is a new one-shot step token.
        val retryAt = if (!scheduledCall && wakeTask && attempt == 1) now.plusSeconds(10 * 60L) else null
        val retryAlarm = retryAt?.let { at ->
            com.jiacimu.lulu.system.LuluAlarmSystem.create(
                claimedTask.characterId,
                characterName,
                at,
                "${claimedTask.goal}（最后一次确认）",
            ).getOrNull()
        }

        CommitmentTaskStore.update(claimedTask.id) { current ->
            current.copy(
                status = if (!actionResult.success) CommitmentTaskStatus.Blocked
                    else if (scheduledCall) CommitmentTaskStatus.Completed
                    else CommitmentTaskStatus.WaitingForFeedback,
                attemptCount = attempt,
                nextCheckAt = retryAt,
                linkedAlarmId = retryAlarm?.id,
                lastActionResult = buildString {
                    append("约定到期，执行器已尝试履行；")
                    if (actionResult.success) {
                        append(if (useCall) "已实际发起主动来电（不等于用户已经接听）" else "已发送确认消息")
                    } else {
                        append("附加${if (useCall) "来电" else "消息"}执行失败：${actionResult.summary.take(160)}")
                    }
                    if (retryAlarm != null) append("；未确认前仅安排一次最终重试")
                    else if (retryAt != null) append("；最终重试安排失败，停止继续追")
                    else if (scheduledCall) append(if (actionResult.success) "；电话邀约已发出，本次拨号责任已执行" else "；来电受阻，已留存失败原因")
                    else append("；等待用户明确反馈，不再自动追加重试")
                },
            )
        }
    }
}

private fun CommitmentTask.isWakeResponsibility(): Boolean {
    val text = "$goal $completionCondition".lowercase()
    return listOf("叫醒", "起床", "醒来", "wake", "睡醒").any(text::contains)
}
