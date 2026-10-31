package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMessageGateTest {

    private val now: Instant = Instant.parse("2026-10-26T12:00:00Z")

    private fun policy(
        enabled: Boolean = true,
        quietHoursEnabled: Boolean = true,
        quietStart: Int = 1380,
        quietEnd: Int = 420,
    ) = ProactiveGatePolicy(
        enabled = enabled,
        quietHoursEnabled = quietHoursEnabled,
        quietStartMinutesOfDay = quietStart,
        quietEndMinutesOfDay = quietEnd,
    )

    private fun thought(
        topicKey: String = "motive-1",
        createdAt: Instant = Instant.parse("2026-10-26T00:00:00Z"),
        expiresAt: Instant = Instant.parse("2026-10-29T00:00:00Z"),
        attempts: Int = 0,
    ) = ProactiveThoughtMeta(
        thought = "想问问今天过得怎么样",
        topicKey = topicKey,
        createdAt = createdAt,
        expiresAt = expiresAt,
        attempts = attempts,
        lastAttemptAt = null,
        status = "held",
    )

    private fun input(
        requestId: String = "test-1",
        localTime: LocalTime = LocalTime.of(12, 0),
        gatePolicy: ProactiveGatePolicy = policy(),
        modelAction: String = "message",
        hasFreshUserMessage: Boolean = false,
        lastProactiveSentAt: Instant? = null,
        proactiveSentToday: Int = 0,
        unansweredOutstanding: Int = 0,
        recentAttempts: List<ProactiveAttempt> = emptyList(),
        thoughtMeta: ProactiveThoughtMeta? = null,
    ) = ProactiveGateInput(
        characterId = "lulu",
        requestId = requestId,
        now = now,
        localTime = localTime,
        policy = gatePolicy,
        modelAction = modelAction,
        hasFreshUserMessage = hasFreshUserMessage,
        lastProactiveSentAt = lastProactiveSentAt,
        proactiveSentToday = proactiveSentToday,
        unansweredOutstanding = unansweredOutstanding,
        recentAttempts = recentAttempts,
        thought = thoughtMeta,
    )

    @Test
    fun allowWhenAllFactsPass() {
        val decision = ProactiveMessageGate.evaluate(
            input(lastProactiveSentAt = now.minus(Duration.ofHours(5)))
        )
        assertTrue(decision.allowedInShadow)
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
        assertEquals("test-1", decision.decisionId)
    }

    @Test
    fun rejectWhenConsentDisabled() {
        val decision = ProactiveMessageGate.evaluate(input(gatePolicy = policy(enabled = false)))
        assertFalse(decision.allowedInShadow)
        assertEquals(listOf(ProactiveGateReason.CONSENT_DISABLED), decision.reasons)
    }

    @Test
    fun rejectInQuietHoursOvernight() {
        val decision = ProactiveMessageGate.evaluate(input(localTime = LocalTime.of(1, 30)))
        assertEquals(listOf(ProactiveGateReason.QUIET_HOURS), decision.reasons)
    }

    @Test
    fun rejectInQuietHoursDaytimeWindow() {
        val decision = ProactiveMessageGate.evaluate(
            input(localTime = LocalTime.of(13, 30), gatePolicy = policy(quietStart = 780, quietEnd = 840))
        )
        assertEquals(listOf(ProactiveGateReason.QUIET_HOURS), decision.reasons)
    }

    @Test
    fun allowAtQuietBoundary() {
        // 跨夜语义：minute >= start || minute < end；结束分钟 07:00 本身不在静默内。
        val atEnd = ProactiveMessageGate.evaluate(input(localTime = LocalTime.of(7, 0)))
        assertEquals(listOf(ProactiveGateReason.ALLOWED), atEnd.reasons)
        // 开始分钟 23:00 本身在静默内。
        val atStart = ProactiveMessageGate.evaluate(input(localTime = LocalTime.of(23, 0)))
        assertEquals(listOf(ProactiveGateReason.QUIET_HOURS), atStart.reasons)
        // start == end 时 isQuiet 恒为 false。
        val emptyWindow = ProactiveMessageGate.evaluate(
            input(gatePolicy = policy(quietStart = 420, quietEnd = 420))
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), emptyWindow.reasons)
    }

    @Test
    fun isQuietRespectsQuietHoursEnabledFlag() {
        val decision = ProactiveMessageGate.evaluate(
            input(localTime = LocalTime.of(1, 30), gatePolicy = policy(quietHoursEnabled = false))
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun recentDeliveryIsNotAContactVeto() {
        val decision = ProactiveMessageGate.evaluate(
            input(lastProactiveSentAt = now.minus(Duration.ofMinutes(60)))
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun allowAfterCooldown() {
        val decision = ProactiveMessageGate.evaluate(
            input(lastProactiveSentAt = now.minus(Duration.ofMinutes(241)))
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun unansweredMessageDoesNotBlockAnotherAutonomousChoice() {
        val decision = ProactiveMessageGate.evaluate(input(unansweredOutstanding = 1))
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun allowWhenUnansweredCleared() {
        val decision = ProactiveMessageGate.evaluate(input(unansweredOutstanding = 0))
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun highDailyCountDoesNotBlockAnotherChoice() {
        val decision = ProactiveMessageGate.evaluate(input(proactiveSentToday = 2))
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun sameTopicAgainIsNotAProgrammaticRefusal() {
        // 注意：实际门控以 thought.topicKey 为过滤键，不是 input 上的独立字段。
        val decision = ProactiveMessageGate.evaluate(
            input(
                thoughtMeta = thought(),
                recentAttempts = listOf(
                    ProactiveAttempt("motive-1", now.minus(Duration.ofHours(2)), delivered = true)
                ),
            )
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun repeatedAttemptsNeverOverrideRoleChoice() {
        val old = now.minus(Duration.ofHours(100))
        val decision = ProactiveMessageGate.evaluate(
            input(
                thoughtMeta = thought(),
                recentAttempts = listOf(
                    ProactiveAttempt("motive-1", old, delivered = true),
                    ProactiveAttempt("motive-1", old.plus(Duration.ofHours(1)), delivered = true),
                    ProactiveAttempt("motive-1", old.plus(Duration.ofHours(2)), delivered = true),
                ),
            )
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun allowDifferentTopic() {
        val decision = ProactiveMessageGate.evaluate(
            input(
                thoughtMeta = thought(topicKey = "motive-2"),
                recentAttempts = listOf(
                    ProactiveAttempt("motive-1", now.minus(Duration.ofHours(2)), delivered = true)
                ),
            )
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun allowSameTopicAfterCooldown() {
        val decision = ProactiveMessageGate.evaluate(
            input(
                thoughtMeta = thought(),
                recentAttempts = listOf(
                    ProactiveAttempt("motive-1", now.minus(Duration.ofHours(25)), delivered = true)
                ),
            )
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun skipSameTopicWhenThoughtIsNull() {
        // thought=null 时同话题检查恒不起作用；不得用文本相似度补位。
        val decision = ProactiveMessageGate.evaluate(
            input(
                thoughtMeta = null,
                recentAttempts = listOf(
                    ProactiveAttempt("motive-1", now.minus(Duration.ofHours(2)), delivered = true)
                ),
            )
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun skipSameTopicWhenThoughtTopicKeyBlank() {
        val decision = ProactiveMessageGate.evaluate(
            input(
                thoughtMeta = thought(topicKey = ""),
                recentAttempts = listOf(
                    ProactiveAttempt("motive-1", now.minus(Duration.ofHours(2)), delivered = true)
                ),
            )
        )
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
    }

    @Test
    fun expiredThoughtIsDiagnosedNotDeleted() {
        val meta = thought(expiresAt = Instant.parse("2026-10-26T11:00:00Z"))
        val decision = ProactiveMessageGate.evaluate(input(thoughtMeta = meta))
        assertEquals(listOf(ProactiveGateReason.ALLOWED), decision.reasons)
        val expired = ProactiveMessageGate.expireIfDue(meta, now)
        assertEquals("expired", expired.status)
        assertEquals("想问问今天过得怎么样", expired.thought)
        assertEquals(meta.createdAt, expired.createdAt)
        assertEquals(meta.expiresAt, expired.expiresAt)
    }

    @Test
    fun attemptCountingMatchesActualCode() {
        // 实际代码：delivered 无论真假，attempts 都 +1；只有 delivered=true 才把 status 置为 sent。
        // 若主会话要求 attempts 只计已送达，须另改门控文件，不得改本测试来掩盖。
        val meta = thought()
        val delivered = ProactiveMessageGate.recordAttempt(meta, now, delivered = true)
        assertEquals(1, delivered.attempts)
        assertEquals("sent", delivered.status)
        assertEquals(now, delivered.lastAttemptAt)
        val undelivered = ProactiveMessageGate.recordAttempt(meta, now, delivered = false)
        assertEquals(1, undelivered.attempts)
        assertEquals("held", undelivered.status)
        assertEquals(now, undelivered.lastAttemptAt)
    }

    @Test
    fun evaluateIsPureAndIdempotent() {
        val in1 = input(lastProactiveSentAt = now.minus(Duration.ofHours(5)))
        val first = ProactiveMessageGate.evaluate(in1)
        val second = ProactiveMessageGate.evaluate(in1)
        assertEquals(first, second)
        assertEquals("message", in1.modelAction)
        assertEquals(0, in1.proactiveSentToday)
    }

    @Test
    fun modelDecisionIsNotMutated() {
        val in1 = input(modelAction = "message", thoughtMeta = thought())
        ProactiveMessageGate.evaluate(in1)
        assertEquals("message", in1.modelAction)
        assertEquals("motive-1", in1.thought?.topicKey)
    }

    @Test
    fun skippedForFreshUserReply() {
        // 有新用户消息时，即使其他事实会拒绝，也只返回 SKIPPED，不计为拒绝。
        val decision = ProactiveMessageGate.evaluate(
            input(
                hasFreshUserMessage = true,
                gatePolicy = policy(enabled = false),
                localTime = LocalTime.of(1, 30),
                unansweredOutstanding = 5,
            )
        )
        assertEquals(listOf(ProactiveGateReason.SKIPPED_NOT_PROACTIVE), decision.reasons)
        assertFalse(decision.allowedInShadow)
    }

    @Test
    fun topicKeyForPrefersMotiveThenConcern() {
        assertEquals("motive-9", ProactiveMessageGate.topicKeyFor("motive-9", "concern-3"))
        assertEquals("concern-3", ProactiveMessageGate.topicKeyFor(null, "concern-3"))
        assertEquals("concern-3", ProactiveMessageGate.topicKeyFor("", "concern-3"))
        assertEquals("", ProactiveMessageGate.topicKeyFor(null, null))
        assertEquals("", ProactiveMessageGate.topicKeyFor("", ""))
    }

    @Test
    fun gateReasonOrderIsFixed() {
        // 同意关闭优先于静默：两者同时成立时只返回第一个原因（实际代码为单原因 when）。
        val decision = ProactiveMessageGate.evaluate(
            input(gatePolicy = policy(enabled = false), localTime = LocalTime.of(1, 30))
        )
        assertEquals(listOf(ProactiveGateReason.CONSENT_DISABLED), decision.reasons)
    }
}
