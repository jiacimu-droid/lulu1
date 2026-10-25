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
        // Before first layout a LazyColumn starts at index 0. Requiring
        // nearEnd would prevent the initial jump in long conversations.
        if (initial) return true
        if (!nearEnd) return false
        return (autoScroll && appended) || keyboardVisible
    }
}
