package com.jiacimu.lulu.games

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.LuluSpeechEngine
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One scene-scoped voice channel for a team. The existing character voice preferences
 * and LuluSpeechEngine are authoritative, not a new TTS provider or an always-on call.
 * Playback is strictly sequential: a second teammate never cuts the first one off.
 */
internal class IslandCompanionVoice(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val engine = LuluSpeechEngine(context.applicationContext)
    private val pending = ArrayDeque<Pair<String, String>>()
    private var active = false
    private var enabled = false
    private var disposed = false
    private var audioGeneration = 0L

    fun setEnabled(value: Boolean) {
        if (disposed || value == enabled) return
        enabled = value
        audioGeneration++
        if (!value) {
            pending.clear()
            active = false
            engine.stop()
        }
    }

    fun enqueue(characterId: String, line: String) {
        if (!enabled || disposed || characterId.isBlank() || line.isBlank()) return
        pending.addLast(characterId to line)
        if (!active) playNext()
    }

    private fun playNext() {
        if (!enabled || disposed) {
            pending.clear()
            active = false
            return
        }
        val next = pending.removeFirstOrNull() ?: run {
            active = false
            return
        }
        active = true
        val expectedAudioGeneration = audioGeneration
        engine.speak(
            text = next.second,
            scope = scope,
            voiceIdOverride = CharacterVoicePreferenceStore.playbackVoiceId(next.first),
            source = "game_companion_voice",
            onFinished = {
                // Provider callbacks can arrive off-main; enqueue the next speaker on
                // the scene scope and ignore late completions after the game closes.
                scope.launch {
                    if (!disposed && expectedAudioGeneration == audioGeneration) {
                        active = false
                        playNext()
                    }
                }
            },
        )
    }

    fun close() {
        if (disposed) return
        disposed = true
        audioGeneration++
        enabled = false
        pending.clear()
        engine.shutdown()
    }
}

@Composable
internal fun rememberIslandCompanionVoice(): IslandCompanionVoice {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val voice = remember(context) { IslandCompanionVoice(context, scope) }
    DisposableEffect(voice) {
        onDispose { voice.close() }
    }
    return voice
}
