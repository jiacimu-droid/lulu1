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

    @Test fun desktopClockCanUseIndependentInkAndAnOptionalFrame() {
        val sheet = LuluCssSheet.parse("""
            ${LuluCssTargets.widgetSelector("clock")} {
                color: #292929; font-size: 38sp; background-color: transparent; border-width: 0dp;
            }
            ${LuluCssTargets.widgetSelector("calendar")} { border-color: #ddd; border-width: 1dp; }
        """.trimIndent())
        assertTrue(sheet.errors.isEmpty())
        assertTrue(sheet.warnings.isEmpty())
        val clock = sheet.element("Home", "widget-clock")
        assertEquals("38sp", clock["font-size"])
        assertEquals("0dp", clock["border-width"])
        assertEquals(0L, LuluCssSheet.parseColor(clock["background-color"]))
        assertEquals("1dp", sheet.element("Home", "widget-calendar")["border-width"])
        assertTrue(sheet.element("Chat", "widget-clock").isEmpty())
    }

    @Test fun nestedListeningRoomInheritsChatAndOverridesOnlyItsOwnSkin() {
        val token = "asset://123e4567-e89b-12d3-a456-426614174000.png"
        val sheet = LuluCssSheet.parse("""
            #Chat { --accent: #ab3456; --paper: #faf0ee; }
            #ChatDetail { --ink: #332b30; }
            #ListenTogether { --paper: #e0f0ff; }
            #ListenTogether .music-room { background-image: url("$token"); }
            #ListenTogether .music-mini { background-image: url("$token"); }
            #ListenTogether .music-mini-compact { background-color: #faf8fa; }
            #ListenTogether .music-mini-hidden { background-color: #aa6688; }
            #ListenTogether .music-vinyl { width: 110dp; background-image: url("$token"); }
            #ListenTogether .music-avatar-role { width: 62dp; }
            #ListenTogether .music-links { color: #f59ca5; border-width: 6dp; }
            #ListenTogether .music-play { background-color: #975588; }
            #MusicLibrary .music-playlist-card { background-color: #fff9fa; }
            #WatchTogether .watch-player { background-color: #222244; }
        """.trimIndent())
        assertTrue(sheet.errors.toString(), sheet.errors.isEmpty())
        assertTrue(sheet.warnings.toString(), sheet.warnings.isEmpty())
        assertEquals(0xFFAB3456L, sheet.profile("ListenTogether", base).palette.accent)
        assertEquals(0xFFE0F0FFL, sheet.profile("ListenTogether", base).palette.paper)
        assertEquals(0xFFFAF0EEL, sheet.profile("WatchTogether", base).palette.paper)
        assertEquals(token, LuluCssSheet.assetToken(
            sheet.element("ListenTogether", "music-mini")["background-image"]))
        assertEquals("#faf8fa", sheet.element("ListenTogether", "music-mini-compact")["background-color"])
        assertEquals("#aa6688", sheet.element("ListenTogether", "music-mini-hidden")["background-color"])
        assertEquals("#fff9fa", sheet.element("MusicLibrary", "music-playlist-card")["background-color"])
        assertEquals(0xFFAB3456L, sheet.profile("MusicLibrary", base).palette.accent)
        assertEquals("110dp", sheet.element("ListenTogether", "music-vinyl")["width"])
        assertEquals(token, LuluCssSheet.assetToken(
            sheet.element("ListenTogether", "music-vinyl")["background-image"]))
        assertEquals("6dp", sheet.element("ListenTogether", "music-links")["border-width"])
        assertEquals("#975588", sheet.element("ListenTogether", "music-play")["background-color"])
        assertTrue(sheet.element("WatchTogether", "music-vinyl").isEmpty())
        assertEquals("#222244", sheet.element("WatchTogether", "watch-player")["background-color"])
        assertTrue(sheet.element("ChatDetail", "watch-player").isEmpty())
    }

    @Test fun nestedMusicSelectorsCannotLeakIntoUnrelatedAppScreens() {
        val sheet = LuluCssSheet.parse("""
            #ListenTogether .music-track-title { color: #334455; font-size: 22sp; }
            #ListenTogether .music-avatar-user { width: 60dp; }
            #WatchTogether .watch-chat { padding: 12dp; }
        """.trimIndent())
        assertTrue(sheet.warnings.toString(), sheet.warnings.isEmpty())
        assertEquals("22sp", sheet.element("ListenTogether", "music-track-title")["font-size"])
        assertEquals("60dp", sheet.element("ListenTogether", "music-avatar-user")["width"])
        assertTrue(sheet.element("Chat", "music-avatar-user").isEmpty())
        assertEquals("12dp", sheet.element("WatchTogether", "watch-chat")["padding"])
    }

    @Test fun bubbleDecorationsFollowScopeAndSenderWithoutOverwritingTheBubble() {
        val token = "asset://123e4567-e89b-12d3-a456-426614174000.png"
        val sheet = LuluCssSheet.parse("""
            #Chat .bubble { background-color: #fff; }
            #Chat .bubble::before { background-color: #fea; }
            #ChatDetail .bubble-role::after {
                content: ""; background-image: url("$token"); position: absolute;
                right: -6dp; top: -4dp; width: 32dp; height: 24dp; rotate: -12deg; opacity: .8;
            }
            #GroupChat .bubble-role::after { content: none; }
        """.trimIndent())
        assertTrue(sheet.errors.toString(), sheet.errors.isEmpty())
        assertTrue(sheet.warnings.toString(), sheet.warnings.isEmpty())
        val role = sheet.element("ChatDetail", "bubble", "bubble-role")
        assertEquals("#fff", role["background-color"])
        assertEquals("#fea", role["before/background-color"])
        assertEquals(token, LuluCssSheet.assetToken(role["after/background-image"]))
        assertEquals("-6dp", role["after/right"])
        assertEquals("-4dp", role["after/top"])
        assertEquals("none", sheet.element("GroupChat", "bubble", "bubble-role")["after/content"])
        assertNull(sheet.element("ChatDetail", "bubble", "bubble-user")["after/background-image"])
        assertTrue(sheet.element("Settings", "bubble", "bubble-role").isEmpty())
    }

    @Test fun radiusAndBorderShorthandsRespectOverrides() {
        val sheet = LuluCssSheet.parse("""
            .bubble { border: 2dp solid #aec; border-radius: 5dp 18dp 14dp; }
            #ChatDetail .bubble-role { border: none; border-top-left-radius: 7dp; }
            #GroupChat .bubble-role { border-radius: 12dp; }
        """.trimIndent())
        assertTrue(sheet.errors.toString(), sheet.errors.isEmpty())
        val style = sheet.element("ChatDetail", "bubble", "bubble-role")
        assertEquals("0dp", style["border-width"])
        assertEquals(listOf("7dp", "18dp", "14dp", "18dp"), LuluCssSheet.CORNERS.map { style[it] })
        val group = sheet.element("GroupChat", "bubble", "bubble-role")
        assertEquals(List(4) { "12dp" }, LuluCssSheet.CORNERS.map { group[it] })
    }

    @Test fun gradientsAndShadowsAcceptFiniteValuesAndRejectRemoteLayerImages() {
        val sheet = LuluCssSheet.parse("""
            #ChatDetail .bubble {
                background-image: linear-gradient(135deg, #fff, #faa8);
                box-shadow: 0 3dp 10dp #11223380;
                background-position: top right;
            }
        """.trimIndent())
        assertTrue(sheet.errors.toString(), sheet.errors.isEmpty())
        val style = sheet.element("ChatDetail", "bubble")
        val gradient = LuluCssSheet.gradient(style["background-image"])
        assertEquals(135f, gradient?.angle)
        assertEquals(listOf(0xFFFFFFFFL, 0x88FFAAAAL), gradient?.colors)
        val shadow = LuluCssSheet.shadow(style["box-shadow"])
        assertEquals(10f, shadow?.blur)
        assertEquals(0x80112233L, shadow?.color)
        assertTrue(LuluCssSheet.parse(".bubble::after { background-image: url(https://example.org/ribbon.png); }").errors.isNotEmpty())
        assertTrue(LuluCssSheet.parse(".bubble { box-shadow: 0 2dp -1dp #000; }").errors.isNotEmpty())
        assertTrue(LuluCssSheet.parse(".bubble { background-image: linear-gradient(90deg, #fff); }").errors.isNotEmpty())
        assertTrue(LuluCssSheet.parse(".bubble::before { opacity: 2; }").errors.isNotEmpty())
        assertTrue(LuluCssSheet.parse(".bubble::active { background-color: #fff; }").warnings.isNotEmpty())
    }

    @Test fun noBundledAppearancePacksRemain() {
        assertEquals(listOf("paper"), LuluAppearanceCatalog.builtIns.map { it.id })
    }
}
