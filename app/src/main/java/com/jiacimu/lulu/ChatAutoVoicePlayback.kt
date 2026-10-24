package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MeetingSegmentType
import com.jiacimu.lulu.data.UserMessageFavorites
import com.jiacimu.lulu.data.UserMessageFavorite
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
        val conversationId: String,
        val text: String,
        val voiceId: String?,
        val requireAutoPlay: Boolean,
        val prepareOnly: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Request>(Channel.UNLIMITED)
    // Two rapid "朗读" taps or duplicate UI callbacks must not each
    // purchase a fresh synthesis for the same uncached message.
    private val queuedOrSynthesizing = mutableSetOf<String>()
    private val playAfterPreparation = mutableSetOf<String>()
    private fun sendOnce(request: Request): Boolean = synchronized(this) {
        if (!queuedOrSynthesizing.add(request.messageId)) return@synchronized true
        if (queue.trySend(request).isSuccess) true
        else { queuedOrSynthesizing.remove(request.messageId); false }
    }
    private var appContext: Context? = null
    private var engine: LuluSpeechEngine? = null
    private var workerStarted = false
    @Volatile private var lastCachePruneMillis = 0L
    private var autoPlaySuppressionDepth = 0
    @Volatile private var activeRequest: Request? = null
    @Volatile private var visibleConversationId: String? = null
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
                lastCachePruneMillis = System.currentTimeMillis()
            }
            if (!workerStarted) {
                workerStarted = true
                scope.launch {
                    for (request in queue) {
                        try {
                        if (!request.prepareOnly && !AutomaticVoiceForeground.visible()) continue
                        if (request.requireAutoPlay && visibleConversationId != request.conversationId) continue
                        if (request.requireAutoPlay && (autoPlaySuppressed() ||
                            !VoiceSynthesisPolicy.chatAutomaticAllowed(applicationContext, request.characterId))) continue
                        val speech = request.text.trim()
                        if (speech.isBlank()) continue
                        val base = cacheBase(request.messageId) ?: continue
                        activeRequest = request
                        try {
                            if (request.prepareOnly) {
                                val produced = runCatching {
                                    engine?.synthesizeVoiceNoteToCache(speech, base, request.voiceId) == true
                                }.getOrDefault(false)
                                val shouldPlay = synchronized(this@ChatAutoVoicePlayback) {
                                    playAfterPreparation.remove(request.messageId)
                                }
                                if (produced) cachedFile(request.messageId)?.let { audio ->
                                    runCatching { UserMessageFavorites.retainAudio(request.messageId, audio) }
                                    if (shouldPlay && AutomaticVoiceForeground.visible()) engine?.playCached(audio)
                                }
                                continue
                            }
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
                                                visibleConversationId == request.conversationId &&
                                                (!autoPlaySuppressed() &&
                                                    VoiceSynthesisPolicy.chatAutomaticAllowed(applicationContext, request.characterId)))
                                    },
                                ) ?: continuation.resume(Unit)
                                continuation.invokeOnCancellation { engine?.stop() }
                            }
                        } finally {
                            if (activeRequest === request) activeRequest = null
                        }
                        } finally {
                            synchronized(this@ChatAutoVoicePlayback) {
                                queuedOrSynthesizing.remove(request.messageId)
                            }
                            // Refresh this cache's lifecycle after actual generation, not only at launch.
                            val current = System.currentTimeMillis()
                            if (current - lastCachePruneMillis >= CACHE_PRUNE_INTERVAL_MILLIS) {
                                runCatching { pruneOldCache(applicationContext) }
                                lastCachePruneMillis = current
                            }
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

    /** Auto-read follows the actually visible chat, not any autonomous background reply. */
    @Synchronized fun setVisibleConversation(conversationId: String?) {
        visibleConversationId = conversationId?.takeIf(String::isNotBlank)
        val ongoing = activeRequest
        if (ongoing?.requireAutoPlay == true && ongoing.conversationId != visibleConversationId) engine?.stop()
    }

    @Synchronized fun clearVisibleConversation(conversationId: String) {
        if (visibleConversationId == conversationId) setVisibleConversation(null)
    }

    /** Called after a generated character bubble is persisted. */
    fun enqueue(characterId: String, messageId: String, text: String, conversationId: String) {
        if (conversationId.isBlank() || visibleConversationId != conversationId) return
        if (autoPlaySuppressed()) return
        if (!VoiceSynthesisPolicy.chatAutomaticAllowed(appContext ?: return, characterId)) return
        if (!AutomaticVoiceForeground.visible()) return
        // Never pay TTS to read hidden reply directives or control metadata.
        val clean = stripCharacterReplyDirective(text).trim()
        if (clean.isBlank() || messageId.isBlank()) return
        sendOnce(
            Request(
                characterId = characterId,
                messageId = messageId,
                conversationId = conversationId,
                text = clean,
                voiceId = CharacterVoicePreferenceStore.playbackVoiceId(characterId),
                requireAutoPlay = true,
            ),
        )
    }

    /** Prepare character-chosen speech silently; never auto-play a sent voice note. */
    fun prepareVoiceNote(characterId: String, messageId: String, text: String, conversationId: String,
        speechText: String = ""): Boolean {
        val context = appContext ?: return false
        if (!VoiceSynthesisPolicy.enabled(context) || text.isBlank() || messageId.isBlank()) return false
        if (hasCached(messageId)) return true
        return sendOnce(Request(characterId, messageId, conversationId,
            voiceNoteAudioText(context, text, speechText),
            CharacterVoicePreferenceStore.playbackVoiceId(characterId),
            requireAutoPlay = false, prepareOnly = true))
    }

    /**
     * QQ-style "朗读": cached audio wins. If this message never generated voice before, synthesize it
     * now, persist the cache, then play it. Returns false only when the message cannot be resolved.
     */
    fun replayCached(messageId: String): Boolean {
        val audio = cachedFile(messageId)
        if (audio != null) return if (AutomaticVoiceForeground.visible()) {
            if (engine?.playCached(audio) == true) {
                // Recently replayed recordings should stay near the front of the normal cache.
                if (audio.parentFile?.name in setOf("chat_voice_notes", "chat_voice_cache"))
                    audio.setLastModified(System.currentTimeMillis())
                true
            } else false
        } else false
        // An explicitly starred performance has its own immutable archive.
        // It should remain replayable even if the conversation cache is gone.
        val favorite = UserMessageFavorites.store.entries.value.firstOrNull { it.messageId == messageId }
        val starredAudio = favorite?.let { UserMessageFavorites.store.audioFile(it) }
        if (starredAudio != null) return engine?.playCached(starredAudio) == true
        // An old phone transcript has no original performance to replay.
        // Never silently synthesize a different voice and pretend it was the call.
        if (messageId.startsWith("voice-")) return false
        val characterMessage = resolveCharacterMessage(messageId)?.second
        if (characterMessage?.voiceNote == true) synchronized(this) {
            if (messageId in queuedOrSynthesizing) {
                playAfterPreparation.add(messageId)
                return true
            }
        }

        val target = resolveCharacterMessage(messageId) ?: return false
        val characterId = target.first
        val message = target.second
        val speech = stripCharacterReplyDirective(message.content).trim()
        if (speech.isBlank()) return false
        val audioText = if (message.voiceNote) voiceNoteAudioText(appContext ?: return false,
            speech, message.voiceSpeechText) else speech
        return sendOnce(
            Request(
                characterId = characterId,
                messageId = message.id,
                conversationId = "", // Explicit tap works from a favorites page too.
                text = audioText,
                voiceId = CharacterVoicePreferenceStore.playbackVoiceId(characterId),
                requireAutoPlay = false,
            ),
        )
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
        synchronized(this) { playAfterPreparation.remove(messageId) }
        listOfNotNull(cacheBase(messageId), voiceNoteBase(messageId)).forEach { base ->
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

    /** Speech directions remain strictly off the visible chat/memory track. */
    internal fun voiceNoteAudioText(context: Context, text: String, speechText: String): String {
        val clean = VoicePerformance.plain(stripCharacterReplyDirective(text)).trim()
        val aligned = VoicePerformance.meetingSegment(MeetingSegmentType.DIALOGUE,
            clean, speechText).speechText
        return VoicePerformance.forPlayback(context, aligned.ifBlank { clean })
    }

    private fun voiceNoteBase(messageId: String): File? {
        val context = appContext ?: return null
        return File(File(context.filesDir, "chat_voice_notes").apply { mkdirs() }, sha256(messageId))
    }

    private fun cacheBase(messageId: String): File? {
        val context = appContext ?: return null
        if (resolveCharacterMessage(messageId)?.second?.voiceNote == true) return voiceNoteBase(messageId)
        val directory = File(context.filesDir, "chat_voice_cache").apply { mkdirs() }
        return File(directory, sha256(messageId))
    }

    private fun pruneOldCache(context: Context) {
        pruneVoiceNotes(context)
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

    /**
     * The small retained voice-note directory is a REBUILDABLE cache, not the archive.
     * Favorite originals live separately under favorite_voice and are never pruned.
     * File mtime tracks last successful replay; the cutoffs protect phone storage only.
     */
    internal fun pruneVoiceNotes(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val directory = File(context.filesDir, "chat_voice_notes")
        val recordings = directory.listFiles()?.filter {
            it.isFile && it.extension in setOf("mp3", "wav")
        }.orEmpty()
        if (recordings.isEmpty()) return
        val favoriteEntries = UserMessageFavorites.store.entries.value
        // A favorite whose independent copy is missing must not lose its last original.
        favoriteEntries.forEach { entry ->
            val original = cachedFile(entry.messageId)
            if (original != null && UserMessageFavorites.store.audioFile(entry) == null)
                runCatching { UserMessageFavorites.retainAudio(entry.messageId, original) }
        }
        val protected = synchronized(this) {
            queuedOrSynthesizing.toSet() + listOfNotNull(activeRequest?.messageId) +
                favoriteEntries.map(UserMessageFavorite::messageId)
        }.mapTo(hashSetOf(), ::sha256)
        val eligible = recordings.filter { file ->
            file.nameWithoutExtension !in protected && file.length() > 0L
        }
        // Preserve a month of typical replay use; older uncatalogued audio is expendable.
        eligible.filter { nowMillis - it.lastModified() > VOICE_NOTE_RETENTION_MILLIS &&
            it.lastModified() <= nowMillis }.forEach(File::delete)
        var total = directory.listFiles()?.asSequence()?.filter {
            it.isFile && it.extension in setOf("mp3", "wav")
        }?.sumOf(File::length) ?: 0L
        if (total <= VOICE_NOTE_CACHE_LIMIT_BYTES) return
        eligible.filter(File::exists).sortedBy(File::lastModified).forEach { file ->
            if (total > VOICE_NOTE_CACHE_LIMIT_BYTES) {
                val bytes = file.length()
                if (file.delete()) total -= bytes
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val MAX_CACHE_FILES = 800
    private const val CACHE_PRUNE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    private const val VOICE_NOTE_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    private const val VOICE_NOTE_CACHE_LIMIT_BYTES = 192L * 1024 * 1024
}
