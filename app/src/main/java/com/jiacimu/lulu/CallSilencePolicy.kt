package com.jiacimu.lulu

/** A quiet call still has a living participant; a pulse is not a demand to speak. */
internal object CallSilencePolicy {
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

    fun context(silenceMillis: Long, muted: Boolean): String = """
        这是仍然接通的一对一电话中的安静陪伴时刻，不是用户消息；用户没有新增发言。
        距离最近一次用户语音活动约 ${silenceMillis.coerceAtLeast(0) / 1000} 秒；麦克风${if (muted) "已静音，听不到用户不等于用户不在" else "未静音，没有新识别内容不代表知道用户正在做什么"}。
        结合上次实际交谈、已授权的学习/设备状态、自己的持续情绪和数字生活，形成此刻想法，再判断要不要说出口。
        用户明确在学习、忙碌或希望安静时，尊重这种陪伴；可继续自己的已知活动、消化感受或安静待着，不反复问还在不在、催答、重说开场或定时表演关心。
        仅仅安静不能推断用户睡着、离开、生气、不在乎你或允许挂断。需要沟通、有新的想法或真的想结束电话时，可以按人设自然表达。
        可选择 silent：不发声，但可更新心声、状态与持续感受；想法不必每轮都有变化，不能为了刷新而编造用户动作、听见的声音、新事件或情绪。
        只选择 reply 或 silent。本次观察不执行手机工具、另开电话或向其他会话发送内容。
    """.trimIndent()
}
