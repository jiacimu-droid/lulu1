package com.jiacimu.lulu

/**
 * Call audio must keep advancing even if a subtitle, avatar, transcript,
 * or delivery receipt observer fails. Each observer is independent; callers
 * report failures separately from actual TTS playback errors.
 */
internal object CallSpeechCallbackBoundary {
    fun dispatch(onFailure: (Exception) -> Unit, vararg callbacks: () -> Unit) {
        callbacks.forEach { callback ->
            try {
                callback()
            } catch (error: Exception) {
                // Diagnostics must not become a second way to break the queue.
                try { onFailure(error) } catch (_: Exception) { }
            }
        }
    }
}
