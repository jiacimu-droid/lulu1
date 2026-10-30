package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallAudioRecoveryPolicyTest {
    @Test fun silentPaidPcmIsEligibleForExactlyLocalReplay() {
        assertTrue(CallAudioRecoveryPolicy.canReplay(0, 48_000, 48_000, true))
        assertFalse(CallAudioRecoveryPolicy.canReplay(1, 48_000, 48_000, true))
        assertFalse(CallAudioRecoveryPolicy.canReplay(0, 48_000, 47_000, true))
        assertFalse(CallAudioRecoveryPolicy.canReplay(0, 48_000, 48_000, false))
        assertFalse(CallAudioRecoveryPolicy.canReplay(0, 0, 0, true))
        assertFalse(CallAudioRecoveryPolicy.canReplay(0, 3, 3, true))
    }
}
