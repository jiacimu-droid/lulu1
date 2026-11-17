package com.jiacimu.lulu.data

import java.time.Instant

/**
 * Transport safety limit, not a completion/urgency deadline. Android may cold-start
 * a process or defer an AlarmManager/WorkManager callback beyond two minutes.
 * A previously AUTHORIZED action can still run within this bounded delivery
 * grace. Beyond it, the same role must reconsider the original obligation
 * from a real missed-delivery receipt; the system must never dial hours later
 * automatically or mark the goal completed.
 */
internal object CommitmentAlarmTiming {
    const val DELIVERY_WINDOW_SECONDS = 20L * 60L

    fun expired(at: Instant, now: Instant): Boolean =
        now.isAfter(at.plusSeconds(DELIVERY_WINDOW_SECONDS))

    fun due(at: Instant, now: Instant): Boolean = !at.isAfter(now) && !expired(at, now)
}
