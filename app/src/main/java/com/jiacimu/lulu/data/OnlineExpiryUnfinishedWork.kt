package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import org.json.JSONObject

/**
 * Read-only expiry attention over the existing authoritative ToolRouter
 * timeline. An unfinished tool receipt is an objective fact; it does not
 * create a second promise/task queue or decide whether the role stays awake.
 */
internal object OnlineExpiryUnfinishedWork {
    private val actionable = setOf(
        "background_phone", "phone_ui", "read_xiaohongshu",
        "web_search", "image_search", "read_book", "learn_topic",
    )
    private val unfinished = setOf("failed", "unverified", "waiting_user", "pending")

    fun render(events: List<SharedTimelineEvent>, now: Instant): String {
        val recent = events.asSequence()
            .filter { it.source == "tool-router" &&
                it.evidenceKind == EventEvidenceKind.ToolResult &&
                !it.occurredAt.isAfter(now) &&
                Duration.between(it.occurredAt, now) <= Duration.ofMinutes(20) }
            .sortedByDescending { it.occurredAt }
            .mapNotNull { event ->
                val attempt = runCatching { JSONObject(event.content) }.getOrNull()
                    ?: return@mapNotNull null
                val tool = attempt.optString("tool")
                if (tool !in actionable) return@mapNotNull null
                val result = attempt.optString("result")
                    .takeIf(String::isNotBlank)?.let {
                        runCatching { JSONObject(it) }.getOrNull()
                    }
                Triple(tool, attempt, result)
            }.distinctBy { it.first }
            // A later success for the same tool removes the earlier failure
            // from this short attention cue. Success still does not certify
            // that the whole user goal has been achieved.
            .filter { (_, attempt, result) ->
                attempt.optString("status") in unfinished ||
                    result?.optString("status") in unfinished
            }.take(3).toList()
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("【在线窗口结束前｜本人近20分钟实际未完成的设备/探索动作】")
            appendLine("下列是原 ToolRouter 的真实失败/未验证回执，不是主人新命令，也不表示整个目标一定还值得坚持。请先对照自己刚说出口准备做什么，以及是否已经有后续成功证据。")
            recent.forEach { (tool, attempt, result) ->
                val explanation = result?.optString("error").orEmpty().ifBlank {
                    result?.optString("verification").orEmpty().ifBlank {
                        result?.optString("summary").orEmpty()
                    }
                }
                appendLine("tool=$tool；requestId=${attempt.optString("requestId").take(110)}；" +
                    "状态=${attempt.optString("status")}；真实阻碍=${explanation.take(250)}")
            }
            appendLine("若本人仍想继续，可以本轮实际选择安全的工具动作；若现在不适合，可亲自说明失败并登记真实后续，或决定放下。只说‘我去看看’不等于工具执行；到点下线不自动清除这些事实。是否续醒、联系、改方法或休眠仍由本人选择。")
        }.trim()
    }
}
