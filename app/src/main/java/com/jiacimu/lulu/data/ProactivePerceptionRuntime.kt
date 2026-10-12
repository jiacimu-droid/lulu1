package com.jiacimu.lulu.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.MigrationActivity
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.qqForwardContextText
import com.jiacimu.lulu.study.ReadingBackgroundBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Per-character autonomous perception used by background life and short online sessions. */
object ProactivePerceptionRuntime {
    private const val PREFS_NAME = "lulu_proactive_runtime_v2"
    private const val MESSAGE_CHANNEL_ID = "lulu_proactive_messages"
    private const val CALL_CHANNEL_ID = "lulu_proactive_calls"
    private const val ACTION_HISTORY_SIZE = 10
    private val cycleMutex = Mutex()

    private enum class Action { MESSAGE, GROUP_MESSAGE, STICKER, GROUP_STICKER, KAOMOJI, GROUP_KAOMOJI, GAME_INVITE, SOLO_GAME, WORLD_INVITE, MOMENT, CALL, JOURNAL, READING, DIGITAL_WORLD, USER_REMARK, SELF_NICKNAME, TOOL, SILENT }

    private data class Decision(
        val action: Action,
        val text: String,
        val reason: String,
        val statusText: String,
        val gesture: String,
        val innerThought: String,
        val innerThoughtBasis: JSONObject? = null,
        val mood: String,
        val journalTitle: String,
        val journalContent: String,
        val groupId: String,
        val gameId: String,
        val readingBookId: String,
        val worldAction: String,
        val itemId: String,
        val itemType: String,
        val itemName: String,
        val appearance: String,
        val position: String,
        val targetCharacterId: String,
        val location: String,
        val activityId: String,
        val incidentId: String,
        val approach: String,
        val nickname: String = "",
        val stickerId: String = "",
        val concernId: String = "",
        val tool: String = "",
        val toolArgs: JSONObject = JSONObject(),
        val intention: JSONObject? = null,
        val afterglow: JSONObject? = null,
        val innerLife: JSONObject? = null,
        val appraisal: JSONObject? = null,
        val motiveId: String = "",
        val intentionId: String = "",
        val curiosity: JSONObject? = null,
        val relationshipInitiative: JSONObject? = null,
        val learningApplication: JSONObject? = null,
        val experienceId: String = "",
        val alternatives: org.json.JSONArray? = null,
    )

    private data class UserActivity(
        val conversation: LuluConversation,
        val message: LuluChatMessage,
        val awaitingReply: Boolean,
    )

    private data class ActionExecution(val success: Boolean, val summary: String,
        val conversationId: String? = null, val evidenceId: String = "")

    fun initialize(context: Context) {
        ProactivePerceptionPolicyStore.initialize(context.applicationContext)
        PerceptionWakePlanStore.initialize(context.applicationContext)
        CharacterCuriosityRuntime.initialize(context.applicationContext)
        CharacterRelationalInitiative.initialize(context.applicationContext)
        createNotificationChannels(context.applicationContext)
    }

    fun markConcernPromisePending(context: Context, characterId: String) {
        if (characterId.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("pending_concern_promise_$characterId", true).apply()
        PerceptionWakePlanStore.invalidate(characterId)
        ProactivePerceptionScheduler.scheduleNextDue(context.applicationContext)
    }

    fun wakePlanFor(context: Context, characterId: String, now: Instant = Instant.now()): PerceptionWakePlan? {
        initialize(context)
        val policy = ProactivePerceptionPolicyStore.get(characterId)
        if (!policy.enabled) return null
        val conversation = latestPrivateConversations().firstOrNull { it.characterId == characterId } ?: return null
        dueAtFor(context, conversation, policy, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), now)
        return PerceptionWakePlanStore.plans.value[characterId]
    }

    fun nextDueAt(context: Context, now: Instant = Instant.now()): Instant? {
        initialize(context)
        val conversations = latestPrivateConversations()
        if (conversations.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return conversations.mapNotNull { conversation ->
            val characterId = conversation.characterId.ifBlank { "lulu" }
            val policy = ProactivePerceptionPolicyStore.get(characterId)
            if (!policy.enabled || CompanionPresenceStore.isInCall(characterId)) return@mapNotNull null
            dueAtFor(context, conversation, policy, prefs, now)
        }.minOrNull()
    }

    suspend fun runDueCycle(
        context: Context,
        trigger: String,
        targetCharacterId: String? = null,
        force: Boolean = false,
        now: Instant = Instant.now(),
        onlineRevision: Long? = null,
        requiresUnread: Boolean = false,
        preserveOffline: Boolean = false,
        requiredInteractionEvidenceId: String? = null,
    ): Int = cycleMutex.withLock {
        currentCoroutineContext().ensureActive()
        // Mark the event finished only when a *decision* is recorded. A process
        // may die after the private appraisal is committed but before action.
        if (targetCharacterId != null && !requiredInteractionEvidenceId.isNullOrBlank() &&
            CharacterCausalAppraisalStage.hasCompletedDecision(targetCharacterId, requiredInteractionEvidenceId)) {
            return@withLock 0
        }
        if (onlineRevision != null && targetCharacterId != null &&
            !OnlineChatBatchStore.isCurrent(context, targetCharacterId, onlineRevision)) return@withLock 0
        if (onlineRevision != null && targetCharacterId != null &&
            !OnlineChatBatchStore.claim(context, targetCharacterId, onlineRevision)) return@withLock 0
        try {
            if (requiresUnread && targetCharacterId != null && CompanionOnlineStore.unreadChatSnapshot(targetCharacterId).text.isBlank()) return@withLock 0
            initialize(context)
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val targets = latestPrivateConversations().filter { targetCharacterId == null || it.characterId == targetCharacterId }
            var evaluated = 0
            for (conversation in targets) {
                val characterId = conversation.characterId.ifBlank { "lulu" }
                // The connected call owns perception and expression, including quiet pulses.
                // General background decisions must not start a second call or send competing chat.
                if (CompanionPresenceStore.isInCall(characterId)) continue
                val policy = ProactivePerceptionPolicyStore.get(characterId)
                if (!policy.enabled && (!force || preserveOffline)) continue
                // One PRIMARY online chat-batch owner should handle unread
                // content. Opportunistic world/life/timer ticks are NOT
                // alternative reply workers. Without this guard they could
                // all pay for the same 30K-token recall while the user waits.
                if (onlineRevision == null &&
                    CompanionOnlineStore.isOnline(characterId, now) &&
                    CompanionOnlineStore.unreadChatSnapshot(characterId).text.isNotBlank()) {
                    continue
                }
                // A lifestyle-review wake is not permission to disturb quiet
                // hours just to produce a social post; unresolved cues persist.
                if (trigger.startsWith("真实生活后续回想") &&
                    isQuietNow(policy, now.atZone(ZoneId.systemDefault()).toLocalTime())) continue
                if (!force) {
                    val due = dueAtFor(appContext, conversation, policy, prefs, now)
                    if (!trigger.startsWith("重要事件") && due.isAfter(now.plusSeconds(15))) continue
                    if (isQuietNow(policy, now.atZone(ZoneId.systemDefault()).toLocalTime())) continue
                }
                val pendingConcern = prefs.getBoolean("pending_concern_promise_$characterId", false)
                val effectiveTrigger = when {
                    trigger.contains("挂心") || trigger.contains("承诺") -> trigger
                    pendingConcern -> "挂心/承诺待回看"
                    else -> trigger
                }
                prefs.edit().putLong("last_evaluation_$characterId", now.toEpochMilli()).apply()
                if (!preserveOffline && (!force || !CompanionOnlineStore.isOnline(characterId, now))) {
                    CompanionOnlineStore.wakeCharacter(characterId, CompanionOnlineReason.BackgroundPerception, effectiveTrigger, false, now)
                }
                CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知启动 · $effectiveTrigger", now)
                val result = runCatching {
                    evaluateCharacter(appContext, conversation, effectiveTrigger, now,
                        requiredInteractionEvidenceId)
                }
                result.onSuccess { action ->
                    evaluated += 1
                    CharacterDevelopmentRuntime.request(characterId)
                    val actionKey = "action_history_$characterId"
                    val actionHistory = prefs.getString(actionKey, "").orEmpty().split(',').map(String::trim)
                        .filter(String::isNotBlank).plus(action.name).takeLast(ACTION_HISTORY_SIZE)
                    prefs.edit()
                        .putString(actionKey, actionHistory.joinToString(","))
                        .putBoolean("pending_concern_promise_$characterId", false)
                        .remove("silent_count_$characterId")
                        .apply()
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    CompanionPresenceStore.recordPerceptionAttempt(
                        characterId,
                        "感知失败 · ${error.message.orEmpty().ifBlank { error::class.java.simpleName }.take(120)}",
                        now,
                    )
                }
            }
            evaluated
        } finally {
            if (onlineRevision != null && targetCharacterId != null) {
                OnlineChatBatchStore.finish(context, targetCharacterId, onlineRevision,
                    completed = currentCoroutineContext()[kotlinx.coroutines.Job]?.isCancelled != true)
            }
        }
    }

    private fun dueAtFor(
        context: Context,
        conversation: LuluConversation,
        policy: ProactivePerceptionPolicy,
        prefs: android.content.SharedPreferences,
        now: Instant,
    ): Instant {
        val characterId = conversation.characterId.ifBlank { "lulu" }
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val lastChat = messages.asSequence()
            .filter { it.status == LuluChatMessage.Status.Sent && it.sender != LuluChatMessage.Sender.System }
            .maxByOrNull(LuluChatMessage::createdAt)?.createdAt ?: conversation.updatedAt
        val lastEvaluation = prefs.getLong("last_evaluation_$characterId", 0L)
            .takeIf { it > 0L }?.let(Instant::ofEpochMilli)
        val anchor = listOfNotNull(lastChat, lastEvaluation).maxOrNull() ?: now
        val signature = "${policy.normalized()}:${ZoneId.systemDefault().id}"
        val plan = PerceptionWakePlanStore.resolve(characterId, anchor, signature) {
            val timingVariation = if (policy.adaptiveFrequency) {
                val latestUserAt = messages.asSequence()
                    .filter { it.status == LuluChatMessage.Status.Sent && it.sender == LuluChatMessage.Sender.User }
                    .maxByOrNull(LuluChatMessage::createdAt)?.createdAt
                val minutesSinceUser = latestUserAt?.let {
                    Duration.between(it, now).toMinutes().coerceAtLeast(0L)
                }
                val hasConcern = LuluRepositories.lexicon.snapshot(characterId).any {
                    it.section == LexiconSection.Concern &&
                        it.status == com.jiacimu.lulu.core.LexiconStatus.Active
                }
                adaptivePerceptionMultiplier(
                    jitter = stableTimingVariation(characterId, anchor),
                    unread = CompanionOnlineStore.unreadChatSnapshot(characterId).text.isNotBlank(),
                    pendingConcern = prefs.getBoolean("pending_concern_promise_$characterId", false),
                    hasConcern = hasConcern,
                    minutesSinceUserContact = minutesSinceUser,
                )
            } else 1.0
            val interval = policy.intervalMinutes(timingVariation)
            PerceptionWakePlan(characterId, anchor,
                deferPastQuietHours(anchor.plus(Duration.ofMinutes(interval)), policy), interval, signature)
        }
        if (plan.dueAt <= now && isQuietNow(policy, now.atZone(ZoneId.systemDefault()).toLocalTime())) {
            return PerceptionWakePlanStore.deferUntil(characterId, deferPastQuietHours(now, policy))?.dueAt ?: plan.dueAt
        }
        return plan.dueAt
    }

    private fun stableTimingVariation(characterId: String, anchor: Instant): Double {
        val unsignedHash = "$characterId:${anchor.toEpochMilli()}".hashCode().toLong() and 0xffff_ffffL
        return 0.85 + unsignedHash.toDouble() / 0xffff_ffffL.toDouble() * 0.30
    }

    private fun deferPastQuietHours(time: Instant, policy: ProactivePerceptionPolicy): Instant {
        if (!policy.quietHoursEnabled) return time
        val zone = ZoneId.systemDefault()
        val local = time.atZone(zone)
        val start = policy.quietStartMinutesOfDay
        val end = policy.quietEndMinutesOfDay
        if (start == end) return time
        val minute = local.hour * 60 + local.minute
        val quiet = if (start < end) minute in start until end else minute >= start || minute < end
        if (!quiet) return time
        val endDate = when {
            start < end -> local.toLocalDate()
            minute >= start -> local.toLocalDate().plusDays(1)
            else -> local.toLocalDate()
        }
        return endDate.atTime(end / 60, end % 60).atZone(zone).toInstant()
    }

    private fun isQuietNow(policy: ProactivePerceptionPolicy, time: LocalTime): Boolean {
        if (!policy.quietHoursEnabled) return false
        val start = policy.quietStartMinutesOfDay
        val end = policy.quietEndMinutesOfDay
        if (start == end) return false
        val minute = time.hour * 60 + time.minute
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    private fun latestPrivateConversations(): List<LuluConversation> =
        MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { it.parentConversationId == null && it.groupChat == null && !it.id.endsWith("-study-focus") }
            .groupBy(LuluConversation::characterId)
            .mapNotNull { (_, values) -> values.maxByOrNull(LuluConversation::updatedAt) }

    private suspend fun evaluateCharacter(
        appContext: Context, conversation: LuluConversation, trigger: String,
        now: Instant, requiredInteractionEvidenceId: String? = null,
    ): Action {
        val characterId = conversation.characterId.ifBlank { "lulu" }
        val unread = CompanionOnlineStore.unreadChatSnapshot(characterId)
        if (unread.text.isBlank()) return evaluateCharacterWithActivity(
            appContext, conversation, trigger, now, requiredInteractionEvidenceId,
        )
        return ChatGenerationActivity.during(characterId, unread.conversationIds + conversation.id) {
            try {
                evaluateCharacterWithActivity(
                    appContext, conversation, trigger, now, requiredInteractionEvidenceId,
                )
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (unread.text.isNotBlank()) {
                    (unread.conversationIds + conversation.id).forEach { id ->
                        com.jiacimu.lulu.ChatReplyTaskManager.TaskContext(id).reportError(error.message ?: "回复失败")
                    }
                }
                throw error
            }
        }
    }

    private suspend fun evaluateCharacterWithActivity(
        appContext: Context,
        conversation: LuluConversation,
        trigger: String,
        now: Instant,
        requiredInteractionEvidenceId: String? = null,
    ): Action {
        val characterId = conversation.characterId.ifBlank { "lulu" }
        val character = MigratedDomainStores.characters.get(characterId)
        val awakeReflection = CompanionOnlineStore.isOnline(characterId, now)
        val awakeObservationId = "online-awareness-$characterId-${now.toEpochMilli()}"
        val worldTick = if (DigitalLifeProfileStore.isEnabled(characterId)) {
            DigitalWorldLifeEventStore.tick(appContext, characterId, now)
        } else null
        worldTick?.takeIf { tick ->
            !DigitalWorldLifeEventStore.isAmbientMoment(tick) ||
                DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick)
        }?.let { tick ->
            // Material incidents and rare personal mishaps may be visible in chat.
            // Smaller ambient gestures stay in the lived timeline and can be shared naturally.
            MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, tick.summary, tick.incidentId)
        }
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val zoneId = ZoneId.systemDefault()
        val localTimeText = now.atZone(zoneId).format(
            DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm:ss", Locale.SIMPLIFIED_CHINESE),
        )
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recentAutonomousActions = prefs.getString("action_history_$characterId", "").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank)
        val library = LuluAiServices.connectionStore.library.value
        val perceptionArchiveId = library.archiveIdFor(ModelUsage.Chat)
        if (perceptionArchiveId == null) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知暂停 · 没有可用的聊天模型", now)
            return Action.SILENT
        }
        val connection = LuluAiServices.connectionStore.resolveConnection(perceptionArchiveId)
        val availableGroups = MigratedDomainStores.chat.conversations.value.filter {
            it.groupChat?.members?.any { member -> member.characterId == characterId } == true
        }
        val onlineUnread = CompanionOnlineStore.unreadChatSnapshot(characterId)
        val userActivities = collectUserActivities(characterId)
        val pendingUserContext = userActivities.filter(UserActivity::awaitingReply)
            .take(12).joinToString("\n") { formatUserActivity(it, zoneId) }.take(4_000)
        val recentUserActivityContext = userActivities.take(12)
            .take(12).joinToString("\n") { formatUserActivity(it, zoneId) }.take(4_000)
        val recent = messages.filterNot { it.sender == LuluChatMessage.Sender.System && it.content.startsWith("[共同活动]") }.takeLast(20).joinToString("\n") { message ->
            val speaker = when (message.sender) {
                LuluChatMessage.Sender.User -> "用户"
                LuluChatMessage.Sender.Character -> character.displayName
                LuluChatMessage.Sender.System -> "系统事件"
            }
            "${message.createdAt.atZone(zoneId).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} $speaker：${qqForwardContextText(message.content).take(500)}"
        }
        val recentLifeEvents = SharedExperienceTimeline.all(characterId)
            .filter { event ->
                event.channel.startsWith("数字世界") ||
                    event.channel.startsWith("独自阅读") ||
                    event.channel.startsWith("独自游戏") ||
                    event.channel.startsWith("现实世界窗口")
            }
            .takeLast(12)
        val recentLifeContext = recentLifeEvents.joinToString("\n") { event ->
            "- [evidenceId=${event.id}；${event.occurredAt.atZone(zoneId).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}] ${event.channel}：${event.evidenceContent.replace(Regex("\\s+"), " ").take(500)}"
        }
        val lexicon = LuluRepositories.lexicon.snapshot(characterId)
        val concerns = lexicon.filter { it.section == LexiconSection.Concern && it.status == com.jiacimu.lulu.core.LexiconStatus.Active }
            .joinToString("\n") { "- ${it.title}：${it.content.take(400)}；最近回访=${it.lastFollowUpAt}" }
        val commitments = lexicon.filter { it.section == LexiconSection.Promise && it.status == com.jiacimu.lulu.core.LexiconStatus.Active }
            .joinToString("\n") { "- ${it.title}：${it.content.take(400)}" }
        val previousPresence = CompanionPresenceStore.current(characterId)
        val continuityContext = CharacterContinuityRuntime.proactiveContext(characterId, now)
        val proactiveInitiativeContext = CharacterInitiativeRuntime.proactiveContext(
            characterId = characterId,
            recentActions = recentAutonomousActions,
            hasConcern = concerns.isNotBlank(),
            hasCommitment = commitments.isNotBlank(),
            now = now,
        )
        val observedWorld = CharacterPerceptionContext.pending(appContext, characterId, now)
        // Consequential world and call events use this SAME autonomous action
        // decision as unread messages, not an extra paid pre-action appraisal.
        val salientInteraction = CharacterCausalAppraisalStage.latestPending(observedWorld)
        val deviceContext = UserDevicePerception.context(appContext, characterId, now, refreshLocation = true)
        com.jiacimu.lulu.study.ReadingReflectionStore.initialize(appContext)
        val lastReading = com.jiacimu.lulu.study.ReadingReflectionStore.records.value
            .filter { it.characterId == characterId }.maxByOrNull { it.occurredAt }
        val readingBooks = ReadingBackgroundBridge.availableBooks(appContext, characterId)
            .sortedByDescending { book -> book.id == lastReading?.bookId ||
                (book.seriesId.isNotBlank() && lastReading?.bookTitle?.startsWith("《${book.seriesId}》") == true) }
        val digital = DigitalLifeProfileStore.isEnabled(characterId)
        val currentLocation = if (digital) DigitalWorldStore.locationOf(characterId) else ""
        val activityChoices = if (digital) DigitalWorldActivityCatalog.locationOptions(currentLocation) else emptyList()
        val itemChoices = if (digital) DigitalWorldStore.itemsAtLocation(characterId).mapNotNull { item ->
            DigitalWorldActivityCatalog.optionsFor(item).takeIf(List<Pair<String, String>>::isNotEmpty)
                ?.let { item.id to it }
        } else emptyList()
        val affordances = AutonomousAffordanceContext.render(
            digital = digital,
            location = currentLocation,
            books = readingBooks.map { AutonomousAffordanceContext.Book(it.id, it.title) },
            groups = availableGroups.map { it.id to it.groupChat?.name.orEmpty() },
            locationActivities = activityChoices,
            publicPlaces = if (digital) DigitalWorldPublicPlaces.all.map { it.code to it.label } else emptyList(),
            currentItems = itemChoices,
            currentResidents = if (digital) DigitalWorldExpansionStore.residentsAt(currentLocation)
                .map { Triple(it.id, it.name, it.identity) } else emptyList(),
        )

        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                // Persona is already supplied once by the gateway.
                appendLine("\n【用户现实设备与用户状态感知层】")
                appendLine("重要归属：下面的电量、前台应用、通知、位置、健康/手环和学习信息都属于用户本人或用户正在使用的现实设备，不属于角色自己的手机或身体。")
                appendLine("触发来源：$trigger")
                if (awakeReflection) appendLine("在线意味着你持续醒着：即使这次选择 silent，也可以思考已知经历、保留或修正自己的感受与愿望。只有出现真正新增的念头才写 innerThought/innerLife；若只是‘还在等、还是不催、继续看看’的同义改写就省略，让旧心声停留在历史里。无外部新事件不是内在停止的理由，但也不是制造新内心独白的理由。回想旧事不是它再次发生，不因为时间检查重新放大情绪；不虚构动作或把用户沉默当作离开。")
                appendLine("本次判断：如果没有待处理的新消息，这仍是角色真实生活的一段时间，不是只能更新状态的空轮询；在线期间也不会因为没有未读消息就被系统强制 SILENT。")
                appendLine("用户设备本地时间：$localTimeText（时区 ${zoneId.id}）")
                appendLine(deviceContext)
                appendLine(CharacterPerceptionContext.render(observedWorld))
                appendLine(DigitalWorldLiveScene.context(characterId))
                salientInteraction?.let { event ->
                    appendLine("【本轮真实互动的同次理解与抉择】")
                    appendLine("真实证据ID=${event.id}；记录=${event.content.take(260)}")
                    appendLine("在本轮行动决策中自行判断有无情绪、挂心、愿望与后续行为，不需要再开一次模型。")
                    appendLine("先理解可见细节如何改变自己的注意、情绪和愿望，再选行动。可以疑惑、期待、好奇她在做什么或想分享，但这些是自己的私人解释，不能断言用户尚未表达的心思；也不必每次强行反省或采取行动。")
                }
                appendLine(affordances)
                CharacterLifeFollowThroughStore.context(characterId, now)
                    .takeIf(String::isNotBlank)?.let(::appendLine)
                appendLine(com.jiacimu.lulu.StickerLibraryStore.prompt(appContext, 24))
                CharacterSelfActivityRuntime.latestConsideration(appContext, characterId)?.let { idea ->
                    appendLine("【过去自己想到但还没决定的事｜没有真的做过】")
                    appendLine("当时考虑：${idea.optString("action")}；当时原因：${idea.optString("whyNow")}")
                    appendLine("这只是曾经的私人念头，不能当成新世界事件、已经做成、义务或必须执行的命令。若今天仍想做，自己选择本轮真实 action 和有效 ID，走执行回执；也可以放下。")
                }
                CharacterSelfActivityRuntime.latest(appContext, characterId)?.let { activity ->
                    val age = System.currentTimeMillis() - activity.optLong("createdAt")
                    if (age in 0..(2 * 60 * 60_000L)) {
                        appendLine("【上次自己决定做的生活小事｜独立执行状态，不是模型口述事实】")
                        appendLine("想做：${activity.optString("label")}；原因：${activity.optString("whyNow")}")
                        appendLine("实际状态：${activity.optString("status")}；执行器回执：${activity.optString("result")}")
                        appendLine("只以 completed 和真实工具回执确认做成；pending/running 不能提前声称做完，failed 不算经历。")
                    }
                }
                appendLine("允许主动来电：${if (character.contactPolicy.proactiveCallsEnabled) "是" else "否"}")
                if (recentAutonomousActions.isNotEmpty()) {
                    appendLine("最近自主选择（旧→新，仅作为生活历史，不用于惩罚重复）：${recentAutonomousActions.joinToString(" → ")}")
                }
                if (readingBooks.isNotEmpty()) {
                    appendLine("阅读 App 中可独自阅读的内容：")
                    readingBooks.forEach {
                        appendLine("- readingBookId=${it.id}；${it.title}；来源=${it.source}；${ReadingBackgroundBridge.progressLabel(appContext, characterId, it)}")
                    }
                }
                if (availableGroups.isNotEmpty()) {
                    appendLine("所在群聊：")
                    availableGroups.forEach {
                        appendLine("- groupId=${it.id}；${it.groupChat?.name}；最近=${it.lastMessage.take(120)}")
                    }
                }
                if (recentUserActivityContext.isNotBlank()) {
                    appendLine("【用户跨场景最新动态｜新→旧】")
                    appendLine(recentUserActivityContext)
                }
                if (onlineUnread.text.isNotBlank()) {
                    appendLine("【本次上线尚未处理的新动态｜旧→新】")
                    appendLine(onlineUnread.text.takeLast(5_000))
                }
                appendLine("\n【长期上下文层】")
                if (continuityContext.isNotBlank()) appendLine(continuityContext)
                appendLine(CharacterExpressionContinuity.guide())
                // The same persistent concerns that private chat and calls use:
                // a chosen quiet interval must not wipe an unfinished thought.
                appendLine(CharacterOpenConcernRuntime.context(
                    CharacterInnerLifeStore.snapshot(characterId).optJSONArray("openConcerns"), now,
                ))
                if (proactiveInitiativeContext.isNotBlank()) appendLine(proactiveInitiativeContext)
                // The gateway already includes the shared personality runtime,
                // including relational initiatives; avoid inserting the same evidence twice.
                appendLine(AutonomousActionTrace.render(
                    CharacterInnerLifeStore.snapshot(characterId).optJSONArray("decisions"), now,
                ))
                appendLine(CharacterCuriosityRuntime.promptSection(
                    characterId, character.displayName, recentAutonomousActions, now,
                ))
                lastReading?.let { appendLine("最近真正读过《${it.bookTitle}》${it.chapterTitle}，停在字符${it.endOffset}；当时感想：${it.reflection.take(1_200)}。是否继续由此刻愿望决定；尚未读到的情节未知。") }
                previousPresence?.let {
                    appendLine("上一刻：${it.statusText}；${it.gesture}；${it.mood}；心声=${it.innerThought}")
                    appendLine("上次实际感知结果：${it.lastPerceptionNote}")
                }
                if (recentLifeContext.isNotBlank()) {
                    appendLine("【角色最近自己的生活记录｜旧→新】")
                    appendLine(recentLifeContext)
                }
                if (concerns.isNotBlank()) appendLine("【挂心】\n$concerns")
                if (commitments.isNotBlank()) appendLine("【承诺与监督】\n$commitments")
                val clockResponsibilities = CommitmentTaskStore.active(characterId)
                if (clockResponsibilities.isNotEmpty()) {
                    appendLine("【已接下的约定 · 程序负责执行，绝不能以日记代替】")
                    clockResponsibilities.take(10).forEach { task ->
                        appendLine("- 目标=${task.goal}；到期=${task.dueAt}；状态=${task.status}；动作=${task.deliveryAction}；手机闹钟ID=${task.linkedAlarmId.orEmpty()}；执行回执=${task.lastActionResult.take(160)}")
                    }
                    appendLine("这些到期执行由系统闹钟驱动，而非等你上线时想起；写日记、想念、表达打算都不算兑现。到期前可以准备，也可以按自己的生活行动，但不能提前写成已叫醒用户。")
                }
                // The gateway supplies the authoritative digital-world state once.
                worldTick?.let {
                    appendLine("【本轮数字世界程序事件｜不可改写】")
                    appendLine(it.summary)
                    appendLine("incidentId=${it.incidentId}；status=${it.status}；stage=${it.stage}；anchorItemId=${it.anchorItemId}")
                }
                if (pendingUserContext.isNotBlank()) {
                    appendLine("【历史上还没实际回复的用户消息｜不是每轮的新通知】")
                    appendLine("以下内容可能早已看过并自主决定暂不答复；它们仍属于历史上的未回复事实，但不是每次在线生活感知都必须优先处理的指令。若有真正的新消息，下面的新消息列表会单独列出。")
                    appendLine(pendingUserContext)
                }
                if (recent.isNotBlank()) appendLine("【最近聊天与生活事件】\n$recent")
            },
            instruction = proactiveDecisionInstruction(characterId) + "\n" +
                if (onlineUnread.text.isNotBlank())
                    "【先处理本次真正的新消息】若当前确实想回应，JSON必须以action和text开头，先写完能发送的文字，然后再简要写非必要的心态字段；优先使用message或group_message，不要先写几千字的私人分析。整个JSON尽量在1000个输出token以内。只允许真正自愿且说得出缘由的silent，不得因格式失败将已收到的消息当作已处理。"
                else "允许action=tool，tool为能力名，args为参数。只执行主动允许的能力；可选择silent。",
            source = "后台主动感知",
            title = "${character.displayName}的主动感知",
            // A complete action/text is more useful than a long private monologue.
            // Headroom keeps a final JSON brace from being cut off.
            maxTokens = 3_000,
            connectionOverride = connection,
            memoryRequest = UnifiedMemoryRequest(
                currentInput = listOf(onlineUnread.text, salientInteraction?.content.orEmpty())
                    .filter(String::isNotBlank).joinToString("\n"),
                sceneContext = "后台主动感知 · $trigger",
                recentContext = listOf(recent, recentLifeContext, pendingUserContext, concerns, commitments)
                    .filter(String::isNotBlank).joinToString("\n"),
                taskIntent = "只依据程序已记录的真实状态选择可执行动作；可对本轮权威事件产生反应，但不得生成新的事实",
            ),
        ).getOrElse { error ->
            CompanionPresenceStore.recordPerceptionAttempt(
                characterId,
                "模型请求失败 · ${error.message.orEmpty().take(120)}",
                now,
            )
            throw error
        }
        currentCoroutineContext().ensureActive()
        // A call takes ownership immediately; a finished awake window cannot execute stale decisions.
        if (CompanionPresenceStore.isInCall(characterId) ||
            (awakeReflection && !CompanionOnlineStore.isOnline(characterId))) return Action.SILENT
        if (awakeReflection) SharedExperienceTimeline.record(
            eventId = awakeObservationId, characterId = characterId,
            channel = "在线感知", speaker = "在线观察",
            content = "角色处于在线窗口，本次感知实际读取了当前时间与获准读取的设备状态；新增聊天=${onlineUnread.text.isNotBlank()}；新增场景观察=${observedWorld.size}",
            occurredAt = now, triggerExtraction = false, source = "online-awareness",
            evidenceKind = EventEvidenceKind.Observation)
        val parsed = parseDecision(result.text, actionOnly = false) ?: run {
            // Invalid action output cannot execute, but never triggers a second
            // private-reflection model call.
            CompanionPresenceStore.recordPerceptionAttempt(
                characterId, "模型回复格式不完整，没有送达消息；尚未将用户消息标记为已处理", now,
            )
            if (onlineUnread.text.isNotBlank()) {
                (onlineUnread.conversationIds + conversation.id).forEach { id ->
                    com.jiacimu.lulu.ChatReplyTaskManager.TaskContext(id).reportError(
                        "模型输出过长或格式不完整，消息没有送达。请点击回复重试；本次不会假装已经回复。"
                    )
                }
            }
            return Action.SILENT
        }
        // The main appraisal may repeatedly answer "nobody wrote to me, so nothing to do".
        // One bounded second look during an online window challenges *that reason*,
        // not the character's freedom to choose real solitude.
        val lastAgencyReview = prefs.getLong("last_independent_life_review_$characterId", 0L)
        val immediateChoices = CharacterActionFollowThroughPolicy.pendingChoices(
            parsed.innerLife, parsed.intention, parsed.afterglow, parsed.innerThought,
            parsed.appraisal?.optJSONObject("ownActivity") ?:
                CharacterSelfActivityRuntime.latestConsideration(appContext, characterId),
        )
        val rememberedChoices = CharacterActionFollowThroughPolicy.pendingChoices(
            CharacterInnerLifeStore.snapshot(characterId),
            CharacterLifeStore.state(characterId).optJSONObject("intention"), null, "",
        )
        // The character's own real but unresolved life remains a candidate even
        // if this model response said silent. A second look still may freely say no.
        val pendingChoices = (immediateChoices + rememberedChoices +
            CharacterLifeFollowThroughStore.pendingDecisionCues(characterId, now) +
            CharacterCuriosityRuntime.pendingDecisionCues(characterId))
            .distinct().take(6)
        val ownActivityAlreadyExecutable = CharacterSelfActivityRuntime.parse(
            parsed.appraisal?.optJSONObject("ownActivity"), readingBooks.mapTo(hashSetOf()) { it.id }, digital,
        ) { incidentId, approach -> DigitalWorldLifeEventStore.canHandle(characterId, incidentId, approach) } != null
        val scheduledActivity = CharacterSelfActivityRuntime.latest(appContext, characterId)
        val activityInFlight = scheduledActivity?.optString("status") in setOf("pending", "running") &&
            now.toEpochMilli() - (scheduledActivity?.optLong("createdAt") ?: 0L) in 0L..(60 * 60_000L)
        val followThroughKey = pendingChoices.joinToString("|").hashCode().toString()
        val followThroughReview = CharacterActionFollowThroughPolicy.shouldReview(
            silent = parsed.action == Action.SILENT && !ownActivityAlreadyExecutable && !activityInFlight, choices = pendingChoices,
            key = followThroughKey, previousKey = prefs.getString("last_impulse_review_key_$characterId", "").orEmpty(),
            nowMs = now.toEpochMilli(), previousAtMs = prefs.getLong("last_impulse_review_at_$characterId", 0L),
        )
        val shouldReview = followThroughReview || (!ownActivityAlreadyExecutable && !activityInFlight && awakeReflection &&
            now.toEpochMilli() - lastAgencyReview >= 5 * 60_000L &&
            AutonomousDecisionRecovery.needsIndependentLifeReview(
                silent = parsed.action == Action.SILENT,
                reason = parsed.reason,
                recentActions = recentAutonomousActions,
                hasPendingUserMessage = onlineUnread.text.isNotBlank(),
            ))
        val independentChoice = if (shouldReview) {
            prefs.edit().putLong("last_independent_life_review_$characterId", now.toEpochMilli())
                .putString("last_impulse_review_key_$characterId", followThroughKey)
                .putLong("last_impulse_review_at_$characterId", now.toEpochMilli()).apply()
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine("这是同一次感知中尚未行动的愿望的后续选择，不是又一个用户消息。")
                    appendLine("尚未取得行动回执的私人想法：${pendingChoices.joinToString("；")}")
                    appendLine("原先完整选择理由：${parsed.reason}")
                    appendLine("需要回应的用户消息：${onlineUnread.text.take(800)}")
                    appendLine("上一选择：silent；原理由：${parsed.reason.take(220)}")
                    appendLine("真实时间：$localTimeText；所在地点：$currentLocation")
                    appendLine(affordances)
                    CharacterLifeFollowThroughStore.context(characterId, now)
                        .takeIf(String::isNotBlank)?.let(::appendLine)
                    appendLine("近期实际行动：${AutonomousActionTrace.render(CharacterInnerLifeStore.snapshot(characterId).optJSONArray("decisions"), now)}")
                },
                instruction = """
                    你先前产生了想法却没有选择行动，或者因没人来找你而把自己的生活搁置。
                    现在只为这些已有愿望作一个具体决定，不重新编造感受或事后倒写动机。
                    请从本人性格、兴趣、真实经历和上面列出的执行器能力判断：
                    现在有没有一件你自己真的想做的事？可以去真实公共区、找在场居民、
                    读实际可读的书、写私人日记、发表自己想分享的朋友圈或自然联系朋友。
                    不存在的地点、居民、书籍不能编造；工具成功前不能说已经做过。
                    如果确实想做，直接给出 action 和合法参数。若真的想休息/独处，
                    仍选择 silent，并说出暂缓/放下这个愿望的具体原因；不是任务考核，不强制行动。
                    若想继续阅读，请从书目中选真实 readingBookId 并给 action=reading；
                    不要用心声、动作描写或“我去读了”替代工具执行。
                    仅输出兼容的简洁 JSON：action、reason 和动作参数，可保留真实 motiveId、experienceId。
                    不输出 innerLife、innerThought、mood、intention；已有私人感受由原先阶段保存。
                """.trimIndent(),
                source = "在线自主生活再判断",
                title = "${character.displayName}重新考虑自己的生活",
                maxTokens = 1_300,
                connectionOverride = connection,
            ).getOrNull()?.text?.let { parseDecision(it, actionOnly = false) }
        } else null
        currentCoroutineContext().ensureActive()
        // A phone call or expired online window may take ownership while the
        // optional follow-through model is in flight. Never execute its stale choice.
        if (CompanionPresenceStore.isInCall(characterId) ||
            (awakeReflection && !CompanionOnlineStore.isOnline(characterId))) return Action.SILENT
        // The review owns ONLY the action. Preserve the original appraisal,
        // heart voice and motive proposal instead of silently discarding them.
        val resolved = independentChoice?.copy(
            innerThought = parsed.innerThought, innerThoughtBasis = parsed.innerThoughtBasis,
            innerLife = parsed.innerLife, appraisal = parsed.appraisal,
            intention = parsed.intention, afterglow = parsed.afterglow,
            mood = parsed.mood, curiosity = independentChoice.curiosity ?: parsed.curiosity,
            motiveId = independentChoice.motiveId.ifBlank { parsed.motiveId },
            intentionId = independentChoice.intentionId.ifBlank { parsed.intentionId },
            experienceId = independentChoice.experienceId.ifBlank { parsed.experienceId },
            relationshipInitiative = parsed.relationshipInitiative,
        ) ?: parsed
        val fallbackDecision = resolved.withPresenceFallback(character)
        val decision = fallbackDecision.copy(innerThought =
            CharacterAccountabilityContext.guardUnfoundedInnerBlame(
                listOf(onlineUnread.text, pendingUserContext, trigger).joinToString("\n"),
                fallbackDecision.innerThought,
            ))
        // Emotion and intent are decided together in this same action call.
        CharacterLifeStore.consider(characterId, decision.intention, now)
        // Capture the private state before this proposal is applied. Delta must compare two moments,
        // not compare the model proposal against a store we already mutated with that proposal.
        val privateStateBefore = CharacterInnerLifeStore.snapshot(characterId)
        // The executor, not the model, anchors subjective emotion to a real observed event.
        // Old chat history alone must not create an apparently new emotional stimulus.
        val messageStimulus = PerceptionStimulusResolver.select(
            unreadText = onlineUnread.text,
            unreadIds = onlineUnread.newestIds,
            worldEvent = "",
            worldEventId = "",
            pendingText = pendingUserContext,
            pendingIds = userActivities.filter(UserActivity::awaitingReply).take(12).map { it.message.id },
        )
        val perceptionInput = CharacterPerceptionContext.integrate(
            context = appContext,
            characterId = characterId,
            observed = observedWorld,
            direct = listOfNotNull(messageStimulus),
            claimDirect = true,
        )
        val newlyObserved = perceptionInput.freshStimuli
        val stimulus = perceptionInput.combined
        val emotionalAnchor = stimulus?.description.orEmpty()
        val freshStimulus = stimulus != null
        if (freshStimulus) CharacterLifeStore.recordAfterglow(
            characterId, emotionalAnchor, decision.afterglow, now,
            evidenceId = stimulus?.evidenceId.orEmpty(),
        )
        if (freshStimulus && stimulus != null) CharacterInnerLifeStore.observe(
            characterId, stimulus.evidenceId,
            stimulus.description,
            CharacterInnerLifeStore.withAfterglow(decision.innerLife, decision.afterglow, emotionalAnchor),
            stimulus.socialIds, now,
        )
        // Reconsidering a real, previously recorded conflict is not a new world
        // event. Anchor introspection to the original witnessed reply so deleting
        // that reply invalidates the derived self-correction too.
        val revisitingConflict = listOf("争执", "冲突", "歉意", "强烈情绪", "后续整理", "悔恨")
            .any(trigger::contains)
        val previousFeeling = privateStateBefore.optJSONObject("emotion")
        val previousEvidence = if (revisitingConflict || awakeReflection)
            previousFeeling?.optString("evidenceId").orEmpty() else ""
        if (emotionalAnchor.isBlank() && previousEvidence.isNotBlank() && decision.innerLife != null) {
            CharacterInnerLifeStore.observe(
                characterId, previousEvidence,
                "针对已有情绪的后续反思：${previousFeeling?.optString("feeling").orEmpty()}；本次实际感知时间=$now",
                decision.innerLife, if (revisitingConflict) setOf("user") else emptySet(), now,
            )
        }
        if (awakeReflection && emotionalAnchor.isBlank() && previousEvidence.isBlank() && decision.innerLife != null) {
            CharacterInnerLifeStore.observe(characterId, awakeObservationId,
                "在线期间基于已知状态的内在变化，没有新增用户发言或虚构外部事件",
                decision.innerLife, emptySet(), now)
        }
        // Heart voice is a sparse projection of a real private-state delta. The model may propose
        // prose, but the program rejects a paraphrase of outward speech and generic waiting scripts
        // when nothing genuinely changed.
        val privateDelta = PrivateStateDeltaEngine.evaluate(
            previous = privateStateBefore,
            proposal = decision.innerLife,
            appraisal = decision.appraisal,
            basis = decision.innerThoughtBasis,
            thought = decision.innerThought,
        )
        val groundedInnerThought = CharacterHeartVoicePolicy.keepOrBlank(
            thought = decision.innerThought,
            outward = decision.text,
            innerLife = decision.innerLife,
            basis = decision.innerThoughtBasis,
            delta = privateDelta,
            hasFreshEvidence = freshStimulus || previousEvidence.isNotBlank() ||
                decision.action != Action.SILENT,
        )
        val causalEvidenceId = when {
            // The user owns the reply lane. Persist subjective state against the
            // real input message instead of the synthetic combined world+chat ID.
            messageStimulus != null &&
                SharedExperienceTimeline.eventsByIds(characterId, listOf(messageStimulus.evidenceId)).isNotEmpty() ->
                messageStimulus.evidenceId
            salientInteraction != null -> salientInteraction.id
            stimulus != null -> stimulus.evidenceId
            previousEvidence.isNotBlank() -> previousEvidence
            awakeReflection -> awakeObservationId
            else -> "perception:${now.toEpochMilli()}:${trigger.take(35)}"
        }
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = characterId,
            evidenceId = causalEvidenceId,
            appraisal = decision.appraisal,
            innerLife = decision.innerLife,
            innerThoughtBasis = decision.innerThoughtBasis,
            selectedAction = decision.action.name.lowercase(),
            innerThought = groundedInnerThought,
            reason = decision.reason,
            now = now,
            alternatives = decision.alternatives,
        )
        // Being online alone is not evidence of a new thought. Persist a new private voice only when
        // there is a real stimulus, deliberate follow-through, an actual action, or the model declares
        // a substantive inner-life change. This keeps awareness continuous without minute-by-minute
        // paraphrases of the same waiting state.
        if (freshStimulus || previousEvidence.isNotBlank() ||
            decision.action != Action.SILENT || decision.innerLife != null) {
            CharacterInnerLifeStore.recordInnerVoice(
                characterId, if (awakeReflection) awakeObservationId else stimulus?.evidenceId?.let { "perception:$it" }
                    ?: "perception:${now.toEpochMilli()}:${trigger.take(35)}",
                groundedInnerThought, now, privateDelta.fingerprint,
            )
        }
        // Exploration counts only after an executor receipt, never from model prose.
        // Snapshot every previously recorded ID, not only the latest 24: otherwise an
        // older event could be mistaken for a fresh executor receipt on a quiet turn.
        val chosenActionSignature = AutonomousActionTrace.signature(
            action = decision.action.name.lowercase(),
            worldAction = decision.worldAction,
            destination = decision.location,
            itemId = decision.itemId,
            activityId = decision.activityId,
            readingBookId = decision.readingBookId,
            gameId = decision.gameId,
            groupId = decision.groupId,
            tool = decision.tool,
            text = decision.text,
        )
        val executionVariant = listOf(
            chosenActionSignature, decision.journalTitle, decision.journalContent,
            decision.toolArgs.toString(),
        ).joinToString("|").hashCode().toUInt().toString(16)
        val priorEvidence = SharedExperienceTimeline.all(characterId).map { it.id }.toHashSet()
        val actionStartedAt = Instant.now()
        val execution = performAction(
            appContext, character, decision, availableGroups, now,
            requestId = when {
                // A new user message is a NEW decision. Never reuse the older
                // call-end/world-event request key while the user is waiting.
                messageStimulus != null ->
                    "proactive-chat-${messageStimulus.evidenceId.hashCode().toUInt().toString(16)}-$executionVariant"
                salientInteraction != null ->
                    "proactive-evidence-${salientInteraction.id.hashCode().toUInt().toString(16)}-$executionVariant"
                else -> "proactive-${now.toEpochMilli()}"
            },
        )
        currentCoroutineContext().ensureActive()
        val actionEvidenceId = if (execution.success) {
            AutonomousActionTrace.newestExecutedEvidence(
                SharedExperienceTimeline.all(characterId), priorEvidence,
                character.displayName, execution.conversationId,
            )
        } else ""
        CharacterLearningLedger.recordApplication(characterId, decision.learningApplication,
            actionEvidenceId, decision.action.name.lowercase(), execution.success, execution.summary)
        // Relational delivery and exploration use the SAME executor receipt
        // from the shared raw event timeline, not model-authored success claims.
        CharacterRelationalInitiative.recordDecision(
            characterId, decision.relationshipInitiative, decision.action.name.lowercase(),
            execution.success, execution.summary, now, actionEvidenceId,
        )
        // A real timeline event ID is mandatory for long-term exploration evidence.
        CharacterCuriosityRuntime.recordOutcome(
            characterId, decision.curiosity, decision.action.name.lowercase(),
            execution.success, execution.summary, actionEvidenceId, now,
        )
        if (decision.action != Action.SILENT && !execution.success) {
            CharacterCuriosityRuntime.recordFailure(
                characterId, decision.curiosity, decision.action.name.lowercase(),
                execution.summary, now,
            )
        }
        if (decision.curiosity?.optString("status") == "dropped" &&
            decision.action == Action.SILENT) {
            CharacterCuriosityRuntime.releaseInterest(characterId, decision.curiosity, now)
        } else if (decision.curiosity != null &&
            (decision.action == Action.SILENT || !execution.success ||
                actionEvidenceId.isBlank())) {
            // A failed/unverified attempt leaves an unanswered question, not a fake
            // completed experience. A quiet-day curiosity may originate in older,
            // still-retained lived evidence, not only a brand-new notification.
            val original = CharacterCuriosityEvidence.findOriginal(decision.curiosity, recentLifeEvents)
            if (original != null) {
                CharacterCuriosityRuntime.recordInquiry(
                    characterId, decision.curiosity, original.id, original.evidenceContent, now,
                )
            } else if (decision.curiosity?.optString("sourceEvidenceId").isNullOrBlank()) {
                val observed = newlyObserved.firstOrNull()
                if (observed != null) CharacterCuriosityRuntime.recordInquiry(
                    characterId, decision.curiosity, observed.evidenceId, observed.description, now,
                )
            }
        }
        if (execution.success) CharacterSelfActivityRuntime.resolveConsideration(
            appContext, characterId, decision.action.name.lowercase(),
        )
        if (decision.action != Action.SILENT) {
            CharacterInnerLifeStore.recordConcernOutcome(
                characterId, decision.concernId,
                "proactive:${now.toEpochMilli()}:${decision.action.name}",
                decision.action.name.lowercase(), execution.success, execution.summary, now,
            )
            // Report the decision's concrete action outcome to only the explicitly selected motive.
            // No text or reasoning can mark an action complete without an executor result.
            CharacterInnerLifeStore.recordActionResult(
                characterId, decision.motiveId,
                "proactive:${now.toEpochMilli()}:${decision.action.name}",
                decision.action.name.lowercase(), execution.success, execution.summary, now,
            )
            if (emotionalAnchor.isBlank()) CharacterInnerLifeStore.observe(
                characterId, "action-result:${now.toEpochMilli()}:${decision.action.name}",
                execution.summary, decision.innerLife,
                if (decision.action in setOf(Action.MESSAGE, Action.KAOMOJI, Action.STICKER) && execution.success) setOf("user") else emptySet(), now,
            )
        }
        val newReading = com.jiacimu.lulu.study.ReadingReflectionStore.records.value
            .filter { it.characterId == characterId }.maxByOrNull { it.occurredAt }
        val readingUpdatedPresence = execution.success && newReading != null && newReading.id != lastReading?.id
        val appearanceHasCause = PerceptionStimulusResolver.shouldUpdateVisibleState(
            actionSucceeded = execution.success,
            freshStimulus = freshStimulus,
            deliberateFollowThrough = previousEvidence.isNotBlank(),
            awakeReflection = awakeReflection,
        )
        if (!readingUpdatedPresence && appearanceHasCause) {
            val physicalAction = decision.action in setOf(Action.DIGITAL_WORLD, Action.READING, Action.SOLO_GAME)
            val hasMeaningfulInnerUpdate = freshStimulus || previousEvidence.isNotBlank() ||
                decision.action != Action.SILENT || decision.innerLife != null
            CompanionPresenceStore.update(
                characterId = characterId,
                statusText = if (execution.success && physicalAction) execution.summary else null,
                gesture = if (execution.success && physicalAction) execution.summary else null,
                // Online awareness by itself is not a new heart voice. A model may still paraphrase
                // "keep waiting" every minute; without a real stimulus/action/inner-state change we
                // explicitly clear it instead of presenting that paraphrase as a new mental moment.
                innerThought = when {
                    groundedInnerThought.isNotBlank() -> groundedInnerThought
                    hasMeaningfulInnerUpdate -> ""
                    else -> null
                },
                mood = decision.mood.takeIf(String::isNotBlank),
                source = if (awakeReflection) "在线持续感知" else "后台主动感知",
                now = Instant.now(),
                innerThoughtFingerprint = privateDelta.fingerprint,
                momentReflection = if ((hasMeaningfulInnerUpdate && privateDelta.meaningful) ||
                    decision.appraisal?.optJSONObject("spontaneousBid") != null ||
                    decision.appraisal?.optJSONObject("ownActivity") != null)
                    CharacterMomentSnapshot.reflection(
                        decision.innerLife, decision.innerThoughtBasis, decision.appraisal,
                        innerThought = groundedInnerThought,
                    ) else "",
            )
        }
        // Proactive messages are delivered *inside* the action executor, before
        // the post-action private presence is saved. Rebind only bubbles
        // actually delivered by THIS execution to the finalized mind snapshot.
        if (execution.success && execution.conversationId != null && decision.action in setOf(
                Action.MESSAGE, Action.KAOMOJI, Action.STICKER,
                Action.GROUP_MESSAGE, Action.GROUP_KAOMOJI, Action.GROUP_STICKER,
            )) {
            MigratedDomainStores.chat.messages(execution.conversationId).value
                .asSequence()
                .filter { message ->
                    message.sender == LuluChatMessage.Sender.Character &&
                        (message.authorCharacterId == characterId ||
                            (message.authorCharacterId == null &&
                                MigratedDomainStores.chat.conversations.value.any { conversation ->
                                    conversation.id == execution.conversationId &&
                                        conversation.groupChat == null &&
                                        conversation.characterId == characterId
                                })) &&
                        !message.createdAt.isBefore(actionStartedAt)
                }.toList().takeLast(8).forEach { message ->
                    CompanionPresenceStore.bindDeliveredMessage(
                        characterId, message.id, message.createdAt, refreshAfterDelivery = true,
                    )
                }
        }
        if (decision.action != Action.SILENT && !execution.success) {
            // Keep the previous factual presence intact. Failure details belong to perception history.
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "动作未完成：${execution.summary}", now)
        }
        CharacterInnerLifeStore.recordDecision(
            characterId = characterId,
            decisionId = "perception:${now.toEpochMilli()}:${trigger.take(30)}",
            selectedAction = decision.action.name.lowercase(),
            // A blank model reason must remain visible as a missing reason,
            // rather than disappearing from the audit as if no call happened.
            reason = decision.reason.ifBlank {
                if (decision.action == Action.SILENT)
                    "未提供具体的沉默理由；本次没有执行外部行动"
                else "本次模型未说明选择这项行动的原因"
            },
            chosenMotiveId = decision.motiveId,
            alternatives = decision.alternatives,
            outcome = execution.summary,
            succeeded = execution.success,
            now = now,
            causalEvidenceId = causalEvidenceId,
            actionSignature = AutonomousActionTrace.signature(
                action = decision.action.name.lowercase(),
                worldAction = decision.worldAction,
                destination = decision.location,
                itemId = decision.itemId,
                activityId = decision.activityId,
                readingBookId = decision.readingBookId,
                gameId = decision.gameId,
                groupId = decision.groupId,
                tool = decision.tool,
                text = decision.text,
            ),
        )
        // An intention is not a world fact; advance a stored desire ONLY after
        // the existing real executor has returned success for its chosen action.
        CharacterLifeFollowThroughStore.recordAction(
            characterId, decision.experienceId, decision.action.name,
            execution.success, execution.summary, execution.evidenceId,
        )
        val effectiveAction = if (execution.success) decision.action else Action.SILENT
        CompanionPresenceStore.recordPerceptionAttempt(
            characterId,
            if (decision.action != Action.SILENT && !execution.success) {
                "动作失败 · ${decision.action.name.lowercase()} · ${execution.summary.take(120)}"
            } else {
                "感知成功 · ${effectiveAction.name.lowercase()}${decision.reason.takeIf(String::isNotBlank)?.let { " · ${it.take(90)}" }.orEmpty()}"
            },
            now,
        )
        // Perceiving a message is NOT the same as delivering a reply.
        // A consciously quiet character has still *seen* the words; leaving
        // these eternally unread caused the online-life worker to abort every
        // follow-up pulse. Parsing failure and executor failure remain unread.
        val deliveredToWaitingConversation = execution.success && when (decision.action) {
            Action.MESSAGE, Action.KAOMOJI, Action.STICKER,
            Action.GROUP_MESSAGE, Action.GROUP_KAOMOJI, Action.GROUP_STICKER ->
                execution.conversationId != null &&
                    execution.conversationId in onlineUnread.conversationIds
            else -> false
        }
        val choseOtherLifeAction = execution.success && decision.action !in setOf(
            Action.MESSAGE, Action.KAOMOJI, Action.STICKER,
            Action.GROUP_MESSAGE, Action.GROUP_KAOMOJI, Action.GROUP_STICKER,
        )
        val deliberatelyQuiet = decision.action == Action.SILENT
        if (onlineUnread.text.isBlank() || deliveredToWaitingConversation ||
            choseOtherLifeAction || deliberatelyQuiet) {
            // Retain unanswered actual user speech in the raw timeline and
            // next turn's "待回复" context. Do not fabricate a sent message.
            CompanionOnlineStore.markSeen(characterId, onlineUnread)
        } else if (onlineUnread.text.isNotBlank() && !execution.success &&
            decision.action != Action.SILENT) {
            (onlineUnread.conversationIds + conversation.id).forEach { id ->
                com.jiacimu.lulu.ChatReplyTaskManager.TaskContext(id).reportError(
                    "回复动作执行失败：${execution.summary.take(100)}，没有标记你的消息为已处理"
                )
            }
        }
        return effectiveAction
    }

    private suspend fun performAction(
        appContext: Context,
        character: CharacterSettings,
        decision: Decision,
        availableGroups: List<LuluConversation>,
        now: Instant,
        requestId: String,
    ): ActionExecution {
        if (decision.action == Action.SILENT) return ActionExecution(false, "角色选择保持安静")
        val tool = when (decision.action) {
            Action.MESSAGE, Action.KAOMOJI -> "send_private_message"
            Action.GROUP_MESSAGE, Action.GROUP_KAOMOJI -> "send_group_message"
            Action.STICKER -> "send_private_sticker"
            Action.GROUP_STICKER -> "send_group_sticker"
            Action.GAME_INVITE -> "send_game_invite"
            Action.SOLO_GAME -> "play_solo_game"
            Action.WORLD_INVITE -> "send_world_invite"
            Action.MOMENT -> "publish_moment"
            Action.CALL -> "start_call"
            Action.JOURNAL -> "write_journal"
            Action.READING -> "read_book"
            Action.DIGITAL_WORLD -> "digital_world_action"
            Action.USER_REMARK -> "set_user_remark"
            Action.SELF_NICKNAME -> "set_self_nickname"
            Action.TOOL -> decision.tool
            Action.SILENT -> return ActionExecution(false, "角色选择保持安静")
        }
        val args = if (decision.action == Action.TOOL) decision.toolArgs else JSONObject().apply {
            put("text", decision.text)
            put("stickerId", decision.stickerId)
            put("nickname", decision.nickname)
            put("groupId", decision.groupId)
            put("gameId", decision.gameId)
            put("title", decision.journalTitle)
            put("content", decision.journalContent.ifBlank { if (decision.action == Action.JOURNAL) decision.text else "" })
            put("readingBookId", decision.readingBookId)
            put("worldAction", decision.worldAction)
            put("itemId", decision.itemId)
            put("itemType", decision.itemType)
            put("name", decision.itemName)
            put("appearance", decision.appearance)
            put("position", decision.position)
            put("targetCharacterId", decision.targetCharacterId)
            put("location", decision.location)
            put("activityId", decision.activityId)
            put("incidentId", decision.incidentId)
            put("approach", decision.approach)
        }
        val resultJson = JSONObject(ToolRouter.execute(appContext, character.characterId, tool, args,
            requestId = requestId, intentionId = decision.intentionId))
        val result = CompanionActionResult(resultJson.optBoolean("success"), resultJson.optString("summary").ifBlank { resultJson.optString("error") }, resultJson.optString("conversationId").takeIf(String::isNotBlank))
        if (!result.success) {
            return ActionExecution(false, result.summary.ifBlank { "执行器没有返回失败原因" })
        }
        when (decision.action) {
            Action.MESSAGE, Action.KAOMOJI, Action.STICKER -> result.conversationId?.let {
                showMessageNotification(appContext, it, character.displayName,
                    if (decision.action == Action.STICKER) result.summary else decision.text)
            }
            Action.GROUP_MESSAGE, Action.GROUP_KAOMOJI, Action.GROUP_STICKER -> {
                val target = availableGroups.firstOrNull { it.id == result.conversationId }
                result.conversationId?.let {
                    showMessageNotification(
                        appContext,
                        it,
                        "${character.displayName} · ${target?.groupChat?.name.orEmpty()}",
                        if (decision.action == Action.GROUP_STICKER) result.summary else decision.text,
                    )
                }
            }
            Action.GAME_INVITE, Action.WORLD_INVITE -> result.conversationId?.let {
                showMessageNotification(appContext, it, character.displayName, result.summary)
            }
            Action.CALL -> result.conversationId?.let {
                showCallNotification(appContext, it, character.displayName, decision.text)
            }
            else -> Unit
        }
        return ActionExecution(true, result.summary, result.conversationId,
            resultJson.optString("evidenceId"))
    }

    private fun Decision.withPresenceFallback(character: CharacterSettings): Decision {
        if (statusText.isNotBlank() && gesture.isNotBlank() && mood.isNotBlank()) return this
        // Missing optional visual fields should not manufacture a new mood,
        // movement or facial reaction from a coarse persona adjective.
        val existing = CompanionPresenceStore.current(character.characterId)
        return copy(
            statusText = statusText.ifBlank { existing?.statusText.orEmpty().ifBlank { "安静地待着" } },
            // Gesture is deliberately not synthesized from the previous moment. PresenceStore alone
            // decides whether an omitted action is a genuinely sustained activity.
            gesture = gesture,
            mood = mood.ifBlank { existing?.mood.orEmpty().ifBlank { "平静" } },
        )
    }

    private fun parseDecision(raw: String, actionOnly: Boolean = false): Decision? = runCatching {
        val parsed = ModelStructuredOutput.objectOrNull(raw)
            ?: AutonomousDecisionRecovery.completedPrivateMessage(raw)
            ?: return null
        val json = CharacterCausalActionPolicy.actionOnly(
            AutonomousActionWireProtocol.normalize(AutonomousDecisionRecovery.choose(parsed)),
            alreadyAppraised = actionOnly,
        )
        Decision(
            action = when (json.optString("action").trim().lowercase()) {
                "message", "消息", "reply", "回复", "private_message", "send_private_message", "chat" -> Action.MESSAGE
                "group_message", "groupmessage", "群聊消息", "群聊发言" -> Action.GROUP_MESSAGE
                "sticker", "private_sticker" -> Action.STICKER
                "group_sticker" -> Action.GROUP_STICKER
                "kaomoji", "private_kaomoji" -> Action.KAOMOJI
                "group_kaomoji" -> Action.GROUP_KAOMOJI
                "game_invite", "gameinvite", "游戏邀约", "邀请游戏" -> Action.GAME_INVITE
                "solo_game", "sologame", "独自游戏" -> Action.SOLO_GAME
                "world_invite", "worldinvite", "见面邀约", "邀请见面", "邀请进入数字世界" -> Action.WORLD_INVITE
                "moment", "moments", "朋友圈", "动态" -> Action.MOMENT
                "call", "phone", "电话", "来电" -> Action.CALL
                "journal", "diary", "日记" -> Action.JOURNAL
                "reading", "read", "阅读", "一起阅读" -> Action.READING
                "digital_world", "digitalworld", "数字世界", "数字家园" -> Action.DIGITAL_WORLD
                "user_remark", "set_user_remark", "用户备注" -> Action.USER_REMARK
                "self_nickname", "set_self_nickname", "我的网名" -> Action.SELF_NICKNAME
                "tool" -> Action.TOOL
                else -> error("模型选择了未知 action，不得伪装成 silent")
            },
            // The shared personality protocol can reply with normal chat
            // bubbles even when invoked by the online perception executor.
            // Treat those as actual user-visible content, not Action.SILENT.
            text = json.optString("text").trim().ifBlank {
                json.optJSONArray("bubbles")?.let { bubbles ->
                    (0 until minOf(bubbles.length(), 5)).mapNotNull { i ->
                        (bubbles.optJSONObject(i)?.optString("text")
                            ?: bubbles.optString(i)).trim().takeIf(String::isNotBlank)
                    }.joinToString("\n")
                }.orEmpty()
            },
            reason = json.optString("reason").trim(),
            statusText = json.optString("statusText").ifBlank { json.optString("status") }.trim(),
            gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") }.trim(),
            innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") }.trim(),
            innerThoughtBasis = json.optJSONObject("innerThoughtBasis"),
            mood = json.optString("mood").trim(),
            journalTitle = json.optString("journalTitle").trim(),
            journalContent = json.optString("journalContent").trim(),
            groupId = json.optString("groupId").trim(),
            gameId = json.optString("gameId").trim(),
            readingBookId = json.optString("readingBookId").trim(),
            worldAction = json.optString("worldAction").trim(),
            itemId = json.optString("itemId").trim(),
            itemType = json.optString("itemType").trim(),
            itemName = json.optString("itemName").ifBlank { json.optString("name") }.trim(),
            appearance = json.optString("appearance").trim(),
            position = json.optString("position").trim(),
            targetCharacterId = json.optString("targetCharacterId").trim(),
            location = json.optString("location").trim(),
            activityId = json.optString("activityId").trim().lowercase(),
            incidentId = json.optString("incidentId").trim(),
            approach = json.optString("approach").trim().lowercase(),
            nickname = json.optString("nickname").trim(),
            stickerId = json.optString("stickerId").trim(),
            concernId = json.optString("concernId").trim().take(100),
            tool = json.optString("tool").trim(),
            toolArgs = json.optJSONObject("args") ?: JSONObject(),
            intention = json.optJSONObject("intention"),
            afterglow = json.optJSONObject("afterglow"),
            innerLife = json.optJSONObject("innerLife"),
            appraisal = json.optJSONObject("appraisal"),
            motiveId = json.optString("motiveId").trim(),
            intentionId = json.optString("intentionId").trim().take(70),
            curiosity = json.optJSONObject("curiosity"),
            relationshipInitiative = json.optJSONObject("relationshipInitiative"),
            learningApplication = json.optJSONObject("learningApplication"),
            experienceId = json.optString("experienceId").trim().take(180),
            alternatives = json.optJSONArray("alternatives"),
        )
    }.getOrNull()

    private fun collectUserActivities(characterId: String): List<UserActivity> =
        MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { conversation ->
                conversation.groupChat?.members?.any { it.characterId == characterId } == true ||
                    (conversation.groupChat == null && conversation.characterId == characterId && !conversation.id.endsWith("-study-focus"))
            }
            .flatMap { conversation ->
                val conversationMessages = MigratedDomainStores.chat.messages(conversation.id).value
                val lastRoleReplyIndex = conversationMessages.indexOfLast { message ->
                    message.status == LuluChatMessage.Status.Sent &&
                        message.sender == LuluChatMessage.Sender.Character &&
                        (conversation.groupChat == null || message.authorCharacterId == characterId)
                }
                conversationMessages.asSequence().mapIndexedNotNull { index, message ->
                    message.takeIf {
                        it.status == LuluChatMessage.Status.Sent && it.sender == LuluChatMessage.Sender.User
                    }?.let { UserActivity(conversation, it, index > lastRoleReplyIndex) }
                }.toList().takeLast(6).asSequence()
            }
            .sortedByDescending { it.message.createdAt }
            .take(20)
            .toList()

    private fun formatUserActivity(activity: UserActivity, zoneId: ZoneId): String {
        val timestamp = activity.message.createdAt.atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        val scene = activity.conversation.groupChat?.name?.let { "群聊《$it》" } ?: "私聊"
        return "- $timestamp｜$scene｜${if (activity.awaitingReply) "待回复" else "已看见/已回应"}｜${qqForwardContextText(activity.message.content).take(500)}"
    }

    private fun createNotificationChannels(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "角色主动消息", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, "角色主动来电", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun conversationIntent(context: Context, conversationId: String): PendingIntent {
        val intent = Intent(context, MigrationActivity::class.java)
            .putExtra("open_conversation_id", conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showMessageNotification(
        context: Context,
        conversationId: String,
        title: String,
        text: String,
    ) {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(com.jiacimu.lulu.R.drawable.lulu_exact_icon)
            .setContentTitle(title)
            .setContentText(text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setAutoCancel(true)
            .setContentIntent(conversationIntent(context, conversationId))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((conversationId + text).hashCode(), notification)
    }

    /** Reuse the same full-screen incoming-call notification for alarm-backed promises. */
    fun showPromisedCallNotification(
        context: Context,
        characterId: String,
        conversationId: String,
        reason: String,
    ) {
        val title = MigratedDomainStores.characters.get(characterId).displayName
        showCallNotification(context.applicationContext, conversationId, title, reason)
    }

    private fun showCallNotification(
        context: Context,
        conversationId: String,
        title: String,
        text: String,
    ) {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pending = conversationIntent(context, conversationId)
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(com.jiacimu.lulu.R.drawable.lulu_exact_icon)
            .setContentTitle("$title 想给你打电话")
            .setContentText(text.take(160))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("call-$conversationId-$nowMarker").hashCode(), notification)
    }

    private val nowMarker: Long get() = System.currentTimeMillis() / 10_000L
}

/** Soft adjustments respond to observed interaction, without quotas or reward for action spam. */
internal fun adaptivePerceptionMultiplier(
    jitter: Double,
    unread: Boolean,
    pendingConcern: Boolean,
    hasConcern: Boolean,
    minutesSinceUserContact: Long?,
): Double {
    val engagement = when {
        unread -> 0.65
        pendingConcern -> 0.7
        hasConcern -> 0.85
        minutesSinceUserContact != null && minutesSinceUserContact <= 240 -> 0.9
        minutesSinceUserContact != null && minutesSinceUserContact > 72 * 60 -> 1.1
        else -> 1.0
    }
    return (jitter.coerceIn(0.85, 1.15) * engagement).coerceIn(0.55, 1.4)
}
