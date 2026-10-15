package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ProactivePerceptionRecoveryPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun oneFailedBackgroundModelCallGetsOneBoundedRecoveryOpportunity() {
        val policy = ProactivePerceptionRecoveryPolicy
        assertTrue(policy.shouldRetry(0, now, "角色时间间隔", false, true))
        assertFalse(policy.shouldRetry(now - 60_000L, now, "角色时间间隔", false, true))
        assertFalse(policy.shouldRetry(now - 20 * 60_000L, now, "角色时间间隔", false, true))
        assertTrue(policy.shouldRetry(now - policy.COOLDOWN_MILLIS, now,
            "角色时间间隔", false, true))
    }

    @Test fun recoveryCannotRecurseOrCompeteWithUnreadBatchOrDisabledAutonomy() {
        val policy = ProactivePerceptionRecoveryPolicy
        assertFalse(policy.shouldRetry(0, now, policy.RETRY_TRIGGER, false, true))
        assertFalse(policy.shouldRetry(0, now, "在线收到新消息", true, true))
        assertFalse(policy.shouldRetry(0, now, "角色时间间隔", false, false))
        assertEquals(4L * 60_000L, policy.RETRY_DELAY_MILLIS)
    }
}
