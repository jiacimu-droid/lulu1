package com.jiacimu.lulu

/** A quiet call still has a living participant; a pulse is not a demand to speak. */
internal object CallSilencePolicy {
    /** Finalize a provider capture that never returned a VAD end event.
     * Do not assume it was deliberate silence; flush the PCM for transcription.
     * 90 seconds preserves ordinary long-form speaking without hard truncation.
     */
    fun shouldRecoverStuckCapture(
        providerActive: Boolean,
        userSpeechInProgress: Boolean,
        sinceSpeechStartedMillis: Long,
    ): Boolean = providerActive && userSpeechInProgress && sinceSpeechStartedMillis >= 90_000L

    /** Distinct observations only when the silence meaningfully extends. */
    fun observationMilestone(silenceMillis: Long): String = when {
        silenceMillis < 5 * 60_000L -> "one-minute"
        silenceMillis < 15 * 60_000L -> "five-minutes"
        silenceMillis < 30 * 60_000L -> "fifteen-minutes"
        silenceMillis < 60 * 60_000L -> "half-hour"
        else -> "hour-${silenceMillis / (60 * 60_000L)}"
    }

    fun shouldReflect(
        connected: Boolean, sleepMode: Boolean, busy: Boolean,
        userSpeaking: Boolean, sinceUserActivityMillis: Long,
        sinceAudioMillis: Long, sinceReflectionMillis: Long, failures: Int,
    ): Boolean {
        if (!connected || sleepMode || busy || userSpeaking) return false
        val interval = when {
            failures >= 3 -> 10 * 60_000L
            sinceUserActivityMillis >= 10 * 60_000L -> 5 * 60_000L
            else -> 2 * 60_000L
        }
        return sinceUserActivityMillis >= 60_000L && sinceAudioMillis >= 45_000L &&
            sinceReflectionMillis >= interval
    }

    fun context(silenceMillis: Long, muted: Boolean, responsibilityContext: String = ""): String = """
        【本通电话的实际状态】电话仍然接通；用户没有新增发言。这是状态观察，不是用户消息。
        距离最近一次用户语音活动约 ${silenceMillis.coerceAtLeast(0) / 1000} 秒；麦克风${if (muted) "已静音，无法据此判断用户醒睡或是否在场" else "未静音，目前没有新识别内容"}。
        没有新语音只证明没有新识别内容，不证明用户已睡、已醒、离开、生气、需要安静或允许挂断。
        $responsibilityContext
        沿用本人真实人设、关系、最近对话与未完成目标，自行理解并决定本轮行动；系统没有预设你的情绪、动机或用户意愿。
        输出沿用 reply 或 silent 与 steps 的结构：说话和工具可同时选择；silent 只表示本轮没有语音输出，不表示原目标完成或取消。
        已授权的通话内音乐、音量、短铃声均沿原工具执行并记录真实回执。思想不冒充行动，行动不冒充目标结果；不得编造用户回答、呼吸或完成证据。
    """.trimIndent()
}
