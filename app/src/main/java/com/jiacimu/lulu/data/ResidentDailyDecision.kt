package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.ai.isDefinitelyRejectedModelRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.ZoneId
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Native people get a single bounded model decision per local day, not a
 * replica of the owner's device/screen/perception route. The model chooses
 * optional leisure, one social gesture and private mood; deterministic duty
 * slots continue to execute from confirmed local state with no API call.
 */
internal object ResidentDailyDecision {
    const val SCOPE = "resident_daily"
    private const val PREFS = "lulu_resident_daily_decisions_v1"
    private const val WORK_PERIODIC = "lulu-resident-daily-decision-v1"
    private const val WORK_NOW = "lulu-resident-daily-initial-v1"
    private const val WORK_FIXED = "lulu-resident-daily-at-local-time-v2"
    /** Shared local planning hour. WorkManager is durable but Android may defer its exact delivery. */
    internal val DAILY_PLANNING_TIME: LocalTime = LocalTime.of(8, 0)
    private val dayRequests = KeyedWorkMutex()

    data class DayRecovery(val day: String, val phase: String, val revision: Int) {
        val label: String get() = when (phase) {
            "uncertain", "requesting" -> "请求结果未知"
            "repair_requesting" -> "结构补正中断或仍在进行"
            "invalid_output" -> "返回内容未能形成有效决定"
            "definite_rejected" -> "请求已被接口拒绝"
            else -> phase
        }
    }

    fun dayRecovery(context: Context, nativeId: String, now: Instant = Instant.now()): DayRecovery? {
        val p = prefs(context)
        val day = date(now)
        if (p.getString("attempt:$nativeId", "") != day || p.contains(dayDecisionKey(nativeId, day))) return null
        val phase = p.getString(dayPhaseKey(nativeId, day), "").orEmpty()
        if (phase !in setOf("uncertain", "requesting", "repair_requesting", "invalid_output", "definite_rejected")) return null
        return DayRecovery(day, phase, p.getInt("manual-retry:$nativeId:$day", 0))
    }

    /** Explicit owner action only. Old unknown requests are never auto-bought.
     * Same lock as the ordinary daily worker, so an in-flight response wins
     * before this exact displayed recovery snapshot is compared again. */
    suspend fun retryDayDecision(context: Context, nativeId: String, expected: DayRecovery,
        clock: () -> Instant = Instant::now): Boolean = dayRequests.withLock("$nativeId:${expected.day}") {
        val now = clock() // Re-read after the lock: confirmation may have crossed midnight.
        if (date(now) != expected.day || dayRecovery(context, nativeId, now) != expected) return@withLock false
        val native = DigitalWorldExpansionStore.residents().firstOrNull { it.id == nativeId }
            ?: return@withLock false
        ScopedModelSelections.initialize(context.applicationContext)
        if (runCatching { ScopedModelSelections.resolveConnection(SCOPE) }.isFailure) return@withLock false
        val p = prefs(context)
        val archive = JSONObject().put("day", expected.day).put("phase", expected.phase)
            .put("revision", expected.revision).put("authorizedAt", now.toString())
        for (key in listOf("invalid:$nativeId:${expected.day}", "invalid-reason:$nativeId:${expected.day}",
                "last-error:$nativeId:${expected.day}")) {
            p.getString(key, null)?.let { archive.put(key, it) }
        }
        check(p.edit().putString("manual-retry-history:$nativeId:${expected.day}:${expected.revision}", archive.toString())
            .putInt("manual-retry:$nativeId:${expected.day}", expected.revision + 1)
            .remove("attempt:$nativeId").commit()) { "原请求恢复依据未保存，未重新请求" }
        runOnceLocked(context, native, now)
    }

    /** A saved daily decision may have succeeded in arranging the day while
     * one or more independent immediate actions did not complete. Only the
     * ORIGINAL saved choice may be replayed; never buy a second decision here.
     * A snapshot is bound to the local date and recovery counter so a stale
     * confirmation cannot resume another day's resident or an in-flight turn. */
    data class SavedDayRecovery(val nativeId: String, val day: String,
        val phase: String, val recoveries: Int)

    /** Read-only status for the existing resident card; no new task store. */
    fun hasPartialDayActions(context: Context, nativeId: String,
        now: Instant = Instant.now()): Boolean {
        val p = prefs(context)
        val day = date(now)
        return p.getString("attempt:$nativeId", "") == day &&
            p.contains(dayDecisionKey(nativeId, day)) &&
            p.getString(dayPhaseKey(nativeId, day), "") == "partial_side_steps"
    }

    fun savedDayRecovery(context: Context, nativeId: String,
        now: Instant = Instant.now()): SavedDayRecovery? {
        val p = prefs(context)
        val day = date(now)
        if (p.getString("attempt:$nativeId", "") != day ||
            !p.contains(dayDecisionKey(nativeId, day)) ||
            p.getString(dayPhaseKey(nativeId, day), "") != "partial_side_steps" ||
            p.getInt(dayRecoveriesKey(nativeId, day), 0) < MAX_SAVED_DECISION_RECOVERIES ||
            p.getInt("saved-day-manual-resume:$nativeId:$day", 0) > 0) return null
        return SavedDayRecovery(nativeId, day, "partial_side_steps",
            p.getInt(dayRecoveriesKey(nativeId, day), 0))
    }

    /** One explicit owner grant after bounded automatic recovery. Consume it
     * durably before replay, retaining every existing ToolRouter request ID.
     * An unknown-charge model request is never reissued by this route. */
    suspend fun resumeSavedDayDecision(context: Context, expected: SavedDayRecovery,
        clock: () -> Instant = Instant::now): Boolean =
        dayRequests.withLock("${expected.nativeId}:${expected.day}") {
            val now = clock()
            if (date(now) != expected.day ||
                savedDayRecovery(context, expected.nativeId, now) != expected)
                return@withLock false
            val native = DigitalWorldExpansionStore.residents()
                .firstOrNull { it.id == expected.nativeId } ?: return@withLock false
            val p = prefs(context)
            check(p.edit()
                .putInt("saved-day-manual-resume:${expected.nativeId}:${expected.day}", 1)
                .putInt(dayRecoveriesKey(expected.nativeId, expected.day),
                    MAX_SAVED_DECISION_RECOVERIES - 1).commit()) {
                "每日已保存决定的人工续接授权未持久化"
            }
            runOnceLocked(context, native, now)
        }

    private val dueRequests = KeyedWorkMutex()
    data class DueRecovery(val planId: String, val characterId: String, val dueAt: Instant,
        val phase: String, val revision: Int)

    fun dueRecovery(context: Context, plan: RoleDayPlan): DueRecovery? {
        val live = RoleDayScheduleStore.plans(context, plan.characterId).firstOrNull {
            it.id == plan.id && it.dueAt == plan.dueAt && it.status in setOf("planned", "due")
        } ?: return null
        val p = prefs(context)
        val key = dueAttemptKey(live)
        if (!p.getBoolean(key, false) || p.contains("due-decision:$key")) return null
        val phase = p.getString("due-phase:$key", "").orEmpty()
        if (phase !in setOf("uncertain", "requesting", "repair_requesting", "invalid_output", "definite_rejected")) return null
        return DueRecovery(live.id, live.characterId, live.dueAt, phase, p.getInt("due-manual-retry:$key", 0))
    }


    /** Explicitly replay a durable choice, never buy a new model response. */
    data class SavedDueRecovery(val planId: String, val characterId: String,
        val dueAt: Instant, val phase: String, val recoveries: Int)

    fun savedDueRecovery(context: Context, plan: RoleDayPlan): SavedDueRecovery? {
        val live = RoleDayScheduleStore.plans(context, plan.characterId)
            .firstOrNull { it.id == plan.id && it.dueAt == plan.dueAt } ?: return null
        val p = prefs(context)
        val key = dueAttemptKey(live)
        val raw = p.getString("due-decision:$key", null) ?: return null
        val choice = runCatching { JSONObject(raw).optString("choice") }.getOrNull()
            ?: return null
        if (choice !in setOf("execute", "reschedule", "cancel", "wait")) return null
        if (live.status !in setOf("planned", "due", "completed") ||
            (live.status == "completed" && (live.repeat != "once" ||
                choice != "execute" || live.resultEvidenceId.isBlank() ||
                SharedExperienceTimeline.eventsByIds(live.characterId,
                    listOf(live.resultEvidenceId)).none { it.id == live.resultEvidenceId })))
            return null
        val phase = p.getString("due-phase:$key", "").orEmpty()
        val recoveries = p.getInt("due-recoveries:$key", 0)
        if (phase !in setOf("decided", "needs_recovery", "partial_side_steps") ||
            recoveries < MAX_SAVED_DECISION_RECOVERIES ||
            p.getInt("due-manual-resume:$key", 0) > 0) return null
        return SavedDueRecovery(live.id, live.characterId, live.dueAt, phase, recoveries)
    }

    suspend fun resumeSavedDueDecision(context: Context, expected: SavedDueRecovery,
        clock: () -> Instant = Instant::now): Boolean {
        val key = "execute:${expected.planId}:${expected.dueAt}"
        return dueRequests.withLock(key) {
            val now = clock()
            val plan = RoleDayScheduleStore.plans(context, expected.characterId)
                .firstOrNull { it.id == expected.planId } ?: return@withLock false
            if (now.isBefore(plan.dueAt) || savedDueRecovery(context, plan) != expected ||
                ResidentCharacterBridge.nativeResident(plan.characterId) == null)
                return@withLock false
            val p = prefs(context)
            // Consume the single manual grant BEFORE any side effect. A crash
            // cannot create an unbounded replay loop.
            check(p.edit().putInt("due-manual-resume:$key", 1)
                .putInt("due-recoveries:$key", MAX_SAVED_DECISION_RECOVERIES - 1)
                .commit()) { "已保存决定的人工恢复授权未持久化" }
            runDuePlanLocked(context, plan, now)
        }
    }

    suspend fun retryDueDecision(context: Context, expected: DueRecovery,
        clock: () -> Instant = Instant::now): Boolean {
        val key = "execute:${expected.planId}:${expected.dueAt}"
        return dueRequests.withLock(key) {
            val now = clock()
            val plan = RoleDayScheduleStore.plans(context, expected.characterId)
                .firstOrNull { it.id == expected.planId } ?: return@withLock false
            if (now.isBefore(plan.dueAt) || dueRecovery(context, plan) != expected ||
                ResidentCharacterBridge.nativeResident(plan.characterId) == null) return@withLock false
            ScopedModelSelections.initialize(context.applicationContext)
            if (runCatching { ScopedModelSelections.resolveConnection(SCOPE) }.isFailure) return@withLock false
            val p = prefs(context)
            val archive = JSONObject().put("phase", expected.phase).put("dueAt", expected.dueAt.toString())
                .put("authorizedAt", now.toString()).put("revision", expected.revision)
            for (field in listOf("due-invalid:$key", "due-invalid-reason:$key", "due-last-error:$key"))
                p.getString(field, null)?.let { archive.put(field, it) }
            check(p.edit().putString("due-manual-history:$key:${expected.revision}", archive.toString())
                .putInt("due-manual-retry:$key", expected.revision + 1).remove(key).commit()) {
                "原到期请求恢复依据未保存，未重新请求"
            }
            runDuePlanLocked(context, plan, now)
        }
    }

    private const val MAX_AGENDA_ITEMS = 8
    private const val MAX_MESSAGE = 500
    private const val MAX_SAVED_DECISION_RECOVERIES = 2
    // A rejected, non-generated request may be retried ONCE only after the
    // configured connection changes. A timeout/5xx never gets this permission.
    private const val MAX_REJECTED_REQUEST_RETRIES = 1
    private fun dayDecisionKey(nativeId: String, day: String) = "decision:$nativeId:$day"
    private fun dayPhaseKey(nativeId: String, day: String) = "phase:$nativeId:$day"
    private fun dayRecoveriesKey(nativeId: String, day: String) = "recoveries:$nativeId:$day"

    private fun dueAttemptKey(plan: RoleDayPlan) = "execute:${plan.id}:${plan.dueAt}"

    /** Persist only a digest, never a credential, to detect an actual model
     * connection change after a definite HTTP rejection. */
    private fun connectionFingerprint(connection: ModelConnection): String {
        val bytes = listOf(connection.baseUrl, connection.model, connection.apiKey)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun canRetryRejected(p: android.content.SharedPreferences,
        phaseKey: String, retriesKey: String, fingerprintKey: String,
        fingerprint: String): Boolean =
        p.getString(phaseKey, "") == "definite_rejected" &&
            p.getInt(retriesKey, 0) < MAX_REJECTED_REQUEST_RETRIES &&
            p.getString(fingerprintKey, "")?.isNotBlank() == true &&
            p.getString(fingerprintKey, "") != fingerprint

    /** Claim + phase are one synchronized durable transaction. Two wake
     * workers must never both pass a stale Boolean read and buy a response. */
    @Synchronized
    private fun claimDueRequest(p: android.content.SharedPreferences, key: String,
        fingerprint: String): Boolean {
        val phase = "due-phase:$key"
        if (!p.getBoolean(key, false)) return p.edit()
            .putBoolean(key, true).putString(phase, "requesting").commit()
        if (!canRetryRejected(p, phase, "due-rejected-retries:$key",
                "due-rejected-fingerprint:$key", fingerprint)) return false
        return p.edit().putInt("due-rejected-retries:$key",
            p.getInt("due-rejected-retries:$key", 0) + 1)
            .putString(phase, "requesting").commit()
    }

    @Synchronized
    private fun claimDayRequest(context: Context, nativeId: String, at: Instant,
        fingerprint: String): Boolean {
        val p = prefs(context)
        val today = date(at)
        val phase = dayPhaseKey(nativeId, today)
        val retries = "rejected-retries:$nativeId:$today"
        val rejectedFingerprint = "rejected-fingerprint:$nativeId:$today"
        if (p.getString("attempt:$nativeId", "") != today) return p.edit()
            .putString("attempt:$nativeId", today)
            .putString(phase, "requesting")
            .putInt(retries, 0).remove(rejectedFingerprint).commit()
        if (!canRetryRejected(p, phase, retries, rejectedFingerprint, fingerprint)) return false
        return p.edit().putInt(retries, p.getInt(retries, 0) + 1)
            .putString(phase, "requesting").commit()
    }

    /** Read-only eligibility for the EXISTING wake and daily catch-up paths.
     * Changing a model does not revive an unknown-charge network request. */
    internal fun hasSafeDueRequestToRetry(context: Context, plan: RoleDayPlan): Boolean {
        if (plan.status !in setOf("planned", "due")) return false
        val p = prefs(context)
        val key = dueAttemptKey(plan)
        if (!p.getBoolean(key, false) || p.contains("due-decision:$key")) return false
        val selected = runCatching { ScopedModelSelections.resolveConnection(SCOPE) }.getOrNull()
            ?: return false
        return canRetryRejected(p, "due-phase:$key", "due-rejected-retries:$key",
            "due-rejected-fingerprint:$key", connectionFingerprint(selected))
    }

    internal fun hasSafeDayRequestToRetry(context: Context, nativeId: String,
        at: Instant): Boolean {
        val p = prefs(context)
        val today = date(at)
        if (p.getString("attempt:$nativeId", "") != today ||
            p.contains(dayDecisionKey(nativeId, today))) return false
        val selected = runCatching { ScopedModelSelections.resolveConnection(SCOPE) }.getOrNull()
            ?: return false
        return canRetryRejected(p, dayPhaseKey(nativeId, today),
            "rejected-retries:$nativeId:$today",
            "rejected-fingerprint:$nativeId:$today", connectionFingerprint(selected))
    }

    /** A wait is a decision to RECONSIDER, not permission to run the original
     * action later. Explicit review times belong to the resident's model.
     * Older saved choices may omit one: revisit on the existing next daily
     * planning slot instead of letting a due item disappear permanently.
     * An invalid explicitly requested time must never be silently replaced. */
    internal fun waitReviewAt(choice: JSONObject, now: Instant,
        zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val specified = choice.optString("reviewAt").trim()
        if (specified.isEmpty()) return nextPlanningTime(now.plusSeconds(61), zone)
        return runCatching { java.time.OffsetDateTime.parse(specified).toInstant() }
            .getOrNull()?.takeIf { it.isAfter(now.plusSeconds(60)) }
    }
    internal fun hasSavedDueToResume(context: Context, plan: RoleDayPlan): Boolean {
        // The caller may still hold the old RoleDayPlan object after a cancel
        // or reschedule. That old due-slot cannot be replayed after the owner
        // has changed its version, even when optional side steps failed.
        val live = RoleDayScheduleStore.plans(context, plan.characterId).firstOrNull {
            it.id == plan.id && it.dueAt == plan.dueAt &&
                it.status in setOf("planned", "due", "completed")
        } ?: return false
        val p = prefs(context)
        val key = dueAttemptKey(live)
        val raw = p.getString("due-decision:$key", null) ?: return false
        // A completed primary can still have unprocessed independent steps.
        // Only a settled one-shot execute choice is eligible; cancelled,
        // rescheduled and unrelated completed plans never regain authority.
        if (live.status == "completed" && (live.repeat != "once" ||
                live.resultEvidenceId.isBlank() ||
                runCatching { JSONObject(raw).optString("choice") }.getOrNull() != "execute"))
            return false
        return p.getString("due-phase:$key", "") in setOf("decided", "needs_recovery", "partial_side_steps") &&
            p.getInt("due-recoveries:$key", 0) < MAX_SAVED_DECISION_RECOVERIES
    }

    /** A saved model choice can be resumed after a process death without buying
     * another model response. An unanswered/uncertain request never implies
     * permission to re-issue the same chargeable call. */
    internal fun hasSavedDayToResume(context: Context, nativeId: String, at: Instant): Boolean {
        val p = prefs(context)
        val day = date(at)
        return p.contains(dayDecisionKey(nativeId, day)) &&
            p.getString(dayPhaseKey(nativeId, day), "") != "finished" &&
            p.getInt(dayRecoveriesKey(nativeId, day), 0) < MAX_SAVED_DECISION_RECOVERIES
    }

    @Volatile private var appContext: Context? = null

    fun planForApp(residentId: String, at: Instant): JSONObject? =
        appContext?.let { planFor(it, residentId, at) }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun date(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        at.atZone(zone).toLocalDate().toString()

    fun attemptedToday(context: Context, residentId: String, at: Instant = Instant.now()): Boolean =
        prefs(context).getString("attempt:$residentId", "") == date(at)

    fun planFor(context: Context, residentId: String, at: Instant): JSONObject? = runCatching {
        val root = JSONObject(prefs(context).getString("plan:$residentId", "{}").orEmpty())
        root.takeIf { it.optString("date") == date(at) }
    }.getOrNull()

    /** Planned actions use the same shared calendar / wake / receipt store as other characters. */
    internal fun agendaTime(at: Instant, clock: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val time = runCatching { LocalTime.parse(clock.trim()) }.getOrNull() ?: return null
        val target = at.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
        return target.takeIf { it.isAfter(at.plusSeconds(90)) }
    }

    internal fun nextPlanningTime(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
        var next = now.atZone(zone).toLocalDate().atTime(DAILY_PLANNING_TIME).atZone(zone)
        if (!next.toInstant().isAfter(now)) next = next.plusDays(1)
        return next.toInstant()
    }

    /** A running KEEP worker cannot enqueue itself under the same name.
     * Recovery generation is already bounded by MAX_SAVED_DECISION_RECOVERIES. */
    internal fun catchupWorkName(now: Instant, savedRecovery: Int?): String =
        "${WORK_FIXED}-catchup-${date(now)}" +
            (savedRecovery?.let { "-saved-$it" }.orEmpty())

    private val residentAgendaActions = setOf(
        "send_character_message", "send_friend_request", "respond_friend_request",
        "send_group_message", "publish_moment", "digital_world_action", "play_solo_game",
    )

    /** Preserve model-selected targets and destinations in the SAME args checked at execution.
     * A top-level agenda target otherwise exists only in plan metadata, leaving the
     * scheduled ToolRouter call with no recipient or a mismatched location.
     * This never grants permission: canBegin and ToolRouter revalidate at due time.
     */
    internal fun agendaArgs(action: String, row: JSONObject): JSONObject {
        val args = row.optJSONObject("activityArgs")?.let { JSONObject(it.toString()) } ?: JSONObject()
        args.remove("planId")
        if (action in setOf("send_character_message", "send_friend_request")) {
            val target = row.optString("targetCharacterId").trim()
                .ifBlank { args.optString("targetCharacterId").trim() }
            if (target.isNotBlank()) args.put("targetCharacterId", target)
        }
        if (action == "digital_world_action" &&
            args.optString("worldAction") == "visit_public_place") {
            val place = row.optString("locationCode").trim()
                .ifBlank { args.optString("locationCode").trim()
                    .ifBlank { args.optString("location").trim() } }
            if (place.isNotBlank()) args.put("locationCode", place)
        }
        return args
    }

    /** Model plans are proposed intentions, not actions. Registry and social facts own validity. */
    // The failure receipt uses a suspendable performance repository. Keep it
    // in the caller's Worker coroutine instead of launching detached logging.
    /** A structurally valid resident proposal may still be rejected by live
     * contact, world, or scheduling authority. Never silently discard that
     * choice: the original daily decision remains saved, while EACH rejected
     * agenda item receives its own stable, role-owned factual receipt.
     * Replaying a saved decision uses the same evidence ID, not a new event.
     * This does not automatically reinterpret the resident's intent or buy
     * another model response. */
    internal suspend fun saveAgenda(context: Context, native: DigitalWorldResident, proposal: JSONObject,
        at: Instant): List<RoleDayPlan> {
        val actor = ResidentCharacterBridge.characterId(native.id)
        val agenda = proposal.optJSONArray("agenda") ?: return emptyList()
        val known = ResidentSocialMailbox.visibleContacts(actor).toSet()
        val saved = mutableListOf<RoleDayPlan>()
        val decisionId = "native-daily-${native.id}-${date(at)}"
        fun reject(index: Int, row: JSONObject?, reason: String) {
            val title = row?.optString("title").orEmpty().trim().take(95)
            val action = row?.optString("activityAction").orEmpty().trim().take(80)
            SharedExperienceTimeline.record(
                eventId = "$decisionId-agenda-rejected-$index",
                characterId = actor,
                channel = "原住民·每日自我安排",
                speaker = "日程执行器",
                content = "本人计划的第${index + 1}项未排入日程；活动=${title.ifBlank { "未提供" }}；预选动作=${action.ifBlank { "未提供" }}；原因=$reason。未执行原活动，不能当成居民主动取消或完成。",
                occurredAt = at,
                triggerExtraction = false,
                source = "resident-agenda-rejected",
                evidenceKind = EventEvidenceKind.Observation,
                taskId = decisionId,
            )
        }
        for (index in 0 until agenda.length()) {
            val row = agenda.optJSONObject(index)
            if (index >= MAX_AGENDA_ITEMS) {
                reject(index, row, "超过本次每日规划的${MAX_AGENDA_ITEMS}项排程上限")
                continue
            }
            if (row == null) {
                reject(index, null, "日程项不是对象")
                continue
            }
            val action = CapabilityRegistry.scheduledActionName(row.optString("activityAction"))
            if (action !in residentAgendaActions) {
                reject(index, row, "预选动作不是居民可排程的真实工具")
                continue
            }
            val dueAt = agendaTime(at, row.optString("at"))
            if (dueAt == null) {
                reject(index, row, "预选时间无效或已经过去")
                continue
            }
            val title = row.optString("title").trim().take(95)
            val intent = row.optString("intent").trim().take(270)
            if (title.length < 2 || intent.length < 3) {
                reject(index, row, "活动名称或本人动机不完整")
                continue
            }
            // Keep target/location in the persisted tool arguments, not only in plan metadata.
            val params = agendaArgs(action, row)
            val target = params.optString("targetCharacterId").trim()
            if (action in setOf("send_character_message", "send_friend_request") &&
                (target !in known || target == actor)) {
                reject(index, row, "当前没有对目标角色的真实联系权限")
                continue
            }
            if (action == "send_friend_request" &&
                (MomentFriendAccess.areFriends(actor, target) ||
                    CharacterFriendRequests.historyFor(actor).any { request ->
                        request.status == "pending" &&
                            setOf(request.fromId, request.toId) == setOf(actor, target)
                    })) {
                reject(index, row, "双方已经是好友或已有待处理好友申请")
                continue
            }
            if (action == "respond_friend_request" &&
                CharacterFriendRequests.pendingFor(actor).none {
                    it.id == params.optString("requestId")
                }) {
                reject(index, row, "原好友申请已不再待处理")
                continue
            }
            if (action == "send_group_message") {
                val group = params.optString("groupId")
                if (group.isBlank() || MigratedDomainStores.chat.conversations.value.none {
                    it.id == group && it.groupChat?.members?.any { member -> member.characterId == actor } == true
                }) {
                    reject(index, row, "角色当前不是该群成员或群聊已失效")
                    continue
                }
            }
            val location = params.optString("locationCode").ifBlank {
                row.optString("locationCode").ifBlank { params.optString("location") }
            }.takeIf { requested -> DigitalWorldPublicPlaces.all.any { it.code == requested } }.orEmpty()
            val worldAction = params.optString("worldAction")
            if (action == "digital_world_action" && (worldAction !in setOf(
                "visit_public_place", "interact_resident", "visit_resident_home",
                "use_location", "go_home", "build_home_item", "move_home_item",
                "remove_home_item"))) {
                reject(index, row, "世界动作不存在或不受支持")
                continue
            }
            if (action == "digital_world_action" && worldAction == "build_home_item" &&
                (params.optString("name").isBlank() ||
                    params.optString("appearance").isBlank())) {
                reject(index, row, "家具名称或外观参数不完整")
                continue
            }
            if (action == "digital_world_action" && worldAction in setOf(
                    "move_home_item", "remove_home_item") &&
                DigitalWorldStore.itemsAtHome(actor).none { it.id == params.optString("itemId") }) {
                reject(index, row, "所选家具已不属于本人或已不存在")
                continue
            }
            if (action == "digital_world_action" && worldAction == "move_home_item" &&
                params.optString("position").isBlank()) {
                reject(index, row, "家具移动位置未提供")
                continue
            }
            if (action == "digital_world_action" && worldAction == "visit_public_place" &&
                location.isBlank()) {
                reject(index, row, "目标公共地点不存在")
                continue
            }
            if (action in setOf("send_character_message", "send_group_message", "publish_moment") &&
                params.optString("text").isBlank()) {
                reject(index, row, "预选消息正文为空")
                continue
            }
            if (action == "digital_world_action" && worldAction == "interact_resident" &&
                DigitalWorldExpansionStore.residents().none { it.id == params.optString("itemId") }) {
                reject(index, row, "互动目标居民已不存在")
                continue
            }
            val targetResident = if (action == "digital_world_action" &&
                worldAction == "interact_resident") params.optString("itemId") else ""
            val result = runCatching {
                RoleDayScheduleStore.arrange(context, actor, title, intent, dueAt, at,
                    activityAction = action, activityArgs = params,
                    targetCharacterId = if (action == "send_character_message") target else "",
                    targetResidentId = targetResident, locationCode = location,
                    selfInitiated = true, originEvidenceId = decisionId,
                    originEvidenceRevision = 1L)
            }.onFailure { error ->
                reject(index, row, "排程持久化或资源检查失败：${error.message.orEmpty().take(140)}")
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "${native.name}的活动没有排入日程",
                    message = error.message.orEmpty().take(180))
            }.getOrNull()
            if (result != null) saved += result
        }
        return saved
    }

    internal val wakeTools = setOf("like_moment", "comment_moment", "send_friend_request",
        "respond_friend_request", "send_character_message", "publish_moment",
        "send_group_message", "create_group_chat", "play_solo_game", "digital_world_action",
        "reschedule_personal_activity", "cancel_personal_activity")

    /** Project the executable resident catalogue into BOTH regular and due decisions. */
    internal fun wakeToolContext(catalog: String): String = catalog.lineSequence().filter { line ->
        val name = line.trimStart().removePrefix("- ").substringBefore("，").substringBefore("：")
        name in wakeTools
    }.joinToString("\n")

    /** Side actions share the resident's wake decision and original model
     * connection. Real tools keep individual idempotent receipts. Side actions
     * are never allowed to inherit the due activity's planId.
     */
    internal suspend fun executeWakeSteps(context: Context, role: String,
        decision: JSONObject, turnId: String, initialCompleted: Boolean = true,
        causeEvidenceId: String = ""): Int {
        val original = decision.optJSONArray("steps") ?: return 0
        val steps = JSONArray()
        for (i in 0 until original.length()) {
            val row = original.optJSONObject(i)
            if (row == null) {
                steps.put(original.opt(i)) // preserve invalid position for the shared failure report
                continue
            }
            val step = AutonomousActionWireProtocol.normalizeStep(row)
            val args = step.optJSONObject("args") ?: JSONObject()
            // Ordinary side actions must not claim the current due activity.
            // Explicit management tools need their independently chosen target ID.
            if (step.optString("tool") !in setOf("reschedule_personal_activity", "cancel_personal_activity"))
                args.remove("planId")
            args.remove("_replyEvidenceIds")
            step.put("args", args)
            steps.put(step)
        }
        val report = OrderedCharacterActions.execute(context, role, steps,
            requestId = turnId, initialCompleted = initialCompleted,
            causeEvidenceId = causeEvidenceId,
            autonomousLifeTurn = true, sceneAllowedTools = wakeTools,
            // Existing releases used these IDs; replay must recover the same
            // receipts instead of performing a second post/message/like.
            requestIdForStep = { index -> "$turnId-extra-$index" })
        if (report.completedCount < steps.length()) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "居民手机与多步行动", title = "附加行动含未完成步骤",
                message = report.notes.joinToString("\n"))
        }
        return report.completedCount
    }

    /** Validate only NEW model responses: previously saved choices keep their
     * original request IDs and never trigger another charge on replay.
     * Validate the documented wire shape, never the resident's wishes.
     * Previously a malformed nested step or agenda row was silently skipped
     * AFTER the outer decision had been accepted and marked finished. Those
     * missing actions had neither a repair opportunity nor an execution receipt.
     * The bounded same-resident correction path can now fix them before ANY
     * effects. Real-world permissions remain the live executor's authority. */
    private fun residentStepsContractError(steps: JSONArray?): String? {
        if (steps == null) return null
        for (index in 0 until steps.length()) {
            val row = steps.optJSONObject(index)
                ?: return "steps[${index}] 必须是动作对象"
            val normalized = AutonomousActionWireProtocol.normalizeStep(row)
            if (normalized.optString("tool") !in wakeTools)
                return "steps[${index}] 必须选择当前居民可用的工具"
            if (row.optJSONObject("args") == null)
                return "steps[${index}].args 必须是对象（无参数请填写 {}）"
            if (row.has("requiresPreviousSuccess") &&
                row.opt("requiresPreviousSuccess") !is Boolean)
                return "steps[${index}].requiresPreviousSuccess 必须是布尔值"
            val managesPlan = normalized.optString("tool") in
                setOf("reschedule_personal_activity", "cancel_personal_activity")
            if (!managesPlan && row.optJSONObject("args")?.has("planId") == true)
                return "steps[${index}] 不能代替原活动传入 planId"
            if (managesPlan) {
                val args = row.getJSONObject("args")
                if (args.optString("planId").isBlank() || args.optString("reason").isBlank())
                    return "steps[$index] 的日程管理须有 planId 和本人 reason"
                if (runCatching { Instant.parse(args.optString("expectedDueAt")) }.isFailure)
                    return "steps[$index].expectedDueAt 须为本人上下文中的原时间版本"
                if (normalized.optString("tool") == "reschedule_personal_activity" &&
                    runCatching { Instant.parse(args.optString("dueAt")) }.isFailure)
                    return "steps[$index].dueAt 须为新的 ISO8601 时间"
            }
            val args = normalized.optJSONObject("args") ?: JSONObject()
            val tool = normalized.optString("tool")
            // The model's explicit choice must not be silently converted into
            // a failed/no-op tool by supplying empty defaults for required data.
            val missing = when (tool) {
                "publish_moment" -> if (args.optString("text").isBlank()) "text" else ""
                "send_character_message" -> when {
                    args.optString("targetCharacterId").isBlank() -> "targetCharacterId"
                    args.optString("text").isBlank() -> "text"
                    else -> ""
                }
                "send_group_message" -> when {
                    args.optString("groupId").isBlank() -> "groupId"
                    args.optString("text").isBlank() -> "text"
                    else -> ""
                }
                "like_moment" -> if (args.optString("postId").isBlank()) "postId" else ""
                "comment_moment" -> when {
                    args.optString("postId").isBlank() -> "postId"
                    args.optString("text").isBlank() -> "text"
                    else -> ""
                }
                "send_friend_request" ->
                    if (args.optString("targetCharacterId").isBlank()) "targetCharacterId" else ""
                "respond_friend_request" -> when {
                    args.optString("requestId").isBlank() -> "requestId"
                    args.optString("decision") !in setOf("accept", "decline") -> "decision"
                    else -> ""
                }
                "create_group_chat" -> when {
                    args.optString("groupName").isBlank() -> "groupName"
                    args.optJSONArray("memberIds") == null -> "memberIds"
                    else -> ""
                }
                "digital_world_action" ->
                    if (args.optString("worldAction").isBlank()) "worldAction" else ""
                else -> ""
            }
            if (missing.isNotBlank())
                return "steps[${index}].args 缺少 ${missing}，须由本人补正"
        }
        return null
    }

    private fun agendaContractError(agenda: JSONArray?, at: Instant): String? {
        if (agenda == null) return null
        if (agenda.length() > MAX_AGENDA_ITEMS)
            return "agenda 最多包含 ${MAX_AGENDA_ITEMS} 项，不能悄悄截断本人选择"
        for (index in 0 until agenda.length()) {
            val row = agenda.optJSONObject(index)
                ?: return "agenda[${index}] 必须是日程对象"
            if (agendaTime(at, row.optString("at")) == null)
                return "agenda[${index}].at 必须是今天当地有效的未来 HH:mm 时刻"
            if (row.optString("title").trim().length < 2 ||
                row.optString("intent").trim().length < 3)
                return "agenda[${index}] 必须包含具体 title 和本人 intent"
            val action = CapabilityRegistry.scheduledActionName(row.optString("activityAction"))
            if (action !in residentAgendaActions)
                return "agenda[${index}].activityAction 不是允许的居民日程工具"
            val args = row.optJSONObject("activityArgs")
                ?: return "agenda[${index}].activityArgs 必须是对象"
            if (action in setOf("send_character_message", "send_friend_request") &&
                row.optString("targetCharacterId").trim().isBlank() &&
                args.optString("targetCharacterId").trim().isBlank())
                return "agenda[${index}] 必须明确填写目标角色 ID"
            if (action in setOf("send_character_message", "send_group_message", "publish_moment") &&
                args.optString("text").trim().isBlank())
                return "agenda[${index}] 发消息或动态必须提供本人写好的 text"
            if (action == "send_group_message" && args.optString("groupId").isBlank())
                return "agenda[${index}] 群消息必须指定 groupId"
            if (action == "respond_friend_request" && args.optString("requestId").isBlank())
                return "agenda[${index}] 回应好友申请必须指定 requestId"
            if (action == "digital_world_action") {
                val worldAction = args.optString("worldAction")
                if (worldAction.isBlank())
                    return "agenda[${index}] 数字世界行动必须填写 worldAction"
                if (worldAction == "visit_public_place" &&
                    row.optString("locationCode").isBlank() &&
                    args.optString("locationCode").isBlank() &&
                    args.optString("location").isBlank())
                    return "agenda[${index}] 前往公共地点必须填写 locationCode"
                if (worldAction == "build_home_item" &&
                    (args.optString("name").isBlank() || args.optString("appearance").isBlank()))
                    return "agenda[${index}] 家具计划必须填写 name 和 appearance"
                if (worldAction in setOf("move_home_item", "remove_home_item") &&
                    args.optString("itemId").isBlank())
                    return "agenda[${index}] 移动或删除家具必须填写 itemId"
            }
        }
        return null
    }

    internal fun dueContractError(choice: JSONObject?, now: Instant, protectedPlanId: String = ""): String? {
        if (choice == null) return "没有可解析的 JSON 对象"
        val action = choice.optString("choice")
        if (action !in setOf("execute", "reschedule", "cancel", "wait"))
            return "choice 不是 execute/reschedule/cancel/wait"
        if (choice.has("steps") && choice.optJSONArray("steps") == null)
            return "steps 不是数组"
        residentStepsContractError(choice.optJSONArray("steps"))?.let { return it }
        val side = choice.optJSONArray("steps")
        for (i in 0 until (side?.length() ?: 0)) {
            val row = side?.optJSONObject(i) ?: continue
            val step = AutonomousActionWireProtocol.normalizeStep(row)
            if (protectedPlanId.isNotBlank() && step.optString("tool") in
                setOf("reschedule_personal_activity", "cancel_personal_activity") &&
                step.optJSONObject("args")?.optString("planId") == protectedPlanId)
                return "当前到期计划须用主 choice 改期/取消/等待，不能通过 steps 修改同一计划"
        }
        if (action == "execute" && choice.has("args") && choice.optJSONObject("args") == null)
            return "执行参数 args 不是对象"
        if (action == "reschedule") {
            val due = runCatching {
                java.time.OffsetDateTime.parse(choice.getString("dueAt")).toInstant()
            }.getOrNull()
            if (due == null || !due.isAfter(now.plusSeconds(60)))
                return "改期必须提供有效的未来 ISO8601 时刻"
        }
        if (action == "wait" && waitReviewAt(choice, now) == null)
            return "等待复议时间无效"
        return null
    }

    internal fun dayContractError(choice: JSONObject?, at: Instant = Instant.now()): String? {
        if (choice == null) return "没有可解析的 JSON 对象"
        if (allowedLeisureLocation(choice.optString("lunch")) == null ||
            allowedLeisureLocation(choice.optString("evening")) == null)
            return "午间与晚间地点必须是本人明确选择的合法地点"
        // Optional direct actions must be complete before any side effects.
        // Otherwise the original message is silently skipped after the daily
        // decision is marked finished, with no bounded correction opportunity.
        for (field in listOf("post", "message", "messageTo")) {
            if (choice.has(field) && choice.opt(field) !is String)
                return "$field 必须是字符串（不用 JSON null；不发送时留空字符串）"
        }
        val message = choice.optString("message").trim()
        val messageTo = choice.optString("messageTo").trim()
        if (message.isNotBlank() != messageTo.isNotBlank())
            return "message 与 messageTo 必须同时提供正文和收件人，或同时留空"
        if (choice.has("agenda") && choice.optJSONArray("agenda") == null)
            return "agenda 不是数组"
        if (choice.has("steps") && choice.optJSONArray("steps") == null)
            return "steps 不是数组"
        agendaContractError(choice.optJSONArray("agenda"), at)?.let { return it }
        residentStepsContractError(choice.optJSONArray("steps"))?.let { return it }
        return null
    }

    /** An accepted due-time choice is a real character decision, NOT a completed
     * activity. Give it a stable personal timeline source before any side effect.
     * Recovery never rewrites its original timestamp/revision; deletion remains
     * tombstoned, so withdrawn evidence cannot silently reappear. */
    private fun dueDecisionEvidence(plan: RoleDayPlan, choice: JSONObject, at: Instant): String {
        val id = "native-wake-${plan.id}-${plan.dueAt.epochSecond}"
        val existing = SharedExperienceTimeline.eventsByIds(plan.characterId, listOf(id)).firstOrNull()
        // If a user has edited the old decision text, its new revision
        // cannot explain an action chosen from the original saved JSON.
        // Continue the idempotent action, but leave provenance unknown.
        if (existing != null) return id.takeIf {
            existing.source == "resident-due-decision" && existing.revision == 1L
        }.orEmpty()
        val tools = choice.optJSONArray("steps")
        val selected = (0 until (tools?.length() ?: 0))
            .mapNotNull { tools?.optJSONObject(it)?.optString("tool")?.takeIf(String::isNotBlank) }
        SharedExperienceTimeline.record(
            eventId = id, characterId = plan.characterId,
            channel = "原住民·到点自主决定", speaker = "角色",
            source = "resident-due-decision", evidenceKind = EventEvidenceKind.CharacterStatement,
            taskId = plan.id, occurredAt = at, triggerExtraction = false,
            content = "原计划：${plan.title.take(100)}；本人选择：${choice.optString("choice")}；" +
                "本人理由：${choice.optString("reason").take(180)}；" +
                "独立步骤：${selected.joinToString("、").take(250).ifBlank { "无" }}。" +
                "这是本人已保存的决定，不能证明活动或附加动作已经执行。",
        )
        return SharedExperienceTimeline.eventsByIds(plan.characterId, listOf(id))
            .firstOrNull { it.source == "resident-due-decision" && it.revision == 1L }
            ?.id.orEmpty()
    }

    /** Each due item receives one role decision on the normal path.
     * Only a confirmed malformed reply permits one same-role correction. */
    suspend fun runDuePlan(context: Context, plan: RoleDayPlan, at: Instant = Instant.now()): Boolean =
        dueRequests.withLock(dueAttemptKey(plan)) { runDuePlanLocked(context, plan, at) }

    private suspend fun runDuePlanLocked(context: Context, plan: RoleDayPlan, at: Instant): Boolean {
        val app = context.applicationContext
        appContext = app
        val native = ResidentCharacterBridge.nativeResident(plan.characterId) ?: return false
        // Reject an obsolete scheduled snapshot, even when a caller still
        // passes the old "planned" object after cancel/reschedule/wait.
        val live = RoleDayScheduleStore.plans(app, plan.characterId).firstOrNull {
            it.id == plan.id && it.dueAt == plan.dueAt &&
                it.status in setOf("planned", "due", "completed")
        } ?: return false
        val resumeCompleted = live.status == "completed" && live.repeat == "once" &&
            hasSavedDueToResume(app, live)
        if ((live.status !in setOf("due", "planned") && !resumeCompleted) ||
            at.isBefore(live.dueAt)) return false
        initializeBackgroundRuntime(app)
        ResidentSocialMailbox.initialize(app)
        val key = dueAttemptKey(plan)
        val p = prefs(app)
        val decisionKey = "due-decision:$key"
        val phaseKey = "due-phase:$key"
        val recoveriesKey = "due-recoveries:$key"
        val savedChoice = p.getString(decisionKey, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        if (resumeCompleted && savedChoice?.optString("choice") != "execute") return false
        // Replaying a durable choice does not need the old model archive or
        // another chargeable request; only a fresh choice resolves a connection.
        val selected = if (savedChoice == null) {
            runCatching { ScopedModelSelections.resolveConnection(SCOPE) }.getOrNull()
                ?: return false
        } else null
        if (savedChoice != null) {
            if (!hasSavedDueToResume(app, plan)) return false
            check(p.edit().putInt(recoveriesKey, p.getInt(recoveriesKey, 0) + 1).commit()) {
                "居民到点决定恢复计数未保存"
            }
        } else {
            // A crash/timeout before receiving an answer is uncertain: never
            // silently buy a second model request for this appointment.
            if (!claimDueRequest(p, key, connectionFingerprint(checkNotNull(selected)))) return false
        }
        CharacterPhoneInbox.initialize(app)
        val pending = ResidentSocialMailbox.pendingIncoming(plan.characterId)
        val unread = CharacterPhoneInbox.unreadMoments(plan.characterId)
        val proposedArgs = runCatching { JSONObject(plan.activityArgs) }.getOrDefault(JSONObject())
        val now = Instant.now()
        val dueFacts = if (savedChoice == null) decisionFacts(native, now, pending, unread) + "\n" +
            "【确实已到期的个人日程】planId=${plan.id}；原想法=${plan.title}；动机=${plan.intent}；" +
            "时间=${plan.dueAt}；预选工具=${plan.activityAction}；预选参数=${proposedArgs}；" +
            "之前复议/等待记录=${plan.resultSummary.take(180)}。" +
            "这是当初的计划，并不是已执行。原定见面对象/联系人可能已经离开或不再可联系，请重新查证。" else ""
        val responseResult = if (savedChoice == null) LuluAiServices.gateway.generate(
            characterId = plan.characterId,
            facts = dueFacts,
            instruction = """
                你是${native.name}。此刻你从休眠中醒来，先感知自己的地点、身体、到期活动和手机里的未读动态、聊天消息、好友申请，再选择行为。
                当前到期计划只能通过主 choice 执行/改期/取消/等待，steps 的日程管理只可针对其他本人计划，不能改同一 planId。
                你本人预先安排的一项真实生活活动已经到点，它是当前优先要复议的原计划；看手机不等于完成、撤销或放弃它。
                根据本人意愿可以执行、改期、取消或等待，也可以在同一次决定额外选多个真实生活行动，形成独立回执。
                如果执行，只能选原定合法工具=${plan.activityAction}；从预选参数和当前事实给出真正可执行的 args。
                私信必须是真实认识的人，面对面互动必须实际同场；对方没有回答不算聊过，去过地点不等于已经见面。
                行动后由工具留下独立真实回执，禁止自行宣告结果或替另一人说话。
                一次决策可同时选择原计划和额外 steps，全部是你本人真实想做的，不必为活跃而点赞或加好友。
                最近真见过面但还未加好友的人，可考虑 send_friend_request；已有待处理申请就不要重复发，别人有权拒绝。也可用 digital_world_action 的 go_home/build_home_item 在家布置自己的家具，选择应来自本人此刻兴趣且必须满足世界实际位置与物品容量。
                原计划用 choice=execute|reschedule|cancel|wait，追加步骤用 steps=[{"tool":"like_moment|comment_moment|send_friend_request|respond_friend_request|send_character_message|publish_moment|send_group_message|create_group_chat|play_solo_game|digital_world_action","args":{}}]，每项必须有真实 tool 和 args 对象。
                如对手机的真实postId点赞评论，必须提供 postId，评论还要 text；添加真实认识的人需 targetCharacterId，同意申请需实际收到的 requestId 和 decision=accept|decline。
                每步可填写 requiresPreviousSuccess=true|false；true 仅在前一步有实际成功回执后才执行，false 为独立行动。首个附加步骤若选true，需原计划本轮已执行成功；改期、取消、等待不算原活动执行成功。每步可带原有 motiveId/concernId/experienceId，分别关联真实结果。
                不能在附加步骤传 planId 或冒充执行原计划；对方未同意好友前不许称已添加成功。
                若选 wait，请按自己实际想等的条件选择可选 waitCondition（等待什么）和 reviewAt（明确复议时刻，含时区的ISO8601）；它只表示稍后再想一想，不能自动执行原活动。若暂时没有自己的复议时间，reviewAt 留空，系统会在既有下一次每日规划时段让你重新考虑；不是替你答应原活动。
                只输出JSON：{"choice":"execute|reschedule|cancel|wait","args":{},"dueAt":"改期时新的ISO8601时间","reason":"本人理由","waitCondition":"等待条件或空","reviewAt":"等待复议时的含时区ISO8601时间或空","steps":[]}。
                "wait"不会标记原活动完成，之前真实执行的独立 steps 在复议时不能重新发送；原计划是否完成由其真实工具回执单独核验。
            """.trimIndent(),
            source = "原住民个人日程到点", title = "${native.name} · ${plan.title.take(48)}",
            connectionOverride = selected, contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 1_300,
        ).onFailure { error ->
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "数字世界居民日程", title = "${native.name}的到点决策失败",
                message = error.message.orEmpty().take(180))
        } else null
        val response = responseResult?.getOrNull()
        val firstChoice = response?.text?.let(ModelStructuredOutput::objectOrNull)
        val firstError = if (savedChoice == null && response != null)
            dueContractError(firstChoice, now, plan.id) else null
        val repaired = if (firstError != null) {
            // First response was received. Persist the exact invalid answer
            // BEFORE the one optional correction request. If the correction
            // is interrupted, the original claim prevents blind re-billing.
            check(p.edit().putString("due-invalid:$key", response!!.text.take(8_000))
                .putString("due-invalid-reason:$key", firstError)
                .putString(phaseKey, "repair_requesting").commit()) {
                "原始无效决定未保存，禁止补正请求"
            }
            LuluAiServices.gateway.generate(
                characterId = plan.characterId,
                facts = dueFacts + "\n【上一条原始决定（未执行）】" + response.text.take(4_000) +
                    "\n【结构错误】" + firstError,
                instruction = "请你本人根据同一到期日程和真实事实补正刚才的结构错误。保留你真正的选择与独立 steps；不要因为格式错误就擅自取消活动。steps 中每项须有合法 tool、args 对象，requiresPreviousSuccess 若有须为布尔值；普通附加动作不能传 planId；reschedule_personal_activity/cancel_personal_activity 管理步骤须有 planId、expectedDueAt 和 reason，改期另须 dueAt。只输出符合原先 choice、args、dueAt、reason、waitCondition、reviewAt、steps 契约的 JSON 对象，不要附解释。",
                source = "原住民个人日程到点·结构补正",
                title = "${native.name} · 到点决定补正",
                connectionOverride = selected, contextMode = CompanionContextMode.CharacterAndScenario,
                maxTokens = 1_300,
            ).onFailure { error ->
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "${native.name}到点决定补正失败",
                    message = error.message.orEmpty().take(180))
            }.getOrNull()
        } else null
        val choice = savedChoice ?: if (firstError == null) firstChoice
            else repaired?.text?.let(ModelStructuredOutput::objectOrNull)
        val remainingError = if (savedChoice == null && response != null)
            dueContractError(choice, now, plan.id) else null
        if (choice == null || remainingError != null) {
            val rejected = response == null && responseResult?.exceptionOrNull()
                ?.let(::isDefinitelyRejectedModelRequest) == true
            val failurePhase = when {
                rejected -> "definite_rejected"
                response == null || (firstError != null && repaired == null) -> "uncertain"
                else -> "invalid_output"
            }
            val failure = p.edit().putString(phaseKey, failurePhase)
                .putString("due-last-error:$key", (remainingError ?: firstError
                    ?: responseResult?.exceptionOrNull()?.message.orEmpty()).take(180))
            if (rejected) failure.putString("due-rejected-fingerprint:$key",
                connectionFingerprint(checkNotNull(selected)))
            check(failure.commit()) { "居民到点决定失败状态未保存" }
            return false
        }
        val wanted = choice.optString("choice")
        // A wait's review time is resolved ONCE when the original answer is
        // accepted. Replaying the same saved decision after a process death
        // must not move a missing reviewAt to tomorrow again. Old releases
        // have no stored review clock: derive it from the original due slot,
        // not the current replay time, and preserve that recovered value.
        val waitClockKey = "due-wait-review-at:$key"
        val chosenReviewAt = if (wanted == "wait") {
            if (savedChoice == null) waitReviewAt(choice, now)
            else p.getString(waitClockKey, null)?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            } ?: waitReviewAt(choice, plan.dueAt)
        } else null
        // Persist the exact model-selected choice before any original or side
        // action. Replays recover the same request IDs, not a fresh decision.
        if (savedChoice == null) {
            val editor = p.edit().putString(decisionKey, choice.toString())
                .putString(phaseKey, "decided")
            if (chosenReviewAt != null) editor.putString(waitClockKey, chosenReviewAt.toString())
            check(editor.commit()) {
                "居民到点模型决定尚未保存，禁止开始执行"
            }
        } else if (chosenReviewAt != null && !p.contains(waitClockKey)) {
            check(p.edit().putString(waitClockKey, chosenReviewAt.toString()).commit()) {
                "旧版等待复议的原定时刻未保存，禁止继续改期"
            }
        }
        val turnId = "native-wake-${plan.id}-${plan.dueAt.epochSecond}"
        val decisionEvidenceId = dueDecisionEvidence(plan, choice, at)
        // Reschedule, cancel and wait are NOT successes of the original
        // activity. Perform independently selected side actions while the
        // original due slot still exists, so an interruption can replay them
        // using the same per-step request IDs. Moving/cancelling the slot first
        // would make the remaining side steps unreachable on process death.
        val sideTotal = choice.optJSONArray("steps")?.length() ?: 0
        val preMutationSideComplete = if (wanted in setOf("reschedule", "cancel", "wait")) {
            executeWakeSteps(app, plan.characterId, choice, turnId,
                initialCompleted = false, causeEvidenceId = decisionEvidenceId) == sideTotal
        } else true
        var originalHandled = when (wanted) {
            "execute" -> if (resumeCompleted) {
                // The primary has a settled receipt already. Never call the
                // original tool again merely to resume dependent side actions.
                RoleDayScheduleStore.plans(app, plan.characterId).any {
                    it.id == plan.id && it.dueAt == plan.dueAt &&
                        it.status == "completed" && it.resultEvidenceId.isNotBlank()
                }
            } else {
                // When the model explicitly chooses to execute but leaves args
                // unchanged, reuse its own saved proposal; live ToolRouter still
                // rechecks location, recipient, authority and evidence.
                val args = choice.optJSONObject("args")?.takeIf { it.length() > 0 }
                    ?.let { JSONObject(it.toString()) } ?: JSONObject(proposedArgs.toString())
                args.remove("_replyEvidenceIds")
                args.put("planId", plan.id)
                // ToolRouter checks the SAME plan authority and records a durable
                // rejection. A failed primary must not discard independent steps.
                val receipt = JSONObject(ToolRouter.execute(app, plan.characterId,
                    plan.activityAction, args, requestId = "resident-plan-${plan.id}-${plan.dueAt.epochSecond}",
                    autonomousLifeTurn = true, expectedPlanDueAt = plan.dueAt,
                    causeEvidenceId = decisionEvidenceId))
                receipt.optBoolean("success") &&
                    RoleDayScheduleStore.plans(app, plan.characterId).any {
                        it.id == plan.id && it.status == "completed"
                    }
            }
            "reschedule" -> {
                val replacement = runCatching { java.time.OffsetDateTime.parse(
                    choice.getString("dueAt")).toInstant() }.getOrNull()
                if (replacement == null) {
                    // A malformed primary choice must not discard independent
                    // steps in the same saved decision or falsely mark it done.
                    com.jiacimu.lulu.LuluRepositories.performance.recordError(
                        source = "数字世界居民日程", title = "${native.name}的改期时间无效",
                        message = "planId=${plan.id}；模型未给出有效的 ISO8601 改期时间；原日程仍未完成")
                    false
                } else runCatching {
                    RoleDayScheduleStore.reschedule(app, plan.characterId, plan.id,
                        replacement, choice.optString("reason"), Instant.now(),
                        appendToCurrentWork = true, expectedDueAt = plan.dueAt)
                }.onFailure { error ->
                    com.jiacimu.lulu.LuluRepositories.performance.recordError(
                        source = "数字世界居民日程", title = "${native.name}的改期未生效",
                        message = "planId=${plan.id}；${error.message.orEmpty().take(180)}")
                }.isSuccess
            }
            "cancel" -> RoleDayScheduleStore.cancel(app, plan.characterId, plan.id,
                choice.optString("reason"), Instant.now(), retainRunningWake = true,
                expectedDueAt = plan.dueAt)
            "wait" -> {
                if (chosenReviewAt == null) {
                    com.jiacimu.lulu.LuluRepositories.performance.recordError(
                        source = "数字世界居民日程", title = "${native.name}的等待复议时间无效",
                        message = "planId=${plan.id}；原定复议时刻无法恢复；原活动未完成")
                    false
                } else true // only a valid intent; persist the new wake AFTER independent steps
            }
            else -> false
        }
        val sideStepsComplete = if (wanted == "execute") {
            executeWakeSteps(app, plan.characterId, choice, turnId,
                initialCompleted = originalHandled, causeEvidenceId = decisionEvidenceId) == sideTotal
        } else preMutationSideComplete
        if (wanted == "wait" && originalHandled) {
            // The original plan is still due after its independent side steps,
            // so a crash before rescheduling can resume the saved decision with
            // identical idempotent action request IDs.
            val reviewAt = checkNotNull(chosenReviewAt)
            originalHandled = runCatching {
                val condition = choice.optString("waitCondition").trim()
                val reason = choice.optString("reason").trim()
                // A phone may have been off beyond the ORIGINAL review time.
                // Wake for a new model decision soon; never execute the old
                // activity or buy another answer for this saved choice.
                val schedulingNow = Instant.now()
                val catchUp = !reviewAt.isAfter(schedulingNow.plusSeconds(90))
                val nextWake = if (catchUp) schedulingNow.plusSeconds(90) else reviewAt
                RoleDayScheduleStore.reschedule(app, plan.characterId, plan.id,
                    nextWake, ("等待复议：" + reason +
                        (if (condition.isNotBlank()) "；条件：" + condition else "") +
                        (if (catchUp) "；原定复议=${reviewAt}，中断后尽快复议" else "")),
                    schedulingNow, appendToCurrentWork = true, expectedDueAt = plan.dueAt)
            }.onFailure { error ->
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "${native.name}的等待复议没有排期",
                    message = "planId=${plan.id}；${error.message.orEmpty().take(180)}")
            }.isSuccess
        }
        check(CharacterPhoneInbox.acknowledgeObserved(plan.characterId, unread)) {
            "手机动态已参与感知但未读状态未保存"
        }
        pending.lastOrNull()?.let {
            check(ResidentSocialMailbox.acknowledgeIncoming(plan.characterId, it.id))
        }
        // Waiting isn't an accomplished activity. Keep its state distinct;
        // failed effects remain visible and eligible for bounded replay.
        // A newer owner edit supersedes this saved old-version choice. Keep
        // independent receipts, but do not retry the old appointment forever.
        val latestSlot = RoleDayScheduleStore.plans(app, plan.characterId)
            .firstOrNull { it.id == plan.id }
        val superseded = !originalHandled && (latestSlot == null ||
            latestSlot.dueAt != plan.dueAt ||
            latestSlot.status !in setOf("planned", "due"))
        val phase = when {
            superseded -> "superseded"
            wanted == "wait" && originalHandled && sideStepsComplete -> "waiting"
            wanted == "wait" && originalHandled -> "waiting_partial"
            wanted == "wait" -> "needs_recovery"
            originalHandled && sideStepsComplete -> "finished"
            // An already-settled primary must not hide unsuccessful optional
            // steps. The phase is an outcome record, while the LIVE plan
            // version decides whether a bounded resume is permitted.
            originalHandled -> "partial_side_steps"
            else -> "needs_recovery"
        }
        check(p.edit().putString(phaseKey, phase).commit()) {
            "居民到点回执的执行阶段尚未保存"
        }
        // An already-applied cancel/reschedule/wait must not be undone just
        // because an independent optional step failed. Its own ToolRouter
        // receipt remains the authority; the old appointment is NOT replayed
        // after its dueAt version changes. Never call that partial success
        // "finished", nor blindly retry a possibly delivered message.
        return originalHandled && sideStepsComplete
    }

    /** Optional leisure choices are bounded by real public locations. */
    fun allowedLeisureLocation(value: String): String? = when (value) {
        "home", "cafe", "games", "courtyard" -> value
        else -> null
    }

    internal fun publicPlace(option: String, nativeHome: String): String = when (option) {
        "cafe" -> DigitalWorldPublicPlaces.CAFE
        "games" -> DigitalWorldPublicPlaces.GAME_HALL
        "courtyard" -> DigitalWorldPublicPlaces.COURTYARD
        else -> nativeHome
    }

    /** A paid request is claimed durably *before* network access. A crash,
     * timeout, or race cannot cause multiple chargeable calls in the day. */
    @Synchronized
    internal fun claim(context: Context, nativeId: String, at: Instant): Boolean {
        val key = "attempt:$nativeId"
        val p = prefs(context)
        val day = date(at)
        if (p.getString(key, "") == day) return false
        return p.edit().putString(key, day).commit()
    }

    /** A small real action still belongs to tomorrow's life. Only this resident's
     * own witnessed events or settled executor receipts can recur as evidence;
     * a plan / draft never becomes a completed experience.
     */
    internal fun ownLifeEvidence(characterId: String, history: List<SharedTimelineEvent>): List<SharedTimelineEvent> =
        history.asSequence()
            .filter { it.characterId == characterId }
            .filter { event ->
                when (event.source) {
                    "tool-router" -> event.evidenceKind == EventEvidenceKind.ToolResult &&
                        runCatching { JSONObject(event.content).optString("status") in
                            setOf("succeeded", "failed") }.getOrDefault(false)
                    "resident-duty", "meeting", "character-message", "owner-on-behalf",
                    "digital-world", "moment:self" -> true
                    else -> false // A daily plan, a hypothetical thought, or someone else's story isn't an outcome.
                }
            }
            .toList().takeLast(12)

    private fun recentOwnEvents(characterId: String): List<SharedTimelineEvent> =
        ownLifeEvidence(characterId, SharedExperienceTimeline.recentEvents(characterId, 120))

    internal fun lifeEvidenceLine(event: SharedTimelineEvent): String {
        if (event.source == "tool-router") {
            val receipt = runCatching { JSONObject(event.content) }.getOrNull()
                ?: return ""
            val result = runCatching { JSONObject(receipt.optString("result")) }.getOrNull()
            val detail = result?.let { value ->
                value.optString("summary").ifBlank { value.optString("error") }
            }.orEmpty().trim().take(180)
            return "${event.occurredAt} | 实际工具回执 | ${receipt.optString("tool")} " +
                "${receipt.optString("status")}：${detail.ifBlank { "未提供结果说明" }}"
        }
        return "${event.occurredAt} | ${event.channel} | ${event.speaker}：${event.content.take(180)}"
    }

    private fun recentOwnExperience(characterId: String): String =
        recentOwnEvents(characterId).map(::lifeEvidenceLine)
            .filter(String::isNotBlank).joinToString("\n")

    private fun context(native: DigitalWorldResident, at: Instant,
        pending: List<ResidentDirectMessage>): String {
        val characterId = ResidentCharacterBridge.characterId(native.id)
        val body = EmbodiedBodyRuntime.context(characterId).take(750)
        val contacts = ResidentSocialMailbox.visibleContacts(characterId).map { target ->
            "$target=${MigratedDomainStores.characters.get(target).displayName}"
        }
        val groups = MigratedDomainStores.chat.conversations.value.filter {
            it.groupChat?.members?.any { member -> member.characterId == characterId } == true
        }.take(6).joinToString("、") { "${it.id}=${it.title}" }
        val relations = CharacterSocialRelationship.perspectivesFor(characterId,
            ResidentSocialMailbox.visibleContacts(characterId))
        val publicMoments = MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value)
            .asSequence()
            .filter { it.authorType == MomentAuthorType.Character &&
                it.authorCharacterId != characterId &&
                it.authorCharacterId?.let(DigitalLifeProfileStore::isEnabled) == true }
            .take(8)
            .joinToString("\n") {
                val author = it.authorCharacterId.orEmpty()
                "${MigratedDomainStores.characters.get(author).displayName}：${it.content.take(200)}"
            }
        val mail = ResidentSocialMailbox.inbox(characterId, 8).joinToString("\n") {
            val speaker = MigratedDomainStores.characters.get(it.fromId).displayName
            val recipient = MigratedDomainStores.characters.get(it.toId).displayName
            "$speaker → $recipient: ${it.content.take(220)}"
        }
        val witnesses = DigitalWorldExpansionStore.residentsAt(native.currentLocationCode)
            .filter { it.id != native.id }.take(8)
            .joinToString("、") { "${it.name} (resident:${it.id})" }
        val otherRoles = MigratedDomainStores.characters.settings.value.values.filter {
            it.characterId != characterId && !it.characterId.startsWith("resident:") &&
                DigitalLifeProfileStore.isEnabled(it.characterId) &&
                DigitalWorldStore.locationOf(it.characterId) == native.currentLocationCode
        }.take(6).joinToString("、") { "${it.displayName} (${it.characterId})" }
        return """
            【这是你本人所在的世界，而非用户手机】${native.name}，身份=${native.identity}。
            今天=${date(at)}，现居=${native.homeLocationCode}，所在地点=${native.currentLocationCode}，当前职责=${native.dutyActivity}。
            原住民只亲自感知自己所在地点和过去真实接触的人；你看不到现实世界里的用户手机、屏幕、私聊，也不能推知其他角色的私人心声。
            实际在场的原住民：${witnesses.ifBlank { "未遇见" }}。
            实际在场的其他数字人物：${otherRoles.ifBlank { "无" }}。
            认识或真实相遇后才可私信的对象：${contacts.ifEmpty { listOf("暂无") }.joinToString("、")}。
            本人确实加入的群聊 groupId：${groups.ifBlank { "暂无" }}。
            数字世界好友动态（仅已确认好友可见；不代表本人已经与作者见面）：
            ${publicMoments.ifBlank { "暂无" }}
            只属于你本人的最近通信：
            ${mail.ifBlank { "无" }}
            ${CharacterEverydaySocialContext.render(characterId, at, pending)}
            自己确实经历过的事情与工具结果（失败也是真实经历；计划未执行不算）：
            ${recentOwnExperience(characterId).ifBlank { "暂未积累" }}
            自己对熟人的真实个人印象（对方未必这么想）：
            ${relations.ifBlank { "暂无" }}
            自己的身体：
            $body
            ${DigitalWorldPublicBulletin.publicContext(at).ifBlank { "今天没有新的公共活动公告" }}
            这是有限世界信息，不是整个数字世界的直播。没有列在此处的隐私、谈话、住户和事件不能猜成亲眼所见。
        """.trimIndent()
    }

    /** Same role-owned state and local recall as other adapters. No extra
     * embedding, rerank or paid psychological request for a daily plan. */
    internal suspend fun decisionFacts(native: DigitalWorldResident, at: Instant,
        pending: List<ResidentDirectMessage> = ResidentSocialMailbox.pendingIncoming(
            ResidentCharacterBridge.characterId(native.id)),
        unread: List<MomentPost> = CharacterPhoneInbox.unreadMoments(
            ResidentCharacterBridge.characterId(native.id))): String {
        val id = ResidentCharacterBridge.characterId(native.id)
        // The native resident location and the character's home-item ledger
        // are already shared authoritative world state. Surface the *current*
        // home capacity and object IDs in this same daily decision, rather
        // than mentioning furniture only in a rarely reached generic tool list.
        val housing = DigitalWorldStore.resourceContext(id)
        val possessions = DigitalWorldStore.itemsAtHome(id).take(12)
            .joinToString("\n") { "- itemId=${it.id}；${it.name}；位置=${it.position}" }
        val homeOptions = buildString {
            appendLine("【本人家中的真实陈设｜可自行考虑，但不是每日必须做】")
            appendLine(housing)
            if (possessions.isNotBlank()) appendLine(possessions)
            appendLine("想回家整理可选 digital_world_action，args.worldAction=go_home；确实在自己家且有空位时可选 worldAction=build_home_item，填写自己想要的 itemType/name/appearance/position。调整已有家具用 move_home_item 与真实 itemId/position；无法在咖啡厅凭空布置家中。执行结果才是家具已存在的依据。")
        }
        val facts = context(native, at, pending) + "\n" + homeOptions + "\n" +
            CharacterPhoneInbox.modelContext(id, unread) + "\n" +
            CharacterPhoneInbox.requestContext(id) + "\n" +
            appContext?.let { wakeToolContext(CompanionActionRuntime.capabilityContext(it, id)) }.orEmpty()
        val memory = CharacterRuntime.actionMemory(id, UnifiedMemoryRequest(
            currentInput = pending.joinToString("\n") { it.content },
            sceneContext = "原住民自己的日常生活与熟人来往",
            recentContext = facts,
            taskIntent = "根据本人经历、心情与未尽念头安排今天，不补造他人经历",
        ))
        return listOf(facts,
            appContext?.let { RoleDayScheduleStore.plannedContext(it, id, at) }.orEmpty(),
            "已有日程仍属于自己。结合本轮真实来信或现场事实，自己判断之前等待的条件是否有变化；程序不会替你认定对方已接受。" +
                "如果自己决定提前复议或改期，可在 steps 用 reschedule_personal_activity，args={planId,expectedDueAt:上文原时间版本,dueAt:本人新选的未来ISO时间,reason}；新时间须在当前时间60秒之后，到时仍由自己决定是否执行。" +
                "确实放下可用 cancel_personal_activity，args={planId,expectedDueAt,reason}。没有变化可保留原安排。普通社交/活动步骤仍不得携带 planId。",
            CharacterRuntime.personalityRuntimeContext(id, compact = true,
                query = facts, includeActiveCommitments = false),
            // CharacterAndScenario delivers core memories through its central
            // channel; do not inject the same core a second time in facts.
            memory.copy(coreMemories = emptyList()).compactPromptSection())
            .filter(String::isNotBlank).joinToString("\n\n")
    }

    /** Called by the scheduled worker; each citizen chooses using exactly one
     * invocation, from the shared inexpensive model archive selected in settings. */
    suspend fun runOnce(context: Context, native: DigitalWorldResident,
        now: Instant = Instant.now()): Boolean = dayRequests.withLock("${native.id}:${date(now)}") {
        runOnceLocked(context, native, now)
    }

    private suspend fun runOnceLocked(context: Context, native: DigitalWorldResident,
        now: Instant): Boolean {
        val app = context.applicationContext
        appContext = app
        ResidentCharacterBridge.sync(app, now)
        ResidentSocialMailbox.initialize(app)
        CharacterPhoneInbox.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val id = ResidentCharacterBridge.characterId(native.id)
        ScopedModelSelections.initialize(app)
        val today = date(now)
        val p = prefs(app)
        val decisionKey = dayDecisionKey(native.id, today)
        val phaseKey = dayPhaseKey(native.id, today)
        val recoveriesKey = dayRecoveriesKey(native.id, today)
        val savedDecision = p.getString(decisionKey, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val selected = if (savedDecision == null) {
            runCatching { ScopedModelSelections.resolveConnection(SCOPE) }.getOrNull()
                ?: return false
        } else null
        if (savedDecision != null) {
            if (p.getString(phaseKey, "") == "finished" ||
                p.getInt(recoveriesKey, 0) >= MAX_SAVED_DECISION_RECOVERIES) return false
            check(p.edit().putInt(recoveriesKey, p.getInt(recoveriesKey, 0) + 1).commit()) {
                "原住民有效决定的恢复次数未保存"
            }
        } else {
            if (!claimDayRequest(app, native.id, now,
                    connectionFingerprint(checkNotNull(selected)))) return false
        }
        val pending = ResidentSocialMailbox.pendingIncoming(id)
        val unread = CharacterPhoneInbox.unreadMoments(id)
        val dailyFacts = if (savedDecision == null) decisionFacts(native, now, pending, unread) else ""
        val replyResult = if (savedDecision == null) LuluAiServices.gateway.generate(
            characterId = id,
            facts = dailyFacts,
            instruction = """
                你是生活在这个世界中的${native.name}。只根据自己的现状、个人性格、兴趣和已知交往自主安排今天。
                保留已有工作职责，自己选择午休和晚间的活动，允许无聊、独处、想玩、约人或突然有点小心思。
                这也是每天规划时一次真实的手机感知：你知道这会儿哪些好友新发了朋友圈，有什么未读私信与好友申请。可以在同一轮模型决定日程，并自己选择处理或忽略它们。
                注意昨天和之前实际发生的小事：某次工具失败、没送出去的信、一次真实互动、一次具体的快乐或失落，都可能让你今天换个办法、继续关注或决定放下。但回执只能说明执行成功/失败，不能替别人确认喜欢、收到或原谅；已写计划不等于亲历，不为显得热闹而重复安排。
                更重要的是：你活在有人际交往的真实小世界里。想见熟人、与另一位居民聊共同话题、约朋友玩、回复真实来信、参与群聊、组织自己认识的好友建群或结识新朋友，都可以成为具体行程；不是每天只有喝水和独处。
                见过面但尚未成为好友的人，不等于已经发出了申请。今天若确实想继续来往，可以从上文真实认识且没有待处理申请的 targetCharacterId 自主选择 steps 中的 send_friend_request；对方仍可以拒绝，也可以彼此保持普通熟人。已收到申请则由你决定 respond_friend_request，不允许替对方同意。
                有真实已确认的好友时，可以选择当下 steps 工具 create_group_chat，args={"groupName":"自己想的名称","memberIds":["邀请的真实好友角色ID"]} 组建没有用户自动参与的朋友圈小群；创建并不代表群友回应。确认有合适共同话题再建，已有适合群时优先继续旧群。
                steps 按本人选择的先后执行；每步可填写 requiresPreviousSuccess=true|false，true 依赖前一步实际完成，false 可独立继续；没有前置动作的首步可直接执行。可带原有 motiveId/concernId/experienceId，让每个回执只关联自己的事项。
                你可以决定今天没有行程，也可根据生活需要自然安排多项。每项独立到点醒来、再判断是否执行；不要为了填满日程编造友谊或假设对方一定赴约。
                agenda 最多${MAX_AGENDA_ITEMS}项，每项需包含 at=今天当地HH:mm未来时刻、title=短活动、intent=本人的真实动机、activityAction、activityArgs 对象。字段缺失、非法时间或无效工具会请你本人补正，不会被系统悄悄丢弃。
                真实发私信/群聊/动态时，在 activityArgs.text 写你本人准备说的内容；发私信需同时写真实 targetCharacterId，不留空。访问公共地点时 activityArgs.worldAction=visit_public_place 且 locationCode 要与日程地点一致。日程写下不是对方收到消息，计划到点仍需你重新选择并得到工具回执。
                可用：send_character_message（确实认识的真实ID）；send_friend_request（认识且双方无待处理申请）；respond_friend_request（本人收到的真实 requestId）；digital_world_action（visit_public_place 真实公共地点码；同场 interact_resident；go_home；在家且有空位时 build_home_item，args.itemType/name/appearance/position 由本人选择；move_home_item/remove_home_item 需真实本人家具 itemId）；publish_moment；play_solo_game；本人实际入群才可 send_group_message。
                真实熟人不必都加好友，已经舒适的家也不必添家具。确有想做的事可以立刻在 steps 执行，也可在 agenda 约定未来一刻；到点仍由本人重新判断，实际执行结果单独留回执。
                去公共地点是去找接触机会，不保证见到目标；要真正交流须执行当下可证实的互动或私信。人际对象可能后来改期或拒绝。
                仅输出 JSON，不能替别人发言：
                {"agenda":[{"at":"HH:mm","title":"计划中的一件具体活动","intent":"本人为什么想做","activityAction":"send_character_message|send_friend_request|respond_friend_request|digital_world_action|publish_moment|play_solo_game|send_group_message","targetCharacterId":"真实认识的ID或留空","locationCode":"公共地点码或留空","activityArgs":{}}],
                 "lunch":"cafe|home|courtyard|games",
                 "evening":"cafe|home|courtyard|games",
                 "eveningActivity":"一件自己愿意做的短活动",
                 "mood":"今天本人真实的心情，可为空",
                 "thought":"自己内心一闪而过的念头，可为空",
                 "post":"如果今天真的想发朋友圈，填自己的短文；否则留空",
                 "messageTo":"上文列出的确实认识的角色ID，否则为空",
                 "message":"给这位角色真正想说的私信，否则为空",
                 "steps":[{"tool":"like_moment|comment_moment|send_friend_request|respond_friend_request|send_character_message|publish_moment|send_group_message|play_solo_game|digital_world_action|reschedule_personal_activity|cancel_personal_activity","args":{}}],
                 "innerLife":{"emotion":{"feeling":"确实发生变化时的情绪","cause":"本人真实经历的原因"},"social":{"targetId":"确实认识的对象ID","interpretation":"本人对其个人看法","reason":"双方实际来往的依据","dimensions":{"trust":"up|down|same","warmth":"up|down|same","ease":"up|down|same","friction":"up|down|same","boundarySafety":"up|down|same"}}}}}
                不能为了活跃指标强制发帖/发消息；不能凭空接触不在身边的人或替别人决定是否喜欢你。
                发布只是你的选择，不代表对方一定读过；关系随各自真实来往慢慢改变，不能宣告双方已相爱。
                用户只允许数字世界内社交与行动，禁止现实设备感知、联网、购买和自主联系现实用户。
            """.trimIndent(),
            source = "数字世界原住民每日规划",
            title = "${native.name}的一天",
            connectionOverride = selected,
            contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 1_900,
        ).onFailure { error ->
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "数字世界居民日程", title = "${native.name}今天规划失败",
                message = error.message.orEmpty().take(180))
        } else null
        val reply = replyResult?.getOrNull()
        val firstPlan = reply?.text?.let(ModelStructuredOutput::objectOrNull)
        val firstError = if (savedDecision == null && reply != null)
            dayContractError(firstPlan, now) else null
        val repaired = if (firstError != null) {
            check(p.edit().putString("invalid:${native.id}:$today", reply!!.text.take(8_000))
                .putString("invalid-reason:${native.id}:$today", firstError)
                .putString(phaseKey, "repair_requesting").commit()) {
                "原始无效每日决定未保存，禁止补正请求"
            }
            LuluAiServices.gateway.generate(
                characterId = id,
                facts = dailyFacts + "\n【上一条原始决定（尚未执行）】" + reply.text.take(4_000) +
                    "\n【结构错误】" + firstError,
                instruction = "请你本人根据同一事实修正上一条每日规划的 JSON 结构。保留你的兴趣、日程和社交选择，不要因格式错误被程序替你决定。lunch 和 evening 必须明确选 home/cafe/games/courtyard；agenda 最多8项，每项须有未来 HH:mm、title、intent、合法 activityAction、activityArgs 对象和该动作需要的目标/正文；steps 中每项须有合法 tool、args 对象；若本人想立即私信，message 与 messageTo 必须同时有真实正文和收件人，否则两个字段都留空；post 若有必须是文本。只输出完整的每日规划 JSON 对象。",
                source = "数字世界原住民每日规划·结构补正",
                title = "${native.name} · 日计划补正",
                connectionOverride = selected, contextMode = CompanionContextMode.CharacterAndScenario,
                maxTokens = 1_900,
            ).onFailure { error ->
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "${native.name}日计划补正失败",
                    message = error.message.orEmpty().take(180))
            }.getOrNull()
        } else null
        val json = savedDecision ?: if (firstError == null) firstPlan
            else repaired?.text?.let(ModelStructuredOutput::objectOrNull)
        val remainingError = if (savedDecision == null && reply != null)
            dayContractError(json, now) else null
        if (json == null || remainingError != null) {
            // Only a provider's explicit rejection is provably non-generated.
            // A timeout or a failed correction may already have been charged.
            val rejected = reply == null && replyResult?.exceptionOrNull()
                ?.let(::isDefinitelyRejectedModelRequest) == true
            val failurePhase = when {
                rejected -> "definite_rejected"
                reply == null || (firstError != null && repaired == null) -> "uncertain"
                else -> "invalid_output"
            }
            val failure = p.edit().putString(phaseKey, failurePhase)
                .putString("last-error:${native.id}:$today", (remainingError ?: firstError
                    ?: replyResult?.exceptionOrNull()?.message.orEmpty()).take(180))
            if (rejected) failure.putString("rejected-fingerprint:${native.id}:$today",
                connectionFingerprint(checkNotNull(selected)))
            check(failure.commit()) { "居民每日决定失败状态未保存" }
            return false
        }
        // The exact model decision is durable before any calendar, inner-life,
        // inbox or messaging side effects. Replays retain original request IDs.
        if (savedDecision == null) {
            check(p.edit().putString(decisionKey, json.toString())
                .putString(phaseKey, "decided").commit()) {
                "原住民有效模型决定尚未持久化，不能开始副作用"
            }
        }
        val lunch = allowedLeisureLocation(json.optString("lunch")) ?: "cafe"
        val evening = allowedLeisureLocation(json.optString("evening")) ?: "home"
        val plan = JSONObject()
            .put("date", date(now))
            .put("lunch", lunch)
            .put("evening", evening)
            .put("eveningActivity", json.optString("eveningActivity").trim().take(95))
            .put("mood", json.optString("mood").trim().take(140))
            .put("thought", json.optString("thought").trim().take(250))
        check(prefs(app).edit().putString("plan:${native.id}", plan.toString()).commit()) {
            "原住民日程没有保存"
        }
        val agenda = saveAgenda(app, native, json, now)
        val eventId = "native-daily-${native.id}-${date(now)}"
        // A replay can occur minutes after the first save. Do not rewrite
        // the same decision event with a newer occurredAt: that would bump
        // its revision and sever the already persisted tool provenance.
        if (SharedExperienceTimeline.eventsByIds(id, listOf(eventId)).isEmpty()) {
            SharedExperienceTimeline.record(
                eventId = eventId, characterId = id, channel = "原住民·每日自我安排",
                speaker = native.name, source = "resident-daily", occurredAt = now,
                evidenceKind = EventEvidenceKind.CharacterStatement, triggerExtraction = false,
                content = "本人选定的当天安排：午休=$lunch；晚间=$evening；晚间想做：${plan.optString("eveningActivity")}。" +
                    "已排入真实日程的自主活动=${agenda.joinToString("；") { "${it.dueAt} ${it.title}" }}。计划均尚未执行。",
            )
        }
        val decisionEvidenceId = SharedExperienceTimeline.eventsByIds(id, listOf(eventId))
            .firstOrNull { it.source == "resident-daily" && it.revision == 1L }
            ?.id.orEmpty()
        // The same private thought and social perspective channel used by
        // protagonists. An unsupported stranger's bond is never invented.
        val known = ResidentSocialMailbox.visibleContacts(id).toSet()
        val incomingEvents = SharedExperienceTimeline.eventsByIds(id,
            pending.map { ResidentSocialMailbox.evidenceId(it, id) })
        val scopedInnerLife = json.optJSONObject("innerLife")?.let { JSONObject(it.toString()) }
        var mentalEvidenceId = eventId
        scopedInnerLife?.let { scoped ->
            val target = scoped.optJSONObject("social")?.optString("targetId").orEmpty()
            val encounter = (incomingEvents + recentOwnEvents(id))
                .filter { it.evidenceKind == EventEvidenceKind.Observation && !it.occurredAt.isAfter(now) }
                .map(CharacterPerceptionContext::stimulus)
                .lastOrNull { target in it.socialIds }
            // Merely knowing a name is not a new encounter. Use an actual
            // supplied letter/meeting as provenance, so withdrawal works.
            if (target !in known || encounter == null) scoped.remove("social")
            if (scoped.has("social") && encounter != null) mentalEvidenceId = encounter.evidenceId
            CharacterInnerLifeStore.observe(
                id, mentalEvidenceId,
                encounter?.description ?: "今天自己的真实处境与安排",
                scoped, encounter?.socialIds.orEmpty(), now,
            )
        }
        CharacterInnerLifeStore.recordInnerVoice(id, mentalEvidenceId, plan.optString("thought"), now)
        CharacterInnerLifeStore.recordCausalTransition(id, mentalEvidenceId,
            appraisal = null, innerLife = scopedInnerLife, innerThoughtBasis = null,
            selectedAction = "plan_day", innerThought = plan.optString("thought"),
            reason = "本人作出的当天安排；尚未执行的活动不能算亲历", now = now)
        val post = json.optString("post").trim().take(MAX_MESSAGE)
        val postComplete = if (post.isNotBlank()) {
            MomentsStore.initialize(app)
            ToolExecutionStatus.completed(JSONObject(ToolRouter.execute(app, id,
                "publish_moment", JSONObject().put("text", post),
                requestId = "$eventId-post", causeEvidenceId = decisionEvidenceId)))
        } else true
        val messageTo = json.optString("messageTo").trim()
        val message = json.optString("message").trim().take(MAX_MESSAGE)
        val messageComplete = if (message.isNotBlank() && messageTo.isNotBlank()) {
            ToolExecutionStatus.completed(JSONObject(ToolRouter.execute(app, id,
                "send_character_message",
                JSONObject().put("targetCharacterId", messageTo).put("text", message),
                requestId = "$eventId-message", causeEvidenceId = decisionEvidenceId)))
        } else true
        val sideTotal = json.optJSONArray("steps")?.length() ?: 0
        val sideComplete = executeWakeSteps(app, id, json, eventId,
            causeEvidenceId = decisionEvidenceId) == sideTotal
        check(CharacterPhoneInbox.acknowledgeObserved(id, unread)) {
            "已感知的手机未读动态尚未记录"
        }
        pending.lastOrNull()?.let { check(ResidentSocialMailbox.acknowledgeIncoming(id, it.id)) {
            "居民来信处理进度没有保存"
        } }
        // Saving the calendar is not the same as completing the immediate
        // independent actions. Never call a denied message or an unfinished
        // step 'finished'. Recovery uses the same durable decision and exact
        // ToolRouter IDs; settled posts are returned, not published again.
        val allComplete = postComplete && messageComplete && sideComplete
        check(p.edit().putString(phaseKey,
            if (allComplete) "finished" else "partial_side_steps").commit()) {
            "原住民本次决定的完成状态没有保存"
        }
        return allComplete
    }

    suspend fun tick(context: Context, now: Instant = Instant.now()): Int {
        val app = context.applicationContext
        // WorkManager can start a cold process without MigrationActivity.
        // Restore the same repositories and model archive before any claims.
        initializeBackgroundRuntime(app)
        appContext = app
        ResidentDailyRoutine.refresh(app, now)
        ResidentSocialMailbox.initialize(app)
        MomentsStore.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        ScopedModelSelections.initialize(app)
        // Every registered resident gets a chance; the old .take(24) left later
        // residents permanently unscheduled. Bound concurrent remote requests instead.
        val semaphore = Semaphore(3)
        val succeeded = coroutineScope {
            DigitalWorldExpansionStore.residents().map { native ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            runOnce(app, native, now)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                                source = "数字世界居民日程",
                                title = "${native.name}的每日安排未完成",
                                message = error.message.orEmpty().take(180))
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }
        // A completed planning decision may change today's free time if it
        // wasn't already reached, without rewriting a finished duty receipt.
        ResidentDailyRoutine.refresh(app, now)
        return succeeded
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        appContext = app
        ScopedModelSelections.initialize(app)
        DigitalWorldExpansionStore.initialize(app)
        val manager = WorkManager.getInstance(app)
        // Migrate away from a rolling 24-hour period: local 08:00 is a stable calendar time.
        manager.cancelUniqueWork(WORK_PERIODIC)
        manager.cancelUniqueWork(WORK_NOW)
        val now = Instant.now()
        val next = nextPlanningTime(now)
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<ResidentDailyDecisionWorker>()
            .setConstraints(network)
            .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork("${WORK_FIXED}-${date(next)}", ExistingWorkPolicy.KEEP, request)
        // If the app first opens after planning time, seed today's missing agendas once.
        // Claim() still owns each citizen's paid daily opportunity.
        if (now.atZone(ZoneId.systemDefault()).toLocalTime() >= DAILY_PLANNING_TIME &&
            runCatching { ScopedModelSelections.selectedArchiveId(SCOPE) }.getOrNull() != null) {
            val residents = DigitalWorldExpansionStore.residents()
            val savedRecovery = residents.asSequence().filter {
                hasSavedDayToResume(app, it.id, now)
            }.map {
                prefs(app).getInt(dayRecoveriesKey(it.id, date(now)), 0)
            }.minOrNull()
            val needsCatchup = savedRecovery != null || residents.any {
                prefs(app).getString("attempt:${it.id}", "") != date(now) ||
                    hasSafeDayRequestToRetry(app, it.id, now)
            }
            if (needsCatchup) {
                // KEEP on a single daily catch-up name cannot enqueue the next
                // recovery while that very Worker is running. Distinct bounded
                // saved-decision generations make both permitted replays
                // reachable without a new paid model request or an endless loop.
                manager.enqueueUniqueWork(catchupWorkName(now, savedRecovery),
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ResidentDailyDecisionWorker>()
                        .setConstraints(network)
                        .setInitialDelay(if (savedRecovery != null) 120L else 0L,
                            TimeUnit.SECONDS).build())
            }
        }
    }
}

internal class ResidentDailyDecisionWorker(
    appContext: Context, params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) { ResidentDailyDecision.tick(applicationContext) }
        ResidentDailyDecision.schedule(applicationContext)
        Result.success()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        com.jiacimu.lulu.LuluRepositories.performance.recordError(
            source = "数字世界居民日程", title = "原住民每日计划未执行完毕",
            message = error.message.orEmpty().take(180))
        Result.failure()
    }
}
