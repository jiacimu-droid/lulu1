package com.jiacimu.lulu.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CommitmentDecisionProtocolTest {
    @Test fun interpreterChosenCalendarDateSurvivesWithoutProgramReinterpretation() {
        val changes = JSONArray("""[{"action":"create","goal":"这次睡醒时叫我","dueAt":"2026-10-16T09:00:00+08:00","timezone":"Asia/Shanghai"}]""")
        assertEquals(Instant.parse("2026-10-16T01:00:00Z"), CommitmentDecisionProtocol.drafts(changes, emptyList()).single().dueAt)
    }

    @Test fun incompleteOrUnknownTargetsCannotFallThroughToAnotherPromise() {
        val old = CommitmentTask(id = "study", characterId = "role", goal = "跟进进度")
        listOf("""[{"action":"reschedule","goal":"跟进进度"}]""",
            """[{"action":"cancel","targetTaskId":"someone-else"}]""",
            """[{"action":"made_up_action","goal":"跟进进度"}]""").forEach { raw ->
            assertTrue(runCatching { CommitmentDecisionProtocol.drafts(JSONArray(raw), listOf(old)) }.isFailure)
        }
    }

    @Test fun malformedClockIsNotSilentlyConvertedToAnUnscheduledPromise() {
        assertTrue(runCatching { CommitmentDecisionProtocol.drafts(JSONArray(
            """[{"action":"create","goal":"叫醒","dueAt":"明天九点"}]"""), emptyList()) }.isFailure)
    }

    @Test fun mixedRoutineSupervisionIsNotManufacturedIntoAFailedWakeAlarm() {
        val draft = parseCommitmentTaskDrafts(
            """[{"action":"create","goal":"督促学习、睡觉、起床、按时吃饭",
               "promiseKind":"long_term_supervision","completionCondition":"根据真实生活进展持续跟进"}]"""
        ).single()
        assertNull(draft.dueAt)
        assertFalse("An undated responsibility has no missing wake alarm route", draft.needsClarification)
        assertEquals("send_private_message", draft.deliveryAction)

        val ongoing = CommitmentTask(
            characterId = "role",
            goal = draft.goal,
            status = CommitmentTaskStatus.WaitingForFeedback,
        )
        assertNull("A general responsibility is not a failed call",
            wakeScheduleReceipt(ongoing))
        assertNull(wakeScheduleReceipt(ongoing.copy(
            status = CommitmentTaskStatus.NeedsClarification,
            lastActionResult = "缺少必要条件，需要后续自然确认",
        )))
    }

    @Test fun explicitTimedWakeStillRequiresARealDeliveryChannel() {
        val dueAt = Instant.now().plusSeconds(90 * 60L)
        val draft = parseCommitmentTaskDrafts(
            """[{"action":"create","goal":"九十分钟后叫醒我起床",
               "dueAt":"$dueAt","deliveryAction":"send_private_message"}]"""
        ).single()
        assertEquals(dueAt, draft.dueAt)
        assertTrue("An actual timed wake cannot be quietly texted", draft.needsClarification)
        val pending = CommitmentTask(
            characterId = "role", goal = draft.goal, dueAt = draft.dueAt,
            status = CommitmentTaskStatus.NeedsClarification,
            lastActionResult = "未选出有声闹钟",
        )
        assertTrue(wakeScheduleReceipt(pending)?.contains("未选出有声闹钟") == true)
        assertNull(wakeScheduleReceipt(pending.copy(
            goal = "今晚监督睡觉", status = CommitmentTaskStatus.WaitingForFeedback,
        )))
    }

    @Test fun ongoingResponsibilityDoesNotRequireClockGuessingAndEmptyMeansNoTask() {
        val changes = JSONArray("""[{"action":"create","goal":"留意这个进展","promiseKind":"long_term_supervision","needsClarification":false}]""")
        val draft = CommitmentDecisionProtocol.drafts(changes, emptyList()).single()
        assertNull(draft.dueAt)
        assertFalse(draft.needsClarification)
        assertEquals("long_term_supervision", draft.promiseKind)
        assertTrue(CommitmentDecisionProtocol.drafts(JSONArray(), emptyList()).isEmpty())
    }
}
