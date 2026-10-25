package com.jiacimu.lulu.data

import java.time.Instant
import org.json.JSONObject

/** Read-only trace. A plan, a claim and a verifiable execution are not the same thing. */
internal data class CharacterLifeVisible(
    val at: Instant,
    val category: String,
    val title: String,
    val detail: String,
    val evidenceId: String = "",
    val learningGoalId: String = "",
)

internal object CharacterLifeVisibility {
    private fun parse(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
    private fun source(role: String, id: String) =
        if (id.isBlank()) null else SharedExperienceTimeline.eventsByIds(role, listOf(id)).firstOrNull()
    private fun stage(raw: String) = when (raw) {
        "considering" -> "考虑中"
        "decided" -> "已经决定"
        "executing" -> "执行中"
        "blocked" -> "遇到阻碍"
        "following_up" -> "行动有结果，等待后续"
        "paused" -> "暂缓"
        "released" -> "已放下"
        else -> "考虑中"
    }
    private fun jsonEntries(array: org.json.JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    /** Avatar is current inner life, never the entire historic audit. */
    fun current(role: String, now: Instant = Instant.now()): List<CharacterLifeVisible> = buildList {
        // 此刻 is a snapshot of active mental changes, not a task inbox.
        // Persisted goals remain available to the planner and their original
        // stores; do not duplicate a stale "想继续做" on every avatar open.
        val mind = CharacterInnerLifeStore.snapshot(role)
        CharacterOpenConcernRuntime.recent(mind.optJSONArray("openConcerns"), now)
            .lastOrNull { source(role, it.optString("evidenceId")) != null }
            ?.let { concern ->
                val t = parse(concern.optString("updatedAt")) ?:
                    parse(concern.optString("createdAt"))
                if (t != null && !t.isAfter(now) && concern.optString("focus").isNotBlank()) {
                    add(CharacterLifeVisible(t, "当前", "还在挂心",
                        concern.optString("focus").take(170) +
                            if (concern.optString("whyItMatters").isNotBlank())
                                "\n缘由：" + concern.optString("whyItMatters").take(140)
                            else ""))
                }
            }
        val curiosity = CharacterCuriosityRuntime.snapshot(role)
        jsonEntries(curiosity.optJSONArray("questions"))
            .filter { source(role, it.optString("observedId")) != null }
            .takeLast(2).forEach { q ->
                val t = parse(q.optString("at")) ?: return@forEach
                if (!t.isAfter(now)) add(CharacterLifeVisible(t, "当前",
                    "尚未实践的好奇 · " + q.optString("topic").take(65),
                    q.optString("question").take(175)))
            }
        jsonEntries(curiosity.optJSONArray("threads")).filter { it.optString("status") == "exploring" }
            .takeLast(1).forEach { thread ->
                val step = jsonEntries(thread.optJSONArray("steps"))
                    .lastOrNull { source(role, it.optString("evidenceId")) != null }
                    ?: return@forEach
                val t = parse(step.optString("at")) ?: return@forEach
                if (!t.isAfter(now)) add(CharacterLifeVisible(t, "当前",
                    "想继续探索 · " + thread.optString("topic").take(70),
                    thread.optString("question").take(150) +
                        "\n上次行动：" + step.optString("receipt").take(140)))
            }
        // Held shares are past possibilities, not perpetual *current* feelings.
        // They remain in their original follow-through store and may be reconsidered
        // through the shared decision runtime without being pinned to 此刻.
    }


    /**
     * Life Records -> 心事 is a read-only window into the existing character's
     * subjective state. Neither a new diary nor an alternative goal/task store.
     * Mental entries require role-scoped evidence; schedules use the original persisted plan.
     */
    fun matters(role: String, now: Instant = Instant.now(),
        @Suppress("UNUSED_PARAMETER") historyLimit: Int = 160,
        plans: List<RoleDayPlan> = emptyList()): List<CharacterLifeVisible> = buildList {
        addAll(scheduledMatters(role, plans, now))
        val mind = CharacterInnerLifeStore.snapshot(role)
        fun include(at: Instant?, category: String, title: String, detail: String, id: String) {
            if (at == null || at.isAfter(now) || id.isBlank() ||
                detail.isBlank() || source(role, id) == null) return
            add(CharacterLifeVisible(at, category, title, detail.trim(), id))
        }
        val motives = mind.optJSONArray("motives")
        (CharacterMotiveState.active(motives) + CharacterMotiveState.paused(motives))
            .distinctBy { it.optString("id") }.forEach { motive ->
                val state = when (motive.optString("status")) {
                    "paused" -> "暂缓"
                    "waiting_feedback" -> "已经尝试，等待反馈"
                    else -> "仍想做"
                }
                val last = jsonEntries(motive.optJSONArray("outcomes")).lastOrNull()
                val attempt = last?.takeIf { outcome ->
                    outcome.optString("id").isNotBlank() &&
                        CharacterActionEvidence.resolve(role, outcome.optString("id"))?.let {
                            CharacterActionEvidence.supports(it, outcome.optString("action"))
                        } == true
                }?.let { outcome ->
                    "\n最近尝试：" + outcome.optString("summary").take(180)
                }.orEmpty()
                include(parse(motive.optString("updatedAt")) ?: parse(motive.optString("createdAt")),
                    "持续意愿", motive.optString("aim").take(180),
                    state + "\n" + motive.optString("why").take(180) +
                        CharacterMotiveProgress.brief(motive).takeIf(String::isNotBlank)
                            ?.let { "\n$it" }.orEmpty() + attempt,
                    motive.optString("evidenceId"))
            }


        val recentConcerns = CharacterOpenConcernRuntime.recent(mind.optJSONArray("openConcerns"), now)
            .mapTo(hashSetOf()) { it.optString("id") }
        jsonEntries(mind.optJSONArray("openConcerns")).forEach { concern ->
            val detail = listOf(concern.optString("whyItMatters"),
                concern.optString("hesitation").takeIf(String::isNotBlank)?.let { "还在权衡：$it" },
                concern.optString("possibleNextStep").takeIf(String::isNotBlank)?.let { "曾考虑：$it" },
                concern.optJSONObject("lastOutcome")?.optString("summary")?.takeIf(String::isNotBlank)?.let { "最近尝试：$it" })
                .filterNotNull().filter(String::isNotBlank).joinToString("\n")
            include(parse(concern.optString("updatedAt")) ?: parse(concern.optString("createdAt")),
                "挂心", concern.optString("focus").take(180),
                (if (concern.optString("id") in recentConcerns) "尚未想通" else "暂时沉淀") +
                    if (detail.isBlank()) "" else "\n$detail",
                concern.optString("evidenceId"))
        }


        CharacterLearningAgenda.goals(role, now).filter { it.optString("status") != "dropped" }.forEach { goal ->
            val origin = goal.optString("originId")
            if (source(role, origin) == null) return@forEach
            val time = parse(goal.optString("failedAt")) ?: parse(goal.optString("startedAt")) ?:
                parse(goal.optString("createdAt")) ?: return@forEach
            if (time.isAfter(now)) return@forEach
            val status = when (goal.optString("status")) {
                "failed" -> "遇到阻碍，尚未完成"
                "running" -> "正在探索"
                "paused" -> "已暂缓"
                "research" -> "已有材料，等待理解"
                "practice" -> "已有暂定理解，仍可实践"
                else -> "准备继续了解"
            }
            val detail = listOf(status, goal.optString("question"),
                goal.optString("error").takeIf(String::isNotBlank)?.let { "上次失败：$it" },
                goal.optString("nextStep").takeIf(String::isNotBlank)?.let { "曾考虑：$it" })
                .filterNotNull().filter(String::isNotBlank).joinToString("\n")
            add(CharacterLifeVisible(time, "正在琢磨", goal.optString("topic"), detail,
                origin, goal.optString("id")))
        }
        CharacterCuriosityRuntime.visibleQuestions(role).forEach { q ->
            include(parse(q.optString("at")), "正在琢磨", q.optString("topic").take(90),
                listOf(q.optString("question"),
                    q.optString("hypothesis").takeIf(String::isNotBlank)?.let { "待验证：$it" },
                    q.optString("nextStep").takeIf(String::isNotBlank)?.let { "可能下一步：$it" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n"),
                q.optString("observedId"))
        }
        CharacterCuriosityRuntime.activeThreads(role).forEach { thread ->
            val step = jsonEntries(thread.optJSONArray("steps")).lastOrNull { entry ->
                val evidence = CharacterActionEvidence.resolve(role, entry.optString("evidenceId"))
                evidence != null && evidence.revision == entry.optLong("revision", evidence.revision) &&
                    CharacterActionEvidence.supports(evidence, entry.optString("action"))
            } ?: return@forEach
            include(parse(step.optString("at")), "正在琢磨", thread.optString("topic").take(90),
                listOf(thread.optString("question"),
                    step.optString("receipt").takeIf(String::isNotBlank)?.let { "上次实际结果：$it" },
                    thread.optString("nextStep").takeIf(String::isNotBlank)?.let { "可能下一步：$it" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n"),
                step.optString("evidenceId"))
        }


    }.distinctBy { listOf(it.category, it.title, it.detail, it.evidenceId).joinToString("|") }
        .sortedWith(compareBy<CharacterLifeVisible> { when (it.category) { "挂心" -> 0; "持续意愿" -> 1; "日程" -> 3; else -> 2 } }
            .thenComparator { a, b -> if (a.category == "日程") a.at.compareTo(b.at) else b.at.compareTo(a.at) }
            .thenBy { it.title })

    /** The original schedule is authoritative; future slots are intentionally visible. */
    internal fun scheduledMatters(role: String, plans: List<RoleDayPlan>,
        now: Instant): List<CharacterLifeVisible> = plans.filter {
        it.characterId == role && it.status in setOf("planned", "due") && !it.createdAt.isAfter(now)
    }.sortedBy { it.dueAt }.map { plan ->
        val time = plan.dueAt.atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
        CharacterLifeVisible(plan.dueAt, "日程", plan.title,
            listOf(if (plan.status == "due" || !plan.dueAt.isAfter(now)) "到期待处理" else "已安排",
                "时间：$time", plan.intent,
                if (plan.repeat == "daily") "每天重复" else "",
                plan.resultSummary.takeIf(String::isNotBlank)?.let { "上次结果：$it" }.orEmpty())
                .filter(String::isNotBlank).joinToString("\n"), "own-plan-${plan.id}")
    }

}
