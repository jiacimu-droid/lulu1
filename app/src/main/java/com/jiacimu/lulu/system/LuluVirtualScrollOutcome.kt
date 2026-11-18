package com.jiacimu.lulu.system

/**
 * Mika ActionEffect / WheelOutcome-inspired read-only swipe receipt.
 * Scrolling is never declared successful just because Android accepted a swipe
 * or the virtual screen produced another frame. "No visible change" is NOT
 * proof of the end of a list, only an invitation for the same role to reassess.
 */
internal object LuluVirtualScrollOutcome {
    enum class Kind(val wire: String) {
        CONTENT_CHANGED("content_changed"),
        FRAME_CHANGED("frame_changed"),
        NO_VISIBLE_CHANGE("no_visible_change"),
        UNKNOWN("unknown"),
        REJECTED("rejected"),
    }

    fun classify(
        accepted: Boolean, targetOwned: Boolean,
        beforeFingerprint: String?, afterFingerprint: String?,
        beforeText: String?, afterText: String?, freshPixelsChanged: Boolean = false,
    ): Kind {
        if (!accepted) return Kind.REJECTED
        if (!targetOwned) return Kind.UNKNOWN
        val beforeTree = beforeFingerprint.orEmpty()
        val afterTree = afterFingerprint.orEmpty()
        val a = beforeText.orEmpty().trim()
        val b = afterText.orEmpty().trim()
        val comparableTrees = beforeTree.isNotBlank() && afterTree.isNotBlank()
        val comparableText = a.isNotBlank() && b.isNotBlank()
        if ((comparableTrees && beforeTree != afterTree) || (comparableText && a != b))
            return Kind.CONTENT_CHANGED
        // Pixels can change because a video plays in place. Send the current
        // image for the role to inspect without declaring that scrolling worked.
        if (freshPixelsChanged) return Kind.FRAME_CHANGED
        return if (comparableTrees && comparableText) Kind.NO_VISIBLE_CHANGE else Kind.UNKNOWN
    }
}
