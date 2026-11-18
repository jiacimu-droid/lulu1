package com.jiacimu.lulu

import com.jiacimu.lulu.games.GameInvitationCatalog
import com.jiacimu.lulu.data.gameInvitationOutgoingContents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameInviteMessageParserTest {
    @Test fun everyCurrentGameHallInviteUsesCanonicalTitleAndSafeRoute() {
        GameInvitationCatalog.titles.forEach { (id, title) ->
            val parsed = parseGameInvite("[游戏邀约|$id|旧标题或伪造名称] 走，来玩这一局")
            assertEquals(id, parsed?.gameId)
            assertEquals(title, parsed?.title)
            assertEquals("走，来玩这一局", parsed?.message)
        }
    }

    @Test fun optionalInviteWordsAreSeparatePersistedMessagesBeforeTheCleanCard() {
        val text = "要不要先来一局？" + SemanticBubbleSeparator + "我当出题者也可以。"
        val messages = gameInvitationOutgoingContents("turtle_soup", "海龟汤", text)
        assertEquals(
            listOf("要不要先来一局？", "我当出题者也可以。", "[游戏邀约|turtle_soup|海龟汤]"),
            messages,
        )
        assertEquals("", parseGameInvite(messages.last())?.message)
        assertNull(parseGameInvite(messages.first()))
    }

    @Test fun inviteWithoutWordsDoesNotGenerateCannedSpeech() {
        val messages = gameInvitationOutgoingContents("memory_match", "记忆配对", "  ")
        assertEquals(listOf("[游戏邀约|memory_match|记忆配对]"), messages)
        assertEquals("", parseGameInvite(messages.single())?.message)
    }

    @Test fun existingInlineInviteTextRemainsReadableOutsideTheCard() {
        val legacy = parseGameInvite("[游戏邀约|turtle_soup|海龟汤] " +
            "这段原本在卡片里的长邀请语，现在应该完整保留在普通聊天气泡，而不是四行省略号。")
        assertEquals("这段原本在卡片里的长邀请语，现在应该完整保留在普通聊天气泡，而不是四行省略号。", legacy?.message)
    }

    @Test fun unknownOrMalformedInvitationsCannotBecomeActionableCards() {
        assertNull(parseGameInvite("[游戏邀约|not_a_game|假游戏] 点击开始"))
        assertNull(parseGameInvite("[游戏邀约|relic_escape|迷雾摸金"))
        assertNull(parseGameInvite("咱们去玩迷雾摸金吧"))
    }
}
