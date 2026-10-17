package com.jiacimu.lulu.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.jiacimu.lulu.MigrationActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant

data class BackgroundCompanionStatus(val running: Boolean = false, val note: String = "尚未启动")

/** An explicit, user-visible foreground companion, independent of the Activity and microphone. */
object BackgroundCompanionControl {
    private const val PREFS = "lulu_background_companion_v1"
    private val mutableStatus = MutableStateFlow(BackgroundCompanionStatus())
    val status = mutableStatus.asStateFlow()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).commit()) { "后台陪伴设置未保存" }
        if (enabled) refresh(context) else {
            context.stopService(Intent(context, BackgroundCompanionService::class.java))
            report(false, "常驻已关闭，保留系统后台任务")
        }
    }

    /** Call from visible UI or a permitted boot broadcast; rejection leaves WorkManager intact. */
    fun refresh(context: Context) {
        if (!enabled(context) || status.value.running) return
        runCatching {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, BackgroundCompanionService::class.java))
        }.onFailure {
            report(false, "常驻启动受限，系统任务仍保留：${it.message.orEmpty().take(80)}")
        }
    }

    internal fun report(running: Boolean, note: String) {
        mutableStatus.value = BackgroundCompanionStatus(running, note)
    }
}

class BackgroundCompanionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runner: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackgroundCompanionControl.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BackgroundCompanionControl.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notice = notification(this, ongoing = true)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notice)
        } catch (error: Exception) {
            BackgroundCompanionControl.report(false, "常驻启动失败：${error.message.orEmpty().take(100)}")
            stopSelf()
            return START_NOT_STICKY
        }
        BackgroundCompanionControl.report(true, "常驻运行中")
        if (runner?.isActive != true) runner = scope.launch {
            try {
                initializeBackgroundRuntime(applicationContext)
                ProactivePerceptionRuntime.initialize(applicationContext)
                ProactivePerceptionScheduler.schedule(applicationContext)
                while (isActive && BackgroundCompanionControl.enabled(applicationContext)) {
                    AutonomousPerceptionAttempts.recoverInterrupted(applicationContext)
                    val due = ProactivePerceptionRuntime.nextDueAt(applicationContext)
                    val hasEnabledRole = MigratedDomainStores.chat.conversations.value.any {
                        it.parentConversationId == null && it.groupChat == null &&
                            !it.id.endsWith("-study-focus") && ProactivePerceptionPolicyStore.get(it.characterId).enabled
                    }
                    if (!hasEnabledRole) {
                        BackgroundCompanionControl.report(false, "角色主动感知已关闭")
                        stopSelf()
                        break
                    }
                    if (due != null && !due.isAfter(Instant.now())) {
                        if (networkAvailable()) perceiveDue() else {
                            BackgroundCompanionControl.report(true, "等待网络恢复")
                        }
                    }
                    ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
                    delay(30_000L)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                BackgroundCompanionControl.report(false, "常驻中断，保留系统重试：${error.message.orEmpty().take(90)}")
                runCatching { ProactivePerceptionScheduler.enqueueWake(applicationContext, restoreSchedule = true) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun perceiveDue() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lulu:BackgroundPerception")
            .apply { setReferenceCounted(false); acquire(9 * 60_000L) }
        try {
            BackgroundCompanionControl.report(true, "正在感知")
            // The existing runtime mutex/due anchor arbitrate this service and queued workers.
            // No forced pulse, no extra model call on every thirty-second local check.
            withTimeout(8 * 60_000L) {
                ProactivePerceptionRuntime.runDueCycle(applicationContext, "角色时间间隔")
            }
            BackgroundCompanionControl.report(true, "常驻运行中")
        } catch (error: CancellationException) {
            // A local model deadline is recoverable; destruction cancels the whole service.
            if (!scope.isActive) throw error
            BackgroundCompanionControl.report(true, "感知超时，等待后续调度")
        } catch (error: Exception) {
            BackgroundCompanionControl.report(true, "感知未完成：${error.message.orEmpty().take(95)}")
        } finally {
            releaseWakeLock()
            ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the UI away does not disable the user's persistent companion preference.
        ProactivePerceptionScheduler.scheduleNextDue(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        val previous = BackgroundCompanionControl.status.value
        if (previous.running) BackgroundCompanionControl.report(false, "常驻已停止，保留系统后台任务")
        super.onDestroy()
    }

    @Synchronized private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "lulu_background_companion"
        private const val NOTIFICATION_ID = 8421
        private const val ACTION_STOP = "com.jiacimu.lulu.STOP_BACKGROUND_COMPANION"

        internal fun workerForegroundInfo(context: Context): ForegroundInfo =
            ForegroundInfo(8422, notification(context, ongoing = false))

        private fun notification(context: Context, ongoing: Boolean): Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "后台陪伴运行", NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null) })
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MigrationActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.jiacimu.lulu.R.drawable.lulu_exact_icon)
                .setContentTitle(if (ongoing) "后台陪伴已开启" else "正在处理角色消息")
                .setContentText("不打开应用，也按角色设定感知与联系")
                .setContentIntent(open).setOngoing(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
            if (ongoing) builder.addAction(0, "关闭常驻", PendingIntent.getService(context, NOTIFICATION_ID,
                Intent(context, BackgroundCompanionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return builder.build()
        }
    }
}
