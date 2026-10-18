package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RelationshipRhythmPerceptionTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")
    private val role = "rhythm-test"
    private fun day(ago: Long, hour: Long = 16): Instant =
        now.minusSeconds(ago * 86400L).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            .plusSeconds(hour * 3600L)
    private fun user(id: String, ago: Long) = SharedTimelineEvent(
        id, role, "私聊", "用户", "你好", day(ago),
        evidenceKind = EventEvidenceKind.UserStatement,
    )
    private fun call(id: String, ago: Long, hour: Long) = SharedTimelineEvent(
        "interaction-call-start-$id", role, "电话互动", "通话操作",
        "电话真实接通", day(ago, hour), sessionId = id,
        source = InteractionSignalBridge.SOURCE, evidenceKind = EventEvidenceKind.Observation,
    )

    @Test fun comparesRealInteractionFrequencyWithoutInventedFeelings() {
        val baseline = (8L..19L).flatMap { d -> (0..3).map { user("old-$d-$it", d) } }
        val present = listOf(user("new-1", 1), user("new-2", 3))
        val report = RelationshipRhythmPerception.context(baseline + present, now, ZoneId.of("UTC"))
        assertTrue(report.contains("前14天有48条"))
        assertTrue(report.contains("最近7天有2条"))
        assertFalse(report.contains("角色很失落"))
    }

    @Test fun sourceDeletionAutomaticallyRemovesDerivedChange() {
        val baseline = (8L..19L).flatMap { d -> (0..3).map { user("old-$d-$it", d) } }
        assertTrue(RelationshipRhythmPerception.context(baseline, now, ZoneId.of("UTC")).isNotBlank())
        assertEquals("", RelationshipRhythmPerception.context(emptyList(), now, ZoneId.of("UTC")))
    }

    @Test fun phoneTimeCrossesMidnightCorrectly() {
        val events = listOf(
            call("a", 18, 2), call("b", 15, 2), call("c", 11, 1),
            call("d", 4, 23), call("e", 2, 23),
        )
        val report = RelationshipRhythmPerception.context(events, now, ZoneId.of("UTC"))
        assertTrue(report.contains("典型本地时刻"))
        assertTrue(report.contains("23:00"))
        assertTrue(report.contains("02:00") || report.contains("01:00"))
    }

    @Test fun oldNoiseAndCharacterActionsCannotBecomeUserContact() {
        val unrelated = (8L..19L).map { d ->
            SharedTimelineEvent("act-$d", role, "数字世界", "角色", "整理书架",
                day(d), evidenceKind = EventEvidenceKind.CharacterStatement)
        }
        assertEquals("", RelationshipRhythmPerception.context(unrelated, now, ZoneId.of("UTC")))
    }
}
