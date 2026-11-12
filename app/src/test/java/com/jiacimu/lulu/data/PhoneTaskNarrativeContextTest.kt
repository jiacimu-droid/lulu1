package com.jiacimu.lulu.data

import com.jiacimu.lulu.encodeQqChatImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTaskNarrativeContextTest {
    private fun message(
        sender: LuluChatMessage.Sender,
        content: String,
        status: LuluChatMessage.Status = LuluChatMessage.Status.Sent,
        authorCharacterId: String? = null,
    ) = LuluChatMessage(
        conversationId = "chat-1", sender = sender, content = content,
        status = status, authorCharacterId = authorCharacterId,
    )

    @Test fun preservesAlreadyShownImageAndSubsequentSelection() {
        val image = encodeQqChatImage(
            imageUri = "https://example.org/product.png",
            caption = "刚刚找到的几款商品",
            imageDescription = "商品截图中可见6.48元的巧克力",
        )
        val context = PhoneTaskNarrativeContext.render(listOf(
            message(LuluChatMessage.Sender.User, "帮我找巧克力"),
            message(LuluChatMessage.Sender.Character, image, authorCharacterId = "role-1"),
            message(LuluChatMessage.Sender.Character, "挑到几款了，你瞧瞧", authorCharacterId = "role-1"),
            message(LuluChatMessage.Sender.User, "那个六块钱的，就买那个"),
        ), "role-1")
        assertTrue(context.contains("本人已发送：[发送图片]"))
        assertTrue(context.contains("6.48元"))
        assertTrue(context.contains("挑到几款了"))
        assertTrue(context.contains("主人：那个六块钱的，就买那个"))
        assertFalse(context.contains("CHAT_IMAGE:"))
    }

    @Test fun excludesUnsentAndOtherCharacterBubbles() {
        val context = PhoneTaskNarrativeContext.render(listOf(
            message(LuluChatMessage.Sender.Character, "不应出现的草稿",
                LuluChatMessage.Status.Sending, "role-1"),
            message(LuluChatMessage.Sender.Character, "其他人物说的",
                authorCharacterId = "role-2"),
            message(LuluChatMessage.Sender.System, "本机设备失败"),
            message(LuluChatMessage.Sender.Character, "真实发出的内容",
                authorCharacterId = "role-1"),
        ), "role-1")
        assertTrue(context.contains("系统记录（不是本人发言）"))
        assertTrue(context.contains("真实发出的内容"))
        assertFalse(context.contains("草稿"))
        assertFalse(context.contains("其他人物"))
    }

    @Test fun boundedToRecentSentMessages() {
        val messages = (1..20).map { message(LuluChatMessage.Sender.User, "第${it}条") }
        val context = PhoneTaskNarrativeContext.render(messages, "role-1", maxMessages = 3)
        assertFalse(context.contains("第17条"))
        assertTrue(context.contains("第18条"))
        assertTrue(context.contains("第20条"))
    }
}
