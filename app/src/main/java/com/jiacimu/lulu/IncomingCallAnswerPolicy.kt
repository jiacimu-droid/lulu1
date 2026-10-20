package com.jiacimu.lulu

import com.jiacimu.lulu.data.ProactiveIncomingCall

/** The Answer button is intent, not evidence. Only a matching Connected session
 * can consume the original pending ring or acknowledge a timed call. */
internal object IncomingCallAnswerPolicy {
    fun genuinelyConnected(
        invited: ProactiveIncomingCall,
        pending: ProactiveIncomingCall?,
        actual: LuluVoiceCallState,
    ): Boolean = pending == invited && invited.active() &&
        actual.phase == CallPhase.Connected &&
        actual.conversationId == invited.conversationId &&
        actual.characterId == invited.characterId &&
        actual.callStartMessageCount >= 0
}
