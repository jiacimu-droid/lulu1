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

    @Test fun systemRejectedWechatLaunchCannotMasqueradeAsDispatched() {
        assertFalse(LuluShellDisplayMain.shellCommandSucceeded(0,
            "Error type 3\nError: Activity class {com.tencent.mm/com.tencent.mm.ui.LauncherUI} does not exist."))
        assertFalse(LuluShellDisplayMain.shellCommandSucceeded(0,
            "Exception occurred while executing 'start':\njava.lang.SecurityException: Permission Denial"))
        assertFalse(LuluShellDisplayMain.shellCommandSucceeded(1, ""))
    }
}
