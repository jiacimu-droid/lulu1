package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluAlarmSystem
import org.json.JSONObject
import java.time.Instant

/**
 * The system wakes an executable commitment at its deadline. It never treats
 * dialing as evidence the user woke up, and observes call outcome before retry.
 */
internal object CommitmentExecutor {
    suspend fun onAlarm(
        context: Context,
        claimedTask: CommitmentTask,
        characterName: String,
        now: Instant = Instant.now(),
        notificationShown: Boolean = true,
    ) {
        val appContext = context.applicationContext
        ProactiveIncomingCallStore.initialize(appContext)
        ProactiveIncomingCallStore.reconcileExpired(now)
        if (CommitmentTaskStore.snapshot().none {
            it.id == claimedTask.id && it.status == CommitmentTaskStatus.Running
        }) return

        val attempt = claimedTask.attemptCount + 1
        val wake = claimedTask.isWakeResponsibility()
        val character = MigratedDomainStores.characters.get(claimedTask.characterId)
        val callsEnabled = character.contactPolicy.proactiveCallsEnabled
        val scheduledCall = claimedTask.deliveryAction == "start_call"

        if (CommitmentCallRetryPolicy.isFinalCheck(claimedTask, attempt, callsEnabled)) {
            CommitmentTaskStore.update(claimedTask.id) { old ->
                old.copy(
                    status = CommitmentTaskStatus.Blocked,
                    attemptCount = attempt,
                    linkedAlarmId = null,
                    nextCheckAt = null,
                    lastActionResult = "最后一次拨号仍未接听；目标没有确认完成。重拨已自动停止",
                )
            }
            return
        }

        val useCall = scheduledCall || (wake && attempt >= 2 && callsEnabled)
        val action = if (useCall) "start_call" else "send_private_message"
        val wording = when {
            scheduledCall && attempt == 1 -> "之前说好给你打电话：${claimedTask.goal}。我按约定来啦。"
            useCall && attempt > 1 -> "之前答应了${claimedTask.goal}。刚才电话没接通，我再打一次；你醒了告诉我一声。"
            wake && attempt == 1 -> "${claimedTask.goal}。我来叫你啦，醒了跟我说一声。"
            wake -> "${claimedTask.goal}。还没确认你醒来，我再提醒你一次。"
            else -> "之前答应你的事到时间了：${claimedTask.goal}。完成或取消都可以告诉我。"
        }
        val args = JSONObject().put("text", wording)
        if (useCall) args.put("commitmentTaskId", claimedTask.id)
        val result = CompanionActionRuntime.execute(
            context = appContext,
            characterId = claimedTask.characterId,
            action = action,
            args = args,
            now = now,
        )
        if (useCall && result.success && result.conversationId != null) {
            ProactivePerceptionRuntime.showPromisedCallNotification(
                appContext, claimedTask.characterId, result.conversationId, wording,
            )
        }

        val afterSeconds = if (result.success && (notificationShown || useCall))
            CommitmentCallRetryPolicy.nextDelaySeconds(claimedTask, attempt, callsEnabled) else null
        val retryAt = afterSeconds?.let(now::plusSeconds)
        val followUp = retryAt?.let { at ->
            LuluAlarmSystem.create(
                claimedTask.characterId, characterName, at,
                "${claimedTask.goal}（后续确认）",
                silentCallback = true,
            ).getOrNull()
        }
        CommitmentTaskStore.update(claimedTask.id) { old ->
            old.copy(
                status = if (!result.success || (wake && !notificationShown && !useCall))
                    CommitmentTaskStatus.Blocked else CommitmentTaskStatus.WaitingForFeedback,
                attemptCount = attempt,
                nextCheckAt = followUp?.triggerAt,
                linkedAlarmId = followUp?.id,
                lastActionResult = buildString {
                    append("第$attempt 次${if (useCall) "主动来电" else "叫醒提醒"}已尝试；")
                    append(if (result.success)
                        if (useCall) "拨号发出不代表用户接听或醒来"
                        else "提醒发出不代表用户已经醒来"
                        else "执行失败：${result.summary.take(150)}")
                    if (!notificationShown) append("；提醒通知没有得到确认")
                    if (followUp != null) append("；未收到反馈时，已安排有限后续尝试或结果检查")
                    else if (retryAt != null) append("；后续调度失败，已停止继续呼叫")
                    else append("；未安排更多自动呼叫")
                },
            )
        }
    }
}
