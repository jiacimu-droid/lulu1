package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluCssRuntimeTest {
    private val base = LuluAppearanceCatalog.builtIns.first()

    @Test fun scopeInheritsGlobalWithoutLeakingIntoSiblings() {
        val sheet = LuluCssSheet.parse("""
            :root { --accent: #bc3355; --paper: #fdf1e2; }
            #Chat { --paper: #242124; color-scheme: dark; }
        """.trimIndent())
        assertTrue(sheet.errors.isEmpty())
        assertEquals(0xFFBC3355L, sheet.profile("Home", base).palette.accent)
        assertEquals(0xFFFDF1E2L, sheet.profile("Home", base).palette.paper)
        assertEquals(0xFF242124L, sheet.profile("Chat", base).palette.paper)
        assertTrue(sheet.profile("Chat", base).dark)
        assertFalse(sheet.profile("Home", base).dark)
    }

    @Test fun componentOffsetIsScopedToNamedNativeHook() {
        val sheet = LuluCssSheet.parse("""
            #Home .launcher { offset-x: 24dp; offset-y: -12dp; }
            #Chat .bubble { offset-y: 7dp; }
        """.trimIndent())
        assertEquals("24dp", sheet.element("Home", "launcher")["offset-x"])
        assertEquals("-12dp", sheet.element("Home", "launcher")["offset-y"])
        assertNull(sheet.element("Chat", "launcher")["offset-x"])
        assertEquals("7dp", sheet.element("Chat", "bubble")["offset-y"])
    }

    @Test fun importedImageOnlyAcceptsLocalToken() {
        val token = "asset://123e4567-e89b-12d3-a456-426614174000.png"
        val valid = LuluCssSheet.parse("#Home { background-image: url(\"" + token + "\"); }")
        assertTrue(valid.errors.isEmpty())
        assertEquals(token, valid.imageToken("Home"))
        val remote = LuluCssSheet.parse("#Home { background-image: url(https://example.org/a.png); }")
        assertTrue(remote.errors.isNotEmpty())
        assertNull(remote.imageToken("Home"))
    }

    @Test fun malformedCssCannotBeAppliedSilently() {
        assertTrue(LuluCssSheet.parse("#Home { --paper: #zzzzzz; }").errors.isNotEmpty())
        assertTrue(LuluCssSheet.parse("#Home { --accent: #ffffff;").errors.isNotEmpty())
        assertTrue(LuluCssSheet.parse("x".repeat(LuluCssSheet.MAX_LENGTH + 1)).errors.isNotEmpty())
    }

    @Test fun cssAlphaUsesTrailingRgbaByte() {
        assertEquals(0x80112233L, LuluCssSheet.parseColor("#11223380"))
        assertEquals(0xFF112233L, LuluCssSheet.parseColor("#112233"))
    }

    @Test fun childPagesKeepOldChatStyleAndCanMoveTheirOwnControls() {
        val sheet = LuluCssSheet.parse("""
            :root { --paper: #fff; }
            #Chat { background-color: #eee; }
            #Chat .bubble { offset-y: 4dp; }
            #ChatDetail .bubble { top: 8%; }
            #GroupChat .composer-send { offset-x: 12dp; width: 64dp; }
            #GroupChat .footer { dock: top; }
        """.trimIndent())
        assertTrue(sheet.errors.isEmpty())
        assertTrue(sheet.warnings.isEmpty())
        assertEquals(0xFFEEEEEEL, sheet.profile("GroupChat", base).palette.paper)
        assertEquals("4dp", sheet.element("Chat", "bubble")["offset-y"])
        assertEquals("8%", sheet.element("GroupChat", "bubble")["offset-y"])
        assertEquals("top", sheet.element("GroupChat", "footer")["dock"])
        assertEquals("64dp", sheet.element("GroupChat", "composer-send")["width"])
        assertNull(sheet.element("ChatDetail", "composer-send")["width"])
    }

    @Test fun eachDesktopCoverIsIndependentAndCanRestoreItsIcon() {
        val token = "asset://123e4567-e89b-12d3-a456-426614174000.png"
        val sheet = LuluCssSheet.parse("""
            #Home .launcher { background-image: url("$token"); }
            #Home .app-chat { background-image: none; }
            #Home .app-reading { background-size: contain; }
            #Home .widget-chat { offset-x: 16dp; }
        """.trimIndent())
        assertTrue(sheet.warnings.isEmpty())
        assertNull(LuluCssSheet.assetToken(sheet.element("Home", "launcher", "app-chat")["background-image"]))
        val reading = sheet.element("Home", "launcher", "app-reading")
        assertEquals(token, LuluCssSheet.assetToken(reading["background-image"]))
        assertEquals("contain", reading["background-size"])
    }

    @Test fun noBundledAppearancePacksRemain() {
        assertEquals(listOf("paper"), LuluAppearanceCatalog.builtIns.map { it.id })
    }
}
