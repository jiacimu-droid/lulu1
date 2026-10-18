package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallExternalResultPolicyTest {
    private fun ready(role: String = "a", call: String = "first", connected: Boolean = true,
        opening: Boolean = false, thinking: Boolean = false, speaking: Boolean = false,
        userSpeaking: Boolean = false, audioPending: Boolean = false) =
        CallExternalResultPolicy.canDeliver("a", "first", role, call,
            connected, opening, thinking, speaking, userSpeaking, audioPending)

    @Test fun realResultWaitsForBothPeopleAndQueuedAudio() {
        assertTrue(ready())
        assertFalse(ready(opening = true))
        assertFalse(ready(thinking = true))
        assertFalse(ready(speaking = true))
        assertFalse(ready(userSpeaking = true))
        assertFalse(ready(audioPending = true))
    }

    @Test fun oldResultCannotSpeakInAnotherCallOrAsAnotherPerson() {
        assertFalse(ready(role = "b"))
        assertFalse(ready(call = "second"))
        assertFalse(ready(connected = false))
    }
}
