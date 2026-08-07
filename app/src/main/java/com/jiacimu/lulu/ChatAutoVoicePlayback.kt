package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * App-scope voice queue for generated chat replies.
 * Playback is intentionally not owned by the chat screen, so leaving the page while a reply is being
 * generated does not cancel the user's per-character auto-play preference.
 */
object ChatAutoVoicePlayback {
    private data class Request(val characterId: String, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private var engine: LuluSpeechEngine? = null
    private var workerStarted = false

    fun initialize(context: Context) {
        synchronized(this) {
            if (engine == null) {
                CharacterVoicePreferenceStore.initialize(context.applicationContext)
                engine = LuluSpeechEngine(context.applicationContext)
            }
            if (!workerStarted) {
                workerStarted = true
                scope.launch {
                    for (request in queue) {
                        if (!CharacterVoicePreferenceStore.isEnabled(request.characterId)) continue
                        val speech = request.text.trim()
                        if (speech.isBlank()) continue
                        suspendCancellableCoroutine { continuation ->
                            engine?.speak(speech, scope) {
                                if (continuation.isActive) continuation.resume(Unit)
                            } ?: continuation.resume(Unit)
                            continuation.invokeOnCancellation { engine?.stop() }
                        }
                    }
                }
            }
        }
    }

    fun enqueue(characterId: String, text: String) {
        if (!CharacterVoicePreferenceStore.isEnabled(characterId)) return
        val clean = text.trim()
        if (clean.isBlank()) return
        queue.trySend(Request(characterId, clean))
    }
}
