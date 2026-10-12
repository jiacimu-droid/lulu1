package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ReadingChatReceiptFormatterTest {
    @Test fun compactReceiptOnlyLinksCallToActionNotEntireReflection() {
        val raw = ReadingChatReceiptFormatter.create(
            recordId = "read-001",
            bookTitle = "春天在屋顶上",
            chapterTitle = "第4章 远方",
            reflection = "我觉得这一章的对话有点好笑，也想知道他们会怎么选择。\n第二段很有趣。",
        )
        val preview = ReadingChatReceiptFormatter.preview("[共同活动] $raw")!!
        assertEquals("read-001", preview.recordId)
        assertTrue(preview.text.contains("《春天在屋顶上》"))
        assertTrue(preview.text.contains("第4章"))
        assertTrue(preview.text.contains("第二段很有趣。"))
        assertEquals("查看感想", preview.text.substring(preview.linkStart, preview.linkEnd))
        assertTrue(preview.linkStart > 0)
        assertEquals(1, ReadingChatReceiptFormatter.consecutiveCount(raw))
    }

    @Test fun legacyVerboseReceiptHidesDuplicateBookHeadingsAndShelfTrace() {
        val legacy = "[共同活动] [阅读记录|historical-id]已读《《旧小说》·第5章 夜雨》 · 《旧小说》·第5章 夜雨（2749/2749）\n" +
            "江渡走到了原木书架旁，准备挑一本书。\n读后感：这章让我想起了雨夜的等待。\n点开查看完整感想和正文"
        val compact = ReadingChatReceiptFormatter.preview(legacy)!!
        assertEquals("historical-id", compact.recordId)
        assertTrue(compact.text.contains("《旧小说》 · 第5章"))
        assertEquals(1, compact.text.split("《旧小说》").size - 1)
        assertFalse(compact.text.contains("原木书架"))
        assertFalse(compact.text.contains("2749"))
        assertFalse(compact.text.contains("点开查看完整感想和正文"))
        assertTrue(compact.text.length < legacy.length / 2)
    }

    @Test fun successiveChaptersStayIndependentInStorageButShareOneProgressNotice() {
        val latest = ReadingChatReceiptFormatter.create("record-2", "同一本书", "第2章",
            "后面这段让我改了主意。", consecutiveReads = 2)
        assertTrue(ReadingChatReceiptFormatter.isReadingNotice("[共同活动] $latest"))
        assertEquals(2, ReadingChatReceiptFormatter.consecutiveCount("[共同活动] $latest"))
        val preview = ReadingChatReceiptFormatter.preview(latest)!!
        assertTrue(preview.text.startsWith("连读2段"))
        assertTrue(preview.text.contains("第2章"))
        assertEquals("查看感想", preview.text.substring(preview.linkStart, preview.linkEnd))
    }
}
