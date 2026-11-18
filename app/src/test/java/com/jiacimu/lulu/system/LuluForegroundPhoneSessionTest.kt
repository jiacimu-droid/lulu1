package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main display is never an invisible replacement for an ADB display. */
class LuluForegroundPhoneSessionTest {
    @Test fun roleMustReobserveBeforeTouchingOrTyping() {
        listOf("tap", "long_press", "tap_element", "type", "navigate",
            "search", "focus_search").forEach {
            assertTrue(it, LuluForegroundPhoneSession.requiresNewObservation(it))
        }
        listOf("observe", "back", "launch", "stop", "swipe").forEach {
            assertFalse(it, LuluForegroundPhoneSession.requiresNewObservation(it))
        }
    }

    @Test fun foregroundCannotOperateOtherAppsOrPrivacyExcludedPages() {
        assertTrue(LuluForegroundPhoneSession.allowsObservedTarget(
            "com.tencent.mm", "com.tencent.mm", excluded = false))
        assertFalse(LuluForegroundPhoneSession.allowsObservedTarget(
            "com.tencent.mm", "com.xunmeng.pinduoduo", excluded = false))
        assertFalse(LuluForegroundPhoneSession.allowsObservedTarget(
            "com.tencent.mm", "com.tencent.mm", excluded = true))
        assertFalse(LuluForegroundPhoneSession.allowsObservedTarget(
            "", "com.tencent.mm", excluded = false))
    }
}
