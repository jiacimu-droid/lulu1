package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * A question born during a quiet window may refer to a *past* lived event.
 * Only accept an exact, still-present original event shown to the planner.
 * Never turn a character's own story or imagined intention into an observation.
 */
internal object CharacterCuriosityEvidence {
    fun findOriginal(
        proposal: JSONObject?,
        recentLifeEvents: List<SharedTimelineEvent>,
    ): SharedTimelineEvent? {
        val requestedId = proposal?.optString("sourceEvidenceId")?.trim().orEmpty()
        if (requestedId.isBlank()) return null
        return recentLifeEvents.lastOrNull { event ->
            event.id == requestedId &&
                event.content.isNotBlank() &&
                event.evidenceKind != EventEvidenceKind.Inference &&
                event.evidenceKind != EventEvidenceKind.CharacterStatement &&
                event.evidenceKind != EventEvidenceKind.UserStatement &&
                (
                    event.channel.startsWith("独自阅读") ||
                    event.channel.startsWith("独自游戏") ||
                    event.channel.startsWith("数字世界") ||
                    event.channel.startsWith("现实世界窗口")
                )
        }
    }
}
