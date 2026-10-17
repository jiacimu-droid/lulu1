package com.jiacimu.lulu.data

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.jiacimu.lulu.system.CommitmentAlarmDelivery
import com.jiacimu.lulu.system.CommitmentAlarmService
import com.jiacimu.lulu.system.LuluAlarmReceiver
import com.jiacimu.lulu.system.LuluAlarmSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CommitmentAlarmRecoveryTest {
    private lateinit var context: Context

    @Before fun before() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        detach(CommitmentTaskStore, "prefs")
        @Suppress("UNCHECKED_CAST")
        (CommitmentTaskStore.javaClass.getDeclaredField("inFlight").apply { isAccessible = true }
            .get(CommitmentTaskStore) as MutableMap<String, String>).clear()
        context.getSharedPreferences("lulu_commitment_tasks", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("lulu_alarm_store", Context.MODE_PRIVATE).edit().clear().commit()
        ProactiveIncomingCallStore.clear()
        detach(ProactiveIncomingCallStore, "prefs")
        ProactiveIncomingCallStore.initialize(context)
        CommitmentTaskStore.initialize(context)
        LuluAlarmSystem.initialize(context)
    }

    @After fun after() {
        ProactiveIncomingCallStore.clear()
        CommitmentTaskStore.snapshot().forEach {
            CommitmentTaskStore.finishAlarmExecution(it.id, it.executionToken)
            CommitmentTaskStore.remove(it.id)
        }
        detach(CommitmentTaskStore, "prefs")
        detach(ProactiveIncomingCallStore, "prefs")
        MemoryTestEnvironment.release()
    }

    @Test fun openingActivityAtDeadlineKeepsBothUnclaimedAndLiveExecutions() {
        val now = Instant.now()
        val task = saveTask(now.minusSeconds(1))
        writeAlarm(task)
        LuluAlarmSystem.restoreFutureAlarms(context)
        LuluAlarmSystem.list()
        CommitmentTaskStore.reconcileAfterRestart(now)
        assertEquals(CommitmentTaskStatus.Scheduled, current(task).status)
        assertNotNull(LuluAlarmSystem.find(context, "alarm"))
        val claimed = CommitmentTaskStore.claimAlarmExecution("alarm", task.dueAt, now)!!
        // Metadata syncing may increase revision while the same execution is still alive.
        CommitmentTaskStore.update(task.id) { it.copy(lexiconEntryId = "promise") }
        CommitmentTaskStore.reconcileAfterRestart(now.plusSeconds(1))
        assertEquals(CommitmentTaskStatus.Running, current(task).status)
        assertEquals(claimed.executionToken, current(task).executionToken)
        assertNull(CommitmentTaskStore.claimAlarmExecution("alarm", task.dueAt, now))
    }

    @Test fun actualInterruptedClaimIsBlockedWithoutRedialing() {
        val now = Instant.now()
        val task = saveTask(now)
        val claimed = CommitmentTaskStore.claimAlarmExecution("alarm", now, now)!!
        CommitmentTaskStore.finishAlarmExecution(task.id, claimed.executionToken)
        // Reload the real persisted task as a new process would.
        detach(CommitmentTaskStore, "prefs")
        CommitmentTaskStore.initialize(context)
        CommitmentTaskStore.reconcileAfterRestart(now.plusSeconds(5))
        assertEquals(CommitmentTaskStatus.Blocked, current(task).status)
        assertNull(current(task).linkedAlarmId)
    }

    @Test fun coldBroadcastStartsIndependentServiceWithoutActivityBootstrap() {
        val task = saveTask(Instant.now().minusSeconds(1))
        writeAlarm(task)
        detach(LuluAlarmSystem, "context")
        LuluAlarmReceiver().onReceive(context, Intent("app.lulu.ALARM_TRIGGER").putExtra("alarm_id", "alarm"))
        val started = requireNotNull(shadowOf(context as android.app.Application).nextStartedService)
        assertEquals(CommitmentAlarmService::class.java.name, started.component!!.className)
        assertEquals("alarm", started.getStringExtra("alarm_id"))
        assertEquals(CommitmentTaskStatus.Scheduled, current(task).status)
        LuluAlarmSystem.initialize(context)
    }

    @Test fun alarmAndWorkerCanConsumeOnlyOneDurableToken() {
        val now = Instant.now()
        val task = saveTask(now.minusSeconds(2))
        writeAlarm(task)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { pool.submit<Boolean> {
                LuluAlarmSystem.takeForDelivery(context, "alarm", now) != null
            } }.map { it.get() }
            assertEquals(1, attempts.count { it })
        } finally { pool.shutdownNow() }
        assertNotNull(CommitmentTaskStore.claimAlarmExecution("alarm", task.dueAt, now))
        assertNull(CommitmentTaskStore.claimAlarmExecution("alarm", task.dueAt, now))
    }

    @Test fun lateHourOldCallbacksNeverBecomeANewWakeCall() = runBlocking {
        val task = saveTask(Instant.now().minusSeconds(86 * 60L))
        writeAlarm(task)
        CommitmentAlarmDelivery.execute(context, "alarm", "系统任务补偿")
        assertEquals(CommitmentTaskStatus.Expired, current(task).status)
        assertNotNull(current(task).lastAlarmReceivedAt)
        assertNull(ProactiveIncomingCallStore.pending.value)
    }

    @Test fun recoveryLeavesBrieflyDueRetryButExpiresOldMissedDeadline() {
        val now = Instant.now()
        val task = saveTask(now.minusSeconds(3600))
        CommitmentTaskStore.update(task.id) { it.copy(status = CommitmentTaskStatus.WaitingForFeedback,
            nextCheckAt = now.minusSeconds(1)) }
        CommitmentTaskStore.reconcileAfterRestart(now)
        assertEquals(CommitmentTaskStatus.WaitingForFeedback, current(task).status)
        assertNotNull(CommitmentTaskStore.claimAlarmExecution("alarm", now.minusSeconds(1), now))
        val expired = CommitmentTaskStore.save(CommitmentTask(characterId = "other", goal = "09:30 叫醒",
            status = CommitmentTaskStatus.Scheduled, dueAt = now.minusSeconds(3600)))
        CommitmentTaskStore.reconcileAfterRestart(now)
        assertEquals(CommitmentTaskStatus.Expired, current(expired).status)
    }

    @Test fun cancelledAlarmCannotBeHandedToForegroundService() {
        val task = saveTask(Instant.now().minusSeconds(1))
        writeAlarm(task)
        CommitmentTaskStore.cancel(task.id, "取消叫醒")
        LuluAlarmReceiver().onReceive(context, Intent().putExtra("alarm_id", "alarm"))
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
        assertNull(LuluAlarmSystem.find(context, "alarm"))
    }

    @Test fun futureAlarmAndSilentReviewSurviveRegistrationRestore() {
        val due = Instant.now().plusSeconds(3600)
        val alarm = LuluAlarmSystem.create("role", "角色", due, "叫醒结果复查", silentCallback = true).getOrThrow()
        LuluAlarmSystem.initialize(context)
        assertEquals(alarm.id, LuluAlarmSystem.list().single().id)
        assertEquals(due.toEpochMilli(), context.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime)
        assertEquals(1, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test fun dispatchEvidenceAndLocalOnlyActionSurviveRestart() {
        val now = Instant.now()
        val task = saveTask(now.minusSeconds(15))
        CommitmentTaskStore.update(task.id) { it.copy(deliveryAction = "alarm_only") }
        val claimed = CommitmentTaskStore.claimAlarmExecution("alarm", task.dueAt, now, "系统任务补偿")!!
        val restored = decodeCommitmentTasks(encodeCommitmentTasks(listOf(claimed))).single()
        assertEquals("alarm_only", restored.deliveryAction)
        assertEquals(now, restored.lastAlarmReceivedAt)
        assertEquals(task.dueAt, restored.lastAlarmScheduledAt)
        assertEquals("系统任务补偿", restored.lastAlarmDeliverySource)
        assertEquals("alarm", restored.executionToken)
    }

    @Test(timeout = 30_000) fun coldDeliveryOffersOneRealCallAndStopsOnDecline() = runBlocking {
        detach(MigratedDomainStores.characters, "prefs")
        detach(MigratedDomainStores.chat, "prefs")
        MigratedDomainStores.initialize(context)
        val role = MigratedDomainStores.characters.create("冷启动角色")
        MigratedDomainStores.characters.update(role.copy(contactPolicy = role.contactPolicy.copy(proactiveCallsEnabled = true)))
        val task = saveTask(Instant.now().minusSeconds(1), role.characterId)
        writeAlarm(task)
        val execution = async(Dispatchers.Default) { CommitmentAlarmDelivery.execute(context, "alarm", "系统闹钟") }
        withTimeout(15_000) {
            while (ProactiveIncomingCallStore.pending.value == null || current(task).status != CommitmentTaskStatus.WaitingForFeedback) delay(10)
        }
        val call = ProactiveIncomingCallStore.pending.value!!
        assertEquals(task.id, call.commitmentTaskId)
        assertEquals(role.characterId, call.characterId)
        assertEquals(1, current(task).attemptCount)
        // A concurrent fallback sees the consumed token and cannot offer another call.
        CommitmentAlarmDelivery.execute(context, "alarm", "系统任务补偿")
        assertEquals(call, ProactiveIncomingCallStore.pending.value)
        ProactiveIncomingCallStore.respond(call, answered = false)
        execution.await()
        assertNull(ProactiveIncomingCallStore.pending.value)
        assertEquals(CommitmentTaskStatus.Blocked, current(task).status)
        assertNull(current(task).linkedAlarmId)
    }

    private fun saveTask(due: Instant, role: String = "role") = CommitmentTaskStore.save(CommitmentTask(
        characterId = role, goal = "09:30 打电话叫醒", status = CommitmentTaskStatus.Scheduled,
        dueAt = due, nextCheckAt = due, linkedAlarmId = "alarm", deliveryAction = "start_call"))

    private fun current(task: CommitmentTask) = CommitmentTaskStore.snapshot().single { it.id == task.id }

    private fun writeAlarm(task: CommitmentTask) {
        val alarm = JSONObject().put("id", "alarm").put("characterId", task.characterId)
            .put("characterName", "角色").put("triggerAt", task.dueAt.toString())
            .put("label", task.goal).put("commitmentTaskId", task.id)
        context.getSharedPreferences("lulu_alarm_store", Context.MODE_PRIVATE).edit()
            .putString("alarms_v1", JSONArray().put(alarm).toString()).commit()
    }

    private fun detach(target: Any, name: String) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, null)
}
