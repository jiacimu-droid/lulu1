package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * P0, shadow only: a pure, fact-based record of whether a model-chosen
 * proactive message would pass timing / frequency / consent checks.
 *
 * This object never decides what a character wants to say, never rewrites
 * a model decision, and never sends, blocks, or delays a message by itself.
 * Callers may only log the returned decision. Any future enforcement is a
 * separate, explicitly authorised step.
 *
 * Inputs are facts only (timestamps, counts, consent flag, topic key from
 * an existing motive / concern id). No text is searched, matched, or scored
 * here, so this cannot become a keyword / regex semantic judge.
 */
enum class ProactiveGateReason {
    SKIPPED_NOT_PROACTIVE,
    CONSENT_DISABLED,
    QUIET_HOURS,
    UNANSWERED_OUTSTANDING,
    COOLDOWN,
    DAILY_CAP,
    SAME_TOPIC_RECENT,
    THOUGHT_EXPIRED,
    ALLOWED,
}

data class ProactiveGatePolicy(
    val enabled: Boolean,
    val quietHoursEnabled: Boolean,
    val quietStartMinutesOfDay: Int,
    val quietEndMinutesOfDay: Int,
    val minGapMinutes: Long = 240,
    val maxPerDay: Int = 2,
    val maxUnansweredOutstanding: Int = 1,
    val sameTopicCooldownHours: Long = 24,
    val thoughtTtlHours: Long = 72,
    val maxAttemptsPerTopic: Int = 3,
)

data class ProactiveAttempt(
    val topicKey: String,
    val attemptedAt: Instant,
    /** Must come from a real ToolRouter receipt, never from a model claim. */
    val delivered: Boolean,
)

data class ProactiveThoughtMeta(
    /** Audit echo only. Never used for semantic judgement in this file. */
    val thought: String,
    val topicKey: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val attempts: Int,
    val lastAttemptAt: Instant?,
    val status: String,
)

data class ProactiveGateInput(
    val characterId: String,
    /** Reuse the runtime's existing requestId, so shadow logs are idempotent. */
    val requestId: String,
    val now: Instant,
    /** Caller converts with the device timezone; this file infers no timezone. */
    val localTime: LocalTime,
    val policy: ProactiveGatePolicy,
    /** The model-chosen action, echoed only. This gate never mutates it. */
    val modelAction: String,
    val hasFreshUserMessage: Boolean,
    val lastProactiveSentAt: Instant?,
    val proactiveSentToday: Int,
    val unansweredOutstanding: Int,
    val recentAttempts: List<ProactiveAttempt>,
    val thought: ProactiveThoughtMeta?,
)

data class ProactiveGateDecision(
    val decisionId: String,
    val allowedInShadow: Boolean,
    val reasons: List<ProactiveGateReason>,
    val evaluatedAt: Instant,
)

object ProactiveMessageGate {
    fun evaluate(input: ProactiveGateInput): ProactiveGateDecision {
        val reason = when {
            input.hasFreshUserMessage -> ProactiveGateReason.SKIPPED_NOT_PROACTIVE
            !input.policy.enabled -> ProactiveGateReason.CONSENT_DISABLED
            isQuiet(input.policy, input.localTime) -> ProactiveGateReason.QUIET_HOURS
            input.unansweredOutstanding >= input.policy.maxUnansweredOutstanding ->
                ProactiveGateReason.UNANSWERED_OUTSTANDING
            input.lastProactiveSentAt != null &&
                Duration.between(input.lastProactiveSentAt, input.now).toMinutes() <
                input.policy.minGapMinutes -> ProactiveGateReason.COOLDOWN
            input.proactiveSentToday >= input.policy.maxPerDay ->
                ProactiveGateReason.DAILY_CAP
            hasSameTopicRecent(input) -> ProactiveGateReason.SAME_TOPIC_RECENT
            input.thought != null && input.now.isAfter(input.thought.expiresAt) ->
                ProactiveGateReason.THOUGHT_EXPIRED
            else -> ProactiveGateReason.ALLOWED
        }
        return ProactiveGateDecision(
            decisionId = input.requestId,
            allowedInShadow = reason == ProactiveGateReason.ALLOWED,
            reasons = listOf(reason),
            evaluatedAt = input.now,
        )
    }

    fun isQuiet(policy: ProactiveGatePolicy, localTime: LocalTime): Boolean {
        if (!policy.quietHoursEnabled) return false
        val start = policy.quietStartMinutesOfDay
        val end = policy.quietEndMinutesOfDay
        if (start == end) return false
        val minute = localTime.hour * 60 + localTime.minute
        return if (start < end) minute in start until end
        else minute >= start || minute < end
    }

    fun topicKeyFor(motiveId: String?, concernThreadId: String?): String =
        when {
            !motiveId.isNullOrBlank() -> motiveId
            !concernThreadId.isNullOrBlank() -> concernThreadId
            else -> ""
        }

    fun recordAttempt(
        meta: ProactiveThoughtMeta,
        now: Instant,
        delivered: Boolean,
    ): ProactiveThoughtMeta = meta.copy(
        attempts = meta.attempts + 1,
        lastAttemptAt = now,
        status = if (delivered) "sent" else meta.status,
    )

    fun expireIfDue(meta: ProactiveThoughtMeta, now: Instant): ProactiveThoughtMeta =
        if (now.isAfter(meta.expiresAt)) meta.copy(status = "expired") else meta

    private fun hasSameTopicRecent(input: ProactiveGateInput): Boolean {
        val topicKey = input.thought?.topicKey.orEmpty()
        if (topicKey.isBlank()) return false
        val attempts = input.recentAttempts.filter { it.topicKey == topicKey }
        if (attempts.size >= input.policy.maxAttemptsPerTopic) return true
        return attempts.any { attempt ->
            attempt.delivered &&
                Duration.between(attempt.attemptedAt, input.now).toHours() <
                input.policy.sameTopicCooldownHours
        }
    }
}
