package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Live, witnessed scene information. Heartbeats expire; persisted goals cannot attach to a new visit. */
internal object DigitalWorldLiveScene {
    internal data class Scene(val token: String, val location: String, val label: String,
        val x: Float, val y: Float, val witnesses: Set<String>, val detail: String, val at: Long)
    internal data class Goal(val id: String, val session: String, val action: String, val at: Long)
    internal data class Offer(val id: String, val session: String, val characterId: String,
        val action: String, val createdAt: Long)
    private val initiatedOffers = setOf("offer_hand", "offer_hug", "offer_follow")
    @Volatile private var scene: Scene? = null
    private var prefs: android.content.SharedPreferences? = null
    @Synchronized fun begin(context: Context, location: String, label: String): String {
        prefs = context.applicationContext.getSharedPreferences("lulu_world_movement_v1", Context.MODE_PRIVATE)
        val token = UUID.randomUUID().toString()
        scene = Scene(token, location, label, 800f, 820f, emptySet(), "", System.currentTimeMillis())
        return token
    }
    @Synchronized fun update(token: String, x: Float, y: Float, witnesses: Set<String>, detail: String) {
        val old = scene?.takeIf { it.token == token } ?: return
        scene = old.copy(x = x, y = y, witnesses = witnesses, detail = detail, at = System.currentTimeMillis())
    }
    @Synchronized fun end(token: String) {
        if (scene?.token == token) {
            scene = null
            prefs?.all?.keys?.toList()?.filterNot { it.startsWith("offer:") }
                ?.forEach { cancel(it, "用户结束了本次现场探索，移动目标停止") }
            prefs?.all?.keys?.filter { it.startsWith("offer:") }?.let { stale ->
                prefs?.edit()?.apply { stale.forEach(::remove) }?.commit()
            }
        }
    }
    fun witnessed(characterId: String, now: Long = System.currentTimeMillis()): Scene? =
        scene?.takeIf { fresh(it.at, now) && characterId in it.witnesses &&
            DigitalWorldStore.locationOf(characterId) == it.location }

    internal fun fresh(at: Long, now: Long): Boolean = now >= at && now - at <= 5_000L

    fun context(characterId: String): String = witnessed(characterId)?.let {
        "【当前实际同场】用户现在在${it.label}；${it.detail}。能看到行为，不能知道她的动机。" +
            "可选择 action=digital_world；worldAction=approach_user（实际走近用户）或 wait_at_entrance（去入口等候），也可 offer_hand/offer_hug/offer_follow（主动走近并发出牵手、拥抱或同行邀请）。" +
            "对方同意后才算真实接触；抵达后会出现现场可接受或拒绝的邀请，不把角色单方面想法当作用户同意。"
    }.orEmpty()

    @Synchronized fun request(characterId: String, action: String, now: Instant): String {
        require(action in setOf("approach_user", "wait_at_entrance") + initiatedOffers)
        val live = witnessed(characterId) ?: error("当前没有可核验的同场用户，不能执行旧到访的移动目标")
        val old = goal(characterId)
        if (old?.session == live.token && old.action == action) return "该移动目标仍在进行，尚未确认到达"
        val goal = JSONObject().put("id", "world-movement-${UUID.randomUUID()}")
            .put("session", live.token).put("action", action).put("at", now.toEpochMilli())
        check(prefs?.edit()?.putString(characterId, goal.toString())?.commit() == true)
        DigitalWorldActivityStateStore.pauseActivity(characterId, "自主选择离开原活动，开始现场走动", now)
        return when (action) {
            "approach_user" -> "已开始走近同场用户；尚未实际到达。"
            "offer_hand" -> "正向同场用户走去，准备询问是否牵手；还没有接触或得到同意。"
            "offer_hug" -> "正向同场用户走去，准备提出一个拥抱；还没有拥抱或得到同意。"
            "offer_follow" -> "正向同场用户走去，想邀请一起探索；尚未得到答复。"
            else -> "已开始走向本地点入口等候；尚未到达。"
        }
    }
    @Synchronized fun goal(characterId: String): Goal? {
        val raw = prefs?.getString(characterId, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val goal = Goal(json.optString("id"), json.optString("session"), json.optString("action"), json.optLong("at"))
        val live = witnessed(characterId)
        val now = System.currentTimeMillis()
        if (live == null || live.token != goal.session || now < goal.at || now - goal.at > 90_000L) {
            cancel(characterId, "现场目标失效或移动超时，未确认到达；需要根据新现场重新决定")
            return null
        }
        return goal
    }
    @Synchronized fun arrived(characterId: String, goalId: String) {
        val goal = goal(characterId)?.takeIf { it.id == goalId } ?: return
        prefs?.edit()?.remove(characterId)?.commit()
        if (goal.action in initiatedOffers) {
            val pending = JSONObject().put("id", goal.id).put("session", goal.session)
                .put("characterId", characterId).put("action", goal.action)
                .put("createdAt", System.currentTimeMillis())
            check(prefs?.edit()?.putString("offer:$characterId", pending.toString())?.commit() == true)
            receipt(characterId, goal.id,
                "角色主动来到用户身边，向用户提出${offerLabel(goal.action)}邀请；用户尚未答复，未发生接触。")
        } else receipt(characterId, goal.id, if (goal.action == "approach_user")
            "角色实际走到了用户身边；尚未发生对话或接触，用户的回应未知。" else
            "角色实际走到了本地点入口，正在那里等候；不能断言用户会来找自己。")
    }

    private fun offerLabel(action: String) = when (action) {
        "offer_hand" -> "牵手"
        "offer_hug" -> "拥抱"
        "offer_follow" -> "结伴探索"
        else -> "互动"
    }

    /** A map offer survives recomposition but expires on scene departure/timeout. */
    @Synchronized fun offer(characterId: String): Offer? {
        val json = runCatching { JSONObject(prefs?.getString("offer:$characterId", "{}")) }
            .getOrNull() ?: return null
        val offer = Offer(json.optString("id"), json.optString("session"),
            json.optString("characterId"), json.optString("action"),
            json.optLong("createdAt"))
        val live = witnessed(characterId)
        if (offer.id.isBlank() || offer.action !in initiatedOffers ||
            live == null || offer.session != live.token ||
            System.currentTimeMillis() - offer.createdAt !in 0L..120_000L) {
            prefs?.edit()?.remove("offer:$characterId")?.commit()
            return null
        }
        return offer
    }

    @Synchronized fun replyToOffer(characterId: String, offerId: String,
                                   accept: Boolean): String? {
        val pending = offer(characterId)?.takeIf { it.id == offerId } ?: return null
        check(prefs?.edit()?.remove("offer:$characterId")?.commit() == true)
        val response = if (accept) "用户明确接受了角色的${offerLabel(pending.action)}邀请。"
            else "用户拒绝或暂不接受角色的${offerLabel(pending.action)}邀请，没有接触。"
        receipt(characterId, "$offerId-response", response)
        return response
    }
    private fun cancel(characterId: String, reason: String) {
        val raw = prefs?.getString(characterId, null) ?: return
        val id = runCatching { JSONObject(raw).optString("id") }.getOrDefault("")
        prefs?.edit()?.remove(characterId)?.commit()
        if (id.isNotBlank()) receipt(characterId, "$id-cancel", reason)
    }
    private fun receipt(characterId: String, id: String, summary: String) {
        SharedExperienceTimeline.record(eventId = id, characterId = characterId,
            channel = "数字世界·现场行动结果", speaker = "场景事实", content = summary,
            source = "digital-world", evidenceKind = EventEvidenceKind.Observation, triggerExtraction = true)
    }
}
