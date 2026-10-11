package com.jiacimu.lulu.data

import androidx.test.core.app.ApplicationProvider
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
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-10-11T12:00:00Z")

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
        CharacterLifeFollowThroughStore.recordAction(role, id, "moment", true, "已发布朋友圈")
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
        CharacterLifeFollowThroughStore.recordAction(role, id, "reading", true, "正文阅读进度已保存")
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
