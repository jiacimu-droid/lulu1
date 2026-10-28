package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real memory repository + canonical timeline + both prompt delivery paths. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NarrativeMemoryRecallIntegrationTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun talkPromiseAndToolFailureAreOneEvidenceBackedEpisodeAcrossScenes() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        val role = "episode-" + UUID.randomUUID()
        val task = "supervision-" + UUID.randomUUID()
        val start = Instant.now().minus(Duration.ofDays(6))
        val talkId = "talk-" + UUID.randomUUID()
        val attemptId = "attempt-" + UUID.randomUUID()
        val resultId = "result-" + UUID.randomUUID()

        SharedExperienceTimeline.record(talkId, role, "私聊", "角色",
            "监督学习计划已经答应，要检查屏幕", start, false,
            evidenceKind = EventEvidenceKind.CharacterStatement, taskId = task)
        SharedExperienceTimeline.record(attemptId, role, "电话", "角色",
            "已经提出监督学习计划的屏幕检查", start.plusSeconds(86_400), false,
            evidenceKind = EventEvidenceKind.CharacterStatement, taskId = task)
        SharedExperienceTimeline.record(resultId, role, "工具回执", "执行器",
            "屏幕权限被拒，实际检查失败", start.plusSeconds(2 * 86_400), false,
            evidenceKind = EventEvidenceKind.ToolResult, taskId = task)
        SharedExperienceTimeline.record("unrelated-" + UUID.randomUUID(), role,
            "工具回执", "执行器", "一件无关事件", start.plusSeconds(90), false,
            evidenceKind = EventEvidenceKind.ToolResult, taskId = "unrelated-task")

        val history = UnifiedMemoryOrchestrator.assemble(
            role, UnifiedMemoryRequest(currentInput = "监督学习计划"),
            localOnly = true, evidenceLimit = 16)
        assertEquals(setOf(talkId, attemptId, resultId),
            history.sourceEvents.map { it.id }.toSet())
        assertTrue(history.compactPromptSection(8_000).contains("同任务事件链"))
        assertTrue(history.compactPromptSection(8_000).contains("实际检查失败"))
        val full = MemoryWorkingSet.assemble(history, emptyList(), emptyList(),
            PromptContextBudget(18, 1000, 6000, 3000, false, false, 1500, false,
                workingSetCharacters = 9000), "")
        assertTrue(full.sections[MemoryWorkingSet.EVIDENCE].orEmpty().contains("同任务事件链"))
        assertTrue(full.sections[MemoryWorkingSet.EVIDENCE].orEmpty().contains("实际检查失败"))
        assertFalse(full.sections[MemoryWorkingSet.EVIDENCE].orEmpty().contains("一件无关事件"))

        // Withdrawal from the SINGLE authoritative timeline immediately
        // removes the receipt from both recall routes, not just from UI.
        SharedExperienceTimeline.deleteEvent(resultId)
        val after = UnifiedMemoryOrchestrator.assemble(
            role, UnifiedMemoryRequest(currentInput = "监督学习计划"),
            localOnly = true, evidenceLimit = 16)
        assertFalse(after.sourceEvents.any { it.id == resultId })
        assertFalse(after.compactPromptSection(8_000).contains("实际检查失败"))
        assertFalse(MemoryWorkingSet.assemble(after, emptyList(), emptyList(),
            PromptContextBudget(18, 1000, 6000, 3000, false, false, 1500, false,
                workingSetCharacters = 9000), "")
            .sections[MemoryWorkingSet.EVIDENCE].orEmpty().contains("实际检查失败"))
    }

    @Test fun revisionChangeAndOtherCharactersCannotResurrectOldEpisodeText() {
        val app = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        val owner = "owner-" + UUID.randomUUID()
        val other = "other-" + UUID.randomUUID()
        val task = "task-" + UUID.randomUUID()
        val old = Instant.now().minusSeconds(3600)
        val id = "original-" + UUID.randomUUID()
        val newId = "new-" + UUID.randomUUID()
        SharedExperienceTimeline.record(id, owner, "私聊", "用户", "我只想喝茶", old, false,
            evidenceKind = EventEvidenceKind.UserStatement, taskId = task)
        val original = SharedExperienceTimeline.eventsByIds(owner, listOf(id)).single()
        SharedExperienceTimeline.record(id, owner, "私聊", "用户", "我不想喝茶了", old, false,
            evidenceKind = EventEvidenceKind.UserStatement, taskId = task, expectedRevision = original.revision)
        SharedExperienceTimeline.record(newId, other, "工具回执", "执行器", "其他角色执行过",
            old.plusSeconds(10), false, evidenceKind = EventEvidenceKind.ToolResult, taskId = task)
        val live = SharedExperienceTimeline.all(owner)
        assertTrue(MemoryEvidenceSelection.expand(listOf(original), live).isEmpty())
        val current = SharedExperienceTimeline.eventsByIds(owner, listOf(id)).single()
        assertEquals("我不想喝茶了", current.content)
        assertEquals(listOf(id), MemoryEvidenceSelection.expand(listOf(current), live).map { it.id })
        assertFalse(NarrativeEvidenceChain.annotations(listOf(current)).containsKey(newId))
    }
}
