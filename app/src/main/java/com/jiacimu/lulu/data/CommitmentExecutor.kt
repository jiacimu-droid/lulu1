package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluAlarmSystem
import com.jiacimu.lulu.system.LuluMediaVolume
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

        // A review callback belongs to the same durable commitment; it is not
        // a second reminder or an invitation to call again.
        if (claimedTask.isOutcomeReview()) {
            val observation = runCatching {
                UserDevicePerception.context(appContext, claimedTask.characterId, now)
            }.getOrElse { error ->
                "设备状态读取失败：${error.javaClass.simpleName}，不能推断完成"
            }
            val summary = "约定的结果核查；目标：${claimedTask.goal}；完成标准：${claimedTask.completionCondition}。" +
                "初始行动已发生，但目的是否实现仍未确认。以下是近期实际设备线索，并不自动构成达标证据：\n" +
                observation.take(2_500)
            val reviewEventId = "commitment:verification:${claimedTask.id}:${claimedTask.executionToken}"
            SharedExperienceTimeline.record(
                eventId = reviewEventId,
                characterId = claimedTask.characterId,
                channel = "约定结果核查",
                speaker = "设备观察",
                content = summary,
                occurredAt = now,
                source = "commitment-follow-up",
                evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = false,
            )
            CommitmentTaskStore.update(claimedTask.id) { old ->
                if (old.status != CommitmentTaskStatus.Running ||
                    old.executionToken != claimedTask.executionToken) old else old.copy(
                    status = CommitmentTaskStatus.WaitingForFeedback,
                    linkedAlarmId = null, nextCheckAt = null, followUpKind = "",
                    lastActionResult = "到期行动已尝试，已进行一次客观状态复查；结果仍须按完成标准找可靠证据确认",
                )
            }
            // This call owns the ongoing goal. Deliver its real review through
            // the existing call result queue, rather than a background reply
            // that is suppressed while the same role is on the phone.
            val reviewCall = com.jiacimu.lulu.LuluVoiceCallSession.state.value
            if (reviewCall.connected && reviewCall.characterId == claimedTask.characterId &&
                reviewCall.incomingCommitmentTaskId == claimedTask.id) {
                val queued = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.onExternalToolResult(
                        claimedTask.characterId, reviewCall.callExperienceId, reviewEventId)
                }
                if (queued) return
            }
            CompanionOnlineStore.initialize(appContext)
            CompanionOnlineStore.wakeCharacter(
                claimedTask.characterId, CompanionOnlineReason.ScheduledCommitment,
                "先前承担的目标进入结果核查阶段，不是收到新用户要求。\n" + summary +
                    "\n请结合原始承诺和实际经历自行选择下一步。如果目前证据不充分，可在原有权限内选择恰当工具、等待或调整计划。" +
                    "不可把设备息屏、短暂停用、挂断电话、单条用户声明或角色自己说过的话当作外部目标完成的证据。" +
                    "如需补充核实，可以自己行动，但不得重复建立同一约定，也不要无意义地连续联系主人。",
                perceiveNow = true, now = now,
            )
            return
        }

        val activeCall = com.jiacimu.lulu.LuluVoiceCallSession.state.value
        val sameCall = activeCall.connected && activeCall.characterId == claimedTask.characterId
        val wake = claimedTask.isWakeResponsibility()
        val selected = if (wake)
            WakeActionPlanner.choose(appContext, claimedTask, sameCall, now)
        else WakeActionPlanner.Choice(WakeActionPlanner.Cue.RING)

        fun playChosenMusic(): Boolean {
            if (selected.cue != WakeActionPlanner.Cue.MUSIC) return false
            selected.volumePercent?.let {
                LuluMediaVolume.control(appContext, claimedTask.characterId, "set", it)
            }
            val played = WakeMusicCoordinator.playImmediately(
                appContext, claimedTask.characterId, claimedTask.id)
            if (!played && selected.volumePercent != null)
                LuluMediaVolume.control(appContext, claimedTask.characterId, "restore")
            return played
        }

        // A local alarm is NOT permission to place a new phone call. In a live
        // bedtime call the same reminder may use its existing audio session.
        if (claimedTask.deliveryAction == "alarm_only") {
            val playing = playChosenMusic()
            val delivered = if (playing) {
                if (sameCall) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.exitSleepForWake(claimedTask.characterId, claimedTask.id)
                }
                true
            } else if (sameCall) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.announceScheduledReminder(
                        claimedTask.characterId, claimedTask.goal, claimedTask.id,
                        ringFirst = selected.cue != WakeActionPlanner.Cue.SPEAK,
                        ringCount = selected.ringCount,
                        ringSeconds = selected.ringSeconds,
                        pauseSeconds = selected.pauseSeconds,
                    )
                }
            } else {
                androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
                    runCatching {
                        LuluAlarmSystem.showAlarmNotification(appContext,
                            claimedTask.linkedAlarmId ?: claimedTask.id,
                            claimedTask.characterId, characterName, claimedTask.goal)
                    }.isSuccess
            }
            val review = if (delivered)
                LuluAlarmSystem.create(
                    claimedTask.characterId, characterName, now.plusSeconds(10 * 60L),
                    "${claimedTask.goal}（监督结果复查）", silentCallback = true,
                    commitmentTaskId = claimedTask.id,
                ).getOrNull() else null
            CommitmentTaskStore.update(claimedTask.id) { old ->
                old.copy(
                    status = if (delivered) CommitmentTaskStatus.WaitingForFeedback
                        else CommitmentTaskStatus.Blocked,
                    linkedAlarmId = review?.id, nextCheckAt = review?.triggerAt,
                    followUpKind = if (review != null) "verification" else "",
                    attemptCount = 1,
                    lastActionResult = if (!delivered) "到点叫醒失败：本机通知或音乐未能实际触发"
                        else if (playing) "角色选择音乐叫醒，媒体会话已确认播放；未证明用户醒来"
                        else if (sameCall) "在原电话内${if (selected.cue == WakeActionPlanner.Cue.SPEAK) "用普通声线" else "响铃后"}叫醒，没有重新拨号；未确认醒来"
                        else "本机闹钟已发出通知；未证明目标已经达成；" +
                            (if (review != null) "已安排后续客观复查" else "复查未能安排") +
                            selected.reason.takeIf { it.isNotBlank() }?.let { "；角色决定：$it" }.orEmpty(),
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
        // A timed chat reminder is a *fact for the character to respond to*,
        // not permission for the alarm executor to put its own operational
        // template into the character's private-message timeline. It also
        // needs an actionable popup, even if the character's model is offline.
        if (shouldShowScheduledReminderPopup(claimedTask)) {
            val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull {
                it.characterId == claimedTask.characterId &&
                    it.groupChat == null && it.parentConversationId == null
            }
            val popupResult = com.jiacimu.lulu.system.LuluScreenAwareness.scheduledReminder(
                appContext, claimedTask.characterId, claimedTask.goal,
                "commitment:${claimedTask.id}:${claimedTask.dueAt?.toEpochMilli() ?: now.toEpochMilli()}",
                conversation?.id.orEmpty(),
            )
            val requested = JSONObject(popupResult).optBoolean("success", false)
            val review = if (requested)
                LuluAlarmSystem.create(
                    claimedTask.characterId, characterName, now.plusSeconds(10 * 60L),
                    "${claimedTask.goal}（监督结果复查）", silentCallback = true,
                    commitmentTaskId = claimedTask.id,
                ).getOrNull() else null
            CommitmentTaskStore.update(claimedTask.id) { old ->
                if (old.status != CommitmentTaskStatus.Running ||
                    old.executionToken != claimedTask.executionToken) old else old.copy(
                    status = if (requested) CommitmentTaskStatus.WaitingForFeedback
                        else CommitmentTaskStatus.Blocked,
                    linkedAlarmId = review?.id, nextCheckAt = review?.triggerAt,
                    followUpKind = if (review != null) "verification" else "",
                    attemptCount = attempt,
                    lastActionResult = if (requested)
                        "到期后已请求显示可交互提醒；没有代替角色发送聊天台词，也未确认主人已执行" +
                            if (review != null) "；已安排结果复查" else "；复查未安排，不能认定目标已实现"
                    else "定时提醒未显示：${JSONObject(popupResult).optString("error")}；没有伪造角色回复",
                )
            }
            // Use the same character perception/decision path as normal
            // autonomous dialogue. The model may speak naturally, perform
            // allowed actions, or remain silent; never pre-commit fake prose.
            CompanionOnlineStore.wakeCharacter(
                claimedTask.characterId, CompanionOnlineReason.ScheduledCommitment,
                "已接受的定时约定刚到期：${claimedTask.goal}。" +
                    "这是系统提供的时间事实，不是你说过的话。" +
                    "结合本人真实关系与上下文，自主决定是否说话或行动；" +
                    "绝不可把系统执行文字照抄给用户。",
                perceiveNow = true, now = now,
            )
            return
        }

        val useCall = scheduledCall || (wake && attempt >= 2 && callsEnabled)
        val action = if (useCall) "start_call" else "send_private_message"
        val wording = selected.openingLine.takeIf { wake && useCall && it.isNotBlank() } ?: when {
            // Incoming-call text is now literally spoken after answer, never
            // just shown as an instruction on the ringing screen.
            scheduledCall && wake && attempt == 1 -> "到我们约好的时间啦，我来叫你起床。醒了就跟我说一声，好不好？"
            scheduledCall && attempt == 1 -> "到我们约好的时间啦，我给你打过来啦。"
            useCall && wake && attempt > 1 -> "我再来叫你一次啦。醒了就跟我说一声。"
            useCall && attempt > 1 -> "刚才没接通，我按约定再打来啦。"
            wake && attempt == 1 -> "${claimedTask.goal}。我来叫你啦，醒了跟我说一声。"
            wake -> "${claimedTask.goal}。还没确认你醒来，我再提醒你一次。"
            else -> error("An ordinary scheduled chat reminder must use the popup and character decision route")
        }
        // A promised wake-up during an already connected call is a spoken
        // reminder in THAT call, never another incoming call over its UI.
        // The call session switches from bedtime voice to the normal voice.
        if (wake && sameCall) {
            val playing = playChosenMusic()
            val queued = if (playing) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.exitSleepForWake(claimedTask.characterId, claimedTask.id)
                }
            } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                com.jiacimu.lulu.LuluVoiceCallSession.announceScheduledReminder(
                    claimedTask.characterId,
                    "真实定时事件：在同一通电话中履行第$attempt 次叫醒『${claimedTask.goal}』。" +
                        "按你自己的人格自然地叫她起来；不把响铃当作她已经醒了。",
                    claimedTask.id,
                    ringFirst = selected.cue != WakeActionPlanner.Cue.SPEAK,
                    ringCount = selected.ringCount,
                    ringSeconds = selected.ringSeconds,
                    pauseSeconds = selected.pauseSeconds,
                )
            }
            val music = if (playing) "角色自主选择在原电话内播放音乐" else "角色选择${if (selected.cue == WakeActionPlanner.Cue.SPEAK) "直接说话" else "铃声加自然对话"}"
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
                        append("第$attempt 次已在原电话执行叫醒；未收到明确回应，不视为已醒。$music")
                        if (selected.reason.isNotBlank()) append("；角色考虑：${selected.reason}")
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
        // A song is deferred only if THIS role actually chose music today.
        // The fact a phone call ended is never sufficient to create a song request.
        val plannedMusic = if (wake && useCall &&
            selected.cue == WakeActionPlanner.Cue.MUSIC) {
            selected.volumePercent?.let {
                LuluMediaVolume.control(appContext, claimedTask.characterId, "set", it)
            }
            WakeMusicCoordinator.onScheduledWake(
                appContext, claimedTask.characterId, claimedTask.id, defer = true)
        } else ""
        val args = JSONObject().put("text", wording)
        if (useCall) args.put("commitmentTaskId", claimedTask.id)
        val result = CompanionActionRuntime.execute(
            context = appContext,
            characterId = claimedTask.characterId,
            action = action,
            args = args,
            now = now,
        )
        // The same start_call executor posts the invitation for primary,
        // nested and scheduled calls. Read the real result instead of issuing
        // a second notification only for alarm-backed calls.
        val deliveryNotificationShown = if (useCall)
            result.callNotificationShown == true else notificationShown
        val musicResult = plannedMusic

        val reviewOnly = !wake && result.success && (deliveryNotificationShown || useCall)
        val afterSeconds = if (reviewOnly) 10L * 60L
            else if (result.success && (deliveryNotificationShown || useCall))
                CommitmentCallRetryPolicy.nextDelaySeconds(claimedTask, attempt, callsEnabled)
            else null
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
                followUpKind = if (followUp == null) "" else if (reviewOnly) "verification" else "retry",
                lastActionResult = buildString {
                    append("第$attempt 次${if (useCall) "主动来电" else "叫醒提醒"}已尝试；")
                    append(if (result.success)
                        if (useCall) "拨号发出不代表用户接听或醒来"
                        else "提醒发出不代表用户已经醒来"
                        else "执行失败：${result.summary.take(150)}")
                    if (musicResult.isNotBlank()) append("；音乐：$musicResult")
                    if (!deliveryNotificationShown) append("；提醒通知没有得到确认")
                    if (followUp != null) append(if (reviewOnly)
                        "；已安排一次目标结果复查，不会自动再次拨号"
                        else "；未收到反馈时，已安排有限后续尝试或结果检查")
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

/** No operating-system deadline template may masquerade as character dialogue. */
internal fun shouldShowScheduledReminderPopup(task: CommitmentTask): Boolean =
    task.dueAt != null && task.deliveryAction == "send_private_message" &&
        !task.isWakeResponsibility()
