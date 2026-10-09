package com.jiacimu.lulu.data

/**
 * A percept is defined by real source identity, never by when a background
 * model happened to wake up. Re-examining an unanswered message is reflection,
 * not a fresh emotional event each time the worker runs.
 */
internal data class PerceptionStimulus(
    val evidenceId: String,
    val description: String,
    val socialIds: Set<String> = emptySet(),
)

internal object PerceptionStimulusResolver {
    fun select(
        unreadText: String,
        unreadIds: Set<String>,
        worldEvent: String,
        worldEventId: String,
        pendingText: String,
        pendingIds: List<String>,
    ): PerceptionStimulus? {
        if (unreadText.isNotBlank()) {
            // The ID of the latest actual observed user message, not a clock tick.
            val id = unreadIds.sorted().lastOrNull() ?: "unknown:${unreadText.hashCode()}"
            return PerceptionStimulus(
                id.take(220),
                "本次上线收到的消息：${unreadText.takeLast(180)}",
                setOf("user"),
            )
        }
        if (worldEvent.isNotBlank()) return PerceptionStimulus(
            "world-event:${worldEventId.ifBlank { worldEvent.hashCode().toString() }}",
            "本次世界真实事件：${worldEvent.take(180)}",
        )
        if (pendingText.isNotBlank()) {
            val id = pendingIds.firstOrNull() ?: "unknown:${pendingText.hashCode()}"
            return PerceptionStimulus(
                id.take(220),
                "尚未回复的真实消息：${pendingText.takeLast(180)}",
                setOf("user"),
            )
        }
        return null
    }
}
