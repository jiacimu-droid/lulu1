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
            prefs?.all?.keys?.toList()?.forEach { cancel(it, "用户结束了本次现场探索，移动目标停止") }
        }
    }
    fun witnessed(characterId: String, now: Long = System.currentTimeMillis()): Scene? =
        scene?.takeIf { fresh(it.at, now) && characterId in it.witnesses &&
            DigitalWorldStore.locationOf(characterId) == it.location }

    internal fun fresh(at: Long, now: Long): Boolean = now >= at && now - at <= 5_000L

    fun context(characterId: String): String = witnessed(characterId)?.let {
        "【当前实际同场】用户现在在${it.label}；${it.detail}。能看到行为，不能知道她的动机。" +
            "可选择 action=digital_world；worldAction=approach_user（实际走近用户）或 wait_at_entrance（走到本地点入口等候）。" +
            "用户离开或场景结束后目标停止；开始走动不等于已经到达，也不等于用户接受互动。"
    }.orEmpty()

    @Synchronized fun request(characterId: String, action: String, now: Instant): String {
        require(action in setOf("approach_user", "wait_at_entrance"))
        val live = witnessed(characterId) ?: error("当前没有可核验的同场用户，不能执行旧到访的移动目标")
        val old = goal(characterId)
        if (old?.session == live.token && old.action == action) return "该移动目标仍在进行，尚未确认到达"
        val goal = JSONObject().put("id", "world-movement-${UUID.randomUUID()}")
            .put("session", live.token).put("action", action).put("at", now.toEpochMilli())
        check(prefs?.edit()?.putString(characterId, goal.toString())?.commit() == true)
        DigitalWorldActivityStateStore.pauseActivity(characterId, "自主选择离开原活动，开始现场走动", now)
        return if (action == "approach_user") "已开始走近同场用户，尚未到达；以地图后续到达记录为准"
            else "已开始走向本地点入口等候，尚未到达；以地图后续到达记录为准"
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
        receipt(characterId, goal.id, if (goal.action == "approach_user")
            "角色实际走到了用户身边；尚未发生对话或接触，用户的回应未知。" else
            "角色实际走到了本地点入口，正在那里等候；不能断言用户会来找自己。")
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
