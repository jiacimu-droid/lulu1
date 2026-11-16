package com.jiacimu.lulu.system

import com.wangzan101.mika.cap.phone.shell.LuluShellDisplayMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android's am can print an error even with exit code 0. */
class LuluShellDisplayCommandTest {
    @Test fun successfulActivityLaunchIsStillOnlyAnAcceptedCommand() {
        assertTrue(LuluShellDisplayMain.shellCommandSucceeded(0,
            "Starting: Intent { act=android.intent.action.MAIN cmp=com.tencent.mm/.ui.LauncherUI }"))
    }

    @Test fun virtualPhonePreservesTallScreenRatioAndLogicalDensity() {
        // 1220x2712 at 480dpi must not become a 9:16 360dp-wide phone.
        val phone = LuluShellDisplayMain.chooseGeometry(1220, 2712, 480)
        org.junit.Assert.assertEquals(720, phone.width)
        org.junit.Assert.assertEquals(1600, phone.height)
        org.junit.Assert.assertEquals(283, phone.densityDpi)
        // Logical dp width/height differ by less than one dp due to rounding.
        org.junit.Assert.assertEquals(407, (phone.width * 160f / phone.densityDpi).toInt())
        val fallback = LuluShellDisplayMain.chooseGeometry(0, 0, 0)
        org.junit.Assert.assertEquals(720, fallback.width)
        org.junit.Assert.assertEquals(1280, fallback.height)
        org.junit.Assert.assertEquals(320, fallback.densityDpi)
        val small = LuluShellDisplayMain.chooseGeometry(480, 1024, 320)
        org.junit.Assert.assertEquals(480, small.width)
        org.junit.Assert.assertEquals(1024, small.height)
        org.junit.Assert.assertEquals(320, small.densityDpi)
    }

    @Test fun systemRejectedWechatLaunchCannotMasqueradeAsDispatched() {
        assertFalse(LuluShellDisplayMain.shellCommandSucceeded(0,
            "Error type 3\nError: Activity class {com.tencent.mm/com.tencent.mm.ui.LauncherUI} does not exist."))
        assertFalse(LuluShellDisplayMain.shellCommandSucceeded(0,
            "Exception occurred while executing 'start':\njava.lang.SecurityException: Permission Denial"))
        assertFalse(LuluShellDisplayMain.shellCommandSucceeded(1, ""))
    }
}
