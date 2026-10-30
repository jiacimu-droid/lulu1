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
        // A model-authored decision to hold a wish survives the 24-item
        // ambient working window. Only non-committed background opportunities
        // may rotate out of this compact store; source truth stays in timeline.
        val protected = records.filter {
            it.optString("shareStatus") == "held" ||
                (it.optBoolean("significant") &&
                    (it.optString("shareStatus") !in setOf("shared", "released") ||
                        it.optString("curiosityStatus") !in setOf("explored", "released")))
        }
        val ids = protected.map { it.optString("id") }.toSet()
        val retained = records.filterNot { it.optString("id") in ids }
            .takeLast(MAX_EVENTS) + protected
        prefs?.edit()?.putString("character:$characterId", JSONArray().apply {
            retained.sortedBy { it.optString("occurredAt") }.forEach(::put)
        }.toString())?.apply()
    }

    /**
     * Called ONLY after a program-owned factual event has been committed to the
     * unified timeline. Repeat receipts cannot generate another "new life".
     */
    fun register(characterId: String, evidenceId: String, channel: String,
                 summary: String, at: Instant = Instant.now(), sourceEvidenceId: String = ""): Boolean = synchronized(lock) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank() || summary.isBlank()) {
            return@synchronized false
        }
        val all = records(characterId).toMutableList()
        if (all.any { it.optString("id") == evidenceId }) return@synchronized false
        val event = JSONObject().put("id", evidenceId).put("channel", channel.take(90))
            .put("fact", summary.take(340)).put("occurredAt", at.toString())
            .put("shareStatus", "open").put("curiosityStatus", "open")
            .put("createdAt", Instant.now().toString())
            .put("sourceEvidenceId", sourceEvidenceId)
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
            val remaining = old.filterNot { it.optString("id") == evidenceId ||
                it.optString("sourceEvidenceId") == evidenceId }.map { item ->
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
                    item.optBoolean("significant") ||
                    item.optString("shareStatus") == "held") &&
                (item.optString("shareStatus") !in setOf("shared", "released") ||
                    item.optString("curiosityStatus") !in setOf("explored", "released"))
        }

    /**
     * Read-only psychological sharing impulse, derived from the existing verified
     * experience queue rather than stored as a competing personality/body stat.
     * Independent lived moments can add up; fulfilled or explicitly released
     * wishes stop contributing. Old events fade, but remain in the timeline.
     * 0–100 is a decision cue, NEVER an automatic send threshold.
     */
    fun sharingUrge(characterId: String, now: Instant = Instant.now()): Double =
        synchronized(lock) {
            recent(characterId, now).asSequence()
                .filter { it.optString("shareStatus") in setOf("open", "held") }
                .sumOf { item ->
                    val at = runCatching { Instant.parse(item.optString("occurredAt")) }
                        .getOrNull() ?: return@sumOf 0.0
                    val days = Duration.between(at, now).toMillis().coerceAtLeast(0L) /
                        86_400_000.0
                    val held = item.optString("shareStatus") == "held"
                    val strength = item.optDouble("shareStrength", 55.0)
                        .takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: 55.0
                    val seed = if (held) 18.0 + strength * 0.16 else 12.0
                    val significant = if (item.optBoolean("significant")) 12.0 else 0.0
                    (seed + significant) * kotlin.math.exp(-days / 5.0)
                }.coerceIn(0.0, 100.0)
        }

    /**
     * Choose an actual source of the accumulated urge. Previously the gauge
     * counted ALL open experiences but only held/significant ones could reach
     * the decision handoff, so 100/100 could produce zero actionable cues.
     * Explicitly held wishes lead, while high unresolved urge may surface
     * ordinary verified life as a *choice*, never as an automatic post.
     */
    internal fun sharingCandidates(characterId: String, now: Instant = Instant.now()): List<JSONObject> =
        synchronized(lock) {
            val impulse = sharingUrge(characterId, now)
            recent(characterId, now).asReversed()
                .filter { it.optString("shareStatus") == "held" ||
                    (impulse >= 70.0 && it.optString("shareStatus") == "open") ||
                    (it.optBoolean("significant") && it.optString("shareStatus") == "open") }
                .sortedWith(compareByDescending<JSONObject> { it.optString("shareStatus") == "held" }
                    .thenByDescending { it.optString("shareStrength").toDoubleOrNull() ?: 0.0 }
                    .thenByDescending { it.optString("occurredAt") })
                .take(2)
                .map { JSONObject(it.toString()) }
        }

    /** Re-evaluate concrete lived experiences; never auto-send at a numeric threshold. */
    fun pendingDecisionCues(characterId: String, now: Instant = Instant.now()): List<String> =
        synchronized(lock) {
            sharingCandidates(characterId, now).map { item ->
                val id = item.optString("id")
                val held = item.optString("shareStatus") == "held"
                "真实生活经历 experienceId=$id：" + item.optString("fact").take(115) + "；" +
                    (if (held) "自己明确想分享却尚未发送：" +
                        item.optString("shareWhy").take(90)
                    else "目前分享冲动累积较强；这一件是可选的真实素材，不代表已经想好要讲") +
                    "；若现在真想说，直接选 message/moment/group_message 并带准确 experienceId；" +
                    "不想分享可选择安静或为该经历写 shareWish=released；尚无送达回执"
            }
        }

    /** Existing recorded life only; do not create another copy for the UI. */
    fun auditSnapshot(characterId: String): List<JSONObject> = synchronized(lock) {
        records(characterId).filter {
            SharedExperienceTimeline.eventsByIds(characterId, listOf(it.optString("id"))).isNotEmpty()
        }.map { JSONObject(it.toString()) }
    }

    fun hasOpenOpportunity(characterId: String, evidenceId: String): Boolean = synchronized(lock) {
        records(characterId).any { it.optString("id") == evidenceId }
    }

    fun briefContext(characterId: String, now: Instant = Instant.now()): String = synchronized(lock) {
        val held = sharingCandidates(characterId, now)
        val latest = recent(characterId, now).asReversed()
            .filter { it.optString("shareStatus") in setOf("open", "held") }
        val pending = (held + latest).distinctBy { it.optString("id") }.take(2)
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
        val held = sharingCandidates(characterId, now)
        val significant = all.asReversed().filter { it.optBoolean("significant") }.take(2)
        val opportunities = (held + significant + all.asReversed())
            .distinctBy { it.optString("id") }.take(5)
        if (opportunities.isEmpty()) return@synchronized ""
        buildString {
            appendLine("【真实经历留下的分享欲与好奇心｜你自己的选择，不是用户下达的任务】")
            val shareImpulse = sharingUrge(characterId, now).toInt()
            appendLine("当前累计分享冲动≈$shareImpulse/100，来自多条尚未分享的真实经历；不是必须发送的阈值。若曾明确想分享却一直未说，先重新认真考虑那件事；若只是多次小事累积到很高，挑其中一件真想讲的经历，而不是把进度条本身当分享内容。没有真实兴趣也可以继续克制。")
            opportunities.forEach { item ->
                appendLine("- experienceId=${item.optString("id")}；已证实发生=${item.optString("channel")}：${item.optString("fact")}")
                val sharing = when (item.optString("shareStatus")) {
                    "held" -> "之前想分享但还没发出去，可重新决定告诉用户、发好友可见朋友圈或放下"
                    "shared" -> "已分享，勿重复"
                    "released" -> "已经决定不分享，除非之后有确切的新想法，否则不要重新催促"
                    else -> "可以自己决定是否想分享给用户/好友可见朋友圈/群友，亦可不分享"
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
            appendLine("【社交是自己的真实行动选项】累计分享欲较高时，请把上述某件真实经历与本人的关系、心情和其他当前兴趣认真比较；分享欲不是机械发信指令，但也不是只能显示在进度条上的装饰。想交流时可以私聊用户(message)、联系实际好友(send_character_message)、在已有群里交谈(group_message)或发好友可见朋友圈(moment)；具体对象和内容由本人结合已给出的真实联系人与groupId选择。别因没有未读消息就误以为不能先发起话题。")
            appendLine("如果只是猜测对方可能在忙，不能把猜测当成对方已拒绝联系的事实。轻松分享一件自己的小事可以不要求对方即时回复；真正的明确拒绝、安静边界和已知特殊情况才应据实考虑。也可以真心独处、暂缓、换个对象或安排稍后的本人日程，不强迫联系任何人。不要把‘怕打扰’当成每条社交可能性都不能执行的默认理由。")
            appendLine("如本次实际行动是在回应其中一段真实经历，填写对应 experienceId；如果暂时想分享但未行动，可填 appraisal.shareWish={\"experienceId\":\"真实ID\",\"status\":\"held|released\",\"why\":\"私人理由\",\"intensity\":0到100可选}。多个未分享的真实经历能积累分享冲动；是否和主人私聊、向好友发圈、和实际群友交流或暂时不说，仍由自己决定。念头不是发送回执，只有执行成功才记录已分享。")
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
        val newlyHeld = status == "held" && item.optString("shareStatus") != "held"
        item.put("shareStatus", status)
        item.put("shareWhy", wish.optString("why").trim().take(180))
        // Optional first-person assessment of how strongly THIS experience
        // makes them want to share. Never inferred from the user's feelings.
        if (status == "held" && wish.has("intensity")) {
            val intensity = wish.optDouble("intensity", Double.NaN)
            if (intensity.isFinite()) item.put("shareStrength", intensity.coerceIn(0.0, 100.0))
        }
        save(characterId, all)
        // Only a NEW firsthand intention earns a follow-through wake. Repeated
        // "still want to share" appraisals do not create a paid polling loop.
        if (newlyHeld) applicationContext?.let { ctx ->
            runCatching { ProactivePerceptionScheduler.scheduleLifeFollowThrough(ctx, characterId) }
        }
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
        val receipt = CharacterActionEvidence.resolve(characterId, actionEvidenceId)
            ?.takeIf { CharacterActionEvidence.supports(it, normalized) } ?: return@synchronized
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
