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
        NO_VISIBLE_CHANGE("no_visible_change"),
        UNKNOWN("unknown"),
        REJECTED("rejected"),
    }

    fun classify(
        accepted: Boolean, targetOwned: Boolean,
        beforeFingerprint: String?, afterFingerprint: String?,
        beforeText: String?, afterText: String?,
    ): Kind {
        if (!accepted) return Kind.REJECTED
        if (!targetOwned) return Kind.UNKNOWN
        val beforeTree = beforeFingerprint.orEmpty()
        val afterTree = afterFingerprint.orEmpty()
        val a = beforeText.orEmpty().trim()
        val b = afterText.orEmpty().trim()
        if (beforeTree.isNotBlank() && afterTree.isNotBlank()) {
            if (beforeTree != afterTree) return Kind.CONTENT_CHANGED
            // Even an unchanged accessibility tree does not prove the list has
            // reached its end: image-only pages may have no readable text.
            return if (a.isNotBlank() && b.isNotBlank() && a == b)
                Kind.NO_VISIBLE_CHANGE else Kind.UNKNOWN
        }
        if (a.isNotBlank() && b.isNotBlank() && a != b) return Kind.CONTENT_CHANGED
        return Kind.UNKNOWN
    }
}
