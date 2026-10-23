package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class XhsReadingReceiptTest {
    private fun event(content: String, channel: String = "小红书阅读") =
        SharedTimelineEvent(
            id = "xhs-read-sample", characterId = "role-a", channel = channel,
            speaker = "外部平台", content = content,
            occurredAt = Instant.parse("2026-10-23T07:00:00Z"),
            source = "xiaohongshu-mcp", evidenceKind = EventEvidenceKind.Observation,
        )

    @Test fun actualEvidenceCountsStayDistinct() {
        val raw = JSONObject().put("title", "一篇真实笔记").put("text", "正文")
            .put("bodyActuallyLoaded", true)
            .put("commentCountActuallyLoaded", 6)
            .put("imageCount", 5).put("imageDownloadedCount", 3).put("imageAnalyzedCount", 2)
        val row = XhsReadingReceipt.from(event(raw.toString()))!!
        assertEquals("正文已读", row.bodyLabel)
        assertEquals("评论 6 条", row.commentsLabel)
        assertEquals("视觉 2/5", row.imagesLabel)
        assertEquals(3, row.imagesDownloaded)
    }

    @Test fun oldRecordsDoNotInventVisionOrComments() {
        val raw = JSONObject().put("title", "旧笔记").put("text", "旧正文")
            .put("imageCount", 4).put("imageReadingStatus", "图片链接4张")
        val row = XhsReadingReceipt.from(event(raw.toString()))!!
        assertEquals("评论未记录", row.commentsLabel)
        assertEquals("图片 4 张", row.imagesLabel)
        assertNull(row.imagesAnalyzed)
    }

    @Test fun failedImageOnlyOrNonReadingEventCannotPretendSuccess() {
        val raw = JSONObject().put("title", "只有图片链接").put("text", "")
            .put("bodyActuallyLoaded", false).put("commentCountActuallyLoaded", 0)
            .put("imageCount", 2).put("imageDownloadedCount", 0)
            .put("imageAnalyzedCount", 0)
        assertEquals("正文未核验", XhsReadingReceipt.from(event(raw.toString()))!!.bodyLabel)
        assertEquals("视觉 0/2", XhsReadingReceipt.from(event(raw.toString()))!!.imagesLabel)
        assertNull(XhsReadingReceipt.from(event(raw.toString(), "私聊")))
        assertNull(XhsReadingReceipt.from(event("not json")))
    }
}
