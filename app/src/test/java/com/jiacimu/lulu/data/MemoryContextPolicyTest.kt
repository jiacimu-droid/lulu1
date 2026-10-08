package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MemoryContextPolicyTest {
    private fun memory(id: String, content: String) = MemoryEntry(id, "role", content, MemoryKind.Fact,
        "手动", null, Instant.parse("2026-09-01T00:00:00Z"), 5, false, true)

    @Test fun manyShortMemoriesFitAndAnOversizedEntryDoesNotEvictFollowingOnes() {
        val short = (1..40).map { memory("$it", "喜欢食物$it") }
        val selected = selectMemoryWithinBudget(listOf(memory("long", "长".repeat(12_000))) + short, 5_000)
        assertEquals(short, selected)
    }

    @Test fun coreAndPinnedAreIndependentOfRetrievalStrength() {
        assertTrue(memory("core", "明确边界").copy(tier = MemoryTier.Core).isCoreMemory())
        assertTrue(memory("pin", "固定内容").copy(pinned = true).isCoreMemory())
        assertFalse(memory("strong", "普通喜好").copy(strength = 10).isCoreMemory())
    }

    @Test fun idleFlushUsesElapsedTimeAndNeverFlushesAFutureTimestamp() {
        val now = Instant.parse("2026-10-08T12:00:00Z")
        assertTrue(shouldFlushMemoryTail(now.minusSeconds(600), now))
        assertFalse(shouldFlushMemoryTail(now.minusSeconds(599), now))
        assertFalse(shouldFlushMemoryTail(now.plusSeconds(1), now))
    }

    @Test fun lastMonthUsesCalendarRangeAcrossYearBoundary() {
        val range = memoryTimeRange("上个月我们聊过什么", Instant.parse("2027-01-08T12:00:00Z"), ZoneId.of("Asia/Shanghai"))!!
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), range.first)
        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), range.second)
    }
}
