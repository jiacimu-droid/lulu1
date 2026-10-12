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
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
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
        // Preserve a handful of significant unresolved lived experiences even
        // if many harmless ambient moments arrive later.
        val protectedIds = records.filter { it.optBoolean("significant") &&
            (it.optString("shareStatus") !in setOf("shared", "released") ||
                it.optString("curiosityStatus") !in setOf("explored", "released"))
        }.takeLast(6).map { it.optString("id") }.toSet()
        val retained = records.filter { it.optString("id") !in protectedIds }
            .takeLast(MAX_EVENTS - protectedIds.size) +
            records.filter { it.optString("id") in protectedIds }
        prefs?.edit()?.putString("character:$characterId", JSONArray().apply {
            retained.sortedBy { it.optString("occurredAt") }.forEach(::put)
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
        // Wake the already existing shared decision pipeline once for an
        // important verified experience; never auto-post or auto-read here.
        applicationContext?.let { ctx ->
            runCatching { ProactivePerceptionScheduler.scheduleLifeFollowThrough(ctx, characterId) }
        }
        true
    }

    /** An unresolved material incident gets a longer-lived chance to matter.
     *  It is still a PAST observation, never proof of its current location.
     */
    fun markSignificant(characterId: String, evidenceId: String) = synchronized(lock) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank()) return@synchronized
        val all = records(characterId)
        val item = all.firstOrNull { it.optString("id") == evidenceId } ?: return@synchronized
        if (!item.optBoolean("significant")) {
            item.put("significant", true)
            save(characterId, all)
        }
    }

    fun invalidateEvidence(evidenceId: String) = synchronized(lock) {
        if (prefs == null || evidenceId.isBlank()) return@synchronized
        prefs?.all?.keys?.filter { it.startsWith("character:") }?.forEach { key ->
            val role = key.removePrefix("character:")
            val old = records(role)
            val remaining = old.filterNot { it.optString("id") == evidenceId }.map { item ->
                val restored = JSONObject(item.toString())
                if (restored.optString("shareReceiptId") == evidenceId) {
                    restored.put("shareStatus", if (restored.optString("shareWhy").isNotBlank())
                        "held" else "open")
                    restored.remove("shareReceiptId")
                }
                if (restored.optString("curiosityReceiptId") == evidenceId) {
                    restored.put("curiosityStatus", "open")
                    restored.remove("curiosityReceiptId")
                }
                restored
            }
            if (old.map(JSONObject::toString) != remaining.map(JSONObject::toString)) save(role, remaining)
        }
    }

    private fun recent(characterId: String, now: Instant): List<JSONObject> =
        records(characterId).filter { item ->
            val created = runCatching { Instant.parse(item.optString("occurredAt")) }.getOrNull()
            created != null && !created.isAfter(now) &&
                (Duration.between(created, now).toDays() <= 7 ||
                    item.optBoolean("significant")) &&
                (item.optString("shareStatus") !in setOf("shared", "released") ||
                    item.optString("curiosityStatus") !in setOf("explored", "released"))
        }

    /** Real unresolved options; these only invite one more voluntary choice, never execute it. */
    fun pendingDecisionCues(characterId: String, now: Instant = Instant.now()): List<String> =
        synchronized(lock) {
            recent(characterId, now).asReversed().filter { item ->
                (item.optString("shareStatus") == "held" ||
                    item.optBoolean("significant") &&
                    item.optString("shareStatus") == "open") ||
                    (item.optBoolean("significant") && item.optString("curiosityStatus") == "open")
            }.take(2).map { item ->
                val id = item.optString("id")
                val share = item.optString("shareStatus")
                val wanted = item.optString("shareWhy")
                "真实生活经历 $id：" + item.optString("fact").take(115) + "；" +
                    (if (share == "held") "自己之前想分享：" + wanted.take(90) else "仍有分享/探究的选择余地") +
                    "；现在可重新决定是否分享或探索，也可克制；尚未执行"
            }
        }

    fun hasOpenOpportunity(characterId: String, evidenceId: String): Boolean = synchronized(lock) {
        records(characterId).any { it.optString("id") == evidenceId }
    }

    fun briefContext(characterId: String, now: Instant = Instant.now()): String = synchronized(lock) {
        val pending = recent(characterId, now).filter { it.optString("shareStatus") !in setOf("shared", "released") }.takeLast(2)
        if (pending.isEmpty()) return@synchronized ""
        buildString {
            appendLine("【自己真实经历过、还未分享的生活｜有兴趣才自然带入当前话题】")
            pending.forEach { item ->
                appendLine("- experienceId=${item.optString("id")}；[${item.optString("occurredAt")}] ${item.optString("fact").take(200)}")
                item.optString("shareWhy").takeIf(String::isNotBlank)?.let { appendLine("  当时想分享的理由：${it.take(100)}") }
            }
            append("这是过去经历，不是现在又发生；不要为了说点自己的事打断对方，也不必等她主动问。")
        }.trim()
    }

    fun context(characterId: String, now: Instant = Instant.now()): String = synchronized(lock) {
        val all = recent(characterId, now)
        val significant = all.filter { it.optBoolean("significant") }.takeLast(2)
        val opportunities = (all.takeLast(4) + significant)
            .distinctBy { it.optString("id") }.takeLast(5).asReversed()
        if (opportunities.isEmpty()) return@synchronized ""
        buildString {
            appendLine("【真实经历留下的分享欲与好奇心｜你自己的选择，不是用户下达的任务】")
            opportunities.forEach { item ->
                appendLine("- experienceId=${item.optString("id")}；已证实发生=${item.optString("channel")}：${item.optString("fact")}")
                val sharing = when (item.optString("shareStatus")) {
                    "held" -> "之前想分享但还没发出去，可重新决定告诉用户、发公开朋友圈或放下"
                    "shared" -> "已分享，勿重复"
                    "released" -> "已经决定不分享，除非之后有确切的新想法，否则不要重新催促"
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
            appendLine("以上是曾经真实发生的经历；事件是否仍持续，必须以当前程序世界状态为准。旧的蟑螂可能已被处理，旧阅读也不能算刚读到新章节。")
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

    /**
     * One real sender or executor receipt is required. Planning, status text and
     * model-authored "I posted" are insufficient. The exact evidence ID lets
     * deletion of a bubble/post retract the share result without erasing the experience.
     */
    fun recordAction(characterId: String, experienceId: String, action: String,
                     succeeded: Boolean, result: String,
                     actionEvidenceId: String = "") = synchronized(lock) {
        if (prefs == null || !succeeded || experienceId.isBlank() ||
            actionEvidenceId.isBlank()) return@synchronized
        val normalized = action.lowercase()
        val share = normalized in setOf("message", "group_message", "moment", "sticker",
            "group_sticker", "kaomoji", "group_kaomoji")
        val explore = normalized in setOf("reading", "solo_game", "digital_world")
        if (!share && !explore) return@synchronized
        // Group chat mirrors a real message once per member with a namespaced
        // timeline ID. Resolve to this role's own receipt so deletion can retract
        // the share outcome even when the original sender returned a bare ID.
        val receipt = SharedExperienceTimeline.eventsByIds(
            characterId, listOf(actionEvidenceId, "$actionEvidenceId:group:$characterId"),
        ).firstOrNull() ?: return@synchronized
        if (receipt.evidenceKind !in setOf(EventEvidenceKind.CharacterStatement,
                EventEvidenceKind.ToolResult, EventEvidenceKind.Observation)) return@synchronized
        if (share && receipt.evidenceKind != EventEvidenceKind.CharacterStatement) return@synchronized
        if (normalized == "moment" && receipt.source != "moment:self") return@synchronized
        if (explore && receipt.evidenceKind != EventEvidenceKind.ToolResult &&
            !receipt.source.startsWith("reading:") &&
            !receipt.channel.startsWith("独自") &&
            !receipt.channel.startsWith("数字世界")) return@synchronized
        val all = records(characterId)
        val item = all.firstOrNull { it.optString("id") == experienceId } ?: return@synchronized
        val occurred = runCatching { Instant.parse(item.optString("occurredAt")) }.getOrNull()
            ?: return@synchronized
        if (receipt.occurredAt.isBefore(occurred)) return@synchronized
        if (share) {
            if (item.optString("shareStatus") in setOf("shared", "released")) return@synchronized
            item.put("shareStatus", "shared").put("shareReceiptId", receipt.id)
        } else {
            if (item.optString("curiosityStatus") in setOf("explored", "released")) return@synchronized
            item.put("curiosityStatus", "explored").put("curiosityReceiptId", receipt.id)
        }
        item.put("lastAction", normalized).put("lastReceipt", result.take(200))
            .put("actedAt", Instant.now().toString())
        save(characterId, all)
    }
}
