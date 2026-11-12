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

    /**
     * Owner taps a real notification; Lulu NEVER launches a checkout/payment
     * activity in the owner's foreground on the role's behalf.
     * Android does not promise to move an activity from the virtual display
     * to display 0. The merchant app may reopen at its normal start page.
     */
    fun showPaymentHandoff(context: Context, merchantPackage: String) {
        if (merchantPackage.isBlank() ||
            LuluScreenAwareness.isExcluded(context, merchantPackage)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val channel = "lulu_shopping_handoff_v1"
        manager.createNotificationChannel(NotificationChannel(
            channel, "购物付款接管", NotificationManager.IMPORTANCE_DEFAULT))
        val merchantIntent = context.packageManager.getLaunchIntentForPackage(merchantPackage)
        val safeIntent = merchantIntent
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        safeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val pending = PendingIntent.getActivity(context, 47232, safeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("购物已到需要你操作的付款阶段")
            .setContentText("角色已暂停。点此打开商家 App；若未恢复付款页，请到待付款订单查看。")
            .setStyle(Notification.BigTextStyle().bigText(
                "独立后台屏的支付页面无法保证直接迁移到主屏。请你亲自核对商品、金额、地址和付款方式；" +
                    "点此打开商家 App，必要时到待付款订单继续。角色没有替你付款。"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(47232, notification)
    }

    fun clear(context: Context) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancel(ID)
        // STOP invalidates any prior shopping handoff. Never leave a stale
        // "pay here" invitation after the owner has explicitly stopped.
        notifications.cancel(47232)
    }
}

internal class LuluBackgroundPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LuluBackgroundPhoneNotice.ACTION_STOP) return
        val completion = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { LuluBackgroundPhoneSession.stop(context) }
            finally { completion.finish() }
        }
    }
}
