package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MemoryContextPolicyTest {
    private fun memory(id: String, content: String) = MemoryEntry(id, "role", content, MemoryKind.Fact,
        "手动", null, Instant.parse("2026-09-01T00:00:00Z"), 5, false, true)

    @Test fun manyShortMemoriesFitAndAnOversizedEntryDoesNotEvictFollowingOnes() {
        val short = (1..40).map { memory("$it", "喜欢食物$it") }
        val selected = selectMemoryWithinBudget(listOf(memory("long", "长".repeat(12_000))) + short, 5_000)
        assertEquals(short, selected)
    }

    @Test fun coreAndPinnedAreIndependentOfRetrievalStrength() {
        assertTrue(memory("core", "明确边界").copy(tier = MemoryTier.Core).isCoreMemory())
        assertTrue(memory("pin", "固定内容").copy(pinned = true).isCoreMemory())
        assertFalse(memory("strong", "普通喜好").copy(strength = 10).isCoreMemory())
    }

    @Test fun casualPreferencesUseSingleBatchedExtractorButExplicitRequestsStayUrgent() {
        assertFalse(requestsImmediateMemory("今天发现自己特别爱吃火锅"))
        assertFalse(requestsImmediateMemory("我喜欢鸭子，也喜欢小兔子"))
        assertFalse(requestsImmediateMemory("不是很喜欢这个口味"))
        assertTrue(requestsImmediateMemory("记住我过敏，千万别忘"))
        assertTrue(requestsImmediateMemory("以后不要再这样称呼我"))
    }

    @Test fun eachCharactersAuthoredMemoryLensIsBriefAndNotAnEventSource() {
        val first = renderMemorySelectionPerspective(
            "喜欢观星的角色", listOf("兴趣" to "星星、夜空", "珍视的事" to "自由"), "朋友关系",
        )
        val second = renderMemorySelectionPerspective(
            "喜欢做饭的角色", listOf("兴趣" to "热汤、料理"), "闺蜜关系",
        )
        assertTrue(first.contains("星星、夜空"))
        assertTrue(first.contains("朋友关系"))
        assertFalse(first.contains("热汤、料理"))
        assertTrue(second.contains("热汤、料理"))
        assertFalse(second.contains("观星"))
        assertTrue(first.contains("不是事件证据"))
        assertTrue(first.contains("不把上面的人设或关系描述当作某件事发生过"))
        assertFalse(first.contains("sourceEventIds"))
    }

    @Test fun subjectiveMemoriesRequireRoleAuthoredStatementNotOtherMeetingGuest() {
        val at = Instant.parse("2026-11-09T10:00:00Z")
        val self = SharedTimelineEvent("self", "role", "独自日常", "角色",
            "我觉得很开心", at, source = "own",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val other = self.copy(id = "other", speaker = "另一位居民", source = "meeting")
        val user = self.copy(id = "user", speaker = "主人",
            evidenceKind = EventEvidenceKind.UserStatement)
        val presence = self.copy(id = "present", source = "presence",
            evidenceKind = EventEvidenceKind.Inference)
        assertTrue(self.isOwnSubjectiveMemorySource())
        assertFalse(other.isOwnSubjectiveMemorySource())
        assertFalse(user.isOwnSubjectiveMemorySource())
        assertTrue(presence.isOwnSubjectiveMemorySource())
        assertFalse(presence.copy(source = "unverified-guess").isOwnSubjectiveMemorySource())
    }

    @Test fun idleFlushUsesElapsedTimeAndNeverFlushesAFutureTimestamp() {
        val now = Instant.parse("2026-10-08T12:00:00Z")
        assertTrue(shouldFlushMemoryTail(now.minusSeconds(600), now))
        assertFalse(shouldFlushMemoryTail(now.minusSeconds(599), now))
        assertFalse(shouldFlushMemoryTail(now.plusSeconds(1), now))
    }

    @Test fun currentQuestionSurvivesLongRecentContext() {
        val query = RelevantMemoryRecall.focusQuery("当前输入：晚饭吃什么\n当前场景：准备晚餐\n近期上下文：" + "旧闲聊".repeat(2_000))
        assertTrue(query.contains("晚饭吃什么"))
        assertTrue(query.contains("准备晚餐"))
        assertTrue(query.length <= 1_800)
    }

    @Test fun lastMonthUsesCalendarRangeAcrossYearBoundary() {
        val range = memoryTimeRange("上个月我们聊过什么", Instant.parse("2027-01-08T12:00:00Z"), ZoneId.of("Asia/Shanghai"))!!
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"), range.first)
        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), range.second)
    }

    @Test fun currentTimeQuestionWinsOverAnOlderDateInChatHistory() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val range = memoryTimeRange("近期上下文：上个月聊过考试\n当前输入：昨天我们聊了什么", now, ZoneId.of("Asia/Shanghai"))!!
        assertEquals(Instant.parse("2026-10-10T16:00:00Z"), range.first)
        val lastWeek = memoryTimeRange("上周", now, ZoneId.of("Asia/Shanghai"))!!
        assertEquals(Instant.parse("2026-10-04T16:00:00Z"), lastWeek.first)
        assertEquals(Instant.parse("2026-10-11T16:00:00Z"), lastWeek.second)
    }

    @Test fun anActionCueSurvivesGenericInstructionsAndLongHistory() {
        val query = RelevantMemoryRecall.focusQuery("当前输入：有点累\n拟采取行动：讲笑话逗她开心\n任务意图：" + "通用规则".repeat(500))
        assertTrue(query.contains("讲笑话逗她开心"))
        assertTrue(query.contains("有点累"))
        assertFalse(query.contains("通用规则"))
        assertEquals(setOf("橘猫"), memorySearchTerms("当前输入：橘猫"))
    }
}
