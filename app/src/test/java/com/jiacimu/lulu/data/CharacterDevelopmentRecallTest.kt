package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CharacterDevelopmentRecallTest {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private fun record(id: String, text: String, days: Long = 0,
                       maturity: DevelopmentMaturity = DevelopmentMaturity.Emerging) = DevelopmentRecord(
        id, "role", id, DevelopmentKind.SituationalPattern, text, 0.7,
        mapOf("real-$id" to 1L), emptyMap(), 1, now.minusSeconds(days * 86_400), maturity = maturity)

    @Test fun relatedOlderExperienceSurvivesManyRecentUnrelatedHabits() {
        val old = record("old", "对方学习疲惫时，先倾听，提供安静陪伴和休息空间", 120)
        val recent = (1..20).map { record("new-$it", "喜欢探索不同的咖啡制作方式和旅行景色$it") }
        val chosen = CharacterDevelopmentRecall.select(listOf(old) + recent, "学习太累了，想休息一会，陪陪我", 5, now)
        assertEquals("old", chosen.first().id)
        assertEquals(5, chosen.size)
    }

    @Test fun establishedHabitsRemainAvailableWithoutAKeywordMatch() {
        val stable = record("stable", "稳定的相处习惯", 90, DevelopmentMaturity.Established)
        val emerging = record("fresh", "一次新的兴趣", 0)
        assertEquals("stable", CharacterDevelopmentRecall.select(listOf(stable, emerging), "", 1, now).single().id)
    }

    @Test fun repeatedWordingAndSupersededSlotsCannotFillTheWorkingSet() {
        val original = record("old", "有分歧时先认真倾听")
        val revised = original.copy(id = "revised", version = 2, content = "有分歧时先确认双方理解")
        val duplicate = record("duplicate", "有分歧时，先确认双方理解！")
        val selected = CharacterDevelopmentRecall.select(listOf(original, revised, duplicate), "分歧", 8, now)
        assertEquals(1, selected.size)
        assertEquals("revised", selected.single().id)
    }

    @Test fun retiredAndFutureRecordsDoNotBecomeCurrentPersonality() {
        val current = record("current", "真实习惯")
        val retired = record("retired", "已经撤销").copy(active = false)
        val future = record("future", "未来记录").copy(createdAt = now.plusSeconds(1))
        assertEquals(listOf(current), CharacterDevelopmentRecall.select(listOf(current, retired, future), "", 8, now))
        assertTrue(CharacterDevelopmentRecall.select(listOf(current), "", 0, now).isEmpty())
    }

    @Test fun selectionDoesNotRewriteStoredEvidenceOrPersonality() {
        val source = listOf(record("a", "自然表达"), record("b", "阅读兴趣", 1))
        val before = source.toList()
        CharacterDevelopmentRecall.select(source, "阅读", 1, now)
        assertEquals(before, source)
        assertEquals(mapOf("real-a" to 1L), source.first().evidence)
    }
}
