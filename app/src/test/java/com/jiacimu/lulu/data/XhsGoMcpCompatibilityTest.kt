package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class XhsGoMcpCompatibilityTest {
    private val id = "1234567890abcdef12345678"
    @Test fun upstreamGoSearchFeedsRetainsExactDetailToken() {
        val result = JSONObject("""{
          "feeds":[{"id":"1234567890abcdef12345678","xsecToken":"A+/==",
          "modelType":"note","noteCard":{"displayTitle":"猫咪奇闻",
          "user":{"nickname":"小红书真实作者"}}}],"count":1}""")
        val cards = XhsReadingEngine.parseMcpCards(result, "猫咪")
        assertEquals(1, cards.size)
        assertEquals(id, cards.single().id)
        assertEquals("A+/==", cards.single().token)
        assertEquals("猫咪奇闻", cards.single().title)
        assertEquals("小红书真实作者", cards.single().author)
        assertEquals("mcp", cards.single().provider)
    }

    @Test fun upstreamGoDetailKeepsNoteTextImagesAndActuallyLoadedComments() {
        val response = JSONObject("""{
          "feed_id":"1234567890abcdef12345678",
          "data":{"note":{"noteId":"1234567890abcdef12345678",
            "title":"真的读过这篇","desc":"这是来自真实帖子的正文，不是搜索摘要。",
            "user":{"nickname":"原作者"},
            "imageList":[{"urlDefault":"https://example.org/a.jpg"}]},
            "comments":{"list":[{"content":"评论一"},{"content":"评论二"}],"hasMore":true}}
        }""")
        val note = XhsReadingEngine.normalizeMcpNote(
            response, XhsCard(id,"A+/==","原标题","","pc_search","mcp"),
        )
        assertEquals("真的读过这篇", note.optString("title"))
        assertEquals("这是来自真实帖子的正文，不是搜索摘要。", note.optString("body"))
        assertEquals(1, note.optJSONArray("images")!!.length())
        assertEquals("原作者", note.optString("author"))
        val comments = note.optJSONArray("comments")!!
        assertEquals(2, comments.length())
        assertEquals("评论一", comments.optString(0))
    }

    @Test fun unverifiedDifferentNoteMustNotCountAsActualRead() {
        val incorrect = JSONObject("""{
          "data":{"note":{"noteId":"abcdefabcdefabcdefabcdef","desc":"另一篇笔记"}}}""")
        try {
            XhsReadingEngine.normalizeMcpNote(
                incorrect, XhsCard(id,"A+/==","原标题","","pc_search","mcp"),
            )
            fail("another note must not be recorded as the requested one")
        } catch (_: IllegalArgumentException) { }
    }
}
