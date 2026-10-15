package com.jiacimu.lulu.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

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

    @Test fun ongoingResponsibilityDoesNotRequireClockGuessingAndEmptyMeansNoTask() {
        val changes = JSONArray("""[{"action":"create","goal":"留意这个进展","promiseKind":"long_term_supervision","needsClarification":false}]""")
        val draft = CommitmentDecisionProtocol.drafts(changes, emptyList()).single()
        assertNull(draft.dueAt)
        assertFalse(draft.needsClarification)
        assertEquals("long_term_supervision", draft.promiseKind)
        assertTrue(CommitmentDecisionProtocol.drafts(JSONArray(), emptyList()).isEmpty())
    }
}
