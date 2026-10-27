package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPatternNotesTest {

    private val now = Instant.parse("2026-10-28T12:00:00Z")

    private fun daysAgo(days: Long) = now.minusSeconds(days * 86_400)

    private fun entry(
        id: String,
        subject: String,
        slot: String,
        kind: String,
        value: Double?,
        unit: String,
        occurredAt: Instant,
        sceneKey: String = "",
    ): MemoryEntry = MemoryEntry(
        id = id,
        characterId = "lulu",
        content = "测试记忆",
        kind = MemoryKind.Fact,
        source = "test",
        occurredAt = occurredAt,
        createdAt = occurredAt,
        strength = 5,
        pinned = false,
        canRecallProactively = true,
        tier = MemoryTier.Stable,
        subject = subject,
        slot = slot,
        sceneKey = sceneKey,
        measureKind = kind,
        measureValue = value,
        measureUnit = unit,
    )

    @Test
    fun againNoteForSinglePriorRecord() {
        val prior = entry("m1", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(1))
        val note = MemoryPatternNotes.build(listOf(prior), listOf(prior), "lulu", now)
        assertTrue(note.contains("共有1次"))
        assertTrue(note.contains("7小时"))
        assertTrue(note.contains("昨天"))
    }

    @Test
    fun habitNoteForThreeInFourteenDays() {
        val entries = listOf(
            entry("m1", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(1)),
            entry("m2", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(2)),
            entry("m3", "睡眠", "睡眠时长", "sleep_hours", 6.5, "小时", daysAgo(4)),
        )
        val note = MemoryPatternNotes.build(listOf(entries[0]), entries, "lulu", now)
        assertTrue(note.contains("最近14天在3天记录3次"))
        assertTrue(note.contains("6.5小时"))
    }

    @Test
    fun bedtimeAcrossMidnightIsNotDeviation() {
        val entries = listOf(
            entry("m1", "睡眠", "入睡时间", "bedtime", 0.2, "时", daysAgo(1)),
            entry("m2", "睡眠", "入睡时间", "bedtime", 23.8, "时", daysAgo(2)),
            entry("m3", "睡眠", "入睡时间", "bedtime", 23.6, "时", daysAgo(3)),
        )
        val note = MemoryPatternNotes.build(listOf(entries[0]), entries, "lulu", now)
        assertTrue(note.contains("最近14天内出现3次"))
        assertFalse(note.contains("与之前不同"))
    }

    @Test
    fun deviantLatestAddsBreakClause() {
        val entries = listOf(
            entry("m1", "睡眠", "睡眠时长", "sleep_hours", 4.5, "小时", daysAgo(1)),
            entry("m2", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(2)),
            entry("m3", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(3)),
        )
        val note = MemoryPatternNotes.build(listOf(entries[0]), entries, "lulu", now)
        assertTrue(note.contains("与之前不同"))
        assertTrue(note.contains("4.5小时"))
    }

    @Test
    fun noMeasureMeansNoNote() {
        val plain = entry("m1", "睡眠", "睡眠时长", "", null, "", daysAgo(1))
        assertEquals("", MemoryPatternNotes.build(listOf(plain), listOf(plain), "lulu", now))
    }

    @Test
    fun sceneKeyExpandsAssociations() {
        val seed = entry("s1", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(1), sceneKey = "睡觉|卧室|自己|夜里")
        val sameScene = entry("s2", "熬夜", "熬夜原因", "", null, "", daysAgo(2), sceneKey = "睡觉|卧室|自己|夜里")
        val hits = MemoryAssociationPolicy.expand(listOf(seed, sameScene), listOf(seed))
        assertTrue(hits.any { it.memory.id == "s2" && it.reason == "同一场景" })
    }

    @Test
    fun futureTimestampCannotBeCountedAsRecentEvidence() {
        val old = entry("old", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(2))
        val future = entry("future", "睡眠", "睡眠时长", "sleep_hours", 99.0,
            "小时", now.plusSeconds(7200))
        val note = MemoryPatternNotes.build(listOf(old), listOf(old, future), "lulu", now)
        assertTrue(note.contains("共有1次"))
        assertFalse(note.contains("99"))
        assertFalse(note.contains("最近14天"))
    }

    @Test
    fun multipleRecordsFromSameDayDoNotPretendToBeCrossDayHabit() {
        val sameDay = (1..3).map { i ->
            entry("same-$i", "睡眠", "入睡时间", "bedtime",
                23.0, "时", now.minusSeconds(i * 3600L))
        }
        val note = MemoryPatternNotes.build(sameDay.take(1), sameDay, "lulu", now)
        assertTrue(note.contains("最近14天记录3次（共1天）"))
        assertFalse(note.contains("在3天"))
    }

    @Test
    fun extractionDuplicatesWithSameSourceCountOnlyOnce() {
        val original = entry("a", "睡眠", "睡眠时长", "sleep_hours", 7.0,
            "小时", daysAgo(1)).copy(source = "timeline-events:observation-1")
        val reExtracted = original.copy(id = "b", createdAt = daysAgo(1).plusSeconds(10))
        val different = original.copy(id = "c", source = "timeline-events:observation-2")
        val unique = MemoryPatternNotes.dedupeSameEvidence(listOf(original, reExtracted, different))
        assertEquals(2, unique.size)
        assertEquals(1, unique.count { it.sourceIds() == listOf("observation-1") })
    }

    @Test
    fun differentUnitsNeverShareStatisticsOrDeviationThreshold() {
        val hour1 = entry("h1", "睡眠", "睡眠时长", "sleep_hours", 7.0,
            "小时", daysAgo(1))
        val hour2 = entry("h2", "睡眠", "睡眠时长", "sleep_hours", 8.0,
            "小时", daysAgo(2))
        val minutes = entry("m1", "睡眠", "睡眠时长", "sleep_hours", 420.0,
            "分钟", daysAgo(3))
        val note = MemoryPatternNotes.build(listOf(hour1), listOf(hour1, hour2, minutes),
            "lulu", now)
        assertTrue(note.contains("共有2次"))
        assertFalse(note.contains("420"))
    }

    @Test
    fun weeklyPatternRequiresSeparateCalendarWeeks() {
        val sameWeekday = listOf(7L, 14L, 21L).mapIndexed { i, days ->
            entry("w$i", "运动", "跑步距离", "run_km", 2.0,
                "公里", daysAgo(days))
        }
        val weekly = MemoryPatternNotes.build(sameWeekday.take(1), sameWeekday, "lulu", now)
        assertTrue(weekly.contains("3个不同周"))
        val sameDay = (1..3).map { i ->
            entry("d$i", "运动", "跑步距离", "run_km", 2.0,
                "公里", daysAgo(21).minusSeconds(i * 60L))
        }
        val notWeekly = MemoryPatternNotes.build(sameDay.take(1), sameDay, "lulu", now)
        assertFalse(notWeekly.contains("不同周"))
    }

    @Test
    fun unknownUnitsCannotClaimMeaningfulDeviation() {
        val measurements = listOf(2.0, 1.0, 1.0).mapIndexed { i, value ->
            entry("t$i", "步行", "步行距离", "distance", value,
                "公里", daysAgo(i + 1L))
        }
        val note = MemoryPatternNotes.build(measurements.take(1), measurements, "lulu", now)
        assertFalse(note.contains("最新这条与之前不同"))
    }

    @Test
    fun noteIsAtMostOneLine() {
        val sleep = entry("m1", "睡眠", "睡眠时长", "sleep_hours", 7.0, "小时", daysAgo(1))
        val run = entry("m2", "跑步", "跑步距离", "run_km", 5.0, "公里", daysAgo(1))
        val note = MemoryPatternNotes.build(listOf(sleep, run), listOf(sleep, run), "lulu", now)
        assertFalse(note.contains("\n"))
    }
}
