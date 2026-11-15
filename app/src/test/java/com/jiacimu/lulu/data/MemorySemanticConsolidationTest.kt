package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemorySemanticConsolidationTest {
    @Test fun overlappingWindowsKeepAllCandidatesAndCompareBothEnds() {
        val items = (1..110).toList()
        val windows = MemorySemanticConsolidation.overlappingReviewWindows(items)
        assertEquals(items.toSet(), windows.flatten().toSet())
        assertTrue(windows.any { 1 in it && 110 in it })
        assertTrue(windows.all { it.size in 2..70 })
    }

    @Test fun objectiveAndSubjectiveMemoriesAreNeverPlacedInTheSameReviewBatch() {
        val at = Instant.parse("2026-11-09T08:00:00Z")
        val objective = com.jiacimu.lulu.core.MemoryEntry("o", "role", "这次说了什么",
            com.jiacimu.lulu.core.MemoryKind.Timeline, "timeline-events:event", at, at,
            4, false, true)
        val subjective = objective.copy(id = "s", content = "我对此很在意",
            perspective = com.jiacimu.lulu.core.MemoryPerspective.Subjective)
        assertTrue(MemorySemanticConsolidation.reviewWindows(listOf(objective, subjective)).isEmpty())
        val secondObjective = objective.copy(id = "o2")
        val windows = MemorySemanticConsolidation.reviewWindows(listOf(objective, secondObjective, subjective))
        assertEquals(1, windows.size)
        assertEquals(setOf("o", "o2"), windows.single().map { it.id }.toSet())
    }

    @Test fun weeklySemanticReviewKeepsDistinctScenesNumbersAndCorrections() {
        val time = Instant.parse("2026-11-10T08:00:00Z")
        val original = com.jiacimu.lulu.core.MemoryEntry(
            "old", "role", "那天主人喜欢红茶，不加糖",
            com.jiacimu.lulu.core.MemoryKind.Fact, "timeline-events:event-old",
            time, time, 5, false, true,
            subject = "主人", slot = "饮品", sceneKey = "饮茶|家中|主人|上午",
            measureKind = "cups", measureValue = 1.0, measureUnit = "杯",
        )
        fun safe(other: com.jiacimu.lulu.core.MemoryEntry) =
            MemorySemanticConsolidation.safeKeeperForEquivalentGroup(listOf(original, other))
        assertEquals(null, safe(original.copy(id = "scene",
            sceneKey = "饮茶|咖啡馆|主人|上午")))
        assertEquals(null, safe(original.copy(id = "value", measureValue = 2.0)))
        assertEquals(null, safe(original.copy(id = "missing", measureKind = "")))
        assertEquals(null, safe(original.copy(id = "day", occurredAt = time.plusSeconds(86_400))))
        assertEquals(null, safe(original.copy(id = "correction",
            content = "后来主人说现在不喜欢红茶了")))
        assertEquals(null, safe(original.copy(id = "different-person",
            subject = "朋友")))
    }

    @Test fun weeklySemanticReviewDoesNotEraseAUniqueDetailInLessImportantEntry() {
        val at = Instant.parse("2026-11-10T08:00:00Z")
        val shortCore = com.jiacimu.lulu.core.MemoryEntry(
            "core", "role", "主人喜欢红茶",
            com.jiacimu.lulu.core.MemoryKind.Fact, "timeline-events:event-a",
            at, at, 9, false, true, tier = com.jiacimu.lulu.core.MemoryTier.Core,
            subject = "主人", slot = "饮品",
        )
        val moreDetailed = shortCore.copy(
            id = "detail", tier = com.jiacimu.lulu.core.MemoryTier.Stable,
            content = "主人喜欢红茶，而且明确不要加糖",
        )
        assertEquals(null, MemorySemanticConsolidation.safeKeeperForEquivalentGroup(
            listOf(shortCore, moreDetailed)))
        // Same conditions and all details still present in the keeper: safe.
        assertEquals(moreDetailed, MemorySemanticConsolidation.safeKeeperForEquivalentGroup(
            listOf(shortCore.copy(tier = com.jiacimu.lulu.core.MemoryTier.Stable), moreDetailed)))
        assertEquals(null, MemorySemanticConsolidation.safeKeeperForEquivalentGroup(
            listOf(moreDetailed, moreDetailed.copy(id = "extra", content = "主人喜欢红茶，但只有独处时才不加糖"))))
    }

    @Test fun weeklyReviewKeepsTwoSimilarButIndependentLivedEpisodes() {
        val at = Instant.parse("2026-11-10T08:00:00Z")
        val first = com.jiacimu.lulu.core.MemoryEntry(
            "one", "role", "昨晚一起散步很开心",
            com.jiacimu.lulu.core.MemoryKind.Timeline, "timeline-events:first",
            at, at, 5, false, true, subject = "散步", slot = "共同经历",
        )
        assertEquals(null, MemorySemanticConsolidation.safeKeeperForEquivalentGroup(
            listOf(first, first.copy(id = "next", source = "timeline-events:second",
                occurredAt = at.plusSeconds(86_400)))))
        assertEquals(first, MemorySemanticConsolidation.safeKeeperForEquivalentGroup(
            listOf(first, first.copy(id = "duplicate"))))
    }

    @Test fun singleRecordNeverCreatesPaidReviewBatch() {
        assertTrue(MemorySemanticConsolidation.overlappingReviewWindows(listOf("only")).isEmpty())
    }

    @Test fun exactIdsOnlyAndNoGroupMayConsumeAnotherGroup() {
        val result = MemorySemanticConsolidation.parseGroups(
            """{"groups":[["a","b"],["b","c"],["a","missing"],["c","d"]]}""",
            setOf("a", "b", "c", "d"),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), result)
    }

    @Test fun invalidResponsesNeverMarkAnyMemory() {
        assertTrue(MemorySemanticConsolidation.parseGroups("not-json", setOf("a", "b")).isEmpty())
        assertTrue(MemorySemanticConsolidation.parseGroups("""{"groups":[["a"]]}""", setOf("a", "b")).isEmpty())
        assertTrue(MemorySemanticConsolidation.parseGroups("""{"groups":[["a","x"]]}""", setOf("a", "b")).isEmpty())
    }

    @Test fun fencedModelOutputIsSupportedWithoutGuessingIds() {
        val groups = MemorySemanticConsolidation.parseGroups(
            "```json\n{\"groups\":[[\"a\",\"b\"]]}\n```", setOf("a", "b"),
        )
        assertEquals(listOf(listOf("a", "b")), groups)
    }
    @Test fun interruptedPaidReviewDoesNotDisplaySuccessAfterAppRestart() {
        val context = RuntimeEnvironment.getApplication()
        MemorySemanticConsolidation.initialize(context)
        val role = "report-restart-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences("lulu_memory_maintenance_schedule_v1", Context.MODE_PRIVATE)
        val record = JSONObject()
            .put("phase", "Running")
            .put("totalRecords", 128)
            .put("eligibleRecords", 25)
            .put("reviewedBatches", 1)
            .put("totalBatches", 3)
            .put("archived", 4)
            .put("detail", "正在审核")
            .put("updatedAt", Instant.now().toString())
        prefs.edit().putString("report:$role", record.toString()).commit()
        val restored = MemorySemanticConsolidation.report(role)
        assertEquals(MemoryReviewPhase.Interrupted, restored.phase)
        assertEquals(1, restored.reviewedBatches)
        assertEquals(4, restored.archived)
        assertNotEquals(MemoryReviewPhase.Completed, restored.phase)
    }

    @Test fun dailyMaintenanceStatusOnlyReportsPersistedSuccess() {
        val context = RuntimeEnvironment.getApplication()
        MemorySemanticConsolidation.initialize(context)
        val role = "daily-status-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences("lulu_memory_maintenance_schedule_v1", Context.MODE_PRIVATE)
        assertEquals(null, MemorySemanticConsolidation.lastDailyMaintenanceAt(role))
        val finishedAt = Instant.parse("2026-11-08T03:00:00Z")
        prefs.edit().putString("daily:$role", finishedAt.toString()).commit()
        assertEquals(finishedAt, MemorySemanticConsolidation.lastDailyMaintenanceAt(role))
        prefs.edit().putString("daily:$role", "broken").commit()
        assertEquals(null, MemorySemanticConsolidation.lastDailyMaintenanceAt(role))
    }

    @Test fun semanticAliasDoesNotBecomeHistoricalFactCorrection() {
        MemoryValidityStore.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        val oldId = "test-equivalent-old"
        val keeperId = "test-equivalent-keeper"
        MemoryValidityStore.markEquivalent(listOf(oldId), keeperId)
        assertFalse(MemoryValidityStore.isActive(oldId))
        assertEquals(keeperId, MemoryValidityStore.equivalenceSnapshot()[oldId])
        assertFalse(oldId in MemoryValidityStore.supersessionSnapshot())
        MemoryValidityStore.removeMemory(oldId)
    }

}
