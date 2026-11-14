package com.jiacimu.lulu

import com.jiacimu.lulu.games.GameInvitationCatalog
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

    @Test fun unknownOrMalformedInvitationsCannotBecomeActionableCards() {
        assertNull(parseGameInvite("[游戏邀约|not_a_game|假游戏] 点击开始"))
        assertNull(parseGameInvite("[游戏邀约|relic_escape|迷雾摸金"))
        assertNull(parseGameInvite("咱们去玩迷雾摸金吧"))
    }
}
