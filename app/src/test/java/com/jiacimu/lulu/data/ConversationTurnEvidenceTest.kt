package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConversationTurnEvidenceTest {
    private val now = Instant.parse("2026-10-16T01:30:00Z")
    private fun own(id: String, content: String) = SharedTimelineEvent(id, "role", "私聊", "本人",
        content, now.minusSeconds(60), sessionId = "chat", source = "message",
        evidenceKind = EventEvidenceKind.CharacterStatement)

    @Test fun userQuotesOtherSpeakersAndOtherScenesCannotBecomeOwnOpenings() {
        val actual = own("actual", "在呢在呢，来了！")
        val events = listOf(actual,
            own("user", "用户引用的句子").copy(evidenceKind = EventEvidenceKind.UserStatement),
            own("other-role", "其他角色的话").copy(characterId = "other"),
            own("other-chat", "另一段私聊").copy(sessionId = "other-chat"),
            own("diary", "自己日记里的句子").copy(source = "diary"),
            own("future", "未来尚未发生的台词").copy(occurredAt = now.plusSeconds(60)))
        assertEquals(listOf(actual.content), ConversationTurnEconomy.recentOwnReplies(events, "role", "chat", now))
        assertTrue(ConversationTurnEconomy.recentOwnReplies(events, "role", "", now).isEmpty())
    }

    @Test fun cuesAreBoundedAndDisappearWhenTheActualMessageIsRemoved() {
        val events = (0..5).map { i -> own("$i", "开头$i").copy(occurredAt = now.minusSeconds(20L - i)) }
        assertEquals(listOf("开头2", "开头3", "开头4", "开头5"),
            ConversationTurnEconomy.recentOwnReplies(events.reversed(), "role", "chat", now))
        assertFalse(ConversationTurnEconomy.recentOwnReplies(events.filterNot { it.id == "5" }, "role", "chat", now)
            .contains("开头5"))
    }
}
