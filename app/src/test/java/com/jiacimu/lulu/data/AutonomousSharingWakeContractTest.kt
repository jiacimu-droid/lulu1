package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Share urge supplies a role decision opportunity, never an automatic send. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousSharingWakeContractTest {
    @Test fun verifiedExperienceCanBeReviewedAfterOnlineLeaseExpires() {
        val data = ProactivePerceptionScheduler.lifeFollowThroughInput("star-he")
        assertEquals("star-he", data.getString("characterId"))
        assertTrue(data.getString("trigger").orEmpty().startsWith("真实生活后续回想"))
        assertTrue(data.getBoolean("force", false))
        assertTrue(data.getBoolean("preserveOffline", false))
        assertFalse(shouldDeferSleepingLifeReview(
            sleeping = true, deferredReview = true, onlineExpiryAtMillis = 0L,
            explicitUserWake = false, preserveOffline = data.getBoolean("preserveOffline", false),
        ))
    }

    @Test fun plainLowPriorityReviewsStillRespectCharacterSleep() {
        assertTrue(shouldDeferSleepingLifeReview(
            sleeping = true, deferredReview = true, onlineExpiryAtMillis = 0L,
            explicitUserWake = false, preserveOffline = false,
        ))
        assertFalse(shouldDeferSleepingLifeReview(
            sleeping = false, deferredReview = true, onlineExpiryAtMillis = 0L,
            explicitUserWake = false, preserveOffline = false,
        ))
        assertFalse(shouldDeferSleepingLifeReview(
            sleeping = true, deferredReview = false, onlineExpiryAtMillis = 0L,
            explicitUserWake = false, preserveOffline = false,
        ))
    }
}
