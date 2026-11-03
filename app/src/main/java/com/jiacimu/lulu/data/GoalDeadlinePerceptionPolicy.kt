package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * An imminent AlarmManager-backed promise owns the current action slot.
 * Do not make a redundant passive model call while the commitment is pending.
 * However, excluding a character from *all* wake planning would strand its
 * autonomous life until an unrelated watchdog, sometimes hours later.
 */
internal object GoalDeadlinePerceptionPolicy {
    private fun protectedDeadlines(tasks: List<CommitmentTask>, now: Instant): List<Instant> =
        tasks.mapNotNull { task ->
            val deadline = when (task.status) {
                CommitmentTaskStatus.Scheduled -> task.dueAt
                CommitmentTaskStatus.WaitingForFeedback -> task.nextCheckAt
                else -> null
            }
            deadline?.takeIf { task.linkedAlarmId != null &&
                Duration.between(now, it).seconds in 1L..(30L * 60L) }
        }

    /**
     * A due alarm replaces a duplicate generic timer sweep, not a NEW
     * independently witnessed interaction. Otherwise a review planned 30
     * minutes away can suppress fresh owner input or verified world/device
     * evidence until after that review; the appointment becomes an accidental
     * "ignore everything until then" lock.
     *
     * This only authorizes the normal character decision path to run. It never
     * chooses a message, overrides quiet-hours permissions or reschedules a task.
     */
    fun deferPassiveCheck(tasks: List<CommitmentTask>, now: Instant,
                          hasIndependentEvidence: Boolean = false): Boolean =
        !hasIndependentEvidence && protectedDeadlines(tasks, now).isNotEmpty()

    /** Keep a *durable* background resume time after the last protected alarm
     * instead of dropping this character from nextDueAt altogether.
     * A one-minute buffer lets the actual promise worker own its due event. */
    fun passiveResumeAt(tasks: List<CommitmentTask>, now: Instant): Instant? =
        protectedDeadlines(tasks, now).maxOrNull()?.plusSeconds(60)
}
