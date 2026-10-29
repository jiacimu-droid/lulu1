package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** Fired only when the final queued utterance finishes naturally, never on stop(). */
    private val onDrained: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private data class Request(
        val speakerId: String?,
        val text: String,
        val voiceId: String?,
        val messageId: String?,
        val onDelivered: (() -> Unit)?,
        val onStarted: (() -> Unit)?,
        val onEnded: (() -> Unit)?,
        val onProgress: ((Long) -> Unit)?,
        val retryOnFailure: Boolean,
        val retriesRemaining: Int,
    )

    private val appContext = context.applicationContext
    private val engine = LuluSpeechEngine(appContext)
    private val pending = ArrayDeque<Request>()
    private var active = false
    private var generation = 0L
    private var activeRequest: Request? = null
    private var retryDelayJob: kotlinx.coroutines.Job? = null
    /** Includes synthesis waiting for first audio, not just audible playback. */
    val hasPendingAudio: Boolean get() = active || pending.isNotEmpty()

    private fun notifySafely(vararg callbacks: () -> Unit) {
        CallSpeechCallbackBoundary.dispatch(
            { error -> android.util.Log.e("LuluCallSpeechQueue", "Nonfatal playback observer failure", error) },
            *callbacks,
        )
    }

    // Kotlin's trailing-lambda syntax does not accept a vararg-only
    // parameter. Keep one-callback and multi-callback overloads explicit.
    private fun notifySafely(callback: () -> Unit) {
        notifySafely(*arrayOf(callback))
    }

    init { ChatAutoVoicePlayback.initialize(appContext) }

    fun enqueue(
        text: String,
        speakerId: String? = null,
        voiceId: String? = null,
        onDelivered: (() -> Unit)? = null,
        messageId: String? = null,
        onStarted: (() -> Unit)? = null,
        onEnded: (() -> Unit)? = null,
        onProgress: ((Long) -> Unit)? = null,
        retryOnFailure: Boolean = false,
    ) {
        val speech = VoicePerformance.forPlayback(appContext, text)
        if (speech.isBlank()) return
        pending.addLast(Request(speakerId, speech, voiceId, messageId, onDelivered, onStarted, onEnded,
            onProgress, retryOnFailure, if (retryOnFailure) 1 else 0))
        if (!active && retryDelayJob?.isActive != true) playNext()
    }

    fun stop(clearQueue: Boolean = true) {
        retryDelayJob?.cancel()
        retryDelayJob = null
        notifySafely { activeRequest?.speakerId?.let { AvatarController.playback(it, false) } }
        // End the visual moment even on barge-in, TTS failure or hangup.
        // The generation fence below discards the now-stale finish callback.
        notifySafely { activeRequest?.onEnded?.invoke() }
        generation += 1
        if (clearQueue) pending.clear()
        active = false
        activeRequest = null
        engine.onPlaybackState = null
        engine.onPlaybackPosition = null
        notifySafely({ onSpeakerChanged(null) }, { onBusyChanged(false) })
        engine.stop()
    }

    fun shutdown() {
        retryDelayJob?.cancel()
        retryDelayJob = null
        notifySafely { activeRequest?.speakerId?.let { AvatarController.playback(it, false) } }
        notifySafely { activeRequest?.onEnded?.invoke() }
        generation += 1
        pending.clear()
        active = false
        activeRequest = null
        engine.onPlaybackState = null
        engine.onPlaybackPosition = null
        notifySafely({ onSpeakerChanged(null) }, { onBusyChanged(false) })
        engine.shutdown()
    }

    private fun playNext() {
        if (active) return
        val request = pending.pollFirst()
        if (request == null) {
            notifySafely({ onSpeakerChanged(null) }, { onBusyChanged(false) })
            // The busy callback may re-enter the call/session. A forced stop
            // is not a completed narration and must not drain deferred words.
            if (!active && pending.isEmpty()) notifySafely { onDrained() }
            return
        }

        active = true
        activeRequest = request
        val localGeneration = generation
        var audible = false
        var lastMouthSampleAt = 0L
        // Report real PCM playback, not the HTTP TTS preparation interval.
        engine.onPlaybackState = { playing ->
            if (playing) scope.launch(Dispatchers.Main.immediate) {
                if (localGeneration == generation && active && activeRequest === request && !audible) {
                    audible = true
                    notifySafely(
                        { onBusyChanged(true) },
                        { request.speakerId?.let { AvatarController.playback(it, true) } },
                        { onSpeakerChanged(request.speakerId) },
                        { request.onStarted?.invoke() },
                    )
                }
            }
        }
        engine.onPlaybackPosition = { elapsed -> scope.launch(Dispatchers.Main.immediate) {
            if (localGeneration == generation && active && activeRequest === request && audible)
                notifySafely { request.onProgress?.invoke(elapsed) }
        } }
        val target = request.messageId?.let(ChatAutoVoicePlayback::callRecordingTarget)
        val onFinished: () -> Unit = {
            scope.launch {
                if (localGeneration != generation || !active || activeRequest !== request) return@launch
                val succeeded = engine.lastPlaybackSucceeded
                val failure = engine.lastError
                notifySafely { request.speakerId?.let { AvatarController.playback(it, false) } }
                active = false
                activeRequest = null
                engine.onPlaybackState = null
                engine.onPlaybackPosition = null
                if (!succeeded && SleepCallContinuationPolicy.shouldRetryFailedAudio(
                        request.retryOnFailure, request.retriesRemaining, audible)) {
                    // Retry the identical bedtime paragraph, not a new model turn.
                    // Keep later segments queued behind this request, and fence
                    // the delayed retry against new speech or a call hangup.
                    pending.addFirst(request.copy(retriesRemaining = request.retriesRemaining - 1))
                    val retryGeneration = generation
                    retryDelayJob = scope.launch {
                        delay(2_000L)
                        retryDelayJob = null
                        if (retryGeneration == generation && !active) playNext()
                    }
                    return@launch
                }
                notifySafely(
                    { if (succeeded) request.onDelivered?.invoke() },
                    { request.onEnded?.invoke() },
                )
                // When the last retry fails, do not silently skip the broken
                // bedtime segment and play its sequel as though it was heard.
                if (!succeeded && request.retryOnFailure) pending.clear()
                if (!succeeded) notifySafely { onError(failure.ifBlank { "发声失败，回复保留在字幕里" }) }
                playNext()
            }
        }
        if (target != null && appContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
                .getString("tts_provider", "system") == "system") {
            // Android's built-in TTS can synthesize to WAV once before playback.
            // For streaming cloud providers we record AudioTrack itself below.
            engine.speakAndCache(request.text, java.io.File(target.parentFile, target.name.removeSuffix(".wav")),
                scope, voiceIdOverride = request.voiceId, onFinished = onFinished, source = "phone_direct")
        } else {
            engine.speak(
                text = request.text,
                scope = scope,
                voiceIdOverride = request.voiceId,
                source = "phone_direct",
                onFinished = onFinished,
                recordingTarget = target,
                onPcmFrame = { pcm, offset, length ->
                    if (localGeneration == generation && active && activeRequest === request) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastMouthSampleAt >= 65L) {
                            lastMouthSampleAt = now
                            request.speakerId?.let {
                                AvatarController.audio(it, CallSpeechPcmLevel.measure(pcm, offset, length))
                            }
                        }
                    }
                },
            )
        }
    }
}
