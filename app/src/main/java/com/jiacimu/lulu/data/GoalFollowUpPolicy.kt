package com.jiacimu.lulu.data

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * The existing character intention is the single source of truth.
 * A real outgoing action is evidence of an ATTEMPT, never proof the
 * relationship/other personal aim has been fulfilled.
 */
internal object GoalFollowUpPolicy {
    private const val FIVE_MINUTES = 300L

    fun outwardAction(action: String): Boolean = action.lowercase() in setOf(
        "message", "phone_reply", "send_private_message", "start_call",
        "send_private_sticker", "send_group_message",
    )

    /** Existing contact pacing/explicit-space boundary applies per action,
     * never to the whole model decision. Silence alone is not a request for space. */
    fun contactBlockReason(goal: JSONObject?, action: String, autonomous: Boolean,
        intentionId: String = "", reviewingGoal: Boolean = false): String? {
        if (!autonomous || goal == null || !outwardAction(action)) return null
        if (goal.optBoolean("userRequestedSpace"))
            return "用户明确要求暂停主动联系；尊重边界，等对方主动交流"
        // A lack of reply is not an owner request for space. A numeric count
        // of earlier contact attempts is not authority to overrule the role's
        // next valid decision. Actual explicit space requests still block.
        return null
    }

    fun initialReview(proposal: JSONObject, at: Instant): Instant? {
        // An intention with an actual next step merits one revisit; a generic
        // feeling with no plan does not start an endless paid five-minute loop.
        if (proposal.optString("nextStep").isBlank()) return null
        return at.plusSeconds(FIVE_MINUTES)
    }

    /** Only the role decides what to do next. Lack of a reply never terminates a live goal. */
    @Suppress("UNUSED_PARAMETER")
    fun afterReceipt(action: String, success: Boolean, priorUnanswered: Int,
                     now: Instant): Instant? {
        if (!success || !outwardAction(action)) return null
        return now.plusSeconds(FIVE_MINUTES)
    }


    fun reviewMatches(goal: JSONObject?, intentionId: String, plannedMillis: Long,
                      now: Instant): Boolean {
        if (goal == null || goal.optString("createdAt") != intentionId ||
            goal.optString("stage") in setOf("paused", "released", "completed")) return false
        val due = runCatching { Instant.parse(goal.optString("nextReviewAt")) }.getOrNull()
            ?: return false
        // Android may delay work; a stale promise does not license a
        // surprise unsolicited message hours after the intended review.
        val lateBy = Duration.between(due, now).seconds
        return due.toEpochMilli() == plannedMillis && lateBy in -2L..(30L * 60L)
    }

    /** The five-minute online expiry can do this same appraisal once.
     * A separate goal worker at nearly the same deadline wastes a model call. */
    fun isCoveredByOnlineExpiry(reviewAt: Instant, onlineUntil: Instant?, now: Instant): Boolean {
        if (onlineUntil == null) return false
        return kotlin.math.abs(Duration.between(reviewAt, onlineUntil).toMillis()) <= 60_000L &&
            Duration.between(now, onlineUntil).toMillis() in -30_000L..60_000L
    }

    fun hasRequestedSpace(text: String): Boolean =
        Regex("别再(给我发消息|联系我|找我|打电话)|不要再(给我发消息|联系我|找我|打电话)|先别联系我|给我点空间|让我静静").containsMatchIn(text.replace(Regex("""\s+"""), ""))
}
