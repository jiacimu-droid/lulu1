package com.jiacimu.lulu.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * User-visible active session notification with a real STOP control.
 * No screen data or pairing keys enter notifications.
 */
internal object LuluBackgroundPhoneNotice {
    private const val CHANNEL = "lulu_background_phone_v1"
    private const val ID = 47231
    const val ACTION_STOP = "com.jiacimu.lulu.BACKGROUND_PHONE_STOP"

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled()) {
            "请先允许露露机通知，以便显示可随时停止的后台手机操作状态"
        }
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "角色后台手机操作", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, LuluBackgroundPhoneStopReceiver::class.java)
            .setAction(ACTION_STOP)
        val stop = PendingIntent.getBroadcast(context, 47231, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("角色正在后台操作手机")
            .setContentText("使用独立虚拟屏幕，不占用你的主屏")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "停止操作", stop).build())
            .build()
        manager.notify(ID, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}

internal class LuluBackgroundPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LuluBackgroundPhoneNotice.ACTION_STOP) return
        val completion = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { LuluBackgroundPhoneSession.stop() }
            finally { completion.finish() }
        }
    }
}
