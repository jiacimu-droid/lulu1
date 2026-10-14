package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class QqXhsShareCodecTest {
    @Test fun externalShareHasReadablePreviewButNoUnverifiedArticle() {
        val note = externalXhsSharedNote(
            "橘子的旅行照片，好好看！ https://www.xiaohongshu.com/explore/6777abcdef1234567890abcd?xsec_token=abc"
        )!!
        assertEquals("橘子的旅行照片，好好看！", note.title)
        assertFalse(note.verified)
        val content = encodeQqXhsSharedNote(note)
        val restored = decodeQqXhsSharedNote(content)!!
        assertEquals(note.title, restored.title)
        assertFalse(restored.verified)
        val context = qqXhsContextText(content)!!
        assertTrue(context.contains("尚未实际读取"))
        assertFalse(context.contains("真实提取的正文"))
        assertFalse(context.contains("⟪XHS_NOTE:"))
    }

    @Test fun readArticleSharesFullContentWithoutShowingRawTokenToModel() {
        val source = QqXhsSharedNote(
            title = "一只猫的日常", url = "https://www.xiaohongshu.com/explore/6777abcdef1234567890abcd?xsec_source=pc_share",
            author = "橘子", coverUrl = "https://sns-webpic-qc.xhscdn.com/abc",
            body = "今天猫咪趴在窗台晒太阳。", visualReading = "图中有猫咪和一扇窗。",
            comments = listOf("可爱！", "好想摸摸。"), verified = true,
        )
        val raw = encodeQqXhsSharedNote(source)
        val restored = decodeQqXhsSharedNote(raw)!!
        assertEquals(source.title, restored.title)
        assertEquals(source.coverUrl, restored.coverUrl)
        assertTrue(restored.verified)
        assertEquals(source.comments, restored.comments)
        val context = qqForwardContextText(raw)
        assertTrue(context.contains("今天猫咪趴在窗台"))
        assertTrue(context.contains("图中有猫咪"))
        assertTrue(context.contains("可爱"))
        assertFalse(context.contains("⟪XHS_NOTE:"))
    }

    @Test fun legacyLongBubbleBecomesACompactNote() {
        val legacy = "分享给你一篇小红书笔记：\n" +
            "标题：猫咪今天好乖\n作者：小橘\n" +
            "原链接：https://www.xiaohongshu.com/explore/6777abcdef1234567890abcd\n" +
            "帖子原文：\n猫咪在窗边睡觉。\n"
        val note = decodeQqXhsSharedNote(legacy)!!
        assertTrue(note.verified)
        assertEquals("猫咪今天好乖", note.title)
        assertTrue(note.body.contains("猫咪在窗边"))
    }

    @Test fun foreignDomainCannotBePresentedAsXiaohongshuShare() {
        assertNull(externalXhsSharedNote("https://example.com/not-xiaohongshu"))
        assertNull(externalXhsSharedNote("https://www.xiaohongshu.com.evil.tld/explore/abc"))
        assertNull(decodeQqXhsSharedNote("随便一句普通聊天，不是分享"))
    }

    @Test fun externalShortLinkIsAcceptedWithoutFakingVision() {
        val note = externalXhsSharedNote("看看这个！ https://xhslink.com/abcde")!!
        assertFalse(note.verified)
        assertEquals("https://xhslink.com/abcde", note.url)
        assertTrue(qqXhsContextText(encodeQqXhsSharedNote(note))!!.contains("只有用户分享"))
    }
}
