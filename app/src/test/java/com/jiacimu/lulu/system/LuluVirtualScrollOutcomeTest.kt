package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Test

class LuluVirtualScrollOutcomeTest {
    private fun kind(
        accepted: Boolean = true, owned: Boolean = true,
        before: String = "tree1", after: String = "tree2",
        oldText: String = "旧帖子", newText: String = "新帖子",
    ) = LuluVirtualScrollOutcome.classify(accepted, owned, before, after, oldText, newText)

    @Test fun contentChangeRequiresRealEvidence() {
        assertEquals(LuluVirtualScrollOutcome.Kind.CONTENT_CHANGED, kind())
        assertEquals(LuluVirtualScrollOutcome.Kind.CONTENT_CHANGED,
            kind(before = "", after = "", oldText = "第一页", newText = "下一页"))
    }

    @Test fun acceptedGestureWithoutUiChangeIsNotProgress() {
        assertEquals(LuluVirtualScrollOutcome.Kind.NO_VISIBLE_CHANGE,
            kind(before = "same", after = "same", oldText = "同一条", newText = "同一条"))
    }

    @Test fun unknownOrProtectedScreenCannotClaimProgressOrBottom() {
        assertEquals(LuluVirtualScrollOutcome.Kind.UNKNOWN,
            kind(before = "", after = "", oldText = "", newText = ""))
        assertEquals(LuluVirtualScrollOutcome.Kind.UNKNOWN,
            kind(owned = false))
        assertEquals(LuluVirtualScrollOutcome.Kind.UNKNOWN,
            kind(before = "same", after = "same", oldText = "", newText = ""))
    }

    @Test fun rejectedSwipeNeverCountsAsRead() {
        assertEquals(LuluVirtualScrollOutcome.Kind.REJECTED, kind(accepted = false))
    }
}
