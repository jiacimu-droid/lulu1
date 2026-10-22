package com.jiacimu.lulu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real playback timeline for the single active phone session.
 *
 * LLM decisions are discrete and may arrive seconds before TTS; this clock
 * advances ONLY when the existing voice player reports that a particular
 * segment has actually begun or ended. It never makes new AI requests.
 * A monotonically increasing revision prevents an old TTS callback from
 * accidentally clearing a newer speech segment or another call's face.
 */
internal object Live2DSpeechTimeline {
    enum class Phase { Idle, Playing, Finished, Interrupted }
    data class Event(
        val characterId: String = "",
        val callId: String = "",
        val utteranceId: String = "",
        val phase: Phase = Phase.Idle,
        val revision: Long = 0L,
    )
    private val mutableEvents = MutableStateFlow(Event())
    val events = mutableEvents.asStateFlow()
    private var revision = 0L

    @Synchronized
    fun started(characterId: String, callId: String, utteranceId: String) {
        if (characterId.isBlank() || callId.isBlank() || utteranceId.isBlank()) return
        mutableEvents.value = Event(characterId, callId, utteranceId,
            Phase.Playing, ++revision)
    }

    @Synchronized
    fun finished(characterId: String, callId: String, utteranceId: String) {
        val current = mutableEvents.value
        if (current.characterId != characterId || current.callId != callId ||
            current.utteranceId != utteranceId || current.phase != Phase.Playing) return
        mutableEvents.value = current.copy(phase = Phase.Finished, revision = ++revision)
    }

    @Synchronized
    fun interrupted(characterId: String, callId: String) {
        val current = mutableEvents.value
        if (current.characterId != characterId || current.callId != callId ||
            current.phase == Phase.Interrupted) return
        mutableEvents.value = current.copy(phase = Phase.Interrupted, revision = ++revision)
    }
}
