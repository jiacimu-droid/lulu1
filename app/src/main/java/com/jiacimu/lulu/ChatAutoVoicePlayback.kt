package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * App-scope voice queue for generated chat replies.
 * Every auto-played character bubble is synthesized once into a persistent local cache keyed by the
 * chat message id. Long-press replay uses that exact file and never calls the model or TTS provider again.
 */
object ChatAutoVoicePlayback {
    private data class Request(
        val characterId: String,
        val messageId: String,
        val text: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private var appContext: Context? = null
    private var engine: LuluSpeechEngine? = null
    private var workerStarted = false

    fun initialize(context: Context) {
        synchronized(this) {
            val applicationContext = context.applicationContext
            appContext = applicationContext
            if (engine == null) {
                CharacterVoicePreferenceStore.initialize(applicationContext)
                engine = LuluSpeechEngine(applicationContext)
                pruneOldCache(applicationContext)
            }
            if (!workerStarted) {
                workerStarted = true
                scope.launch {
                    for (request in queue) {
                        if (!CharacterVoicePreferenceStore.isEnabled(request.characterId)) continue
                        val speech = request.text.trim()
                        if (speech.isBlank()) continue
                        val base = cacheBase(request.messageId) ?: continue
                        suspendCancellableCoroutine<Unit> { continuation ->
                            engine?.speakAndCache(speech, base, scope) {
                                if (continuation.isActive) continuation.resume(Unit)
                            } ?: continuation.resume(Unit)
                            continuation.invokeOnCancellation { engine?.stop() }
                        }
                    }
                }
            }
        }
    }

    fun enqueue(characterId: String, messageId: String, text: String) {
        if (!CharacterVoicePreferenceStore.isEnabled(characterId)) return
        val clean = text.trim()
        if (clean.isBlank() || messageId.isBlank()) return
        queue.trySend(Request(characterId, messageId, clean))
    }

    /** Replays an already cached file. Returns false instead of synthesizing again when no cache exists. */
    fun replayCached(messageId: String): Boolean {
        val base = cacheBase(messageId) ?: return false
        val audio = engine?.cachedAudioFile(base) ?: return false
        return engine?.playCached(audio) == true
    }

    fun hasCached(messageId: String): Boolean {
        val base = cacheBase(messageId) ?: return false
        return engine?.cachedAudioFile(base) != null
    }

    fun remove(messageId: String) {
        val base = cacheBase(messageId) ?: return
        File(base.parentFile, "${base.name}.mp3").delete()
        File(base.parentFile, "${base.name}.wav").delete()
    }

    private fun cacheBase(messageId: String): File? {
        val context = appContext ?: return null
        val directory = File(context.filesDir, "chat_voice_cache").apply { mkdirs() }
        return File(directory, sha256(messageId))
    }

    private fun pruneOldCache(context: Context) {
        val directory = File(context.filesDir, "chat_voice_cache")
        if (!directory.exists()) return
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy(File::lastModified)
            .take(files.size - MAX_CACHE_FILES)
            .forEach(File::delete)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val MAX_CACHE_FILES = 800
}
