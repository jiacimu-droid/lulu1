package com.jiacimu.lulu.system

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
