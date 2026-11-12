package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualWindowPolicyTest {
    private val cls = LuluVirtualWindowPolicy::classify
    private val target = "com.xingin.xhs"

    @Test fun ignoreImeButNeverARealSystemDialog() {
        assertEquals(LuluVirtualWindowPolicy.Kind.TARGET,
            cls(target,target,LuluVirtualWindowPolicy.TYPE_APPLICATION))
        assertEquals(LuluVirtualWindowPolicy.Kind.SYSTEM_DIALOG,
            cls(target,"android",LuluVirtualWindowPolicy.TYPE_SYSTEM))
        assertEquals(LuluVirtualWindowPolicy.Kind.SYSTEM_DIALOG,
            cls(target,target,LuluVirtualWindowPolicy.TYPE_SYSTEM))
        assertEquals(LuluVirtualWindowPolicy.Kind.SYSTEM_DIALOG,
            cls(target,null,LuluVirtualWindowPolicy.TYPE_SYSTEM))
    }

    @Test fun appSwitchDoesNotClaimSameTaskOrSafeClick() {
        assertEquals(LuluVirtualWindowPolicy.Kind.OTHER_APP,
            cls(target,"com.android.browser",LuluVirtualWindowPolicy.TYPE_APPLICATION))
        assertEquals(LuluVirtualWindowPolicy.Kind.UNKNOWN,
            cls(target,null,LuluVirtualWindowPolicy.TYPE_APPLICATION))
        assertTrue(LuluVirtualWindowPolicy.pausesAutomation(
            cls(target,"android",LuluVirtualWindowPolicy.TYPE_SYSTEM)))
        assertFalse(LuluVirtualWindowPolicy.pausesAutomation(
            cls(target,target,LuluVirtualWindowPolicy.TYPE_APPLICATION)))
    }

    @Test fun mikaClassifierOnlyMarksProvenTaskRelation() {
        assertTrue(LuluMikaAppSwitch.switchedTo(target,"com.android.browser",
            sameTask = { false }, partOfTask = emptySet(), settingsPage = { false })?.byTarget == false)
        assertTrue(LuluMikaAppSwitch.switchedTo(target,"com.android.browser",
            sameTask = { true }, partOfTask = emptySet(), settingsPage = { false })?.byTarget == true)
        assertEquals(null,LuluMikaAppSwitch.switchedTo(target,"android",
            sameTask = { false }, partOfTask = setOf("android"), settingsPage = { false }))
    }
}
