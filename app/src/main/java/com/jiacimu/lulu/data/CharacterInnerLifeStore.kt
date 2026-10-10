package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A character's subjective, revisable inner life. World facts and action results remain
 * with the authoritative stores; model prose alone never means an action succeeded.
 */
object CharacterInnerLifeStore {
    private var prefs: android.content.SharedPreferences? = null
    private val revisionState = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = revisionState

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("lulu_inner_life_v1", Context.MODE_PRIVATE)
    }

    @Synchronized fun snapshot(characterId: String): JSONObject =
        runCatching { JSONObject(prefs?.getString(characterId, "{}") ?: "{}") }.getOrDefault(JSONObject())

    @Synchronized fun clear(characterId: String) {
        if (prefs?.edit()?.remove(characterId)?.commit() == true) revisionState.value += 1L
    }

    private fun save(id: String, state: JSONObject) {
        check(prefs?.edit()?.putString(id, state.toString())?.commit() == true) {
            "内在生活状态尚未初始化或保存失败"
        }
        revisionState.value += 1L
    }

    /** The user may stop a pending thought without erasing the role's other life threads. */
    @Synchronized fun stopMotive(characterId: String, motiveId: String) {
        if (prefs == null || motiveId.isBlank()) return
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: return
        val remaining = JSONArray()
        var removed = false
        for (i in 0 until motives.length()) {
            val item = motives.optJSONObject(i) ?: continue
            if (item.optString("id") == motiveId) removed = true else remaining.put(item)
        }
        if (removed) {
            root.put("motives", remaining)
            save(characterId, root)
        }
    }

    /**
     * A one-time aftercare decision is appropriate only after a recent intense feeling.
     * Silence is still a valid choice; this does not require a diary, post or apology.
     */
    fun needsPostOnlineReflection(characterId: String, now: Instant = Instant.now()): Boolean {
        val root = snapshot(characterId)
        val emotion = root.optJSONObject("emotion")
        if (emotion != null) {
            val at = runCatching { Instant.parse(emotion.optString("startedAt")) }.getOrNull()
            val recent = at != null && !at.isAfter(now) &&
                Duration.between(at, now).toMinutes() in 0..60
            val intense = emotion.optInt("strength", 2) >= 3 ||
                Regex("后悔|愧疚|自责|难过|委屈|心疼|吵架|伤心|生气|懊悔")
                    .containsMatchIn(emotion.optString("feeling"))
            if (recent && intense) return true
        }
        val afterglow = CharacterLifeStore.state(characterId).optJSONObject("afterglow") ?: return false
        val started = runCatching { Instant.parse(afterglow.optString("startedAt")) }.getOrNull() ?: return false
        return !started.isAfter(now) && Duration.between(started, now).toMinutes() in 0..60 &&
            Regex("后悔|愧疚|自责|难过|委屈|心疼|吵架|伤心|生气|懊悔")
                .containsMatchIn(afterglow.optString("feeling"))
    }

    /** Individual raw-source deletion also invalidates subjective conclusions derived from it. */
    @Synchronized fun invalidateEvidence(eventId: String) {
        if (eventId.isBlank()) return
        PerceptionStimulusLedger.invalidate(eventId)
        CharacterCuriosityRuntime.invalidateEvidence(eventId)
        val ids = prefs?.all?.keys.orEmpty()
        fun backedBy(value: String): Boolean =
            value == eventId || value.contains(":$eventId:")
        ids.forEach { characterId ->
            val root = snapshot(characterId)
            var changed = false
            val oldSeen = root.optJSONArray("seen") ?: JSONArray()
            val seen = JSONArray()
            for (i in 0 until oldSeen.length()) {
                val key = oldSeen.optString(i)
                if (backedBy(key.substringBeforeLast(":"))) changed = true else seen.put(key)
            }
            root.put("seen", seen)
            listOf("emotion").forEach { key ->
                val record = root.optJSONObject(key)
                if (record != null && backedBy(record.optString("evidenceId"))) {
                    root.remove(key); changed = true
                }
            }
            // A deleted event must not survive as an earlier emotional influence.
            root.optJSONArray("emotionHistory")?.let { history ->
                val retained = JSONArray()
                for (i in 0 until history.length()) {
                    val entry = history.optJSONObject(i) ?: continue
                    if (backedBy(entry.optString("evidenceId"))) changed = true
                    else retained.put(entry)
                }
                root.put("emotionHistory", retained)
            }
            listOf("motives", "corrections", "voice", "innerVoices", "thoughts", "causalTransitions", "groundingEvents").forEach { key ->
                val values = root.optJSONArray(key) ?: return@forEach
                val next = JSONArray()
                for (i in 0 until values.length()) {
                    val entry = values.optJSONObject(i) ?: continue
                    val linked = backedBy(entry.optString("evidenceId")) ||
                        (key == "voice" && backedBy(entry.optString("id")))
                    if (linked) changed = true else next.put(entry)
                }
                root.put(key, next)
            }
            val bonds = root.optJSONObject("bonds")
            if (bonds != null) {
                bonds.keys().asSequence().toList().forEach { target ->
                    val opinion = bonds.optJSONObject(target) ?: return@forEach
                    val history = opinion.optJSONArray("encounters") ?: JSONArray()
                    val retained = JSONArray()
                    for (i in 0 until history.length()) {
                        val encounter = history.optJSONObject(i) ?: continue
                        if (backedBy(encounter.optString("source"))) changed = true
                        else retained.put(encounter)
                    }
                    if (retained.length() == 0 && backedBy(opinion.optString("evidenceId"))) {
                        bonds.remove(target); changed = true
                    } else if (retained.length() > 0 && retained.length() != history.length()) {
                        val last = retained.optJSONObject(retained.length() - 1)
                        opinion.put("encounters", retained)
                            .put("interpretation", last?.optString("thought"))
                            .put("reason", last?.optString("because"))
                            .put("evidenceId", last?.optString("source"))
                            .put("trends", relationTrends(retained))
                        changed = true
                    }
                }
            }
            val interactions = root.optJSONObject("interactions")
            if (interactions != null) {
                interactions.keys().asSequence().toList().forEach { key ->
                    val state = interactions.optJSONObject(key) ?: return@forEach
                    val history = state.optJSONArray("history") ?: JSONArray()
                    val retained = JSONArray()
                    for (i in 0 until history.length()) {
                        val item = history.optJSONObject(i) ?: continue
                        if (backedBy(item.optString("source"))) changed = true
                        else retained.put(item)
                    }
                    if (retained.length() == 0 && history.length() > 0) {
                        interactions.remove(key)
                        changed = true
                    } else if (retained.length() != history.length()) {
                        val last = retained.optJSONObject(retained.length() - 1)
                        val rebuilt = JSONObject()
                            .put("history", retained)
                            .put("lastMeaning", last?.optString("meaning").orEmpty())
                            .put("lastAim", last?.optString("responseAim").orEmpty())
                            .put("lastCommonGroundUpdate", last?.optString("commonGroundUpdate").orEmpty())
                            .put("lastMove", last?.optString("interactionMove").orEmpty())
                            .put("evidenceId", last?.optString("source").orEmpty())
                            .put("updatedAt", last?.optString("at").orEmpty())
                        var restoredUncertainty = ""
                        for (i in retained.length() - 1 downTo 0) {
                            val item = retained.optJSONObject(i) ?: continue
                            if (item.has("uncertainty")) {
                                restoredUncertainty = item.optString("uncertainty")
                                break
                            }
                        }
                        rebuilt.put("uncertainty", restoredUncertainty)
                        interactions.put(key, rebuilt)
                        changed = true
                    }
                }
            }
            if (changed) save(characterId, root)
        }
    }

    /**
     * Existing afterglow already records truthful subjective reactions.
     * Feed it into continuous emotion as well, without paying for another LLM call or
     * assuming something actually happened just because the role imagined it.
     */
    internal fun withAfterglow(
        proposal: JSONObject?, afterglow: JSONObject?, witnessedInput: String,
    ): JSONObject? {
        if (proposal?.optJSONObject("emotion") != null) return proposal
        val feeling = afterglow?.optString("feeling")?.trim().orEmpty()
        if (feeling.isBlank() || witnessedInput.isBlank()) return proposal
        val merged = proposal?.let { JSONObject(it.toString()) } ?: JSONObject()
        val hours = afterglow?.optInt("holdHours", 2) ?: 2
        merged.put("emotion", JSONObject().put("feeling", feeling.take(120))
            .put("impulse", afterglow?.optString("impulse").orEmpty().take(150))
            .put("cause", witnessedInput.take(180))
            .put("halfLifeMinutes", (hours * 30).coerceIn(30, 1440)))
        return merged
    }

    /**
     * An observation must have a real event ID and source text.
     * Duplicate evidence does not repeatedly elevate feelings or relationships.
     */
    @Synchronized fun observe(
        characterId: String,
        evidenceId: String,
        evidenceText: String,
        proposal: JSONObject?,
        allowedSocialIds: Set<String> = emptySet(),
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank() ||
            evidenceText.isBlank() || proposal == null) return
        val root = snapshot(characterId)
        val evidenceKey = evidenceId + ":" + evidenceText.hashCode()
        val seen = root.optJSONArray("seen") ?: JSONArray()
        if ((0 until seen.length()).any { seen.optString(it) == evidenceKey }) return
        root.put("seen", JSONArray().apply {
            for (i in maxOf(0, seen.length() - 39) until seen.length()) put(seen.optString(i))
            put(evidenceKey)
        })
        proposal.optJSONObject("emotion")?.let { emotion ->
            val feeling = emotion.optString("feeling").trim().take(120)
            val cause = emotion.optString("cause").trim().take(180)
            if (feeling.isNotBlank() && cause.isNotBlank()) {
                val previous = root.optJSONObject("emotion")
                val entry = JSONObject().put("feeling", feeling)
                    .put("cause", cause)
                    .put("otherFeeling", emotion.optString("otherFeeling").trim().take(100))
                    .put("impulse", emotion.optString("impulse").trim().take(150))
                    .put("restraint", emotion.optString("restraint").trim().take(150))
                    .put("physicalCue", emotion.optString("physicalCue").trim().take(120))
                    .put("outwardCue", emotion.optString("outwardCue").trim().take(120))
                    .put("strength", emotion.optInt("strength", 2).coerceIn(1, 4))
                    .put("startedAt", if (previous?.optString("evidenceId") == evidenceId)
                        previous.optString("startedAt", now.toString()) else now.toString())
                    .put("halfLifeMinutes", emotion.optInt("halfLifeMinutes", 180).coerceIn(30, 1440))
                    .put("evidenceId", evidenceId)
                // Keep the previous emotional course as witnessed history, rather than overwriting
                // a complex reaction every time the model supplies a fresh feeling.
                val history = root.optJSONArray("emotionHistory") ?: JSONArray()
                val nextHistory = JSONArray()
                for (i in maxOf(0, history.length() - 9) until history.length()) {
                    nextHistory.put(history.opt(i))
                }
                if (previous != null && previous.optString("evidenceId") != evidenceId) {
                    nextHistory.put(previous)
                }
                root.put("emotionHistory", nextHistory)
                root.put("emotion", entry)
            }
        }
        // Parallel, sometimes contradictory impulses are transient subjective
        // viewpoints, not tool requests or evidence that an action occurred.
        proposal.optJSONArray("thoughts")?.let { proposed ->
            val history = root.optJSONArray("thoughts") ?: JSONArray()
            val updated = mutableListOf<JSONObject>()
            for (index in maxOf(0, history.length() - 15) until history.length()) {
                history.optJSONObject(index)?.let(updated::add)
            }
            val seenThoughts = updated.map { it.optString("thought") }.toMutableSet()
            for (index in 0 until minOf(proposed.length(), 4)) {
                val item = proposed.optJSONObject(index) ?: continue
                val thought = item.optString("thought").replace(Regex("\\s+"), " ").trim().take(180)
                if (thought.isBlank() || !seenThoughts.add(thought)) continue
                updated += JSONObject()
                    .put("thought", thought)
                    .put("impulse", item.optString("impulse").trim().take(120))
                    .put("hesitation", item.optString("hesitation").trim().take(120))
                    .put("evidenceId", evidenceId).put("at", now.toString())
            }
            root.put("thoughts", JSONArray().apply { updated.takeLast(16).forEach { put(it) } })
        }
        val motives = root.optJSONArray("motives") ?: JSONArray()
        val records = (0 until motives.length()).mapNotNull(motives::optJSONObject).toMutableList()
        proposal.optJSONArray("motives")?.let { changes ->
            for (index in 0 until minOf(3, changes.length())) {
                val change = changes.optJSONObject(index) ?: continue
                val op = change.optString("op").lowercase()
                val id = change.optString("id")
                val item = records.firstOrNull { it.optString("id") == id }
                val reason = change.optString("reason").trim().take(160)
                when (op) {
                    "start" -> {
                        val aim = change.optString("aim").trim().take(180)
                        val why = change.optString("why").trim().take(160)
                        val legacyAim = CharacterLifeStore.state(characterId)
                            .optJSONObject("intention")?.optString("aim").orEmpty()
                        if (aim.isBlank() || why.isBlank() || records.size >= 6 ||
                            sameCharacterMotive(legacyAim, aim) ||
                            records.any { sameCharacterMotive(it.optString("aim"), aim) }) continue
                        records += JSONObject().put("id", UUID.randomUUID().toString())
                            .put("aim", aim).put("why", why)
                            .put("priority", change.optInt("priority", 2).coerceIn(1, 3))
                            .put("status", "active").put("createdAt", now.toString())
                            .put("evidenceId", evidenceId).put("outcomes", JSONArray())
                    }
                    "revise", "pause", "resume", "release" -> {
                        if (item == null || reason.isBlank()) continue
                        if (op == "revise") {
                            val aim = change.optString("aim").trim().take(180)
                            val why = change.optString("why").trim().take(160)
                            if (aim.isBlank() || why.isBlank()) continue
                            item.put("aim", aim).put("why", why)
                        }
                        if (op == "release") records.remove(item)
                        else {
                            if (op != "revise") item.put("status", if (op == "pause") "paused" else "active")
                            item.put("changedBecause", reason).put("updatedAt", now.toString())
                        }
                    }
                }
            }
        }
        root.put("motives", JSONArray().apply { records.forEach { put(it) } })
        proposal.optJSONObject("social")?.let { observation ->
            val target = observation.optString("targetId")
            val thought = observation.optString("interpretation").trim().take(180)
            if (target in allowedSocialIds && target != characterId && thought.isNotBlank()) {
                val bonds = root.optJSONObject("bonds") ?: JSONObject()
                val old = bonds.optJSONObject(target)
                val events = old?.optJSONArray("encounters") ?: JSONArray()
                val recent = JSONArray()
                for (i in maxOf(0, events.length() - 7) until events.length()) recent.put(events.opt(i))
                val signals = normalizedRelationSignals(observation.optJSONObject("dimensions"))
                val encounter = JSONObject().put("thought", thought)
                    .put("because", observation.optString("reason").trim().take(180))
                    .put("source", evidenceId).put("at", now.toString())
                if (signals.length() > 0) encounter.put("signals", signals)
                recent.put(encounter)
                bonds.put(target, JSONObject().put("interpretation", thought)
                    .put("reason", observation.optString("reason").trim().take(180))
                    .put("priorThought", old?.optString("interpretation").orEmpty().take(120))
                    .put("observations", (old?.optInt("observations", 0) ?: 0).coerceAtMost(999) + 1)
                    .put("encounters", recent)
                    .put("trends", relationTrends(recent))
                    .put("evidenceId", evidenceId).put("updatedAt", now.toString()))
                root.put("bonds", bonds)
            }
        }
        proposal.optJSONObject("selfCorrection")?.let { correction ->
            val insight = correction.optString("realization").trim().take(180)
            val next = correction.optString("nextTime").trim().take(180)
            if (insight.isNotBlank() && next.isNotBlank()) {
                val old = root.optJSONArray("corrections") ?: JSONArray()
                root.put("corrections", JSONArray().apply {
                    for (i in maxOf(0, old.length() - 7) until old.length()) put(old.opt(i))
                    put(JSONObject().put("realization", insight).put("nextTime", next)
                        .put("evidenceId", evidenceId).put("at", now.toString()))
                })
            }
        }
        save(characterId, root)
    }

    /**
     * Conversation common ground is a short-lived, evidence-bound working state.
     * It is not a new memory system and never outranks the actual chat transcript.
     */
    @Synchronized fun recordInteractionAppraisal(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        appraisal: JSONObject?,
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || conversationKey.isBlank() ||
            evidenceId.isBlank() || appraisal == null) return
        val meaning = appraisal.optString("meaning").trim().take(240)
        val responseAim = appraisal.optString("responseAim").trim().take(180)
        val commonGround = appraisal.optString("commonGroundUpdate").trim().take(240)
        val uncertainty = appraisal.optString("uncertainty").trim().take(180)
        val requestedMove = appraisal.optString("interactionMove").trim().lowercase()
        val move = requestedMove.takeIf {
            it in setOf("acknowledge", "answer", "repair", "ask", "share", "tease", "decline", "shift", "silent")
        }.orEmpty()
        val uncertaintySpecified = appraisal.has("uncertainty")
        if (meaning.isBlank() && responseAim.isBlank() && commonGround.isBlank() &&
            !uncertaintySpecified && move.isBlank()) return

        val key = conversationKey.trim().take(120)
        val root = snapshot(characterId)
        val interactions = root.optJSONObject("interactions") ?: JSONObject()
        val old = interactions.optJSONObject(key)
        val history = old?.optJSONArray("history") ?: JSONArray()
        if ((0 until history.length()).any { history.optJSONObject(it)?.optString("source") == evidenceId }) return

        val entry = JSONObject()
            .put("meaning", meaning)
            .put("responseAim", responseAim)
            .put("commonGroundUpdate", commonGround)
            .put("interactionMove", move)
            .put("source", evidenceId)
            .put("at", now.toString())
        if (uncertaintySpecified) entry.put("uncertainty", uncertainty)

        val updated = JSONArray().apply {
            for (i in maxOf(0, history.length() - 10) until history.length()) put(history.opt(i))
            put(entry)
        }
        val next = JSONObject()
            .put("history", updated)
            .put("lastMeaning", meaning.ifBlank { old?.optString("lastMeaning").orEmpty() })
            .put("lastAim", responseAim.ifBlank { old?.optString("lastAim").orEmpty() })
            .put("lastCommonGroundUpdate", commonGround.ifBlank { old?.optString("lastCommonGroundUpdate").orEmpty() })
            .put("lastMove", move.ifBlank { old?.optString("lastMove").orEmpty() })
            .put("uncertainty", if (uncertaintySpecified) uncertainty else old?.optString("uncertainty").orEmpty())
            .put("evidenceId", evidenceId)
            .put("updatedAt", now.toString())
        interactions.put(key, next)
        root.put("interactions", interactions)
        save(characterId, root)
    }

    fun interactionContext(
        characterId: String,
        conversationKey: String,
        now: Instant = Instant.now(),
    ): String {
        val key = conversationKey.trim().take(120)
        if (key.isBlank()) return ""
        val state = snapshot(characterId).optJSONObject("interactions")?.optJSONObject(key) ?: return ""
        val history = state.optJSONArray("history") ?: JSONArray()
        if (history.length() == 0) return ""
        val updatedAt = runCatching { Instant.parse(state.optString("updatedAt")) }.getOrNull()
        val ageHours = updatedAt?.let { Duration.between(it, now).toHours().coerceAtLeast(0) }
        return buildString {
            appendLine("【当前互动共同语境｜主观会话工作记忆，不是长期人格或客观事实】")
            val start = maxOf(0, history.length() - 4)
            for (i in start until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val pieces = buildList {
                    item.optString("commonGroundUpdate").takeIf(String::isNotBlank)
                        ?.let { add("共同语境更新=${it.take(180)}") }
                    item.optString("interactionMove").takeIf(String::isNotBlank)
                        ?.let { add("互动动作=$it") }
                    item.optString("meaning").takeIf(String::isNotBlank)
                        ?.let { add("当时理解=${it.take(150)}") }
                }
                if (pieces.isNotEmpty()) appendLine("· ${pieces.joinToString("；")}")
            }
            state.optString("uncertainty").takeIf(String::isNotBlank)?.let {
                appendLine("仍未确认的点：${it.take(180)}。没有新证据时保持不确定，不自行补全。")
            }
            if (ageHours != null && ageHours >= 72) {
                appendLine("这段共同语境距今约${ageHours}小时；只有当前话题确实延续时才沿用，新的明确说法优先。")
            }
            appendLine("若对方刚刚纠正、否认或澄清，以最新修复覆盖旧推断；不要为了维持旧理解而和新消息对抗。")
        }.trim()
    }

    @Synchronized fun recordGroundingCandidate(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        content: String,
        confidence: Double,
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || conversationKey.isBlank() ||
            evidenceId.isBlank() || content.isBlank()) return
        val root = snapshot(characterId)
        val events = root.optJSONArray("groundingEvents") ?: JSONArray()
        val cleanContent = content.replace(Regex("\\s+"), " ").trim().take(260)
        val propositionId = "p:" + conversationKey.hashCode().toUInt().toString(16) + ":" +
            cleanContent.hashCode().toUInt().toString(16)

        val latestById = linkedMapOf<String, JSONObject>()
        for (i in 0 until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            if (item.optString("conversationKey") != conversationKey) continue
            latestById[item.optString("propositionId")] = item
        }
        val duplicate = latestById[propositionId]
        if (duplicate?.optString("action") == "candidate" &&
            duplicate.optString("evidenceId") == evidenceId) return

        // A new candidate replaces older unconfirmed guesses instead of letting several
        // incompatible interpretations remain active at the same time.
        val superseded = latestById.values.filter {
            it.optString("action") == "candidate" &&
                it.optString("propositionId") != propositionId
        }.takeLast(4)

        val next = JSONArray().apply {
            for (i in maxOf(0, events.length() - 24) until events.length()) put(events.opt(i))
            superseded.forEach { candidate ->
                put(JSONObject()
                    .put("conversationKey", conversationKey.take(120))
                    .put("propositionId", candidate.optString("propositionId"))
                    .put("action", "superseded")
                    .put("content", candidate.optString("content").take(260))
                    .put("reason", "出现了更新的候选理解")
                    .put("evidenceId", evidenceId)
                    .put("at", now.toString()))
            }
            put(JSONObject()
                .put("conversationKey", conversationKey.take(120))
                .put("propositionId", propositionId)
                .put("action", "candidate")
                .put("content", cleanContent)
                .put("confidence", confidence.coerceIn(0.0, 1.0))
                .put("evidenceId", evidenceId)
                .put("at", now.toString()))
        }
        root.put("groundingEvents", next)
        save(characterId, root)
    }

    @Synchronized fun acceptLatestGroundingCandidate(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        reason: String = "用户明确确认了上一轮候选理解",
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || conversationKey.isBlank() || evidenceId.isBlank()) return
        val root = snapshot(characterId)
        val events = root.optJSONArray("groundingEvents") ?: return
        val latestById = linkedMapOf<String, JSONObject>()
        for (i in 0 until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            if (item.optString("conversationKey") != conversationKey) continue
            latestById[item.optString("propositionId")] = item
        }
        val candidate = latestById.values.lastOrNull { it.optString("action") == "candidate" } ?: return
        val next = JSONArray().apply {
            for (i in maxOf(0, events.length() - 28) until events.length()) put(events.opt(i))
            put(JSONObject()
                .put("conversationKey", conversationKey.take(120))
                .put("propositionId", candidate.optString("propositionId"))
                .put("action", "grounded")
                .put("content", candidate.optString("content").take(260))
                .put("confidence", 1.0)
                .put("reason", reason.take(180))
                .put("evidenceId", evidenceId)
                .put("at", now.toString()))
        }
        root.put("groundingEvents", next)
        save(characterId, root)
    }

    @Synchronized fun rejectGroundingCandidates(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        reason: String,
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || conversationKey.isBlank() || evidenceId.isBlank()) return
        val root = snapshot(characterId)
        val events = root.optJSONArray("groundingEvents") ?: return
        val latestById = linkedMapOf<String, JSONObject>()
        for (i in 0 until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            if (item.optString("conversationKey") != conversationKey) continue
            latestById[item.optString("propositionId")] = item
        }
        val active = latestById.values.filter { it.optString("action") == "candidate" }
        if (active.isEmpty()) return
        val next = JSONArray().apply {
            for (i in maxOf(0, events.length() - 25) until events.length()) put(events.opt(i))
            active.takeLast(4).forEach { candidate ->
                put(JSONObject()
                    .put("conversationKey", conversationKey.take(120))
                    .put("propositionId", candidate.optString("propositionId"))
                    .put("action", "rejected")
                    .put("content", candidate.optString("content").take(260))
                    .put("reason", reason.take(180))
                    .put("evidenceId", evidenceId)
                    .put("at", now.toString()))
            }
        }
        root.put("groundingEvents", next)
        save(characterId, root)
    }

    fun groundingContext(characterId: String, conversationKey: String): String {
        val events = snapshot(characterId).optJSONArray("groundingEvents") ?: return ""
        val latestById = linkedMapOf<String, JSONObject>()
        for (i in 0 until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            if (item.optString("conversationKey") != conversationKey) continue
            latestById[item.optString("propositionId")] = item
        }
        if (latestById.isEmpty()) return ""
        val active = latestById.values.filter { it.optString("action") == "candidate" }
        val grounded = latestById.values.filter { it.optString("action") == "grounded" }.takeLast(2)
        val rejected = latestById.values.filter { it.optString("action") == "rejected" }.takeLast(3)
        return buildString {
            appendLine("【程序化共同理解状态】")
            grounded.forEach { item ->
                appendLine(
                    "双方已经明确确认的理解：" + item.optString("content").take(220) +
                        "。除非后续出现纠正，否则可以把它作为当前共同语境。"
                )
            }
            active.takeLast(3).forEach { item ->
                appendLine(
                    "候选理解（尚未确认，不能当事实）：" + item.optString("content").take(220) +
                        "；confidence=" + "%.2f".format(item.optDouble("confidence", 0.0))
                )
            }
            rejected.forEach { item ->
                appendLine(
                    "已被用户否定的旧理解：" + item.optString("content").take(220) +
                        "。后续不得沿用或围绕它继续发挥。"
                )
            }
        }.trim()
    }

    private val relationAxes = listOf("trust", "warmth", "ease", "friction", "boundarySafety")

    private fun normalizedRelationSignals(raw: JSONObject?): JSONObject {
        val result = JSONObject()
        relationAxes.forEach { axis ->
            val value = raw?.optString(axis)?.trim()?.lowercase().orEmpty()
            if (value in setOf("up", "down", "same")) result.put(axis, value)
        }
        return result
    }

    private fun relationTrends(encounters: JSONArray): JSONObject {
        val trends = JSONObject()
        relationAxes.forEach { axis ->
            var score = 0
            var count = 0
            for (i in maxOf(0, encounters.length() - 8) until encounters.length()) {
                val signal = encounters.optJSONObject(i)?.optJSONObject("signals")
                    ?.optString(axis).orEmpty()
                when (signal) {
                    "up" -> { score += 1; count += 1 }
                    "down" -> { score -= 1; count += 1 }
                    "same" -> count += 1
                }
            }
            val label = when {
                count < 2 -> "证据不足"
                score >= 2 -> "上升"
                score <= -2 -> "下降"
                else -> "大致稳定"
            }
            trends.put(axis, label)
        }
        return trends
    }

    private fun relationshipTrendText(bond: JSONObject): String {
        val trends = bond.optJSONObject("trends") ?: return ""
        val labels = listOf(
            "trust" to "信任",
            "warmth" to "亲近/温度",
            "ease" to "相处自在度",
            "friction" to "未解摩擦",
            "boundarySafety" to "边界安全感",
        )
        return labels.joinToString("；") { (key, label) ->
            "$label=${trends.optString(key, "证据不足")}"
        }
    }

    /** Never acknowledge an attempted action as completed without an executor receipt. */
    @Synchronized fun recordActionResult(
        characterId: String, motiveId: String, receiptId: String,
        action: String, success: Boolean, summary: String, now: Instant = Instant.now(),
    ) {
        if (prefs == null || motiveId.isBlank() || receiptId.isBlank()) return
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: return
        val motive = (0 until motives.length()).mapNotNull(motives::optJSONObject)
            .firstOrNull { it.optString("id") == motiveId } ?: return
        val previous = motive.optJSONArray("outcomes") ?: JSONArray()
        if ((0 until previous.length()).any { previous.optJSONObject(it)?.optString("id") == receiptId }) return
        motive.put("lastAttemptOutcome", if (success) "success" else "needs_review")
        motive.put("lastAttemptAt", now.toString())
        motive.put("outcomes", JSONArray().apply {
            for (i in maxOf(0, previous.length() - 7) until previous.length()) put(previous.opt(i))
            put(JSONObject().put("id", receiptId).put("action", action).put("success", success)
                .put("summary", summary.take(250)).put("at", now.toString()))
        })
        save(characterId, root)
    }

    /**
     * This is role-authored fictional inner speech, not objective evidence.
     * A thought remains part of the personal mental timeline even if it is never spoken.
     */
    @Synchronized fun recordInnerVoice(
        characterId: String,
        evidenceId: String,
        thought: String,
        now: Instant = Instant.now(),
        causeFingerprint: String = "",
    ) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank()) return
        val clean = thought.replace(Regex("[ \\t]+"), " ").trim().take(1_200)
        if (clean.isBlank()) return
        val root = snapshot(characterId)
        val old = root.optJSONArray("innerVoices") ?: JSONArray()
        if ((0 until old.length()).any { old.optJSONObject(it)?.optString("evidenceId") == evidenceId }) return
        val latest = old.optJSONObject(old.length() - 1)
        val latestAt = latest?.optString("occurredAt")?.takeIf(String::isNotBlank)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val recentMinutes = latestAt?.let { runCatching { Duration.between(it, now).toMinutes() }.getOrNull() }
        val cleanFingerprint = causeFingerprint.trim().take(120)
        if (cleanFingerprint.isNotBlank()) {
            for (i in maxOf(0, old.length() - 5) until old.length()) {
                val prior = old.optJSONObject(i) ?: continue
                val at = prior.optString("occurredAt").takeIf(String::isNotBlank)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: continue
                val minutes = runCatching { Duration.between(at, now).toMinutes() }.getOrNull() ?: continue
                if (minutes in 0..30 && prior.optString("causeFingerprint") == cleanFingerprint) return
            }
        }
        if (recentMinutes != null && recentMinutes in 0..10 &&
            sameInnerVoiceMeaning(latest?.optString("thought").orEmpty(), clean)) return
        root.put("innerVoices", JSONArray().apply {
            for (i in maxOf(0, old.length() - 5) until old.length()) put(old.opt(i))
            put(JSONObject().put("evidenceId", evidenceId).put("thought", clean)
                .put("causeFingerprint", cleanFingerprint)
                .put("occurredAt", now.toString()))
        })
        save(characterId, root)
    }

    /**
     * Auditable causal bridge from a real source event to the role's private interpretation,
     * state delta and selected action. This is not extra memory for the model to embellish;
     * it is a bounded provenance trail used to explain why a heart voice/state change existed.
     */
    @Synchronized fun recordCausalTransition(
        characterId: String,
        evidenceId: String,
        appraisal: JSONObject?,
        innerLife: JSONObject?,
        innerThoughtBasis: JSONObject?,
        selectedAction: String,
        innerThought: String,
        reason: String,
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank()) return
        val action = selectedAction.trim().lowercase().take(80)
        val thought = innerThought.replace(Regex("[ \\t]+"), " ").trim().take(500)
        val focus = innerThoughtBasis?.optString("focus").orEmpty().trim().take(220)
        val change = innerThoughtBasis?.optString("change").orEmpty().trim().take(220)
        val conflict = innerThoughtBasis?.optString("conflict").orEmpty().trim().take(220)
        val unsaidWhy = innerThoughtBasis?.optString("unsaidWhy").orEmpty().trim().take(220)
        val hasStateDelta = CharacterHeartVoicePolicy.hasStructuredDelta(innerLife)
        val meaning = appraisal?.optString("meaning").orEmpty().trim().take(240)
        val responseAim = appraisal?.optString("responseAim").orEmpty().trim().take(180)
        val move = appraisal?.optString("interactionMove").orEmpty().trim().lowercase().take(80)
        val uncertainty = appraisal?.optString("uncertainty").orEmpty().trim().take(180)

        if (!hasStateDelta && thought.isBlank() && focus.isBlank() && meaning.isBlank() &&
            responseAim.isBlank() && reason.isBlank()) return

        val root = snapshot(characterId)
        val past = root.optJSONArray("causalTransitions") ?: JSONArray()
        val id = "$evidenceId:$action"
        if ((0 until past.length()).any { past.optJSONObject(it)?.optString("id") == id }) return

        val entry = JSONObject()
            .put("id", id)
            .put("evidenceId", evidenceId)
            .put("selectedAction", action)
            .put("reason", reason.trim().take(240))
            .put("innerThought", thought)
            .put("at", now.toString())

        val appraisalTrace = JSONObject()
        if (meaning.isNotBlank()) appraisalTrace.put("meaning", meaning)
        if (responseAim.isNotBlank()) appraisalTrace.put("responseAim", responseAim)
        if (move.isNotBlank()) appraisalTrace.put("interactionMove", move)
        if (uncertainty.isNotBlank()) appraisalTrace.put("uncertainty", uncertainty)
        if (appraisalTrace.length() > 0) entry.put("appraisal", appraisalTrace)

        val basis = JSONObject()
        if (focus.isNotBlank()) basis.put("focus", focus)
        if (change.isNotBlank()) basis.put("change", change)
        if (conflict.isNotBlank()) basis.put("conflict", conflict)
        if (unsaidWhy.isNotBlank()) basis.put("unsaidWhy", unsaidWhy)
        if (basis.length() > 0) entry.put("innerThoughtBasis", basis)

        val delta = JSONObject()
        innerLife?.optJSONObject("emotion")?.let { emotion ->
            val feeling = emotion.optString("feeling").trim().take(120)
            val cause = emotion.optString("cause").trim().take(180)
            if (feeling.isNotBlank() || cause.isNotBlank()) {
                delta.put("emotion", JSONObject().put("feeling", feeling).put("cause", cause))
            }
        }
        val motiveCount = innerLife?.optJSONArray("motives")?.length() ?: 0
        val thoughtCount = innerLife?.optJSONArray("thoughts")?.length() ?: 0
        if (motiveCount > 0) delta.put("motiveChanges", motiveCount)
        if (thoughtCount > 0) delta.put("thoughtChanges", thoughtCount)
        innerLife?.optJSONObject("social")?.let { social ->
            val target = social.optString("targetId").trim()
            val interpretation = social.optString("interpretation").trim().take(180)
            if (target.isNotBlank() || interpretation.isNotBlank()) {
                delta.put("social", JSONObject()
                    .put("targetId", target.take(100))
                    .put("interpretation", interpretation))
            }
        }
        innerLife?.optJSONObject("selfCorrection")?.let { correction ->
            val realization = correction.optString("realization").trim().take(180)
            if (realization.isNotBlank()) delta.put("selfCorrection", realization)
        }
        if (delta.length() > 0) entry.put("stateDelta", delta)

        root.put("causalTransitions", JSONArray().apply {
            for (i in maxOf(0, past.length() - 19) until past.length()) put(past.opt(i))
            put(entry)
        })
        save(characterId, root)
    }

    /**
     * Auditable choices. Deliberating is not an external event: outcome is supplied by the
     * action executor only, and skipped choices are never recorded as actions.
     */
    @Synchronized fun recordDecision(
        characterId: String,
        decisionId: String,
        selectedAction: String,
        reason: String,
        chosenMotiveId: String,
        alternatives: JSONArray?,
        outcome: String,
        succeeded: Boolean,
        now: Instant = Instant.now(),
        actionSignature: String = "",
    ) {
        if (prefs == null || characterId.isBlank() || decisionId.isBlank()) return
        if (selectedAction == "silent" && reason.isBlank()) return
        val root = snapshot(characterId)
        val past = root.optJSONArray("decisions") ?: JSONArray()
        if ((0 until past.length()).any { past.optJSONObject(it)?.optString("id") == decisionId }) return
        val skipped = JSONArray()
        if (alternatives != null) {
            for (i in 0 until minOf(3, alternatives.length())) {
                val item = alternatives.optJSONObject(i) ?: continue
                val thought = item.optString("idea").trim().take(140)
                val whyNot = item.optString("whyNot").trim().take(160)
                if (thought.isNotBlank() && whyNot.isNotBlank()) {
                    skipped.put(JSONObject().put("idea", thought).put("whyNot", whyNot))
                }
            }
        }
        val updated = JSONArray()
        for (i in maxOf(0, past.length() - 11) until past.length()) updated.put(past.opt(i))
        updated.put(JSONObject()
            .put("id", decisionId).put("at", now.toString())
            .put("selected", selectedAction.take(90))
            .put("signature", actionSignature.take(180))
            .put("reason", reason.trim().take(240))
            .put("motiveId", chosenMotiveId.take(80))
            .put("alternatives", skipped)
            .put("succeeded", succeeded)
            .put("outcome", outcome.take(230)))
        root.put("decisions", updated)
        save(characterId, root)
    }

    /** Only call from an observed, persisted character message, never a speculative draft. */
    @Synchronized fun recordSpokenText(characterId: String, eventId: String, text: String) {
        if (prefs == null || eventId.isBlank()) return
        val sample = text.replace(Regex("\\s+"), " ").trim().take(140)
        if (sample.length < 3 || sample.startsWith("{")) return
        val root = snapshot(characterId)
        val old = root.optJSONArray("voice") ?: JSONArray()
        if ((0 until old.length()).any { old.optJSONObject(it)?.optString("id") == eventId }) return
        root.put("voice", JSONArray().apply {
            for (i in maxOf(0, old.length() - 9) until old.length()) put(old.opt(i))
            put(JSONObject().put("id", eventId).put("text", sample))
        })
        save(characterId, root)
    }


    private fun sameInnerVoiceMeaning(left: String, right: String): Boolean {
        fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_-]+"), "")
            .replace(Regex("^(还是|就是|只是|现在|这会儿|此刻|嗯|唔|好吧)+"), "")
        val a = normalize(left)
        val b = normalize(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        if (a.length < 4 || b.length < 4) return false
        val aPairs = a.windowed(2).toSet()
        val bPairs = b.windowed(2).toSet()
        val denominator = minOf(aPairs.size, bPairs.size).coerceAtLeast(1)
        return aPairs.intersect(bPairs).size.toDouble() / denominator >= 0.68
    }

    /** Durable but focused subjective state. Full introspection stays available to
     * background deliberation, world scenes and exact continuity/reconciliation turns.
     */
    fun compactContext(characterId: String, now: Instant = Instant.now()): String {
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: JSONArray()
        val active = (0 until motives.length()).mapNotNull(motives::optJSONObject)
            .filter { it.optString("status", "active") == "active" }
            .sortedByDescending { it.optInt("priority", 2) }
        val paused = (0 until motives.length()).mapNotNull(motives::optJSONObject)
            .filter { it.optString("status") == "paused" }
        val emotion = root.optJSONObject("emotion")
        val bonds = root.optJSONObject("bonds")
        val thoughts = root.optJSONArray("thoughts") ?: JSONArray()
        val decisions = root.optJSONArray("decisions") ?: JSONArray()
        return buildString {
            if (emotion != null) {
                val started = runCatching { Instant.parse(emotion.optString("startedAt")) }.getOrNull()
                val elapsed = started?.let { Duration.between(it, now).toMinutes().coerceAtLeast(0) } ?: Long.MAX_VALUE
                val halfLife = emotion.optInt("halfLifeMinutes", 180).coerceAtLeast(30)
                val baseStrength = emotion.optInt("strength", 2).coerceIn(1, 4)
                val influence = if (elapsed == Long.MAX_VALUE) 0.0
                    else baseStrength * Math.pow(0.5, elapsed.toDouble() / halfLife.toDouble())
                if (influence >= 0.35 && elapsed <= halfLife * 4L) {
                    val impact = when {
                        influence >= 3.0 -> "强"
                        influence >= 1.5 -> "中等"
                        influence >= 0.7 -> "较弱"
                        else -> "很淡"
                    }
                    appendLine("现在感受=${emotion.optString("feeling")}；并存=${emotion.optString("otherFeeling")}；由=${emotion.optString("cause").take(160)}；距今${elapsed}分钟；当前影响=$impact（会随时间自然衰减，旧事被想起不等于重新受刺激）")
                    emotion.optString("restraint").takeIf(String::isNotBlank)?.let { appendLine("此刻克制：${it.take(130)}") }
                }
            }
            val priorEmotions = root.optJSONArray("emotionHistory") ?: JSONArray()
            for (i in maxOf(0, priorEmotions.length() - 2) until priorEmotions.length()) {
                val prior = priorEmotions.optJSONObject(i) ?: continue
                val priorAt = runCatching { Instant.parse(prior.optString("startedAt")) }.getOrNull() ?: continue
                val elapsed = Duration.between(priorAt, now).toMinutes()
                if (elapsed !in 0..(prior.optInt("halfLifeMinutes", 180).coerceAtLeast(30) * 3L)) continue
                appendLine("此前仍可能有余波：${prior.optString("feeling")}；由=${prior.optString("cause").take(120)}；已过${elapsed}分钟。新感受不必立刻清除旧感受，按时间、强度和新事实权衡。")
            }
            if (active.isNotEmpty()) {
                appendLine("【仍在意的事｜可以权衡而非必须行动】")
                active.take(6).forEach { m ->
                    append("- id=${m.optString("id")}；目标=${m.optString("aim").take(170)}；缘由=${m.optString("why").take(120)}")
                    val outcomes = m.optJSONArray("outcomes")
                    if (outcomes != null && outcomes.length() > 0) {
                        val last = outcomes.optJSONObject(outcomes.length() - 1)
                        append("；最近行动=${last?.optString("summary")?.take(150)}")
                    }
                    appendLine()
                }
            }
            if (paused.isNotEmpty()) appendLine("暂缓：${paused.takeLast(2).joinToString("、") { it.optString("aim").take(90) }}")
            bonds?.keys()?.asSequence()?.take(4)?.forEach { id ->
                val bond = bonds.optJSONObject(id) ?: return@forEach
                appendLine("对${if (id == "user") "用户" else id}的主观看法：${bond.optString("interpretation").take(140)}；依据=${bond.optString("reason").take(110)}")
                relationshipTrendText(bond).takeIf(String::isNotBlank)?.let {
                    appendLine("关系近期趋势：$it（趋势不是好感分，也不能由单次互动定型）")
                }
            }
            val corrections = root.optJSONArray("corrections")
            if (corrections != null && corrections.length() > 0) {
                val last = corrections.optJSONObject(corrections.length() - 1)
                appendLine("最近自我修正：${last?.optString("nextTime")?.take(140)}")
            }
            val recentThought = (0 until thoughts.length()).mapNotNull(thoughts::optJSONObject)
                .lastOrNull { item ->
                    runCatching { Instant.parse(item.optString("at")) }.getOrNull()
                        ?.let { !it.isAfter(now) && Duration.between(it, now) <= Duration.ofHours(18) } == true
                }
            recentThought?.let { appendLine("还没说出的心事：${it.optString("thought").take(170)}；犹豫=${it.optString("hesitation").take(120)}") }
            if (decisions.length() > 0) {
                val last = decisions.optJSONObject(decisions.length() - 1)
                if (last != null && last.optString("selected") == "silent")
                    appendLine("上次主动保持安静：${last.optString("reason").take(130)}（不是失败）")
            }
        }.trim()
    }

    fun context(characterId: String, now: Instant = Instant.now()): String {
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: JSONArray()
        val emotion = root.optJSONObject("emotion")
        val emotionHistory = root.optJSONArray("emotionHistory") ?: JSONArray()
        val bonds = root.optJSONObject("bonds")
        val corrections = root.optJSONArray("corrections") ?: JSONArray()
        val voice = root.optJSONArray("voice") ?: JSONArray()
        val innerVoices = root.optJSONArray("innerVoices") ?: JSONArray()
        val thoughts = root.optJSONArray("thoughts") ?: JSONArray()
        val decisions = root.optJSONArray("decisions") ?: JSONArray()
        return buildString {
            appendLine("【角色持续内在生活｜主观状态而非客观事实】")
            val active = (0 until motives.length()).mapNotNull(motives::optJSONObject)
                .filter { it.optString("status", "active") == "active" }
            val paused = (0 until motives.length()).mapNotNull(motives::optJSONObject)
                .filter { it.optString("status") == "paused" }
            if (active.size > 1) {
                appendLine("现在并行牵挂${active.size}件事。不要每回合只按固定优先级执行；考虑此刻状态、执行条件、上次行动后果及角色本人的冲突。")
                val contrasting = active.sortedByDescending { it.optInt("priority", 2) }.take(3)
                appendLine("当下可权衡的心愿：${contrasting.joinToString("／") { it.optString("aim") }}。")
            }
            if (paused.isNotEmpty()) appendLine("另外有${paused.size}件事被暂时搁置，若没有新的理由不要硬恢复。")
            appendLine("连续人格链：真实记忆与现有信念 → 多种同时存在的感受 → 内在愿望和实际限制 → 本轮可以执行/克制的选择 → 言语和动作 → 真实后果 → 下轮反思。")
            appendLine("不要把心理状态列表念给用户；它应暗中影响自然的停顿、措辞、话题偏爱、行动选择、相处边界和对失败的态度。")
            appendLine("可以有私心、好奇、无聊、得意、嘴硬或反悔，必须对应个人设定与实际触发。不同性格的人反应方式不同，不要统一撒娇或每轮强烈心动。")
            if (motives.length() == 0) appendLine("尚无明确长期小目标，不能为了填满生活编造。")
            for (i in 0 until motives.length()) {
                val m = motives.optJSONObject(i) ?: continue
                appendLine("· 动机 id=${m.optString("id")}；${m.optString("status")}；优先级${m.optInt("priority")}；目标${m.optString("aim")}；缘由${m.optString("why")}")
                if (m.optString("lastAttemptOutcome") == "needs_review")
                    appendLine("  此愿望上次行动没做成：应按真实失败原因决定复试、暂停、求助或放弃，不得装成已经成功。")
                val history = m.optJSONArray("outcomes") ?: JSONArray()
                if (history.length() > 0) {
                    val last = history.optJSONObject(history.length() - 1)
                    appendLine("  最近实际执行：${last?.optString("action")}；成功=${last?.optBoolean("success")}；结果=${last?.optString("summary")}")
                }
            }
            if (emotion != null) {
                val from = runCatching { Instant.parse(emotion.optString("startedAt")) }.getOrNull()
                val mins = from?.let { Duration.between(it, now).toMinutes().coerceAtLeast(0) } ?: Long.MAX_VALUE
                if (mins <= emotion.optInt("halfLifeMinutes", 180).coerceAtLeast(30) * 3L) {
                    appendLine("当前情绪：${emotion.optString("feeling")}；另一层感受：${emotion.optString("otherFeeling")}；触发原因：${emotion.optString("cause")}；已经过去${mins}分钟。")
                    emotion.optString("impulse").takeIf(String::isNotBlank)?.let { appendLine("本能想做：$it") }
                    emotion.optString("restraint").takeIf(String::isNotBlank)?.let { appendLine("克制/犹豫：$it") }
                    emotion.optString("physicalCue").takeIf(String::isNotBlank)?.let { appendLine("自身可感知的身体反应：$it") }
                    emotion.optString("outwardCue").takeIf(String::isNotBlank)?.let { appendLine("可能被旁人察觉的变化：$it") }
                    appendLine("这些不是固定剧本：根据时间让情绪逐渐消退、叠加或被新事实扭转，而非每次从零开始。")
                }
            }
            if (emotionHistory.length() > 0) {
                appendLine("最近情绪变化的前因（避免无理由性格跳变）：")
                for (i in maxOf(0, emotionHistory.length() - 2) until emotionHistory.length()) {
                    val prior = emotionHistory.optJSONObject(i) ?: continue
                    val priorAt = runCatching { Instant.parse(prior.optString("startedAt")) }.getOrNull() ?: continue
                    if (Duration.between(priorAt, now).toMinutes() > prior.optInt("halfLifeMinutes", 180) * 3L) continue
                    appendLine("· ${prior.optString("feeling")}，源于${prior.optString("cause")}")
                }
            }
            bonds?.keys()?.asSequence()?.take(8)?.forEach { id ->
                val bond = bonds?.optJSONObject(id) ?: return@forEach
                appendLine("· 对${if (id == "user") "用户" else "角色$id"}的当前私人看法：${bond.optString("interpretation")}；因为${bond.optString("reason")}；此前想法：${bond.optString("priorThought")}；累计${bond.optInt("observations")}次实际互动推断。")
                relationshipTrendText(bond).takeIf(String::isNotBlank)?.let {
                    appendLine("  关系近期趋势：$it。这里只描述多个真实互动累积的方向，不是绝对好感度。")
                }
                appendLine("  不是绝对结论，观点可以纠结、相互矛盾，不能只凭一次聊天就彻底爱上/讨厌。")
            }
            if (corrections.length() > 0) {
                val last = corrections.optJSONObject(corrections.length() - 1)
                appendLine("自我修正：${last?.optString("realization")}；下次尝试：${last?.optString("nextTime")}。不要反复口头忏悔，以行动表现。")
            }
            val recentThoughts = (0 until thoughts.length()).mapNotNull(thoughts::optJSONObject)
                .filter { item ->
                    runCatching { Instant.parse(item.optString("at")) }.getOrNull()
                        ?.let { at -> !at.isAfter(now) && Duration.between(at, now) <= Duration.ofHours(24) } == true
                }.takeLast(6)
            if (recentThoughts.isNotEmpty()) {
                appendLine("【仍可能相互拉扯的多个念头｜主观设想，不是已执行的行动】")
                recentThoughts.forEach { item ->
                    appendLine("· ${item.optString("thought")}；冲动：${item.optString("impulse")}；犹豫：${item.optString("hesitation")}")
                }
                appendLine("这些念头可以冲突，也可以随着新事件消退。若要落实，先检查对方边界和实际工具；下一轮须依据真实结果修正，不得把想象写成执行成功。")
            }
            if (innerVoices.length() > 0) {
                appendLine("过往没说出口的真实主观心声（思绪可改变，不是已发生的事件）：")
                for (i in maxOf(0, innerVoices.length() - 3) until innerVoices.length()) {
                    val voiceMoment = innerVoices.optJSONObject(i) ?: continue
                    appendLine("· ${voiceMoment.optString("thought")}")
                }
                appendLine("若情境有关，可让旧念头继续、碰撞或自然淡化；避免逐字复读、反复提起同一念头。")
            }
            if (decisions.length() > 0) {
                appendLine("最近有依据的取舍与真实结果（仅供以后规划参考）：")
                for (i in maxOf(0, decisions.length() - 2) until decisions.length()) {
                    val chosen = decisions.optJSONObject(i) ?: continue
                    if (chosen.optString("selected") == "silent") {
                        appendLine("· 选择暂时沉默：${chosen.optString("reason")}；无外部动作，不属于执行失败")
                    } else {
                        appendLine("· 做出选择：${chosen.optString("selected")}；原因：${chosen.optString("reason")}；执行成功：${chosen.optBoolean("succeeded")}；实际结果：${chosen.optString("outcome")}")
                    }
                    val others = chosen.optJSONArray("alternatives") ?: JSONArray()
                    for (j in 0 until minOf(others.length(), 2)) {
                        val alternate = others.optJSONObject(j) ?: continue
                        appendLine("  暂时没做：${alternate.optString("idea")}；因为：${alternate.optString("whyNot")}")
                    }
                }
            }
            if (voice.length() > 0) {
                appendLine("角色近期实际说过的话（延续自己的语言节奏、称呼、话题与情绪表达习惯；不复制原话）：")
                for (i in maxOf(0, voice.length() - 3) until voice.length()) {
                    appendLine("· ${voice.optJSONObject(i)?.optString("text")}")
                }
            }
            appendLine("愿望可以并存、冲突和暂停。选择前考虑性格、时间、用户边界与执行能力；只从工具回执确认成功，失败后可反省并改法。")
        }.trim()
    }
}
