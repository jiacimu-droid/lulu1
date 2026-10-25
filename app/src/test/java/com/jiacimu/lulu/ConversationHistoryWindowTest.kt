package com.jiacimu.lulu

import com.jiacimu.lulu.data.ConversationHistoryWindow
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.PrivateChatExpressionContext
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.MigratedDomainStores
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConversationHistoryWindowTest {
    private val now = Instant.parse("2026-10-25T11:00:00Z")

    private fun user(id: String, text: String, replyTo: String? = null) =
        LuluChatMessage(id = id, conversationId = "chat", sender = LuluChatMessage.Sender.User,
            content = text, createdAt = now, replyToMessageId = replyTo)

    private fun role(id: String, text: String, batch: String?, author: String = "role") =
        LuluChatMessage(id = id, conversationId = "chat",
            sender = LuluChatMessage.Sender.Character, content = text,
            authorCharacterId = author, createdAt = now, replyBatchId = batch)

    @Test fun newestCharacterReplyIsOneTurnEvenPastMessageAndCharacterBudgets() {
        val all = listOf(user("old", "旧话"),
            role("a", "先听我说。", "same"),
            role("b", "我昨天遇到一件事。", "same"),
            role("c", "想接着和你聊。", "same"))
        val kept = ConversationHistoryWindow.select(all, maxMessages = 2, maxCharacters = 8) { it.content.length }
        assertEquals(listOf("a", "b", "c"), kept.map { it.id })
        assertEquals(1, ConversationHistoryWindow.turns(kept).size)
        assertEquals("先听我说。\n我昨天遇到一件事。\n想接着和你聊。",
            ConversationHistoryWindow.turns(kept).single().joinToString("\n") { it.content })
    }

    @Test fun consecutiveUserBubblesAreNotCutInTheMiddleOfAnIncomingTurn() {
        val all = listOf(role("old", "听着呢。", "earlier"),
            user("u1", "我还有件事"),
            user("u2", "其实刚才那句话的意思是…"))
        val kept = ConversationHistoryWindow.select(all, 1, 10) { it.content.length }
        assertEquals(listOf("u1", "u2"), kept.map { it.id })
        assertEquals(1, ConversationHistoryWindow.turns(kept).size)
    }

    @Test fun distinctCharacterTurnsAndAuthorsDoNotGetMerged() {
        val all = listOf(role("one", "第一个人", "batch", "a"),
            role("two", "第二个人", "batch", "b"),
            role("three", "新一轮", "next", "b"))
        assertEquals(listOf("one", "two", "three"),
            ConversationHistoryWindow.turns(all).map { it.single().id })
    }

    @Test fun longHistoryDoesNotRenderOrDecodeEveryArchivedTurn() {
        val archive = (0 until 5_000).map { index ->
            role("old-$index", "旧消息-$index", null)
        }
        val messages = archive + listOf(role("recent-a", "我刚想到", "latest"),
            role("recent-b", "还能再聊几句", "latest"))
        var inspected = 0
        val recent = ConversationHistoryWindow.select(messages, 2, 100) {
            inspected++
            it.content.length
        }
        assertEquals(listOf("recent-a", "recent-b"), recent.map { it.id })
        assertTrue("Long-lived history must not render thousands of old bubbles",
            inspected <= 3)
    }

    @Test fun quotedImageUsesOnlyExistingDescriptionAndRejectsMissingOrFutureReferences() {
        val image = encodeQqChatImage(imageUri = "content://mock/image",
            caption = "发给你的猫", imageDescription = "一只趴在书上的橘猫")
        val quoted = user("quote", "就是这个！", replyTo = "image")
        val all = listOf(role("image", image, "picture"), quoted)
        val note = ConversationHistoryWindow.quoteNote(quoted, all)
        assertTrue(note.contains("一只趴在书上的橘猫"))
        assertTrue(note.contains("发给你的猫"))
        assertFalse(note.contains("content://"))
        assertEquals("", ConversationHistoryWindow.quoteNote(quoted, all, setOf("image")))
        assertEquals("", ConversationHistoryWindow.quoteNote(
            user("earlier", "这个呢？", replyTo = "later"),
            listOf(user("earlier", "这个呢？", replyTo = "later"),
                role("later", "稍后才说", "last"))))
    }

    @Test fun privateModelContextCombinesOneReplyAndIncludesRealActiveQuote() {
        val image = encodeQqChatImage("content://mock/b", imageDescription = "蓝色风铃")
        val messages = listOf(
            user("old", "晚上好"),
            role("a", "你看", "one-turn"),
            role("b", image, "one-turn"),
            role("c", "我想给你看看这个。", "one-turn"),
            user("current", "第二张好看", replyTo = "b"),
        )
        val history = PrivateChatExpressionContext.conversation(
            characterId = "role", messages = messages,
            currentUserMessageIds = setOf("current"), currentInput = "第二张好看",
            maxMessages = 2, maxCharacters = 30,
        )
        assertEquals(2, history.size)
        assertTrue(history[0].content.contains("你看"))
        assertTrue(history[0].content.contains("蓝色风铃"))
        assertTrue(history[0].content.contains("我想给你看看这个"))
        assertTrue(history[1].content.contains("引用之前的角色消息"))
        assertTrue(history[1].content.contains("蓝色风铃"))
        assertTrue(history[1].content.endsWith("第二张好看"))
    }

    @Test fun renderedGroupHistoryCannotStartWithTheLastOfThreeCharacterBubbles() {
        val app = RuntimeEnvironment.getApplication()
        MigratedDomainStores.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        val messages = listOf(user("older", "很久前说过"),
            role("first", "我有个主意。", "batch"),
            role("second", "要不明天一起看电影？", "batch"),
            role("third", "也可以先吃点东西。", "batch"))
        val history = buildBoundedHistory(messages, "测试角色",
            maxMessages = 1, maxChars = 15)
        assertTrue(history.contains("我有个主意。"))
        assertTrue(history.contains("要不明天一起看电影？"))
        assertTrue(history.contains("也可以先吃点东西。"))
        assertFalse(history.contains("很久前说过"))
    }
}
