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
import com.jiacimu.lulu.data.isOutcomeReview
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
        val hadTask = CommitmentTaskStore.snapshot().any { task ->
            task.linkedAlarmId == id || task.dailyNextAlarmId == id ||
                task.dailyAttempts.any { it.reviewAlarmId == id }
        }
        val alarm = LuluAlarmSystem.takeForDelivery(context, id, now) ?: return
        val carry = CommitmentTaskStore.claimPastDailyReview(id, alarm.triggerAt, now)
        if (carry != null) {
            val (owner, old) = carry
            withContext(Dispatchers.IO) {
                LuluRepositories.initialize(context)
                MigratedDomainStores.initialize(context)
                SharedExperienceTimeline.initialize(context)
                val details = "此前每日约定的独立结果复查；原执行时刻=${old.dueAt}；" +
                    "原状态=${old.status}；上次回执=${old.result.take(250)}；" +
                    "该轮效果尚不能凭旧通知或沉默判断。"
                val evidenceId = "commitment:prior-review:${owner.id}:$id"
                SharedExperienceTimeline.record(evidenceId, owner.characterId,
                    "跨日约定结果复查", "系统观察", details, occurredAt = now,
                    source = "commitment-follow-up",
                    evidenceKind = com.jiacimu.lulu.data.EventEvidenceKind.Observation,
                    triggerExtraction = false)
                com.jiacimu.lulu.data.CompanionOnlineStore.initialize(context)
                com.jiacimu.lulu.data.CompanionOnlineStore.wakeCharacter(
                    owner.characterId,
                    com.jiacimu.lulu.data.CompanionOnlineReason.ScheduledCommitment,
                    details + "今天的独立每日安排不受此回执影响；请本人结合有效证据自行判断。",
                    perceiveNow = true, now = now)
            }
            return
        }
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
                    // The durable task already retains the full dated history.
                    // Record only the newly carried occurrence here; replaying
                    // every historical day on each delivery grows quadratically.
                    task.dailyAttempts.takeLast(1).forEach { earlier ->
                        val eid = "commitment:daily-history:${task.id}:${earlier.dueAt.toEpochMilli()}"
                        if (SharedExperienceTimeline.eventsByIds(task.characterId, listOf(eid)).isEmpty())
                            SharedExperienceTimeline.record(eid, task.characterId,
                                "每日约定历史", "系统事实",
                                "原时间=${earlier.dueAt}；状态=${earlier.status}；结果=${earlier.result}",
                                source = "commitment-occurrence",
                                evidenceKind = com.jiacimu.lulu.data.EventEvidenceKind.ToolResult,
                                triggerExtraction = false)
                    }
                    if (!isCurrent(task)) return@withContext
                    val shown = when {
                        alarm.silentCallback -> NotificationManagerCompat.from(context).areNotificationsEnabled()
                        task.deliveryAction == "start_call" || task.deliveryAction == "alarm_only" -> false
                        else -> showLocalAlarm(context, alarm)
                    }
                    CommitmentExecutor.onAlarm(context, task, alarm.characterName, now, shown)
                }
            }
            // The 150-second watchdog covers dispatch, not the time a real
            // incoming call is ringing. A cold start plus the character's
            // bounded decision may already use part of that budget; awaiting
            // the full two-minute call here must not falsely mark dispatch
            // as timed out. Preserve this separately bounded outcome wait.
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
            // Keep the real alarm sound alive while this foreground service /
            // expedited worker still owns the dispatch. A quick model decision
            // must not end the process and cut off the 30-second local cue.
            val remainingRingMs = LuluAudibleWakeRinger.remainingMs(task.id)
            if (remainingRingMs > 0L) kotlinx.coroutines.delay(remainingRingMs)
        } catch (error: Exception) {
            if (isCurrent(task)) CommitmentTaskStore.update(task.id) { old -> old.copy(
                status = CommitmentTaskStatus.Blocked,
                lastActionResult = "$source 执行中断：${error.message.orEmpty().take(140)}；未确认目标完成",
            ) }
            if (error is CancellationException) throw error
        } finally {
            LuluAudibleWakeRinger.stop(task.id)
            // Each already-accepted daily occurrence is independently scheduled.
            // Tomorrow never changes today's unknown outcome into completed.
            CommitmentTaskStore.advanceDailyAfterAttempt(context, task.id, task.executionToken,
                outcomeReview = task.isOutcomeReview())
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
