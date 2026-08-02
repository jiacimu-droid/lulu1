package com.jiacimu.lulu.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jiacimu.lulu.MigrationActivity
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Delivers genuine character-initiated chat turns while the app process is alive.
 * It respects notification/proactive switches, quiet hours, idle time, daily limits and a
 * persistent per-character cooldown. Generation failures do not advance the checkpoint.
 */
object ProactiveMessageAutomation {
    private const val PREFS_NAME = "lulu_proactive_runtime"
    private const val CHANNEL_ID = "lulu_proactive_messages"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    private const val INITIAL_DELAY_MS = 45 * 1000L
    private const val MIN_IDLE_MINUTES = 120L
    private const val COOLDOWN_MINUTES = 240L
    private const val DAILY_LIMIT = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var context: Context? = null
    private var started = false

    @Synchronized
    fun initialize(appContext: Context) {
        if (started) return
        started = true
        context = appContext.applicationContext
        createNotificationChannel(appContext)
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
        val settings = LuluAppPreferencesStore.state.value
        if (!settings.notificationsEnabled || !settings.proactiveContactEnabled) return false
        if (settings.quietHoursEnabled && isQuietHour(settings, LocalTime.now())) return false

        val conversation = MigratedDomainStores.chat.conversations.value
            .filter { it.parentConversationId == null }
            .maxByOrNull { it.updatedAt }
            ?: return false
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val latest = messages.lastOrNull()
        val lastActivity = latest?.createdAt ?: conversation.updatedAt
        if (Duration.between(lastActivity, now).toMinutes() < MIN_IDLE_MINUTES) return false

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val characterKey = conversation.characterId.ifBlank { "lulu" }
        val lastSent = prefs.getLong("last_sent_$characterKey", 0L)
        if (lastSent > 0L && Duration.between(Instant.ofEpochMilli(lastSent), now).toMinutes() < COOLDOWN_MINUTES) {
            return false
        }
        val today = LocalDate.now().toString()
        val storedDay = prefs.getString("count_day_$characterKey", null)
        val count = if (storedDay == today) prefs.getInt("count_$characterKey", 0) else 0
        if (count >= DAILY_LIMIT) return false

        val character = MigratedDomainStores.characters.get(conversation.characterId)
        val recent = messages.takeLast(12).joinToString("\n") { message ->
            val speaker = if (message.sender == LuluChatMessage.Sender.User) "主人" else character.displayName
            "$speaker：${message.content}"
        }
        val result = LuluAiServices.gateway.generate(
            characterId = conversation.characterId,
            facts = buildString {
                appendLine("现在角色准备主动联系主人。")
                appendLine("距离上次聊天已经过去 ${Duration.between(lastActivity, now).toHours().coerceAtLeast(2)} 小时。")
                if (recent.isNotBlank()) {
                    appendLine("最近聊天：")
                    appendLine(recent)
                }
            },
            instruction = """
                以角色本人的口吻主动给主人发一条自然消息。
                可以延续最近话题、表达挂心、轻轻监督已有约定，或分享一句当下想说的话。
                不要声称自己看到了现实中无法知道的事情，不要编造主人当前状态。
                不要解释这是自动消息，不要写标题，不超过 100 个汉字，避免每次都用同一种问候。
            """.trimIndent(),
            source = "主动联系",
            title = "${character.displayName}主动消息",
            temperature = 0.9,
            maxTokens = 260,
        )
        val text = result.getOrNull()?.text?.trim().orEmpty()
        if (text.isBlank()) return false

        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, text)
        prefs.edit()
            .putLong("last_sent_$characterKey", now.toEpochMilli())
            .putString("count_day_$characterKey", today)
            .putInt("count_$characterKey", count + 1)
            .apply()
        showNotification(appContext, conversation.id, character.displayName, text)
        return true
    }

    private fun isQuietHour(settings: LuluAppPreferences, time: LocalTime): Boolean {
        val hour = time.hour
        val start = settings.quietStartHour
        val end = settings.quietEndHour
        return if (start == end) true else if (start < end) hour in start until end else hour >= start || hour < end
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "角色主动消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "露露和其他角色主动发来的聊天消息"
            },
        )
    }

    private fun showNotification(context: Context, conversationId: String, title: String, text: String) {
        val intent = Intent(context, MigrationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_conversation_id", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(conversationId.hashCode(), notification)
    }
}
