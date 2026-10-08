package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Phone-only speech queue.
 *
 * LuluSpeechEngine.speak() intentionally interrupts the current utterance. That is useful for
 * previews and page-driven reading, but wrong for a live phone call: once a line starts speaking,
 * later lines must wait their turn. This wrapper serializes call speech without changing the
 * semantics of LuluSpeechEngine for the rest of the app.
 */
internal class LuluCallSpeechQueue(
    context: Context,
    private val scope: CoroutineScope,
    private val onSpeakerChanged: (String?) -> Unit = {},
    private val onBusyChanged: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private data class Request(
        val speakerId: String?,
        val text: String,
        val voiceId: String?,
        val messageId: String?,
        val onDelivered: (() -> Unit)?,
    )

    private val appContext = context.applicationContext
    private val engine = LuluSpeechEngine(appContext)
    private val pending = ArrayDeque<Request>()
    private var active = false
    private var generation = 0L

    init { ChatAutoVoicePlayback.initialize(appContext) }

    fun enqueue(
        text: String,
        speakerId: String? = null,
        voiceId: String? = null,
        onDelivered: (() -> Unit)? = null,
        messageId: String? = null,
    ) {
        val speech = VoicePerformance.forPlayback(appContext, text)
        if (speech.isBlank()) return
        pending.addLast(Request(speakerId, speech, voiceId, messageId, onDelivered))
        if (!active) playNext()
    }

    fun stop(clearQueue: Boolean = true) {
        generation += 1
        if (clearQueue) pending.clear()
        active = false
        onSpeakerChanged(null)
        onBusyChanged(false)
        engine.stop()
    }

    fun shutdown() {
        generation += 1
        pending.clear()
        active = false
        onSpeakerChanged(null)
        onBusyChanged(false)
        engine.shutdown()
    }

    private fun playNext() {
        if (active) return
        val request = pending.pollFirst()
        if (request == null) {
            onSpeakerChanged(null)
            onBusyChanged(false)
            return
        }

        active = true
        val localGeneration = generation
        onBusyChanged(true)
        onSpeakerChanged(request.speakerId)
        val target = request.messageId?.let(ChatAutoVoicePlayback::callRecordingTarget)
        val onFinished: () -> Unit = {
            scope.launch {
                if (localGeneration != generation) return@launch
                val succeeded = engine.lastPlaybackSucceeded
                val failure = engine.lastError
                if (succeeded) request.onDelivered?.invoke()
                active = false
                if (!succeeded) onError(failure.ifBlank { "发声失败，回复保留在字幕里" })
                playNext()
            }
        }
        if (target != null && appContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
                .getString("tts_provider", "system") == "system") {
            // Android's built-in TTS can synthesize to WAV once before playback.
            // For streaming cloud providers we record AudioTrack itself below.
            engine.speakAndCache(request.text, java.io.File(target.parentFile, target.name.removeSuffix(".wav")),
                scope, voiceIdOverride = request.voiceId, onFinished = onFinished)
        } else {
            engine.speak(
                text = request.text,
                scope = scope,
                voiceIdOverride = request.voiceId,
                onFinished = onFinished,
                recordingTarget = target,
            )
        }
    }
}
