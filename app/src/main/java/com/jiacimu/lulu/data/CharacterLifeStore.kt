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
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val states = mutable.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("lulu_character_life", Context.MODE_PRIVATE)
        mutable.value = prefs!!.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        // User-requested installation migration, limited to this named companion.
        MigratedDomainStores.characters.settings.value.values.filter { it.displayName in setOf("江渡", "江都") }
            .forEach { applyJiangDuPreset(it.characterId) }
    }

    fun state(characterId: String): JSONObject = runCatching {
        JSONObject(mutable.value[characterId] ?: "{}")
    }.getOrDefault(JSONObject())

    @Synchronized fun setProfile(characterId: String, key: String, value: String) {
        require(CharacterProfileSchema.fields.any { it.key == key })
        val root = state(characterId)
        val profile = root.optJSONObject("profile") ?: JSONObject()
        profile.put(key, value)
        root.put("profile", profile)
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

    /** One-time, restart-safe preset. Later user edits must never be overwritten on launch. */
    @Synchronized fun applyJiangDuPreset(characterId: String) {
        val character = MigratedDomainStores.characters.get(characterId)
        if (character.displayName !in setOf("江渡", "江都")) return
        val root = state(characterId)
        val version = root.optInt("jiangDuPresetVersion")
        if (version >= 6) return
        if (version >= 5) {
            migrateJiangDuUnifiedFramework(characterId, character, root)
            return
        }
        if (version >= 4) {
            refreshJiangDuLanguageDefaults(characterId, character, root)
            if (state(characterId).optInt("jiangDuPresetVersion") >= 5) applyJiangDuPreset(characterId)
            return
        }
        if (version >= 3) {
            reorganizeJiangDuProfile(characterId, character, root)
            applyJiangDuPreset(characterId)
            return
        }
        if (version >= 2) {
            if (!DigitalLifeProfileStore.isEnabled(characterId)) return
            if (!root.has("jiangDuRespectBackup")) {
                root.put("jiangDuRespectBackup", JSONObject().put("persona", character.persona)
                    .put("profile", JSONObject((root.optJSONObject("profile") ?: JSONObject()).toString())))
                save(characterId, root)
            }
            val profile = root.optJSONObject("profile") ?: JSONObject()
            val stagedRespect = CharacterProfileSchema.jiangDuV5.getValue("respect")
            val oldRespect = profile.optString("respect")
            if (!oldRespect.contains(stagedRespect)) {
                profile.put("respect", listOf(oldRespect, stagedRespect)
                    .filter(String::isNotBlank).joinToString("\n"))
            }
            if (!character.persona.contains(CharacterProfileSchema.jiangDuRespectMarker)) {
                MigratedDomainStores.characters.update(character.copy(persona = character.persona +
                    "\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + stagedRespect))
            }
            root.put("profile", profile).put("jiangDuPresetVersion", 3)
            save(characterId, root)
            applyJiangDuPreset(characterId)
            return
        }
        val rawIdentity = CharacterIdentityStore.identities.value[characterId].orEmpty()
        if (!root.has("jiangDuPresetBackup")) {
            root.put("jiangDuPresetBackup", JSONObject().put("persona", character.persona)
                .put("identity", rawIdentity).put("profile", JSONObject((root.optJSONObject("profile") ?: JSONObject()).toString())))
            // Persist backup before modifying any existing store; interrupted launches can retry.
            save(characterId, root)
        }
        if (!DigitalLifeProfileStore.isResolved(characterId)) {
            DigitalLifeProfileStore.confirmLegacyLifeForm(characterId, character.displayName,
                "创造者", CharacterLifeForm.DIGITAL)
        }
        // A resolved real-world character is not silently converted by a name match.
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return
        // A custom identity/persona must survive an initial name-based preset too.
        if (rawIdentity.isBlank() || rawIdentity == LegacyJiangDuProfileSchema.jiangDuIdentity)
            CharacterIdentityStore.set(characterId, CharacterProfileSchema.jiangDuV5Identity)
        if (character.persona.isBlank() || character.persona == LegacyJiangDuProfileSchema.jiangDuPersona ||
            character.persona == CharacterProfileSchema.previousJiangDuPersona) {
            MigratedDomainStores.characters.update(character.copy(persona = CharacterProfileSchema.jiangDuV5Persona + "\n\n" +
                CharacterProfileSchema.jiangDuRespectMarker + "\n" + CharacterProfileSchema.jiangDuV5.getValue("respect")))
        }
        val profile = root.optJSONObject("profile") ?: JSONObject()
        CharacterProfileSchema.jiangDuV5.forEach { (key, value) ->
            if (!profile.has(key)) profile.put(key, value)
        }
        root.put("profile", profile).put("jiangDuPresetVersion", 3)
        save(characterId, root)
        applyJiangDuPreset(characterId)
    }

    private fun reorganizeJiangDuProfile(characterId: String, character: CharacterSettings,
        root: JSONObject) {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return
        val identity = CharacterIdentityStore.identities.value[characterId].orEmpty()
        val profile = root.optJSONObject("profile") ?: JSONObject()
        if (!root.has("jiangDuOrganizationBackup")) {
            root.put("jiangDuOrganizationBackup", JSONObject().put("persona", character.persona)
                .put("identity", identity).put("profile", JSONObject(profile.toString())))
            save(characterId, root)
        }
        // Replace only program-owned defaults; preserve each user's edited field verbatim.
        val v5Respect = CharacterProfileSchema.jiangDuV5.getValue("respect")
        val cleanPersona = character.persona
            .removeSuffix("\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + LegacyJiangDuProfileSchema.jiangDuRespect)
            .removeSuffix("\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + v5Respect).trim()
        val persona = if (cleanPersona == LegacyJiangDuProfileSchema.jiangDuPersona ||
            cleanPersona == CharacterProfileSchema.previousJiangDuPersona ||
            cleanPersona == CharacterProfileSchema.jiangDuV5Persona) CharacterProfileSchema.jiangDuV5Persona else cleanPersona
        MigratedDomainStores.characters.update(character.copy(persona = persona))
        if (identity == LegacyJiangDuProfileSchema.jiangDuIdentity) {
            CharacterIdentityStore.set(characterId, CharacterProfileSchema.jiangDuV5Identity)
        }
        CharacterProfileSchema.jiangDuV5.forEach { (key, value) ->
            if (key == "speechHabits" && profile.has(key)) return@forEach
            val current = profile.optString(key)
            val legacy = LegacyJiangDuProfileSchema.jiangDu[key].orEmpty()
            if (!profile.has(key) || current == legacy || current == value) profile.put(key, value)
            else if (key == "respect") {
                val custom = current.replace(LegacyJiangDuProfileSchema.jiangDuRespect, "").trim()
                profile.put(key, listOf(custom, value).filter(String::isNotBlank).distinct().joinToString("\n"))
            }
        }
        root.put("profile", profile).put("jiangDuPresetVersion", 4)
        save(characterId, root)
    }

    /** Migrate only unchanged default descriptions; preserve every user-authored field. */
    private fun refreshJiangDuLanguageDefaults(characterId: String, character: CharacterSettings,
        root: JSONObject) {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return
        val beforeConstraints = CharacterRuntime.personaConstraintSnapshot(characterId)
        val profile = root.optJSONObject("profile") ?: JSONObject()
        if (!root.has("jiangDuLanguageBackup")) {
            root.put("jiangDuLanguageBackup", JSONObject()
                .put("persona", character.persona).put("profile", JSONObject(profile.toString())))
            save(characterId, root)
        }
        val previous = CharacterProfileSchema.previousJiangDuPersona
        val stagedRespect = CharacterProfileSchema.jiangDuV5.getValue("respect")
        val decoratedLegacy = previous + "\n\n" +
            CharacterProfileSchema.jiangDuRespectMarker + "\n" + LegacyJiangDuProfileSchema.jiangDuRespect
        val decoratedV5 = previous + "\n\n" +
            CharacterProfileSchema.jiangDuRespectMarker + "\n" + stagedRespect
        if (character.persona == previous || character.persona == decoratedLegacy ||
            character.persona == decoratedV5) {
            MigratedDomainStores.characters.update(character.copy(
                persona = CharacterProfileSchema.jiangDuV5Persona))
        }
        if (profile.optString("expression") == CharacterProfileSchema.previousJiangDuExpression)
            profile.put("expression", CharacterProfileSchema.jiangDuV5.getValue("expression"))
        if (profile.optString("social") == CharacterProfileSchema.previousJiangDuSocial)
            profile.put("social", CharacterProfileSchema.jiangDuV5.getValue("social"))
        // Explicitly saved empty strings are user edits, not missing defaults.
        if (!profile.has("speechHabits"))
            profile.put("speechHabits", CharacterProfileSchema.jiangDuSpeechHabits)
        root.put("profile", profile).put("jiangDuPresetVersion", 5)
        save(characterId, root)
        // A program-owned preset refresh is not a user decision to discard
        // evidence-backed character growth. The exact before/after fingerprints
        // allow only this trusted migration to retain earlier active evidence.
        val afterConstraints = CharacterRuntime.personaConstraintSnapshot(characterId)
        if (beforeConstraints != afterConstraints) {
            root.put("jiangDuLanguagePreviousConstraints", beforeConstraints)
                .put("jiangDuLanguageCurrentConstraints", afterConstraints)
            save(characterId, root)
        }
    }

    /**
     * v6 migration: move every program-owned JiangDu default into the unified framework without
     * treating a user's later edits as defaults. Runtime state, memories, names, motives and
     * relationship history are untouched.
     */
    private fun migrateJiangDuUnifiedFramework(
        characterId: String,
        character: CharacterSettings,
        root: JSONObject,
    ) {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return
        val beforeConstraints = CharacterRuntime.personaConstraintSnapshot(characterId)
        val profile = root.optJSONObject("profile") ?: JSONObject()
        val identity = CharacterIdentityStore.identities.value[characterId].orEmpty()
        if (!root.has("jiangDuUnifiedFrameworkBackup")) {
            root.put("jiangDuUnifiedFrameworkBackup", JSONObject()
                .put("persona", character.persona)
                .put("identity", identity)
                .put("profile", JSONObject(profile.toString())))
            save(characterId, root)
        }

        if (identity == LegacyJiangDuProfileSchema.jiangDuIdentity ||
            identity == CharacterProfileSchema.jiangDuV5Identity ||
            identity == CharacterProfileSchema.jiangDuIdentity) {
            CharacterIdentityStore.set(characterId, CharacterProfileSchema.jiangDuIdentity)
        }

        val knownRespectAppendices = listOf(
            LegacyJiangDuProfileSchema.jiangDuRespect,
            CharacterProfileSchema.jiangDuV5["respect"].orEmpty(),
            CharacterProfileSchema.jiangDuRespect,
        ).filter(String::isNotBlank)
        var cleanPersona = character.persona.trim()
        knownRespectAppendices.forEach { respect ->
            cleanPersona = cleanPersona.removeSuffix(
                "\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + respect,
            ).trim()
        }
        val isProgramOwnedPersona = cleanPersona in setOf(
            LegacyJiangDuProfileSchema.jiangDuPersona,
            CharacterProfileSchema.previousJiangDuPersona,
            CharacterProfileSchema.jiangDuV5Persona,
            CharacterProfileSchema.jiangDuPersona,
        )
        if (isProgramOwnedPersona) {
            MigratedDomainStores.characters.update(character.copy(persona = CharacterProfileSchema.jiangDuPersona))
        }

        CharacterProfileSchema.jiangDu.forEach { (key, value) ->
            val current = profile.optString(key)
            val legacy = LegacyJiangDuProfileSchema.jiangDu[key].orEmpty()
            val v5 = CharacterProfileSchema.jiangDuV5[key].orEmpty()
            when {
                !profile.has(key) -> profile.put(key, value)
                current == legacy || current == v5 || current == value -> profile.put(key, value)
                key == "respect" -> {
                    val custom = current
                        .replace(LegacyJiangDuProfileSchema.jiangDuRespect, "")
                        .replace(v5, "")
                        .replace(CharacterProfileSchema.jiangDuRespect, "")
                        .trim()
                    profile.put(
                        key,
                        listOf(custom, value).filter(String::isNotBlank).distinct().joinToString("\n"),
                    )
                }
            }
        }

        root.put("profile", profile).put("jiangDuPresetVersion", 6)
        save(characterId, root)
        val afterConstraints = CharacterRuntime.personaConstraintSnapshot(characterId)
        if (beforeConstraints != afterConstraints) {
            root.put("jiangDuUnifiedPreviousConstraints", beforeConstraints)
                .put("jiangDuUnifiedCurrentConstraints", afterConstraints)
            save(characterId, root)
        }
    }

    /** A pending intention survives new rounds; a model cannot silently overwrite it. */
    @Synchronized fun consider(characterId: String, proposal: JSONObject?, now: Instant = Instant.now()) {
        if (proposal == null) return
        val root = state(characterId)
        val existing = root.optJSONObject("intention")
        if (existing != null) {
            if (proposal.optString("disposition") == "release" &&
                proposal.optString("id") == existing.optString("createdAt") && proposal.optString("reason").isNotBlank()) {
                existing.put("releaseReason", proposal.optString("reason").take(300))
                root.put("previousIntention", existing)
                root.remove("intention")
                save(characterId, root)
            }
            if (proposal.optString("disposition") == "update" &&
                proposal.optString("id") == existing.optString("createdAt") && proposal.optString("reason").isNotBlank()) {
                val aim = proposal.optString("aim").trim().take(300)
                val motive = proposal.optString("motive").trim().take(300)
                if (aim.isNotBlank() && motive.isNotBlank()) {
                    existing.put("aim", aim).put("motive", motive)
                        .put("updatedAt", now.toString()).put("changeReason", proposal.optString("reason").take(300))
                    save(characterId, root)
                }
            }
            return
        }
        val aim = proposal.optString("aim").trim().take(300)
        val motive = proposal.optString("motive").trim().take(300)
        if (aim.isBlank() || motive.isBlank()) return
        val innerMotives = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
        if ((0 until (innerMotives?.length() ?: 0)).any { index ->
                sameCharacterMotive(innerMotives?.optJSONObject(index)?.optString("aim").orEmpty(), aim)
            }) return
        if (root.optJSONObject("previousIntention")?.let { it.optString("aim") == aim && it.optString("releaseReason") == "用户结束这件事" } == true) return
        root.put("intention", JSONObject().put("aim", aim).put("motive", motive)
            .put("createdAt", now.toString()).put("outcomes", JSONArray()))
        save(characterId, root)
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

    @Synchronized fun stopIntention(characterId: String) {
        val root = state(characterId)
        root.optJSONObject("intention")?.let { root.put("previousIntention", it.put("releaseReason", "用户结束这件事")) }
        root.remove("intention")
        // Keep completed executor receipts in the raw timeline, not as fabricated life memories.
        save(characterId, root)
    }

    @Synchronized fun clearHistory(characterId: String) {
        val root = state(characterId)
        root.remove("intention")
        root.remove("previousIntention")
        root.remove("socialNames")
        root.remove("afterglow")
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
            }
            if (changed) save(characterId, root)
        }
    }

    fun profileContext(characterId: String): String {
        val profile = state(characterId).optJSONObject("profile") ?: return ""
        val sections = CharacterProfileSchema.groupedFields().mapNotNull { (group, fields) ->
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
                names.optString("preferredAddress").takeIf(String::isNotBlank)?.let {
                    appendLine("用户明确喜欢的日常称呼：$it（自然使用，可偶尔变换）")
                }
            }
            afterglowContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            root.optJSONObject("previousIntention")?.let { prior ->
                if (prior.optString("releaseReason") == "用户结束这件事")
                    appendLine("用户已结束的愿望：${prior.optString("aim")}，不可擅自重启")
            }
            root.optJSONObject("intention")
                ?.takeUnless { goal -> innerAims.any { sameCharacterMotive(goal.optString("aim"), it) } }
                ?.let { goal ->
                    appendLine("仍在意：${goal.optString("aim")}；原因：${goal.optString("motive")}；id=${goal.optString("createdAt")}")
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
                val address = names.optString("preferredAddress")
                if (remark.isNotBlank() || nickname.isNotBlank() || address.isNotBlank()) {
                    appendLine("【角色亲自设置的社交称呼｜已存储的状态，不改变现实姓名与用户资料】")
                    if (remark.isNotBlank()) appendLine("角色给用户的私人备注：$remark（不强迫每句都这样称呼）")
                    if (nickname.isNotBlank()) appendLine("角色自己的聊天网名：$nickname（原角色身份仍不变）")
                    if (address.isNotBlank()) appendLine("用户喜爱的日常称呼：$address（并非每句都必须使用）")
                }
            }
            afterglowContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            root.optJSONObject("previousIntention")?.let { appendLine("上一件已放下的事（不代表完成）：${it.optString("aim")}；原因：${it.optString("releaseReason")}。用户结束的事不要擅自重新开启。") }
            val innerGoals = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
            val innerAims = (0 until (innerGoals?.length() ?: 0))
                .mapNotNull { innerGoals?.optJSONObject(it)?.optString("aim")?.trim() }
                .toSet()
            root.optJSONObject("intention")
                ?.takeUnless { intention -> innerAims.any { aim -> sameCharacterMotive(intention.optString("aim"), aim) } }
                ?.let { intention ->
                appendLine("【持续动机｜角色主观愿望，不是已完成事实或用户承诺】")
                appendLine("从${intention.optString("createdAt")}开始在意：${intention.optString("aim")}；动机：${intention.optString("motive")}")
                appendLine("最近调整：${intention.optString("updatedAt", intention.optString("createdAt"))}；依据：${intention.optString("changeReason")}。")
                appendLine("动机ID=${intention.optString("createdAt")}。近期实际动作回执（不自动认定每个动作都推进这个愿望）：${intention.optJSONArray("outcomes") ?: JSONArray()}")
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
