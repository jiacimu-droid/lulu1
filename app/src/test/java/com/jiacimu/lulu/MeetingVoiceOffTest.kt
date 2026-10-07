package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class MeetingVoiceOffTest {
    @Test fun disabledMeetingNeverStartsSynthesisOrCreatesAudioCache() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("lulu_advanced_settings", 0).edit()
            .putBoolean("tts_enabled", true).putString("tts_provider", "minimax")
            .putString("minimax_api_key", "dummy-test-key").putString("minimax_voice_id", "test-voice").commit()
        MeetingVoicePlayback.setEnabled(context, false)
        MeetingVoicePlayback.playVisibleDialogue(context, "disabled-session", "page-one", "role", "有效角色台词")
        assertFalse(File(context.filesDir, "meeting_voice_cache/disabled-session").exists())
        val engine = LuluSpeechEngine(context)
        var completed = false
        engine.speakAndCache("有效台词", File(context.filesDir, "disabled-audio"), CoroutineScope(Dispatchers.Unconfined),
            onFinished = { completed = true }, allowGeneration = { false })
        assertTrue(completed)
        val job = LuluSpeechEngine::class.java.getDeclaredField("synthesisJob").apply { isAccessible = true }
        assertNull(job.get(engine))
        assertFalse(File(context.filesDir, "disabled-audio.mp3").exists())
        engine.stop()
    }
}
