package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallExternalResultPolicyTest {
    private fun ready(role: String = "a", call: String = "first", connected: Boolean = true,
        opening: Boolean = false, thinking: Boolean = false, speaking: Boolean = false,
        userSpeaking: Boolean = false, audioPending: Boolean = false,
        hangupPending: Boolean = false) =
        CallExternalResultPolicy.canDeliver("a", "first", role, call,
            connected, opening, thinking, speaking, userSpeaking, audioPending, hangupPending)

    @Test fun realResultWaitsForBothPeopleAndQueuedAudio() {
        assertTrue(ready())
        assertFalse(ready(opening = true))
        assertFalse(ready(thinking = true))
        assertFalse(ready(speaking = true))
        assertFalse(ready(userSpeaking = true))
        assertFalse(ready(audioPending = true))
    }

    @Test fun receiptWaitsThroughFarewellGraceAndCanProceedAfterUserCancelsIt() {
        assertFalse(ready(hangupPending = true))
        assertFalse(ready(hangupPending = true, userSpeaking = true))
        assertFalse(ready(connected = false, hangupPending = false))
        // A confirmed user turn cancels the farewell, then takes the turn slot.
        assertFalse(ready(hangupPending = false, userSpeaking = true))
        assertFalse(ready(hangupPending = false, thinking = true))
        assertTrue(ready(hangupPending = false))
    }

    @Test fun oldResultCannotSpeakInAnotherCallOrAsAnotherPerson() {
        assertFalse(ready(role = "b"))
        assertFalse(ready(call = "second"))
        assertFalse(ready(connected = false))
    }
}
