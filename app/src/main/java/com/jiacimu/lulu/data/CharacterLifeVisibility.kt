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
        else -> "考虑中"
    }
    private fun jsonEntries(array: org.json.JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    /** Avatar is current inner life, never the entire historic audit. */
    fun current(role: String, now: Instant = Instant.now()): List<CharacterLifeVisible> = buildList {
        // 此刻 is a snapshot of active mental changes, not a task inbox.
        // Persisted goals remain available to the planner and the read-only
        // 经历 archive; do not duplicate a stale "想继续做" on every avatar open.
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
        // They remain in the evidence-backed archive and may be reconsidered
        // through the shared decision runtime without being pinned to 此刻.
    }

}
