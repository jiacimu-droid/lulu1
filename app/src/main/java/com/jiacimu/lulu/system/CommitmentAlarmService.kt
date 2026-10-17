package com.jiacimu.lulu.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jiacimu.lulu.MigrationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Deadline delivery and ringing have a lifecycle independent of yesterday's connected call. */
class CommitmentAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deliveries = mutableMapOf<String, Job>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "定时约定执行", NotificationManager.IMPORTANCE_LOW)
            .apply { setSound(null, null) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lulu:CommitmentAlarm")
            .apply { setReferenceCounted(false); acquire(165_000L) }
        // Stop before Android's three-minute short-service timeout, on older Android too.
        scope.launch { delay(160_000L); stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("alarm_id").orEmpty()
        val alarm = LuluAlarmSystem.find(this, id)
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MigrationActivity::class.java)
                .putExtra("open_conversation_id", alarm?.characterId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${alarm?.characterName ?: "角色"}的定时约定")
            .setContentText(alarm?.label ?: "正在处理到点任务")
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        else startForeground(NOTIFICATION_ID, notification)
        if (alarm == null) {
            if (deliveries.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        if (id !in deliveries) {
            // Start lazily so completion cannot remove an entry before it is inserted.
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { CommitmentAlarmDelivery.execute(applicationContext, id, "系统闹钟") }
                finally {
                    deliveries.remove(id)
                    if (deliveries.isEmpty()) stopSelf()
                }
            }
            deliveries[id] = job
            job.start()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "lulu_commitment_execution"
        const val NOTIFICATION_ID = 9274
    }
}
