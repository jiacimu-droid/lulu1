package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Reply attribution is pure counting over stored facts: which
 * proactive-send receipts exist, and whether a user statement follows
 * each one in the same timeline session. No content is read, no meaning
 * is judged, and no timeline database is needed here.
 *
 * Runs under Robolectric (like the ledger tests) because the receipts
 * test builds its snapshots with org.json, whose methods are stubs
 * that throw in plain local unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
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
            put(receipt("proactive-send:evt4", "2026-10-26T12:00:00Z", true, evidenceId = "evt1"))
            put(receipt("proactive-send:evt5", "2026-10-26T12:00:00Z", true, evidenceId = ""))
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
    @Test
    fun replyBeforeActualDeliveryIsNotCountedEvenWhenReceiptClockIsEarly() {
        val receipt = ProactiveSendReceipt(
            "proactive-send:evt-late", Instant.parse("2026-10-26T10:00:00Z"), "m1", "evt-late"
        )
        val events = listOf(
            event("evt-late", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:10:00Z"),
            event("user-before-delivery", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:05:00Z"),
        )
        assertEquals(false, ProactiveSendAttribution.repliedByDecisionId(listOf(receipt), events)[receipt.decisionId])
        val withReply = events + event(
            "user-after-delivery", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:11:00Z"
        )
        assertEquals(true, ProactiveSendAttribution.repliedByDecisionId(listOf(receipt), withReply)[receipt.decisionId])
    }

    @Test
    fun nonDeliveredEvidenceAndOtherCharactersCannotProveReply() {
        val receipt = ProactiveSendReceipt(
            "proactive-send:evt", Instant.parse("2026-10-26T10:00:00Z"), "m1", "evt"
        )
        val wrongKind = listOf(
            event("evt", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:00:00Z"),
            event("later", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:01:00Z"),
        )
        assertFalse(ProactiveSendAttribution.repliedByDecisionId(listOf(receipt), wrongKind).containsKey(receipt.decisionId))
        val delivered = event("evt", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:00:00Z")
        val otherRoleReply = event("later", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:01:00Z")
            .copy(characterId = "role-b")
        assertEquals(false, ProactiveSendAttribution.repliedByDecisionId(listOf(receipt), listOf(delivered, otherRoleReply))[receipt.decisionId])
    }

    @Test
    fun invalidatedOriginalStatementsCannotProveDeliveryOrReply() {
        val receipt = ProactiveSendReceipt(
            "proactive-send:evt", Instant.parse("2026-10-26T10:00:00Z"), "m1", "evt"
        )
        val delivered = event("evt", "conv-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:00:00Z")
        val user = event("reply", "conv-a", EventEvidenceKind.UserStatement, "2026-10-26T10:01:00Z")
        val events = listOf(delivered, user)
        assertEquals(true, ProactiveSendAttribution.repliedByDecisionId(listOf(receipt), events)[receipt.decisionId])
        // Simulate canonical chat rows being deleted, unsent or revised while
        // their old timeline projections still exist.
        assertFalse(ProactiveSendAttribution.repliedByDecisionId(
            listOf(receipt), events
        ) { it.id != "evt" }.containsKey(receipt.decisionId))
        assertEquals(false, ProactiveSendAttribution.repliedByDecisionId(
            listOf(receipt), events
        ) { it.id != "reply" }[receipt.decisionId])
    }

    @Test
    fun successfulToolWithoutResolvedDeliveryNeverPersistsSuccessReceipt() {
        // The production runtime calls this gate before writing CharacterInnerLifeStore.
        // Tool success alone, or a model's selected action, is not a delivered event.
        assertFalse(ProactiveSendAttribution.shouldPersistDeliveredSend(true, "", true))
        assertFalse(ProactiveSendAttribution.shouldPersistDeliveredSend(false, "evt-delivered", true))
        assertFalse(ProactiveSendAttribution.shouldPersistDeliveredSend(true, "evt-group", false))
        assertTrue(ProactiveSendAttribution.shouldPersistDeliveredSend(true, "evt-delivered", true))
    }

    @Test
    fun primaryDeliveryUsesExactExecutorIdsNotNewerSameSessionSideSend() {
        val main = event("primary", "private-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:00:00Z")
        val side = event("side", "private-a", EventEvidenceKind.CharacterStatement, "2026-10-26T10:01:00Z")
        val other = event("other", "private-b", EventEvidenceKind.CharacterStatement, "2026-10-26T10:02:00Z")
        val events = listOf(main, side, other)
        val acceptsMessage: (SharedTimelineEvent) -> Boolean = {
            it.evidenceKind == EventEvidenceKind.CharacterStatement
        }
        assertEquals("primary", ProactiveSendAttribution.selectPrimaryEvidence(
            "private-a", listOf("primary"), events, acceptsMessage,
        ))
        // A successful side send cannot turn a failed or unproven primary
        // into a successful proactive-send receipt.
        assertEquals("", ProactiveSendAttribution.selectPrimaryEvidence(
            "private-a", emptyList(), events, acceptsMessage,
        ))
        assertEquals("", ProactiveSendAttribution.selectPrimaryEvidence(
            "private-a", listOf("side"), listOf(main), acceptsMessage,
        ))
        assertEquals("", ProactiveSendAttribution.selectPrimaryEvidence(
            "private-a", listOf("other"), events, acceptsMessage,
        ))
        assertEquals("", ProactiveSendAttribution.selectPrimaryEvidence(
            "private-a", listOf("primary"), events,
        ) { false })
    }


    @Test
    fun persistedPrimaryReceiptSurvivesStoreReadAndNeverClaimsSideOnlyDelivery() {
        // Exercise the actual timeline and inner-life persistence, not only a
        // synthetic list of candidate events. The executor itself is not run.
        // A timeline row alone is not a delivered chat message. Authorship is
        // verified against the canonical sent message in the actual chat store.
        // Build both sides through the production append path, not a synthetic
        // "message" event that the ownership gate must reject.
        MemoryTestEnvironment.release()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val role = "proactive-persist-" + java.util.UUID.randomUUID().toString()
        val chat = MigratedDomainStores.chat
        val conversation = chat.ensureConversation(role, "主动发送回执验证").id
        try {
            val primary = chat.appendCharacterMessage(
                conversation, "独立送达的第1条消息", role,
            )
            val side = chat.appendCharacterMessage(
                conversation, "独立送达的第2条消息", role,
            )
            val primaryId = primary.id
            val sideId = side.id
            val sentAt = primary.createdAt
            // A forged timeline row with the correct channel/session/source
            // cannot claim delivery without the actual sent-chat record.
            val orphanId = "orphan-" + java.util.UUID.randomUUID().toString()
            SharedExperienceTimeline.record(
                eventId = orphanId, characterId = role, channel = "私聊",
                speaker = "角色", content = "并未真实发出的消息",
                occurredAt = sentAt, triggerExtraction = false,
                sessionId = conversation, source = "message",
                evidenceKind = EventEvidenceKind.CharacterStatement,
            )
            assertEquals("", ProactiveSendAttribution.primaryDeliveryEvidence(
                role, conversation, listOf(orphanId), "message",
            ))
            val mainEvidence = ProactiveSendAttribution.primaryDeliveryEvidence(
                role, conversation, listOf(primaryId), "message",
            )
            assertEquals(primaryId, mainEvidence)
            // Partial executor delivery: a failed first message ID must not
            // hide a later independently verified primary message.
            assertEquals(primaryId, ProactiveSendAttribution.primaryDeliveryEvidence(
                role, conversation, listOf("missing-primary-id", primaryId), "message",
            ))
            // The side message is real, but never evidence when the executor
            // reports no primary delivery (even though a side action succeeded).
            assertEquals("", ProactiveSendAttribution.primaryDeliveryEvidence(
                role, conversation, emptyList(), "message",
            ))
            assertTrue(ProactiveSendAttribution.summarize(role).receipts.isEmpty())
            assertEquals("", ProactiveSendAttribution.primaryDeliveryEvidence(
                role, conversation, emptyList(), "message",
            ))
            assertEquals("", ProactiveSendAttribution.primaryDeliveryEvidence(
                role, "another-conversation", listOf(primaryId), "message",
            ))
            // The same physical chat delivery cannot be attributed to another character.
            assertEquals("", ProactiveSendAttribution.primaryDeliveryEvidence(
                "different-role-" + role, conversation, listOf(primaryId), "message",
            ))
            if (ProactiveSendAttribution.shouldPersistDeliveredSend(true, mainEvidence, true)) {
                CharacterInnerLifeStore.recordDecision(
                    role, "proactive-send:$mainEvidence", "send_private_message",
                    "真实送达", "", null, "已送达", true, sentAt,
                    causalEvidenceId = mainEvidence,
                )
            }
            // Same request replay cannot manufacture a second receipt.
            CharacterInnerLifeStore.recordDecision(
                role, "proactive-send:$mainEvidence", "send_private_message",
                "重放", "", null, "重复回执", true, sentAt,
                causalEvidenceId = mainEvidence,
            )
            val persisted = ProactiveSendAttribution.summarize(role)
            assertEquals(1, persisted.receipts.size)
            assertEquals(primaryId, persisted.receipts.single().causalEvidenceId)
            assertEquals(1, persisted.attributable)
            assertEquals(0, persisted.replied)
            assertEquals(1, persisted.unanswered)
            assertFalse(persisted.receipts.any { it.causalEvidenceId == sideId })

            // Exercise the canonical chat mutation path rather than injecting
            // a synthetic validity predicate. A real reply counts only while
            // its original sent row remains valid.
            val reply = chat.sendUserMessage(conversation, "我收到啦")
            assertEquals(1, ProactiveSendAttribution.summarize(role).replied)
            // Editing the original sent row is a real revision, not a retraction.
            // ChatStore updates the matching timeline revision atomically, so
            // the user's reply still exists with the same session and send time.
            assertTrue(chat.editMessage(reply.id, "这条回复后来被修改"))
            assertEquals(1, ProactiveSendAttribution.summarize(role).replied)
            // Unlike an edit, losing Sent status makes the old projected row
            // insufficient evidence of delivery, even before deletion.
            chat.markFailed(reply.id)
            assertEquals(0, ProactiveSendAttribution.summarize(role).replied)
            assertTrue(chat.deleteMessage(reply.id))
            assertEquals(0, ProactiveSendAttribution.summarize(role).replied)
            // The original delivery is no longer proof after a canonical
            // edit, even if a stale projection survives elsewhere.
            assertTrue(chat.editMessage(primaryId, "已修改的角色消息"))
            assertEquals(0, ProactiveSendAttribution.summarize(role).attributable)

        } finally {
            chat.deleteConversation(conversation)
            CharacterInnerLifeStore.clear(role)
            MemoryTestEnvironment.release()
        }
    }
}
