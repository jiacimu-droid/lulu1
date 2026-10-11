package com.jiacimu.lulu

/**
 * A narrow fallback for a companion who has *explicitly committed* to ending
 * this call in her own final spoken words but omitted the structured endCall flag.
 *
 * This does not treat ordinary farewells, questions, hypothetical discussion,
 * requests for the USER to hang up, or unfinished intentions as call-control.
 * A positive match still requires successful audible playback and a live
 * matching call/session generation before the call may actually end.
 */
internal object CallSpokenHangupPolicy {
    private val separators = Regex("[。！？!?；;\\n]")
    private val before = Regex("^(?:嗯嗯|嗯好|好的|好啦|好吧|好|行吧|行|那么|那好|那行|那|宝宝|宝贝|亲爱的|乖|嗯|唔)?[，,、\\s]*")
    private val direct = Regex(
        "^(?:那|就)?(?:我|我这边|我这里)(?:就|得|要|还是|先|现在|这就|这边|暂时|先把|把|先去|去|就去|这就去|马上去|马上)?(?:先|就|现在|这就|把)?(?:电话)?(?:挂断电话|挂电话|挂断|挂)(?:了|啦|咯|喽|啊|哈|吧|哦|噢|掉|掉了)?(?:[，,、\\s]*(?:宝宝晚安|宝贝晚安|晚安|拜拜|再见|明天见|回头聊|先这样|么么|亲亲|乖乖睡|你好好休息|我走啦|爱你))*[。！!，,\\s]*$"
    )
    private val alternative = Regex(
        "^(?:那|好|行|嗯)?[，,\\s]*(?:我|我这边|我这里)(?:先|现在|就|得|要|还是|去|就去|马上|马上去)*(?:把)?(?:电话|通话)(?:先)?挂(?:了|啦|咯|喽|断|掉|掉了)(?:[，,\\s]*(?:拜拜|晚安|再见|回头聊))*$"
    )
    private val prohibited = listOf(
        "别挂", "不要挂", "不挂", "没挂", "挂不", "不想挂", "想挂", "可以挂",
        "要不要", "你挂", "你先", "让你挂", "让她挂", "让他挂", "如果", "假如",
        "比如", "假设", "说过", "说了", "说的是", "说那", "他说", "她说",
        "别说", "不是真的", "不能", "不会", "可能", "大概", "以后", "等会",
    )

    fun explicitlyEndsOwnCall(spoken: String): Boolean {
        val clean = spoken.replace(Regex("\\[[^\\]]{1,42}\\]"), "").trim()
        if (clean.isBlank()) return false
        // Only the ending of the reply is actionable. A quote in the middle
        // of a lengthy discussion cannot end a call.
        val clauses = clean.split(separators).map(String::trim).filter(String::isNotBlank)
        val last = clauses.lastOrNull() ?: return false
        val candidates = listOf(last, clauses.takeLast(2).joinToString("，"))
        return candidates.any { candidate ->
            if (candidate.length > 62 || candidate.any { it in "“”「」『』\"‘’" }) return@any false
            val meaningful = before.replace(candidate.trim(), "").trim()
            if (prohibited.any(meaningful::contains)) return@any false
            direct.matches(meaningful) || alternative.matches(meaningful)
        }
    }
}
