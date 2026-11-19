package com.jiacimu.lulu

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Hold-to-talk must save the actual microphone PCM as playable WAV, not a TTS imitation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class QqChatSpeechLaunchTest {
    @Test fun ownerVoiceMessageKeepsRealAudioAndTranscriptAcrossReload() {
        val context = RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        val store = InMemoryLuluChatStore().also { it.initialize(context) }
        val conversation = store.ensureConversation("voice-owner-" + UUID.randomUUID(), "语音私聊")
        val recorded = ByteArray(16_000) { i -> (i % 103).toByte() }
        val message = store.sendUserVoiceNote(conversation.id, "这是我亲口说的", recorded)
        assertTrue(message.voiceNote)
        assertEquals("这是我亲口说的", message.content)
        val name = MessageDigest.getInstance("SHA-256").digest(message.id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(context.filesDir, "call_voice_recordings/$name.wav")
        try {
            assertTrue(file.isFile)
            assertArrayEquals(pcmWav(recorded), file.readBytes())
            val reload = InMemoryLuluChatStore().also { it.initialize(context) }
            val restored = reload.messages(conversation.id).value.first { it.id == message.id }
            assertTrue(restored.voiceNote)
            assertEquals(message.content, restored.content)
        } finally {
            file.delete()
        }
    }

    @Test fun noRecognizedSpeechCannotBeSentAsFabricatedTranscript() {
        val context = RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        val store = InMemoryLuluChatStore().also { it.initialize(context) }
        val conversation = store.ensureConversation("voice-empty-" + UUID.randomUUID(), "语音空白")
        assertThrows(IllegalArgumentException::class.java) {
            store.sendUserVoiceNote(conversation.id, " ", ByteArray(16_000))
        }
        assertTrue(store.messages(conversation.id).value.isEmpty())
    }
}
