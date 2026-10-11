package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * A generic introduction ("get to know what kind of person she is") is a
 * relationship orientation, not an endlessly pending actionable goal.
 * Keep concrete, evolving motives and real executor receipts untouched.
 */
internal object CharacterStaleGoalPolicy {
    private val genericOrientation = Regex(
        "(?:了解|弄清楚|认识|搞清楚).{0,22}(?:我|你|她|他|用户|对方).{0,22}(?:什么样|怎么样|怎样|啥样|是哪种|是什么样)"
    )

    fun isGenericOrientation(aim: String): Boolean =
        genericOrientation.containsMatchIn(aim.replace(Regex("\\s+"), ""))

    fun shouldRetire(
        aim: String, createdAt: String, updatedAt: String,
        outcomeCount: Int, now: Instant = Instant.now(),
    ): Boolean {
        if (!isGenericOrientation(aim) || outcomeCount > 0) return false
        val first = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return false
        val last = runCatching { Instant.parse(updatedAt.ifBlank { createdAt }) }.getOrNull() ?: return false
        return !first.isAfter(now) && !last.isAfter(now) &&
            Duration.between(first, now) >= Duration.ofDays(21) &&
            Duration.between(last, now) >= Duration.ofDays(21)
    }
}
