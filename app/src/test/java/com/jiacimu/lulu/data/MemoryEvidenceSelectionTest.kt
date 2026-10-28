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

    @Test fun crossSceneTaskStoryReturnsPromisesAndRealReceiptsAcrossDays() {
        val start = at.minusSeconds(4 * 86_400)
        val intent = SharedTimelineEvent("promise", "role", "私聊", "角色", "我会看看",
            start, taskId = "task-123", evidenceKind = EventEvidenceKind.CharacterStatement)
        val attempt = SharedTimelineEvent("attempt", "role", "定时执行", "工具", "已发起检查",
            start.plusSeconds(86_400), taskId = "task-123", evidenceKind = EventEvidenceKind.Observation)
        val receipt = SharedTimelineEvent("receipt", "role", "工具回执", "执行器", "检查失败，需要权限",
            at, taskId = "task-123", evidenceKind = EventEvidenceKind.ToolResult)
        val otherTask = receipt.copy(id = "unrelated", taskId = "task-other")
        val otherRole = receipt.copy(id = "other-role", characterId = "role-2")
        val found = MemoryEvidenceSelection.expand(listOf(intent),
            listOf(intent, attempt, receipt, otherTask, otherRole))
        assertEquals(listOf("promise", "attempt", "receipt"), found.map { it.id })
        assertTrue(NarrativeEvidenceChain.annotations(found)["receipt"].orEmpty().contains("3/3"))
        val rendered = MemoryEvidenceSelection.render(found, 1_500)
        assertTrue(rendered.text.contains("同任务事件链"))
        assertTrue(rendered.text.contains("检查失败"))
        assertTrue(rendered.text.contains("已发起检查"))
    }

    @Test fun sessionAcrossPhoneAndChatOnlyLinksNearEvents() {
        val phone = event("phone", session = "call-9").copy(channel = "电话")
        val chat = event("chat", seconds = 180, session = "call-9").copy(channel = "私聊")
        val nextDay = event("tomorrow", seconds = 86_400, session = "call-9").copy(channel = "电话")
        val otherSession = event("other", seconds = 90, session = "call-10")
        assertEquals(setOf("phone", "chat"), MemoryEvidenceSelection.expand(
            listOf(phone), listOf(phone, chat, nextDay, otherSession)).map { it.id }.toSet())
        assertEquals(setOf("phone", "chat"), NarrativeEvidenceChain.annotations(
            listOf(phone, chat)).keys)
    }

    @Test fun deletedEditedOrOtherRoleEvidenceCannotReenterFromAnOldSeed() {
        val seed = event("original", session = "call").copy(taskId = "task-x")
        val later = event("later", seconds = 100).copy(taskId = "task-x")
        val newRevision = seed.copy(content = "纠正后的事实", revision = seed.revision + 1)
        assertTrue(MemoryEvidenceSelection.expand(listOf(seed), listOf(newRevision, later)).isEmpty())
        assertTrue(MemoryEvidenceSelection.expand(listOf(seed), listOf(later)).isEmpty())
        assertTrue(MemoryEvidenceSelection.expand(listOf(seed), listOf(seed.copy(characterId = "other"), later)).isEmpty())
        assertTrue(MemoryEvidenceSelection.expand(listOf(seed), listOf(seed, later), limit = 0).isEmpty())
        assertEquals(listOf("original"), MemoryEvidenceSelection.expand(
            listOf(seed), listOf(seed, later.copy(occurredAt = java.time.Instant.now().plusSeconds(86_400)))).map { it.id })
    }

    @Test fun saturatedRecallBudgetCanStillCarryRealTaskOutcome() {
        val seed = event("top").copy(taskId = "task")
        val hits = listOf(seed) + (1..9).map { event("hit-$it", seconds = it.toLong(),
            session = "unrelated-$it") }
        val receipt = event("receipt", seconds = 86_400)
            .copy(taskId = "task", evidenceKind = EventEvidenceKind.ToolResult)
        val found = MemoryEvidenceSelection.expand(hits, hits + receipt, 10)
        assertEquals(10, found.size)
        assertEquals("top", found.first().id)
        assertTrue(found.any { it.id == "receipt" })
    }

    @Test fun manyEventsBoundOnlyTheSelectedPromptAndNeverTruncateTheTimeline() {
        val seed = event("seed").copy(taskId = "five-year-task")
        val archive = (0 until 1500).map { n ->
            seed.copy(id = "related-$n", occurredAt = at.plusSeconds((n + 1L) * 20),
                evidenceKind = EventEvidenceKind.CharacterStatement)
        }
        val before = archive.size
        val found = MemoryEvidenceSelection.expand(listOf(seed), listOf(seed) + archive, limit = 12)
        assertTrue(found.size in 2..12)
        assertEquals(listOf("seed", "related-0", "related-1"), found.map { it.id })
        assertEquals("seed", found.first().id)
        assertEquals(before, archive.size)
        assertTrue(found.all { it.characterId == "role" })
    }

    @Test fun narrativeMetadataNeverEvictsOriginalEvidenceWhenBudgetIsSmall() {
        val first = event("first").copy(taskId = "same-task")
        val next = event("next", 5).copy(taskId = "same-task")
        val small = MemoryEvidenceSelection.render(listOf(first, next), characterBudget = 115)
        assertEquals(listOf("first"), small.ids)
        assertTrue(small.text.contains("原始内容first"))
        assertFalse(small.text.contains("同任务事件链"))
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
