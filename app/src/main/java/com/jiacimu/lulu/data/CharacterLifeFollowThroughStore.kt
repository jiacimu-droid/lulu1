package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Duration
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * One evidence-grounded continuation queue for autonomous sharing AND curiosity.
 *
 * A verified experience is an opportunity to develop an interest or to share;
 * it is NEVER a pre-authorized outbound message or an invented achievement.
 * Each axis can advance independently. The original event remains the anchor.
 */
internal object CharacterLifeFollowThroughStore {
    private const val PREFS = "lulu_verified_life_followthrough_v1"
    private const val MAX_EVENTS = 24
    private val lock = Any()
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun records(characterId: String): List<JSONObject> {
        val raw = prefs?.getString("character:$characterId", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull(array::optJSONObject)
        }.getOrDefault(emptyList())
    }

    private fun save(characterId: String, records: List<JSONObject>) {
        prefs?.edit()?.putString("character:$characterId", JSONArray().apply {
            records.takeLast(MAX_EVENTS).forEach(::put)
        }.toString())?.apply()
    }

    /**
     * Called ONLY after a program-owned factual event has been committed to the
     * unified timeline. Repeat receipts cannot generate another "new life".
     */
    fun register(characterId: String, evidenceId: String, channel: String,
                 summary: String, at: Instant = Instant.now()): Boolean = synchronized(lock) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank() || summary.isBlank()) {
            return@synchronized false
        }
        val all = records(characterId).toMutableList()
        if (all.any { it.optString("id") == evidenceId }) return@synchronized false
        val event = JSONObject().put("id", evidenceId).put("channel", channel.take(90))
            .put("fact", summary.take(340)).put("occurredAt", at.toString())
            .put("shareStatus", "open").put("curiosityStatus", "open")
            .put("createdAt", Instant.now().toString())
        all += event
        save(characterId, all)
        true
    }

    fun invalidateEvidence(evidenceId: String) = synchronized(lock) {
        if (prefs == null || evidenceId.isBlank()) return@synchronized
        prefs?.all?.keys?.filter { it.startsWith("character:") }?.forEach { key ->
            val role = key.removePrefix("character:")
            val old = records(role)
            val remaining = old.filterNot { it.optString("id") == evidenceId }
            if (old.size != remaining.size) save(role, remaining)
        }
    }

    private fun recent(characterId: String, now: Instant): List<JSONObject> =
        records(characterId).filter { item ->
            val created = runCatching { Instant.parse(item.optString("occurredAt")) }.getOrNull()
            created != null && !created.isAfter(now) &&
                Duration.between(created, now).toDays() <= 7 &&
                (item.optString("shareStatus") !in setOf("shared", "released") ||
                    item.optString("curiosityStatus") !in setOf("explored", "released"))
        }

    fun hasOpenOpportunity(characterId: String, evidenceId: String): Boolean = synchronized(lock) {
        records(characterId).any { it.optString("id") == evidenceId }
    }

    fun context(characterId: String, now: Instant = Instant.now()): String = synchronized(lock) {
        val opportunities = recent(characterId, now).takeLast(4).asReversed()
        if (opportunities.isEmpty()) return@synchronized ""
        buildString {
            appendLine("【真实经历留下的分享欲与好奇心｜你自己的选择，不是用户下达的任务】")
            opportunities.forEach { item ->
                appendLine("- experienceId=${item.optString("id")}；已证实发生=${item.optString("channel")}：${item.optString("fact")}")
                val sharing = when (item.optString("shareStatus")) {
                    "held" -> "之前想分享但还没发出去，可重新决定告诉用户、发公开朋友圈或放下"
                    "shared" -> "已分享，勿重复"
                    else -> "可以自己决定是否想分享给用户/公开朋友圈/群友，亦可不分享"
                }
                appendLine("  分享：$sharing；好奇探索：${when (item.optString("curiosityStatus")) {
                    "explored" -> "已有真实行动回执"
                    "released" -> "已决定放下"
                    else -> "如果真感兴趣，可观察/处理持续事件、读实际书、玩实际游戏；必须执行才算做过"
                }}")
                item.optString("shareWhy").takeIf(String::isNotBlank)?.let {
                    appendLine("  自己曾想分享的原因：${it.take(140)}")
                }
            }
            appendLine("选择普通私聊、公开朋友圈(moment)和群聊(group_message)是不同社交意图。公开朋友圈不是只供重大通报的稀有动作；生活趣事、吐槽、惊讶、阅读中真正触动的细节都可能值得发。不要每件事必发，也不要仅因没有用户未读消息就放弃本人的分享欲。")
            appendLine("如本次实际行动是在回应其中一段真实经历，填写对应 experienceId；如果暂时想分享但未行动，可以填 appraisal.shareWish={\"experienceId\":\"真实ID\",\"status\":\"held|released\",\"why\":\"私人理由\"}。念头不是发送回执，只有执行成功才记录已分享。")
        }.trim()
    }

    /** A private, optional preference to share later. No post is sent here. */
    fun recordShareWish(characterId: String, wish: JSONObject?) = synchronized(lock) {
        if (prefs == null || wish == null) return@synchronized
        val id = wish.optString("experienceId").trim()
        val status = wish.optString("status").trim().lowercase()
        if (id.isBlank() || status !in setOf("held", "released")) return@synchronized
        val all = records(characterId)
        val item = all.firstOrNull { it.optString("id") == id } ?: return@synchronized
        if (item.optString("shareStatus") in setOf("shared", "released")) return@synchronized
        item.put("shareStatus", status)
        item.put("shareWhy", wish.optString("why").trim().take(180))
        save(characterId, all)
    }

    /** Only a verified executor outcome can settle either axis of an intention. */
    fun recordAction(characterId: String, experienceId: String, action: String,
                     succeeded: Boolean, result: String) = synchronized(lock) {
        if (prefs == null || !succeeded || experienceId.isBlank()) return@synchronized
        val all = records(characterId)
        val item = all.firstOrNull { it.optString("id") == experienceId } ?: return@synchronized
        when (action.lowercase()) {
            "message", "group_message", "moment", "sticker", "group_sticker",
            "kaomoji", "group_kaomoji" -> item.put("shareStatus", "shared")
            "reading", "solo_game", "digital_world" -> item.put("curiosityStatus", "explored")
            else -> return@synchronized
        }
        item.put("lastAction", action.lowercase()).put("lastReceipt", result.take(200))
            .put("actedAt", Instant.now().toString())
        save(characterId, all)
    }
}
