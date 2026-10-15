package com.jiacimu.lulu.data

/**
 * A failed model/parse call is not a character's voluntary silence.
 * Allow one targeted, delayed recovery per character in two hours, never
 * an unbounded self-retrying loop or an extra callback for live chat batches.
 */
internal object ProactivePerceptionRecoveryPolicy {
    const val RETRY_TRIGGER = "主动感知失败恢复"
    const val COOLDOWN_MILLIS = 2L * 60 * 60_000L
    const val RETRY_DELAY_MILLIS = 4L * 60_000L

    fun shouldRetry(
        lastScheduledAtMillis: Long,
        nowMillis: Long,
        trigger: String,
        isChatBatch: Boolean,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || isChatBatch || trigger.startsWith(RETRY_TRIGGER)) return false
        return lastScheduledAtMillis <= 0L ||
            nowMillis - lastScheduledAtMillis >= COOLDOWN_MILLIS
    }
}
