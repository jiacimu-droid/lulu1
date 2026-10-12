package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterLifeVisibilityTest {
    private val ctx get() = RuntimeEnvironment.getApplication() as Context

    @Before fun init() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        CharacterInnerLifeStore.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        CharacterCuriosityRuntime.initialize(ctx)
        CharacterLifeFollowThroughStore.initialize(ctx)
        CharacterDevelopmentStore.initialize(ctx)
    }

    @After fun close() {
        MemoryTestEnvironment.release()
    }

    @Test fun aRealQuestionIsVisibleAsPendingAndWithdrawnWhenSourceDeleted() {
        val role = "visibility-question-source-test"
        val original = "visibility-user-question-source"
        CharacterCuriosityRuntime.clear(role)
        val time = Instant.now()
        SharedExperienceTimeline.record(original, role, "私聊", "用户",
            "我好奇这段故事会怎样发展", time,
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val proposal = JSONObject().put("topic", "故事走向")
            .put("question", "故事后面会怎么发展")
            .put("why", "听到用户说起它")
            .put("nextStep", "下次实际阅读正文")
        CharacterCuriosityRuntime.recordInquiry(role, proposal, original,
            "用户提到了故事", time)
        assertTrue(CharacterLifeVisibility.current(role, time.plusSeconds(2))
            .any { it.title.contains("尚未实践的好奇") })
        assertTrue(CharacterLifeVisibility.archive(role, time.plusSeconds(2))
            .any { it.title.contains("尚未行动") && it.evidenceId == original })
        SharedExperienceTimeline.deleteEvent(original)
        assertFalse(CharacterLifeVisibility.current(role, time.plusSeconds(3))
            .any { it.title.contains("尚未实践的好奇") })
        assertFalse(CharacterLifeVisibility.archive(role, time.plusSeconds(3))
            .any { it.evidenceId == original })
        CharacterCuriosityRuntime.clear(role)
    }

    @Test fun unverifiedSharingIsNotReportedAsAnActualPublishedPost() {
        val role = "visibility-share-proof-test"
        val origin = "visibility-reading-experience"
        val time = Instant.now()
        CharacterLifeFollowThroughStore.invalidateEvidence(origin)
        SharedExperienceTimeline.record(origin, role, "独自阅读", "角色",
            "读过一本真实存在的书", time, triggerExtraction = false,
            source = "reading:sample", evidenceKind = EventEvidenceKind.Observation)
        CharacterLifeFollowThroughStore.register(role, origin, "独自阅读",
            "读过一本真实存在的书", time)
        CharacterLifeFollowThroughStore.recordShareWish(role,
            JSONObject().put("experienceId", origin)
                .put("status", "held").put("why", "想分享一个有趣的结尾"))
        val planned = CharacterLifeVisibility.archive(role, time.plusSeconds(2))
            .first { it.evidenceId == origin && it.title == "真实经历的后续变化" }
        assertTrue(planned.detail.contains("还没有发"))
        CharacterLifeFollowThroughStore.recordAction(role, origin, "moment", true,
            "说自己发了朋友圈", "not-an-actual-post")
        assertFalse(CharacterLifeVisibility.archive(role, time.plusSeconds(2))
            .first { it.evidenceId == origin && it.title == "真实经历的后续变化" }
            .detail.contains("确实已经分享"))
        val post = "visibility-genuine-post"
        SharedExperienceTimeline.record(post, role, "朋友圈", "角色",
            "看到有趣的故事了", time.plusSeconds(1), triggerExtraction = false,
            source = "moment:self", evidenceKind = EventEvidenceKind.CharacterStatement)
        CharacterLifeFollowThroughStore.recordAction(role, origin, "moment", true,
            "朋友圈已发布", post)
        assertTrue(CharacterLifeVisibility.archive(role, Instant.now().plusSeconds(5))
            .any { it.evidenceId == origin && it.detail.contains("确实已经分享") })
        SharedExperienceTimeline.deleteEvent(post)
        assertFalse(CharacterLifeVisibility.archive(role, Instant.now().plusSeconds(5))
            .any { it.evidenceId == origin && it.detail.contains("确实已经分享") })
        SharedExperienceTimeline.deleteEvent(origin)
    }
}
