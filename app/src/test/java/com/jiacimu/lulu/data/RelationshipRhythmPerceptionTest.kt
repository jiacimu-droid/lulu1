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

    @Test fun orphanGroupUserWitnessesDoNotInflateContactOrRelationshipRhythm() {
        val orphan = (8L..19L).flatMap { d ->
            (0..3).map { index ->
                SharedTimelineEvent(
                    "orphan-$d-$index:group:$role", role, "群聊·测试群",
                    "用户", "一条已经失去原始消息的见证记录", day(d),
                    sessionId = "missing-group-conversation",
                    source = "message", evidenceKind = EventEvidenceKind.UserStatement)
            }
        }
        // Group timeline rows may outlive deleted/failed canonical messages.
        // A witness label alone must not reset the last user contact or
        // fabricate a 48-message relationship-frequency change.
        assertTrue(CompanionContactClock.verifiedUserContacts(orphan).isEmpty())
        assertEquals("", RelationshipRhythmPerception.context(
            orphan + listOf(user("real-direct", 1)), now, ZoneId.of("UTC")))
        val mixed = orphan + listOf(user("real-direct", 1))
        assertEquals(listOf("real-direct"),
            CompanionContactClock.verifiedUserContacts(mixed).map { it.id })
        assertTrue(CompanionContactClock.describe(
            CompanionContactClock.verifiedUserContacts(mixed).map { it.occurredAt }, now)
            .contains("最近一次用户联系"))
    }

    @Test fun migratedGroupChannelOrWitnessIdCannotBypassUserAuthorship() {
        // Imported/renamed group channels can have a prefix, and historical
        // group witnesses may keep the :group:<role> ID after a channel edit.
        // Neither representation turns an orphan into a verified user message.
        val variants = listOf(
            SharedTimelineEvent(
                "renamed-orphan:group:$role", role, "私聊", "用户",
                "已失去原消息的群聊见证", day(1),
                sessionId = "missing-group", source = "message",
                evidenceKind = EventEvidenceKind.UserStatement),
            SharedTimelineEvent(
                "prefixed-orphan", role, "手机群聊·旧群", "用户",
                "导入的群聊见证", day(2),
                sessionId = "missing-group", source = "message",
                evidenceKind = EventEvidenceKind.UserStatement),
            SharedTimelineEvent(
                "normal-orphan:group:$role", role, "群聊·旧群", "用户",
                "旧群聊见证", day(3),
                sessionId = "missing-group", source = "message",
                evidenceKind = EventEvidenceKind.UserStatement),
        )
        assertTrue(CompanionContactClock.verifiedUserContacts(variants).isEmpty())
        assertEquals("", RelationshipRhythmPerception.context(
            variants, now, ZoneId.of("UTC")))
        assertEquals(listOf("real-direct"), CompanionContactClock.verifiedUserContacts(
            variants + user("real-direct", 1)).map { it.id })
    }

    @Test fun oldNoiseAndCharacterActionsCannotBecomeUserContact() {
        val unrelated = (8L..19L).map { d ->
            SharedTimelineEvent("act-$d", role, "数字世界", "角色", "整理书架",
                day(d), evidenceKind = EventEvidenceKind.CharacterStatement)
        }
        assertEquals("", RelationshipRhythmPerception.context(unrelated, now, ZoneId.of("UTC")))
    }
}
