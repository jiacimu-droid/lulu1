package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CharacterStaleGoalPolicyTest {
    private val now = Instant.parse("2026-10-11T08:00:00Z")

    @Test fun theUnchangingGenericBirthGoalIsNotAnEternalPendingTask() {
        assertTrue(CharacterStaleGoalPolicy.isGenericOrientation("弄清楚我是怎么样一个人"))
        assertTrue(CharacterStaleGoalPolicy.isGenericOrientation("想了解你是什么样的人"))
        assertTrue(CharacterStaleGoalPolicy.shouldRetire(
            "弄清楚我是怎么样一个人", "2026-08-01T00:00:00Z", "", 0, now))
    }

    @Test fun newSpecificGoalsAndActualOutcomesAreNeverAutomaticallyDiscarded() {
        assertFalse(CharacterStaleGoalPolicy.shouldRetire(
            "下周读完已经借的书", "2026-08-01T00:00:00Z", "", 0, now))
        assertFalse(CharacterStaleGoalPolicy.shouldRetire(
            "弄清楚我是怎么样一个人", "2026-08-01T00:00:00Z", "", 1, now))
        assertFalse(CharacterStaleGoalPolicy.shouldRetire(
            "弄清楚我是怎么样一个人", "2026-10-09T00:00:00Z", "", 0, now))
        assertFalse(CharacterStaleGoalPolicy.shouldRetire(
            "弄清楚我是怎么样一个人", "2026-08-01T00:00:00Z", "2026-10-09T00:00:00Z", 0, now))
    }

    @Test fun invalidOrFutureDatesAreNotGuessedAt() {
        assertFalse(CharacterStaleGoalPolicy.shouldRetire(
            "弄清楚我是怎么样一个人", "", "", 0, now))
        assertFalse(CharacterStaleGoalPolicy.shouldRetire(
            "弄清楚我是怎么样一个人", "2026-11-01T00:00:00Z", "", 0, now))
    }
}
