package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuluSharedWebLinksTest {
    @Test fun preservesCaptionsAndBuildsCardsForEachDistinctLink() {
        val message = LuluSharedWebLinks.project(
            "喜欢这两篇\n[笔记](https://xhslink.cn/o/abc)\nhttps://example.com/post?a=1&b=2\nhttps://example.com/post?a=1&b=2")
        assertEquals("喜欢这两篇", message.text)
        assertEquals(listOf(
            LuluSharedWebLinks.Link("https://xhslink.cn/o/abc", "笔记"),
            LuluSharedWebLinks.Link("https://example.com/post?a=1&b=2"),
        ), message.links)
    }

    @Test fun knownShortLinksUseHttpsAndUnrelatedHostsCannotClaimXhs() {
        assertEquals("https://xhslink.com/o/abc",
            LuluSharedWebLinks.project("看看 http://xhslink.com/o/abc。").links.single().url)
        assertNull(LuluSharedWebLinks.xhsUrl("https://xhslink.com.example.org/o/abc"))
        assertNull(LuluSharedWebLinks.uri("https://user@example.org/path"))
        assertNull(LuluSharedWebLinks.uri("intent://example.org/path"))
    }

    @Test fun genericCopyRequiresFreshSourceOrKnownFirstPartyLink() {
        val xhs = LuluSharedWebLinks.XHS_PACKAGE
        val browser = "com.android.chrome"
        val note = "https://xhslink.cn/o/abc"
        val web = "https://example.com/article?id=123"
        fun copy(app: String, source: String?, link: String, at: Long = 1300) =
            LuluShoppingLinkShare.sharedCopyLink(app, 1000, 1200, at, source, link, merchantOnly = false)

        assertEquals(note, copy(xhs, null, "喜欢这篇  $note"))
        assertEquals(web, copy(browser, browser, web))
        assertNull(copy(browser, null, web))
        assertNull(copy(xhs, browser, note))
        assertNull(copy(xhs, xhs, note, at = 1100))
    }
}
