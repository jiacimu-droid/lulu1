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
        // Before paying for an unsolicited greeting, give the caller a moment
        // to start speaking. A scheduled wake-up must not be delayed.
        fun initialReplyGraceMillis(incomingReason: String, hasScheduledTask: Boolean): Long =
            if (hasScheduledTask || isWakeCallReason(incomingReason)) 0L else 1_200L

        fun isWakeCallReason(reason: String): Boolean =
            listOf("叫醒", "起床", "醒醒", "唤醒", "喊醒", "睡醒").any(reason::contains)

        /** Authored before dialing, but audible only AFTER the user actually answers. */
        const val incomingLineContract =
            "主动来电的 text 是用户已经接听、双方电话已接通后，你面对她亲口说出的第一句话；不是拨号前自言自语、响铃期间的催接台词或发给未接听者的留言。接听前这句话不会被说出。可以自然提起以前真实发生的拒接，但此刻对方已经接听、尚未开口；不要把当前电话说成仍无人接听，也不要编造她已回答。开场由你真实想联系她的缘由与本人个性决定，不把例行设备操作说明当成第一句话；只有用户主动问起或出现确实发生的通话故障，才自然讨论技术问题。"

        fun prompt(reason: String = ""): String = buildString {
            append("[真实通话事件：本通电话已经接通，你和用户正在实时通话；她还没有开口。按本人性格、关系、来电动机和最近上下文自然开始说话，不需要固定寒暄或确认接听。不要把此刻当作拨号、响铃或等待接听，不要念出事件说明或编造用户发言。设备设置、接通与收音状态由电话界面提示，不是例行开场话题；除非用户确实问起或发生真实故障，不主动指导她操作手机。]")
            if (reason.isNotBlank()) append("[你此前想打电话的缘由：${reason.take(500)}。这是拨号前的想法，不是已经说过的话；自然接续。]")
        }
    }
}
