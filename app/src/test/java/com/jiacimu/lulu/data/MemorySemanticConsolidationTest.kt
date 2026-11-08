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
