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

    @Test fun onlyListedMembersCanReceiveAnOpportunity() {
        val ids = listOf("role-a", "role-b", "role-c")
        val raw = """{"speakerIds":["role-b","intruder","role-b","role-a","role-c"]}"""
        assertEquals(listOf("role-b", "role-a"), GroupChatTurnPlanner.decode(raw, ids, 2))
        assertEquals(listOf("role-b", "role-a", "role-c"),
            GroupChatTurnPlanner.decode(raw, ids, 8))
    }

    @Test fun realPauseIsDifferentFromMalformedPlannerOutput() {
        val ids = listOf("a", "b")
        assertEquals(emptyList<String>(), GroupChatTurnPlanner.decode("""{"speakerIds":[]}""", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("no json", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("""{"other":true}""", ids, 3))
        assertEquals(listOf("b"), GroupChatTurnPlanner.decode(
            """```json\n{"speakerIds":["b"]}\n```""", ids, 3))
    }


    @Test fun directorPlanKeepsDistinctActorBeatsAndTargets() {
        val raw = """{"sceneFocus":"用户刚介绍两位群友","beats":[
          {"speakerId":"a","targetId":"user","focus":"回应介绍","direction":"一句话接住"},
          {"speakerId":"b","targetId":"a","focus":"接刚才的真实发言","direction":"按本人立场回应"},
          {"speakerId":"a","direction":"不能重复"},
          {"speakerId":"intruder","direction":"不能插入"}
        ],"ending":"留给主人插话"}"""
        val plan = GroupChatTurnPlanner.decodePlan(raw, listOf("a", "b"), 5)!!
        assertEquals(listOf("a", "b"), plan.beats.map { it.speakerId })
        assertEquals("a", plan.beats[1].targetId)
        assertTrue(plan.hintFor("a", mapOf("a" to "星禾", "b" to "江渡")).contains("一句话接住"))
        assertFalse(plan.hintFor("a", emptyMap()).contains("按本人立场回应"))
        assertTrue(plan.hintFor("b", mapOf("a" to "星禾")).contains("与星禾互动"))
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

    @Test fun oneActorNeverReceivesAnotherActorsSuggestedDialogue() {
        val scene = GroupChatScenePlan(
            listOf(
                GroupChatSceneBeat("a", direction = "只属于甲的建议"),
                GroupChatSceneBeat("b", direction = "只属于乙的建议"),
            ),
        )
        assertTrue(scene.hintFor("a", emptyMap()).contains("只属于甲的建议"))
        assertFalse(scene.hintFor("a", emptyMap()).contains("只属于乙的建议"))
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
}
