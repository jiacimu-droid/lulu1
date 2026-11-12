package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluPhoneRepeatGuardTest {
    @Test fun sameTapCannotBeRepeatedAfterUnverifiedEffect() {
        val last = LuluPhoneRepeatGuard.Attempt("tap", "500:300", true, "unknown")
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(last, "tap", "500:300"))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(last, "tap", "500:400"))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(last, "observe", ""))
    }

    @Test fun submitIsNotReissuedWithoutFreshEvidence() {
        val last = LuluPhoneRepeatGuard.Attempt("submit", "enter", true, "tree_unchanged")
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(last, "submit", "enter"))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(
            last.copy(effect = "tree_changed"), "submit", "enter"))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(
            last.copy(accepted = false), "submit", "enter"))
    }

    @Test fun renewedRefOnSameScreenDoesNotUnlockAnUnverifiedTap() {
        val before = LuluPhoneRepeatGuard.Screen("2:app", "same-tree", "搜索", "frame")
        val after = before.copy()
        val first = LuluPhoneRepeatGuard.target("tap_element",
            mapOf("screenSnapshotId" to "s1", "elementRef" to "e2"), "search|搜索|10|20")
        val second = LuluPhoneRepeatGuard.target("tap_element",
            mapOf("screenSnapshotId" to "s2", "elementRef" to "e3"), "search|搜索|10|20")
        assertEquals(first, second)
        assertEquals(false, LuluPhoneRepeatGuard.changed(before, after))
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(
            LuluPhoneRepeatGuard.Attempt("tap_element", first, true, "tree_unchanged"),
            "tap_element", second))
        assertEquals(true, LuluPhoneRepeatGuard.changed(before, after.copy(text = "搜索结果")))
    }

    @Test fun repeatedReadNeedsComparableEvidenceAndRespectsVisualChanges() {
        val before = LuluPhoneRepeatGuard.Screen("2:app", "tree", "同一页", "a")
        assertTrue(LuluPhoneRepeatGuard.isRepeatedRead(before, before))
        assertFalse(LuluPhoneRepeatGuard.isRepeatedRead(before, before.copy(image = "b")))
        assertFalse(LuluPhoneRepeatGuard.isRepeatedRead(before, before.copy(tree = "", image = null)))
    }

    @Test fun sameUnverifiedQueryIsBlockedButDifferentQueryIsAvailable() {
        val previous = LuluPhoneRepeatGuard.Attempt("search", "考研", true, "tree_unchanged")
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(previous, "search",
            LuluPhoneRepeatGuard.target("search", mapOf("text" to " 考研 "))))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(previous, "search",
            LuluPhoneRepeatGuard.target("search", mapOf("text" to "法硕"))))
    }

    @Test fun rejectedLaunchCanChangeAppsButCannotRepeatTheSameTarget() {
        val target = LuluPhoneRepeatGuard.target("launch", mapOf("appName" to "小红书"))
        val previous = LuluPhoneRepeatGuard.Attempt("launch", target, false, "known_rejection")
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(previous, "launch", target))
        assertFalse(LuluPhoneRepeatGuard.shouldBlock(previous, "launch", "浏览器"))
    }

    @Test fun matchingSnapshotMustAlsoMatchElementReference() {
        val a = LuluPhoneRepeatGuard.target("tap_element",
            mapOf("screenSnapshotId" to "s1", "elementRef" to "e4"))
        val b = LuluPhoneRepeatGuard.target("tap_element",
            mapOf("screenSnapshotId" to "s2", "elementRef" to "e4"))
        assertFalse(a == b)
        assertTrue(LuluPhoneRepeatGuard.shouldBlock(
            LuluPhoneRepeatGuard.Attempt("tap_element", a, true, "tree_unchanged"),
            "tap_element", a))
    }
}
