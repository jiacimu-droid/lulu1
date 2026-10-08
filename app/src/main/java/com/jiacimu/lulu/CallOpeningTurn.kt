package com.jiacimu.lulu

/** One opening for a connected session, including microphone reconnects. */
internal class CallOpeningTurn {
    private var claimedSession = ""

    fun claim(sessionId: String): Boolean {
        if (sessionId.isBlank() || claimedSession == sessionId) return false
        claimedSession = sessionId
        return true
    }

    companion object {
        fun prompt(reason: String = ""): String = buildString {
            append("[通话事件：电话刚刚接通，用户还没有说话。请你先自然开口，按自己的人设、关系和最近上下文说一两句开场话。不要念出事件说明，不要编造用户说过的话。]")
            if (reason.isNotBlank()) append("[这是你主动打来的电话，来电缘由：${reason.take(500)}。自然接续这个缘由。]")
        }
    }
}
