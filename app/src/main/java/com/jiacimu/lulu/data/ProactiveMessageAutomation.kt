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
import com.jiacimu.lulu.core.LexiconSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Persona-grounded proactive contact runtime.
 *
 * This keeps the useful part of the old Lulu pipeline: perceive real context, build only
 * executable candidates, let the character decide according to persona/concerns/commitments,
 * then persist the result. It deliberately removes fake background actions, fixed greetings and
 * the old narrow activity window.
 *
 * The loop currently survives while the app process is alive. A WorkManager/foreground service
 * bridge can later reuse [checkOnce] without duplicating decision logic.
 */
object ProactiveMessageAutomation {
    private const val PREFS_NAME = "lulu_proactive_runtime"
    private const val MESSAGE_CHANNEL_ID = "lulu_proactive_messages"
    private const val CALL_CHANNEL_ID = "lulu_proactive_calls"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    private const val INITIAL_DELAY_MS = 45 * 1000L
    private const val MIN_IDLE_MINUTES = 75L
    private const val MESSAGE_COOLDOWN_MINUTES = 180L
    private const val CALL_COOLDOWN_MINUTES = 720L
    private const val DAILY_CONTACT_LIMIT = 5
    private const val DAILY_CALL_LIMIT = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var context: Context? = null
    private var started = false

    private enum class Action { MESSAGE, CALL, SILENT }

    private data class Decision(
        val action: Action,
        val text: String,
        val reason: String,
    )

    @Synchronized
    fun initialize(appContext: Context) {
        if (started) return
        started = true
        context = appContext.applicationContext
        createNotificationChannels(appContext)
        scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                runCatching { checkOnce() }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    internal suspend fun checkOnce(now: Instant = Instant.now()): Boolean {
        val appContext = context ?: return false
        val preferences = LuluAppPreferencesStore.state.value
        if (!preferences.notificationsEnabled || !preferences.proactiveContactEnabled) return false
        if (preferences.quietHoursEnabled && isQuietHour(preferences, LocalTime.now())) return false

        val conversation = MigratedDomainStores.chat.conversations.value
            .filter { it.parentConversationId == null }
            .maxByOrNull { it.updatedAt }
            ?: return false
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val lastActivity = messages.lastOrNull()?.createdAt ?: conversation.updatedAt
        val idleMinutes = Duration.between(lastActivity, now).toMinutes()
        if (idleMinutes < MIN_IDLE_MINUTES) return false

        val characterId = conversation.characterId.ifBlank { "lulu" }
        val character = MigratedDomainStores.characters.get(characterId)
        if (!character.contactPolicy.enabled) return false
        if (character.contactPolicy.quietHoursEnabled && isCharacterQuietHour(character.contactPolicy, LocalTime.now())) {
            return false
        }

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val storedDay = prefs.getString("count_day_$characterId", null)
        val contactCount = if (storedDay == today) prefs.getInt("count_$characterId", 0) else 0
        val callCount = if (storedDay == today) prefs.getInt("call_count_$characterId", 0) else 0
        if (contactCount >= DAILY_CONTACT_LIMIT) return false

        val lastContactAt = prefs.getLong("last_contact_$characterId", 0L)
        if (lastContactAt > 0L && Duration.between(Instant.ofEpochMilli(lastContactAt), now).toMinutes() < MESSAGE_COOLDOWN_MINUTES) {
            return false
        }

        val recent = messages.takeLast(18).joinToString("\n") { message ->
            val speaker = if (message.sender == LuluChatMessage.Sender.User) "主人" else character.displayName
            "$speaker：${message.content}"
        }
        val lexicon = LuluRepositories.lexicon.snapshot(characterId)
        val concerns = lexicon
            .filter { it.section == LexiconSection.Concern }
            .take(8)
            .joinToString("\n") { "- ${it.title}：${it.content}" }
        val commitments = lexicon
            .filter { it.section == LexiconSection.Promise }
            .take(10)
            .joinToString("\n") { "- ${it.promiseKind?.name ?: "Promise"}｜${it.title}：${it.content}" }
        val memories = RelevantMemoryRecall.recall(
            characterId = characterId,
            query = listOf(recent.takeLast(2_500), concerns, commitments).joinToString("\n"),
            limit = 10,
        )
        val memoryContext = RelevantMemoryRecall.formatForPrompt(memories)

        val callAllowed = preferences.proactiveCallsEnabled &&
            character.contactPolicy.proactiveCallsEnabled &&
            isInsideCallWindow(character.contactPolicy, LocalTime.now()) &&
            callCount < DAILY_CALL_LIMIT &&
            (prefs.getLong("last_call_$characterId", 0L).let { value ->
                value == 0L || Duration.between(Instant.ofEpochMilli(value), now).toMinutes() >= CALL_COOLDOWN_MINUTES
            })

        val modelLibrary = LuluAiServices.connectionStore.library.value
        val activeArchive = modelLibrary.archives.firstOrNull { it.id == modelLibrary.activeArchiveId }
            ?: return false
        val backgroundModelLabel = LuluAiServices.connectionStore.archiveLabel(activeArchive)

        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("【角色人设】")
                appendLine(character.persona.ifBlank { "按角色当前设定自然行动。" })
                appendLine("\n【真实时间状态】")
                appendLine("距离上次聊天约 ${idleMinutes.coerceAtLeast(1)} 分钟。")
                appendLine("今天已经主动联系 $contactCount 次；主动来电 $callCount 次。")
                appendLine("当前允许主动来电：${if (callAllowed) "是" else "否"}")
                if (concerns.isNotBlank()) appendLine("\n【挂心】\n$concerns")
                if (commitments.isNotBlank()) appendLine("\n【承诺、责任与监督】\n$commitments")
                if (memoryContext.isNotBlank()) appendLine("\n$memoryContext")
                if (recent.isNotBlank()) appendLine("\n【最近聊天】\n$recent")
            },
            instruction = """
                你正在替当前角色判断此刻是否要主动联系主人。核心目标是像真实的人，而不是完成系统打卡。

                只返回一个 JSON 对象，不要代码块：
                {"action":"message|call|silent","text":"真正发送给主人的内容","reason":"内部简短原因"}

                决策规则：
                1. 必须严格贴合角色人设。活泼角色可以更直接，克制角色可以含蓄，冷淡角色不必突然撒娇；任何角色都不能被统一写成温柔助手。
                2. 挂心、承诺和长期监督是可用动机，但不能每次都机械提醒。只有此刻自然相关时才提起。
                3. 不得编造主人当前正在做什么、身体状态或现实环境。
                4. 若没有真实想联系的理由，选择 silent。沉默可以是符合人设的行动，不是失败。
                5. message 的 text 应像角色主动发来的聊天，通常 20~160 个汉字，不写标题，不解释自动化。
                6. call 只在“当前允许主动来电：是”时可选，而且必须有比普通消息更强的动机。text 是来电前一句很短的理由。
                7. 不要重复最近已经说过的问候、监督或相同句式。
                8. 用户要求与角色人设冲突时，尊重用户边界，但保留角色自己的表达方式。
            """.trimIndent(),
            source = "后台感知",
            title = "${character.displayName}的主动行动决策 · $backgroundModelLabel",
            temperature = 0.72,
            maxTokens = 520,
        )
        val decision = result.getOrNull()?.text?.let(::parseDecision) ?: return false
        when (decision.action) {
            Action.SILENT -> {
                prefs.edit().putLong("last_silent_$characterId", now.toEpochMilli()).apply()
                return false
            }
            Action.MESSAGE -> {
                if (decision.text.isBlank()) return false
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, decision.text)
                showMessageNotification(appContext, conversation.id, character.displayName, decision.text)
            }
            Action.CALL -> {
                if (!callAllowed || decision.text.isBlank()) return false
                val callText = decision.text.take(80)
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "[想给你打电话] $callText")
                showCallNotification(appContext, conversation.id, character.displayName, callText)
                prefs.edit()
                    .putLong("last_call_$characterId", now.toEpochMilli())
                    .putInt("call_count_$characterId", callCount + 1)
                    .apply()
            }
        }

        prefs.edit()
            .putLong("last_contact_$characterId", now.toEpochMilli())
            .putString("count_day_$characterId", today)
            .putInt("count_$characterId", contactCount + 1)
            .apply()
        return true
    }

    private fun parseDecision(raw: String): Decision? = runCatching {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { value ->
                val start = value.indexOf('{')
                val end = value.lastIndexOf('}')
                if (start >= 0 && end > start) value.substring(start, end + 1) else value
            }
        val json = JSONObject(cleaned)
        val action = when (json.optString("action").trim().lowercase()) {
            "message", "消息" -> Action.MESSAGE
            "call", "phone", "电话", "来电" -> Action.CALL
            else -> Action.SILENT
        }
        Decision(
            action = action,
            text = json.optString("text").trim(),
            reason = json.optString("reason").trim(),
        )
    }.getOrNull()

    private fun isQuietHour(settings: LuluAppPreferences, time: LocalTime): Boolean =
        isHourInRange(time.hour, settings.quietStartHour, settings.quietEndHour)

    private fun isCharacterQuietHour(policy: CharacterContactPolicy, time: LocalTime): Boolean =
        isHourInRange(time.hour, policy.quietStartHour, policy.quietEndHour)

    private fun isInsideCallWindow(policy: CharacterContactPolicy, time: LocalTime): Boolean =
        if (policy.callWindowStartHour == policy.callWindowEndHour) true
        else if (policy.callWindowStartHour < policy.callWindowEndHour) {
            time.hour in policy.callWindowStartHour until policy.callWindowEndHour
        } else {
            time.hour >= policy.callWindowStartHour || time.hour < policy.callWindowEndHour
        }

    private fun isHourInRange(hour: Int, start: Int, end: Int): Boolean =
        if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end

    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "角色主动消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "角色根据人设、挂心和承诺主动发来的消息"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, "角色主动来电", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色在合适时机主动发起的来电"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
        )
    }

    private fun conversationIntent(context: Context, conversationId: String, incomingCall: Boolean): PendingIntent {
        val intent = Intent(context, MigrationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_conversation_id", conversationId)
            putExtra("open_incoming_call", incomingCall)
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode() + if (incomingCall) 31 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showMessageNotification(context: Context, conversationId: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(conversationIntent(context, conversationId, incomingCall = false))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(conversationId.hashCode(), notification)
    }

    private fun showCallNotification(context: Context, conversationId: String, title: String, reason: String) {
        val pendingIntent = conversationIntent(context, conversationId, incomingCall = true)
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("$title 想给你打电话")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(conversationId.hashCode() xor 0xCA11, notification)
    }
}
