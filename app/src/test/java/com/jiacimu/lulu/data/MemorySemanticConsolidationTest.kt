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
