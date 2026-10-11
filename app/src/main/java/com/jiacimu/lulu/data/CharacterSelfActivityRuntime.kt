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
import com.jiacimu.lulu.study.ReadingBackgroundBridge
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Bridges one explicitly DECIDED private activity to the existing real executor.
 * Thinking "perhaps I could read" is NOT a decision, and no natural-language
 * keyword search is allowed to invent an action from the heart voice.
 *
 * A chat can be spoken before the role gets around to the activity. Only the
 * executor's outcome may transform that intention into lived experience.
 */
internal object CharacterSelfActivityRuntime {
    private const val PREFS = "lulu_own_activity_followthrough_v1"
    private const val REPEAT_WINDOW_MS = 10 * 60_000L

    internal data class Proposal(val tool: String, val args: JSONObject, val label: String) {
        val signature: String get() = "$tool:${args.toString()}"
    }

    fun parse(proposal: JSONObject?, realBookIds: Set<String>, digitalLife: Boolean): Proposal? {
        val input = proposal ?: return null
        if (input.optString("stage").lowercase() != "decided") return null
        return when (input.optString("action").lowercase()) {
            "reading" -> {
                val id = input.optString("readingBookId").trim()
                if (id.isBlank() || id !in realBookIds) null
                else Proposal("read_book", JSONObject().put("readingBookId", id), "读一本真正可读的书")
            }
            "solo_game" -> {
                if (input.optString("gameId") != "memory_match") null
                else Proposal("play_solo_game", JSONObject().put("gameId", "memory_match"), "独自玩一局记忆配对")
            }
            "digital_world" -> {
                if (!digitalLife) return null
                val action = input.optString("worldAction").trim()
                if (action !in setOf("go_home", "visit_cloud_meadow", "visit_public_place",
                    "use_home_item", "use_location")) return null
                val args = JSONObject().put("worldAction", action)
                if (action == "visit_public_place") {
                    val location = input.optString("location").trim()
                    if (location.isBlank()) return null
                    args.put("location", location)
                }
                if (action == "use_home_item" || action == "use_location") {
                    val activityId = input.optString("activityId").trim()
                    if (activityId.isBlank()) return null
                    args.put("activityId", activityId)
                    if (action == "use_home_item") {
                        val itemId = input.optString("itemId").trim()
                        if (itemId.isBlank()) return null
                        args.put("itemId", itemId)
                    }
                    // A reading activity must choose a real book through read_book,
                    // otherwise "sat down to read" could fake having read content.
                    if (activityId in setOf("read_at_desk", "quiet_read", "window_read"))
                        return null
                }
                Proposal("digital_world_action", args, "在数字世界做一件真实的小事")
            }
            else -> null
        }
    }

    /** Common accepted-decision dispatcher: private chat, group chat, phone and
     * autonomous loops all enter from CharacterInnerLifeStore, not UI screens.
     * This registers a choice; execution happens ONLY through ToolRouter.
     */
    fun offer(context: Context, characterId: String, appraisal: JSONObject?, evidenceId: String = ""): Boolean {
        if (characterId.isBlank()) return false
        val intended = appraisal?.optJSONObject("ownActivity") ?: return false
        val available = if (intended.optString("action") == "reading")
            ReadingBackgroundBridge.availableBooks(context, characterId) else emptyList()
        val books = available.mapTo(hashSetOf()) { it.id }
        val candidate = parse(intended, books, DigitalLifeProfileStore.isEnabled(characterId)) ?: return false
        val selected = if (candidate.tool == "read_book") {
            val title = available.firstOrNull { it.id == intended.optString("readingBookId") }?.title
                ?: return false
            candidate.copy(label = "翻开《${title.take(90)}》")
        } else candidate
        val whyNow = intended.optString("whyNow").trim().take(200)
        if (whyNow.length < 3) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString("activity:$characterId", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val now = System.currentTimeMillis()
        if (previous?.optString("status") in setOf("pending", "running")) return false
        if (previous?.optString("signature") == selected.signature &&
            now - previous.optLong("createdAt") in 0..REPEAT_WINDOW_MS) return false
        if (evidenceId.isNotBlank() && previous?.optString("evidenceId") == evidenceId) return false

        val id = UUID.randomUUID().toString()
        val record = JSONObject()
            .put("id", id).put("characterId", characterId)
            .put("signature", selected.signature).put("createdAt", now)
            .put("whyNow", whyNow).put("evidenceId", evidenceId.take(180))
            .put("label", selected.label).put("tool", selected.tool)
            .put("args", selected.args).put("status", "pending")
        if (!prefs.edit().putString("activity:$characterId", record.toString()).commit()) return false
        val work = OneTimeWorkRequestBuilder<CharacterSelfActivityWorker>()
            .setInitialDelay(8_000L, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putString("characterId", characterId)
                .putString("activityId", id).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-self-activity-$characterId-$id", ExistingWorkPolicy.KEEP, work)
        return true
    }

    fun latest(context: Context, characterId: String): JSONObject? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("activity:$characterId", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    internal fun markRunning(context: Context, characterId: String, id: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "activity:$characterId"
        val entry = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return
        if (entry.optString("id") == id && entry.optString("status") == "pending") {
            entry.put("status", "running")
            prefs.edit().putString(key, entry.toString()).commit()
        }
    }

    internal fun markResult(context: Context, characterId: String, id: String,
                            success: Boolean, summary: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "activity:$characterId"
        val prior = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return
        if (prior.optString("id") != id) return
        prior.put("status", if (success) "completed" else "failed")
            .put("result", summary.take(240)).put("finishedAt", Instant.now().toString())
        prefs.edit().putString(key, prior.toString()).commit()
    }
}

class CharacterSelfActivityWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("activityId").orEmpty()
        val characterId = inputData.getString("characterId").orEmpty()
        if (id.isBlank() || characterId.isBlank()) return Result.success()
        return try {
            initializeBackgroundRuntime(applicationContext)
            val entry = CharacterSelfActivityRuntime.latest(applicationContext, characterId)
                ?: return Result.success()
            if (entry.optString("id") != id ||
                entry.optString("status") !in setOf("pending", "running"))
                return Result.success()
            val elapsed = System.currentTimeMillis() - entry.optLong("createdAt")
            if (elapsed !in 0L..(60 * 60_000L)) {
                CharacterSelfActivityRuntime.markResult(applicationContext, characterId, id,
                    false, "行动意向已过时，没有假装执行")
                return Result.success()
            }
            // A character may decide to read while still talking on the phone.
            // Delay the physical action until the call releases ownership;
            // do not impersonate simultaneous unrelated activities.
            if (CompanionPresenceStore.isInCall(characterId)) return Result.retry()
            CharacterSelfActivityRuntime.markRunning(applicationContext, characterId, id)
            val raw = ToolRouter.execute(applicationContext, characterId,
                entry.optString("tool"), entry.optJSONObject("args") ?: JSONObject(),
                requestId = "self-activity-$id")
            val outcome = JSONObject(raw)
            val success = outcome.optBoolean("success")
            val summary = outcome.optString("summary").ifBlank {
                outcome.optString("error").ifBlank { "执行器没有确认结果" }
            }
            CharacterSelfActivityRuntime.markResult(applicationContext, characterId, id, success, summary)
            // The factual tool receipt precedes any psychological conclusion.
            // Decisions remain private, revisable records; only executor proof can
            // say an action actually took place.
            CharacterInnerLifeStore.recordDecision(
                characterId = characterId, decisionId = "self-activity-$id",
                selectedAction = entry.optString("tool"),
                reason = entry.optString("whyNow"), chosenMotiveId = "",
                alternatives = null, outcome = summary, succeeded = success,
                actionSignature = entry.optString("signature"),
                causalEvidenceId = entry.optString("evidenceId"),
            )
            if (success) ProactivePerceptionScheduler.scheduleActionFollowThrough(
                applicationContext, characterId, id,
            )
            Result.success()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // ToolRouter deduplicates using the stable requestId, even if WorkManager retries.
            if (runAttemptCount < 1) Result.retry() else {
                CharacterSelfActivityRuntime.markResult(applicationContext, characterId, id,
                    false, "本次活动执行中断：${error.message.orEmpty().take(100)}")
                Result.failure()
            }
        }
    }
}
