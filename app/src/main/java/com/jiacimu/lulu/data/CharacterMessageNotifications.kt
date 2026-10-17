package com.jiacimu.lulu.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.MigrationActivity
import com.jiacimu.lulu.qqForwardContextText

/** All real character chat deliveries share this entry, including ToolRouter's generic tool action. */
internal object CharacterMessageNotifications {
    private const val LEGACY_CHANNEL = "lulu_proactive_messages"
    private const val CHANNEL = "lulu_companion_messages_v2"
    private const val PREFS = "lulu_character_message_notifications"

    fun visibleConversation(conversationId: String): Boolean = UserInteractionPresenceStore.state.value.let {
        it.appVisible == true && it.conversationId == conversationId
    }

    fun channelId(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = manager.getNotificationChannel(LEGACY_CHANNEL)
        // Android cannot raise an existing default channel's importance. Upgrade the app's old
        // default, while retaining explicit user choices, including disabled/quiet notifications.
        val customized = legacy != null && (legacy.importance != NotificationManager.IMPORTANCE_DEFAULT ||
            (Build.VERSION.SDK_INT >= 29 && legacy.hasUserSetImportance()) ||
            (Build.VERSION.SDK_INT >= 30 && legacy.hasUserSetSound()))
        if (customized) return LEGACY_CHANNEL
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "角色聊天消息",
            NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        return CHANNEL
    }

    @Synchronized fun onSaved(context: Context, conversation: LuluConversation, message: LuluChatMessage) {
        if (message.sender != LuluChatMessage.Sender.Character || message.status != LuluChatMessage.Status.Sent ||
            conversation.parentConversationId != null || conversation.id.endsWith("-study-focus") ||
            conversation.groupChat?.muted == true || visibleConversation(conversation.id)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = channelId(context)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("last_message_${conversation.id}", null) == message.id) return
        val sameBatch = !message.replyBatchId.isNullOrBlank() &&
            prefs.getString("last_batch_${conversation.id}", null) == message.replyBatchId
        val actor = MigratedDomainStores.characters.get(message.authorCharacterId ?: conversation.characterId).displayName
        val title = conversation.groupChat?.name?.let { "$actor · $it" } ?: actor
        val text = qqForwardContextText(message.content)
            .replace(Regex("^\\[(?:游戏邀约|见面邀约)\\|[^]]+]\\s*"), "").take(600)
        val open = PendingIntent.getActivity(context, conversation.id.hashCode(),
            Intent(context, MigrationActivity::class.java)
                .putExtra("open_conversation_id", conversation.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.jiacimu.lulu.R.drawable.lulu_exact_icon)
            .setContentTitle(title).setContentText(text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(sameBatch).setAutoCancel(true).setContentIntent(open).build()
        runCatching {
            manager.notify(notificationId(conversation.id), notification)
            prefs.edit().putString("last_message_${conversation.id}", message.id)
                .putString("last_batch_${conversation.id}", message.replyBatchId).apply()
        }.onFailure {
            android.util.Log.w("LuluMessages", "Chat saved; system notification unavailable", it)
        }
    }

    fun cancel(context: Context, conversationId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(conversationId))
    }

    private fun notificationId(conversationId: String) = "message-$conversationId".hashCode()
}
