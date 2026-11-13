package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The character has one chat app like the owner's, not an icon for every QQ tab. */
class RolePhoneAppPlacementTest {
    @Test fun chatAppKeepsTheFourTabsFoundInTheOwnersChatApp() {
        assertEquals(listOf("消息", "好友", "朋友圈", "我的"), RolePhoneChatTabs)
    }

    @Test fun momentsFavoritesAndProfileAreNotStandalonePhoneApps() {
        val desktop = RolePhoneDesktopRoutes
        assertTrue("messages entry", "messages" in desktop)
        assertTrue("photo album remains independent", "album" in desktop)
        assertTrue("owner can inspect real captured frames", "observations" in desktop)
        assertTrue("browser remains independent", "browser" in desktop)
        assertTrue("memo pad remains independent", "memos" in desktop)
        assertTrue("real virtual screen remains independent", "screen" in desktop)
        assertFalse("moments must be under chat", "moments" in desktop)
        assertFalse("favorites must be under chat My", "favorites" in desktop)
        assertFalse("QQ My must not be duplicated", "profile" in desktop)
        assertEquals(8, desktop.size)
    }
}
