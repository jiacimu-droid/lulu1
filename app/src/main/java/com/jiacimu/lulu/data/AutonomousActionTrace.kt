package com.jiacimu.lulu.data

import org.json.JSONArray
import java.time.Duration
import java.time.Instant

/**
 * Bounded detail in the existing inner-life decision history. No new scheduler, quotas
 * or extra surveillance. Signatures disambiguate two actions in the same broad category.
 */
internal object AutonomousActionTrace {
    /**
     * The common timeline records private character bubbles with speaker="角色",
     * while moments and world actions may use the character's display name.
     * Never mistake a freshly received user statement for a successful action.
     * The caller independently requires a successful executor receipt.
     */
    fun newestExecutedEvidence(
        events: List<SharedTimelineEvent>,
        previousIds: Set<String>,
        displayName: String,
        conversationId: String? = null,
    ): String = events.asReversed().firstOrNull { event ->
        event.id !in previousIds && event.channel != "在线感知" && (
            event.evidenceKind == EventEvidenceKind.ToolResult ||
                (event.evidenceKind == EventEvidenceKind.CharacterStatement &&
                    (event.speaker == displayName || event.speaker == "角色" ||
                        (conversationId != null && event.sessionId == conversationId)))
            )
    }?.id.orEmpty()

    fun signature(
        action: String,
        worldAction: String = "",
        destination: String = "",
        itemId: String = "",
        activityId: String = "",
        readingBookId: String = "",
        gameId: String = "",
        groupId: String = "",
        tool: String = "",
        text: String = "",
    ): String {
        val normalized = action.trim().lowercase()
        fun clean(v: String) = v.trim().lowercase().replace(Regex("\\s+"), " ").take(80)
        return when (normalized) {
            "digital_world" -> listOf(normalized, worldAction, destination, itemId, activityId)
                .map(::clean).filter(String::isNotBlank).joinToString("/")
            "reading" -> "reading/" + clean(readingBookId)
            "solo_game", "game_invite" -> normalized + "/" + clean(gameId)
            "group_message" -> normalized + "/" + clean(groupId)
            "tool" -> "tool/" + clean(tool)
            "moment", "journal", "message" -> normalized + "/" +
                clean(text).take(28)
            else -> normalized
        }.take(180)
    }

    fun render(decisions: JSONArray?, now: Instant = Instant.now()): String {
        val records = decisions ?: return ""
        val recent = (0 until records.length()).mapNotNull(records::optJSONObject).filter { entry ->
            val time = runCatching { Instant.parse(entry.optString("at")) }.getOrNull()
            time != null && !time.isAfter(now) && Duration.between(time, now) <= Duration.ofHours(48)
        }.takeLast(8)
        val completed = recent.filter { it.optBoolean("succeeded") &&
            it.optString("selected") != "silent" && it.optString("signature").isNotBlank() }
        val last = completed.takeLast(5)
        val repeated = last.takeLast(3).takeIf { it.size >= 2 }
            ?.let { items -> items.groupBy { it.optString("signature") }.values
                .firstOrNull { it.size >= 2 } }?.firstOrNull()
        val failures = recent.filter { !it.optBoolean("succeeded") &&
            it.optString("selected") != "silent" }.takeLast(2)
        // An all-silent character previously received no feedback at all,
        // leaving the next autonomous decision blind to its own inactivity.
        val silenceStreak = recent.asReversed().takeWhile {
            it.optString("selected").equals("silent", ignoreCase = true)
        }.size
        if (last.isEmpty() && failures.isEmpty() && silenceStreak < 2) return ""
        return buildString {
            appendLine("【自己的近期行动过程｜真实执行回执，而非任务配额】")
            last.takeLast(3).forEach {
                appendLine("- 做过 " + it.optString("signature").take(130) +
                    "；结果：" + it.optString("outcome").take(115))
            }
            if (silenceStreak >= 2) {
                appendLine("- 最近${silenceStreak}次自主感知连续选择了安静，尚无真实执行回执。")
                appendLine("  安静可以是符合本人的选择，但别因为没有新消息就停止自己的生活。")
                appendLine("  重新看看已有愿望、真实书籍、可去的地点、眼前居民、日记和分享欲。")
                appendLine("  如果此刻真想做且执行器有条件，就直接选择具体 action；")
                appendLine("  如果仍想独处，请给出属于本人的具体理由，而不是套用等待用户的说法。")
            }
            failures.forEach {
                appendLine("- 上次未成功：" + it.optString("signature").ifBlank {
                    it.optString("selected")
                }.take(120) + "；原因：" + it.optString("outcome").take(110))
            }
            if (repeated != null) appendLine(
                "同一具体动作近期出现过多次：" + repeated.optString("signature").take(120) +
                    "。若这是有真实进展的持续爱好可以继续；若只是原地重复，没有新的缘由就考虑别的生活选择。"
            )
        }.trim()
    }
}
