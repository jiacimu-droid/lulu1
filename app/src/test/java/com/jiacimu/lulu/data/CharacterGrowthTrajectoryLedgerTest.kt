package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterGrowthTrajectoryLedgerTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun importantFeedbackIsVisibleBeforeAnyApologyAndRetractionHonorsDeletion() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        val role = "growth-trail-private-role"
        val evidence = "growth-trail-original-feedback"
        val now = Instant.now()
        SharedExperienceTimeline.record(evidence, role, "私聊", "用户",
            "你总是把我弱化，这让我很没有安全感。", now,
            triggerExtraction = false, sessionId = "our-chat", source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val trace = CharacterGrowthTrajectory.entries(role, now.plusSeconds(2))
        assertTrue(trace.any { it.evidenceId == evidence && it.category == "反馈" })
        assertFalse(trace.any { it.category == "成长" })
        assertFalse(trace.any { it.category == "反思" })
        assertTrue(CharacterGrowthTrajectory.context(role, now.plusSeconds(2)).contains("弱化"))

        SharedExperienceTimeline.deleteEvent(evidence)
        assertFalse(CharacterGrowthTrajectory.entries(role, now.plusSeconds(5))
            .any { it.evidenceId == evidence })
        assertFalse(CharacterGrowthTrajectory.context(role, now.plusSeconds(5)).contains("弱化"))
    }
}
