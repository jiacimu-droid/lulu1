package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class XhsSharedLinkResolverTest {
    private val id = "6777abcdef1234567890abcd"

    @Test fun signedExploreLinkKeepsExactIdAndToken() {
        val link = XhsSharedLinkResolver.parseDirect(
            "https://www.xiaohongshu.com/explore/$id?xsec_token=abc%2Bdef&xsec_source=pc_share",
        )!!
        assertEquals(id, link.id)
        assertEquals("abc+def", link.token)
        assertEquals("pc_share", link.source)
    }

    @Test fun unsigedDiscoveryLinkCanBeOpenedOnlyByForegroundReader() {
        val link = XhsSharedLinkResolver.parseDirect(
            "https://www.xiaohongshu.com/discovery/item/$id",
        )!!
        assertEquals(id, link.id)
        assertEquals("", link.token)
        assertEquals("pc_share", link.source)
    }

    @Test fun shortLinkNeedsResolutionNotKeywordSearch() {
        assertNull(XhsSharedLinkResolver.parseDirect("https://xhslink.com/abc123"))
    }

    @Test fun rejectThirdPartyUserInfoAndUnsafeScheme() {
        listOf(
            "http://www.xiaohongshu.com/explore/$id",
            "https://www.xiaohongshu.com.evil.example/explore/$id",
            "https://evil@www.xiaohongshu.com/explore/$id",
            "https://www.xiaohongshu.com:8080/explore/$id",
            "https://127.0.0.1/explore/$id",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                XhsSharedLinkResolver.parseDirect(raw)
            }
        }
    }

    @Test fun invalidPageIsNotPassedOffAsAnActualNote() {
        assertNull(XhsSharedLinkResolver.parseDirect(
            "https://www.xiaohongshu.com/search_result?keyword=cat",
        ))
        assertNull(XhsSharedLinkResolver.parseDirect(
            "https://www.xiaohongshu.com/explore/not-a-note",
        ))
    }
}
