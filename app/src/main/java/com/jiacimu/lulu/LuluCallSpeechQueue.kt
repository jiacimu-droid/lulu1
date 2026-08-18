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
) {
    private data class Request(
        val speakerId: String?,
        val text: String,
        val voiceId: String?,
    )

    private val engine = LuluSpeechEngine(context.applicationContext)
    private val pending = ArrayDeque<Request>()
    private var active = false
    private var generation = 0L

    fun enqueue(
        text: String,
        speakerId: String? = null,
        voiceId: String? = null,
    ) {
        val speech = text.trim()
        if (speech.isBlank()) return
        pending.addLast(Request(speakerId, speech, voiceId))
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
        engine.speak(
            text = request.text,
            scope = scope,
            voiceIdOverride = request.voiceId,
            onFinished = {
                scope.launch {
                    if (localGeneration != generation) return@launch
                    active = false
                    playNext()
                }
            },
        )
    }
}
