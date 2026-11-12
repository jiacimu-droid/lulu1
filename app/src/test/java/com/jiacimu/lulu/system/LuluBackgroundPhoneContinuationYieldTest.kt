package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluBackgroundPhoneContinuationYieldTest {
    @Test fun newMessageWhilePhoneModelWaitsYieldsBeforeAnyNextTool() {
        val frozen = setOf("old-user-message", "old-group-message")
        assertFalse(LuluBackgroundPhoneContinuation.hasNewUnreadInput(
            frozen, setOf("old-user-message")))
        assertTrue(LuluBackgroundPhoneContinuation.hasNewUnreadInput(
            frozen, setOf("old-user-message", "new-user-message")))
    }

    @Test fun acknowledgingOldMessagesDoesNotFakeNewInput() {
        assertFalse(LuluBackgroundPhoneContinuation.hasNewUnreadInput(
            setOf("old-user-message", "old-group-message"), emptySet()))
    }
}
