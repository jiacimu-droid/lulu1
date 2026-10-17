package com.jiacimu.lulu.data

import java.time.Instant

/** A small delivery window is not permission to replay an old wake-up on next app launch. */
internal object CommitmentAlarmTiming {
    const val DELIVERY_WINDOW_SECONDS = 120L

    fun expired(at: Instant, now: Instant): Boolean =
        now.isAfter(at.plusSeconds(DELIVERY_WINDOW_SECONDS))

    fun due(at: Instant, now: Instant): Boolean = !at.isAfter(now) && !expired(at, now)
}
