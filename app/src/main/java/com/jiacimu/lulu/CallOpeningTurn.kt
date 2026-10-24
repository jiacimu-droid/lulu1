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
        fun isWakeCallReason(reason: String): Boolean =
            listOf("叫醒", "起床", "醒醒", "唤醒", "喊醒", "睡醒").any(reason::contains)

        /** Authored before dialing, but audible only AFTER the user actually answers. */
        const val incomingLineContract =
            "主动来电的 text 是用户已经接听、双方电话已接通后，你面对她亲口说出的第一句话；不是拨号前自言自语、响铃期间的催接台词或发给未接听者的留言。接听前这句话不会被说出。可以自然提起以前真实发生的拒接，但此刻对方已经接听、尚未开口；不要把当前电话说成仍无人接听，也不要编造她已回答。"

        fun prompt(reason: String = ""): String = buildString {
            append("[真实通话事件：电话已经接通，用户确实接听了，此刻正听着你；她还没有开口。按本人性格、关系和最近上下文自然开始说话。不要把此刻当作拨号、响铃或等待接听，不要念出事件说明或编造用户发言。]")
            if (reason.isNotBlank()) append("[你此前想打电话的缘由：${reason.take(500)}。这是拨号前的想法，不是已经说过的话；自然接续。]")
        }
    }
}
