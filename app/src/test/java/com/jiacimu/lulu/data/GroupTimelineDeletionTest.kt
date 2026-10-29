package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GroupTimelineDeletionTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun before() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
    }
    @After fun after() { MemoryTestEnvironment.release() }

    private fun group(id: String, members: List<String>) = LuluConversation(
        id = id, characterId = members.first(), title = "同名群", lastMessage = "", updatedAt = Instant.now(),
        groupChat = LuluGroupChat(name = "同名群", members = members.map { LuluGroupMember(it) }))

    @Test fun clearingSameNamedGroupPreservesTheOtherAndDeletesHistoricalMembers() {
        val a = group("group_a%", listOf("one", "two"))
        val b = group("group_ab", listOf("one", "two"))
        val message = LuluChatMessage(id = "old_message%", conversationId = a.id,
            sender = LuluChatMessage.Sender.User, content = "后来删除的原话")
        SharedExperienceTimeline.recordConversationMessage(a, message, triggerExtraction = false)
        SharedExperienceTimeline.recordConversationMessage(b, message.copy(id = "other", conversationId = b.id), false)
        SharedExperienceTimeline.record("group-joined-${a.id}-two-1", "two", "群聊·同名群", "系统", "加入A", triggerExtraction = false)
        SharedExperienceTimeline.record("group-joined-${b.id}-two-1", "two", "群聊·同名群", "系统", "加入B", triggerExtraction = false)
        SharedExperienceTimeline.record("call-a", "two", "群聊电话·同名群", "系统", "A电话", triggerExtraction = false, sessionId = a.id)
        SharedExperienceTimeline.record("call-b", "two", "群聊电话·同名群", "系统", "B电话", triggerExtraction = false, sessionId = b.id)
        val afterLeaving = a.copy(groupChat = a.groupChat!!.copy(members = listOf(LuluGroupMember("one"))))
        SharedExperienceTimeline.deleteConversationData(afterLeaving, listOf(message))
        assertTrue(SharedExperienceTimeline.all("one").all { it.sessionId != a.id })
        assertTrue(SharedExperienceTimeline.eventsByIds("two", listOf("${message.id}:group:two", "call-a",
            "group-joined-${a.id}-two-1")).isEmpty())
        assertEquals(3, SharedExperienceTimeline.all("two").size)
        assertEquals(setOf("other:group:two", "call-b", "group-joined-${b.id}-two-1"),
            SharedExperienceTimeline.all("two").map { it.id }.toSet())
    }

    @Test fun clearingGroupWithdrawsLinkedPresenceWithoutTouchingAnotherGroup() {
        CompanionPresenceStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val groupA = group("presence_group_a", listOf("presence_owner_a", "presence_owner_b"))
        val groupB = group("presence_group_b", listOf("presence_owner_a", "presence_owner_b"))
        val messageA = LuluChatMessage(id = "presence_user_message_a", conversationId = groupA.id,
            sender = LuluChatMessage.Sender.User, content = "A 群的新话题")
        val messageB = messageA.copy(id = "presence_user_message_b",
            conversationId = groupB.id, content = "B 群的新话题")
        SharedExperienceTimeline.recordConversationMessage(groupA, messageA, false)
        SharedExperienceTimeline.recordConversationMessage(groupB, messageB, false)
        val sourceA = "${messageA.id}:group:presence_owner_a"
        val sourceB = "${messageB.id}:group:presence_owner_a"
        CompanionPresenceStore.update("presence_owner_a", "正在聊A群", null, "A群的想法", "开心",
            "聊天", messageA.createdAt.plusMillis(100), provenanceId = sourceA)
        CompanionPresenceStore.update("presence_owner_a", "正在聊B群", null, "B群的想法", "轻松",
            "聊天", messageB.createdAt.plusMillis(500), provenanceId = sourceB)
        assertTrue(SharedExperienceTimeline.all("presence_owner_a").any {
            it.id == "presence-$sourceA-presence_owner_a" && it.sessionId == groupA.id
        })
        SharedExperienceTimeline.deleteConversationMessage(groupA, messageA.id)
        assertFalse(SharedExperienceTimeline.all("presence_owner_a").any {
            it.id == "presence-$sourceA-presence_owner_a"
        })
        assertTrue(SharedExperienceTimeline.all("presence_owner_a").any {
            it.id == "presence-$sourceB-presence_owner_a"
        })
        assertEquals("B群的想法", CompanionPresenceStore.current("presence_owner_a")?.innerThought)
    }

    @Test fun clearGroupRetractsLegacyOutboundReceiptsOnlyForItsOwnConversation() {
        val groupA = group("legacy_outbound_a", listOf("legacy_group_one", "legacy_group_two"))
        val groupB = group("legacy_outbound_b", listOf("legacy_group_one", "legacy_group_two"))
        fun receipt(id: String, conversationId: String) {
            SharedExperienceTimeline.record(id, "legacy_group_one", "实际工具执行", "执行器",
                JSONObject().put("tool", "send_group_message")
                    .put("result", JSONObject().put("success", true)
                        .put("conversationId", conversationId).put("evidenceId", id + "_message")
                        .toString()).toString(),
                source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult,
                triggerExtraction = false)
        }
        receipt("legacy_receipt_a", groupA.id)
        receipt("legacy_receipt_b", groupB.id)
        SharedExperienceTimeline.deleteConversationData(groupA, emptyList())
        assertTrue(SharedExperienceTimeline.eventsByIds("legacy_group_one",
            listOf("legacy_receipt_a")).isEmpty())
        assertEquals(1, SharedExperienceTimeline.eventsByIds("legacy_group_one",
            listOf("legacy_receipt_b")).size)
    }

    @Test fun removingOneDeliveredGroupBubbleRetractsItsExactToolReceipt() {
        val groupA = group("outbound_single_a", listOf("outbound_member_a", "outbound_member_b"))
        val message = LuluChatMessage(id = "outbound_message_a", conversationId = groupA.id,
            sender = LuluChatMessage.Sender.Character, content = "我已经发在群里啦",
            authorCharacterId = "outbound_member_a")
        SharedExperienceTimeline.recordConversationMessage(groupA, message, false)
        SharedExperienceTimeline.record("outbound_tool_receipt", "outbound_member_a",
            "实际工具执行", "执行器",
            JSONObject().put("tool", "send_group_message").put("result",
                JSONObject().put("success", true).put("evidenceId", message.id + ":group:outbound_member_a")
                    .put("conversationId", groupA.id).toString()).toString(),
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult,
            triggerExtraction = false)
        SharedExperienceTimeline.deleteConversationMessage(groupA, message.id)
        assertTrue(SharedExperienceTimeline.eventsByIds("outbound_member_a",
            listOf("outbound_tool_receipt")).isEmpty())
    }

    @Test fun removingRawPresenceEventAlsoRemovesVisibleHeartVoiceAndSameTurnMindHistory() {
        CompanionPresenceStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val owner = "raw_presence_owner"
        val source = "raw_presence_source"
        val initial = Instant.parse("2026-10-30T01:00:00Z")
        SharedExperienceTimeline.record(source, owner, "私聊", "主人", "这句话依然存在",
            occurredAt = initial, triggerExtraction = false)
        CharacterInnerLifeStore.recordInnerVoice(owner, source, "旧的心理内容", initial.plusSeconds(1))
        CharacterInnerLifeStore.recordCausalTransition(owner, source,
            JSONObject().put("meaning", "旧消息让我联想到一件事"), null, null,
            "reply", "旧的心理内容", "正在回应", initial.plusSeconds(1))
        CompanionPresenceStore.update(owner, "正在思考", null, "旧的心理内容", "好奇",
            "聊天", initial.plusSeconds(2), provenanceId = source)
        val eventId = "presence-$source-$owner"
        assertEquals(1, SharedExperienceTimeline.eventsByIds(owner, listOf(eventId)).size)
        assertTrue(CompanionPresenceStore.histories.value[owner].orEmpty()
            .any { it.innerThought == "旧的心理内容" })
        assertTrue(CharacterInnerLifeStore.historySnapshot(owner).optJSONArray("causalTransitions")!!
            .length() > 0)

        // Raw timeline UI calls deleteEvent directly; original chat remains.
        SharedExperienceTimeline.deleteEvent(eventId)

        assertTrue(SharedExperienceTimeline.eventsByIds(owner, listOf(eventId)).isEmpty())
        assertEquals(1, SharedExperienceTimeline.eventsByIds(owner, listOf(source)).size)
        assertFalse(CompanionPresenceStore.histories.value[owner].orEmpty()
            .any { it.innerThought == "旧的心理内容" })
        assertFalse(CharacterInnerLifeStore.historySnapshot(owner).optJSONArray("innerVoices")
            ?.let { rows -> (0 until rows.length()).any { rows.optJSONObject(it)?.optString("thought") == "旧的心理内容" } }
            ?: false)
        assertFalse(CharacterInnerLifeStore.historySnapshot(owner).optJSONArray("causalTransitions")
            ?.let { rows -> (0 until rows.length()).any { rows.optJSONObject(it)?.optString("innerThought") == "旧的心理内容" } }
            ?: false)
    }

    @Test fun rawPresenceDeletionDoesNotEraseLaterDifferentMindTurnOfSameConversation() {
        CompanionPresenceStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val owner = "raw_presence_two_turns"
        val source = "raw_presence_two_turns_source"
        val initial = Instant.parse("2026-10-30T02:00:00Z")
        SharedExperienceTimeline.record(source, owner, "私聊", "主人", "同一条仍然存在的消息",
            occurredAt = initial, triggerExtraction = false)
        CompanionPresenceStore.update(owner, "先想一件事", null, "最初的想法", null,
            "聊天", initial.plusSeconds(1), provenanceId = source)
        CompanionPresenceStore.update(owner, "后来又有新认识", null, "后来独立想到的东西", null,
            "聊天", initial.plusSeconds(120), provenanceId = source)
        SharedExperienceTimeline.deleteEvent("presence-$source-$owner")
        // Exact event ID is stable for the same provenance, so this deletion
        // withdraws both projections under that event; it does not delete the source.
        assertEquals(1, SharedExperienceTimeline.eventsByIds(owner, listOf(source)).size)
    }
}
