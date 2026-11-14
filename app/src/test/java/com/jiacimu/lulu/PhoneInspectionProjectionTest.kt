package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.ResidentDirectMessage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInspectionProjectionTest {
    private val whenSent = Instant.parse("2026-10-26T01:30:00Z")
    private val chats = listOf(
        LuluConversation(id = "owner-a", characterId = "a", title = "A",
            updatedAt = whenSent),
        LuluConversation(id = "owner-b", characterId = "b", title = "B",
            updatedAt = whenSent),
        LuluConversation(id = "group", characterId = "a", title = "好友群",
            updatedAt = whenSent, groupChat = LuluGroupChat(
                name = "好友群", members = listOf(
                    LuluGroupMember("a"), LuluGroupMember("b")))),
    )
    private val nativeMail = listOf(
        ResidentDirectMessage("mail1", "a", "resident:one",
            "你好", whenSent, "character"),
        ResidentDirectMessage("mail2", "resident:one", "a",
            "嗯", whenSent.plusSeconds(1), "character"),
    )

    @Test fun roleOnlySeesItsOwnThreadsAndJoinedGroups() {
        val alice = inspectedThreads("a", chats, nativeMail, mapOf("resident:one" to "阿一"))
        assertEquals(setOf("chat:owner-a", "chat:group", "mail:resident:one"),
            alice.map { it.key }.toSet())
        assertFalse(alice.any { it.key == "chat:owner-b" })
        val resident = inspectedThreads("resident:one", chats, nativeMail, emptyMap())
        assertEquals(listOf("mail:a"), resident.map { it.key })
    }

    @Test fun oneDeliveredMailProjectsToBothRealParticipants() {
        val from = inspectedThreads("a", emptyList(), nativeMail, emptyMap()).single()
        val to = inspectedThreads("resident:one", emptyList(), nativeMail, emptyMap()).single()
        assertEquals(from.lastText, to.lastText)
        assertEquals(from.updatedAt, to.updatedAt)
        assertEquals("嗯", from.lastText)
    }

    @Test fun ownerAndRoleBubbleSidesAreReversedWithoutChangingMessage() {
        val original = chats.first()
        val userMessage = LuluChatMessage(conversationId = original.id,
            sender = LuluChatMessage.Sender.User, content = "在吗")
        val characterMessage = LuluChatMessage(conversationId = original.id,
            sender = LuluChatMessage.Sender.Character, content = "在呢")
        assertFalse(inspectedChatMessageIsMine("a", original, userMessage))
        assertTrue(inspectedChatMessageIsMine("a", original, characterMessage))
        assertFalse(inspectedChatMessageIsMine("b", original, characterMessage))
    }

    @Test fun savedPrivateOwnerRemarkOverridesLegacyContactNameInPhoneAndThreads() {
        assertEquals("佳辞（主人）", inspectedOwnerContactName("佳辞", "主人"))
        assertEquals("佳辞（主人）", inspectedOwnerContactName("   佳辞  ", "主人"))
        assertEquals("公开昵称", inspectedOwnerContactName("", "公开昵称"))
        assertEquals("主人", inspectedOwnerContactName(" ", ""))

        val renamed = inspectedThreads("a", chats, nativeMail, emptyMap(), "佳辞")
        assertEquals("佳辞（主人）", renamed.single { it.key == "chat:owner-a" }.title)
        assertEquals("好友群", renamed.single { it.key == "chat:group" }.title)
        val unchanged = inspectedThreads("a", chats, nativeMail, emptyMap(), "另一个备注")
        assertEquals("另一个备注（主人）", unchanged.single { it.key == "chat:owner-a" }.title)
        assertEquals("佳辞（主人）", renamed.single { it.key == "chat:owner-a" }.title)
        assertEquals("阿辞", inspectedOwnerContactName("阿辞", "阿辞"))
        assertEquals("旧备注（新名字）", inspectedOwnerContactName("旧备注", "新名字"))
        assertEquals("旧备注（更新后的名字）", inspectedThreads(
            "a", chats, nativeMail, emptyMap(),
            inspectedOwnerContactName("旧备注", "更新后的名字"))
            .single { it.key == "chat:owner-a" }.title)
        assertEquals("主人", inspectedThreads("a", chats, nativeMail, emptyMap())
            .single { it.key == "chat:owner-a" }.title)
    }

    @Test fun inspectedPhoneUsesOwnerSavedAvatarOnlyForOwnerPrivateChat() {
        val ownerThread = inspectedThreads("a", chats, nativeMail, mapOf("resident:one" to "阿一"))
            .single { it.key == "chat:owner-a" }
        val groupThread = inspectedThreads("a", chats, nativeMail, emptyMap())
            .single { it.key == "chat:group" }
        val residentThread = inspectedThreads("a", chats, nativeMail, emptyMap())
            .single { it.key == "mail:resident:one" }

        assertEquals("content://owner/new-photo", inspectedThreadAvatarUri(
            ownerThread, "content://owner/new-photo", "content://character/other",
            "content://group/photo"))
        assertEquals("content://group/photo", inspectedThreadAvatarUri(
            groupThread, "content://owner/new-photo", "content://character/other",
            "content://group/photo"))
        assertEquals("content://resident/photo", inspectedThreadAvatarUri(
            residentThread, "content://owner/new-photo", "content://resident/photo", null))
        // Reprojection after a profile change never relies on a cached role avatar.
        assertEquals("content://owner/next-photo", inspectedThreadAvatarUri(
            ownerThread, "content://owner/next-photo", null, null))
        assertEquals(null, inspectedThreadAvatarUri(ownerThread, null, null, null))
    }

    @Test fun onlyActualGroupAuthorCountsAsSelf() {
        val group = chats.last()
        val fromB = LuluChatMessage(conversationId = group.id,
            sender = LuluChatMessage.Sender.Character, authorCharacterId = "b",
            content = "晚安")
        assertFalse(inspectedChatMessageIsMine("a", group, fromB))
        assertTrue(inspectedChatMessageIsMine("b", group, fromB))
    }
}
