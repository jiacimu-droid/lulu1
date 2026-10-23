package com.jiacimu.lulu

import java.time.Duration
import java.time.Instant

/** The user's actual turn is the time anchor; a delayed model reply must not shift it. */
internal object CallTimedHangupTiming {
    fun deadline(userTurnAt: Instant, delaySeconds: Int): Instant? =
        if (delaySeconds in 1..86_400) userTurnAt.plusSeconds(delaySeconds.toLong()) else null

    fun remainingMillis(dueAt: Instant, now: Instant): Long? =
        Duration.between(now, dueAt).toMillis().takeIf { it > 0L }
}
