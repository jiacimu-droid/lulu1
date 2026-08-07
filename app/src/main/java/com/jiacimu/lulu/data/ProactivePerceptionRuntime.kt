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
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.health.GadgetbridgeHealthStore
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
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
                prefs.edit()
                    .putInt(silentKey, nextSilent.coerceAtMost(4))
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
        val due = anchor.plus(Duration.ofMinutes(policy.intervalMinutes(multiplier)))
        return deferPastQuietHours(due, policy)
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
        val recent = messages.takeLast(20).joinToString("\n") { message ->
            val speaker = when (message.sender) {
                LuluChatMessage.Sender.User -> "用户"
                LuluChatMessage.Sender.Character -> character.displayName
                LuluChatMessage.Sender.System -> "系统事件"
            }
            "$speaker：${message.content.take(500)}"
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
                appendLine("\n【真实世界感知层】")
                appendLine("触发来源：$trigger")
                appendLine("设备本地时间：$localTimeText（时区 ${zoneId.id}）")
                appendLine(deviceContext)
                appendLine("允许主动来电：${if (character.contactPolicy.proactiveCallsEnabled) "是" else "否"}")
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
                if (recent.isNotBlank()) appendLine("【最近聊天】\n$recent")
            },
            instruction = """
                你正在让当前角色按“真实世界感知 → 长期上下文 → 此刻判断 → 自主选择”形成这一刻。不要写系统报告。
                只返回 JSON：
                {"action":"message|group_message|game_invite|moment|call|journal|reading|silent","text":"实际发送/发布内容","groupId":"群ID","gameId":"游戏ID","readingBookId":"阅读内容ID","reason":"为什么这样做","statusText":"角色此刻在做什么","gesture":"动作神态","innerThought":"第一人称没说出口的心声","mood":"简短心情","journalTitle":"日记标题","journalContent":"日记正文"}

                规则：
                1. 每次感知都必须形成 statusText、gesture、mood；innerThought 可以为空。silent 不是失败，而是角色决定只过自己的这一刻。
                2. 手机信息只是现实线索。尤其通知、位置、前台应用和健康数据都不能被夸大推断；健康数据带同步时间时，要意识到它可能在下一次手环导出前保持不变。
                3. 不设每日主动次数、消息冷却、电话冷却、朋友圈冷却或日记冷却。是否行动由人设、关系、上下文和此刻意愿决定，不要因为“能做”就每次都做。
                4. message 是主动私聊；group_message 只能使用真实 groupId；game_invite 可用 gameId：roleplay、turtle_soup、yacht_dice、gomoku、memory_match。
                5. call 只有“允许主动来电=是”时才能选择；否则必须换其他动作或 silent。
                6. reading 只能使用真实 readingBookId。角色会真正读取对应正文并产生自己的感想，不要假装读了列表之外的书。
                7. journal 是角色第一人称私人日记；moment 是角色愿意公开的朋友圈。不要把所有动作写成对用户的服务或监督。
                8. 学习状态只在当前角色就是学习 App 的陪同角色时提供；没提供就代表这个角色没有权限知道，禁止猜。
                9. 角色语气、主动程度、动作、心声必须服从人设。避免机械问候、固定催睡、固定催学习以及每次重复同一种动作。
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
            conversation = conversation,
            character = character,
            decision = decision,
            availableGroups = availableGroups,
            readingBooks = readingBooks,
            now = now,
        )
        if (decision.action == Action.SILENT || !acted) {
            MigratedDomainStores.chat.appendPrivateActivityNotice(
                characterId,
                "刚刚更新了自己的此刻：${decision.statusText}${decision.mood.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
            )
        }
        CompanionPresenceStore.recordPerceptionAttempt(
            characterId,
            "感知成功 · ${decision.action.name.lowercase()}${decision.reason.takeIf(String::isNotBlank)?.let { " · ${it.take(90)}" }.orEmpty()}",
            now,
        )
        return decision.action
    }

    private suspend fun performAction(
        appContext: Context,
        conversation: LuluConversation,
        character: CharacterSettings,
        decision: Decision,
        availableGroups: List<LuluConversation>,
        readingBooks: List<com.jiacimu.lulu.study.BackgroundReadingBook>,
        now: Instant,
    ): Boolean {
        val characterId = character.characterId
        return when (decision.action) {
            Action.SILENT -> false
            Action.MESSAGE -> {
                if (decision.text.isBlank()) false else {
                    MigratedDomainStores.chat.appendCharacterMessage(conversation.id, decision.text, characterId)
                    showMessageNotification(appContext, conversation.id, character.displayName, decision.text)
                    true
                }
            }
            Action.GROUP_MESSAGE -> {
                val target = availableGroups.firstOrNull { it.id == decision.groupId }
                if (target == null || decision.text.isBlank()) false else {
                    MigratedDomainStores.chat.appendCharacterMessage(target.id, decision.text, characterId)
                    showMessageNotification(appContext, target.id, "${character.displayName} · ${target.groupChat?.name.orEmpty()}", decision.text)
                    true
                }
            }
            Action.GAME_INVITE -> {
                val titles = mapOf(
                    "roleplay" to "跑团",
                    "turtle_soup" to "海龟汤",
                    "yacht_dice" to "快艇骰子",
                    "gomoku" to "五子棋",
                    "memory_match" to "记忆配对",
                )
                val title = titles[decision.gameId]
                if (title == null || decision.text.isBlank()) false else {
                    val content = "[游戏邀约|${decision.gameId}|$title] ${decision.text.take(240)}"
                    MigratedDomainStores.chat.appendCharacterMessage(conversation.id, content, characterId)
                    showMessageNotification(appContext, conversation.id, character.displayName, "邀请你一起玩《$title》")
                    true
                }
            }
            Action.MOMENT -> {
                if (decision.text.isBlank()) false else {
                    val published = MomentsStore.publishCharacter(characterId, decision.text.take(2_000)) != null
                    if (published) MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚发了一条朋友圈。")
                    published
                }
            }
            Action.CALL -> {
                if (!character.contactPolicy.proactiveCallsEnabled || decision.text.isBlank()) false else {
                    val callText = decision.text.take(100)
                    MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "[想给你打电话] $callText", characterId)
                    showCallNotification(appContext, conversation.id, character.displayName, callText)
                    true
                }
            }
            Action.JOURNAL -> {
                if (decision.journalContent.isBlank()) false else {
                    val diaryId = UUID.randomUUID().toString()
                    val title = decision.journalTitle.ifBlank { "没写完的一页" }.take(30)
                    LuluRepositories.lexicon.save(
                        LexiconEntry(
                            id = diaryId,
                            characterId = characterId,
                            section = LexiconSection.Diary,
                            title = title,
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
                        content = "$title\n${decision.journalContent.take(2_000)}",
                        occurredAt = now,
                    )
                    MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚写了一篇日记《$title》。")
                    true
                }
            }
            Action.READING -> performReading(appContext, character, decision, readingBooks, now)
        }
    }

    private suspend fun performReading(
        context: Context,
        character: CharacterSettings,
        decision: Decision,
        books: List<com.jiacimu.lulu.study.BackgroundReadingBook>,
        now: Instant,
    ): Boolean {
        val book = books.firstOrNull { it.id == decision.readingBookId } ?: return false
        val reflection = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = buildString {
                appendLine("你刚刚决定独自去阅读 App 里读《${book.title}》。")
                appendLine("正文：")
                append(book.content.take(12_000))
            },
            instruction = """
                认真读提供的正文，写下角色本人真实的阅读感想。不是给用户做书评，不续写，不冒充作者。
                用角色第一人称，1—3 段，可以写喜欢、不喜欢、联想到什么、对人物或细节的反应。只输出感想正文。
            """.trimIndent(),
            source = "主动感知·阅读",
            title = "${character.displayName}阅读《${book.title}》",
            temperature = 0.82,
            maxTokens = 700,
        ).getOrNull()?.text?.trim().orEmpty()
        if (reflection.isBlank()) return false
        SharedExperienceTimeline.record(
            eventId = "reading-alone-${UUID.randomUUID()}",
            characterId = character.characterId,
            channel = "独自阅读《${book.title}》",
            speaker = character.displayName,
            content = reflection.take(2_000),
            occurredAt = now,
        )
        MigratedDomainStores.chat.appendPrivateActivityNotice(
            character.characterId,
            "刚刚读了《${book.title}》，留下了一点感想：${reflection.replace(Regex("\\s+"), " ").take(180)}",
        )
        return true
    }

    private suspend fun buildRealWorldContext(context: Context, characterId: String, now: Instant): String = buildString {
        appendLine("时间：${now.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("电量：${batteryContext(context)}")
        appendLine("最近前台应用：${foregroundAppContext(context, now)}")
        appendLine("位置：${locationContext(context)}")
        appendLine("最近通知（总摘录最多500字）：${notificationContext(now)}")
        appendLine("健康数据：${healthContext(context, now)}")
        appendLine("学习状态：${studyContext(characterId)}")
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

    private fun healthContext(context: Context, now: Instant): String {
        GadgetbridgeHealthStore.initialize(context.applicationContext)
        val state = GadgetbridgeHealthStore.state.value
        if (!state.connected) return "未连接 Gadgetbridge 数据"
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val day = state.days.firstOrNull { it.date == today } ?: state.latest
        val sleep = state.days.lastOrNull { it.sleepStartEpochSeconds != null || it.sleepEndEpochSeconds != null }
        if (day == null && sleep == null) return "已连接，但暂时没有可读数据"
        fun clock(epoch: Long?): String = epoch?.let {
            Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } ?: "—"
        val heartRange = day?.minimumHeartRate?.let { min -> "$min—${day.maximumHeartRate ?: min} 次/分" } ?: "—"
        val distance = day?.distanceMeters?.let { meters ->
            if (meters >= 1_000) "%.2f公里".format(Locale.getDefault(), meters / 1_000f) else "${meters}米"
        } ?: "—"
        val synced = state.lastImportedAt?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("M-d HH:mm")) ?: "未知"
        return buildString {
            append("入睡=${clock(sleep?.sleepStartEpochSeconds)}；起床=${clock(sleep?.sleepEndEpochSeconds)}")
            append("；步数=${day?.steps ?: 0}")
            append("；活动热量=${day?.calories?.let { "$it 千卡" } ?: "—"}")
            append("；活动距离=$distance")
            append("；血氧=${day?.spo2?.let { "$it%" } ?: "—"}")
            append("；心率范围=$heartRange")
            append("；Gadgetbridge最后解析=$synced。该数据按手环导出节奏更新，导出前数值可能重复。")
        }
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
