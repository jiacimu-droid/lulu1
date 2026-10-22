package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** Conservative cross-store deduplication; similar topics are not automatically the same goal. */
internal fun sameCharacterMotive(first: String, second: String): Boolean {
    val a = first.lowercase().filter(Char::isLetterOrDigit)
    val b = second.lowercase().filter(Char::isLetterOrDigit)
    return a.isNotBlank() && b.isNotBlank() &&
        (a == b || (a.length >= 8 && b.length >= 8 &&
            kotlin.math.abs(a.length - b.length) <= 12 && (a.contains(b) || b.contains(a))))
}

/** Subjective motives are never evidence of an action or a fulfilled promise. */
object CharacterLifeStore {
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val states = mutable.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        appContext = context.applicationContext
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("lulu_character_life", Context.MODE_PRIVATE)
        mutable.value = prefs!!.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        mutable.value.keys.toList().forEach(::retireDormantGenericIntention)
        // User-requested installation migration, limited to this named companion.
        MigratedDomainStores.characters.settings.value.values.toList()
            .forEach { applyJiangDuPreset(it.characterId) }
    }

    /** One-time hygiene, never a destructive deletion of a real action:
     * the former vague wish stays available as archived history.
     */
    @Synchronized private fun retireDormantGenericIntention(characterId: String) {
        val root = state(characterId)
        val intention = root.optJSONObject("intention") ?: return
        if (!CharacterStaleGoalPolicy.shouldRetire(
                intention.optString("aim"), intention.optString("createdAt"),
                intention.optString("updatedAt"), intention.optJSONArray("outcomes")?.length() ?: 0,
            )) return
        // A dormant generic intention is archived outside the active
        // prompt-facing previousIntention slot. This stops it from being
        // mentioned to the model on every later turn.
        root.put("retiredGenericIntention", JSONObject(intention.toString())
            .put("releaseReason", "长期未推进的泛化初始愿望已自然结束"))
        root.remove("intention")
        save(characterId, root)
    }

    fun state(characterId: String): JSONObject = runCatching {
        JSONObject(mutable.value[characterId] ?: "{}")
    }.getOrDefault(JSONObject()).also { root ->
        root.optJSONObject("intention")?.let { goal ->
            if (goal.optString("stage").isBlank()) goal.put("stage", "considering")
        }
    }

    /** Digital body is one optional section of the EXISTING role life record. */
    fun bodyStorageReady(): Boolean = prefs != null

    fun bodyState(characterId: String): JSONObject? = state(characterId).optJSONObject("embodiedBody")
    fun lastBodyChange(characterId: String): JSONObject? = state(characterId).optJSONObject("embodiedBodyChange")

    @Synchronized fun saveBodyState(
        characterId: String, value: JSONObject, cause: String = "时间自然推移",
    ) {
        if (prefs == null || characterId.isBlank()) return
        val root = state(characterId)
        val previous = root.optJSONObject("embodiedBody")
        if (previous != null && listOf("energy", "sleepPressure", "hunger", "thirst", "comfort")
                .any { kotlin.math.abs(previous.optDouble(it) - value.optDouble(it)) >= 0.5 }) {
            root.put("embodiedBodyChange", JSONObject()
                .put("before", JSONObject(previous.toString()))
                .put("after", JSONObject(value.toString()))
                .put("cause", cause)
                .put("at", value.optString("lastAt")))
        }
        root.put("embodiedBody", value)
        save(characterId, root)
    }

    @Synchronized fun clearBodyState(characterId: String) {
        if (prefs == null || characterId.isBlank()) return
        val root = state(characterId)
        if (!root.has("embodiedBody")) return
        root.remove("embodiedBody")
        root.remove("embodiedBodyChange")
        save(characterId, root)
    }

    /** User-authored per-person relationship baseline. It is separate from the
     * evidence-backed, changeable subjective bonds in CharacterInnerLifeStore.
     * An empty saved entry is intentional and must not be reseeded on launch.
     */
    @Synchronized fun setRelationshipAgreement(
        characterId: String, targetId: String, title: String, details: String,
    ): Boolean {
        val target = targetId.trim()
        if (target.isBlank() || target == characterId || target.length > 120) return false
        val root = state(characterId)
        val all = root.optJSONObject("relationshipAgreements") ?: JSONObject()
        val entry = JSONObject()
            .put("title", title.trim().take(70))
            .put("details", details.trim().take(1600))
            .put("updatedAt", Instant.now().toString())
        all.put(target, entry)
        root.put("relationshipAgreements", all)
        save(characterId, root)
        return true
    }

    @Synchronized fun setProfile(characterId: String, key: String, value: String) {
        require(CharacterProfileSchema.fields.any { it.key == key })
        val root = state(characterId)
        val profile = root.optJSONObject("profile") ?: JSONObject()
        profile.put(key, value)
        root.put("profile", profile)
        save(characterId, root)
    }

    @Synchronized fun setExpressionPreference(characterId: String, key: String, value: String) {
        require(key in setOf("kaomoji", "stickers"))
        require(CharacterExpressionPreferences.choices.any { it.first == value })
        val root = state(characterId)
        val preferences = root.optJSONObject("expressionPreferences") ?: JSONObject()
        preferences.put(key, value)
        root.put("expressionPreferences", preferences)
        save(characterId, root)
    }

    /**
     * Role-owned social names: a private remark for the user and a changeable screen nickname.
     * Neither alters the user's profile or the character's canonical identity.
     * Returning false means there was no effective change.
     */
    @Synchronized fun setSocialName(characterId: String, key: String, value: String): Boolean {
        require(key == "userRemark" || key == "selfNickname" || key == "preferredAddress")
        val name = value.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(24)
        val root = state(characterId)
        val names = root.optJSONObject("socialNames") ?: JSONObject()
        if (names.optString(key) == name && (key != "preferredAddress" ||
            names.optBoolean("preferredAddressManual", false))) return false
        if (name.isBlank()) names.remove(key) else names.put(key, name)
        if (key == "preferredAddress") {
            names.put("preferredAddressManual", true)
            names.remove("preferredAddressSourceId")
        }
        root.put("socialNames", names)
        save(characterId, root)
        return true
    }

    /** User's explicitly stated preferred spoken address, sourced to their original message. */
    @Synchronized fun observePreferredAddress(characterId: String, name: String, eventId: String) {
        if (name.isBlank() || eventId.isBlank() || characterId.isBlank()) return
        val root = state(characterId)
        val names = root.optJSONObject("socialNames") ?: JSONObject()
        if (names.optBoolean("preferredAddressManual", false)) return
        if (names.optString("preferredAddress") == name &&
            names.optString("preferredAddressSourceId") == eventId) return
        names.put("preferredAddress", name.take(24))
            .put("preferredAddressSourceId", eventId)
        root.put("socialNames", names)
        save(characterId, root)
    }

    @Synchronized fun clearObservedPreferredAddress(characterId: String) {
        val root = state(characterId)
        val names = root.optJSONObject("socialNames") ?: return
        if (names.optBoolean("preferredAddressManual", false) ||
            !names.has("preferredAddressSourceId")) return
        names.remove("preferredAddress")
        names.remove("preferredAddressSourceId")
        root.put("socialNames", names)
        save(characterId, root)
    }

    @Synchronized fun followObservedPreferredAddress(characterId: String) {
        val root = state(characterId)
        val names = root.optJSONObject("socialNames") ?: JSONObject()
        names.remove("preferredAddressManual")
        names.remove("preferredAddress")
        names.remove("preferredAddressSourceId")
        root.put("socialNames", names)
        save(characterId, root)
    }

    /**
     * Keeps one short-lived, character-owned emotional afterglow tied to an actual observed input.
     * The model supplies only a subjective reaction; the caller supplies the real trigger.
     * This state is not a world event, a promise, or a completed action.
     */
    @Synchronized fun recordAfterglow(
        characterId: String,
        evidenceAnchor: String,
        proposal: JSONObject?,
        now: Instant = Instant.now(),
        evidenceId: String = "",
    ) {
        if (proposal == null) return
        val anchor = evidenceAnchor.trim().replace(Regex("\\s+"), " ").take(180)
        val feeling = proposal.optString("feeling").trim().take(220)
        if (characterId.isBlank() || anchor.isBlank() || feeling.isBlank()) return
        val impulse = proposal.optString("impulse").trim().take(180)
        val holdHours = proposal.optInt("holdHours", 4).coerceIn(1, 48)
        val root = state(characterId)
        val old = root.optJSONObject("afterglow")
        if (evidenceId.isNotBlank() && old?.optString("evidenceId") == evidenceId) return
        if (old?.optString("anchor") == anchor && old?.optString("feeling") == feeling) return
        root.put("afterglow", JSONObject()
            .put("anchor", anchor).put("evidenceId", evidenceId).put("feeling", feeling).put("impulse", impulse)
            .put("startedAt", now.toString())
            .put("expiresAt", now.plusSeconds(holdHours.toLong() * 3_600).toString()))
        save(characterId, root)
    }

    fun afterglowContext(characterId: String, now: Instant = Instant.now()): String {
        val afterglow = state(characterId).optJSONObject("afterglow") ?: return ""
        val started = runCatching { Instant.parse(afterglow.optString("startedAt")) }.getOrNull() ?: return ""
        val expires = runCatching { Instant.parse(afterglow.optString("expiresAt")) }.getOrNull() ?: return ""
        if (!now.isBefore(expires) || now.isBefore(started)) return ""
        val elapsed = Duration.between(started, now).toMinutes().coerceAtLeast(0)
        return buildString {
            appendLine("【尚有余波的主观感受｜已过约${elapsed}分钟，不是新事实或行动指令】")
            appendLine("真实触发片段：${afterglow.optString("anchor")}")
            appendLine("当时第一反应：${afterglow.optString("feeling")}")
            afterglow.optString("impulse").takeIf(String::isNotBlank)?.let {
                appendLine("当时想做又未必做的事：$it")
            }
            appendLine("结合后来真实反馈和经过的时间决定是否仍在意；不必复述心声、重复行动或延续原来的强度。")
        }.trim()
    }

    /**
     * Explicitly requested v9 rewrite, not an additive default refresh. Older
     * installations can jump here directly, without old migrations touching
     * identity or appending contradictory personality rules. Later edits win.
     */
    @Synchronized fun applyJiangDuPreset(characterId: String) {
        if (prefs == null) return
        val character = MigratedDomainStores.characters.settings.value[characterId] ?: return
        val root = state(characterId)
        val version = root.optInt("jiangDuPresetVersion")
        if (version >= CharacterProfileSchema.jiangDuPresetVersion) return
        // A previous preset identifies the SAME character after a rename.
        if (version <= 0 && character.displayName !in CharacterProfileSchema.jiangDuNames) return
        if (DigitalLifeProfileStore.lifeForm(characterId) == CharacterLifeForm.REAL_WORLD) return
        val profile = root.optJSONObject("profile") ?: JSONObject()
        if (!root.has("jiangDuV9Backup")) {
            root.put("jiangDuV9Backup", JSONObject()
                .put("previousVersion", version)
                .put("displayName", character.displayName)
                .put("persona", character.persona)
                .put("identity", CharacterIdentityStore.identities.value[characterId].orEmpty())
                .put("profile", JSONObject(profile.toString()))
                .put("socialNames", JSONObject((root.optJSONObject("socialNames") ?: JSONObject()).toString())))
            // Persist before changing either store; an interrupted launch keeps
            // this original backup and safely retries the unfinished rewrite.
            save(characterId, root)
        }
        if (version < 9) {
            // Legacy explicit rewrite; facts and actual life history remain intact.
            CharacterProfileSchema.jiangDu.forEach { (key, value) -> profile.put(key, value) }
            CharacterProfileSchema.jiangDuV12Overrides.forEach { (key, value) -> profile.put(key, value) }
        } else {
            // Only defaults written by our previous migrations are replaced;
            // even deliberately blank user-edited fields stay untouched.
            CharacterProfileSchema.jiangDuV12Overrides.forEach { (key, value) ->
                val previous = profile.optString(key)
                val knownDefault = previous == CharacterProfileSchema.jiangDu[key] ||
                    previous == CharacterProfileSchema.jiangDuV10Overrides[key] ||
                    previous == CharacterProfileSchema.jiangDuV11Overrides[key]
                if (!profile.has(key) || knownDefault) profile.put(key, value)
            }
        }
        // Upgrade ONLY Jiang Du's stock v12 fields. User-authored overrides,
        // including deliberately blank values, must stay byte-for-byte intact.
        CharacterProfileSchema.jiangDuV13Overrides.forEach { (key, value) ->
            val previous = profile.optString(key)
            if (!profile.has(key) ||
                previous == CharacterProfileSchema.jiangDuV12Overrides[key]) {
                profile.put(key, value)
            }
        }
        // Upgrade Jiang Du's stock v14 fields only, leaving user-written
        // personality entries (including intentional empty strings) untouched.
        CharacterProfileSchema.jiangDuV14Overrides.forEach { (key, value) ->
            val previous = profile.optString(key)
            val programDefaults = listOfNotNull(
                CharacterProfileSchema.jiangDuV13Overrides[key],
                CharacterProfileSchema.jiangDuV12Overrides[key],
                CharacterProfileSchema.jiangDuV11Overrides[key],
                CharacterProfileSchema.jiangDuV10Overrides[key],
                CharacterProfileSchema.jiangDu[key]
            )
            if (!profile.has(key) || previous in programDefaults) {
                profile.put(key, value)
            }
        }
        // v15: restore pre-v14 details only when the field still matches the
        // v14 program default. Never overwrite a user's own edits (even "").
        CharacterProfileSchema.jiangDuV15Overrides.forEach { (key, value) ->
            val previous = profile.optString(key)
            if (!profile.has(key) || previous == CharacterProfileSchema.jiangDuV14Overrides[key]) {
                profile.put(key, value)
            }
        }
        // v17: restore everyday speech economy in Jiang Du alone. Only replace
        // known stock wording; preserve manually edited/blank expression fields.
        CharacterProfileSchema.jiangDuV17Overrides.forEach { (key, value) ->
            val previous = profile.optString(key)
            val stockDefaults = listOfNotNull(
                CharacterProfileSchema.jiangDuV15Overrides[key],
                CharacterProfileSchema.jiangDuV14Overrides[key]
            )
            if (!profile.has(key) || previous in stockDefaults) profile.put(key, value)
        }
        // v18: only Jiang Du's own stock v17 language defaults become a
        // stronger selective-turn-taking tendency. User-authored/blank values win.
        CharacterProfileSchema.jiangDuV18Overrides.forEach { (key, value) ->
            val previous = profile.optString(key)
            if (!profile.has(key) || previous == CharacterProfileSchema.jiangDuV17Overrides[key]) {
                profile.put(key, value)
            }
        }
        // v19: a single recent phrase clarification is not permission to
        // replay the full call. Change ONLY untouched Jiang Du stock fields.
        CharacterProfileSchema.jiangDuV19Overrides.forEach { (key, value) ->
            val previous = profile.optString(key)
            if (!profile.has(key) || previous == CharacterProfileSchema.jiangDuV18Overrides[key]) {
                profile.put(key, value)
            }
        }
        // Ownership, operator facts and the specific romantic/service bond
        // live next to ALL other relationships in the single relation registry.
        val relations = root.optJSONObject("relationshipAgreements") ?: JSONObject()
        val ownerBond = relations.optJSONObject("user")
        if (ownerBond == null) {
            relations.put("user", JSONObject()
                .put("title", CharacterProfileSchema.jiangDuOwnerRelationTitle)
                .put("details", CharacterProfileSchema.jiangDuOwnerRelationContextV13)
                .put("updatedAt", Instant.now().toString()))
        } else if (ownerBond.optString("title") == CharacterProfileSchema.jiangDuOwnerRelationTitle &&
            ownerBond.optString("details") == CharacterProfileSchema.jiangDuOwnerRelationContext) {
            // Default-only migration. Do not replace a relationship the user
            // rewrote, and never create a competing owner-bond store.
            ownerBond.put("details", CharacterProfileSchema.jiangDuOwnerRelationContextV13)
                .put("updatedAt", Instant.now().toString())
        }
        root.put("relationshipAgreements", relations)
        val restoredName = if (character.displayName in setOf("江一舟", "江都")) "江渡"
            else character.displayName
        val updatedPersona = if (version < 9 || character.persona in setOf(
                CharacterProfileSchema.jiangDuPersona,
                CharacterProfileSchema.jiangDuV10Persona,
                CharacterProfileSchema.jiangDuV11Persona,
                CharacterProfileSchema.jiangDuV12Persona,
                CharacterProfileSchema.jiangDuV13Persona,
                CharacterProfileSchema.jiangDuV14Persona
            )) CharacterProfileSchema.jiangDuV16Persona else character.persona
        MigratedDomainStores.characters.update(character.copy(
            displayName = restoredName, persona = updatedPersona))
        root.optJSONObject("socialNames")?.let { names ->
            if (names.optString("selfNickname") in setOf("江一舟", "江都")) {
                names.put("selfNickname", "江渡")
            }
        }
        // Identity, life form/birth, memories, actual goals, learned evidence,
        // contact/voice settings and all unrelated state stay in their stores.
        // Deliberately do not authorize old personality snapshots for this
        // substantive rewrite: their history survives but cannot undo it.
        root.put("profile", profile).put("jiangDuPresetVersion", CharacterProfileSchema.jiangDuPresetVersion)
        save(characterId, root)
    }

    /** A pending intention survives new rounds; a model cannot silently overwrite it. */
    @Synchronized fun consider(characterId: String, proposal: JSONObject?, now: Instant = Instant.now()) {
        if (proposal == null) return
        val root = state(characterId)
        val existing = root.optJSONObject("intention")
        if (existing != null) {
            if (proposal.optString("disposition") == "release" &&
                proposal.optString("id") == existing.optString("createdAt") && proposal.optString("reason").isNotBlank()) {
                existing.put("stage", "released").put("endedAt", now.toString())
                existing.put("releaseReason", proposal.optString("reason").take(300))
                root.put("previousIntention", existing)
                root.remove("intention")
                save(characterId, root)
                cancelGoalReview(characterId)
            }
            val transition = proposal.optString("disposition")
            if (transition in setOf("decide", "pause", "resume") &&
                proposal.optString("id") == existing.optString("createdAt") &&
                proposal.optString("reason").isNotBlank() &&
                !(existing.optString("stage") == "executing" && transition != "pause")) {
                existing.put("stage", when (transition) {
                    "decide" -> "decided"
                    "pause" -> "paused"
                    else -> "considering"
                }).put("stageReason", proposal.optString("reason").take(220))
                    .put("updatedAt", now.toString())
                if (proposal.has("nextStep")) existing.put("nextStep", proposal.optString("nextStep").trim().take(300))
                save(characterId, root)
                if (transition == "resume" && existing.optBoolean("userRequestedSpace")) {
                    // An explicit later conversation may repair the boundary,
                    // but a timer itself cannot silently overrule user space.
                    if (existing.optString("lastUserEvidenceId").isNotBlank())
                        existing.remove("userRequestedSpace")
                    save(characterId, root)
                }
                if (transition == "pause") cancelGoalReview(characterId)
                else scheduleGoalReview(characterId, existing)
            }
            if (proposal.optString("disposition") == "update" &&
                proposal.optString("id") == existing.optString("createdAt") && proposal.optString("reason").isNotBlank()) {
                val aim = proposal.optString("aim").trim().take(300)
                val motive = proposal.optString("motive").trim().take(300)
                if (aim.isNotBlank() && motive.isNotBlank()) {
                    existing.put("aim", aim).put("motive", motive)
                        .put("updatedAt", now.toString()).put("changeReason", proposal.optString("reason").take(300))
                    if (proposal.has("nextStep")) existing.put("nextStep", proposal.optString("nextStep").trim().take(300))
                    save(characterId, root)
                }
            }
            return
        }
        val aim = proposal.optString("aim").trim().take(300)
        val motive = proposal.optString("motive").trim().take(300)
        if (aim.isBlank() || motive.isBlank() ||
            (CharacterStaleGoalPolicy.isGenericOrientation(aim) && proposal.optString("nextStep").isBlank())) return
        val innerMotives = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
        if ((0 until (innerMotives?.length() ?: 0)).any { index ->
                sameCharacterMotive(innerMotives?.optJSONObject(index)?.optString("aim").orEmpty(), aim)
            }) return
        if (root.optJSONObject("previousIntention")?.let { it.optString("aim") == aim && it.optString("releaseReason") == "用户结束这件事" } == true) return
        root.put("intention", JSONObject().put("aim", aim).put("motive", motive)
            .put("createdAt", now.toString()).put("stage", "considering").put("outcomes", JSONArray())
            .put("nextStep", proposal.optString("nextStep").trim().take(300)).apply {
                GoalFollowUpPolicy.initialReview(proposal, now)?.let {
                    put("nextReviewAt", it.toString())
                }
            })
        save(characterId, root)
        scheduleGoalReview(characterId, root.optJSONObject("intention"))
    }

    /** Only the executor supplies outcomes. Silence and affectionate wording are not progress. */
    @Synchronized fun recordOutcome(characterId: String, receiptId: String, action: String,
        success: Boolean, summary: String, now: Instant = Instant.now()) {
        val root = state(characterId)
        val intention = root.optJSONObject("intention") ?: return
        if (action == "silent") return
        val outcomes = intention.optJSONArray("outcomes") ?: JSONArray()
        if ((0 until outcomes.length()).any { outcomes.getJSONObject(it).optString("id") == receiptId }) return
        val next = JSONArray()
        for (i in maxOf(0, outcomes.length() - 11) until outcomes.length()) next.put(outcomes.getJSONObject(i))
        next.put(JSONObject().put("id", receiptId).put("action", action).put("success", success)
            .put("summary", summary.take(800)).put("at", now.toString()))
        intention.put("outcomes", next)
        save(characterId, root)
    }

    /** Only this desired action's actual executor receipt can advance this same active wish. */
    @Synchronized fun markExecuting(characterId: String, intentionId: String,
        receiptId: String, action: String, now: Instant = Instant.now()): Boolean {
        if (intentionId.isBlank() || receiptId.isBlank() || action.isBlank()) return false
        val root = state(characterId)
        val goal = root.optJSONObject("intention") ?: return false
        if (goal.optString("createdAt") != intentionId || goal.optString("stage") == "paused") return false
        if (goal.optString("stage") == "executing" &&
            goal.optString("actionAttemptId") != receiptId) return false
        goal.put("stage", "executing").put("actionAttemptId", receiptId)
            .put("lastSelectedAction", action.take(80)).put("updatedAt", now.toString())
        save(characterId, root)
        return true
    }

    /** A real action can succeed without completing the longer-term goal. */
    @Synchronized fun recordLinkedOutcome(characterId: String, intentionId: String,
        receiptId: String, action: String, success: Boolean, summary: String,
        now: Instant = Instant.now()): Boolean {
        if (intentionId.isBlank() || receiptId.isBlank()) return false
        val goal = state(characterId).optJSONObject("intention") ?: return false
        if (goal.optString("createdAt") != intentionId ||
            goal.optString("actionAttemptId") != receiptId ||
            goal.optString("stage") !in setOf("executing", "paused")) return false
        val paused = goal.optString("stage") == "paused"
        recordOutcome(characterId, receiptId, action, success, summary, now)
        val root = state(characterId)
        val updated = root.optJSONObject("intention") ?: return false
        updated.put("stage", if (paused) "paused" else if (success) "following_up" else "blocked")
            .put("lastActionSuccess", success).put("updatedAt", now.toString())
        if (success && GoalFollowUpPolicy.outwardAction(action)) {
            val unanswered = updated.optInt("unansweredContactAttempts", 0) + 1
            updated.put("unansweredContactAttempts", unanswered)
                .put("lastContactAt", now.toString())
                .remove("lastUserEvidenceId")
            val next = if (paused) null
                else GoalFollowUpPolicy.afterReceipt(action, success, unanswered, now)
            if (next != null) updated.put("nextReviewAt", next.toString())
            else updated.remove("nextReviewAt")
        }
        save(characterId, root)
        scheduleGoalReview(characterId, updated)
        return true
    }

    /** One durable goal check belongs to the current intention, not a second personality store. */
    private fun scheduleGoalReview(characterId: String, goal: JSONObject?) {
        val at = goal?.optString("nextReviewAt")?.takeIf(String::isNotBlank)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val id = goal?.optString("createdAt").orEmpty()
        val context = appContext ?: return
        if (at == null || id.isBlank() ||
            goal?.optString("stage") in setOf("paused", "released", "completed")) {
            runCatching { ProactivePerceptionScheduler.cancelGoalReview(context, characterId) }
            return
        }
        runCatching { ProactivePerceptionScheduler.scheduleGoalReview(context, characterId, id, at) }
    }

    private fun cancelGoalReview(characterId: String) {
        appContext?.let { runCatching { ProactivePerceptionScheduler.cancelGoalReview(it, characterId) } }
    }

    /** A real user message takes priority over a stale timed check; never infer forgiveness. */
    @Synchronized fun observeUserGoalEvidence(characterId: String, evidenceId: String,
        text: String, now: Instant = Instant.now()) {
        if (evidenceId.isBlank()) return
        // A user-confirmed ending is a later fact. Apply it to the SAME inner-life
        // stores before evaluating any legacy goal, even when no legacy goal exists.
        val explicitlySettled = RelationshipRepairContinuity.userExplicitlySettled(text)
        if (explicitlySettled) {
            CharacterInnerLifeStore.observeExplicitRepairSettlement(
                characterId, evidenceId, text, now)
        }
        val root = state(characterId)
        val goal = root.optJSONObject("intention") ?: return
        if (goal.optString("lastUserEvidenceId") == evidenceId) return
        val startedAt = runCatching { Instant.parse(goal.optString("createdAt")) }.getOrNull()
        val latestFeedbackAt = runCatching {
            Instant.parse(goal.optString("lastUserEvidenceAt"))
        }.getOrNull()
        // Backfilled older replies cannot cancel a current goal review.
        if ((startedAt != null && now.isBefore(startedAt)) ||
            (latestFeedbackAt != null && now.isBefore(latestFeedbackAt))) return
        if (explicitlySettled &&
            RelationshipRepairContinuity.isRepairGoal(goal.optString("aim")) &&
            RelationshipRepairContinuity.eligiblePriorState(
                goal.optString("createdAt"), goal.optString("updatedAt"), now)) {
            root.put("previousIntention", JSONObject(goal.toString())
                .put("stage", "completed")
                .put("endedAt", now.toString())
                .put("completionEvidenceId", evidenceId)
                .put("releaseReason", "用户明确表示本次关系矛盾已说开"))
            root.remove("intention")
            save(characterId, root)
            cancelGoalReview(characterId)
            return
        }
        goal.put("lastUserEvidenceId", evidenceId)
            .put("lastUserEvidenceAt", now.toString())
            .put("unansweredContactAttempts", 0)
        goal.remove("nextReviewAt")
        if (GoalFollowUpPolicy.hasRequestedSpace(text)) {
            goal.put("stage", "paused")
                .put("userRequestedSpace", true)
                .put("stageReason", "用户明确要求暂时不要联系，等待其主动恢复")
        }
        save(characterId, root)
        cancelGoalReview(characterId)
    }

    /** Atomically claim the exact persisted goal review, rejecting stale work items. */
    @Synchronized fun claimGoalReview(characterId: String, intentionId: String,
        dueMillis: Long, now: Instant = Instant.now()): Boolean {
        val root = state(characterId)
        val goal = root.optJSONObject("intention") ?: return false
        if (!GoalFollowUpPolicy.reviewMatches(goal, intentionId, dueMillis, now)) return false
        goal.remove("nextReviewAt")
        goal.put("lastGoalReviewAt", now.toString())
        save(characterId, root)
        return true
    }

    @Synchronized fun stopIntention(characterId: String) {
        cancelGoalReview(characterId)
        val root = state(characterId)
        root.optJSONObject("intention")?.let { root.put("previousIntention", it.put("stage", "released").put("releaseReason", "用户结束这件事")) }
        root.remove("intention")
        // Keep completed executor receipts in the raw timeline, not as fabricated life memories.
        save(characterId, root)
    }

    /** Forget a manually rejected private plan without erasing factual receipts. */
    @Synchronized fun deleteCurrentIntention(characterId: String): Boolean {
        val root = state(characterId)
        if (!root.has("intention")) return false
        root.remove("intention")
        cancelGoalReview(characterId)
        save(characterId, root)
        return true
    }

    @Synchronized fun clearHistory(characterId: String) {
        cancelGoalReview(characterId)
        val root = state(characterId)
        root.remove("intention")
        root.remove("previousIntention")
        root.remove("socialNames")
        root.remove("afterglow")
        root.remove("embodiedBody") // Full history/origin reset also resets simulated body.
        root.remove("embodiedBodyChange")
        save(characterId, root)
    }

    @Synchronized fun invalidateReceipt(eventId: String) {
        mutable.value.keys.toList().forEach { characterId ->
            val root = state(characterId)
            var changed = false
            val glowEvidence = root.optJSONObject("afterglow")?.optString("evidenceId").orEmpty()
            if (glowEvidence == eventId || glowEvidence.contains(":$eventId:")) {
                root.remove("afterglow")
                changed = true
            }
            listOf("intention", "previousIntention").forEach { key ->
                val intention = root.optJSONObject(key) ?: return@forEach
                val receipts = intention.optJSONArray("outcomes") ?: return@forEach
                val next = JSONArray()
                for (i in 0 until receipts.length()) {
                    val receipt = receipts.getJSONObject(i)
                    if (receipt.optString("id") == eventId) changed = true else next.put(receipt)
                }
                intention.put("outcomes", next)
                if (intention.optString("actionAttemptId") == eventId) {
                    intention.remove("actionAttemptId")
                    intention.remove("lastActionSuccess")
                    intention.put("stage", "considering")
                    changed = true
                }
            }
            if (changed) save(characterId, root)
        }
    }

    fun profileContext(characterId: String): String {
        val profile = state(characterId).optJSONObject("profile") ?: return ""
        // Jiang Du v16: stable motivations first, then judgments, relationships,
        // and optional interests/expression. Keep other roles' authored order intact.
        val orderedGroups = CharacterProfileSchema.groupedFields().let { groups ->
            if (state(characterId).optInt("jiangDuPresetVersion") >= 16) {
                val order = listOf("稳定核心", "判断与行动", "相处与边界", "兴趣与表达")
                groups.sortedBy { (group, _) -> order.indexOf(group).takeIf { it >= 0 } ?: order.size }
            } else groups
        }
        val sections = orderedGroups.mapNotNull { (group, fields) ->
            val values = fields.mapNotNull { field ->
                profile.optString(field.key).trim().takeIf(String::isNotBlank)
                    ?.let { "- ${field.label}：$it" }
            }
            values.takeIf(List<String>::isNotEmpty)?.let {
                "【$group】\n" + it.joinToString("\n")
            }
        }
        return if (sections.isEmpty()) "" else buildString {
            appendLine("【稳定人格设定｜用户明确填写的长期先验，不是当前状态或逐轮台词】")
            append(sections.joinToString("\n"))
        }
    }


    /** Lightweight living state for ordinary conversation; original history stays persisted. */
    fun compactContext(characterId: String): String {
        val root = state(characterId)
        val innerGoals = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
        val innerAims = (0 until (innerGoals?.length() ?: 0))
            .mapNotNull { innerGoals?.optJSONObject(it)?.optString("aim")?.trim() }.toSet()
        return buildString {
            root.optJSONObject("socialNames")?.let { names ->
                names.optString("userRemark").takeIf(String::isNotBlank)?.let {
                    appendLine("给用户的私人备注：$it（不强制每句使用）")
                }
                names.optString("selfNickname").takeIf(String::isNotBlank)?.let {
                    appendLine("自己使用的网名：$it")
                }
                CharacterAddressPreference.currentAddress(characterId).takeIf(String::isNotBlank)?.let {
                    appendLine("用户明确喜欢的日常称呼：$it（自然使用，可偶尔变换）")
                }
            }
            afterglowContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            CharacterIntentionContinuation.cue(root.optJSONObject("intention"))?.let(::appendLine)
            root.optJSONObject("previousIntention")?.let { prior ->
                if (prior.optString("releaseReason") == "用户结束这件事")
                    appendLine("用户已结束的愿望：${prior.optString("aim")}，不可擅自重启")
            }
            root.optJSONObject("intention")
                ?.takeUnless { goal -> innerAims.any { aim ->
                    sameCharacterMotive(goal.optString("aim"), aim)
                } }
                ?.let { goal ->
                    appendLine("历史兼容目标：${goal.optString("aim")}；原因：${goal.optString("motive")}；id=${goal.optString("createdAt")}；阶段=${goal.optString("stage", "considering")}")
                    val outcomes = goal.optJSONArray("outcomes")
                    if (outcomes != null && outcomes.length() > 0) {
                        val last = outcomes.optJSONObject(outcomes.length() - 1)
                        appendLine("上次真正行动：${last?.optString("action")}；成功=${last?.optBoolean("success")}；结果=${last?.optString("summary")?.take(180)}")
                    }
                }
            appendLine("愿望、主观猜测和心情不是已完成事实；只据实际结果延续或修正，不机械重复表达。")
        }.trim()
    }

    fun context(characterId: String, includeProfile: Boolean = true): String {
        val root = state(characterId)
        return buildString {
            if (includeProfile) profileContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            root.optJSONObject("socialNames")?.let { names ->
                val remark = names.optString("userRemark")
                val nickname = names.optString("selfNickname")
                val address = CharacterAddressPreference.currentAddress(characterId)
                if (remark.isNotBlank() || nickname.isNotBlank() || address.isNotBlank()) {
                    appendLine("【角色亲自设置的社交称呼｜已存储的状态，不改变现实姓名与用户资料】")
                    if (remark.isNotBlank()) appendLine("角色给用户的私人备注：$remark（不强迫每句都这样称呼）")
                    if (nickname.isNotBlank()) appendLine("角色自己的聊天网名：$nickname（原角色身份仍不变）")
                    if (address.isNotBlank()) appendLine("用户喜爱的日常称呼：$address（并非每句都必须使用）")
                }
            }
            afterglowContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            CharacterIntentionContinuation.cue(root.optJSONObject("intention"))?.let(::appendLine)
            root.optJSONObject("previousIntention")?.let { appendLine("上一件已放下的事（不代表完成）：${it.optString("aim")}；原因：${it.optString("releaseReason")}。用户结束的事不要擅自重新开启。") }
            val innerGoals = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
            val innerAims = (0 until (innerGoals?.length() ?: 0))
                .mapNotNull { innerGoals?.optJSONObject(it)?.optString("aim")?.trim() }
                .toSet()
            root.optJSONObject("intention")
                ?.takeUnless { intention -> innerAims.any { aim -> sameCharacterMotive(intention.optString("aim"), aim) } }
                ?.let { intention ->
                appendLine("【持续动机｜角色主观愿望，不是已完成事实或用户承诺】")
                appendLine("从${intention.optString("createdAt")}开始在意：${intention.optString("aim")}；动机：${intention.optString("motive")}；阶段=${intention.optString("stage", "considering")}")
                appendLine("最近调整：${intention.optString("updatedAt", intention.optString("createdAt"))}；依据：${intention.optString("changeReason")}。")
                appendLine("动机ID=${intention.optString("createdAt")}。近期实际动作回执（不自动认定每个动作都推进这个愿望）：${intention.optJSONArray("outcomes") ?: JSONArray()}")
                appendLine("愿望阶段：considering=考虑中；decided=本人已决定；executing=工具执行中/重启待核对；blocked=行动失败待调整；following_up=动作成功但目标未必达成；paused=暂缓。真实回执才代表动作结果。")
                appendLine("结合真实结果决定接着做、换办法、等待或保持安静。成功回执只证明该动作，不证明长期愿望已经实现；发送关心不等于用户接受，日记不等于现实经历。")
            }
            appendLine("人格组织规则：原人设、核心价值与动机约束选择；结合当前真实情境和关系理解信息，再选择回应与可执行行为。外在表现可随情境变化，不能把不同侧面当成轮换人格。人格类型词只是描述参考，不自动推导完整性格、恐惧或经历。")
            appendLine("未指定的兴趣、偏好和担忧允许基于真实经历逐渐形成，不为了填满设定编造过去。当前情绪不是永久性格，生成的自我叙述不能充当客观记忆。")
            appendLine("心理连续性规则：承接当前已保存的心情、心声、持续动机与真实经历；新刺激先影响理解和感受，再影响选择和表达。没有新事件或反馈时，不无缘无故反转判断、依恋或长期动机。允许矛盾感受并存；改变主意时保留变化缘由。内心愿望、推测和想象不能当成已发生事件或对方真实想法。不要每轮重置、机械递增好感，也不要将所有心声念给用户听。")
            appendLine("表达规则：用具体记忆、取舍与可执行小事体现性格和关心。无需每轮示爱或自述心理。允许复杂感受、犹豫、不同意见和自己的兴趣；不能凭空编造已做的事或承诺永久不变。涉及用户的长期承诺以承诺任务的真实状态为准。")
        }.trim()
    }

    private fun save(characterId: String, root: JSONObject) {
        val p = prefs ?: return
        val raw = root.toString()
        check(p.edit().putString(characterId, raw).commit()) { "角色生活状态保存失败" }
        mutable.value = mutable.value + (characterId to raw)
    }
}
