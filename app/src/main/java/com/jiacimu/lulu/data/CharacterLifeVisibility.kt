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
        else -> "尚未确认"
    }
    private fun jsonEntries(array: org.json.JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    /** Avatar is current inner life, never the entire historic audit. */
    fun current(role: String, now: Instant = Instant.now()): List<CharacterLifeVisible> = buildList {
        val life = CharacterLifeStore.state(role)
        life.optJSONObject("intention")?.let { goal ->
            val start = parse(goal.optString("createdAt"))
            if (start != null && !start.isAfter(now)) {
                val output = buildString {
                    append(goal.optString("aim").take(180))
                    if (goal.optString("motive").isNotBlank())
                        append("\n缘由：" + goal.optString("motive").take(170))
                    append("\n阶段：" + stage(goal.optString("stage")))
                    val outcomes = goal.optJSONArray("outcomes")
                    val last = outcomes?.optJSONObject(outcomes.length() - 1)
                    if (last != null && source(role, last.optString("id")) != null) {
                        append("\n上次实际执行：" + last.optString("summary").take(160))
                        append(if (last.optBoolean("success")) "（动作成功，愿望未必完成）" else "（没成功）")
                    }
                }
                add(CharacterLifeVisible(start, "当前", "持续想做的事 · " +
                    stage(goal.optString("stage")), output))
            }
        }
        val mind = CharacterInnerLifeStore.snapshot(role)
        jsonEntries(mind.optJSONArray("openConcerns"))
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
        CharacterLifeFollowThroughStore.auditSnapshot(role).asReversed()
            .filter { it.optString("shareStatus") == "held" }.take(2).forEach { event ->
                val t = parse(event.optString("occurredAt")) ?: return@forEach
                if (!t.isAfter(now)) add(CharacterLifeVisible(t, "当前",
                    "想分享，但还没有发出",
                    event.optString("fact").take(155) +
                        if (event.optString("shareWhy").isNotBlank())
                            "\n原因：" + event.optString("shareWhy").take(150)
                        else ""))
            }
    }

    /** Long-term history belongs to the read-only 足迹 in 辞海. */
    fun archive(role: String, now: Instant = Instant.now()): List<CharacterLifeVisible> {
        val items = mutableListOf<CharacterLifeVisible>()
        CharacterGrowthTrajectory.entries(role, now).forEach { trace ->
            items += CharacterLifeVisible(trace.at,
                if (trace.category == "学习") "探索" else if (trace.category == "行动") "行动" else "成长",
                trace.label, trace.detail, trace.evidenceId)
        }
        val mind = CharacterInnerLifeStore.snapshot(role)
        jsonEntries(mind.optJSONArray("decisions")).forEach { decision ->
            val id = decision.optString("id")
            if (!id.startsWith("perception:") && !id.startsWith("self-activity-")) return@forEach
            val t = parse(decision.optString("at")) ?: return@forEach
            if (t.isAfter(now)) return@forEach
            val action = decision.optString("selected")
            val label = if (action == "silent") "自行选择安静"
                else if (decision.optBoolean("succeeded")) "执行器报告已完成"
                else "尝试失败或尚无成功回执"
            items += CharacterLifeVisible(t, "行动", label,
                "决定：" + action.take(80) +
                    "\n原因：" + decision.optString("reason").ifBlank { "没有记录理由" }.take(180) +
                    "\n结果：" + decision.optString("outcome").ifBlank { "没有结果回执" }.take(210),
                decision.optString("causalEvidenceId"))
        }
        val life = CharacterLifeStore.state(role)
        listOf("intention", "previousIntention", "retiredGenericIntention").forEach { name ->
            val intention = life.optJSONObject(name) ?: return@forEach
            jsonEntries(intention.optJSONArray("outcomes")).forEach { receipt ->
                val evidence = source(role, receipt.optString("id")) ?: return@forEach
                val t = parse(receipt.optString("at")) ?: evidence.occurredAt
                if (t.isAfter(now)) return@forEach
                items += CharacterLifeVisible(t, "行动",
                    if (receipt.optBoolean("success")) "心愿关联动作有回执" else "心愿关联动作未完成",
                    "原本想做：" + intention.optString("aim").take(125) +
                        "\n执行：" + receipt.optString("action").take(80) +
                        "\n结果：" + receipt.optString("summary").take(200) +
                        "\n动作完成不代表心愿已经实现",
                    evidence.id)
            }
            val ended = parse(intention.optString("endedAt"))
            if (name != "intention" && ended != null && !ended.isAfter(now)) {
                items += CharacterLifeVisible(ended, "行动", "本人决定放下愿望",
                    intention.optString("aim").take(160) + "\n" +
                        intention.optString("releaseReason").ifBlank { "未保存缘由" })
            }
        }
        CharacterLifeFollowThroughStore.auditSnapshot(role).forEach { experience ->
            val t = parse(experience.optString("actedAt")) ?:
                parse(experience.optString("occurredAt")) ?: return@forEach
            if (t.isAfter(now)) return@forEach
            val shareId = experience.optString("shareReceiptId")
            val exploreId = experience.optString("curiosityReceiptId")
            val shared = experience.optString("shareStatus") == "shared" && source(role, shareId) != null
            val explored = experience.optString("curiosityStatus") == "explored" && source(role, exploreId) != null
            val shareStatus = when {
                shared -> "确实已经分享"
                experience.optString("shareStatus") == "held" -> "想分享但还没有发"
                experience.optString("shareStatus") == "released" -> "自己放弃分享"
                else -> "未确认分享"
            }
            val curiosityStatus = if (explored) "有真实探索回执"
                else if (experience.optString("curiosityStatus") == "released") "已放下"
                else "尚未有探索回执"
            val trace = buildString {
                append("原始经历：" + experience.optString("fact").take(180))
                append("\n分享：" + shareStatus)
                append("\n探索：" + curiosityStatus)
                experience.optString("shareWhy").takeIf(String::isNotBlank)?.let {
                    append("\n当时想法：" + it.take(140))
                }
                if (shared) append("\n分享回执：" + shareId)
                if (explored) append("\n探索回执：" + exploreId)
            }
            items += CharacterLifeVisible(t, "探索", "真实经历的后续变化",
                trace, experience.optString("id"))
        }
        val curiosity = CharacterCuriosityRuntime.snapshot(role)
        jsonEntries(curiosity.optJSONArray("questions")).forEach { q ->
            val evidence = source(role, q.optString("observedId")) ?: return@forEach
            val t = parse(q.optString("at")) ?: evidence.occurredAt
            if (t.isAfter(now)) return@forEach
            items += CharacterLifeVisible(t, "探索", "产生疑问 · 尚未行动",
                "想知道：" + q.optString("question").take(170) +
                    "\n事实触发：" + q.optString("observed").take(170), evidence.id)
        }
        jsonEntries(curiosity.optJSONArray("threads")).forEach { thread ->
            jsonEntries(thread.optJSONArray("steps")).forEach { step ->
                val event = CharacterActionEvidence.resolve(role, step.optString("evidenceId"))
                    ?.takeIf { CharacterActionEvidence.supports(it, step.optString("action")) }
                    ?: return@forEach
                val t = parse(step.optString("at")) ?: event.occurredAt
                if (t.isAfter(now)) return@forEach
                items += CharacterLifeVisible(t, "探索", "真实尝试探索",
                    "主题：" + thread.optString("topic").take(95) +
                        "\n具体行动：" + step.optString("action").take(65) +
                        "\n结果：" + step.optString("receipt").take(210), event.id)
            }
        }
        return items.sortedWith(compareByDescending<CharacterLifeVisible> { it.at }.thenBy { it.title }).take(150)
    }
}
