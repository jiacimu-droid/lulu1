package com.jiacimu.lulu.system

/**
 * The call session owns the actual bedtime toggle. A mention of "哄睡" in
 * an unfinished commitment, scene description or silence observation must
 * never activate the continuous sleep prompt while the call toggle is off.
 */
internal object PhoneSleepPromptMode {
    fun active(callId: String, modeEnabled: Boolean): Boolean =
        callId.isNotBlank() && modeEnabled
}
