package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CharacterContinuitySnapshotTest {
    @Test fun otherGroupSpeakersCannotEvictOwnPriorDialogueAnchors() {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val role = "group-witness-flood-continuity"
        val base = Instant.parse("2026-11-04T03:00:00Z")
        SharedExperienceTimeline.record("owned-user-before-group", role, "私聊", "用户",
            "周末一起看书吧。", base, triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
        SharedExperienceTimeline.record("owned-role-before-group", role, "私聊", "角色",
            "好呀，我想读那本小说。", base.plusSeconds(1), triggerExtraction = false,
            evidenceKind = EventEvidenceKind.CharacterStatement)
        // These are actual witnessed group records, but none has a canonical
        // sent chat message establishing this role as its author.
        repeat(175) { index ->
            SharedExperienceTimeline.record("other-group-speaker-$index:group:$role",
                role, "群聊", "其他成员", "其他成员在群里说话 $index",
                base.plusSeconds(index + 2L), source = "message",
                triggerExtraction = false,
                evidenceKind = EventEvidenceKind.CharacterStatement)
        }
        val snapshot = CharacterContinuityRuntime.snapshot(role, now = base.plusSeconds(300))
        assertEquals("owned-user-before-group", snapshot.lastUserStatement?.id)
        assertEquals("owned-role-before-group", snapshot.lastCharacterStatement?.id)
        assertEquals(base.plusSeconds(1), snapshot.previousInteractionAt)
        assertFalse(CharacterContinuityRuntime.render(snapshot).contains("其他成员在群里说话"))
    }

    @Test fun reanswerHidesTheSupersededRoleSpeechButKeepsOlderDialogue() {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val role = "reanswer-continuity-boundary"
        val base = Instant.parse("2026-11-04T07:00:00Z")
        SharedExperienceTimeline.record("original-user", role, "私聊", "用户",
            "你能帮我找资料吗", base, triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
        SharedExperienceTimeline.record("earlier-role", role, "私聊", "角色",
            "之前聊的电影还挺有意思", base.minusSeconds(60), triggerExtraction = false,
            evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record("discarded-reply", role, "私聊", "角色",
            "我没有联网工具", base.plusSeconds(1), triggerExtraction = false,
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val normal = CharacterContinuityRuntime.snapshot(role, now = base.plusSeconds(10))
        assertEquals("discarded-reply", normal.lastCharacterStatement?.id)
        val fresh = CharacterContinuityRuntime.snapshot(role,
            now = base.plusSeconds(10),
            excludedEventIds = setOf("discarded-reply"))
        assertEquals("earlier-role", fresh.lastCharacterStatement?.id)
        assertEquals("original-user", fresh.lastUserStatement?.id)
        assertFalse(CharacterContinuityRuntime.render(fresh).contains("我没有联网工具"))
    }

    @Test fun identicalShortUserMessagesRemainSeparatePriorTurns() {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val role = "identical-user-echo-continuity"
        val base = Instant.parse("2026-11-04T06:00:00Z")
        SharedExperienceTimeline.record("first-yes", role, "私聊", "用户",
            "好。", base, triggerExtraction = false, source = "legacy",
            evidenceKind = EventEvidenceKind.UserStatement)
        SharedExperienceTimeline.record("second-yes", role, "私聊", "用户",
            "好。", base.plusSeconds(20), triggerExtraction = false, source = "legacy",
            evidenceKind = EventEvidenceKind.UserStatement)
        val now = base.plusSeconds(30)
        // The latest one may be the current input, but the preceding same
        // words were an independent real message, not another copy of it.
        val echo = CharacterContinuityRuntime.snapshot(role, "好", now)
        assertEquals("first-yes", echo.lastUserStatement?.id)
        assertEquals(base, echo.previousInteractionAt)
        assertEquals("second-yes", CharacterContinuityRuntime.snapshot(role, now = now)
            .lastUserStatement?.id)
    }

    @Test fun matchingEchoExclusionIsNotRepeatedAcrossGroupWitnessPages() {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val role = "repeated-echo-with-busy-group"
        val base = Instant.parse("2026-11-04T06:30:00Z")
        listOf("earlier-user", "latest-user").forEachIndexed { index, id ->
            SharedExperienceTimeline.record(id, role, "私聊", "用户",
                "嗯", base.plusSeconds(index.toLong()), triggerExtraction = false,
                source = "legacy", evidenceKind = EventEvidenceKind.UserStatement)
        }
        repeat(175) { index ->
            SharedExperienceTimeline.record("other-${index}:group:$role", role,
                "群聊·旁人讨论", "别的角色", "群里其他人 #$index",
                base.plusSeconds(index + 2L), triggerExtraction = false,
                source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        }
        val snapshot = CharacterContinuityRuntime.snapshot(role, "嗯", base.plusSeconds(179))
        assertEquals("earlier-user", snapshot.lastUserStatement?.id)
        assertEquals(base, snapshot.previousInteractionAt)
    }

    @Test fun backgroundEventsDoNotEvictLastActualExchange() {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val role = "dialogue-anchor-after-background-events"
        val base = Instant.parse("2026-11-04T02:00:00Z")
        SharedExperienceTimeline.record("anchor-user", role, "私聊", "用户",
            "明天再说这件事，今晚先放下。", base, triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
        SharedExperienceTimeline.record("anchor-character", role, "私聊", "角色",
            "好，明天再接着说。", base.plusSeconds(1), triggerExtraction = false,
            evidenceKind = EventEvidenceKind.CharacterStatement)
        repeat(170) { index ->
            SharedExperienceTimeline.record("background-$index", role, "数字世界", "观察",
                "真实发生的普通环境事件 $index", base.plusSeconds(index + 2L),
                triggerExtraction = false, evidenceKind = EventEvidenceKind.Observation)
        }
        val now = base.plusSeconds(300)
        assertTrue(SharedExperienceTimeline.recentEvents(role, 160)
            .none { it.id == "anchor-user" })
        val snapshot = CharacterContinuityRuntime.snapshot(role, now = now)
        assertEquals("anchor-user", snapshot.lastUserStatement?.id)
        assertEquals("anchor-character", snapshot.lastCharacterStatement?.id)
        assertEquals(base.plusSeconds(1), snapshot.previousInteractionAt)
        val rendered = CharacterContinuityRuntime.render(snapshot)
        assertTrue(rendered.contains("今晚先放下"))
        assertTrue(rendered.contains("明天再接着说"))
    }
    @Test fun soloArtifactsAndPublicPostsDoNotResetActualUserContact() {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val role = "solo-artifact-contact-boundary"
        val base = Instant.parse("2026-11-04T04:00:00Z")
        SharedExperienceTimeline.record("contact-user", role, "私聊", "用户",
            "明天再聊。", base, triggerExtraction = false, source = "legacy",
            evidenceKind = EventEvidenceKind.UserStatement)
        SharedExperienceTimeline.record("contact-own", role, "私聊", "角色",
            "好，明天见。", base.plusSeconds(1), triggerExtraction = false,
            source = "legacy", evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record("solo-practice", role, "自主学习·私人练习", "角色",
            "{\"artifact\":\"私人练习稿\"}", base.plusSeconds(2),
            triggerExtraction = false, source = "learning-practice:goal",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record("public-post", role, "朋友圈", "角色",
            "今天看了一本书。", base.plusSeconds(3), triggerExtraction = false,
            source = "moment:self", evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record("user-public-post", role, "朋友圈", "用户",
            "随手发了一条动态。", base.plusSeconds(4), triggerExtraction = false,
            source = "moment:user", evidenceKind = EventEvidenceKind.UserStatement)
        SharedExperienceTimeline.record("resident-conversation", role,
            "数字世界见面·咖啡馆", "角色", "我和居民聊了天气。",
            base.plusSeconds(5), triggerExtraction = false, sessionId = "resident-session",
            source = "meeting", evidenceKind = EventEvidenceKind.CharacterStatement)
        val snapshot = CharacterContinuityRuntime.snapshot(role, now = base.plusSeconds(300))
        assertEquals("contact-user", snapshot.lastUserStatement?.id)
        assertEquals("contact-own", snapshot.lastCharacterStatement?.id)
        assertEquals(base.plusSeconds(1), snapshot.previousInteractionAt)
        assertFalse(CharacterContinuityRuntime.render(snapshot).contains("私人练习稿"))
    }


}
