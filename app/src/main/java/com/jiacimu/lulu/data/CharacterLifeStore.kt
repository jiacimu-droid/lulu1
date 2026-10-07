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
    }

    fun state(characterId: String): JSONObject = runCatching {
        JSONObject(mutable.value[characterId] ?: "{}")
    }.getOrDefault(JSONObject())

    @Synchronized fun setProfile(characterId: String, key: String, value: String) {
        require(key in setOf("values", "care", "conflict", "interests", "expression"))
        val root = state(characterId)
        val profile = root.optJSONObject("profile") ?: JSONObject()
        profile.put(key, value.trim().take(1000))
        root.put("profile", profile)
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

    fun context(characterId: String): String {
        val root = state(characterId)
        return buildString {
            root.optJSONObject("profile")?.let { profile ->
                appendLine("【用户设定的行为性格｜补充人设，不能替代原有锁定人设】")
                val labels = mapOf("values" to "在意与底线", "care" to "表达关心的方式", "conflict" to "分歧与受挫反应", "interests" to "自己的兴趣", "expression" to "语言与情绪表达")
                labels.forEach { (key, label) -> profile.optString(key).takeIf(String::isNotBlank)?.let { appendLine("$label：$it") } }
            }
            root.optJSONObject("previousIntention")?.let { appendLine("上一件已放下的事（不代表完成）：${it.optString("aim")}；原因：${it.optString("releaseReason")}。用户结束的事不要擅自重新开启。") }
            root.optJSONObject("intention")?.let { intention ->
                appendLine("【持续动机｜角色主观愿望，不是已完成事实或用户承诺】")
                appendLine("从${intention.optString("createdAt")}开始在意：${intention.optString("aim")}；动机：${intention.optString("motive")}")
                appendLine("实际执行回执：${intention.optJSONArray("outcomes") ?: JSONArray()}")
                appendLine("结合真实结果决定接着做、换办法、等待或保持安静。成功回执只证明该动作，不证明长期愿望已经实现；发送关心不等于用户接受，日记不等于现实经历。")
            }
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
