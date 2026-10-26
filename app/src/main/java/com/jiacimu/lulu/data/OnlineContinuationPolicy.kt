package com.jiacimu.lulu.data

/**
 * The role owns its online decision. Missing fields are contract errors, not
 * semantic permission for the executor to change continue into sleep.
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

    /** Output-shape validation only; the model owns every subjective reason. */
    fun contractIssue(choice: String, reason: String): String? = when {
        choice !in setOf("continue", "sleep") -> "在线决定缺失或 choice 无效"
        reason.isBlank() -> "onlineDecision.reason 必填但为空"
        else -> null
    }

    fun permits(
        choice: String,
        reason: String,
        hasChosenAction: Boolean,
        hasOpenIntention: Boolean,
        previousReason: String,
        minutesSincePrevious: Long,
    ): Boolean = choice == "continue" && contractIssue(choice, reason) == null
}
