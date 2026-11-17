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

    @Test fun noBundledAppearancePacksRemain() {
        assertEquals(listOf("paper"), LuluAppearanceCatalog.builtIns.map { it.id })
    }
}
