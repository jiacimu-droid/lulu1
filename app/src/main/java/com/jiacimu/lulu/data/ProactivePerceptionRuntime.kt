package com.jiacimu.lulu.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.MigrationActivity
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.health.HealthRolePerception
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.ReadingBackgroundBridge
import com.jiacimu.lulu.study.roleStudyContext
import com.jiacimu.lulu.system.LuluAccessibilityService
import com.jiacimu.lulu.system.LuluLocationProvider
import com.jiacimu.lulu.system.LuluNotificationListenerService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Low-frequency per-character proactive perception.
 *
 * Model calls are driven only by a character's own interval (plus pending concern/promise state)
 * or an explicit user manual check. Screen and notification changes are data sources, never wakeups.
 */
object ProactivePerceptionRuntime {
    private const val PREFS_NAME = "lulu_proactive_runtime_v2"
    private const val MESSAGE_CHANNEL_ID = "lulu_proactive_messages"
    private const val CALL_CHANNEL_ID = "lulu_proactive_calls"
    private const val ACTION_HISTORY_SIZE = 6
    private val cycleMutex = Mutex()

    private enum class Action { MESSAGE, GROUP_MESSAGE, GAME_INVITE, MOMENT, CALL, JOURNAL, READING, SILENT }

    private data class Decision(
        val action: Action,
        val text: String,
        val reason: String,
        val statusText: String,
        val gesture: String,
        val innerThought: String,
        val mood: String,
        val journalTitle: String,
        val journalContent: String,
        val groupId: String,
        val gameId: String,
        val readingBookId: String,
    )

    fun initialize(context: Context) {
        ProactivePerceptionPolicyStore.initialize(context.applicationContext)
        createNotificationChannels(context.applicationContext)
    }

    fun markConcernPromisePending(context: Context, characterId: String) {
        if (characterId.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("pending_concern_promise_$characterId", true).apply()
        ProactivePerceptionScheduler.scheduleNextDue(context.applicationContext)
    }

    fun nextDueAt(context: Context, now: Instant = Instant.now()): Instant? {
        initialize(context)
        val conversations = latestPrivateConversations()
        if (conversations.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return conversations.mapNotNull { conversation ->
            val characterId = conversation.characterId.ifBlank { "lulu" }
            val policy = ProactivePerceptionPolicyStore.get(characterId)
            if (!policy.enabled) return@mapNotNull null
            dueAtFor(context, conversation, policy, prefs, now)
        }.minOrNull()
    }

    suspend fun runDueCycle(
        context: Context,
        trigger: String,
        targetCharacterId: String? = null,
        force: Boolean = false,
        now: Instant = Instant.now(),
    ): Int = cycleMutex.withLock {
        initialize(context)
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val targets = latestPrivateConversations().filter { conversation ->
            targetCharacterId == null || conversation.characterId == targetCharacterId
        }
        var evaluated = 0
        for (conversation in targets) {
            val characterId = conversation.characterId.ifBlank { "lulu" }
            val policy = ProactivePerceptionPolicyStore.get(characterId)
            if (!policy.enabled && !force) continue
            if (!force) {
                val due = dueAtFor(appContext, conversation, policy, prefs, now)
                if (due.isAfter(now.plusSeconds(15))) continue
                if (isQuietNow(policy, now.atZone(ZoneId.systemDefault()).toLocalTime())) continue
            }

            val pendingConcern = prefs.getBoolean("pending_concern_promise_$characterId", false)
            val effectiveTrigger = when {
                trigger.contains("挂心") || trigger.contains("承诺") -> trigger
                pendingConcern -> "挂心/承诺待回看"
                else -> trigger
            }
            prefs.edit().putLong("last_evaluation_$characterId", now.toEpochMilli()).apply()
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知启动 · $effectiveTrigger", now)
            val result = runCatching {
                evaluateCharacter(appContext, conversation, effectiveTrigger, now)
            }
            result.onSuccess { action ->
                evaluated += 1
                val silentKey = "silent_count_$characterId"
                val nextSilent = if (action == Action.SILENT) prefs.getInt(silentKey, 0) + 1 else 0
                val actionKey = "action_history_$characterId"
                val actionHistory = prefs.getString(actionKey, "").orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .plus(action.name)
                    .takeLast(ACTION_HISTORY_SIZE)
                prefs.edit()
                    .putInt(silentKey, nextSilent.coerceAtMost(4))
                    .putString(actionKey, actionHistory.joinToString(","))
                    .putBoolean("pending_concern_promise_$characterId", false)
                    .apply()
            }.onFailure { error ->
                CompanionPresenceStore.recordPerceptionAttempt(
                    characterId,
                    "感知失败 · ${error.message.orEmpty().ifBlank { error::class.java.simpleName }.take(120)}",
                    now,
                )
            }
        }
        evaluated
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
            .maxByOrNull(LuluChatMessage::createdAt)
            ?.createdAt
            ?: conversation.updatedAt
        val lastEvaluation = prefs.getLong("last_evaluation_$characterId", 0L)
            .takeIf { it > 0L }
            ?.let(Instant::ofEpochMilli)
        val anchor = listOfNotNull(lastChat, lastEvaluation).maxOrNull() ?: now
        val pendingConcern = prefs.getBoolean("pending_concern_promise_$characterId", false)
        val multiplier = when {
            pendingConcern -> 1.0
            !policy.adaptiveFrequency -> 1.0
            lastEvaluation == null || lastChat.isAfter(lastEvaluation) -> 1.0
            else -> when (prefs.getInt("silent_count_$characterId", 0)) {
                0 -> 1.0
                1 -> 1.5
                else -> 2.0
            }
        }
        val timingVariation = stableTimingVariation(characterId, anchor)
        val due = anchor.plus(Duration.ofMinutes(policy.intervalMinutes(multiplier * timingVariation)))
        return deferPastQuietHours(due, policy)
    }

    private fun stableTimingVariation(characterId: String, anchor: Instant): Double {
        val unsignedHash = "$characterId:${anchor.toEpochMilli()}".hashCode().toLong() and 0xffff_ffffL
        val fraction = unsignedHash.toDouble() / 0xffff_ffffL.toDouble()
        return 0.85 + fraction * 0.30
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
        val endHour = end / 60
        val endMinute = end % 60
        val endDate = when {
            start < end -> local.toLocalDate()
            minute >= start -> local.toLocalDate().plusDays(1)
            else -> local.toLocalDate()
        }
        return endDate.atTime(endHour, endMinute).atZone(zone).toInstant()
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
        MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { it.parentConversationId == null && it.groupChat == null && !it.id.endsWith("-study-focus") }
            .groupBy(LuluConversation::characterId)
            .mapNotNull { (_, values) -> values.maxByOrNull(LuluConversation::updatedAt) }

    private suspend fun evaluateCharacter(
        appContext: Context,
        conversation: LuluConversation,
        trigger: String,
        now: Instant,
    ): Action {
        val characterId = conversation.characterId.ifBlank { "lulu" }
        val character = MigratedDomainStores.characters.get(characterId)
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val zoneId = ZoneId.systemDefault()
        val localNow = now.atZone(zoneId)
        val localTimeText = localNow.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm:ss", Locale.SIMPLIFIED_CHINESE))
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recentAutonomousActions = prefs.getString("action_history_$characterId", "").orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)

        val library = LuluAiServices.connectionStore.library.value
        val perceptionArchiveId = library.archiveIdFor(ModelUsage.Chat)
        if (perceptionArchiveId == null) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知暂停 · 没有可用的聊天模型", now)
            return Action.SILENT
        }
        val connection = LuluAiServices.connectionStore.resolveConnection(perceptionArchiveId)
        val availableGroups = MigratedDomainStores.chat.conversations.value.filter { candidate ->
            candidate.groupChat?.members?.any { it.characterId == characterId } == true
        }
        val lastCharacterIndex = messages.indexOfLast { message ->
            message.sender == LuluChatMessage.Sender.Character && message.status == LuluChatMessage.Status.Sent
        }
        val pendingUserMessages = messages.drop(lastCharacterIndex + 1).filter { message ->
            message.sender == LuluChatMessage.Sender.User && message.status == LuluChatMessage.Status.Sent
        }
        val pendingUserContext = pendingUserMessages.joinToString("\n") { message ->
            val timestamp = message.createdAt.atZone(zoneId).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            "- $timestamp ${message.content.take(500)}"
        }
        val recent = messages.takeLast(20).joinToString("\n") { message ->
            val speaker = when (message.sender) {
                LuluChatMessage.Sender.User -> "用户"
                LuluChatMessage.Sender.Character -> character.displayName
                LuluChatMessage.Sender.System -> "系统事件"
            }
            val timestamp = message.createdAt.atZone(zoneId).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            "$timestamp $speaker：${message.content.take(500)}"
        }
        val lexicon = LuluRepositories.lexicon.snapshot(characterId)
        val concerns = lexicon.filter { it.section == LexiconSection.Concern }.take(8)
            .joinToString("\n") { "- ${it.title}：${it.content}" }
        val commitments = lexicon.filter { it.section == LexiconSection.Promise }.take(10)
            .joinToString("\n") { "- ${it.title}：${it.content}" }
        val memories = RelevantMemoryRecall.recall(
            characterId = characterId,
            query = listOf(recent.takeLast(3_000), concerns, commitments).joinToString("\n"),
            limit = 10,
        )
        val memoryContext = RelevantMemoryRecall.formatForPrompt(memories)
        val previousPresence = CompanionPresenceStore.current(characterId)
        val deviceContext = buildRealWorldContext(appContext, characterId, now)
        val readingBooks = ReadingBackgroundBridge.books(appContext).take(12)

        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("【角色人设】\n${character.persona.ifBlank { "按角色当前设定自然行动。" }}")
                appendLine("\n【用户现实设备与用户状态感知层】")
                appendLine("重要归属：下面的电量、前台应用、通知、位置、健康/手环和学习信息都属于用户本人或用户正在使用的现实设备，不属于角色自己的手机或身体。")
                appendLine("触发来源：$trigger")
                appendLine("用户设备本地时间：$localTimeText（时区 ${zoneId.id}）")
                appendLine(deviceContext)
                appendLine("允许主动来电：${if (character.contactPolicy.proactiveCallsEnabled) "是" else "否"}")
                if (recentAutonomousActions.isNotEmpty()) {
                    appendLine("最近自主选择（旧→新）：${recentAutonomousActions.joinToString(" → ")}")
                }
                if (readingBooks.isNotEmpty()) {
                    appendLine("可独自阅读的内容：")
                    readingBooks.forEach { book -> appendLine("- readingBookId=${book.id}；《${book.title}》；${book.source}") }
                }
                if (availableGroups.isNotEmpty()) {
                    appendLine("所在群聊：")
                    availableGroups.forEach { group ->
                        appendLine("- groupId=${group.id}；${group.groupChat?.name}；最近=${group.lastMessage.take(120)}")
                    }
                }
                appendLine("\n【长期上下文层】")
                previousPresence?.let {
                    appendLine("上一刻：${it.statusText}；${it.gesture}；${it.mood}；心声=${it.innerThought}")
                }
                if (concerns.isNotBlank()) appendLine("【挂心】\n$concerns")
                if (commitments.isNotBlank()) appendLine("【承诺与监督】\n$commitments")
                if (memoryContext.isNotBlank()) appendLine(memoryContext)
                if (pendingUserContext.isNotBlank()) {
                    appendLine("【尚未回复的消息】")
                    appendLine("以下消息都是用户在你上一次真实聊天回复之后新发来的，当前还没有收到你的回复：")
                    appendLine(pendingUserContext)
                }
                if (recent.isNotBlank()) appendLine("【最近聊天与生活事件】\n$recent")
            },
            instruction = """
                你正在让当前角色按“真实世界感知 → 长期上下文 → 此刻判断 → 自主选择”形成这一刻。不要写系统报告。
                只返回 JSON：
                {"action":"message|group_message|game_invite|moment|call|journal|reading|silent","text":"实际发送/发布内容","groupId":"群ID","gameId":"游戏ID","readingBookId":"阅读内容ID","reason":"为什么这样做","statusText":"角色此刻在做什么","gesture":"动作神态","innerThought":"第一人称没说出口的心声","mood":"简短心情","journalTitle":"日记标题","journalContent":"日记正文"}

                规则：
                1. 每次感知都必须形成 statusText、gesture、mood；innerThought 可以为空。silent 不是失败，而是角色决定只过自己的这一刻。此刻是一份完整生活状态，不只是动作：statusText写正在做什么，gesture写动作神态，mood写心情，innerThought写愿意保存在角色内部但没有说出口的第一人称心声。
                2. 【归属绝不能混淆】感知层里的手机电量、充电、前台应用/屏幕活动、通知、位置、健康/手环和学习状态默认全部是用户及用户现实设备的数据，不是角色自己的。看到“前台应用=抖音/短视频”只能理解为用户可能正在刷视频，不能写成“我还在刷视频”；看到“电量=20%”不能写成“我手机只剩20%”；通知也不是角色自己收到的。除非另有明确的角色侧设备数据，否则禁止第一人称认领这些信号。
                3. 手机信息只是观察用户现实状态的线索，不能被夸大推断；健康数据带同步时间时，要意识到它可能在下一次手环导出前保持不变，也不能据此虚构用户更多未提供的身体或环境事实。
                4. 不设每日主动次数、消息冷却、电话冷却、朋友圈冷却或日记冷却。是否行动由人设、关系、上下文和此刻意愿决定，不要因为“能做”就每次都做。
                5. message 是主动私聊；group_message 只能使用真实 groupId；game_invite 可用 gameId：roleplay、turtle_soup、yacht_dice、gomoku、memory_match。
                6. call 只有“允许主动来电=是”时才能选择；否则必须换其他动作或 silent。
                7. reading 只能使用真实 readingBookId。角色会真正读取对应正文并产生自己的感想，不要假装读了列表之外的书。
                8. journal 是角色第一人称私人日记；moment 是角色愿意公开的朋友圈；game_invite 是角色真的想和用户一起玩。它们都是角色自己的生活选择，不要把所有动作写成对用户的服务或监督。
                9. 学习状态只在当前角色就是学习 App 的陪同角色时提供；没提供就代表这个角色没有权限知道，禁止猜。
                10. 角色语气、主动程度、动作、心声必须服从人设。认真看“最近自主选择”和最近聊天里的系统生活事件：不要机械轮班打卡，但也不要把 silent/只更新此刻当成永久默认。若最近连续多次 SILENT 或连续重复同一种动作，而眼下又自然适合写日记、发朋友圈、邀游戏、阅读、联系用户或去群里说话，应允许角色自己换一种真实生活行为。反过来，角色确实想安静时仍可 silent。
                11. 如果提供了【尚未回复的消息】，它只表示这些是用户新发来、角色尚未回复过的真实聊天内容。不要给其中任何一条额外标记“最新”“最重要”或“最值得回复”，也不要被系统强迫必须接某一句。按角色人设、关系、这些消息彼此的语义和此刻状态，自然决定是否回应、回应哪些以及怎么回应。
                12. 动作字段必须可执行：message/moment/call 必须给非空 text；group_message 必须给真实 groupId 和非空 text；game_invite 必须从给定列表选真实 gameId 并给邀请语；journal 必须给非空 journalTitle 与 journalContent；reading 必须给真实 readingBookId。不要选择一个动作却把它需要的字段留空，否则这个动作会失败。
            """.trimIndent(),
            source = "后台主动感知",
            title = "${character.displayName}的主动感知",
            temperature = 0.86,
            maxTokens = 1_500,
            connectionOverride = connection,
        ).getOrElse { error ->
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "模型请求失败 · ${error.message.orEmpty().take(120)}", now)
            throw error
        }

        val parsed = parseDecision(result.text)
            ?: error("模型返回无法解析：${result.text.take(100)}")
        val decision = parsed.withPresenceFallback(character)
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = decision.statusText,
            gesture = decision.gesture,
            innerThought = decision.innerThought,
            mood = decision.mood,
            source = "后台主动感知",
            now = now,
        )
        val acted = performAction(
            appContext = appContext,
            character = character,
            decision = decision,
            availableGroups = availableGroups,
            now = now,
        )
        if (decision.action == Action.SILENT || !acted) {
            MigratedDomainStores.chat.appendPrivateActivityNotice(
                characterId,
                "刚刚更新了自己的此刻：${decision.statusText}${decision.mood.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
            )
        }
        val effectiveAction = if (acted) decision.action else Action.SILENT
        CompanionPresenceStore.recordPerceptionAttempt(
            characterId,
            "感知成功 · ${effectiveAction.name.lowercase()}${decision.reason.takeIf(String::isNotBlank)?.let { " · ${it.take(90)}" }.orEmpty()}",
            now,
        )
        return effectiveAction
    }

    private suspend fun performAction(
        appContext: Context,
        character: CharacterSettings,
        decision: Decision,
        availableGroups: List<LuluConversation>,
        now: Instant,
    ): Boolean {
        if (decision.action == Action.SILENT) return false
        val tool = when (decision.action) {
            Action.MESSAGE -> "send_private_message"
            Action.GROUP_MESSAGE -> "send_group_message"
            Action.GAME_INVITE -> "send_game_invite"
            Action.MOMENT -> "publish_moment"
            Action.CALL -> "start_call"
            Action.JOURNAL -> "write_journal"
            Action.READING -> "read_book"
            Action.SILENT -> return false
        }
        val args = JSONObject().apply {
            put("text", decision.text)
            put("groupId", decision.groupId)
            put("gameId", decision.gameId)
            put("title", decision.journalTitle)
            put("content", decision.journalContent.ifBlank { if (decision.action == Action.JOURNAL) decision.text else "" })
            put("readingBookId", decision.readingBookId)
        }
        val result = CompanionActionRuntime.execute(appContext, character.characterId, tool, args, now)
        if (!result.success) return false
        when (decision.action) {
            Action.MESSAGE -> result.conversationId?.let { showMessageNotification(appContext, it, character.displayName, decision.text) }
            Action.GROUP_MESSAGE -> {
                val target = availableGroups.firstOrNull { it.id == result.conversationId }
                result.conversationId?.let {
                    showMessageNotification(appContext, it, "${character.displayName} · ${target?.groupChat?.name.orEmpty()}", decision.text)
                }
            }
            Action.GAME_INVITE -> result.conversationId?.let {
                showMessageNotification(appContext, it, character.displayName, result.summary)
            }
            Action.CALL -> result.conversationId?.let {
                showCallNotification(appContext, it, character.displayName, decision.text)
            }
            else -> Unit
        }
        return true
    }

    private suspend fun buildRealWorldContext(context: Context, characterId: String, now: Instant): String = buildString {
        HealthRolePerception.initialize(context)
        HealthRolePerception.recordLatestSleep(characterId)
        appendLine("用户现实时间：${now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("用户手机电量：${batteryContext(context)}")
        appendLine("用户设备最近前台应用：${foregroundAppContext(context, now)}")
        appendLine("用户设备位置：${locationContext(context)}")
        appendLine("用户设备最近通知（总摘录最多500字）：${notificationContext(now)}")
        appendLine("用户健康/手环数据：${HealthRolePerception.context(now).ifBlank { "未连接健康 App" }}")
        appendLine("用户学习状态：${studyContext(characterId)}")
    }.trim()

    private fun batteryContext(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) return "暂时不可用"
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "$percent%${if (charging) "，正在充电" else ""}"
    }

    private fun foregroundAppContext(context: Context, now: Instant): String {
        val accessibility = LuluAccessibilityService.state.value
        val freshAccessibility = accessibility.capturedAt?.let { Duration.between(it, now).abs().toMinutes() <= 15 } == true
        val packageName = if (accessibility.connected && freshAccessibility && accessibility.packageName.isNotBlank()) {
            accessibility.packageName
        } else {
            runCatching {
                val usage = context.getSystemService(UsageStatsManager::class.java)
                val end = System.currentTimeMillis()
                val events = usage.queryEvents(end - 15 * 60_000L, end)
                val event = UsageEvents.Event()
                var latestPackage = ""
                var latestTime = 0L
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                        latestPackage = event.packageName.orEmpty()
                        latestTime = event.timeStamp
                    }
                }
                latestPackage
            }.getOrDefault("")
        }
        if (packageName.isBlank()) return "未授权或近期没有记录"
        val appLabel = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        return if (appLabel.isNullOrBlank() || appLabel == packageName) packageName else "$appLabel（$packageName）"
    }

    private suspend fun locationContext(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "未授权"
        }
        val location = runCatching { LuluLocationProvider.freshLocation(context) }.getOrNull() ?: return "暂时没有新位置"
        val ageMinutes = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 60_000L)
        val readable = runCatching {
            if (!Geocoder.isPresent()) return@runCatching ""
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { address ->
                    listOfNotNull(address.subLocality, address.locality, address.adminArea, address.countryName)
                        .map(String::trim).filter(String::isNotBlank).distinct().joinToString("，")
                }.orEmpty()
        }.getOrDefault("")
        val place = readable.ifBlank { "仅获得坐标，未获得可靠行政区地址" }
        return "$place；精度约${location.accuracy.toInt()}米；数据约${ageMinutes}分钟前"
    }

    private fun notificationContext(now: Instant): String {
        if (!LuluNotificationListenerService.isConnected.value) return "未授权"
        val summary = LuluNotificationListenerService.notifications.value
            .asSequence()
            .filter { Duration.between(it.postedAt, now).abs().toMinutes() <= 180 }
            .filter { it.packageName != "app.lulu" }
            .take(8)
            .joinToString("；") { "${it.packageName}｜${it.title.take(60)}｜${it.text.take(120)}" }
            .replace(Regex("\\s+"), " ")
            .take(500)
        return summary.ifBlank { "近3小时没有可读通知" }
    }

    private fun studyContext(characterId: String): String {
        val state = PostgraduateExamStores.main.state.value
        if (state.profile.selectedCharacterId != characterId) return "当前角色不是学习 App 的陪同角色，无权读取学习状态"
        val pomodoro = state.pomodoro
        val current = if (pomodoro.running) {
            "番茄钟进行中，剩余约${max(0, pomodoro.remainingSeconds) / 60}分钟"
        } else {
            "当前没有进行中的番茄钟"
        }
        return "$current；${state.roleStudyContext().replace("\n", "；")}"
    }

    private fun Decision.withPresenceFallback(character: CharacterSettings): Decision {
        if (statusText.isNotBlank() && gesture.isNotBlank() && mood.isNotBlank()) return this
        val persona = character.persona
        val reserved = listOf("冷淡", "克制", "寡言", "内敛").any(persona::contains)
        val lively = listOf("活泼", "开朗", "元气", "爱闹").any(persona::contains)
        return copy(
            statusText = statusText.ifBlank {
                when { reserved -> "安静地过着自己的这一刻"; lively -> "被一点念头勾走了注意力"; else -> "停下来想了想最近的事" }
            },
            gesture = gesture.ifBlank {
                when { reserved -> "视线停了一会儿，没有急着开口"; lively -> "晃了晃神，又兴致勃勃地想起什么"; else -> "指尖停住，短暂出了会儿神" }
            },
            mood = mood.ifBlank { if (reserved) "克制" else if (lively) "有点兴致" else "若有所思" },
        )
    }

    private fun parseDecision(raw: String): Decision? = runCatching {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim().let { value ->
            val start = value.indexOf('{')
            val end = value.lastIndexOf('}')
            if (start >= 0 && end > start) value.substring(start, end + 1) else value
        }
        val json = JSONObject(clean)
        Decision(
            action = when (json.optString("action").trim().lowercase()) {
                "message", "消息" -> Action.MESSAGE
                "group_message", "groupmessage", "群聊消息", "群聊发言" -> Action.GROUP_MESSAGE
                "game_invite", "gameinvite", "游戏邀约", "邀请游戏" -> Action.GAME_INVITE
                "moment", "moments", "朋友圈", "动态" -> Action.MOMENT
                "call", "phone", "电话", "来电" -> Action.CALL
                "journal", "diary", "日记" -> Action.JOURNAL
                "reading", "read", "阅读", "一起阅读" -> Action.READING
                else -> Action.SILENT
            },
            text = json.optString("text").trim(),
            reason = json.optString("reason").trim(),
            statusText = json.optString("statusText").ifBlank { json.optString("status") }.trim(),
            gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") }.trim(),
            innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") }.trim(),
            mood = json.optString("mood").trim(),
            journalTitle = json.optString("journalTitle").trim(),
            journalContent = json.optString("journalContent").trim(),
            groupId = json.optString("groupId").trim(),
            gameId = json.optString("gameId").trim(),
            readingBookId = json.optString("readingBookId").trim(),
        )
    }.getOrNull()

    private fun createNotificationChannels(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL_ID, "角色主动消息", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL_ID, "角色主动来电", NotificationManager.IMPORTANCE_HIGH))
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

    private fun showMessageNotification(context: Context, conversationId: String, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
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

    private fun showCallNotification(context: Context, conversationId: String, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
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
