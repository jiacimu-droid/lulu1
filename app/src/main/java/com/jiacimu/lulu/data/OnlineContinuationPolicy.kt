package com.jiacimu.lulu.data

/**
 * Online continuation is a role decision; the program only blocks vacuous
 * re-leasing and fast repeats. No keyword dictionary or minimum sentence
 * length should decide whether a character's own reason "counts".
 */
internal object OnlineContinuationPolicy {
    private val generic = setOf(
        "继续在线", "保持在线", "继续醒着", "再待一会", "再呆一会",
        "等她回来", "等她发消息", "等主人回来", "再看看", "随便逛",
        "没什么事", "暂时没事", "保持存在感", "还不想休眠",
        "想继续在线", "我想继续在线", "继续在线等她发消息",
        "我想继续在线等她发消息", "还是随便逛逛保持在线",
    )
    private fun canonical(reason: String): String =
        reason.replace(Regex("""[\s\p{P}\p{S}]+"""), "").trim().lowercase()

    fun permits(
        choice: String,
        reason: String,
        hasChosenAction: Boolean,
        hasOpenIntention: Boolean,
        previousReason: String,
        minutesSincePrevious: Long,
    ): Boolean {
        if (choice != "continue") return false
        val current = canonical(reason)
        if (current.isBlank() || current in generic) return false
        // A character can continue to think, rest or linger without an
        // external tool action. The model decides that; the five-minute OS
        // lease, de-duplication and power policy still limit resources.
        val repeated = current == canonical(previousReason)
        if (repeated && minutesSincePrevious in 0..20 && !hasChosenAction) return false
        return true
    }
}
