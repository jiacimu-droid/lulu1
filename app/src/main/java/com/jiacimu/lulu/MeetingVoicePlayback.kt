package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.MeetingTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Kept for source compatibility with older builds; page-driven playback no longer uses pacing. */
enum class MeetingVoicePace(val label: String, internal val factor: Double) {
    QUICK("轻快", 0.72),
    NATURAL("自然", 1.0),
    RELAXED("从容", 1.35),
}

/**
 * Meeting voice follows the page the user is actually reading.
 *
 * The switch is global to Meeting. When enabled, only the currently visible CHARACTER dialogue
 * page is spoken. Moving to prose, user text, another page, another scene, or disabling the switch
 * immediately stops the previous voice. Nothing is queued ahead of the reader anymore.
 */
object MeetingVoicePlayback {
    private const val PREFS_NAME = "lulu_meeting_voice"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACE = "pace"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    // Compatibility state only. The new UI deliberately does not expose or use reading pace.
    private val mutablePace = MutableStateFlow(MeetingVoicePace.NATURAL)
    val pace: StateFlow<MeetingVoicePace> = mutablePace.asStateFlow()

    private var appContext: Context? = null
    private var engine: LuluSpeechEngine? = null
    private val lock = Any()
    private var activePageToken: String? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            val application = context.applicationContext
            appContext = application
            CharacterVoicePreferenceStore.initialize(application)
            engine = LuluSpeechEngine(application)
            val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            mutableEnabled.value = prefs.getBoolean(KEY_ENABLED, false)
            mutablePace.value = runCatching {
                MeetingVoicePace.valueOf(
                    prefs.getString(KEY_PACE, MeetingVoicePace.NATURAL.name).orEmpty(),
                )
            }.getOrDefault(MeetingVoicePace.NATURAL)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        mutableEnabled.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
        if (!value) stopVisibleDialogue()
    }

    /** Compatibility setter. Page-driven playback intentionally ignores this value. */
    fun setPace(context: Context, value: MeetingVoicePace) {
        initialize(context)
        mutablePace.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACE, value.name)
            .apply()
    }

    fun resetSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val shouldStop = synchronized(lock) {
            val token = activePageToken
            if (token?.startsWith("$sessionId|") == true) {
                activePageToken = null
                true
            } else {
                false
            }
        }
        if (shouldStop) engine?.stop()
    }

    /**
     * Legacy entry point retained so older callers compile. The new reader never queues whole turns;
     * playback is triggered only by [playVisibleDialogue].
     */
    @Suppress("UNUSED_PARAMETER")
    fun enqueueTurns(context: Context, sessionId: String, turns: List<MeetingTurn>) {
        initialize(context)
    }

    fun playVisibleDialogue(
        context: Context,
        sessionId: String,
        pageKey: String,
        characterId: String,
        text: String,
    ) {
        initialize(context)
        val spoken = text.trim()
        if (
            !mutableEnabled.value ||
            sessionId.isBlank() ||
            pageKey.isBlank() ||
            characterId.isBlank() ||
            characterId == "system" ||
            spoken.isBlank()
        ) {
            stopVisibleDialogue(sessionId)
            return
        }

        val token = "$sessionId|$pageKey"
        synchronized(lock) {
            activePageToken = token
        }

        // Page changed: stop whatever belonged to the previous page before speaking this one.
        engine?.stop()
        val application = appContext ?: return
        val speech = engine ?: return
        val safeKey = pageKey.hashCode().toUInt().toString(16)
        val cacheBase = File(application.filesDir, "meeting_voice_cache/$sessionId/page-$safeKey")
        cacheBase.parentFile?.mkdirs()

        scope.launch {
            if (!mutableEnabled.value || synchronized(lock) { activePageToken != token }) return@launch
            speech.speakAndCache(
                text = spoken,
                cacheBaseFile = cacheBase,
                scope = scope,
                voiceIdOverride = CharacterVoicePreferenceStore.voiceId(characterId),
                onFinished = {
                    synchronized(lock) {
                        if (activePageToken == token) activePageToken = null
                    }
                },
            )
        }
    }

    fun stopVisibleDialogue(sessionId: String? = null) {
        val shouldStop = synchronized(lock) {
            val token = activePageToken
            if (sessionId == null || token?.startsWith("$sessionId|") == true) {
                activePageToken = null
                token != null
            } else {
                false
            }
        }
        if (shouldStop || sessionId == null) engine?.stop()
    }
}
