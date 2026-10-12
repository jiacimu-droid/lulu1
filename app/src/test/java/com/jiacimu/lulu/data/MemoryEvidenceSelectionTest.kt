package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MemoryEvidenceSelectionTest {
    private val at = Instant.parse("2026-10-12T12:00:00Z")
    private fun event(id: String, seconds: Long = 0, session: String = "meeting", role: String = "role") =
        SharedTimelineEvent(id, role, "见面", "用户", "原始内容$id".repeat(15), at.plusSeconds(seconds),
            sessionId = session, evidenceKind = EventEvidenceKind.UserStatement)

    @Test fun evidenceNeighborsPreserveBeforeAndAfterButCannotCrossSessionsOrOwners() {
        val before = event("before", -10)
        val hit = event("hit")
        val after = event("after", 10)
        val unrelated = event("other", 1, session = "other-session")
        val anotherRole = event("other-role", 2, role = "another-role")
        val result = MemoryEvidenceSelection.expand(listOf(hit), listOf(before, hit, after, unrelated, anotherRole))
        assertEquals(setOf("before", "hit", "after"), result.map { it.id }.toSet())
    }

    @Test fun oldRelevantHitIsSelectedBeforeNewLowPriorityEvidence() {
        val important = event("old", -86_400)
        val newer = event("new")
        val rendered = MemoryEvidenceSelection.render(listOf(important, newer), 120)
        assertEquals(listOf("old"), rendered.ids)
        assertTrue(rendered.text.length <= 120)
        assertTrue(rendered.truncatedIds.contains("old"))
    }

    @Test fun chosenEvidenceIsDisplayedInTimeOrderAfterRelevanceSelection() {
        val first = event("first", -20)
        val second = event("second")
        val rendered = MemoryEvidenceSelection.render(listOf(second, first), 2_000)
        assertTrue(rendered.text.indexOf("原始内容first") < rendered.text.indexOf("原始内容second"))
        assertEquals(setOf("first", "second"), rendered.ids.toSet())
    }

    @Test fun daysApartInTheSameConversationAreNotImmediateCausalNeighbors() {
        val hit = event("hit")
        val old = event("old", -86_400)
        assertEquals(listOf("hit"), MemoryEvidenceSelection.expand(listOf(hit), listOf(old, hit)).map { it.id })
    }

    @Test fun traceRecordsActualPromptBudgetAndCannotBeOverwrittenByAnOlderRequest() {
        val oldTrace = MemoryInspectionStore.recordRecall("role", "旧请求", emptyList(), listOf(event("old")))
        val latest = MemoryInspectionStore.recordRecall("role", "新请求", emptyList(), listOf(event("new")))
        MemoryInspectionStore.recordInjection("role", oldTrace, listOf("event:old"), emptyMap())
        assertFalse(MemoryInspectionStore.snapshot("role")!!.promptRecorded)
        MemoryInspectionStore.recordInjection("role", latest, emptyList(), mapOf("event:new" to "预算"))
        assertTrue(MemoryInspectionStore.snapshot("role")!!.promptRecorded)
        assertEquals("预算", MemoryInspectionStore.snapshot("role")!!.omitted["event:new"])
    }
}
