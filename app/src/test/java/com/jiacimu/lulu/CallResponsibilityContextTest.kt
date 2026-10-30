package com.jiacimu.lulu

import com.jiacimu.lulu.data.CommitmentTask
import com.jiacimu.lulu.data.CommitmentTaskStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CallResponsibilityContextTest {
    private val now = Instant.parse("2026-10-30T02:00:00Z")
    private val task = CommitmentTask(id = "wake", characterId = "a", goal = "九点叫醒我",
        completionCondition = "确认醒来", dueAt = now.minusSeconds(3600),
        status = CommitmentTaskStatus.WaitingForFeedback, lastActionResult = "语音已播放但未确认醒来")
    private fun render(value: CommitmentTask = task, role: String = "a", id: String? = "wake") =
        CallResponsibilityContext.render(listOf(value), role, id, now)

    @Test fun unfinishedCallCarriesGoalEvidenceAndRealActionsOnLaterSilentTurns() {
        val responsibility = render()
        assertTrue(responsibility.contains(task.goal))
        assertTrue(responsibility.contains(task.completionCondition))
        assertTrue(responsibility.contains(task.lastActionResult))
        val quiet = CallSilencePolicy.context(22 * 60_000L, true, responsibility)
        assertTrue(quiet.contains("已静音，无法据此判断用户醒睡"))
        assertTrue(quiet.contains("ring_in_call_wake"))
        assertTrue(quiet.contains("control_music"))
        assertFalse(quiet.contains("同时想联系对方又担心打扰"))
        assertTrue(CallSilencePolicy.context(60_000L, true).contains("reply 或 silent"))
    }

    @Test fun completedCancelledFutureOrForeignTasksCannotKeepDrivingThisCall() {
        assertEquals("", render(task.copy(status = CommitmentTaskStatus.Completed)))
        assertEquals("", render(task.copy(status = CommitmentTaskStatus.Cancelled)))
        assertEquals("", render(task.copy(dueAt = now.plusSeconds(3600))))
        assertEquals("", render(role = "b"))
        assertEquals("", render(id = null))
        assertEquals("", render(id = "other"))
        assertEquals("", render(task.copy(dueAt = null)))
    }
}
