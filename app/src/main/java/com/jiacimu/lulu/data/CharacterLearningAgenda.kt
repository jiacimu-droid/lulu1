package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Durable intentions. Knowledge remains in the source-linked learning ledger. */
internal object CharacterLearningAgenda {
    private var prefs: SharedPreferences? = null
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()
    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(
            "lulu_learning_agenda_v1", Context.MODE_PRIVATE)
    }
    private fun items(role: String): List<JSONObject> {
        val array = runCatching { JSONArray(prefs?.getString(role, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }
    private fun save(role: String, goals: List<JSONObject>) {
        check(checkNotNull(prefs).edit().putString(role, JSONArray().apply {
            goals.takeLast(24).forEach(::put)
        }.toString()).commit()) { "学习目标未能保存" }
        changes.value += 1
    }
    private fun instant(item: JSONObject, key: String): Instant? =
        runCatching { Instant.parse(item.optString(key)) }.getOrNull()
    private fun valid(role: String, goal: JSONObject): Boolean {
        val origin = SharedExperienceTimeline.eventsByIds(role,
            listOf(goal.optString("originId"))).firstOrNull() ?: return false
        return origin.revision == goal.optLong("originRevision", -1)
    }
    @Synchronized fun start(role: String, args: JSONObject, now: Instant): JSONObject {
        val topic = args.optString("topic").trim().take(90)
        val question = args.optString("question").trim().take(220)
        require(topic.length >= 2 && question.length >= 5) { "需要具体学习主题和问题" }
        val origin = SharedExperienceTimeline.eventsByIds(role,
            listOf(args.optString("sourceEvidenceId"))).firstOrNull()
            ?: error("学习起因必须来自真实仍存在的观察或对话")
        require(origin.evidenceKind in setOf(EventEvidenceKind.UserStatement,
            EventEvidenceKind.Observation, EventEvidenceKind.ToolResult)) { "自称已经学会不能作为学习起因" }
        val current = items(role).filter { valid(role, it) }
        current.firstOrNull { it.optString("topic").equals(topic, true) &&
            it.optString("status") != "dropped" }?.let { return JSONObject(it.toString()) }
        require(current.count { it.optString("status") !in setOf("dropped", "paused") } < 6) {
            "已有多项学习意向，先继续、暂缓或放下一项"
        }
        val goal = JSONObject().put("id", "learn-${UUID.randomUUID()}")
            .put("topic", topic).put("question", question)
            .put("why", args.optString("why").trim().take(220))
            .put("originId", origin.id).put("originRevision", origin.revision)
            .put("status", "planned").put("createdAt", now.toString())
            .put("reviews", 0).put("failures", 0)
        save(role, current + goal)
        return JSONObject(goal.toString())
    }
    @Synchronized fun get(role: String, id: String, now: Instant = Instant.now()): JSONObject? {
        val all = items(role)
        val goal = all.firstOrNull { it.optString("id") == id && valid(role, it) } ?: return null
        if (goal.optString("status") == "running" && LearningCyclePolicy.stale(instant(goal, "startedAt"), now)) {
            goal.put("status", "failed").put("error", "上次学习中断，尚无完成回执")
                .put("retryAt", now.toString())
            save(role, all)
        }
        val materialId = goal.optString("materialId")
        val material = SharedExperienceTimeline.eventsByIds(role, listOf(materialId)).firstOrNull()
        if (materialId.isNotBlank() && (material == null ||
            material.revision != goal.optLong("materialRevision", material.revision))) {
            goal.remove("materialId"); goal.remove("reviewAt")
            goal.remove("materialRevision")
            if (goal.optString("status") !in setOf("paused", "dropped")) goal.put("status", "planned")
            save(role, all)
        }
        return JSONObject(goal.toString())
    }
    @Synchronized fun update(role: String, id: String, change: (JSONObject) -> Unit): JSONObject {
        val all = items(role)
        val goal = all.firstOrNull { it.optString("id") == id && valid(role, it) }
            ?: error("学习目标已不存在或起因已撤回")
        change(goal); save(role, all)
        return JSONObject(goal.toString())
    }
    @Synchronized fun begin(context: Context, role: String, id: String, now: Instant) {
        val goal = get(role, id, now) ?: error("找不到有效学习目标")
        require(LearningCyclePolicy.available(goal.optString("status"), instant(goal, "retryAt"), now)) {
            "目标已暂停、放下、正在执行或尚在失败重试间隔中"
        }
        val policy = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val limit = policy.getInt("$role:learning_daily_limit", 2).coerceIn(0, 10)
        val day = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val used = prefs?.getInt("budget:$role:$day", 0) ?: 0
        require(used < limit) { "今天的自主学习预算已用完" }
        check(checkNotNull(prefs).edit().putInt("budget:$role:$day", used + 1).commit())
        val cleanup = checkNotNull(prefs).edit()
        checkNotNull(prefs).all.keys.filter { it.startsWith("budget:$role:") && it != "budget:$role:$day" }
            .forEach(cleanup::remove)
        cleanup.commit()
        update(role, id) { it.put("status", "running").put("startedAt", now.toString()) }
    }
    fun failed(role: String, id: String, error: String, now: Instant) {
        update(role, id) {
            val failures = it.optInt("failures") + 1
            it.put("failures", failures).put("status", "failed").put("error", error.take(220))
                .put("retryAt", LearningCyclePolicy.retryAt(now, failures - 1).toString())
        }
    }
    fun prompt(role: String, now: Instant = Instant.now()): String {
        val goals = goals(role, now)
            .filter { it.optString("status") != "dropped" }.takeLast(4)
        if (goals.isEmpty()) return ""
        return goals.joinToString("\n", "【本人持续学习意向｜不是已经学会】\n") { g ->
            val due = instant(g, "reviewAt")?.let { !it.isAfter(now) } == true
            "- goalId=${g.optString("id")}；${g.optString("topic")}：${g.optString("question")}；" +
                "阶段=${g.optString("status")}；材料证据=${g.optString("materialId")}；" +
                (if (due) "可自选复习；" else "") +
                "下一步=${g.optString("nextStep").take(150)}；" +
                g.optString("error").take(100) + "；可继续/练习/暂停/放下，不必向用户交日报"
        }
    }
    fun goals(role: String, now: Instant = Instant.now()): List<JSONObject> =
        items(role).mapNotNull { get(role, it.optString("id"), now) }
    @Synchronized fun invalidateEvidence(id: String) {
        val store = prefs ?: return
        store.all.keys.filterNot { it.startsWith("budget:") }.forEach { role ->
            val all = items(role)
            val before = all.map(JSONObject::toString)
            val retained = all.filterNot { it.optString("originId") == id }.map { goal ->
                if (goal.optString("materialId") == id) {
                    goal.remove("materialId"); goal.remove("reviewAt")
                    goal.remove("materialRevision")
                    if (goal.optString("status") !in setOf("paused", "dropped")) goal.put("status", "planned")
                }
                if (goal.optString("lastSearchId") == id) goal.remove("lastSearchId")
                if (goal.optString("lastReceiptId") == id) {
                    goal.remove("lastReceiptId"); goal.remove("nextStep"); goal.remove("reviewAt")
                    goal.put("reviews", 0)
                    if (goal.optString("status") !in setOf("paused", "dropped")) goal.put("status",
                        if (goal.optString("materialId").isBlank()) "planned" else "practice")
                }
                goal
            }
            if (before != retained.map(JSONObject::toString)) save(role, retained)
        }
    }
    @Synchronized fun clearCharacter(role: String) {
        val store = prefs ?: return
        val editor = store.edit().remove(role)
        store.all.keys.filter { it.startsWith("budget:$role:") }.forEach(editor::remove)
        check(editor.commit())
        changes.value += 1
    }
}
