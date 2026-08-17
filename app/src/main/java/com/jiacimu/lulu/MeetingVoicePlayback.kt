package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.MeetingSegment
import com.jiacimu.lulu.data.MeetingSegmentType
import com.jiacimu.lulu.data.MeetingTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToLong

enum class MeetingVoicePace(val label: String, internal val factor: Double) {
    QUICK("轻快", 0.72),
    NATURAL("自然", 1.0),
    RELAXED("从容", 1.35),
}

/**
 * Meeting-only auto voice. Only character DIALOGUE is spoken; prose, user turns and system text
 * become reading time before the next spoken line instead of being read aloud.
 */
object MeetingVoicePlayback {
    private const val PREFS_NAME = "lulu_meeting_voice"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACE = "pace"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val mutablePace = MutableStateFlow(MeetingVoicePace.NATURAL)
    val pace: StateFlow<MeetingVoicePace> = mutablePace.asStateFlow()
    private var appContext: Context? = null
    private var engine: LuluSpeechEngine? = null
    private val lock = Any()
    private val queue = ArrayDeque<VoiceRequest>()
    private val sessionReadingChars = mutableMapOf<String, Int>()
    private val sessionHasSpoken = mutableSetOf<String>()
    private var playing = false

    private data class VoiceRequest(
        val sessionId: String,
        val characterId: String,
        val text: String,
        val cacheKey: String,
        val delayBeforeMs: Long,
    )

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            CharacterVoicePreferenceStore.initialize(context.applicationContext)
            engine = LuluSpeechEngine(context.applicationContext)
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            mutableEnabled.value = prefs.getBoolean(KEY_ENABLED, false)
            mutablePace.value = runCatching {
                MeetingVoicePace.valueOf(prefs.getString(KEY_PACE, MeetingVoicePace.NATURAL.name).orEmpty())
            }.getOrDefault(MeetingVoicePace.NATURAL)
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        mutableEnabled.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) {
            synchronized(lock) {
                queue.clear()
                playing = false
                sessionReadingChars.clear()
                sessionHasSpoken.clear()
            }
            engine?.stop()
        }
    }

    fun setPace(context: Context, value: MeetingVoicePace) {
        initialize(context)
        mutablePace.value = value
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACE, value.name).apply()
    }

    /** Drop any accumulated prose when moving to another meeting or enabling from a fresh point. */
    fun resetSession(sessionId: String) {
        synchronized(lock) {
            sessionReadingChars.remove(sessionId)
            sessionHasSpoken.remove(sessionId)
            queue.removeAll { it.sessionId == sessionId }
        }
    }

    fun enqueueTurns(context: Context, sessionId: String, turns: List<MeetingTurn>) {
        initialize(context)
        if (!mutableEnabled.value || sessionId.isBlank() || turns.isEmpty()) return
        val paceFactor = mutablePace.value.factor
        synchronized(lock) {
            var readableChars = sessionReadingChars[sessionId] ?: 0
            var hasSpoken = sessionId in sessionHasSpoken
            turns.forEach { turn ->
                val speakerId = turn.speakerId
                val isCharacter = !speakerId.isNullOrBlank() && speakerId != "system"
                val segments = orderedSegments(turn)
                if (!isCharacter) {
                    readableChars = (readableChars + segments.sumOf { it.text.readingLength() }).coerceAtMost(500)
                    return@forEach
                }
                segments.forEachIndexed { segmentIndex, segment ->
                    when (segment.type) {
                        MeetingSegmentType.ACTION -> {
                            readableChars = (readableChars + segment.text.readingLength()).coerceAtMost(500)
                        }
                        MeetingSegmentType.DIALOGUE -> {
                            val spoken = segment.text.trim()
                            if (spoken.isNotBlank()) {
                                val delayMs = if (!hasSpoken) 0L else readingPauseMillis(readableChars, paceFactor)
                                queue += VoiceRequest(
                                    sessionId = sessionId,
                                    characterId = speakerId,
                                    text = spoken,
                                    cacheKey = "${turn.id}-$segmentIndex",
                                    delayBeforeMs = delayMs,
                                )
                                readableChars = 0
                                hasSpoken = true
                            }
                        }
                    }
                }
            }
            sessionReadingChars[sessionId] = readableChars
            if (hasSpoken) sessionHasSpoken += sessionId
        }
        pump()
    }

    private fun orderedSegments(turn: MeetingTurn): List<MeetingSegment> {
        if (turn.segments.isNotEmpty()) return turn.segments.filter { it.text.isNotBlank() }
        return buildList {
            if (turn.sceneText.isNotBlank()) add(MeetingSegment(MeetingSegmentType.ACTION, turn.sceneText))
            if (turn.dialogue.isNotBlank()) add(MeetingSegment(MeetingSegmentType.DIALOGUE, turn.dialogue))
        }
    }

    private fun String.readingLength(): Int = trim().replace(Regex("\\s+"), "").length.coerceAtMost(220)

    private fun readingPauseMillis(chars: Int, paceFactor: Double): Long {
        if (chars <= 0) return (420 * paceFactor).roundToLong().coerceAtLeast(250L)
        // Roughly 14 Chinese chars/sec for visual reading, with a short orientation beat.
        val base = 650L + chars.coerceAtMost(120) * 72L
        return (base * paceFactor).roundToLong().coerceIn(650L, 9_000L)
    }

    private fun pump() {
        val request = synchronized(lock) {
            if (playing || queue.isEmpty() || !mutableEnabled.value) return
            playing = true
            queue.removeFirst()
        }
        scope.launch {
            if (request.delayBeforeMs > 0) delay(request.delayBeforeMs)
            if (!mutableEnabled.value) {
                finishOne()
                return@launch
            }
            val context = appContext
            val speech = engine
            if (context == null || speech == null) {
                finishOne()
                return@launch
            }
            val cacheBase = File(context.filesDir, "meeting_voice_cache/${request.sessionId}/${request.cacheKey}")
            speech.speakAndCache(
                text = request.text,
                cacheBaseFile = cacheBase,
                scope = scope,
                voiceIdOverride = CharacterVoicePreferenceStore.voiceId(request.characterId),
                onFinished = ::finishOne,
            )
        }
    }

    private fun finishOne() {
        synchronized(lock) { playing = false }
        pump()
    }
}
