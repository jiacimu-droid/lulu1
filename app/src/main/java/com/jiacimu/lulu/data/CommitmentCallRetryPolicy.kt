package com.jiacimu.lulu.data

/** Backoff is bounded: no continuous dialing and no infinite automatic calls. */
internal object CommitmentCallRetryPolicy {
    const val MAX_WAKE_CALL_ATTEMPTS = 3

    fun isFinalCheck(task: CommitmentTask, attempt: Int, callsEnabled: Boolean): Boolean =
        when {
            task.isWakeResponsibility() && (task.deliveryAction == "start_call" || callsEnabled) ->
                attempt > MAX_WAKE_CALL_ATTEMPTS
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
    return listOf("叫醒", "叫我", "喊我", "起床", "醒来", "wake", "睡醒").any(text::contains)
}
