package com.jiacimu.lulu

import com.jiacimu.lulu.data.ConversationHistoryWindow
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.PrivateChatExpressionContext
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SemanticDialogueWindowRegressionTest {
    private val at = Instant.parse("2026-11-17T00:00:00Z")

    private fun user(i: Int) = LuluChatMessage(
        id = "u-$i", conversationId = "chat", sender = LuluChatMessage.Sender.User,
        content = "主人第${i}次完整发言", createdAt = at.plusSeconds(i * 10L))

    private fun role(i: Int, bubble: Int) = LuluChatMessage(
        id = "r-$i-$bubble", conversationId = "chat",
        sender = LuluChatMessage.Sender.Character, authorCharacterId = "role",
        replyBatchId = "batch-$i", content = "江渡第${i}轮气泡$bubble",
        createdAt = at.plusSeconds(i * 10L + bubble + 1L))

    private fun transcript() = buildList {
        repeat(18) { i ->
            add(user(i))
            repeat(3) { bubble -> add(role(i, bubble)) }
        }
        add(user(18))
    }

    @Test fun transportCountsThreeBubblesAsOneTurnNotThreeMessages() {
        val previous = transcript().dropLast(1)
        val selected = ConversationHistoryWindow.select(
            previous, maxMessages = Int.MAX_VALUE, maxCharacters = 12_000,
            maxTurns = 35) { it.content.length }
        assertEquals(71, selected.size) // 17 user + 18 * 3 role bubbles
        assertEquals(35, ConversationHistoryWindow.turns(selected).size)
        assertFalse(selected.any { it.id == "u-0" })
        assertTrue(selected.any { it.id == "r-0-0" })
        assertTrue(selected.any { it.id == "r-0-1" })
        assertTrue(selected.any { it.id == "r-0-2" })
    }

    @Test fun privateDecisionAndExpressionBothRetainThirtyFiveActualTurns() {
        val app = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        val messages = transcript()
        val reply = PrivateChatExpressionContext.conversation(
            characterId = "role", messages = messages,
            currentUserMessageIds = setOf("u-18"),
            currentInput = "主人第18次完整发言")
        assertEquals(36, reply.size) // 35 prior turns + the current user turn
        assertFalse(reply.any { it.content.contains("主人第0次完整发言") })
        assertTrue(reply.any { it.content.contains("江渡第0轮气泡0") &&
            it.content.contains("江渡第0轮气泡2") })
        assertTrue(reply.last().content.contains("主人第18次完整发言"))
        val planner = buildBoundedHistory(messages.dropLast(1), "江渡")
        assertFalse(planner.contains("主人第0次完整发言"))
        assertTrue(planner.contains("江渡第0轮气泡0"))
        assertTrue(planner.contains("江渡第0轮气泡2"))
        assertTrue(planner.contains("主人第17次完整发言"))
    }

    @Test fun explicitLegacyMessageCapRemainsAvailableForOtherCallers() {
        val previous = transcript().dropLast(1)
        val latest = ConversationHistoryWindow.select(previous, 1, 12_000) { it.content.length }
        assertEquals(3, latest.size)
        assertTrue(latest.all { it.replyBatchId == "batch-17" })
    }
}
