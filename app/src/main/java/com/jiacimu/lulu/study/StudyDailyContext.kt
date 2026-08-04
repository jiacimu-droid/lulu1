package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class StudyDailyMetrics(
    val studyMinutes: Int,
    val pomodoros: Int,
    val vocabulary: Int,
    val completedTasks: Int,
    val totalTasks: Int,
)

internal fun StudyState.dailyMetrics(date: LocalDate = LocalDate.now()): StudyDailyMetrics {
    val dateText = date.toString()
    val zone = ZoneId.systemDefault()
    val dayEvents = events.filter { it.createdAt.atZone(zone).toLocalDate() == date }
    val persistedMinutes = dailyStudyMinutes[dateText] ?: 0
    val eventMinutes = dayEvents
        .filter { it.title == "番茄钟完成" }
        .sumOf { Regex("(\\d+)\\s*分钟").find(it.detail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 }
    val persistedPomodoros = dailyPomodoros[dateText] ?: 0
    val eventPomodoros = dayEvents.count { it.title == "番茄钟完成" }
    val persistedVocabulary = dailyVocabularyReviewed[dateText] ?: 0
    val eventVocabulary = dayEvents
        .filter { it.title == "词汇复习" }
        .sumOf { Regex("(\\d+)\\s*个词").find(it.detail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0 }
    val dayTasks = tasks.filter { it.date == dateText }
    return StudyDailyMetrics(
        studyMinutes = maxOf(persistedMinutes, eventMinutes),
        pomodoros = maxOf(persistedPomodoros, eventPomodoros),
        vocabulary = maxOf(persistedVocabulary, eventVocabulary),
        completedTasks = dayTasks.count { it.completed },
        totalTasks = dayTasks.size,
    )
}

internal fun StudyState.roleStudyContext(date: LocalDate = LocalDate.now()): String {
    val daily = dailyMetrics(date)
    return buildString {
        appendLine("今日学习分钟：${daily.studyMinutes}")
        appendLine("今日番茄钟：${daily.pomodoros}")
        appendLine("今日词汇复习：${daily.vocabulary}")
        appendLine("今日完成任务：${daily.completedTasks}/${daily.totalTasks}")
        appendLine("累计学习分钟：${profile.totalStudyMinutes}")
        appendLine("累计番茄钟：${profile.totalPomodoros}")
    }.trim()
}

internal fun StudyState.weekStudyMinutes(date: LocalDate = LocalDate.now()): Int {
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return generateSequence(monday) { day -> day.plusDays(1) }
        .takeWhile { day -> !day.isAfter(date) }
        .sumOf { day -> dailyMetrics(day).studyMinutes }
}

@Composable
internal fun StudyDailySummaryStrip(state: StudyState) {
    val daily = state.dailyMetrics()
    val weeklyMinutes = state.weekStudyMinutes()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = Color.Transparent,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudyMetric("今日学习", daily.studyMinutes.minutesLabel(), Modifier.weight(1f))
            StudyMetric("本周学习", weeklyMinutes.minutesLabel(), Modifier.weight(1f))
        }
    }
}
