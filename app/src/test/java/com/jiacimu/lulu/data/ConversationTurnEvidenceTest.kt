package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConversationTurnEvidenceTest {
    private val role = "conversation-cue-evidence-role"
    private lateinit var conversation: LuluConversation

    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        conversation = MigratedDomainStores.chat.ensureConversation(role, "真实聊天")
    }

    @After fun after() { MemoryTestEnvironment.release() }

    private fun own(content: String): SharedTimelineEvent {
        val message = MigratedDomainStores.chat.appendCharacterMessage(conversation.id, content)
        return SharedExperienceTimeline.eventsByIds(role, listOf(message.id)).single()
    }

    @Test fun userQuotesOtherSpeakersAndOtherScenesCannotBecomeOwnOpenings() {
        val actual = own("在呢在呢，来了！")
        val now = Instant.now().plusSeconds(2)
        val events = listOf(actual,
            actual.copy(id = "quoted-user", content = "用户引用的句子",
                evidenceKind = EventEvidenceKind.UserStatement),
            actual.copy(id = "other-role", content = "其他角色的话", characterId = "other"),
            actual.copy(id = "other-chat", content = "另一段私聊", sessionId = "other-chat"),
            actual.copy(id = "diary", content = "自己日记里的句子", source = "diary"),
            actual.copy(id = "future", content = "未来尚未发生的台词",
                occurredAt = now.plusSeconds(60)))
        assertEquals(listOf(actual.content),
            ConversationTurnEconomy.recentOwnReplies(events, role, conversation.id, now))
        assertTrue(ConversationTurnEconomy.recentOwnReplies(events, role, "", now).isEmpty())
    }

    @Test fun cuesAreBoundedAndDisappearWhenTheActualMessageIsRemoved() {
        val events = (0..5).map { index ->
            val event = own("开头$index")
            // Preserve a stable chronological ordering across fast JVM clocks.
            if (index < 5) Thread.sleep(3)
            event
        }
        val now = Instant.now().plusSeconds(2)
        assertEquals(listOf("开头2", "开头3", "开头4", "开头5"),
            ConversationTurnEconomy.recentOwnReplies(events.reversed(), role, conversation.id, now))
        assertFalse(ConversationTurnEconomy.recentOwnReplies(events.filterNot { it.id == events.last().id },
            role, conversation.id, now).contains("开头5"))
    }
}
