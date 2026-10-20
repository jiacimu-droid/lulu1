package com.jiacimu.lulu.system

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jiacimu.lulu.data.CommitmentAlarmTiming
import com.jiacimu.lulu.data.CommitmentExecutor
import com.jiacimu.lulu.data.CommitmentTask
import com.jiacimu.lulu.data.CommitmentTaskStatus
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.ProactiveIncomingCallStore
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Native alarm and WorkManager share one durable token. Neither route needs an open Activity. */
internal object CommitmentAlarmDelivery {
    private fun backupName(id: String) = "lulu-alarm-backup:$id"

    fun scheduleBackup(context: Context, alarm: LuluAlarm) {
        runCatching {
            val delay = Duration.between(Instant.now(), alarm.triggerAt.plusSeconds(15)).toMillis().coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<CommitmentAlarmWorker>()
                .setInputData(workDataOf("alarm_id" to alarm.id))
                .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(backupName(alarm.id), ExistingWorkPolicy.KEEP, request)
        }.onFailure { android.util.Log.w("LuluAlarm", "Backup registration failed: ${alarm.id}", it) }
    }

    fun cancelBackup(context: Context, id: String) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(backupName(id))
            WorkManager.getInstance(context).cancelUniqueWork("lulu-alarm-now:$id")
        }
    }

    fun enqueueNow(context: Context, id: String) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<CommitmentAlarmWorker>()
                .setInputData(workDataOf("alarm_id" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork("lulu-alarm-now:$id", ExistingWorkPolicy.KEEP, request)
        }.onFailure { android.util.Log.e("LuluAlarm", "Alarm hand-off failed: $id", it) }
    }

    suspend fun execute(context: Context, id: String, source: String) {
        val now = Instant.now()
        CommitmentTaskStore.initialize(context)
        val hadTask = CommitmentTaskStore.snapshot().any { it.linkedAlarmId == id }
        val alarm = LuluAlarmSystem.takeForDelivery(context, id, now) ?: return
        val task = CommitmentTaskStore.claimAlarmExecution(id, alarm.triggerAt, now, source)
        // Canceled, duplicate, early and very late deliveries cannot produce an incoming call.
        if (task == null && (hadTask || alarm.commitmentTaskId != null)) return
        if (!CommitmentAlarmTiming.due(alarm.triggerAt, now)) return
        if (task == null) {
            if (!alarm.silentCallback) showLocalAlarm(context, alarm)
            return
        }
        try {
            withTimeout(150_000L) {
                withContext(Dispatchers.IO) {
                    // Same persisted repositories as the foreground app. No Activity or model
                    // request is needed to offer the actual incoming call and local ringtone.
                    LuluAppPreferencesStore.initialize(context)
                    UserProfileContext.initialize(context)
                    DigitalLifeProfileStore.initialize(context)
                    LuluRepositories.initialize(context)
                    LuluRepositories.lexicon.initialize(context)
                    MigratedDomainStores.initialize(context)
                    SharedExperienceTimeline.initialize(context)
                    LuluAlarmSystem.initialize(context.applicationContext)
                    if (!isCurrent(task)) return@withContext
                    val shown = when {
                        alarm.silentCallback -> NotificationManagerCompat.from(context).areNotificationsEnabled()
                        task.deliveryAction == "start_call" || task.deliveryAction == "alarm_only" -> false
                        else -> showLocalAlarm(context, alarm)
                    }
                    CommitmentExecutor.onAlarm(context, task, alarm.characterName, now, shown)
                }
                // Keep the process alive while this real incoming call rings. Also reconcile
                // timeout without relying on a foreground Compose timer to mark a missed call.
                val pending = ProactiveIncomingCallStore.pending.value
                    ?.takeIf { it.commitmentTaskId == task.id && it.active() }
                if (pending != null) {
                    withTimeoutOrNull(Duration.between(Instant.now(), pending.expiresAt).toMillis().coerceAtLeast(1L)) {
                        combine(ProactiveIncomingCallStore.pending, CommitmentTaskStore.tasks) { call, tasks ->
                            call != pending || tasks.none { it.id == task.id && it.status == CommitmentTaskStatus.WaitingForFeedback }
                        }.first { it }
                    }
                    if (CommitmentTaskStore.snapshot().none {
                            it.id == task.id && it.status == CommitmentTaskStatus.WaitingForFeedback
                        }) ProactiveIncomingCallStore.clear(pending)
                    ProactiveIncomingCallStore.reconcileExpired()
                }
            }
        } catch (error: Exception) {
            if (isCurrent(task)) CommitmentTaskStore.update(task.id) { old -> old.copy(
                status = CommitmentTaskStatus.Blocked,
                lastActionResult = "$source 执行中断：${error.message.orEmpty().take(140)}；未确认目标完成",
            ) }
            if (error is CancellationException) throw error
        } finally {
            CommitmentTaskStore.finishAlarmExecution(task.id, task.executionToken)
        }
    }

    private fun isCurrent(task: CommitmentTask): Boolean = CommitmentTaskStore.snapshot().any {
        it.id == task.id && it.status == CommitmentTaskStatus.Running && it.executionToken == task.executionToken
    }

    private fun showLocalAlarm(context: Context, alarm: LuluAlarm): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() && runCatching {
            LuluAlarmSystem.showAlarmNotification(context, alarm.id, alarm.characterId, alarm.characterName, alarm.label)
        }.isSuccess
}

class CommitmentAlarmWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        // Expedited WorkManager uses an FGS only on Android versions before Android 12.
        val channel = "lulu_commitment_execution"
        applicationContext.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(channel, "定时约定执行", android.app.NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) })
        return androidx.work.ForegroundInfo(9275, androidx.core.app.NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("正在处理定时约定")
            .setOngoing(true).setSilent(true).build())
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString("alarm_id").orEmpty()
        if (id.isBlank()) return Result.failure()
        return try {
            CommitmentAlarmDelivery.execute(applicationContext, id, "系统任务补偿")
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.e("LuluAlarm", "Persistent alarm delivery failed: $id", error)
            Result.failure()
        }
    }
}
