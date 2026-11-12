package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.PerceptionStimulusLedger
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.SharedTimelineEvent

/** A result belongs to the original call; speech always gets the first opportunity. */
internal object CallExternalResultPolicy {
    /** Resolve at dispatch: a normal user turn may have handled the result while it waited. */
    fun pendingEvidence(context: Context, characterId: String, evidenceId: String): SharedTimelineEvent? {
        val event = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .firstOrNull() ?: return null
        val toolReceipt = event.source == "tool-router" && event.evidenceKind == EventEvidenceKind.ToolResult
        return event.takeUnless {
            toolReceipt && PerceptionStimulusLedger.hasSeen(context, characterId, event.id, event.revision)
        }
    }

    fun canDeliver(expectedRole: String, expectedCall: String, role: String, call: String,
        connected: Boolean, opening: Boolean, thinking: Boolean, speaking: Boolean,
        userSpeaking: Boolean, audioPending: Boolean): Boolean =
        connected && expectedRole == role && expectedCall == call &&
            !opening && !thinking && !speaking && !userSpeaking && !audioPending
}
