package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluAppearanceCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPearlStyleTest {
    @Test fun originalUnmodifiedHomeUsesPearlMaterial() {
        val original = LuluAppearanceCatalog.builtIns.first()
        assertTrue(desktopUsesPearlStyle(original, emptyMap()))
    }

    @Test fun userPaletteAndDarkThemeAreNeverOverridden() {
        val original = LuluAppearanceCatalog.builtIns.first()
        assertFalse(desktopUsesPearlStyle(original, mapOf("--paper" to "#18202A")))
        assertFalse(desktopUsesPearlStyle(original, mapOf("--card" to "#EEF5FC")))
        assertFalse(desktopUsesPearlStyle(original.copy(dark = true), emptyMap()))
        assertFalse(desktopUsesPearlStyle(original.copy(id = "my-skin", builtIn = false), emptyMap()))
    }
}
