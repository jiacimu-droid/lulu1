package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluAppearanceCatalog
import com.jiacimu.lulu.data.LuluCssSheet
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPaletteTest {
    @Test fun desktopDefaultsToWhiteAndNeutralGray() {
        val original = LuluAppearanceCatalog.builtIns.first()
        val home = LuluCssSheet.parse("").profile("Home", original)
        assertEquals(0xFFFFFFFFL, home.palette.paper)
        assertEquals(0xFFF4F4F4L, home.palette.cardStrong)
        assertEquals(0xFFE7E7E7L, home.palette.border)
    }

    @Test fun aCustomDesktopKeepsItsOwnColors() {
        val original = LuluAppearanceCatalog.builtIns.first()
        val sheet = LuluCssSheet.parse("#Home { --paper: #192129; --card: #24313a; }")
        val home = sheet.profile("Home", original)
        assertEquals(0xFF192129L, home.palette.paper)
        assertEquals(0xFF24313AL, home.palette.card)
        assertEquals(original, sheet.profile("Chat", original))
    }
}
