package com.jiacimu.lulu.data

import org.json.JSONObject
import java.time.Instant

/**
 * Reply attribution for delivered private proactive sends, from stored facts only.
 *
 * The sends come from the proactive-send decision receipts recorded by the
 * perception runtime (batch 25): one decision per actually delivered private
 * send, id "proactive-send:<timeline event id>", succeeded only. A send
 * counts as replied when a user statement exists in the same timeline
 * session after the send time. Nothing here reads message content, matches
 * keywords, or scores meaning, so it cannot become a semantic judge, and
 * it needs no new model call: the weekly report and the shadow gate both
 * reuse receipts and timeline events that already exist.
 *
 * A receipt whose delivered event is absent from the timeline (deleted,
 * or recorded without a resolvable event id) is unattributed: it is
 * reported separately and never counted as ignored, because guessing
 * would be worse than saying "could not attribute".
 */
data class ProactiveSendReceipt(
    val decisionId: String,
    val sentAt: Instant,
    val motiveId: String,
    val causalEvidenceId: String,
)

data class ProactiveSendSummary(
    val receipts: List<ProactiveSendReceipt>,
    val repliedByDecisionId: Map<String, Boolean>,
) {
    /** Receipts whose delivered event resolved to a timeline session. */
    val attributable: Int get() = repliedByDecisionId.size

    /** Attributable receipts followed by a user statement in the same session. */
    val replied: Int get() = repliedByDecisionId.values.count { it }

    /** Attributable receipts with no later user statement in the same session. */
    val unanswered: Int get() = attributable - replied

    /** Receipts that could not be attributed to a session; excluded from the rate. */
    val unattributed: Int get() = receipts.size - attributable

    val lastSentAt: Instant? get() = receipts.maxOfOrNull { it.sentAt }
}

object ProactiveSendAttribution {
    private const val RECEIPT_PREFIX = "proactive-send:"

    fun receipts(root: JSONObject): List<ProactiveSendReceipt> {
        val decisions = root.optJSONArray("decisions") ?: return emptyList()
        return buildList {
            for (i in 0 until decisions.length()) {
                val item = decisions.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (!id.startsWith(RECEIPT_PREFIX) || item.optBoolean("succeeded") != true) continue
                val sentAt = runCatching { Instant.parse(item.optString("at")) }.getOrNull() ?: continue
                add(
                    ProactiveSendReceipt(
                        decisionId = id,
                        sentAt = sentAt,
                        motiveId = item.optString("motiveId"),
                        causalEvidenceId = item.optString("causalEvidenceId"),
                    )
                )
            }
        }
    }

    /**
     * Maps each attributable receipt id to whether a user statement follows
     * it in the same session. Unattributable receipts are left out of the
     * map entirely. Character statements, tool receipts and statements in
     * other sessions never count as a reply.
     */
    fun repliedByDecisionId(
        receipts: List<ProactiveSendReceipt>,
        events: List<SharedTimelineEvent>,
    ): Map<String, Boolean> {
        if (receipts.isEmpty() || events.isEmpty()) return emptyMap()
        val sessionByEventId = events.associateBy { it.id }
        val userStatements = events.filter { it.evidenceKind == EventEvidenceKind.UserStatement }
        return receipts.mapNotNull { receipt ->
            val session = sessionByEventId[receipt.causalEvidenceId]?.sessionId
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val replied = userStatements.any {
                it.sessionId == session && it.occurredAt.isAfter(receipt.sentAt)
            }
            receipt.decisionId to replied
        }.toMap()
    }

    /**
     * Receipts plus reply attribution for one character. Safe to call when
     * the timeline is not initialized: the timeline read then yields no
     * events and every receipt reports as unattributed.
     */
    fun summarize(characterId: String): ProactiveSendSummary {
        val found = receipts(CharacterInnerLifeStore.snapshot(characterId))
        if (found.isEmpty()) return ProactiveSendSummary(emptyList(), emptyMap())
        val events = runCatching { SharedExperienceTimeline.all(characterId) }.getOrDefault(emptyList())
        return ProactiveSendSummary(found, repliedByDecisionId(found, events))
    }
}
