package com.jiacimu.lulu.games

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiacimu.lulu.VoiceSynthesisPolicy
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
    private val onError: (String) -> Unit = {},
) {
    private val appContext=context.applicationContext
    private val engine = LuluSpeechEngine(appContext)
    private var foreground=true
    private val pending = ArrayDeque<Pair<String, String>>()
    private var active = false
    private var enabled = false
    private var speakingListener: ((Boolean) -> Unit)? = null

    /** Pause the shared game microphone during audible companion speech. */
    fun setSpeakingListener(listener: ((Boolean) -> Unit)?) {
        speakingListener = listener
        listener?.invoke(active)
    }

    private fun setActive(next: Boolean) {
        if (active == next) return
        active = next
        speakingListener?.invoke(next)
    }

    private var disposed = false
    private var audioGeneration = 0L

    fun setEnabled(value: Boolean) {
        if (disposed || value == enabled) return
        enabled = value
        audioGeneration++
        if (!value) {
            pending.clear()
            setActive(false)
            engine.stop()
        }
    }

    fun setForeground(value:Boolean) { foreground=value; if(!value)stop() }

    fun stop() {
        audioGeneration++
        pending.clear()
        setActive(false)
        engine.stop()
    }

    fun enqueue(characterId: String, line: String) {
        if (!enabled || disposed || !foreground || characterId.isBlank() || line.isBlank()) return
        pending.addLast(characterId to line)
        if (!active) playNext()
    }

    private fun playNext() {
        if (!enabled || disposed || !foreground) {
            pending.clear()
            setActive(false)
            return
        }
        val next = pending.removeFirstOrNull() ?: run {
            setActive(false)
            return
        }
        if (!VoiceSynthesisPolicy.meetingPlayable(appContext)) {
            // Honour user audio settings rather than silently pretending TTS played.
            onError("伙伴发言已显示字幕；请在语音设置启用有声播放")
            pending.clear()
            setActive(false)
            return
        }
        if (!CharacterVoicePreferenceStore.isEnabled(next.first)) {
            onError("这一位伙伴的语音已关闭，字幕仍会显示")
            setActive(false)
            playNext()
            return
        }
        setActive(true)
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
                        if(engine.lastError.isNotBlank())onError("伙伴语音："+engine.lastError)
                        setActive(false)
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
        setActive(false)
        speakingListener = null
        engine.shutdown()
    }
}

@Composable
internal fun rememberIslandCompanionVoice(onError: (String) -> Unit = {}): IslandCompanionVoice {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val owner=LocalLifecycleOwner.current
    val latestError by rememberUpdatedState(onError)
    val voice = remember(context) { IslandCompanionVoice(context, scope) { latestError(it) } }
    DisposableEffect(voice,owner) {
        voice.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP)voice.setForeground(false)
            else if(event==Lifecycle.Event.ON_START)voice.setForeground(true)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);voice.close() }
    }
    return voice
}
