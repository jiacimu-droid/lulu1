package com.jiacimu.lulu.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ShortVideoMobileReadingTest {
    private val clip = ShortVideoMobileReading.Clip(
        "douyin", "test-001", "https://www.douyin.com/video/test-001", "测试视频")

    private class FakeSource(
        val parts: List<ShortVideoMobileReading.Segment>,
    ) : ShortVideoMobileReading.Source {
        var calls = 0
        override suspend fun discover(query: String?, cursor: String?, limit: Int) =
            ShortVideoMobileReading.DiscoveryPage(listOf(
                ShortVideoMobileReading.Clip("douyin", "test-001",
                    "https://www.douyin.com/video/test-001")), "next-feed")
        override suspend fun read(clip: ShortVideoMobileReading.Clip,
                                  cursor: String?, limits: ShortVideoMobileReading.Limits):
            ShortVideoMobileReading.Segment = parts[calls++]
    }
    private val interpreter = object : ShortVideoMobileReading.Interpreter {
        override suspend fun interpret(clip: ShortVideoMobileReading.Clip,
                                       segment: ShortVideoMobileReading.Segment) =
            "基于实际取得的片段作出的简短理解"
    }
    private fun part(a: Long, b: Long, cursor: String?, done: Boolean = false) =
        ShortVideoMobileReading.Segment(a, b, speechTranscript = "确实识别到一段话",
            nextCursor = cursor, endOfVideo = done)

    @Test fun metadataIsNeverMisreportedAsHavingWatchedAVideo() = runBlocking {
        val source = FakeSource(listOf(ShortVideoMobileReading.Segment(
            0, 3_000, nextCursor = "next")))
        val result = ShortVideoMobileReading.readSelected(source, interpreter, clip)
        assertEquals(ShortVideoMobileReading.State.NO_EVIDENCE, result.state)
        assertTrue(result.receipts.isEmpty())
        assertEquals("null", result.nextCursor.toString())
    }

    @Test fun longVideoResumesWithoutAutoplayOrLoadingAllSegments() = runBlocking {
        val source = FakeSource(listOf(
            part(0, 3_000, "chunk-1"), part(3_000, 6_000, "chunk-2"),
            part(6_000, 9_000, null, done = true)))
        val receipts = mutableListOf<ShortVideoMobileReading.Receipt>()
        val first = ShortVideoMobileReading.readSelected(source, interpreter, clip,
            onReceipt = { receipts.add(it) })
        assertEquals(2, source.calls)
        assertEquals(ShortVideoMobileReading.State.PARTIAL, first.state)
        assertEquals("chunk-2", first.nextCursor)
        assertFalse(first.receipts.any { it.completeVideo })
        val second = ShortVideoMobileReading.readSelected(source, interpreter, clip,
            startCursor = first.nextCursor, onReceipt = { receipts.add(it) })
        assertEquals(3, source.calls)
        assertEquals(ShortVideoMobileReading.State.FINISHED, second.state)
        assertEquals(3, receipts.size)
        assertTrue(receipts.last().completeVideo)
    }

    @Test fun excessiveMediaIsRejectedBeforeInterpretationAndNoFakeReceipt() = runBlocking {
        val tooLarge = ShortVideoMobileReading.Segment(
            0, 3_000, frame = ShortVideoMobileReading.Frame(500, ByteArray(190_000)),
            nextCursor = "next")
        val source = FakeSource(listOf(tooLarge))
        var analyses = 0
        val analyzer = object : ShortVideoMobileReading.Interpreter {
            override suspend fun interpret(clip: ShortVideoMobileReading.Clip,
                                           segment: ShortVideoMobileReading.Segment): String {
                analyses++
                return "不应该发生"
            }
        }
        val result = ShortVideoMobileReading.readSelected(source, analyzer, clip)
        assertEquals(ShortVideoMobileReading.State.FAILED, result.state)
        assertEquals(0, analyses)
        assertTrue(result.receipts.isEmpty())
    }

    @Test fun failureRetainsLastVerifiedCursorWithoutClaimingFullView() = runBlocking {
        val source = FakeSource(listOf(
            part(0, 2_000, "good"),
            ShortVideoMobileReading.Segment(2_000, 4_000,
                speechTranscript = "有文字但游标未前进", nextCursor = "good")))
        val seen = mutableListOf<ShortVideoMobileReading.Receipt>()
        val result = ShortVideoMobileReading.readSelected(source, interpreter, clip,
            onReceipt = { seen.add(it) })
        assertEquals(ShortVideoMobileReading.State.FAILED, result.state)
        assertEquals(1, result.receipts.size)
        assertEquals(1, seen.size)
        assertEquals("good", result.nextCursor)
    }

    @Test fun receiptContainsOnlyEvidenceRefsNotRawMedia() = runBlocking {
        val sample = ShortVideoMobileReading.Segment(0, 3_000,
            frame = ShortVideoMobileReading.Frame(1200, byteArrayOf(1, 2, 3)),
            speechTranscript = "她说了一句你好",
            comments = listOf(ShortVideoMobileReading.Comment("comment-1", "真有趣")),
            endOfVideo = true)
        val result = ShortVideoMobileReading.readSelected(FakeSource(listOf(sample)), interpreter, clip)
        val json = result.receipts.single().timelineJson().toString()
        assertEquals(ShortVideoMobileReading.State.FINISHED, result.state)
        assertTrue(json.contains("visual"))
        assertTrue(json.contains("speech"))
        assertTrue(json.contains("comment-1"))
        assertFalse(json.contains("base64"))
        assertFalse(json.contains("audioBytes"))
        assertTrue(result.receipts.single().completeVideo)
    }

    @Test fun pausedSessionDoesNotReadOrFabricateReceipt() = runBlocking {
        val source = FakeSource(listOf(part(0, 2_000, "next")))
        val result = ShortVideoMobileReading.readSelected(source, interpreter, clip,
            mayContinue = { false })
        assertEquals(ShortVideoMobileReading.State.PAUSED, result.state)
        assertEquals(0, source.calls)
        assertTrue(result.receipts.isEmpty())
    }

    @Test fun discoveryPageBoundedAndDoesNotClaimVideosWereOpened() = runBlocking {
        val source = FakeSource(emptyList())
        val page = ShortVideoMobileReading.discover(source, "小猫")
        assertEquals(1, page.items.size)
        assertEquals("test-001", page.items[0].id)
        assertFalse(page.items[0].title.contains("已看"))
    }
}
