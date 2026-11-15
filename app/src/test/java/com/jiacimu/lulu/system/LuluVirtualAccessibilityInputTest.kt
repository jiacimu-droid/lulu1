package com.jiacimu.lulu.system

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Virtual display input must fail closed, never fall back to shell or display 0. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LuluVirtualAccessibilityInputTest {
    @Test fun virtualTapWithoutAccessibilityNeverPretendsToDispatch() = runBlocking {
        val receipt = LuluAccessibilityService.tapVirtualDisplay(71, "com.tencent.mm", 500, 500)
        assertFalse(receipt.optBoolean("accepted"))
        assertEquals("accessibility_disconnected", receipt.optString("errorCode"))
        assertEquals("accessibility_tap_virtual_display", receipt.optString("actionMethod"))
    }

    @Test fun wechatComposerRecoveryNeverGuessesKeyboardWithoutOwnedAccessibility() {
        val receipt = LuluAccessibilityService.switchVirtualChatToText(
            displayId = 71, packageName = "com.tencent.mm",
            recipient = "文件传输助手", expectedFingerprint = "observed-real-tree")
        assertFalse(receipt.optBoolean("accepted"))
        assertFalse(receipt.optBoolean("inputModeSwitchClicked"))
        assertFalse(receipt.optBoolean("inputModeTextVerified"))
        assertEquals("accessibility_disconnected", receipt.optString("errorCode"))
        assertFalse(LuluAccessibilityService.virtualChatTextInputVisible(
            71, "com.tencent.mm", "文件传输助手"))
    }

    @Test fun appBackWithoutAccessibilityNeverTouchesAnotherDisplay() {
        val receipt = LuluAccessibilityService.tapVirtualAppBackControl(
            displayId = 71, packageName = "com.ss.android.ugc.aweme",
            expectedFingerprint = "verified-before-tree")
        assertFalse(receipt.optBoolean("accepted"))
        assertEquals("accessibility_disconnected", receipt.optString("errorCode"))
        assertEquals("accessibility_app_back_node", receipt.optString("actionMethod"))
    }

    @Test fun searchSubmitWithoutAccessibilityDoesNotInjectShellKeyevent() {
        val receipt = LuluAccessibilityService.submitVirtualSearch(71, "com.tencent.mm")
        assertFalse(receipt.optBoolean("accepted"))
        assertEquals("accessibility_disconnected", receipt.optString("errorCode"))
        assertEquals("accessibility_ime_enter_virtual_search", receipt.optString("actionMethod"))
    }
}
