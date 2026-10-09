package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoicePerformanceTest {
    @Test fun tagsReachExpressiveModelsButNeverLegacyVoicesOrSubtitles() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_advanced_settings", 0)
        val audio = "[inhales] [hesitant] 我……[sighs] 没事。[sneezes]"
        assertEquals("我…… 没事。", VoicePerformance.plain(audio))
        assertEquals("我没事。", VoicePerformance.plain("我没事。[whisp"))
        prefs.edit().putString("tts_provider", "elevenlabs").putString("eleven_tts_model", "eleven_v4").commit()
        assertEquals(audio, VoicePerformance.forPlayback(context, audio))
        assertTrue(VoicePerformance.phoneNeedsWholeTurn(context))
        assertTrue(VoicePerformance.phoneInstruction(context).contains("后悔"))
        prefs.edit().putString("eleven_tts_model", "eleven_flash_v2_5").commit()
        assertFalse(VoicePerformance.phoneNeedsWholeTurn(context))
        assertEquals(VoicePerformance.plain(audio), VoicePerformance.forPlayback(context, audio))
        assertEquals("", VoicePerformance.forPlayback(context, "[slap sound]"))
        prefs.edit().putString("tts_provider", "system").commit()
        assertEquals(VoicePerformance.plain(audio), VoicePerformance.forPlayback(context, audio))
    }

    @Test fun streamingNeverSplitsPunctuationInsideAnAudioDirection() {
        val stream = CallReplyStream()
        val first = "[hesitant, quietly! "
        assertTrue(stream.update(JSONObject().put("action", "reply").put("text", first).toString()).isEmpty())
        val text = "[hesitant, quietly! sighs] 我先想一想。[sneezes]"
        val chunks = stream.update(JSONObject().put("action", "reply").put("text", text).toString()) + stream.finish(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals("[hesitant, quietly! sighs] 我先想一想。", chunks.first())
        assertEquals("[sneezes]", chunks.last())
    }

    @Test fun pageTracksKeepEventsInOrderWithoutRepeatingThem() {
        val track = VoicePerformance.meetingSegment(MeetingSegmentType.DIALOGUE, "你好。慢点。",
            "[inhales] [warmly] 你好。[sneezes] 慢点。[exhales]").speechText
        val first = VoicePerformance.slice(track, 0, 3)
        val second = VoicePerformance.slice(track, 3, 6)
        assertEquals("你好。", VoicePerformance.plain(first))
        assertEquals("慢点。", VoicePerformance.plain(second))
        assertTrue(first.contains("[inhales]"))
        assertFalse(first.contains("[sneezes]"))
        assertFalse(second.contains("[inhales]"))
        assertTrue(second.contains("[sneezes]"))
        assertTrue(second.contains("[exhales]"))
    }

    @Test fun meetingAudioCannotChangeDialogueOrSpeakActionProse() {
        val dialogue = VoicePerformance.meetingSegment(MeetingSegmentType.DIALOGUE, "先等等。", "[gasps] 先等等。")
        assertEquals("先等等。", dialogue.text)
        assertEquals("[gasps] 先等等。", dialogue.speechText)
        assertEquals("先等等。", VoicePerformance.meetingSegment(MeetingSegmentType.DIALOGUE,
            "先等等。", "[angry] 立刻滚开。").speechText)
        val action = VoicePerformance.meetingSegment(MeetingSegmentType.ACTION, "他打了个喷嚏。", "[sneezes] [inhales]")
        assertEquals("[sneezes] [inhales]", action.speechText)
        assertEquals("", VoicePerformance.meetingSegment(MeetingSegmentType.ACTION,
            "他打了个喷嚏。", "[sneezes] 他打了个喷嚏。").speechText)
        assertEquals("", VoicePerformance.meetingSegment(MeetingSegmentType.DIALOGUE,
            "等等。", "[shouting] 等等。", user = true).speechText)
        assertFalse(listOf(action, dialogue).meetingTranscript().contains("["))
    }

    @Test fun meetingAudioTrackSurvivesSerializationAndOldRecordsStillLoad() {
        val now = Instant.parse("2026-10-08T04:00:00Z")
        val segment = MeetingSegment(MeetingSegmentType.ACTION, "他落下手掌。", "[slap sound]")
        val session = MeetingSession("audio-scene", listOf("role"), MeetingReality.DIGITAL_WORLD,
            "屋内", now, turns = listOf(MeetingTurn("turn", "role", "角色", segment.text, "", now, listOf(segment))))
        val restored = session.toJson().toMeeting()!!
        assertEquals(segment, restored.turns.single().segments.single())
        val legacy = session.toJson()
        legacy.getJSONArray("turns").getJSONObject(0).getJSONArray("segments").getJSONObject(0).remove("speechText")
        assertEquals("", legacy.toMeeting()!!.turns.single().segments.single().speechText)
    }
}
