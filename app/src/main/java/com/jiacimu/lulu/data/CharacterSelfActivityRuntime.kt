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

    fun parse(
        proposal: JSONObject?, realBookIds: Set<String>, digitalLife: Boolean,
        validIncident: (String, String) -> Boolean = { _, _ -> false },
    ): Proposal? {
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
                else Proposal("play_solo_game", JSONObject().put("gameId", "memory_match").apply {
                    if (input.has("targetScore")) put("targetScore", input.optInt("targetScore"))
                }, "独自玩一局记忆配对")
            }
            "digital_world" -> {
                if (!digitalLife) return null
                val action = input.optString("worldAction").trim()
                if (action !in setOf("go_home", "visit_cloud_meadow", "visit_public_place",
                    "use_home_item", "use_location", "handle_incident", "interact_resident", "visit_resident_home", "leave_resident_episode")) return null
                val args = JSONObject().put("worldAction", action)
                if (action == "leave_resident_episode") {
                    val id = input.optString("incidentId").trim()
                    if (id.isBlank()) return null
                    args.put("incidentId", id)
                }
                if (action in setOf("interact_resident", "visit_resident_home")) {
                    val residentId = input.optString("itemId").trim()
                    if (residentId.isBlank()) return null
                    args.put("itemId", residentId)
                    if (action == "interact_resident") {
                        val interaction = input.optString("activityId").trim()
                        if (interaction !in setOf("greet", "chat", "ask_place", "sit_together") +
                            DigitalWorldResidentEpisodeStore.interactions) return null
                        if (interaction == "continue_episode" && input.optString("incidentId").isBlank()) return null
                        if (interaction in DigitalWorldResidentEpisodeStore.starts && input.optString("text").isBlank()) return null
                        args.put("activityId", interaction).put("text", input.optString("text"))
                            .put("incidentId", input.optString("incidentId"))
                    }
                }
                if (action == "handle_incident") {
                    val incidentId = input.optString("incidentId").trim()
                    val approach = input.optString("approach").trim().lowercase()
                    if (incidentId.isBlank() || !validIncident(incidentId, approach)) return null
                    args.put("incidentId", incidentId).put("approach", approach)
                }
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
        // A role-authored reference is accepted only if it resolves to this
        // character's active motive; never infer ownership by similar prose.
        val linkedMotiveId = CharacterMotiveProgress.resolveMotiveId(
            CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
            intended.optString("motiveId").ifBlank { appraisal.optString("motiveId") },
            intended.optString("motiveRef"), evidenceId,
        )
        if (intended.optString("stage").equals("considering", ignoreCase = true)) {
            return consider(context, characterId, intended, evidenceId, linkedMotiveId)
        }
        val available = if (intended.optString("action") == "reading")
            ReadingBackgroundBridge.theaterBooks(context, characterId) else emptyList()
        val books = available.mapTo(hashSetOf()) { it.id }
        val candidate = parse(
            intended, books, DigitalLifeProfileStore.isEnabled(characterId),
        ) { incidentId, approach ->
            DigitalWorldLifeEventStore.canHandle(characterId, incidentId, approach)
        } ?: return false
        intended.optJSONObject("expectation")?.let { candidate.args.put("expectation", it) }
        // Keep an explicit scheduled-plan link even when this activity is executed
        // later via the shared private-life worker rather than a direct tool call.
        intended.optString("planId").takeIf(String::isNotBlank)?.let {
            candidate.args.put("planId", it)
        }
        val selected = if (candidate.tool == "read_book") {
            val title = available.firstOrNull { it.id == intended.optString("readingBookId") }?.title
                ?: return false
            candidate.copy(label = "翻开《${title.take(90)}》")
        } else if (candidate.args.optString("worldAction") == "handle_incident") {
            candidate.copy(label = "尝试处理数字世界里尚未解决的生活事件")
        } else candidate
        val whyNow = intended.optString("whyNow").trim().take(200)
        if (whyNow.length < 3) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString("activity:$characterId", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val now = System.currentTimeMillis()
        if (previous?.optString("status") in setOf("pending", "running") &&
            now - (previous?.optLong("createdAt") ?: 0L) in 0L..(60 * 60_000L)) return false
        if (previous?.optString("signature") == selected.signature &&
            now - (previous?.optLong("createdAt") ?: 0L) in 0L..REPEAT_WINDOW_MS) return false
        if (evidenceId.isNotBlank() && previous?.optString("evidenceId") == evidenceId) return false

        val id = UUID.randomUUID().toString()
        val record = JSONObject()
            .put("id", id).put("characterId", characterId)
            .put("signature", selected.signature).put("createdAt", now)
            .put("whyNow", whyNow).put("evidenceId", evidenceId.take(180))
            .put("motiveId", linkedMotiveId)
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

    /**
     * An unfinished idea is a subjective, revocable option. It may trigger one
     * future autonomous deliberation, but never executes a tool on its own.
     */
    private fun consider(context: Context, characterId: String,
                         intent: JSONObject, evidenceId: String, motiveId: String): Boolean {
        val action = intent.optString("action").trim().lowercase()
        val reason = intent.optString("whyNow").trim().take(180)
        if (action !in setOf("reading", "solo_game", "digital_world") ||
            reason.length < 3) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString("considering:$characterId", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (old?.optString("evidenceId") == evidenceId && evidenceId.isNotBlank()) return false
        val now = System.currentTimeMillis()
        val record = JSONObject()
            .put("stage", "considering").put("action", action)
            .put("whyNow", reason).put("createdAt", now)
            .put("evidenceId", evidenceId.take(180)).put("motiveId", motiveId)
        if (!prefs.edit().putString("considering:$characterId", record.toString()).commit()) return false
        ProactivePerceptionScheduler.scheduleOwnIdeaFollowThrough(context, characterId)
        return true
    }

    fun latestConsideration(context: Context, characterId: String): JSONObject? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("considering:$characterId", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { System.currentTimeMillis() - it.optLong("createdAt") in 0L..(4 * 60 * 60_000L) }

    /** A successful physical receipt consumes its old consideration. */
    internal fun resolveConsideration(context: Context, characterId: String, action: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "considering:$characterId"
        val idea = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val actual = when (action) {
            "read_book" -> "reading"
            "play_solo_game" -> "solo_game"
            "digital_world_action" -> "digital_world"
            else -> action
        }
        if (actual == idea.optString("action")) prefs.edit().remove(key).commit()
    }

    fun latest(context: Context, characterId: String): JSONObject? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("activity:$characterId", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Removing an origin revokes queued intent; removing its tool receipt
     * also removes the cached completed summary from future model context. */
    internal fun invalidateEvidence(context: Context, evidenceId: String) {
        if (evidenceId.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("activity:") || it.startsWith("considering:") }.forEach { key ->
            val entry = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@forEach
            val origin = entry.optString("evidenceId")
            val role = key.substringAfter(':')
            if (origin == evidenceId || origin.contains(":$evidenceId:") ||
                key.startsWith("activity:") && "tool-$role-self-activity-${entry.optString("id")}" == evidenceId)
                editor.remove(key)
        }
        editor.commit()
    }

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
                            success: Boolean, summary: String, attempted: Boolean = true) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "activity:$characterId"
        val prior = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return
        if (prior.optString("id") != id ||
            prior.optString("status") in setOf("completed", "failed")) return
        val finishedAt = Instant.now()
        prior.put("status", if (success) "completed" else "failed")
            .put("result", summary.take(240)).put("finishedAt", finishedAt.toString())
        if (!prefs.edit().putString(key, prior.toString()).commit()) return
        if (attempted) CharacterInnerLifeStore.recordActionResult(
            characterId, prior.optString("motiveId"),
            "tool-$characterId-self-activity-$id",
            prior.optString("tool"), success, summary, finishedAt,
        )
        if (success) resolveConsideration(context, characterId, prior.optString("tool"))
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
                    false, "行动意向已过时，没有假装执行", attempted = false)
                return Result.success()
            }
            // A paused/deleted motive revokes a still-pending linked action.
            // Unlinked activities retain their original behavior.
            val motiveId = entry.optString("motiveId")
            if (motiveId.isNotBlank() &&
                CharacterMotiveProgress.resolveMotiveId(
                    CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
                    motiveId, "", "") != motiveId) {
                CharacterSelfActivityRuntime.markResult(applicationContext, characterId, id,
                    false, "原来的私人愿望已经暂停或不存在，没有执行", attempted = false)
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
                reason = entry.optString("whyNow"),
                chosenMotiveId = entry.optString("motiveId"),
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
