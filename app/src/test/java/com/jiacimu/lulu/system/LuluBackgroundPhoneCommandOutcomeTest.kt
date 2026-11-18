package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Test

class LuluBackgroundPhoneCommandOutcomeTest {
    @Test
    fun missingHelperReplyIsUnknownRatherThanSafeToRetry() {
        val lostReply = LuluBackgroundPhoneSession.UnverifiedHelperCommandException(
            "helper reply missing after a possible device click"
        )
        assertEquals("unverified", LuluBackgroundPhoneSession.commandFailureStatus(lostReply))
    }

    @Test
    fun rejectedBeforeDispatchRemainsFailure() {
        val rejected = IllegalStateException("owner stopped before helper dispatch")
        assertEquals("failed", LuluBackgroundPhoneSession.commandFailureStatus(rejected))
    }
}
