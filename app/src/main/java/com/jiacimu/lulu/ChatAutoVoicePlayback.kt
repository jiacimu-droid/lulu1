package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UserMessageFavorites
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
    @Volatile private var activeRequest: Request? = null
    private var settingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun initialize(context: Context) {
        synchronized(this) {
            val applicationContext = context.applicationContext
            appContext = applicationContext
            AutomaticVoiceForeground.install(applicationContext)
            if (settingsListener == null) {
                AutomaticVoiceForeground.onBackground { cancelInFlight() }
                val preferences = applicationContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
                settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key in setOf("tts_enabled", "tts_auto_speak") &&
                        !VoiceSynthesisPolicy.automaticAllowed(applicationContext)) cancelInFlight()
                }.also(preferences::registerOnSharedPreferenceChangeListener)
            }
            UserMessageFavorites.initialize(applicationContext)
            if (engine == null) {
                CharacterVoicePreferenceStore.initialize(applicationContext)
                engine = LuluSpeechEngine(applicationContext)
                pruneOldCache(applicationContext)
            }
            if (!workerStarted) {
                workerStarted = true
                scope.launch {
                    for (request in queue) {
                        if (!AutomaticVoiceForeground.visible()) continue
                        if (request.requireAutoPlay && (autoPlaySuppressed() ||
                            !VoiceSynthesisPolicy.chatAutomaticAllowed(applicationContext, request.characterId))) continue
                        val speech = request.text.trim()
                        if (speech.isBlank()) continue
                        val base = cacheBase(request.messageId) ?: continue
                        activeRequest = request
                        try {
                            suspendCancellableCoroutine<Unit> { continuation ->
                                engine?.speakAndCache(
                                    text = speech,
                                    cacheBaseFile = base,
                                    scope = scope,
                                    onFinished = {
                                        cachedFile(request.messageId)?.let { audio ->
                                            runCatching { UserMessageFavorites.retainAudio(request.messageId, audio) }
                                        }
                                        if (continuation.isActive) continuation.resume(Unit)
                                    },
                                    voiceIdOverride = request.voiceId,
                                    source = if (request.requireAutoPlay) "chat_auto" else "chat_manual",
                                    allowGeneration = {
                                        AutomaticVoiceForeground.visible() &&
                                            (!request.requireAutoPlay ||
                                                (!autoPlaySuppressed() &&
                                                    VoiceSynthesisPolicy.chatAutomaticAllowed(applicationContext, request.characterId)))
                                    },
                                ) ?: continuation.resume(Unit)
                                continuation.invokeOnCancellation { engine?.stop() }
                            }
                        } finally {
                            if (activeRequest === request) activeRequest = null
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
        if (activeRequest?.requireAutoPlay == true) engine?.stop()
    }

    @Synchronized
    fun resumeAutoPlay() {
        if (autoPlaySuppressionDepth > 0) autoPlaySuppressionDepth -= 1
    }

    @Synchronized
    private fun autoPlaySuppressed(): Boolean = autoPlaySuppressionDepth > 0

    /** A per-role toggle must cancel already-sent but not-yet-heard requests. */
    @Synchronized fun onCharacterAutoReadChanged(characterId: String, enabled: Boolean) {
        if (!enabled && activeRequest?.characterId == characterId &&
            activeRequest?.requireAutoPlay == true) engine?.stop()
    }

    @Synchronized private fun cancelInFlight() {
        if (activeRequest != null) engine?.stop()
    }

    /** Called after a generated character bubble is persisted. */
    fun enqueue(characterId: String, messageId: String, text: String) {
        if (autoPlaySuppressed()) return
        if (!VoiceSynthesisPolicy.chatAutomaticAllowed(appContext ?: return, characterId)) return
        if (!AutomaticVoiceForeground.visible()) return
        val clean = text.trim()
        if (clean.isBlank() || messageId.isBlank()) return
        queue.trySend(
            Request(
                characterId = characterId,
                messageId = messageId,
                text = clean,
                voiceId = CharacterVoicePreferenceStore.playbackVoiceId(characterId),
                requireAutoPlay = true,
            ),
        )
    }

    /**
     * QQ-style "朗读": cached audio wins. If this message never generated voice before, synthesize it
     * now, persist the cache, then play it. Returns false only when the message cannot be resolved.
     */
    fun replayCached(messageId: String): Boolean {
        val audio = cachedFile(messageId)
        if (audio != null) return if (AutomaticVoiceForeground.visible()) engine?.playCached(audio) == true else false
        // An explicitly starred performance has its own immutable archive.
        // It should remain replayable even if the conversation cache is gone.
        val favorite = UserMessageFavorites.store.entries.value.firstOrNull { it.messageId == messageId }
        val starredAudio = favorite?.let { UserMessageFavorites.store.audioFile(it) }
        if (starredAudio != null) return engine?.playCached(starredAudio) == true
        // An old phone transcript has no original performance to replay.
        // Never silently synthesize a different voice and pretend it was the call.
        if (messageId.startsWith("voice-")) return false

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
                voiceId = CharacterVoicePreferenceStore.playbackVoiceId(characterId),
                requireAutoPlay = false,
            ),
        ).isSuccess
    }

    internal fun cachedFile(messageId: String): File? {
        val call = callRecordingBase(messageId)
        val chat = cacheBase(messageId)
        // Live phone WAV is never evicted by the normal chat TTS cache policy.
        return listOfNotNull(
            call?.let { File(it.parentFile, "${it.name}.wav") },
            chat?.let { File(it.parentFile, "${it.name}.mp3") },
            chat?.let { File(it.parentFile, "${it.name}.wav") },
        ).firstOrNull { it.isFile && it.length() > 0L }
    }

    /** Call TTS writes the PCM actually heard, never another synthesis. */
    internal fun callRecordingTarget(messageId: String): File? {
        if (messageId.isBlank()) return null
        val base = callRecordingBase(messageId) ?: return null
        return File(base.parentFile, "${base.name}.wav")
    }

    private fun callRecordingBase(messageId: String): File? {
        val context = appContext ?: return null
        val directory = File(context.filesDir, "call_voice_recordings").apply { mkdirs() }
        return File(directory, sha256(messageId))
    }

    internal fun favoriteAudioBase(messageId: String): File? {
        val context = appContext ?: return null
        val directory = File(context.filesDir, "favorite_voice").apply { mkdirs() }
        return File(directory, sha256(messageId))
    }

    fun hasCached(messageId: String): Boolean = cachedFile(messageId) != null

    fun remove(messageId: String) {
        cacheBase(messageId)?.let { base ->
            File(base.parentFile, "${base.name}.mp3").delete()
            File(base.parentFile, "${base.name}.wav").delete()
        }
        callRecordingTarget(messageId)?.let { file ->
            file.delete()
            File(file.parentFile, file.name + ".partial").delete()
        }
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
        UserMessageFavorites.store.entries.value.forEach { entry ->
            cachedFile(entry.messageId)?.let { audio -> runCatching { UserMessageFavorites.retainAudio(entry.messageId, audio) } }
        }
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
