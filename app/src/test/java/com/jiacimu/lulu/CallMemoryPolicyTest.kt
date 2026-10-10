package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallMemoryPolicyTest {
    @Test fun callsDoNotBecomeHighStrengthMemoriesByDefault() {
        assertFalse(CallMemoryPolicy.DUPLICATE_FULL_CALL_AS_STRONG_MEMORY)
        assertTrue(CallMemoryPolicy.TRANSCRIPT_STILL_AVAILABLE)
        assertTrue(CallMemoryPolicy.IMPORTANT_DETAILS_STILL_EXTRACTABLE)
        assertTrue("voice-sleep-callId-agent-id".startsWith("voice-sleep-"))
        assertFalse("voice-callId-agent-id".startsWith("voice-sleep-"))
    }
}
