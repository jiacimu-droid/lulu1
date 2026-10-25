package com.jiacimu.lulu

/** Keep automatic scrolling subordinate to the user's current reading position. */
internal object ChatScrollFollowPolicy {
    fun shouldFollow(
        initial: Boolean,
        appended: Boolean,
        keyboardVisible: Boolean,
        autoScroll: Boolean,
        nearEnd: Boolean,
    ): Boolean {
        if (!nearEnd) return false
        return (autoScroll && (initial || appended)) || keyboardVisible
    }
}
