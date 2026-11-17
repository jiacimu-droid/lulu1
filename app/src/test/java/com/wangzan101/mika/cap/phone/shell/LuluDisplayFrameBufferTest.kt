package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.*
import org.junit.Test

class LuluDisplayFrameBufferTest {
    private class Frame(val timestamp: Long, val black: Boolean = false) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun staticChatSurvivesObserveTitleOcrAndDraftChecks() {
        val frames = LuluDisplayFrameBuffer<Frame>()
        val chat = Frame(100)
        assertSame(chat, frames.latest { chat })
        repeat(5) { assertSame(chat, frames.latest { null }) }
        assertEquals(0, chat.closes)
        assertFalse(LuluDisplayFrameFreshness.isNewer(chat.timestamp, 100))
        frames.close()
        assertEquals(1, chat.closes)
    }

    @Test fun postActionProofStillRequiresNewProducerTimestamp() {
        val frames = LuluDisplayFrameBuffer<Frame>()
        val before = frames.latest { Frame(100) }!!
        val barrier = LuluDisplayFrameFreshness.barrier(100, before.timestamp)
        val unchanged = frames.latest { null }!!
        assertFalse(LuluDisplayFrameFreshness.accepts(unchanged.timestamp, barrier))
        val after = frames.latest { Frame(101) }!!
        assertTrue(LuluDisplayFrameFreshness.accepts(after.timestamp, barrier))
        assertEquals(1, before.closes)
        assertEquals(0, after.closes)
        frames.close()
    }

    @Test fun blackFrameReplacesPreviousReadableFrameWithoutFallingBack() {
        val frames = LuluDisplayFrameBuffer<Frame>()
        val chat = frames.latest { Frame(100) }!!
        val black = frames.latest { Frame(101, black = true) }!!
        assertSame(black, frames.latest { null })
        assertTrue(black.black)
        assertEquals(1, chat.closes)
        frames.close()
    }

    @Test fun appSwitchAndStopDiscardAllRetainedPixels() {
        val frames = LuluDisplayFrameBuffer<Frame>()
        val previousApp = frames.latest { Frame(100) }!!
        frames.close()
        assertNull(frames.latest { null })
        assertEquals(1, previousApp.closes)
        val nextApp = frames.latest { Frame(200) }!!
        assertSame(nextApp, frames.latest { null })
        frames.close()
        frames.close()
        assertEquals(1, nextApp.closes)
    }

    @Test fun newDisplayWithoutAnyProducedFrameRemainsUnavailable() {
        val frames = LuluDisplayFrameBuffer<Frame>()
        assertNull(frames.latest { null })
        frames.close()
    }
}
