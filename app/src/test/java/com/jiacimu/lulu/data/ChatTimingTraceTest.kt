package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ChatTimingTraceTest {
    private fun timing(stage: String, ms: Long, trace: String = "", note: String = "") =
        PerformanceTimingRecord(stage = stage, durationMillis = ms, recordedAtMillis = ms,
            traceId = trace, detail = note)

    @Test fun sameStagesAcrossDifferentTurnsStaySeparate() {
        val a = ChatTimingTrace.forOnlineBatch("role", 724L)
        val b = ChatTimingTrace.forOnlineBatch("role", 725L)
        val groups = ChatTimingTrace.group(listOf(
            timing("Prompt", 6913, a), timing("消息到首条回复入库", 214779, a),
            timing("Prompt", 19, b)))
        assertEquals(2, groups.size)
        assertEquals(214779L, groups.single { it.id == a }.firstBubbleMillis)
        assertNull(groups.single { it.id == b }.firstBubbleMillis)
    }

    @Test fun oldUnlinkedRecordsCannotBeInferredByTimestamp() {
        val old = timing("消息到首条回复入库", 214779)
        val traced = timing("P08a在线决定", 0, note = "trace=recorded-trace;batch=7")
        assertEquals(listOf(old), ChatTimingTrace.legacy(listOf(old, traced)))
        assertEquals("recorded-trace", ChatTimingTrace.group(listOf(old, traced)).single().id)
    }

    @Test fun millisecondsDisplayAsReadableDurations() {
        assertEquals("0 ms", ChatTimingTrace.duration(0))
        assertEquals("1.0 秒", ChatTimingTrace.duration(1038))
        assertEquals("3 分 34 秒", ChatTimingTrace.duration(214779))
    }
}
