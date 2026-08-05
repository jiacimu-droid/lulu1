package com.jiacimu.lulu.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.MigrationActivity
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.system.LuluAccessibilityService
import com.jiacimu.lulu.system.LuluNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Background perception and proactive action runtime.
 *
 * Unlike the old Activity-only hourly loop, this runtime is also woken by AlarmManager and by
 * meaningful accessibility/notification changes. It rotates through existing private characters,
 * records every attempt, always asks for a living presence update, and only suppresses outward
 * contact when the user's policy or quiet hours require it.
 */
object ProactiveMessageAutomation {
    private const val PREFS_NAME = "lulu_proactive_runtime"
    private const val MESSAGE_CHANNEL_ID = "lulu_proactive_messages"
    private const val CALL_CHANNEL_ID = "lulu_proactive_calls"
    private const val CHECK_INTERVAL_MS = 20 * 60 * 1000L
    private const val INITIAL_DELAY_MS = 20 * 1000L
    private const val EVALUATION_COOLDOWN_MINUTES = 28L
    private const val SIGNAL_COOLDOWN_MINUTES = 10L
    private const val MIN_IDLE_MINUTES = 45L
    private const val MESSAGE_COOLDOWN_MINUTES = 120L
    private const val CALL_COOLDOWN_MINUTES = 720L
    private const val DAILY_CONTACT_LIMIT = 7
    private const val DAILY_CALL_LIMIT = 1
    private const val JOURNAL_COOLDOWN_MINUTES = 480L
    private const val MOMENT_COOLDOWN_MINUTES = 300L
    private const val MAX_CHARACTERS_PER_CYCLE = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cycleMutex = Mutex()
    private var context: Context? = null
    private var started = false

    private enum class Action { MESSAGE, GROUP_MESSAGE, GAME_INVITE, MOMENT, CALL, JOURNAL, SILENT }

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
    )

    @Synchronized
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        createNotificationChannels(appContext)
        ProactivePerceptionScheduler.schedule(appContext)
        if (started) return
        started = true
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                runCatching { runBackgroundCycle("应用内定时") }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Called by real device signals. It is debounced so changing windows cannot burn API calls. */
    fun signalPerceptionChange(appContext: Context, trigger: String) {
        val context = appContext.applicationContext
        ProactivePerceptionScheduler.scheduleSoon(context, trigger)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = Instant.now()
        val lastSignal = prefs.getLong("last_signal_run", 0L)
        if (lastSignal > 0L && Duration.between(Instant.ofEpochMilli(lastSignal), now).toMinutes() < SIGNAL_COOLDOWN_MINUTES) return
        prefs.edit().putLong("last_signal_run", now.toEpochMilli()).apply()
        if (this.context != null) {
            scope.launch {
                delay(8_000L)
                runCatching { runBackgroundCycle(trigger) }
            }
        }
    }

    internal suspend fun checkOnce(now: Instant = Instant.now()): Boolean =
        runBackgroundCycle(trigger = "手动检查", now = now) > 0

    /** Returns the number of characters whose perception model was actually evaluated. */
    internal suspend fun runBackgroundCycle(
        trigger: String = "后台定时",
        now: Instant = Instant.now(),
    ): Int = cycleMutex.withLock {
        val appContext = context ?: return@withLock 0
        val runtimePrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privateConversations = MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { conversation ->
                conversation.parentConversationId == null &&
                    conversation.groupChat == null &&
                    !conversation.id.endsWith("-study-focus")
            }
            .groupBy(LuluConversation::characterId)
            .mapNotNull { (_, values) -> values.maxByOrNull(LuluConversation::updatedAt) }

        if (privateConversations.isEmpty()) return@withLock 0
        val signalTriggered = trigger != "后台定时" && trigger != "应用内定时"
        val due = privateConversations.filter { conversation ->
            val last = runtimePrefs.getLong("last_evaluation_${conversation.characterId}", 0L)
            last == 0L || Duration.between(Instant.ofEpochMilli(last), now).toMinutes() >=
                if (signalTriggered) SIGNAL_COOLDOWN_MINUTES else EVALUATION_COOLDOWN_MINUTES
        }
        val targets = due.sortedWith(
            compareBy<LuluConversation> {
                runtimePrefs.getLong("last_evaluation_${it.characterId}", 0L)
            }.thenByDescending(LuluConversation::updatedAt),
        ).take(MAX_CHARACTERS_PER_CYCLE)

        var evaluated = 0
        targets.forEach { conversation ->
            val characterId = conversation.characterId.ifBlank { "lulu" }
            runtimePrefs.edit().putLong("last_evaluation_$characterId", now.toEpochMilli()).apply()
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知启动 · $trigger", now)
            runCatching {
                evaluateCharacter(appContext, conversation, trigger, now)
            }.onSuccess {
                evaluated += 1
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

    private suspend fun evaluateCharacter(
        appContext: Context,
        conversation: LuluConversation,
        trigger: String,
        now: Instant,
    ) {
        val preferences = LuluAppPreferencesStore.state.value
        val characterId = conversation.characterId.ifBlank { "lulu" }
        val character = MigratedDomainStores.characters.get(characterId)
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val lastActivity = messages.lastOrNull()?.createdAt ?: conversation.updatedAt
        val idleMinutes = Duration.between(lastActivity, now).toMinutes().coerceAtLeast(0)
        val currentTime = LocalTime.now()
        val globalQuiet = preferences.quietHoursEnabled && isQuietHour(preferences, currentTime)
        val characterQuiet = character.contactPolicy.quietHoursEnabled && isCharacterQuietHour(character.contactPolicy, currentTime)
        val runtimePrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val storedDay = runtimePrefs.getString("count_day_$characterId", null)
        val contactCount = if (storedDay == today) runtimePrefs.getInt("count_$characterId", 0) else 0
        val callCount = if (storedDay == today) runtimePrefs.getInt("call_count_$characterId", 0) else 0
        val lastContactAt = runtimePrefs.getLong("last_contact_$characterId", 0L)
        val minutesSinceContact = if (lastContactAt == 0L) Long.MAX_VALUE else
            Duration.between(Instant.ofEpochMilli(lastContactAt), now).toMinutes().coerceAtLeast(0)
        val contactAllowed = preferences.proactiveContactEnabled &&
            character.contactPolicy.enabled &&
            !globalQuiet &&
            !characterQuiet &&
            idleMinutes >= MIN_IDLE_MINUTES &&
            contactCount < DAILY_CONTACT_LIMIT &&
            minutesSinceContact >= MESSAGE_COOLDOWN_MINUTES
        val callAllowed = contactAllowed && preferences.proactiveCallsEnabled &&
            character.contactPolicy.proactiveCallsEnabled &&
            isInsideCallWindow(character.contactPolicy, currentTime) &&
            callCount < DAILY_CALL_LIMIT &&
            runtimePrefs.getLong("last_call_$characterId", 0L).let { value ->
                value == 0L || Duration.between(Instant.ofEpochMilli(value), now).toMinutes() >= CALL_COOLDOWN_MINUTES
            }
        val journalAllowed = runtimePrefs.getLong("last_journal_$characterId", 0L).let { value ->
            value == 0L || Duration.between(Instant.ofEpochMilli(value), now).toMinutes() >= JOURNAL_COOLDOWN_MINUTES
        }
        val momentAllowed = runtimePrefs.getLong("last_moment_$characterId", 0L).let { value ->
            value == 0L || Duration.between(Instant.ofEpochMilli(value), now).toMinutes() >= MOMENT_COOLDOWN_MINUTES
        }

        val library = LuluAiServices.connectionStore.library.value
        val perceptionArchiveId = library.archiveIdFor(ModelUsage.Chat)
        if (perceptionArchiveId == null) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知暂停 · 没有可用的聊天模型", now)
            return
        }
        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(perceptionArchiveId) }
            .getOrElse { error ->
                CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知暂停 · ${error.message.orEmpty().take(120)}", now)
                return
            }

        val availableGroups = MigratedDomainStores.chat.conversations.value.filter { candidate ->
            candidate.groupChat?.members?.any { it.characterId == characterId } == true
        }
        val recent = messages.takeLast(20).joinToString("\n") { message ->
            val speaker = when (message.sender) {
                LuluChatMessage.Sender.User -> "用户"
                LuluChatMessage.Sender.Character -> character.displayName
                LuluChatMessage.Sender.System -> "系统事件"
            }
            "$speaker：${message.content.take(700)}"
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
        val screen = LuluAccessibilityService.state.value
        val screenFresh = screen.capturedAt?.let { Duration.between(it, now).abs().toMinutes() <= 15 } == true
        val screenContext = if (screen.connected && screenFresh && screen.packageName.isNotBlank()) buildString {
            append("前台应用=${screen.packageName}")
            if (screen.windowTitle.isNotBlank()) append("；窗口=${screen.windowTitle.take(120)}")
            if (screen.visibleText.isNotBlank() && screen.packageName != "app.lulu") {
                append("；屏幕可见文字摘录=${screen.visibleText.replace(Regex("\\s+"), " ").take(900)}")
            }
        } else "屏幕感知当前未连接或没有新鲜快照"
        val notifications = LuluNotificationListenerService.notifications.value
            .filter { Duration.between(it.postedAt, now).abs().toMinutes() <= 180 && it.packageName != "app.lulu" }
            .take(5)
            .joinToString("\n") { "- ${it.packageName}｜${it.title.take(80)}｜${it.text.take(160)}" }

        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("【角色人设】\n${character.persona.ifBlank { "按角色当前设定自然行动。" }}")
                appendLine("\n【本次真实感知】")
                appendLine("触发来源：$trigger")
                appendLine("当前时间：$now；距离最后聊天约 $idleMinutes 分钟；距离上次主动联系约 ${if (minutesSinceContact == Long.MAX_VALUE) "很久" else "$minutesSinceContact 分钟"}")
                appendLine("设备：$screenContext")
                if (notifications.isNotBlank()) appendLine("近期通知摘要：\n$notifications")
                appendLine("允许主动消息：${if (contactAllowed) "是" else "否"}；允许主动来电：${if (callAllowed) "是" else "否"}；允许日记：${if (journalAllowed) "是" else "否"}；允许朋友圈：${if (momentAllowed) "是" else "否"}")
                if (availableGroups.isNotEmpty()) {
                    appendLine("所在群聊：")
                    availableGroups.forEach { groupConversation ->
                        appendLine("- groupId=${groupConversation.id}；${groupConversation.groupChat?.name}；最近=${groupConversation.lastMessage.take(120)}")
                    }
                }
                previousPresence?.let { appendLine("上一刻：${it.statusText}；${it.gesture}；${it.mood}；心声=${it.innerThought}") }
                if (concerns.isNotBlank()) appendLine("\n【挂心】\n$concerns")
                if (commitments.isNotBlank()) appendLine("\n【承诺与监督】\n$commitments")
                if (memoryContext.isNotBlank()) appendLine("\n$memoryContext")
                if (recent.isNotBlank()) appendLine("\n【最近聊天】\n$recent")
            },
            instruction = """
                你正在让当前角色基于真实时间、设备感知、记忆和关系形成“此刻”，并判断要不要自然行动。不要做系统报告。
                只返回 JSON：
                {"action":"message|group_message|game_invite|moment|call|journal|silent","text":"实际发送内容","groupId":"群ID","gameId":"游戏ID","reason":"角色为什么这样做","statusText":"角色现在在做什么的短状态","gesture":"符合人设的动作神态","innerThought":"第一人称没说出口的瞬间心声","mood":"简短心情","journalTitle":"日记标题","journalContent":"日记正文"}

                规则：
                1. statusText、gesture、mood 必须有内容；innerThought 可以为空。即使 action=silent，也必须形成新的、符合人设的此刻状态，不能原样复读上一条。
                2. 感知到的应用、窗口和通知只是线索。不得断言用户正在做某件事，不得泄露或机械复述通知全文；只能产生克制、自然的反应。
                3. 当前允许主动消息为“否”时，不得选择 message、group_message、game_invite 或 call，但仍可更新状态，必要时写日记或朋友圈。
                4. 当前允许主动消息为“是”，且已经很久没有联系时，除非人设或上下文明显不适合，优先发一条有具体缘由的小消息，而不是连续多次 silent。消息通常 15—120 字，像真人突然想起对方。
                5. 主动消息不能总是问候、催睡、催学习；可以接续旧话题、分享一瞬想法、追问、轻微吐槽、邀约或兑现承诺。不得编造用户现实状态。
                6. group_message 只能使用上面真实存在的 groupId；gameId 只能是 perfect_man、roleplay、turtle_soup、rapport_quiz、yacht_dice、gomoku、memory_match。
                7. 日记必须是角色第一人称私人内心独白，不是聊天总结或系统说明；朋友圈必须是角色真的愿意公开的内容。
                8. 角色的用词、主动程度、动作和心声必须由其人设决定，禁止把所有角色写成同一种温柔助手。
            """.trimIndent(),
            source = "后台感知",
            title = "${character.displayName}的实时感知",
            temperature = 0.84,
            maxTokens = 1_500,
            connectionOverride = connection,
        )
        val raw = result.getOrElse { error ->
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "模型请求失败 · ${error.message.orEmpty().take(120)}", now)
            return
        }.text
        val parsed = parseDecision(raw)
        if (parsed == null) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "模型返回无法解析 · ${raw.take(90)}", now)
            return
        }
        val decision = parsed.withPresenceFallback(character)
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = decision.statusText,
            gesture = decision.gesture,
            innerThought = decision.innerThought,
            mood = decision.mood,
            source = "后台感知",
            now = now,
        )
        val blocked = !contactAllowed && decision.action in setOf(Action.MESSAGE, Action.GROUP_MESSAGE, Action.GAME_INVITE, Action.CALL)
        if (blocked) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知成功 · 外部联系被安静时段或策略拦截", now)
            return
        }

        val acted = performAction(
            appContext = appContext,
            conversation = conversation,
            character = character,
            decision = decision,
            availableGroups = availableGroups,
            preferences = preferences,
            callAllowed = callAllowed,
            journalAllowed = journalAllowed,
            momentAllowed = momentAllowed,
            now = now,
            runtimePrefs = runtimePrefs,
            contactCount = contactCount,
            callCount = callCount,
            today = today,
        )
        CompanionPresenceStore.recordPerceptionAttempt(
            characterId,
            "感知成功 · ${decision.action.name.lowercase()}${decision.reason.takeIf(String::isNotBlank)?.let { " · ${it.take(90)}" }.orEmpty()}",
            now,
        )
        if (!acted && decision.action != Action.SILENT) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId, "感知成功，但动作条件不完整，已保留状态", now)
        }
    }

    private fun performAction(
        appContext: Context,
        conversation: LuluConversation,
        character: CharacterSettings,
        decision: Decision,
        availableGroups: List<LuluConversation>,
        preferences: LuluAppPreferences,
        callAllowed: Boolean,
        journalAllowed: Boolean,
        momentAllowed: Boolean,
        now: Instant,
        runtimePrefs: android.content.SharedPreferences,
        contactCount: Int,
        callCount: Int,
        today: String,
    ): Boolean {
        val characterId = character.characterId
        when (decision.action) {
            Action.SILENT -> return false
            Action.MESSAGE -> {
                if (decision.text.isBlank()) return false
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, decision.text, characterId)
                if (preferences.notificationsEnabled) showMessageNotification(appContext, conversation.id, character.displayName, decision.text)
            }
            Action.GROUP_MESSAGE -> {
                if (decision.text.isBlank()) return false
                val target = availableGroups.firstOrNull { it.id == decision.groupId } ?: return false
                MigratedDomainStores.chat.appendCharacterMessage(target.id, decision.text, characterId)
                if (preferences.notificationsEnabled) showMessageNotification(appContext, target.id, "${character.displayName} · ${target.groupChat?.name.orEmpty()}", decision.text)
            }
            Action.GAME_INVITE -> {
                if (decision.text.isBlank()) return false
                val titles = mapOf("perfect_man" to "满分男", "roleplay" to "跑团", "turtle_soup" to "海龟汤", "rapport_quiz" to "默契问答", "yacht_dice" to "快艇骰子", "gomoku" to "五子棋", "memory_match" to "记忆配对")
                val title = titles[decision.gameId] ?: return false
                val content = "[游戏邀约|${decision.gameId}|$title] ${decision.text.take(240)}"
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, content, characterId)
                if (preferences.notificationsEnabled) showMessageNotification(appContext, conversation.id, character.displayName, "邀请你一起玩《$title》")
            }
            Action.MOMENT -> {
                if (!momentAllowed || decision.text.isBlank()) return false
                MomentsStore.publishCharacter(characterId, decision.text.take(2_000)) ?: return false
                runtimePrefs.edit().putLong("last_moment_$characterId", now.toEpochMilli()).apply()
                return true
            }
            Action.CALL -> {
                if (!callAllowed || decision.text.isBlank()) return false
                val callText = decision.text.take(80)
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "[想给你打电话] $callText", characterId)
                if (preferences.notificationsEnabled) showCallNotification(appContext, conversation.id, character.displayName, callText)
                runtimePrefs.edit().putLong("last_call_$characterId", now.toEpochMilli()).putInt("call_count_$characterId", callCount + 1).apply()
            }
            Action.JOURNAL -> {
                if (!journalAllowed || decision.journalContent.isBlank()) return false
                val diaryId = UUID.randomUUID().toString()
                LuluRepositories.lexicon.save(
                    LexiconEntry(
                        id = diaryId,
                        characterId = characterId,
                        section = LexiconSection.Diary,
                        title = decision.journalTitle.ifBlank { "没写完的一页" }.take(30),
                        content = decision.journalContent.take(2_000),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                SharedExperienceTimeline.record(
                    eventId = "lexicon-diary-$diaryId",
                    characterId = characterId,
                    channel = "私人日记",
                    speaker = character.displayName,
                    content = "${decision.journalTitle.ifBlank { "没写完的一页" }}\n${decision.journalContent.take(2_000)}",
                    occurredAt = now,
                )
                MigratedDomainStores.chat.appendSystemMessage(conversation.id, "[共同活动] 刚刚写了一篇日记")
                runtimePrefs.edit().putLong("last_journal_$characterId", now.toEpochMilli()).apply()
                return true
            }
        }
        runtimePrefs.edit()
            .putLong("last_contact_$characterId", now.toEpochMilli())
            .putString("count_day_$characterId", today)
            .putInt("count_$characterId", contactCount + 1)
            .apply()
        return true
    }

    private fun Decision.withPresenceFallback(character: CharacterSettings): Decision {
        if (statusText.isNotBlank() && gesture.isNotBlank() && mood.isNotBlank()) return this
        val persona = character.persona
        val reserved = listOf("冷淡", "克制", "寡言", "内敛").any(persona::contains)
        val lively = listOf("活泼", "开朗", "元气", "爱闹").any(persona::contains)
        return copy(
            statusText = statusText.ifBlank {
                when { reserved -> "安静地留意着最近的动静"; lively -> "被一点新动静勾走了注意力"; else -> "停下来想了想最近发生的事" }
            },
            gesture = gesture.ifBlank {
                when { reserved -> "视线停了一会儿，没有急着开口"; lively -> "晃了晃神，又凑近看了一眼"; else -> "指尖停住，短暂出了会儿神" }
            },
            mood = mood.ifBlank { if (reserved) "克制" else if (lively) "好奇" else "若有所思" },
        )
    }

    private fun parseDecision(raw: String): Decision? = runCatching {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim().let { value ->
            val start = value.indexOf('{')
            val end = value.lastIndexOf('}')
            if (start >= 0 && end > start) value.substring(start, end + 1) else value
        }
        val json = JSONObject(cleaned)
        val action = when (json.optString("action").trim().lowercase()) {
            "message", "消息" -> Action.MESSAGE
            "group_message", "groupmessage", "群聊消息", "群聊发言" -> Action.GROUP_MESSAGE
            "game_invite", "gameinvite", "游戏邀约", "邀请游戏" -> Action.GAME_INVITE
            "moment", "moments", "朋友圈", "动态" -> Action.MOMENT
            "call", "phone", "电话", "来电" -> Action.CALL
            "journal", "diary", "日记" -> Action.JOURNAL
            else -> Action.SILENT
        }
        Decision(
            action = action,
            text = json.optString("text").trim(),
            reason = json.optString("reason").trim(),
            statusText = json.optString("statusText").ifBlank { json.optString("status") }.trim(),
            gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") }.trim(),
            innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") }.trim(),
            mood = json.optString("mood").trim(),
            journalTitle = json.optString("journalTitle").ifBlank { json.optString("journal_title") }.trim(),
            journalContent = json.optString("journalContent").ifBlank { json.optString("journal_content") }.trim(),
            groupId = json.optString("groupId").ifBlank { json.optString("group_id") }.trim(),
            gameId = json.optString("gameId").ifBlank { json.optString("game_id") }.trim(),
        )
    }.getOrNull()

    private fun isQuietHour(settings: LuluAppPreferences, time: LocalTime): Boolean = isHourInRange(time.hour, settings.quietStartHour, settings.quietEndHour)
    private fun isCharacterQuietHour(policy: CharacterContactPolicy, time: LocalTime): Boolean = isHourInRange(time.hour, policy.quietStartHour, policy.quietEndHour)
    private fun isInsideCallWindow(policy: CharacterContactPolicy, time: LocalTime): Boolean =
        if (policy.callWindowStartHour == policy.callWindowEndHour) true
        else if (policy.callWindowStartHour < policy.callWindowEndHour) time.hour in policy.callWindowStartHour until policy.callWindowEndHour
        else time.hour >= policy.callWindowStartHour || time.hour < policy.callWindowEndHour
    private fun isHourInRange(hour: Int, start: Int, end: Int): Boolean =
        if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end

    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL_ID, "角色主动消息", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL_ID, "角色主动来电", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun conversationIntent(context: Context, conversationId: String, incomingCall: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        conversationId.hashCode() + if (incomingCall) 31 else 0,
        Intent(context, MigrationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_conversation_id", conversationId)
            putExtra("open_incoming_call", incomingCall)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showMessageNotification(context: Context, conversationId: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(conversationIntent(context, conversationId, false)).setAutoCancel(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(conversationId.hashCode(), notification) }
    }

    private fun showCallNotification(context: Context, conversationId: String, title: String, reason: String) {
        val pendingIntent = conversationIntent(context, conversationId, true)
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("$title 想给你打电话").setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason)).setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true).setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(conversationId.hashCode() xor 0xCA11, notification) }
    }
}
