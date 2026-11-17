package com.jiacimu.lulu.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class ReadingTxtLibraryTest {
    @Test fun decodesChineseUtf8WithAndWithoutBom() {
        val sample = "第一章 海边的信\n窗外下着雨。"
        val bytes = sample.toByteArray(StandardCharsets.UTF_8)
        assertEquals(sample, ReadingTxtLibrary.decodeText(bytes))
        assertEquals(sample, ReadingTxtLibrary.decodeText(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes))
    }

    @Test fun decodesUtf16AndCommonChineseGb18030() {
        val sample = "长夜微光，读一封来信。"
        assertEquals(sample, ReadingTxtLibrary.decodeText(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                sample.toByteArray(StandardCharsets.UTF_16LE)))
        assertEquals(sample, ReadingTxtLibrary.decodeText(
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
                sample.toByteArray(StandardCharsets.UTF_16BE)))
        assertEquals(sample, ReadingTxtLibrary.decodeText(
            sample.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun longNovelIsRenderedInBoundedPassagesWithoutLosingText() {
        val content = "序言\n" + ("第一章 相遇\n" + "云端的房间里有盏灯。\n".repeat(350)) +
            ("第二章 归来\n" + "窗边又多了一封信。\n".repeat(350))
        val book = BackgroundReadingBook("txt-test", "两章", content, "用户上传")
        val passages = readingPassages(book)
        assertTrue(passages.size > 3)
        assertTrue(passages.all { it.content.length <= 1800 })
        assertEquals(content, passages.joinToString("") { it.content })
        assertTrue(passages.any { it.firstInChapter && it.title.contains("第二章") })
    }
}
