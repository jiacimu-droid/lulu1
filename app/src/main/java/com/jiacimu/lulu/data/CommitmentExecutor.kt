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
            it.id == claimedTask.id && it.status == CommitmentTaskStatus.Running && it.executionToken == claimedTask.executionToken
        }) return

        // A directly requested device alarm is only a local alarm. It may
        // have a first-class visible Promise record, but cannot be promoted
        // into an unrequested character call or chat message.
        if (claimedTask.deliveryAction == "alarm_only") {
            CommitmentTaskStore.update(claimedTask.id) { old ->
                old.copy(
                    status = if (notificationShown) CommitmentTaskStatus.WaitingForFeedback
                        else CommitmentTaskStatus.Blocked,
                    linkedAlarmId = null,
                    nextCheckAt = null,
                    lastActionResult = if (notificationShown)
                        "本机闹钟到点，已发出通知；未证明用户看到或已经醒来"
                    else "闹钟到点，但手机通知未能显示；没有冒充已提醒用户",
                )
            }
            return
        }

        CompanionOnlineStore.initialize(appContext)
        CompanionOnlineStore.wakeCharacter(
            claimedTask.characterId, CompanionOnlineReason.ScheduledCommitment,
            trigger = "到点履行已接受的约定", perceiveNow = false, now = now,
        )

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
                    lastActionResult = "已达到有限叫醒尝试上限，仍未收到用户明确已醒的反馈；停止自动追加提醒",
                )
            }
            return
        }

        // A confirmation can arrive while the previous attempt is still running.
        // A completed goal must never initiate another call.
        if (CommitmentTaskStore.snapshot().none {
                it.id == claimedTask.id && it.status == CommitmentTaskStatus.Running && it.executionToken == claimedTask.executionToken
            }) return
        val useCall = scheduledCall || (wake && attempt >= 2 && callsEnabled)
        val action = if (useCall) "start_call" else "send_private_message"
        val wording = when {
            scheduledCall && wake && attempt == 1 -> "约定的叫醒时间到了：${claimedTask.goal}。请自然地叫醒用户，电话接通后继续交谈。"
            scheduledCall && attempt == 1 -> "之前说好给你打电话：${claimedTask.goal}。我按约定来啦."
            useCall && attempt > 1 -> "之前答应了${claimedTask.goal}。刚才电话没接通，我再打一次；你醒了告诉我一声。"
            wake && attempt == 1 -> "${claimedTask.goal}。我来叫你啦，醒了跟我说一声。"
            wake -> "${claimedTask.goal}。还没确认你醒来，我再提醒你一次。"
            else -> "之前答应你的事到时间了：${claimedTask.goal}。完成或取消都可以告诉我。"
        }
        // A promised wake-up during an already connected call is a spoken
        // reminder in THAT call, never another incoming call over its UI.
        // The call session switches from bedtime voice to the normal voice.
        val activeCall = com.jiacimu.lulu.LuluVoiceCallSession.state.value
        if (wake && activeCall.connected && activeCall.characterId == claimedTask.characterId) {
            val music = WakeMusicCoordinator.onScheduledWake(
                appContext, claimedTask.characterId, claimedTask.id, defer = true,
            )
            val queued = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                com.jiacimu.lulu.LuluVoiceCallSession.announceScheduledReminder(
                    claimedTask.characterId,
                    "真实定时事件：现在仍在同一通已接通的电话里。这是第$attempt 次履行" +
                        "『${claimedTask.goal}』的叫醒目标。请根据自己的人格自然地叫她起来；" +
                        "之前已尝试过的只说明响铃或开口，不说明她醒了。" +
                        "不要说再次拨号、电话没接通，也不要机械念闹钟回执。",
                    claimedTask.id,
                )
            }
            // Do not equate ringing/speaking with the goal "user awake".
            // Only outstanding goals receive a bounded follow-up after 5min.
            val delaySeconds = if (queued)
                CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(attempt) else null
            val nextAlarm = delaySeconds?.let { seconds ->
                LuluAlarmSystem.create(
                    claimedTask.characterId, characterName, now.plusSeconds(seconds),
                    "${claimedTask.goal}（叫醒结果复查）", silentCallback = true,
                    commitmentTaskId = claimedTask.id,
                ).getOrNull()
            }
            val updated = CommitmentTaskStore.update(claimedTask.id) { old ->
                if (old.status != CommitmentTaskStatus.Running || old.executionToken != claimedTask.executionToken) old else old.copy(
                    status = if (queued) CommitmentTaskStatus.WaitingForFeedback else CommitmentTaskStatus.Blocked,
                    attemptCount = attempt,
                    linkedAlarmId = nextAlarm?.id,
                    nextCheckAt = nextAlarm?.triggerAt,
                    lastActionResult = if (queued) buildString {
                        append("第$attempt 次已在原电话发起铃声叫醒，随后自然说话；未收到明确回应，不视为已醒。$music")
                        append(if (nextAlarm != null) "；已安排后续目标复查"
                            else "；后续提醒已到次数上限或未排成功")
                    } else "原电话中未能排入叫醒铃声与语音；没有再次拨号",
                )
            }
            if (updated?.status != CommitmentTaskStatus.WaitingForFeedback) {
                nextAlarm?.id?.let(LuluAlarmSystem::cancel)
            }
            return
        }
        // A real incoming call is followed by music only after the ringtone/call ends.
        if (wake && useCall) {
            WakeMusicCoordinator.onScheduledWake(
                appContext, claimedTask.characterId, claimedTask.id, defer = true,
            )
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
        val deliveryNotificationShown = if (useCall && result.success && result.conversationId != null) {
            ProactivePerceptionRuntime.showPromisedCallNotification(
                appContext, claimedTask.characterId, result.conversationId, wording,
            )
        } else notificationShown
        val musicResult = when {
            !wake -> ""
            useCall && result.success -> "等通话结束后放歌"
            // A failed call must not leave the music hand-off blocked forever.
            useCall -> {
                WakeMusicCoordinator.onMissedCall(appContext, claimedTask.characterId, claimedTask.id)
                "通话未发出，改为尝试音乐叫醒"
            }
            else -> WakeMusicCoordinator.onScheduledWake(
                appContext, claimedTask.characterId, claimedTask.id, defer = false,
            )
        }

        val afterSeconds = if (result.success && (deliveryNotificationShown || useCall))
            CommitmentCallRetryPolicy.nextDelaySeconds(claimedTask, attempt, callsEnabled) else null
        val retryAt = afterSeconds?.let(now::plusSeconds)
        val followUp = retryAt?.let { at ->
            LuluAlarmSystem.create(
                claimedTask.characterId, characterName, at,
                "${claimedTask.goal}（后续确认）",
                silentCallback = true,
                commitmentTaskId = claimedTask.id,
            ).getOrNull()
        }
        val updated = CommitmentTaskStore.update(claimedTask.id) { old ->
            if (old.status != CommitmentTaskStatus.Running || old.executionToken != claimedTask.executionToken) old else old.copy(
                status = if (!result.success || (wake && !deliveryNotificationShown && !useCall))
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
                    if (musicResult.isNotBlank()) append("；音乐：$musicResult")
                    if (!deliveryNotificationShown) append("；提醒通知没有得到确认")
                    if (followUp != null) append("；未收到反馈时，已安排有限后续尝试或结果检查")
                    else if (retryAt != null) append("；后续调度失败，已停止继续呼叫")
                    else append("；未安排更多自动呼叫")
                },
            )
        }
        if (updated?.status != CommitmentTaskStatus.WaitingForFeedback) {
            followUp?.id?.let(LuluAlarmSystem::cancel)
        }
        // A fast answer/reject can happen while the alarm job is still
        // persisting its result. Reconcile the actual user's action afterward.
        if (useCall) {
            ProactiveIncomingCallStore.responseForTask(claimedTask.id)?.let { answered ->
                CommitmentCallFeedback.onResponse(claimedTask.id, answered)
            }
        }
    }
}
