package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class RelicLobbyInvitationTest {
    @Test fun acceptingAndDecliningAreDistinctRealDecisions() {
        val accepted=RelicLobbyInvitation.parse("""{"accept":true,"speech":"走，算我一个"}""")
        assertEquals(true,accepted?.accepted)
        assertEquals("走，算我一个",accepted?.speech)
        val declined=RelicLobbyInvitation.parse("""{"accept":false,"speech":"这次想留在营地"}""")
        assertEquals(false,declined?.accepted)
        assertEquals("这次想留在营地",declined?.speech)
    }

    @Test fun ambiguousOrMalformedResponseCannotAutoJoin() {
        assertNull(RelicLobbyInvitation.parse("""{"speech":"好呀"}"""))
        assertNull(RelicLobbyInvitation.parse("""{"accept":"true","speech":"好呀"}"""))
        assertNull(RelicLobbyInvitation.parse("错误：服务不可用"))
        assertEquals(false,RelicLobbyInvitation.parse(
            "```json\n{\"accept\":false,\"speech\":\"不了\"}\n```")?.accepted)
    }
}
