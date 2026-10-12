package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/** Attempts and review spacing, never a score of intelligence or mastery. */
internal object LearningCyclePolicy {
    fun retryAt(now: Instant, failures: Int): Instant =
        now.plusSeconds((900L * (1L shl failures.coerceIn(0, 5))).coerceAtMost(28_800L))

    fun reviewAt(now: Instant, completedReviews: Int): Instant = now.plus(
        Duration.ofDays(listOf(1L, 3L, 7L, 14L)[completedReviews.coerceIn(0, 3)]))

    fun available(status: String, retryAt: Instant?, now: Instant): Boolean =
        status !in setOf("paused", "dropped", "running") &&
            (retryAt == null || !retryAt.isAfter(now))

    fun stale(started: Instant?, now: Instant): Boolean =
        started == null || Duration.between(started, now) > Duration.ofMinutes(10)
}
