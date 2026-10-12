package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.Duration

class LearningCyclePolicyTest {
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    @Test fun failedAttemptsBackOffAndHaveAnUpperBound() {
        assertEquals(now.plusSeconds(900), LearningCyclePolicy.retryAt(now, 0))
        assertEquals(now.plusSeconds(1800), LearningCyclePolicy.retryAt(now, 1))
        assertEquals(now.plusSeconds(28_800), LearningCyclePolicy.retryAt(now, 200))
        assertFalse(LearningCyclePolicy.available("failed", now.plusSeconds(1), now))
        assertTrue(LearningCyclePolicy.available("failed", now, now))
    }
    @Test fun pauseDropAndInFlightWorkNeverAutoResume() {
        for (state in listOf("paused", "dropped", "running"))
            assertFalse(LearningCyclePolicy.available(state, null, now))
    }
    @Test fun reviewsBecomeFurtherApartWithoutAnyMasteryClaim() {
        assertEquals(now.plus(Duration.ofDays(1)), LearningCyclePolicy.reviewAt(now, 0))
        assertEquals(now.plus(Duration.ofDays(3)), LearningCyclePolicy.reviewAt(now, 1))
        assertEquals(now.plus(Duration.ofDays(14)), LearningCyclePolicy.reviewAt(now, 99))
        assertFalse(LearningCyclePolicy.stale(now.minusSeconds(60), now))
        assertTrue(LearningCyclePolicy.stale(now.minusSeconds(601), now))
    }
    @Test fun publicMaterialsRejectCredentialsAndLocalLiteralAddresses() {
        for (url in listOf("http://example.com", "https://localhost/a", "https://192.168.1.2/a",
            "https://user:pass@example.com/a", "https://example.local/a", "https://example.com:8080/a")) {
            assertTrue(url, runCatching { LearningMaterialSource.validateUrl(url) }.isFailure)
        }
        assertEquals("example.com", LearningMaterialSource.validateUrl("https://example.com/article").host)
    }
}
