package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PhoneCallTranscriptFilterTest {
    private val start = Instant.parse("2026-10-10T08:00:00Z")
    private fun item(seconds: Long, sender: LuluChatMessage.Sender) = LuluChatMessage(
        conversationId = "private", sender = sender, content = "some content",
        createdAt = start.plusSeconds(seconds),
    )

    @Test fun nothingBeforeConnectedIsIncludedInLiveCaptions() {
        val all = listOf(item(-120, LuluChatMessage.Sender.User),
            item(-40, LuluChatMessage.Sender.Character),
            item(1, LuluChatMessage.Sender.Character),
            item(5, LuluChatMessage.Sender.User))
        assertEquals(all.drop(2), actualPhoneCaptions(all, start))
        assertTrue(actualPhoneCaptions(all, null).isEmpty())
    }

    @Test fun systemNoticesNeverAppearAsPhoneSpeech() {
        val all = listOf(item(0, LuluChatMessage.Sender.System),
            item(2, LuluChatMessage.Sender.Character))
        assertEquals(all.drop(1), actualPhoneCaptions(all, start))
    }
}
