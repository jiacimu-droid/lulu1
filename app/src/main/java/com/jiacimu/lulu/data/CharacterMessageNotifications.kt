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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A real delivered message preview, never generated from a draft or a model intention. */
internal data class CharacterMessageBanner(
    val messageId: String,
    val conversationId: String,
    val title: String,
    val text: String,
)

/** All real character chat deliveries share this entry, including ToolRouter's generic tool action. */
internal object CharacterMessageNotifications {
    private val mutableInAppBanner = MutableStateFlow<CharacterMessageBanner?>(null)
    val inAppBanner: StateFlow<CharacterMessageBanner?> = mutableInAppBanner.asStateFlow()

    /** Do not remove a newer message when an older preview times out. */
    fun dismissInApp(messageId: String? = null) {
        if (messageId == null || mutableInAppBanner.value?.messageId == messageId)
            mutableInAppBanner.value = null
    }

    private const val LEGACY_CHANNEL = "lulu_proactive_messages"
    private const val CHANNEL = "lulu_companion_messages_v2"
    private const val PREFS = "lulu_character_message_notifications"

    fun visibleConversation(conversationId: String): Boolean = UserInteractionPresenceStore.state.value.let {
        it.appVisible == true && it.conversationId == conversationId
    }

    fun channelId(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val legacy = manager.getNotificationChannel(LEGACY_CHANNEL)
        // Android cannot increase an existing channel's importance. An explicit
        // quiet/importance choice stays on legacy. Changing ONLY the ringtone
        // does not mean the user opted out of heads-up: migrate to HIGH and
        // preserve that chosen ringtone instead of silently staying DEFAULT.
        val userSetImportance = legacy != null && Build.VERSION.SDK_INT >= 29 &&
            legacy.hasUserSetImportance()
        val userSetSound = legacy != null && Build.VERSION.SDK_INT >= 30 &&
            legacy.hasUserSetSound()
        if (legacy != null && preserveLegacyChannel(
                legacy.importance, userSetImportance, userSetSound, legacy.sound != null
            )) return LEGACY_CHANNEL
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "角色聊天消息",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "角色聊天消息的顶部横幅与提示音"
            if (userSetSound && legacy != null) {
                setSound(legacy.sound, legacy.audioAttributes)
                enableVibration(legacy.shouldVibrate())
            } else enableVibration(true)
        })
        return CHANNEL
    }

    /** Only a deliberate importance/silence preference blocks the HIGH migration. */
    internal fun preserveLegacyChannel(
        importance: Int, userSetImportance: Boolean, userSetSound: Boolean,
        hasSound: Boolean,
    ): Boolean = importance != NotificationManager.IMPORTANCE_DEFAULT ||
        userSetImportance || (userSetSound && !hasSound)

    @Synchronized fun onSaved(context: Context, conversation: LuluConversation, message: LuluChatMessage) {
        if (message.sender != LuluChatMessage.Sender.Character || message.status != LuluChatMessage.Status.Sent ||
            conversation.parentConversationId != null || conversation.id.endsWith("-study-focus") ||
            conversation.groupChat?.muted == true ||
            conversation.groupChat?.ownerIncluded == false ||
            visibleConversation(conversation.id)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = channelId(context)
        val importance = manager.getNotificationChannel(channel)?.importance ?: NotificationManager.IMPORTANCE_NONE
        if (importance == NotificationManager.IMPORTANCE_NONE) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("last_message_${conversation.id}", null) == message.id) return
        val sameBatch = !message.replyBatchId.isNullOrBlank() &&
            prefs.getString("last_batch_${conversation.id}", null) == message.replyBatchId
        val actor = MigratedDomainStores.characters.get(message.authorCharacterId ?: conversation.characterId).displayName
        val title = conversation.groupChat?.name?.let { "$actor · $it" } ?: actor
        val text = qqForwardContextText(message.content)
            .replace(Regex("^\\[(?:游戏邀约|见面邀约)\\|[^]]+]\\s*"), "").take(600)
        // When another Lulu page is open, show a compact in-app preview too:
        // OEMs can suppress system heads-up banners even for HIGH channels.
        // Keep the normal system notification for the shade, tone and lockscreen.
        if (UserInteractionPresenceStore.state.value.appVisible == true &&
            importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            mutableInAppBanner.value = CharacterMessageBanner(
                message.id, conversation.id, title, text.take(180))
        }
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
        if (mutableInAppBanner.value?.conversationId == conversationId) dismissInApp()
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(conversationId))
    }

    private fun notificationId(conversationId: String) = "message-$conversationId".hashCode()
}
