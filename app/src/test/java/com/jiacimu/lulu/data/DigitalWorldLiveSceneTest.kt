package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DigitalWorldLiveSceneTest {
    @Test fun heartbeatCannotSurviveBackgroundingOrClockRollback() {
        assertTrue(DigitalWorldLiveScene.fresh(1_000L, 5_999L))
        assertFalse(DigitalWorldLiveScene.fresh(1_000L, 6_001L))
        assertFalse(DigitalWorldLiveScene.fresh(1_000L, 999L))
    }

    @Test fun roleCannotApproachAnUnwitnessedVisitOrUseAnOldSession() {
        val context = RuntimeEnvironment.getApplication()
        val role = "live-scene-test"
        val token = DigitalWorldLiveScene.begin(context, "shared:cafe", "咖啡角")
        DigitalWorldLiveScene.update(token, 10f, 20f, emptySet(), "用户在吧台旁")
        assertNull(DigitalWorldLiveScene.witnessed(role))
        assertEquals("", DigitalWorldLiveScene.context(role))
        assertTrue(runCatching { DigitalWorldLiveScene.request(role, "approach_user", Instant.now()) }.isFailure)
        DigitalWorldLiveScene.end(token)
        // A queued coroutine belonging to the previous scene cannot publish into the new one.
        val next = DigitalWorldLiveScene.begin(context, "shared:courtyard", "庭院")
        DigitalWorldLiveScene.update(token, 10f, 20f, setOf(role), "旧房间的用户")
        assertNull(DigitalWorldLiveScene.witnessed(role))
        DigitalWorldLiveScene.end(next)
    }

    @Test fun actualWorldBehaviorFeedsSocialAppraisalButModelClaimsDoNot() {
        val now = Instant.now()
        val observed = SharedTimelineEvent("world-behavior-walk", "role", "数字世界·庭院", "场景事实",
            "用户走到长椅附近，停下", now, source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        assertEquals(setOf("user"), CharacterPerceptionContext.stimulus(observed).socialIds)
        assertTrue(CharacterCausalAppraisalStage.eligible(observed))
        assertFalse(CharacterCausalAppraisalStage.eligible(observed.copy(evidenceKind = EventEvidenceKind.CharacterStatement)))
        assertEquals(listOf(observed), CharacterPerceptionContext.selectRecent(listOf(observed,
            observed.copy(id = "imagined", evidenceKind = EventEvidenceKind.Inference)), now))
    }
}
