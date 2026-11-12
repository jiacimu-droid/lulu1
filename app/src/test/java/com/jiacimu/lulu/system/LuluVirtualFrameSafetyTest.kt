package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualFrameSafetyTest {
    private val pixels = "data:image/jpeg;base64,AAAB"

    @Test fun aBlackOrOldFrameNeverReachesTheModel() {
        assertFalse(LuluVirtualFrameSafety.canSendToRole(true, 12L, pixels, true))
        assertFalse(LuluVirtualFrameSafety.canSendToRole(false, 12L, pixels, false))
        assertFalse(LuluVirtualFrameSafety.canSendToRole(true, 0L, pixels, false))
        assertFalse(LuluVirtualFrameSafety.canSendToRole(true, 12L, "not-a-frame", false))
    }

    @Test fun verifiedFreshNonBlackFrameCanBeUsed() {
        assertTrue(LuluVirtualFrameSafety.canSendToRole(true, 12L, pixels, false))
    }
}
