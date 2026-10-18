package com.jiacimu.lulu

/** A result belongs to the original call; speech always gets the first opportunity. */
internal object CallExternalResultPolicy {
    fun canDeliver(expectedRole: String, expectedCall: String, role: String, call: String,
        connected: Boolean, opening: Boolean, thinking: Boolean, speaking: Boolean,
        userSpeaking: Boolean, audioPending: Boolean): Boolean =
        connected && expectedRole == role && expectedCall == call &&
            !opening && !thinking && !speaking && !userSpeaking && !audioPending
}
