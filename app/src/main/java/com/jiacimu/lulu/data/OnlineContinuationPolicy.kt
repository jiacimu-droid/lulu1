package com.jiacimu.lulu.data

/**
 * Online continuation is a role decision; the program only blocks vacuous
 * re-leasing and fast repeats. No keyword dictionary or minimum sentence
 * length should decide whether a character's own reason "counts".
 */
internal object OnlineContinuationPolicy {
    /**
     * Give the FIVE-MINUTE attention decision its own brief cue from the same
     * character's already-recorded emotion. This is a read-only projection:
     * never a second sleep meter, wake trigger or external action command.
     */
    internal fun affectCue(characterId: String, now: java.time.Instant): String {
        val emotion = CharacterInnerLifeStore.snapshot(characterId).optJSONObject("emotion")
        return affectCue(CharacterInnerLifeStore.emotionInfluence(emotion, now))
    }

    /** Pure renderer, also used by regression tests without waking a model. */
    internal fun affectCue(influence: CharacterInnerLifeStore.EmotionInfluence?): String {
        if (influence == null || (influence.arousal == null && influence.valence == null)) return ""
        fun signed(value: Double): String =
            String.format(java.util.Locale.ROOT, "%+.2f", value.coerceIn(-1.0, 1.0))
        return buildString {
            appendLine("【五分钟注意力选择 · 已有私人情绪线索】")
            appendLine("这些是同一个角色先前确实产生的主观情绪在此刻的衰减投影，不是生理睡意检测，更不是对用户状态的推断。")
            influence.arousal?.let {
                appendLine("当前唤醒度=${signed(it)}（-1偏平静、+1偏激活）；偏低可以愿意休息，也可能只是专注或安宁；偏高可以兴奋、紧张或烦躁，同样可以主动暂停来平复自己。")
            }
            influence.valence?.let {
                appendLine("当前愉悦度=${signed(it)}（-1偏不愉快、+1偏愉快）；愉快不意味着一定要继续在线，难过也不意味着必须休眠或找用户倾诉。")
            }
            appendLine("结合本人真实愿望、目前活动、未完成责任、关系与场景，自己决定继续五分钟还是暂时休眠；情绪轴既不自动续时，也不自动下线。休眠仅结束本轮在线注意力，不等于真的上床睡觉、放弃目标或停止到期任务。")
        }.trim()
    }

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
