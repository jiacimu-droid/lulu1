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
}
