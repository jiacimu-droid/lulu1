package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluAlarmSystem
import com.jiacimu.lulu.system.LuluAudibleWakeRinger
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
                UserDevicePerception.commitmentReviewContext(appContext, claimedTask.characterId, now)
            }.getOrElse { error ->
                "设备状态读取失败：${error.javaClass.simpleName}，不能推断完成"
            }
            val summary = "约定的结果核查；目标：${claimedTask.goal}；完成标准：${claimedTask.completionCondition}。" +
                "初始行动已发生，但目的是否实现仍未确认。以下是近期实际设备线索，并不自动构成达标证据：\n" +
                observation
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
                    "如需补充核实，可以自己行动：在已有授权下可选择 capture_screen 查看真实画面；" +
                    "亮屏本身无法证明是否醒着，但确有近期使用/触摸事件就证明那时手机发生过交互，" +
                    "不能编成‘一直没动、应该快睡了’；没有可用事件也不等于睡了。" +
                    "优先综合事件时刻、原目标时效与目前可行步骤，决定现在观察、联系或等待。" +
                    "若继续等待，要说明下一次能得到何种新增证据，以及这段等待对原目标的影响；" +
                    "尚无达标证据才能为原 taskId 选择 schedule_commitment_review，" +
                    "或者根据真实情况改变联系方式、暂缓；不要重复创建约定或机械刷屏。",
                perceiveNow = true, now = now,
            )
            return
        }

        val activeCall = com.jiacimu.lulu.LuluVoiceCallSession.state.value
        val sameCall = activeCall.connected && activeCall.characterId == claimedTask.characterId
        val wake = claimedTask.isWakeResponsibility()
        // A reminder callback cannot interrupt its own unfinished wake speech:
        // let that voice complete before the character judges the result.
        if (wake && CommitmentCallRetryPolicy.shouldDeferInCallWakeDecision(
                claimedTask.followUpKind, sameCall, activeCall.thinking,
                activeCall.speaking, activeCall.opening)) {
            val after = now.plusSeconds(45)
            val retry = LuluAlarmSystem.create(
                claimedTask.characterId, characterName, after,
                "${claimedTask.goal}（等待上段叫醒语音播完后复查）",
                silentCallback = true, commitmentTaskId = claimedTask.id,
            ).getOrNull()
            CommitmentTaskStore.update(claimedTask.id) { old ->
                if (old.status != CommitmentTaskStatus.Running ||
                    old.executionToken != claimedTask.executionToken) old else old.copy(
                    status = CommitmentTaskStatus.WaitingForFeedback,
                    linkedAlarmId = retry?.id, nextCheckAt = retry?.triggerAt,
                    followUpKind = if (retry == null) "" else "retry",
                    lastActionResult = if (retry == null)
                        "上一段叫醒仍在播放，但下一次复查调度失败；不能认定主人已醒"
                    else "上一段叫醒仍在准备/播放；暂不打断，稍后再次核查",
                )
            }
            return
        }
        // The first locally promised wake cue must be audible even when the
        // model is slow/unavailable. Notification channel sound is not proof.
        // Later rounds remain the character's own decision.
        val firstLocalRing = wake && !sameCall &&
            claimedTask.deliveryAction == "alarm_only" &&
            claimedTask.attemptCount == 0 &&
            LuluAudibleWakeRinger.start(appContext, claimedTask.id)
        val selected = if (wake)
            WakeActionPlanner.choose(appContext, claimedTask, sameCall, now)
        else WakeActionPlanner.Choice(WakeActionPlanner.Cue.RING)

        // The planning result is a real decision by the SAME character.
        // Pass it into the actual phone reply so the next utterance doesn't
        // forget why the character changed tactics or repeat a generic script.
        fun wakeSpeechContext(attempt: Int): String = buildString {
            append("真实叫醒约定：${claimedTask.goal}；第$attempt 轮。")
            append("本轮此前选定的声音动作：${selected.cue}。")
            if (selected.reason.isNotBlank())
                append("本人这轮的理由：${selected.reason.take(180)}。")
            if (claimedTask.wakeAttemptHistory.isNotEmpty())
                append("以前的实际叫醒步骤与播放回执：")
                    .append(claimedTask.wakeAttemptHistory.takeLast(4).joinToString("；") { it.take(170) })
                    .append("。")
            if (selected.openingLine.isNotBlank())
                append("你刚才想用这样的语气开口：${selected.openingLine.take(180)}；按本人性格自然表达，不要逐字念执行说明。")
            append("没有用户已醒的可靠证据时，不可宣称目标已完成。")
        }

        fun selectionEvidence(): String = when {
            selected.systemFallback -> "系统保底（非角色决定）：${selected.executionIssue}"
            selected.executionIssue.isNotBlank() ->
                "角色原选择=${selected.cue}；暂不能执行：${selected.executionIssue}"
            else -> "角色选择=${selected.cue}；理由=${selected.reason.take(150)}"
        }

        suspend fun playChosenMusic(): Boolean {
            if (selected.cue != WakeActionPlanner.Cue.MUSIC ||
                selected.executionIssue.isNotBlank() ||
                !WakeActionPlanner.musicAllowed(appContext, claimedTask.characterId)) return false
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
        // After the first local alarm, a character may independently choose
        // a real phone call or message to follow up. Do not trap every retry
        // in the notification-only route. The initial alarm-only promise
        // must not be silently converted into an unsolicited first call.
        val audibleFollowUpContact = wake && !sameCall &&
            claimedTask.attemptCount > 0 &&
            selected.cue != WakeActionPlanner.Cue.WAIT &&
            (selected.contact == WakeActionPlanner.Contact.MESSAGE ||
                (selected.contact == WakeActionPlanner.Contact.CALL &&
                    MigratedDomainStores.characters.get(claimedTask.characterId)
                        .contactPolicy.proactiveCallsEnabled))
        if (claimedTask.deliveryAction == "alarm_only" && !audibleFollowUpContact) {
            val attempt = claimedTask.attemptCount + 1
            if (wake && attempt > CommitmentCallRetryPolicy.MAX_WAKE_CALL_ATTEMPTS) {
                CommitmentTaskStore.update(claimedTask.id) { old ->
                    old.copy(status = CommitmentTaskStatus.Blocked, attemptCount = attempt,
                        linkedAlarmId = null, nextCheckAt = null, followUpKind = "",
                        lastActionResult = "已到本次叫醒的尝试上限；没有充分证据确认醒来")
                }
                return
            }
            // The model call above may have taken time; don't deliver a cue
            // after an explicit user confirmation or task cancellation.
            if (CommitmentTaskStore.snapshot().none {
                    it.id == claimedTask.id && it.status == CommitmentTaskStatus.Running &&
                        it.executionToken == claimedTask.executionToken
                }) return
            val playing = playChosenMusic()
            val waiting = selected.cue == WakeActionPlanner.Cue.WAIT && !selected.systemFallback
            val connectedWakeQueued = if (!waiting && !playing && sameCall)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.announceScheduledReminder(
                        claimedTask.characterId, wakeSpeechContext(attempt), claimedTask.id,
                        ringFirst = selected.cue != WakeActionPlanner.Cue.SPEAK,
                        ringSeconds = selected.ringSeconds,
                    )
                } else false
            if (playing && sameCall) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.exitSleepForWake(claimedTask.characterId, claimedTask.id)
                }
            }
            val localRing = if (!waiting && !playing && !sameCall)
                (firstLocalRing || LuluAudibleWakeRinger.start(appContext, claimedTask.id)) else false
            val notificationPosted = if (!waiting && !playing && !sameCall)
                androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
                    runCatching {
                        LuluAlarmSystem.showAlarmNotification(appContext, claimedTask.id,
                            claimedTask.characterId, characterName, claimedTask.goal)
                    }.isSuccess else false
            // A posted notification is NOT proof that an alarm stream played.
            val delivered = waiting || playing || connectedWakeQueued || localRing
            val reviewSeconds = if (wake) selected.reviewAfterSeconds ?: CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(attempt)
                else 10L * 60L
            // Even an audio failure remains an unfulfilled wake duty and gets a
            // bounded recovery decision instead of silently terminating.
            val review = if ((delivered || notificationPosted) && reviewSeconds != null)
                LuluAlarmSystem.create(
                    claimedTask.characterId, characterName, now.plusSeconds(reviewSeconds),
                    "${claimedTask.goal}（下一阶段或最终复查）", silentCallback = true,
                    commitmentTaskId = claimedTask.id,
                ).getOrNull() else null
            CommitmentTaskStore.update(claimedTask.id) { old ->
                if (old.status != CommitmentTaskStatus.Running ||
                    old.executionToken != claimedTask.executionToken) old else old.copy(
                    status = if (delivered || review != null) CommitmentTaskStatus.WaitingForFeedback
                        else CommitmentTaskStatus.Blocked,
                    linkedAlarmId = review?.id, nextCheckAt = review?.triggerAt,
                    followUpKind = if (wake)
                        CommitmentCallRetryPolicy.followUpKindForWakeRound(attempt, review != null)
                        else if (review != null) "verification" else "",
                    attemptCount = attempt,
                    wakeAttemptHistory = if (!wake) old.wakeAttemptHistory else
                        (old.wakeAttemptHistory + "第$attempt 阶段：角色选择${selected.cue}；" +
                            (if (playing) "播放器确认音乐开始" else if (localRing) "Android 闹钟音频已启动"
                             else if (connectedWakeQueued) "已排入原电话待播放" else if (waiting) "角色明确选择等待"
                             else if (notificationPosted) "仅通知送达，未确认任何闹钟音频" else "声音及通知投递失败") +
                            "；${selectionEvidence()}").takeLast(6),
                    lastActionResult = if (waiting)
                        "角色选择暂时等待下一次复查；本轮没有发出声音；尚未确认已醒"
                        else if (playing) "角色选择音乐叫醒，媒体会话已确认播放；未证明用户醒来"
                        else if (sameCall && connectedWakeQueued)
                            "在原电话排入自然说话/铃声，等待实际播放回执；尚未确认已醒"
                        else if (localRing)
                            "Android 闹钟音频已开始播放；${if (notificationPosted) "已同步显示通知" else "通知未成功显示"}；尚未确认已醒" +
                                (if (review != null) "；后续复查已安排" else "；后续复查未安排")
                        else "闹钟音频未成功播放；${if (notificationPosted) "只有普通通知" else "通知也未确认"}；" +
                            (if (review != null) "已安排有限重试" else "后续重试调度失败") +
                            "；${selectionEvidence()}",
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
            // The notification is a device receipt, never fulfillment of the
            // character's accepted responsibility. The SAME role still chooses
            // contact, observation, or an explicit deferred follow-up.
            CompanionOnlineStore.wakeCharacter(
                claimedTask.characterId, CompanionOnlineReason.ScheduledCommitment,
                "已接受的定时约定刚到期：${claimedTask.goal}。" +
                    "这是系统提供的时间事实，不是你说过的话。" +
                    "弹窗仅证明提醒请求已执行，不能代表你已经亲自哄睡、监督或确认结果。" +
                    "主人明确授权过的主动监督不能因她暂时没回复、或你猜提示音会吵到她而自行取消。" +
                    "请根据本人真实关系、任务ID、执行权限和最新证据，选择真实消息/来电/核实动作；" +
                    "如果确实选择暂缓，也要保留本任务并登记真实的下一次复查，不要只写心声。" +
                    "绝不可把系统执行文字照抄给用户。",
                perceiveNow = true, now = now,
            )
            return
        }

        // First contact respects the original promise. Later contacts require
        // the role's new structured decision; attempt count is only a bound.
        val action = when {
            !wake -> if (scheduledCall) "start_call" else "send_private_message"
            sameCall -> "reuse_connected_call"
            else -> CommitmentCallRetryPolicy.wakeContactAction(
                attempt, claimedTask.deliveryAction, selected.contact, callsEnabled,
                selected.cue)
        }
        val useCall = action == "start_call"
        val wording = if (wake) selected.openingLine.ifBlank {
            // This is visibly marked as emergency system text in the receipt,
            // not a fictional character-authored line.
            "到点提醒：${claimedTask.goal}"
        } else if (attempt == 1) "到我们约好的时间啦，我给你打过来啦。"
        else "刚才没接通，我按约定再打来啦。"
        // A promised wake-up during an already connected call is a spoken
        // reminder in THAT call, never another incoming call over its UI.
        // The call session switches from bedtime voice to the normal voice.
        if (wake && sameCall) {
            val playing = playChosenMusic()
            val queued = if (selected.cue == WakeActionPlanner.Cue.WAIT &&
                !selected.systemFallback) true else if (playing) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    com.jiacimu.lulu.LuluVoiceCallSession.exitSleepForWake(claimedTask.characterId, claimedTask.id)
                }
            } else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                com.jiacimu.lulu.LuluVoiceCallSession.announceScheduledReminder(
                    claimedTask.characterId,
                    wakeSpeechContext(attempt),
                    claimedTask.id,
                    ringFirst = selected.cue != WakeActionPlanner.Cue.SPEAK,
                    ringSeconds = selected.ringSeconds,
                )
            }
            val music = when {
                selected.cue == WakeActionPlanner.Cue.WAIT -> "角色选择本轮等待，不追加声音"
                playing -> "播放器确认音乐已启动"
                selected.cue == WakeActionPlanner.Cue.MUSIC ->
                    "角色选音乐，播放不可用，本机铃声保底（非角色改选）"
                selected.systemFallback -> "模型不可用，本机铃声保底"
                selected.cue == WakeActionPlanner.Cue.SPEAK -> "本轮角色选自然说话"
                else -> "本轮角色选一次铃声后再说话"
            }
            // Do not equate ringing/speaking with the goal "user awake".
            // Only outstanding goals receive a bounded follow-up after 5min.
            val delaySeconds = if (queued)
                selected.reviewAfterSeconds ?: CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(attempt) else null
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
                    followUpKind = CommitmentCallRetryPolicy.followUpKindForWakeRound(
                        attempt, nextAlarm != null),
                    wakeAttemptHistory = (old.wakeAttemptHistory +
                        "第$attempt 阶段：角色选${selected.cue}；" +
                        (if (queued) music else "叫醒行动未成功排入") +
                        "；${selectionEvidence()}").takeLast(6),
                    lastActionResult = if (queued) buildString {
                        append("第$attempt 次已在原电话安排叫醒流程；声音实际送达仍以播放器回执为准，未确认主人醒来。$music")
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
        // The role may wait, or a chosen contact may be unavailable. Neither
        // situation authorizes an automatic alternative call/message.
        if (wake && action.isBlank()) {
            val seconds = selected.reviewAfterSeconds
                ?: CommitmentCallRetryPolicy.nextDelaySeconds(claimedTask, attempt, callsEnabled)
            val followUp = seconds?.let {
                LuluAlarmSystem.create(claimedTask.characterId, characterName,
                    now.plusSeconds(it), "${claimedTask.goal}（下一次独立判断）",
                    silentCallback = true, commitmentTaskId = claimedTask.id).getOrNull()
            }
            CommitmentTaskStore.update(claimedTask.id) { old ->
                if (old.status != CommitmentTaskStatus.Running ||
                    old.executionToken != claimedTask.executionToken) old else old.copy(
                    // Without a durable callback, WAIT cannot ever be
                    // reconsidered; report a blocked schedule, not a live
                    // pending review with no execution path.
                    status = if (followUp == null) CommitmentTaskStatus.Blocked
                        else CommitmentTaskStatus.WaitingForFeedback,
                    attemptCount = attempt,
                    wakeAttemptHistory = (old.wakeAttemptHistory +
                        "第$attempt 轮没有向主人发起联系：${selectionEvidence()}；" +
                        "联系渠道=${selected.contact ?: "无有效选择"}").takeLast(6),
                    linkedAlarmId = followUp?.id,
                    nextCheckAt = followUp?.triggerAt,
                    followUpKind = CommitmentCallRetryPolicy.followUpKindForWakeRound(
                        attempt, followUp != null),
                    lastActionResult = "本轮没有擅自发起电话或消息；${selectionEvidence()}；" +
                        if (followUp != null) "复查已登记" else "复查调度未成功",
                )
            }
            return
        }
        // A song is deferred only if THIS role actually chose music today.
        // The fact a phone call ended is never sufficient to create a song request.
        val plannedMusic = if (wake && useCall &&
            selected.cue == WakeActionPlanner.Cue.MUSIC && selected.executionIssue.isBlank()) {
            WakeMusicCoordinator.onScheduledWake(
                appContext, claimedTask.characterId, claimedTask.id, defer = true,
                volumePercent = selected.volumePercent)
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
                if (wake) selected.reviewAfterSeconds
                    ?: CommitmentCallRetryPolicy.nextDelaySeconds(claimedTask, attempt, callsEnabled)
                else CommitmentCallRetryPolicy.nextDelaySeconds(claimedTask, attempt, callsEnabled)
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
                wakeAttemptHistory = if (!wake) old.wakeAttemptHistory else
                    (old.wakeAttemptHistory + "第$attempt 阶段：角色选择${selected.cue}，" +
                        "已尝试${if (useCall) "发起叫醒电话（先由本机来电铃声提示）" else "发出闹钟提醒"}；" +
                        "结果：${if (result.success) "执行请求已发出，实际醒来尚未确认" else "执行失败：${result.summary.take(90)}"}；" +
                        "${selectionEvidence()}").takeLast(6),
                nextCheckAt = followUp?.triggerAt,
                linkedAlarmId = followUp?.id,
                followUpKind = if (wake)
                    CommitmentCallRetryPolicy.followUpKindForWakeRound(attempt, followUp != null)
                    else if (followUp == null) "" else if (reviewOnly) "verification" else "retry",
                lastActionResult = buildString {
                    append("第$attempt 次${if (useCall) "主动来电" else "叫醒提醒"}已尝试；")
                    append(if (result.success)
                        if (useCall) "拨号发出不代表用户接听或醒来"
                        else "提醒发出不代表用户已经醒来"
                        else "执行失败：${result.summary.take(150)}")
                    if (wake) append("；${selectionEvidence()}")
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
