package com.jiacimu.lulu.system

import com.wangzan101.mika.cap.phone.shell.LuluShellTextInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PH-01: full-phone typing is not a WeChat/search-specific feature. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LuluVirtualTextInputPolicyTest {
    @Test fun shellUnicodeTransportTargetsAnyOwnedThirdPartyApp() {
        listOf(
            "com.tencent.mm",
            "com.ss.android.ugc.aweme",
            "com.xingin.xhs",
            "com.netease.cloudmusic",
            "com.taobao.taobao",
            "com.taobao.idlefish",
            "com.example.notes",
        ).forEach { pkg ->
            assertTrue(pkg, LuluShellTextInput.mayPaste(7, pkg, "你好，世界🌸", pkg))
        }
        assertFalse(LuluShellTextInput.mayPaste(0,
            "com.example.notes", "中文", "com.example.notes"))
        assertFalse(LuluShellTextInput.mayPaste(7,
            "com.example.notes", "中文", "com.tencent.mm"))
        assertFalse(LuluShellTextInput.mayPaste(7,
            "com.example.notes", "", "com.example.notes"))
        assertFalse(LuluShellTextInput.mayPaste(7,
            "notes", "中文", "notes"))
        assertFalse(LuluShellTextInput.mayPaste(7,
            "com.example.notes", "a".repeat(LuluVirtualTextInputPolicy.MAX_TEXT_CHARS + 1),
            "com.example.notes"))
        assertTrue(LuluShellTextInput.mayPaste(7,
            "com.example.notes", "a".repeat(800), "com.example.notes"))
    }

    @Test fun visualTypingRequiresLatestSnapshotAndPairedRealCoordinates() {
        val base = JSONObject().put("text", "一起去看日落")
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type", base))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type",
            JSONObject(base.toString()).put("inputX", 410)))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type",
            JSONObject(base.toString()).put("inputX", 410).put("inputY", 750)))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type",
            JSONObject(base.toString())
                .put("inputX", 410).put("inputY", 750)
                .put("screenSnapshotId", "fresh")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type",
            JSONObject(base.toString()).put("inputX", -10)
                .put("inputY", 750).put("screenSnapshotId", "fresh")))
    }

    @Test fun focusedEditorFallbackRequiresClickedPointInsideCurrentOwnedField() {
        // Coordinates come from the exact virtual display snapshot, not display 0.
        assertTrue(LuluVirtualTextInputPolicy.focusedFieldContainsSelectedPoint(
            460, 750, 720, 1280, 90, 915, 680, 995))
        assertFalse("An unrelated focused editor cannot receive the draft",
            LuluVirtualTextInputPolicy.focusedFieldContainsSelectedPoint(
                460, 320, 720, 1280, 90, 915, 680, 995))
        assertFalse("Wrong orientation dimensions cannot authorize a random field",
            LuluVirtualTextInputPolicy.focusedFieldContainsSelectedPoint(
                460, 750, 720, 1280, 90, 915, 1000, 995))
        assertFalse(LuluVirtualTextInputPolicy.focusedFieldContainsSelectedPoint(
            460, 750, 0, 1280, 90, 915, 680, 995))
        assertFalse("System status bar is not an editable area",
            LuluVirtualTextInputPolicy.focusedFieldContainsSelectedPoint(
                460, 10, 720, 1280, 90, 0, 680, 995))
        assertFalse("A full-screen ambiguous node is not a field",
            LuluVirtualTextInputPolicy.focusedFieldContainsSelectedPoint(
                460, 750, 720, 1280, 0, 0, 720, 1280))
    }

    @Test fun customEditorCanPasteWithoutAnImeWindowOnlyWithOwnedWindowFocus() {
        val policy = LuluVirtualTextInputPolicy
        val paste = LuluVirtualTextInputPolicy.VisualInputRoute.TARGETED_PASTE
        val wait = LuluVirtualTextInputPolicy.VisualInputRoute.WAIT
        assertEquals(paste, policy.visualInputRoute(
            safeFocus = true, hasKnownEditor = false, matchesEditor = false, supportsSetText = false,
            sameWindow = true, targetWindowFocused = true, localImeVisible = false))
        assertEquals(wait, policy.visualInputRoute(
            safeFocus = true, hasKnownEditor = false, matchesEditor = false, supportsSetText = false,
            sameWindow = true, targetWindowFocused = false, localImeVisible = false))
        assertEquals(wait, policy.visualInputRoute(
            safeFocus = true, hasKnownEditor = false, matchesEditor = false, supportsSetText = false,
            sameWindow = false, targetWindowFocused = true, localImeVisible = true))
    }

    @Test fun knownEditorMustMatchTheTappedFieldEvenWithAnImeWindow() {
        val policy = LuluVirtualTextInputPolicy
        val wait = LuluVirtualTextInputPolicy.VisualInputRoute.WAIT
        assertEquals(wait, policy.visualInputRoute(
            safeFocus = true, hasKnownEditor = true, matchesEditor = false, supportsSetText = true,
            sameWindow = true, targetWindowFocused = true, localImeVisible = true))
        assertEquals(wait, policy.visualInputRoute(
            safeFocus = false, hasKnownEditor = true, matchesEditor = true, supportsSetText = true,
            sameWindow = true, targetWindowFocused = true, localImeVisible = true))
        assertEquals(LuluVirtualTextInputPolicy.VisualInputRoute.SET_TEXT, policy.visualInputRoute(
            safeFocus = true, hasKnownEditor = true, matchesEditor = true, supportsSetText = true,
            sameWindow = true, targetWindowFocused = true, localImeVisible = false))
        assertEquals(LuluVirtualTextInputPolicy.VisualInputRoute.TARGETED_PASTE, policy.visualInputRoute(
            safeFocus = true, hasKnownEditor = true, matchesEditor = true, supportsSetText = false,
            sameWindow = true, targetWindowFocused = true, localImeVisible = false))
    }

    @Test fun inputPointMustStayOnOwnedAppAboveIme() {
        assertTrue(LuluVirtualTextInputPolicy.inputPointSafe(450, 70, null))
        assertTrue(LuluVirtualTextInputPolicy.inputPointSafe(450, 760, 900))
        assertFalse(LuluVirtualTextInputPolicy.inputPointSafe(450, 900, 900))
        assertFalse(LuluVirtualTextInputPolicy.inputPointSafe(450, 10, null))
        assertFalse(LuluVirtualTextInputPolicy.inputPointSafe(450, 995, null))
        assertFalse(LuluVirtualTextInputPolicy.inputPointSafe(0, 600, null))
        assertEquals("input_dispatched_unverified",
            LuluVirtualTextInputPolicy.stage(true, false))
        assertEquals("draft_verified",
            LuluVirtualTextInputPolicy.stage(true, true))
        assertEquals("input_not_dispatched",
            LuluVirtualTextInputPolicy.stage(false, false))
        // A stale or contradictory exact-readback flag must never promote an
        // explicitly rejected input dispatch into a successful draft.
        assertEquals("input_not_dispatched",
            LuluVirtualTextInputPolicy.stage(false, true))
    }

    @Test fun focusedDraftProofNeverReadsFromInvalidOrOwnerDisplay() {
        assertFalse(LuluAccessibilityService.focusedVirtualDraftMatches(
            0, "com.tencent.mm", "要输入的文字"))
        assertFalse(LuluAccessibilityService.focusedVirtualDraftMatches(
            -1, "com.tencent.mm", "要输入的文字"))
        assertFalse(LuluAccessibilityService.focusedVirtualDraftMatches(
            7, "", "要输入的文字"))
        assertFalse(LuluAccessibilityService.focusedVirtualDraftMatches(
            7, "com.tencent.mm", ""))
    }

    @Test fun ocrCannotAssembleDraftFromSeparateChatBubbles() {
        val separate = listOf(
            LuluVirtualTextReadbackOcr.TextLine("你好", 100, 820, 300, 850),
            LuluVirtualTextReadbackOcr.TextLine("世界", 100, 950, 300, 980),
        )
        assertFalse(LuluVirtualTextReadbackOcr.exactNearField(
            separate, "你好世界", 720, 1280, 280, 900))
        val wrapped = listOf(
            LuluVirtualTextReadbackOcr.TextLine("你好", 100, 850, 300, 880),
            LuluVirtualTextReadbackOcr.TextLine("世界", 100, 884, 300, 914),
        )
        assertTrue(LuluVirtualTextReadbackOcr.exactNearField(
            wrapped, "你好世界", 720, 1280, 280, 900))
    }

    @Test fun wrappedDraftCanBeVerifiedBesideUnrelatedNearbyOcr() {
        val lines = listOf(
            LuluVirtualTextReadbackOcr.TextLine("聊天记录", 100, 760, 300, 790),
            LuluVirtualTextReadbackOcr.TextLine("你好", 100, 850, 300, 880),
            LuluVirtualTextReadbackOcr.TextLine("世界", 100, 884, 300, 914),
            LuluVirtualTextReadbackOcr.TextLine("发送", 550, 900, 650, 930),
        )
        assertTrue(LuluVirtualTextReadbackOcr.exactNearField(
            lines, "你好世界", 720, 1280, 280, 900))
        assertFalse(LuluVirtualTextReadbackOcr.exactNearField(
            listOf(lines[0], lines[2]), "聊天记录世界", 720, 1280, 280, 900))
    }

    @Test fun pixelReadbackMustMatchNearSelectedFieldNotOldTextElsewhere() {
        val oldMessage = LuluVirtualTextReadbackOcr.TextLine("打字成功",
            300, 250, 690, 300)
        val editor = LuluVirtualTextReadbackOcr.TextLine("打字成功",
            85, 910, 400, 950)
        assertFalse(LuluVirtualTextReadbackOcr.exactNearField(
            listOf(oldMessage), "打字成功", 720, 1280, 280, 900))
        assertTrue(LuluVirtualTextReadbackOcr.exactNearField(
            listOf(editor), "打字成功", 720, 1280, 280, 900))
        assertFalse(LuluVirtualTextReadbackOcr.exactNearField(
            listOf(editor), "别的内容", 720, 1280, 280, 900))
    }
}
