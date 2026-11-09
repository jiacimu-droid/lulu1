package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandInvitationTest {
    @Test fun acceptsOnlyARealBooleanDecisionAndKeepsItsSpokenReply() {
        val yes = IslandInvitation.parse("""{"accept":true,"speech":"好呀，出发"}""")
        assertEquals(true, yes?.accepted)
        assertEquals("好呀，出发", yes?.speech)
        val no = IslandInvitation.parse("""{"accept":false,"speech":"今天想先休息"}""")
        assertEquals(false, no?.accepted)
        assertEquals("今天想先休息", no?.speech)
    }

    @Test fun invalidOrPartialAnswerCannotSilentlyBecomeAcceptance() {
        assertNull(IslandInvitation.parse(""))
        assertNull(IslandInvitation.parse("我来"))
        assertNull(IslandInvitation.parse("""{"speech":"好的"}"""))
        assertNull(IslandInvitation.parse("""{"accept":"true"}"""))
        assertNull(IslandInvitation.parse("""{"accept":null}"""))
        assertNull(IslandInvitation.parse("""{"accept":1}"""))
        assertNull(IslandInvitation.parse("""{"accept":true"""))
        assertNull(IslandInvitation.parse("""{"accept":false,"speech":"不"}""".dropLast(1)))
    }
}
