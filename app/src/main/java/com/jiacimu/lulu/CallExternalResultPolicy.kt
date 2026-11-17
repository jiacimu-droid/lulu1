package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterPerceptionContext
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
            toolReceipt && (PerceptionStimulusLedger.hasSeen(context, characterId, event.id, event.revision) ||
                !hasLiveReceiptEvidence(event))
        }
    }

    /** Coalesce queued IDs, but allow a corrected canonical revision to be offered again. */
    fun enqueue(
        context: Context, characterId: String, evidenceId: String,
        pending: MutableSet<String>, offeredRevisions: MutableMap<String, Long>,
    ): Boolean {
        val event = pendingEvidence(context, characterId, evidenceId) ?: return false
        if (offeredRevisions[event.id] == event.revision) return false
        offeredRevisions[event.id] = event.revision
        pending.add(event.id)
        return true
    }

    fun hasLiveReceiptEvidence(event: SharedTimelineEvent): Boolean =
        event.id in CharacterPerceptionContext.liveEvidenceIds(event.characterId, listOf(event.id))

    fun canDeliver(expectedRole: String, expectedCall: String, role: String, call: String,
        connected: Boolean, opening: Boolean, thinking: Boolean, speaking: Boolean,
        userSpeaking: Boolean, audioPending: Boolean, hangupPending: Boolean = false): Boolean =
        connected && expectedRole == role && expectedCall == call &&
            !opening && !thinking && !speaking && !userSpeaking && !audioPending && !hangupPending
}
