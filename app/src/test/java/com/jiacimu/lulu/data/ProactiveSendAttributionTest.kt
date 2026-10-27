package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Reply attribution is pure counting over stored facts: which
 * proactive-send receipts exist, and whether a user statement follows
 * each one in the same timeline session. No content is read, no meaning
 * is judged, and no timeline database is needed here.
 */
class ProactiveSendAttributionTest {

    private fun receipt(
        id: String,
        at: String,
        succeeded: Boolean,
        motiveId: String = "m1",
        evidenceId: String = "evt1",
    ) = JSONObject().put("id", id).put("at", at).put("selected", "send_private_message")
        .put("succeeded", succeeded).put("motiveId", motiveId).put("causalEvidenceId", evidenceId)

    private fun event(id: String, session: String, kind: EventEvidenceKind, at: String) =
        SharedTimelineEvent(
            id = id, characterId = "role-a", channel = "私聊", speaker = "user",
            content = "", occurredAt = Instant.parse(at), sessionId = session, evidenceKind = kind,
        )

    @Test
    fun receiptsKeepOnlyDeliveredProactiveSendsWithParsableTime() {
        val root = JSONObject().put("decisions", JSONArray().apply {
            put(receipt("proactive-send:evt1", "2026-10-26T10:00:00Z", true))
            put(receipt("proactive-send:evt2", "2026-10-26T11:00:00Z", false))
            put(receipt("perception:123", "2026-10-26T12:00:00Z", true, evidenceId = ""))
            put(receipt("proactive-send:evt3", "not-a-date", true))
        })
        val found = ProactiveSendAttribution.receipts(root)
        assertEquals(1, found.size)
        assertEquals("proactive-send:evt1", found[0].decisionId)
        assertEquals("evt1", found[0].causalEvidenceId)
    }

    @Test
    fun repliedOnlyByLaterUserStatementInSameSession() {
        val receipts = listOf(
            ProactiveSendReceipt("proactive-send:evt1", Instant.parse("2026-10-26T10:00:00Z"), "m1", "evt1"),
            ProactiveSendReceipt("proactive-send:evt2", Instant.parse("2026-10-26T12:00:00Z"), "m1", "evt2"),
            ProactiveSendReceipt("proactive-send:evt3", Instant.parse("2026-10-26T10:00:00Z"), "m1", "missing"),
        )
        val events = listOf(
            event("evt1", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:00:00Z"),
            event("user-early", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T09:00:00Z"),
            event("user-late", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T11:00:00Z"),
            event("user-other", "conv-b", EventEvidenceKind.UserStatement, "2026-10-26T13:00:00Z"),
            event("evt2", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T12:00:00Z"),
            event("char-late", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T13:00:00Z"),
        )
        val replied = ProactiveSendAttribution.repliedByDecisionId(receipts, events)
        // evt1: user-late follows in conv-a.
        assertTrue(replied["proactive-send:evt1"] == true)
        // evt2: user-late is earlier, user-other is another session, char-late is not the user.
        assertTrue(replied["proactive-send:evt2"] == false)
        // evt3: delivered event absent from the timeline, never guessed.
        assertFalse(replied.containsKey("proactive-send:evt3"))
    }

    @Test
    fun userStatementAtExactlySendTimeDoesNotCount() {
        val receipts = listOf(
            ProactiveSendReceipt("proactive-send:evt1", Instant.parse("2026-10-26T10:00:00Z"), "m1", "evt1"),
        )
        val events = listOf(
            event("evt1", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:00:00Z"),
            event("user-same", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:00:00Z"),
        )
        val replied = ProactiveSendAttribution.repliedByDecisionId(receipts, events)
        assertTrue(replied["proactive-send:evt1"] == false)
    }
}
