package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryRecallLifecycleTest {
    @Test fun successfulPromptInjectionsArePersistedButCandidatesAndCoreAreNot() {
        val context = RuntimeEnvironment.getApplication() as Context
        MemoryRecallUsageStore.initialize(context)
        val role = "usage-" + UUID.randomUUID()
        val now = Instant.parse("2026-11-21T10:00:00Z")
        MemoryRecallUsageStore.recordSuccessfulInjection(role,
            listOf("memory:a", "memory:a", "event:foo", "core:b", "owner:c"), now)
        MemoryRecallUsageStore.recordSuccessfulInjection(role, listOf("memory:a", "memory:c"), now.plusSeconds(15))
        assertEquals(2, MemoryRecallUsageStore.stats(role, "a").successfulInjections)
        assertEquals(now.plusSeconds(15), MemoryRecallUsageStore.stats(role, "a").lastInjectedAt)
        assertEquals(1, MemoryRecallUsageStore.stats(role, "c").successfulInjections)
        assertEquals(0, MemoryRecallUsageStore.stats(role, "b").successfulInjections)
        assertEquals(0, MemoryRecallUsageStore.stats("another-role", "a").successfulInjections)
        assertTrue(MemoryRecallUsageStore.snapshot(role).containsKey("a"))
        val disk = context.getSharedPreferences("lulu_memory_recall_usage_v1", Context.MODE_PRIVATE)
            .getString("usage:$role", "").orEmpty()
        assertTrue("injection counts must survive process restart", disk.contains("\"injections\":2"))
        MemoryRecallUsageStore.removeMemory(role, "a")
        assertFalse(MemoryRecallUsageStore.snapshot(role).containsKey("a"))
        assertEquals(1, MemoryRecallUsageStore.stats(role, "c").successfulInjections)
    }

    @Test fun periodSummarySelectionDoesNotDropTheFirstHalfOfTheDay() {
        val start = Instant.parse("2026-11-19T16:00:00Z")
        val end = start.plus(1, ChronoUnit.DAYS)
        val entries = (0 until 24).toList().reversed().map { hour ->
            "memory:$hour" to start.plus(hour.toLong(), ChronoUnit.HOURS)
        }
        val selected = memoryTemporalCoverage(entries, start to end, 6,
            { it.second }, { it.first })
        assertEquals(6, selected.size)
        assertTrue(selected.any { it.second.isBefore(start.plus(8, ChronoUnit.HOURS)) })
        assertTrue(selected.any { it.second.isAfter(start.plus(16, ChronoUnit.HOURS)) })
        assertEquals(selected.sortedBy { it.second }, selected)
    }

    @Test fun yesterdayOverviewIncludesMorningAndEveningInsteadOfOnlyLatestMessages() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-11-21T14:00:00Z")
        val start = Instant.parse("2026-11-19T16:00:00Z") // Nov 20 local midnight.
        val yesterday = (0 until 24).map { hour ->
            SharedTimelineEvent("y-$hour", "role", "私聊", "用户",
                "昨天第 ${hour} 个小时的真实生活内容", start.plus(hour.toLong(), ChronoUnit.HOURS),
                evidenceKind = EventEvidenceKind.UserStatement)
        }
        val today = SharedTimelineEvent("today", "role", "私聊", "用户",
            "今天的内容不应进入昨天", now.minusSeconds(1800),
            evidenceKind = EventEvidenceKind.UserStatement)
        val found = RawTimelineMemoryRecall.find("昨天我们做了什么",
            yesterday + today, limit = 6, now = now, zone = zone)
        assertEquals(6, found.size)
        assertFalse(found.any { it.id == "today" })
        val hours = found.map { it.occurredAt.atZone(zone).hour }
        assertTrue("should include early day: $hours", hours.any { it < 8 })
        assertTrue("should include late day: $hours", hours.any { it >= 16 })
        assertEquals(found.sortedBy(SharedTimelineEvent::occurredAt), found)
    }
}
