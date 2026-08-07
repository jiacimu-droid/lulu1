package com.jiacimu.lulu.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Notification history is a passive context source; new notifications never wake the AI runtime. */
class LuluNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected.value = true
    }

    override fun onListenerDisconnected() {
        connected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.packageName == packageName) return
        val extras = item.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val snapshot = LuluNotificationSnapshot(
            packageName = item.packageName,
            title = title,
            text = text,
            postedAt = Instant.ofEpochMilli(item.postTime),
        )
        recent.value = (listOf(snapshot) + recent.value)
            .distinctBy { value -> "${value.packageName}|${value.title}|${value.text}|${value.postedAt}" }
            .take(80)
    }

    companion object {
        private val connected = MutableStateFlow(false)
        private val recent = MutableStateFlow<List<LuluNotificationSnapshot>>(emptyList())
        val isConnected: StateFlow<Boolean> = connected.asStateFlow()
        val notifications: StateFlow<List<LuluNotificationSnapshot>> = recent.asStateFlow()
    }
}

data class LuluNotificationSnapshot(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Instant,
)
