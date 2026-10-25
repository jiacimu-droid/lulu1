package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A scheduling projection of innerLife.motives, never another goal ledger. */
internal data class CharacterMotiveReview(val id: String, val dueAt: Instant, val token: String,
                                        val fallback: Boolean = false) {
    companion object {
        fun from(motive: JSONObject): CharacterMotiveReview? {
            if (!CharacterMotiveState.isActive(motive)) return null
            val id = motive.optString("id").takeIf(String::isNotBlank) ?: return null
            fun candidate(field: String): CharacterMotiveReview? {
                val at = runCatching { Instant.parse(motive.optString(field)) }.getOrNull() ?: return null
                val fields = mutableListOf(id, at.toString(), motive.optString("planEvidenceId"),
                    motive.optString("nextStep"), motive.optString("waitingFor"))
                // Keep fingerprints of old, criteria-free plans compatible on upgrade.
                if (motive.optString("successCondition").isNotBlank() || motive.optString("stopCondition").isNotBlank())
                    fields.addAll(listOf(motive.optString("successCondition"), motive.optString("stopCondition")))
                if (motive.optString("completionKind") == "execution") fields.add("execution")
                val content = fields.joinToString("\u0000")
                val token = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                return CharacterMotiveReview(id, at, token, field == "fallbackReviewAt")
            }
            val explicit = candidate("reviewAfter")
            val reviewedAt = runCatching { Instant.parse(motive.optString("reviewedAt")) }.getOrNull()
            if (explicit != null && motive.optString("reviewedPlan") != explicit.token &&
                (reviewedAt == null || explicit.dueAt.isAfter(reviewedAt))) return explicit
            return candidate("fallbackReviewAt")
        }

        fun pending(state: JSONObject): List<CharacterMotiveReview> =
            CharacterMotiveState.active(state.optJSONArray("motives")).mapNotNull(::from).sortedBy { it.dueAt }

        /** Quiet hours move a review opportunity, without authorizing a social action. */
        fun eligibleAt(due: Instant, policy: ProactivePerceptionPolicy, zone: ZoneId): Instant {
            if (!policy.quietHoursEnabled) return due
            fun quiet(minutes: Int): Boolean {
                val start = policy.quietStartMinutesOfDay
                val end = policy.quietEndMinutesOfDay
                return if (start == end) false else if (start < end) minutes in start until end
                    else minutes >= start || minutes < end
            }
            var at = due.atZone(zone)
            if (!quiet(at.hour * 60 + at.minute)) return due
            at = at.withSecond(0).withNano(0)
            repeat(1_441) {
                at = at.plusMinutes(1)
                if (!quiet(at.hour * 60 + at.minute)) return at.toInstant()
            }
            return due
        }
    }
}

internal object CharacterMotiveReviewScheduler {
    private val submitted = ConcurrentHashMap<String, String>()

    fun schedule(context: Context, role: String, state: JSONObject, force: Boolean = false) {
        val policy = ProactivePerceptionPolicyStore.get(role)
        // Unscheduled wishes ride the existing ordinary perception pulse.
        // Only the role's authored time gets a dedicated wake; otherwise a
        // large goal set could accidentally create one paid loop per batch.
        val next = CharacterMotiveReview.pending(state).firstOrNull { !it.fallback }
        if (!policy.enabled || next == null) { submitted.remove(role); return }
        val due = CharacterMotiveReview.eligibleAt(next.dueAt, policy, ZoneId.systemDefault())
        val signature = next.token + ":" + due.toEpochMilli()
        if (!force && submitted[role] == signature) return
        val request = OneTimeWorkRequestBuilder<CharacterMotiveReviewWorker>()
            .setInitialDelay(Duration.between(Instant.now(), due).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("characterId", role).putString("motiveId", next.id)
                .putString("token", next.token).build()).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-motive-review-$role-${next.token}-${due.toEpochMilli()}", ExistingWorkPolicy.KEEP, request)
        submitted[role] = signature
    }

    fun restore(context: Context) {
        CharacterInnerLifeStore.characterIds().forEach { role ->
            CharacterInnerLifeStore.restoreMotiveFollowUps(role)
            schedule(context, role, CharacterInnerLifeStore.snapshot(role), force = true)
        }
    }
}

class CharacterMotiveReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        initializeBackgroundRuntime(applicationContext)
        val role = inputData.getString("characterId") ?: return Result.failure()
        val id = inputData.getString("motiveId") ?: return Result.failure()
        val token = inputData.getString("token") ?: return Result.failure()
        val policy = ProactivePerceptionPolicyStore.get(role)
        val pending = CharacterMotiveReview.pending(CharacterInnerLifeStore.snapshot(role))
            .firstOrNull { it.id == id && it.token == token } ?: return Result.success()
        if (!policy.enabled) return Result.success()
        val due = CharacterMotiveReview.eligibleAt(pending.dueAt, policy, ZoneId.systemDefault())
        val now = Instant.now()
        if (now.isBefore(due)) return Result.retry()
        if (Duration.between(due, now).toMinutes() > 30) {
            CharacterInnerLifeStore.claimMotiveReview(role, id, token, now, coalesceDue = false)
            return Result.success() // A stale review is not permission to contact hours later.
        }
        // The ordinary per-person owner claims the exact plan after checking
        // call/unread ownership. A busy conversation defers, never consumes it.
        ProactivePerceptionRuntime.runDueCycle(applicationContext,
            "持续动机到期复查：检查本人已保存的下一步、真实结果和新证据，自行选择继续、等待或放下",
            // An authored future review is the role's own chosen wake. Cadence
            // recovery remains a quiet background assessment, not forced online life.
            targetCharacterId = role, force = true, preserveOffline = pending.fallback,
            motiveReviewId = id, motiveReviewToken = token)
        val remains = CharacterMotiveReview.pending(CharacterInnerLifeStore.snapshot(role))
            .any { it.id == id && it.token == token }
        return if (remains) Result.retry() else Result.success()
    }
}
