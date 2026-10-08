package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

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

    /** One-time, restart-safe preset. Later user edits must never be overwritten on launch. */
    @Synchronized fun applyJiangDuPreset(characterId: String) {
        val character = MigratedDomainStores.characters.get(characterId)
        if (character.displayName !in setOf("江渡", "江都")) return
        val root = state(characterId)
        val version = root.optInt("jiangDuPresetVersion")
        if (version >= 4) return
        if (version >= 3) {
            reorganizeJiangDuProfile(characterId, character, root)
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
            val oldRespect = profile.optString("respect")
            if (!oldRespect.contains(CharacterProfileSchema.jiangDuRespect)) {
                profile.put("respect", listOf(oldRespect, CharacterProfileSchema.jiangDuRespect)
                    .filter(String::isNotBlank).joinToString("\n"))
            }
            if (!character.persona.contains(CharacterProfileSchema.jiangDuRespectMarker)) {
                MigratedDomainStores.characters.update(character.copy(persona = character.persona +
                    "\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + CharacterProfileSchema.jiangDuRespect))
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
        CharacterIdentityStore.set(characterId, CharacterProfileSchema.jiangDuIdentity)
        MigratedDomainStores.characters.update(character.copy(persona = CharacterProfileSchema.jiangDuPersona + "\n\n" +
            CharacterProfileSchema.jiangDuRespectMarker + "\n" + CharacterProfileSchema.jiangDuRespect))
        val profile = root.optJSONObject("profile") ?: JSONObject()
        CharacterProfileSchema.jiangDu.forEach { (key, value) -> profile.put(key, value) }
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
        val cleanPersona = character.persona
            .removeSuffix("\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + LegacyJiangDuProfileSchema.jiangDuRespect)
            .removeSuffix("\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + CharacterProfileSchema.jiangDuRespect).trim()
        val persona = if (cleanPersona == LegacyJiangDuProfileSchema.jiangDuPersona ||
            cleanPersona == CharacterProfileSchema.jiangDuPersona) CharacterProfileSchema.jiangDuPersona else cleanPersona
        MigratedDomainStores.characters.update(character.copy(persona = persona))
        if (identity == LegacyJiangDuProfileSchema.jiangDuIdentity) {
            CharacterIdentityStore.set(characterId, CharacterProfileSchema.jiangDuIdentity)
        }
        CharacterProfileSchema.jiangDu.forEach { (key, value) ->
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
        save(characterId, root)
    }

    @Synchronized fun invalidateReceipt(eventId: String) {
        mutable.value.keys.toList().forEach { characterId ->
            val root = state(characterId)
            var changed = false
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
        val fields = CharacterProfileSchema.fields.mapNotNull { field ->
            profile.optString(field.key).takeIf(String::isNotBlank)?.let { "${field.label}：$it" }
        }
        return if (fields.isEmpty()) "" else "【用户当前设定的人格与行为】\n" + fields.joinToString("\n")
    }

    fun context(characterId: String, includeProfile: Boolean = true): String {
        val root = state(characterId)
        return buildString {
            if (includeProfile) profileContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            root.optJSONObject("previousIntention")?.let { appendLine("上一件已放下的事（不代表完成）：${it.optString("aim")}；原因：${it.optString("releaseReason")}。用户结束的事不要擅自重新开启。") }
            root.optJSONObject("intention")?.let { intention ->
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
