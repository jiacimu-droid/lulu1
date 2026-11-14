package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionOnlineStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class GroupChatTurnPlannerTest {
    @Test fun unavailableDirectorNeverNominatesAnUnaddressedFirstSpeaker() {
        assertTrue(GroupChatTurnPlanner.fallbackForUnplannedTurn(
            emptyList(), listOf("first", "second"), 2).beats.isEmpty())
        val directed = GroupChatTurnPlanner.fallbackForUnplannedTurn(
            listOf("second", "unknown", "second"), listOf("first", "second"), 2)
        assertEquals(listOf("second"), directed.beats.map { it.speakerId })
        assertTrue(GroupChatTurnPlanner.fallbackForUnplannedTurn(
            listOf("second"), listOf("first", "second"), 0).beats.isEmpty())
    }

    @Test fun onlyListedMembersCanReceiveRepeatedOpportunities() {
        val ids = listOf("role-a", "role-b", "role-c")
        val raw = """{"speakerIds":["role-b","intruder","role-b","role-a","role-c"]}"""
        assertEquals(listOf("role-b", "role-b"), GroupChatTurnPlanner.decode(raw, ids, 2))
        assertEquals(listOf("role-b", "role-b", "role-a", "role-c"),
            GroupChatTurnPlanner.decode(raw, ids, 8))
    }

    @Test fun realPauseIsDifferentFromMalformedPlannerOutput() {
        val ids = listOf("a", "b")
        assertEquals(emptyList<String>(), GroupChatTurnPlanner.decode("""{"speakerIds":[]}""", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("no json", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("""{"other":true}""", ids, 3))
        assertEquals(listOf("b"), GroupChatTurnPlanner.decode(
            """```json\n{"speakerIds":["b"]}\n```""", ids, 3))
        // An invalid nonempty plan is not a voluntary decision to go silent.
        assertNull(GroupChatTurnPlanner.decode("""{"beats":[{"speakerId":"not-in-room"}]}""", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("""{"speakerIds":["not-in-room"]}""", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("""{"beats":["invalid-item"]}""", ids, 3))
    }


    @Test fun directorPlanKeepsMultipleTurnsForSameSpeakerWithTheirOwnTargets() {
        val raw = """{"sceneFocus":"用户刚介绍两位群友","beats":[
          {"speakerId":"a","targetId":"user","focus":"回应介绍","direction":"一句话接住"},
          {"speakerId":"b","targetId":"a","focus":"接刚才的真实发言","direction":"按本人立场回应"},
          {"speakerId":"a","direction":"不能重复"},
          {"speakerId":"intruder","direction":"不能插入"}
        ],"ending":"留给主人插话"}"""
        val plan = GroupChatTurnPlanner.decodePlan(raw, listOf("a", "b"), 5)!!
        assertEquals(listOf("a", "b", "a"), plan.beats.map { it.speakerId })
        assertEquals("a", plan.beats[1].targetId)
        assertEquals("与用户互动", plan.hintFor(plan.beats[0], emptyMap()))
        assertEquals("与星禾互动", plan.hintFor(plan.beats[1], mapOf("a" to "星禾")))
        assertEquals("", plan.hintFor(plan.beats[2], emptyMap()))
        assertEquals(listOf("a", "b", "a"), GroupChatTurnPlanner.decode(
            """{"beats":[{"speakerId":"a"},{"speakerId":"b"},{"speakerId":"a"}]}""",
            listOf("a", "b"), 3))
    }

    @Test fun invalidTargetCannotInjectUnknownParticipantAndPauseIsPreserved() {
        val raw = """{"beats":[{"speakerId":"a","targetId":"outsider","direction":"对话"}]}"""
        val plan = GroupChatTurnPlanner.decodePlan(raw, listOf("a", "b"), 2)!!
        assertNull(plan.beats.single().targetId)
        assertNull(GroupChatTurnPlanner.decodePlan(
            """{"beats":[{"speakerId":"a","targetId":"user"}]}""",
            listOf("a", "b"), 2, allowUserTarget = false,
        )!!.beats.single().targetId)
        assertEquals(emptyList<String>(), GroupChatTurnPlanner.decode(
            """{"beats":[]}""", listOf("a", "b"), 3))
        assertEquals(listOf("b"), GroupChatTurnPlanner.decode(
            """{"speakerIds":["b"]}""", listOf("a", "b"), 3))
    }

    @Test fun directorProseCannotCrossIntoRoleHints() {
        val raw = """{"sceneFocus":"私下的关系","beats":[
          {"speakerId":"a","focus":"未经公开的事实","direction":"转述乙的设定"},
          {"speakerId":"b","targetId":"a","direction":"替甲发言"}
        ],"ending":"泄露导演掌握的私人资料"}"""
        val scene = GroupChatTurnPlanner.decodePlan(raw, listOf("a", "b"), 2)!!
        assertEquals("", scene.hintFor("a", emptyMap()))
        assertEquals("与江渡互动", scene.hintFor("b", mapOf("a" to "江渡")))
        assertEquals("", scene.hintFor("c", emptyMap()))
    }

    @Test fun nextParticipantAnchorsOnLatestDeliveredSpeechRatherThanRestartingOwnerQuestion() {
        val owner = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.User,
            content = "大家今天怎么样？",
        )
        val a = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "a", content = "我发现一件好玩的事。",
        )
        val failed = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "b", content = "根本没有送达的草稿",
            status = com.jiacimu.lulu.data.LuluChatMessage.Status.Failed,
        )
        val system = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.System,
            content = "成员变更通知",
        )
        val names = mapOf("a" to "甲", "b" to "乙")
        assertEquals("当前群里最后一条真实送达的发言（不要求你一定回复）：甲：我发现一件好玩的事。",
            groupChatLiveReplyAnchor(listOf(owner, a, failed, system), names, "我"))
        assertEquals("当前群里最后一条真实送达的发言（不要求你一定回复）：我：大家今天怎么样？",
            groupChatLiveReplyAnchor(listOf(owner, failed, system), names, "我"))
        assertEquals("", groupChatLiveReplyAnchor(listOf(failed, system), names, "我"))
    }

    @Test fun quotedGroupPeerNeverReceivesOwnerOnlyAddressRepair() {
        val group = com.jiacimu.lulu.data.LuluGroupChat(
            name = "聊天", members = listOf(
                com.jiacimu.lulu.data.LuluGroupMember("a"),
                com.jiacimu.lulu.data.LuluGroupMember("b"),
            ),
        )
        val owner = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "group", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.User,
            content = "主人第三条",
        )
        val peer = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "group", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "b", content = "B的第二条",
        )
        val failedOwner = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "group", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.User,
            content = "发送失败", status = com.jiacimu.lulu.data.LuluChatMessage.Status.Failed,
        )
        val messages = listOf(peer, owner, failedOwner)
        assertTrue(com.jiacimu.lulu.data.shouldApplyOwnerRelationshipRepair(
            null, peer.id, messages))
        assertTrue(com.jiacimu.lulu.data.shouldApplyOwnerRelationshipRepair(
            group, owner.id, messages))
        assertFalse(com.jiacimu.lulu.data.shouldApplyOwnerRelationshipRepair(
            group, peer.id, messages))
        assertFalse(com.jiacimu.lulu.data.shouldApplyOwnerRelationshipRepair(
            group, failedOwner.id, messages))
        assertFalse(com.jiacimu.lulu.data.shouldApplyOwnerRelationshipRepair(
            group, null, messages))
        assertFalse(com.jiacimu.lulu.data.shouldApplyOwnerRelationshipRepair(
            group, "unknown", messages))
    }

    @Test fun groupRolesCanAnswerEarlierDeliveredSpeechOrLaterMemberReplies() {
        val group = com.jiacimu.lulu.data.LuluGroupChat(
            name = "小群", members = listOf(
                com.jiacimu.lulu.data.LuluGroupMember("a"),
                com.jiacimu.lulu.data.LuluGroupMember("b"),
            ),
        )
        val first = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "a", content = "第一句由A说",
        )
        val second = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "b", content = "第二句由B说",
        )
        val owner = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.User,
            content = "第三句我说",
        )
        val fourth = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "a", content = "第四句A回答我",
        )
        val failed = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.Character,
            authorCharacterId = "b", content = "草稿不能当事实",
            status = com.jiacimu.lulu.data.LuluChatMessage.Status.Failed,
        )
        val system = com.jiacimu.lulu.data.LuluChatMessage(
            conversationId = "room", sender = com.jiacimu.lulu.data.LuluChatMessage.Sender.System,
            content = "系统提示",
        )
        val messages = listOf(first, second, owner, fourth, failed, system)
        val choices = groupChatRecentReplyOptions(messages, mapOf("a" to "甲", "b" to "乙"), "我")
        listOf("甲：第一句由A说", "乙：第二句由B说", "我：第三句我说",
            "甲：第四句A回答我").forEach { assertTrue(choices, choices.contains(it)) }
        assertFalse(choices.contains("草稿不能当事实"))
        assertFalse(choices.contains("系统提示"))
        // B may answer A's fourth line; A may still choose B's second line.
        assertEquals(fourth.id, validatedCharacterQuoteTarget(messages, group, "b", setOf(owner.id), fourth.id))
        assertEquals(second.id, validatedCharacterQuoteTarget(messages, group, "a", setOf(owner.id), second.id))
        assertEquals(owner.id, validatedCharacterQuoteTarget(messages, group, "a", emptySet(), owner.id))
        assertNull(validatedCharacterQuoteTarget(messages, group, "a", setOf(owner.id), failed.id))
        assertNull(validatedCharacterQuoteTarget(messages, group, "a", setOf(owner.id), system.id))
        assertNull(validatedCharacterQuoteTarget(messages, group, "stranger", setOf(owner.id), fourth.id))
        assertNull(validatedCharacterQuoteTarget(messages, null, "a", setOf(owner.id), fourth.id))
        assertEquals(owner.id, validatedCharacterQuoteTarget(messages, null, "a", setOf(owner.id), owner.id))
    }

    @Test fun modelAuthoredGroupBubblesAreRealSendBoundariesNotPostHocSentenceSplits() {
        val raw = """{"action":"reply","bubbles":[
            {"text":"啊？"},
            {"text":"你说的是刚才那个吗？"}],
            "reason":"想问清楚"}"""
        val authored = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(raw)
        assertEquals(listOf("啊？", "你说的是刚才那个吗？"), authored)
        val flow = parseGroupReplyFlow(authored!!.joinToString(SemanticBubbleSeparator))
        assertTrue(flow.authoredBubbleBoundaries)
        val plan = characterOutgoingBubblePlan(CharacterReplyPresentation(
            flow.content, preserveAuthoredBubbleBoundaries = flow.authoredBubbleBoundaries))
        assertEquals(listOf("啊？", "你说的是刚才那个吗？"), plan.map { it.text })

        val completeLong = "我把这事想明白了，不过里面确实有几种不同的可能，我们需要逐个核对真实的证据。"
        val one = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(
            """{"action":"reply","bubbles":[{"text":"$completeLong"}]}""")!!
        val chosen = parseGroupReplyFlow(one.joinToString(SemanticBubbleSeparator))
        assertEquals(listOf(completeLong),
            characterOutgoingBubblePlan(CharacterReplyPresentation(chosen.content,
                preserveAuthoredBubbleBoundaries = chosen.authoredBubbleBoundaries)).map { it.text })
        // Old models with only text still follow the existing readable fallback.
        assertEquals(listOf("我就想问一下。"), com.jiacimu.lulu.data.ModelStructuredOutput
            .completedReplyBubbles("""{"action":"reply","text":"我就想问一下。"}"""))
    }

    @Test fun groupRoleAttemptNeverRetriesBlankToolOnlyOrFailedResults() = kotlinx.coroutines.runBlocking {
        val outcomes = listOf(
            Result.success(com.jiacimu.lulu.ai.ModelReply("")),
            Result.success(com.jiacimu.lulu.ai.ModelReply("""{"action":"tool","text":""}""")),
            Result.failure<com.jiacimu.lulu.ai.ModelReply>(IllegalStateException("gateway unavailable")),
            Result.success(com.jiacimu.lulu.ai.ModelReply("啊？")),
        )
        outcomes.forEach { expected ->
            var calls = 0
            val actual = requestGroupRoleOnce(
                "role-a", "visible history", "group prompt", "selected model", "archive-a",
                "group scene", "group trace", listOf("message:group:role-a"),
            ) { id, history, prompt, label, archive, scene, turn, evidence ->
                calls++
                assertEquals("role-a", id)
                assertEquals("visible history", history)
                assertEquals("group prompt", prompt)
                assertEquals("selected model", label)
                assertEquals("archive-a", archive)
                assertEquals("group scene", scene)
                assertEquals("group trace", turn)
                assertEquals(listOf("message:group:role-a"), evidence)
                expected
            }
            assertEquals("one request even for empty, tool-only and failure", 1, calls)
            assertEquals(expected.getOrNull()?.text, actual.getOrNull()?.text)
            assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        }
    }

    @Test fun groupDeferredActionsRequireACompletePersistedDelivery() {
        val complete = RoleReplyDeliveryReceipt(
            text = "真的发出去了", messageIds = listOf("message-1"), complete = true,
        )
        org.junit.Assert.assertTrue(groupDeferredActionsMayRun(complete, true))
        org.junit.Assert.assertFalse(groupDeferredActionsMayRun(complete, false))
        org.junit.Assert.assertFalse(groupDeferredActionsMayRun(
            complete.copy(text = ""), true,
        ))
        org.junit.Assert.assertFalse(groupDeferredActionsMayRun(
            complete.copy(messageIds = emptyList()), true,
        ))
        org.junit.Assert.assertFalse(groupDeferredActionsMayRun(
            complete.copy(complete = false), true,
        ))
        org.junit.Assert.assertFalse(groupDeferredActionsMayRun(
            complete.copy(superseded = true), true,
        ))
        // A recalled final bubble can leave real earlier messages, but it
        // cannot authorize the model's deferred actions for the whole turn.
        org.junit.Assert.assertFalse(groupDeferredActionsMayRun(
            RoleReplyDeliveryReceipt(text = "只发出第一条", messageIds = listOf("message-1"),
                complete = false), true,
        ))
    }

    @Test fun onlyOwnerGroupInputUsesForegroundCoordinator() {
        assertTrue(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = true, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = false, userMessage = true, appVisible = true, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = false, appVisible = true, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = false, inGroupCall = false))
        // Cold start has no lifecycle visibility evidence yet. A nullable
        // foreground signal must neither fail Kotlin compilation nor launch
        // a duplicate foreground coordinator alongside background delivery.
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = null, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = true, inGroupCall = true))
    }
    @Test fun incompleteReplyNeverRunsTurnLevelFavoriteOrPoke() {
        // Complete visible persistence is the only condition for turn-level
        // side effects; a recalled bubble is not a delivered turn.
        assertFalse(replyTurnSideEffectsMayRun(0, 0, stale = false))
        assertFalse(replyTurnSideEffectsMayRun(0, 2, stale = false))
        assertFalse(replyTurnSideEffectsMayRun(1, 2, stale = false))
        assertFalse(replyTurnSideEffectsMayRun(1, 1, stale = true))
        assertFalse(replyTurnSideEffectsMayRun(2, 2, stale = true))
        assertTrue(replyTurnSideEffectsMayRun(1, 1, stale = false))
        assertTrue(replyTurnSideEffectsMayRun(2, 2, stale = false))
    }

}
