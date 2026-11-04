package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AutonomousSocialWakeAnchorsTest {
    private val t = Instant.parse("2026-11-05T08:00:00Z")

    @Test fun groupOnlyMembersEachGetTheirOwnRealGroupWakeAnchor() {
        val group = LuluConversation(
            id = "actual-group", characterId = "a", title = "后宫", updatedAt = t,
            groupChat = LuluGroupChat("后宫", members = listOf(
                LuluGroupMember("a"), LuluGroupMember("b"),
                LuluGroupMember("resident:npc"), LuluGroupMember("retired"))))
        val result = ProactivePerceptionRuntime.autonomousConversationAnchors(
            listOf(group), setOf("a", "b", "resident:npc"))
        assertEquals(setOf("a", "b", "resident:npc"),
            result.map { it.characterId }.toSet())
        assertTrue(result.all { it.id == group.id && it.groupChat != null })
        assertTrue(result.none { it.characterId == "retired" })
        // The resident's default disabled policy still governs paid periodic
        // decisions; this helper only fixes the role-selection omission.
    }

    @Test fun aRealPrivateThreadKeepsPriorityAndNoExtraModelWakeIsAdded() {
        val private = LuluConversation(
            id = "real-dm", characterId = "a", title = "甲", updatedAt = t.minusSeconds(3600))
        val group = LuluConversation(
            id = "actual-group", characterId = "b", title = "好友群",
            updatedAt = t, groupChat = LuluGroupChat("好友群",
                members = listOf(LuluGroupMember("a"), LuluGroupMember("b"))))
        val actual = ProactivePerceptionRuntime.autonomousConversationAnchors(
            listOf(private, group), setOf("a", "b"))
        assertEquals(2, actual.size)
        assertEquals("real-dm", actual.single { it.characterId == "a" }.id)
        assertEquals(group.id, actual.single { it.characterId == "b" }.id)
        assertNotNull(actual.single { it.characterId == "b" }.groupChat)
    }

    @Test fun noUserMessageOrSyntheticPrivateThreadIsIntroducedBySelection() {
        val orphan = LuluConversation("retired", "deleted", "旧私聊", updatedAt = t)
        val study = LuluConversation("a-study-focus", "a", "旧学习页", updatedAt = t)
        assertTrue(ProactivePerceptionRuntime.autonomousConversationAnchors(
            listOf(orphan, study), setOf("a")).isEmpty())
    }
}
