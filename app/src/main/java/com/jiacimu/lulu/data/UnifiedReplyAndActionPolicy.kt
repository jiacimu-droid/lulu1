package com.jiacimu.lulu.data

/**
 * Reply and device actions are independent choices from ONE character model.
 * A tool receipt is never a conversation receipt. Missing spoken output may
 * only consume real unread input if the role explicitly chose silence.
 */
internal object UnifiedReplyAndActionPolicy {
    private val messageActions = setOf(
        "message", "kaomoji", "sticker",
        "group_message", "group_kaomoji", "group_sticker",
    )
    private val messageTools = setOf(
        "send_private_message", "send_group_message",
        "send_private_sticker", "send_group_sticker",
    )

    fun markSeen(
        unreadPresent: Boolean,
        chosenAction: String,
        chosenTool: String,
        replyChoice: String,
        actionSucceeded: Boolean,
        replyActuallyDelivered: Boolean,
        conversationMatches: Boolean,
    ): Boolean {
        if (!unreadPresent) return true
        if (replyActuallyDelivered) return true
        if (chosenAction == "silent") return true
        if (!actionSucceeded) return false
        if (chosenAction in messageActions) return conversationMatches
        if (chosenAction == "tool") {
            if (chosenTool in messageTools) return conversationMatches
            return replyChoice == "silent"
        }
        return true // A character can deliberately choose other lived activity.
    }
}
