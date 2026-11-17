package com.jiacimu.lulu

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ElevenLabsDirectSingingTest {
    @Test fun directSingingUsesOwnTtsVoiceNotMusicOrVoiceConversion() {
        val json = ElevenLabsDirectSinging.payload("晚风轻轻吹过窗台，我想唱给你听")
        assertEquals("eleven_v4", json.getString("model_id"))
        assertTrue(json.getString("text").startsWith("[sings"))
        assertTrue(json.getString("text").contains("晚风轻轻吹过窗台"))
        assertFalse(json.has("finetune_id"))
        assertFalse(json.has("music_length_ms"))
        assertFalse(json.has("force_instrumental"))
        assertFalse(json.has("voice_settings"))
    }

    @Test fun invalidLyricsAreRejectedLocallyBeforeAnyPaidTtsCall() {
        for (invalid in listOf("", " ", "。！", "字".repeat(241))) {
            assertThrows(IllegalArgumentException::class.java) {
                ElevenLabsDirectSinging.payload(invalid)
            }
        }
        assertEquals("你好", ElevenLabsDirectSinging.payload("  你好  ")
            .getString("text").substringAfter("] ").trim())
    }

    @Test fun savedDirectTrialsStayIsolatedFromOriginalMusicAndOtherRoles() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "direct-singer-" + UUID.randomUUID()
        val other = "other-" + role
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(role.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "eleven_direct_singing/$hash")
        assertTrue(dir.mkdirs())
        try {
            val raw = File(dir, "not-finished.mp3.part").apply {
                writeBytes(ByteArray(2100))
            }
            assertNull(ElevenLabsDirectSinging.latest(context, role))
            val saved = File(dir, "finished.mp3").apply {
                writeBytes(ByteArray(2100) { 3 })
            }
            assertEquals(saved, ElevenLabsDirectSinging.latest(context, role))
            assertNull(ElevenLabsDirectSinging.latest(context, other))
            raw.delete()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun onlyPlausibleMp3HeadersPassTheLocalAudioSanityCheck() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = File(context.cacheDir, "direct-sing-audio-" + UUID.randomUUID())
        try {
            file.writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0))
            assertTrue(ElevenLabsDirectSinging.hasMp3Header(file))
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0))
            assertTrue(ElevenLabsDirectSinging.hasMp3Header(file))
            file.writeText("<html>not an mp3</html>")
            assertFalse(ElevenLabsDirectSinging.hasMp3Header(file))
        } finally {
            file.delete()
        }
    }
}
