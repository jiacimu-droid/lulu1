package com.jiacimu.lulu.data

import org.robolectric.RuntimeEnvironment
import android.content.Context
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CharacterLifeFollowThroughStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    @Test fun verifiedShareAndReadingReceiptsRollBackWhenDeleted() {
        SharedExperienceTimeline.initialize(context)
        CharacterLifeFollowThroughStore.initialize(context)
        val role = "real-share-rollback"
        val source = "world-event-for-share-rollback"
        val sent = "actual-post-for-share-rollback"
        SharedExperienceTimeline.record(source, role, "数字世界", "事件",
            "家里突然出现一个意外的小事件", now, triggerExtraction = false,
            source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        assertTrue(CharacterLifeFollowThroughStore.register(role, source,
            "数字世界", "家里的小事件", now))
        SharedExperienceTimeline.record(sent, role, "朋友圈", "角色",
            "刚才家里发生一件趣事", now.plusSeconds(12), triggerExtraction = false,
            source = "moment:self", evidenceKind = EventEvidenceKind.CharacterStatement)
        CharacterLifeFollowThroughStore.recordAction(role, source, "moment", true, "已发动态", sent)
        assertTrue(CharacterLifeFollowThroughStore.context(role, now.plusSeconds(20)).contains("已分享"))
        SharedExperienceTimeline.deleteEvent(sent)
        assertFalse(CharacterLifeFollowThroughStore.context(role, now.plusSeconds(20)).contains("已分享，勿重复"))
        val cue = CharacterLifeFollowThroughStore.pendingDecisionCues(role, now.plusSeconds(20))
        assertTrue(cue.isEmpty() || cue.all { !it.contains("已发动态") })
        SharedExperienceTimeline.deleteEvent(source)
        assertFalse(CharacterLifeFollowThroughStore.hasOpenOpportunity(role, source))
    }

    @Test fun aModelCannotMarkAnExperienceSharedWithoutAnActualPostReceipt() {
        SharedExperienceTimeline.initialize(context)
        CharacterLifeFollowThroughStore.initialize(context)
        val role = "fake-post-prevented"
        val source = "fake-post-source"
        CharacterLifeFollowThroughStore.invalidateEvidence(source)
        CharacterLifeFollowThroughStore.register(role, source, "独自阅读", "读完了一段文字", now)
        CharacterLifeFollowThroughStore.recordAction(role, source, "moment", true, "模型说已发布", "")
        assertFalse(CharacterLifeFollowThroughStore.context(role, now).contains("已分享，勿重复"))
        CharacterLifeFollowThroughStore.invalidateEvidence(source)
    }

    @Test fun livedRoachCanRemainBothAnUnfinishedCuriosityAndAnUnpostedStory() {
        CharacterLifeFollowThroughStore.initialize(context)
        val role = "roach-story-thread"
        val id = "world-fact-roach-test-role"
        CharacterLifeFollowThroughStore.invalidateEvidence(id)
        assertTrue(CharacterLifeFollowThroughStore.register(
            role, id, "数字世界事件·自己的家", "一只蟑螂从门缝里进来，仍藏在房间", now,
        ))
        assertFalse(CharacterLifeFollowThroughStore.register(
            role, id, "数字世界事件·自己的家", "同一只蟑螂", now,
        ))
        val first = CharacterLifeFollowThroughStore.context(role, now)
        assertTrue(first.contains("蟑螂"))
        assertTrue(first.contains(id))
        assertTrue(first.contains("朋友圈"))

        CharacterLifeFollowThroughStore.recordShareWish(role, JSONObject()
            .put("experienceId", id).put("status", "held")
            .put("why", "有点被吓到，又想让她知道这件趣事"))
        val wished = CharacterLifeFollowThroughStore.context(role, now)
        assertTrue(wished.contains("有点被吓到"))
        CharacterLifeFollowThroughStore.recordAction(role, id, "moment", false, "发布失败")
        assertTrue(CharacterLifeFollowThroughStore.context(role, now).contains("还没发出去"))
        SharedExperienceTimeline.initialize(context)
        SharedExperienceTimeline.record("roach-test-post", role, "朋友圈", "角色", "吓了一跳",
            now.plusSeconds(30), triggerExtraction = false, source = "moment:self",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        CharacterLifeFollowThroughStore.recordAction(role, id, "moment", true, "已发布朋友圈",
            "roach-test-post")
        val shared = CharacterLifeFollowThroughStore.context(role, now)
        assertTrue(shared.contains("已分享"))
        assertTrue(shared.contains("探索"))
        // Sharing didn't magically drive out the persistent roach.
        assertTrue(shared.contains("蟑螂"))
        CharacterLifeFollowThroughStore.invalidateEvidence(id)
        assertFalse(CharacterLifeFollowThroughStore.context(role, now).contains(id))
    }

    @Test fun actualReadingCanAdvanceCuriosityWithoutObligatoryPublicPosting() {
        CharacterLifeFollowThroughStore.initialize(context)
        val role = "reader-life-thread"
        val id = "reading-receipt-chapter-7"
        CharacterLifeFollowThroughStore.invalidateEvidence(id)
        CharacterLifeFollowThroughStore.register(role, id, "独自阅读《薄荷》",
            "程序确认已读到书里一段对话", now)
        CharacterLifeFollowThroughStore.recordAction(role, id, "reading", false, "没有找到正文")
        assertTrue(CharacterLifeFollowThroughStore.context(role, now).contains("可以"))
        SharedExperienceTimeline.initialize(context)
        // A ToolResult must contain a real, successful executor envelope.
        // Free-form text such as "已读下一段" is not proof that the book was read.
        SharedExperienceTimeline.record("reader-unverified-result", role, "实际工具执行", "执行器",
            "已读下一段", now.plusSeconds(20), triggerExtraction = false,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        CharacterLifeFollowThroughStore.recordAction(role, id, "reading", true,
            "仅仅声称阅读成功", "reader-unverified-result")
        assertFalse(CharacterLifeFollowThroughStore.context(role, now).contains("已有真实行动回执"))

        val executed = JSONObject().put("characterId", role)
            .put("tool", "read_book").put("status", "succeeded")
            .put("result", JSONObject().put("success", true)
                .put("summary", "阅读进度已提交").toString())
        SharedExperienceTimeline.record("reader-test-receipt", role, "实际工具执行", "执行器",
            executed.toString(), now.plusSeconds(35), triggerExtraction = false,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        CharacterLifeFollowThroughStore.recordAction(role, id, "reading", true,
            "正文阅读进度已保存", "reader-test-receipt")
        val after = CharacterLifeFollowThroughStore.context(role, now)
        assertTrue(after.contains("已有真实行动回执"))
        assertTrue(after.contains("分享"))
        assertFalse(after.contains("已分享，勿重复"))
        CharacterLifeFollowThroughStore.invalidateEvidence(id)
    }

    @Test fun decliningToPostDoesNotEraseKnowledgeAndDoesNotReenablePublicSharing() {
        CharacterLifeFollowThroughStore.initialize(context)
        val role = "private-life-declined"
        val id = "real-experience-dont-share"
        CharacterLifeFollowThroughStore.invalidateEvidence(id)
        CharacterLifeFollowThroughStore.register(role, id, "独自游戏",
            "输掉了一场真实对局", now)
        CharacterLifeFollowThroughStore.recordShareWish(role, JSONObject()
            .put("experienceId", id).put("status", "released")
            .put("why", "不想让别人知道这个成绩"))
        val text = CharacterLifeFollowThroughStore.context(role, now)
        assertTrue(text.contains("已经决定不分享"))
        assertTrue(text.contains("输掉了一场真实对局"))
        CharacterLifeFollowThroughStore.invalidateEvidence(id)
    }
}
