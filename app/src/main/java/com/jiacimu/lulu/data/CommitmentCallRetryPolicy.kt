package com.jiacimu.lulu.data

/** Backoff is bounded: no continuous dialing and no infinite automatic calls. */
internal object CommitmentCallRetryPolicy {
    const val MAX_WAKE_CALL_ATTEMPTS = 3

    /**
     * Live call: the call itself remains open. Revisit the unconfirmed wake
     * goal after five minutes, with at most three audible attempts. The final
     * short callback records a still-unconfirmed outcome without more ringing.
     */
    fun nextInCallWakeReviewSeconds(attempt: Int): Long? = when (attempt) {
        1, 2 -> 5L * 60L
        3 -> 150L
        else -> null
    }


    fun isFinalCheck(task: CommitmentTask, attempt: Int, callsEnabled: Boolean): Boolean =
        when {
            task.isWakeResponsibility() -> attempt > MAX_WAKE_CALL_ATTEMPTS
            task.deliveryAction == "start_call" -> attempt > 1
            else -> false
        }

    /** Last callback is observation-only; it must never dial again. */
    fun nextDelaySeconds(task: CommitmentTask, attempt: Int, callsEnabled: Boolean): Long? = when {
        task.isWakeResponsibility() && (task.deliveryAction == "start_call" || callsEnabled) ->
            when (attempt) {
                1 -> if (task.deliveryAction == "start_call") 5L * 60 else 10L * 60
                2 -> 8L * 60
                3 -> 150L
                else -> null
            }
        task.isWakeResponsibility() && !callsEnabled ->
            if (attempt == 1) 10L * 60 else null
        task.deliveryAction == "start_call" ->
            if (attempt == 1) 150L else null
        else -> null
    }
}

internal fun CommitmentTask.isWakeResponsibility(): Boolean {
    val text = "$goal $completionCondition".lowercase()
    // Asking someone to GO to sleep is not a request to WAKE them up.
    // Misclassifying "每天叫我睡觉" caused bedtime calls to retry as wake alarms.
    val explicitlyWake = listOf("叫醒", "喊醒", "起床", "醒来", "wake", "睡醒").any(text::contains)
    // "叫我学习 / 喊我回来 / 叫我睡觉" are ordinary reminders,
    // not evidence that the owner is asleep. Never add wake-up retries.
    return explicitlyWake
}
