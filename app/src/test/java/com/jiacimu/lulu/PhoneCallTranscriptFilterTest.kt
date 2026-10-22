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

    @Test fun nonSpeechAsrCaptionsCannotBecomeFakeUserTurns() {
        for (caption in listOf("（不停）", "（笑）（一切）", "（膜气）（不幸）",
            "(laughing)", "【环境音】", "[applause]")) {
            assertFalse(caption, PhoneTranscriptEvidence.isConfirmedSpeech(caption))
        }
        assertTrue(PhoneTranscriptEvidence.isConfirmedSpeech("宝宝，我现在有点累了"))
        assertTrue(PhoneTranscriptEvidence.isConfirmedSpeech("（笑）宝宝，我没生气"))
        assertTrue(PhoneTranscriptEvidence.isConfirmedSpeech("笑"))
        assertFalse(PhoneTranscriptEvidence.isConfirmedSpeech("  "))
    }

    @Test fun systemNoticesNeverAppearAsPhoneSpeech() {
        val all = listOf(item(0, LuluChatMessage.Sender.System),
            item(2, LuluChatMessage.Sender.Character))
        assertEquals(all.drop(1), actualPhoneCaptions(all, start))
    }
}
