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
}
