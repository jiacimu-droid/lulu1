package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * A nearby AlarmManager-backed promise already has a durable wake event.
 * Do not wake the same role for generic time-driven awareness while waiting.
 * Fresh user messages, world events and explicitly requested checks bypass this.
 */
internal object GoalDeadlinePerceptionPolicy {
    fun deferPassiveCheck(tasks: List<CommitmentTask>, now: Instant): Boolean =
        tasks.any { task ->
            val deadline = if (task.status == CommitmentTaskStatus.Scheduled)
                task.dueAt else if (task.status == CommitmentTaskStatus.WaitingForFeedback)
                task.nextCheckAt else null
            deadline != null && task.linkedAlarmId != null &&
                Duration.between(now, deadline).seconds in 1L..(30L * 60L)
        }
}
