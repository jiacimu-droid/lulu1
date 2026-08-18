package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
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
 *
 * Auto-played character bubbles are synthesized once into a persistent local cache keyed by the
 * chat message id. Manual "朗读" first reuses that exact cache; if no cache exists yet, it generates
 * one with the speaking character's MiniMax Voice ID, saves it, and plays the new file.
 */
object ChatAutoVoicePlayback {
    private data class Request(
        val characterId: String,
        val messageId: String,
        val text: String,
        val voiceId: String?,
        val requireAutoPlay: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private var appContext: Context? = null
    private var engine: LuluSpeechEngine? = null
    private var workerStarted = false
    private var autoPlaySuppressionDepth = 0

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
                        if (request.requireAutoPlay && autoPlaySuppressed()) continue
                        if (request.requireAutoPlay && !CharacterVoicePreferenceStore.isEnabled(request.characterId)) continue
                        val speech = request.text.trim()
                        if (speech.isBlank()) continue
                        val base = cacheBase(request.messageId) ?: continue
                        suspendCancellableCoroutine<Unit> { continuation ->
                            engine?.speakAndCache(
                                text = speech,
                                cacheBaseFile = base,
                                scope = scope,
                                onFinished = {
                                    if (continuation.isActive) continuation.resume(Unit)
                                },
                                voiceIdOverride = request.voiceId,
                            ) ?: continuation.resume(Unit)
                            continuation.invokeOnCancellation { engine?.stop() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Live calls own their own ordered speech queue. Suppress ordinary chat auto-read while a call
     * is active so the same generated bubble cannot be spoken twice by two independent engines.
     */
    @Synchronized
    fun suppressAutoPlay() {
        autoPlaySuppressionDepth += 1
    }

    @Synchronized
    fun resumeAutoPlay() {
        if (autoPlaySuppressionDepth > 0) autoPlaySuppressionDepth -= 1
    }

    @Synchronized
    private fun autoPlaySuppressed(): Boolean = autoPlaySuppressionDepth > 0

    /** Called after a generated character bubble is persisted. */
    fun enqueue(characterId: String, messageId: String, text: String) {
        if (autoPlaySuppressed()) return
        if (!CharacterVoicePreferenceStore.isEnabled(characterId)) return
        val clean = text.trim()
        if (clean.isBlank() || messageId.isBlank()) return
        queue.trySend(
            Request(
                characterId = characterId,
                messageId = messageId,
                text = clean,
                voiceId = CharacterVoicePreferenceStore.voiceId(characterId),
                requireAutoPlay = true,
            ),
        )
    }

    /**
     * QQ-style "朗读": cached audio wins. If this message never generated voice before, synthesize it
     * now, persist the cache, then play it. Returns false only when the message cannot be resolved.
     */
    fun replayCached(messageId: String): Boolean {
        val base = cacheBase(messageId) ?: return false
        val audio = engine?.cachedAudioFile(base)
        if (audio != null) return engine?.playCached(audio) == true

        val target = resolveCharacterMessage(messageId) ?: return false
        val characterId = target.first
        val message = target.second
        val speech = stripCharacterReplyDirective(message.content).trim()
        if (speech.isBlank()) return false
        return queue.trySend(
            Request(
                characterId = characterId,
                messageId = message.id,
                text = speech,
                voiceId = CharacterVoicePreferenceStore.voiceId(characterId),
                requireAutoPlay = false,
            ),
        ).isSuccess
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

    private fun resolveCharacterMessage(messageId: String): Pair<String, LuluChatMessage>? {
        MigratedDomainStores.chat.conversations.value.forEach { conversation ->
            val message = MigratedDomainStores.chat.messages(conversation.id).value
                .firstOrNull { it.id == messageId }
                ?.takeIf { it.sender == LuluChatMessage.Sender.Character }
                ?: return@forEach
            val characterId = message.authorCharacterId
                ?.takeIf(String::isNotBlank)
                ?: conversation.characterId
            if (characterId.isNotBlank()) return characterId to message
        }
        return null
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
