package com.jiacimu.lulu

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ElevenLabsAudioContractsTest {
    @Test fun finalFrameWithoutAudioDoesNotProduceGarbagePcm() {
        assertNull(ElevenLabsDialogueStream.decodeAudio(org.json.JSONObject("""{"audio":null,"is_final":true}""")))
        assertNull(ElevenLabsDialogueStream.decodeAudio(org.json.JSONObject("""{"is_final":true}""")))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ElevenLabsDialogueStream.decodeAudio(org.json.JSONObject("""{"audio":"AQIDBA=="}""")))
    }
    @Test fun currentModelsUseTheirSupportedTransportAndBody() {
        assertTrue(ElevenLabsModels.choices.any { it.second == "eleven_v4" })
        assertTrue(ElevenLabsModels.websocket("eleven_v4_turbo"))
        assertTrue(ElevenLabsModels.websocket("eleven_v3_conversational"))
        assertFalse(ElevenLabsModels.websocket("eleven_v4"))
        val dialogue = ElevenLabsModels.body("eleven_v4", "你好", "my-voice", .37f, .8f)
        assertEquals("/v1/text-to-dialogue/stream", ElevenLabsModels.httpPath("eleven_v4", "my-voice"))
        assertEquals("eleven_v4", dialogue.getString("model_id"))
        assertEquals("my-voice", dialogue.getJSONArray("inputs").getJSONObject(0).getString("voice_id"))
        assertFalse(dialogue.has("voice_settings"))
        val legacy = ElevenLabsModels.body("eleven_flash_v2_5", "你好", "my-voice", .37f, .8f)
        assertEquals("/v1/text-to-speech/my-voice/stream", ElevenLabsModels.httpPath("eleven_flash_v2_5", "my-voice"))
        assertEquals("你好", legacy.getString("text"))
        assertTrue(legacy.has("voice_settings"))
        assertFalse(legacy.has("inputs"))
    }
    @Test fun newCallOpensMicrophoneAndOutputSwitchNeverMutesIt() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.isMicrophoneMute = true
        val route = CallAudioRoute(manager, { _, _ -> }, {})
        try {
            route.start()
            // A phone call may not globally unmute another app or overwrite the
            // operating system's microphone privacy state. Muting belongs to the
            // recorder / WebRTC session, not AudioManager.isMicrophoneMute.
            assertTrue(manager.isMicrophoneMute)
            route.speaker(false)
            assertTrue(manager.isMicrophoneMute)
            route.speaker(true)
            assertTrue(manager.isMicrophoneMute)
            route.microphone(true)
            assertTrue(manager.isMicrophoneMute)
            route.microphone(false)
            assertTrue(manager.isMicrophoneMute)
        } finally { route.stop() }
        assertTrue(manager.isMicrophoneMute)
        assertNull(CallAudioRoute.preferredOutput)
    }
}
