package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * A five-minute lease is not an infinite background timer.
 * This verifies that the model chose a specific reason rather than treating
 * merely remaining online as a goal. It never forces another model call.
 */
internal object OnlineContinuationPolicy {
    private val emptyReasons = listOf(
        "继续在线", "保持在线", "继续醒着", "再待一会", "再呆一会",
        "等她回来", "等她发消息", "等主人回来", "再看看", "随便逛",
        "没什么事", "暂时没事", "无聊就", "保持存在感", "还不想休眠",
    )
    private val genuineMindReason = Regex(
        "想清楚|还在想|挂心|担心|顾虑|好奇|计划|准备|想读|想写|正在读|继续探索|接着做|要处理|要完成|还没完成|期待|想说|想找|研究|想弄明白"
    )

    fun permits(
        choice: String,
        reason: String,
        hasChosenAction: Boolean,
        hasOpenIntention: Boolean,
        previousReason: String,
        minutesSincePrevious: Long,
    ): Boolean {
        if (choice != "continue") return false
        val clean = reason.replace(Regex("\\s+"), "").trim()
        if (clean.length < 9) return false
        val vague = emptyReasons.any { phrase -> clean.contains(phrase) } &&
            !genuineMindReason.containsMatchIn(clean)
        if (vague) return false
        // A prolonged quiet online spell must have a real, identifiable
        // private thought or still-open personal purpose to support it.
        if (!hasChosenAction && !hasOpenIntention && !genuineMindReason.containsMatchIn(clean))
            return false
        val sameAsLast = clean == previousReason.replace(Regex("\\s+"), "").trim()
        if (sameAsLast && minutesSincePrevious in 0..20 && !hasChosenAction) return false
        return true
    }
}
