package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object StudyDesign {
    val paper = Color(0xFFFFFDF7)
    val card = Color(0xFFFFFBF1)
    val wheat = Color(0xFFF4D57D)
    val wheatSoft = Color(0xFFFFF2C8)
    val muted = Color(0xFF6D7888)
    val ink = Color(0xFF343434)
    val border = Color(0xFFEAE0CC)
    val dark = Color(0xFF272A30)
    val darkCard = Color(0xFF343840)
    val success = Color(0xFF5E7E65)
    val error = Color(0xFFA55C54)
}

internal enum class StudySection(val label: String) {
    Companion("陪伴"), Today("今日"), Plan("计划"), Gacha("抽卡"),
    Collection("收藏"), Achievements("成就"), Shop("商店"), Guide("说明"),
}

@Composable
internal fun StudyCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudyDesign.card),
        border = BorderStroke(1.dp, StudyDesign.border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
internal fun StudyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = StudyDesign.card),
        border = BorderStroke(1.dp, StudyDesign.border),
        shape = RoundedCornerShape(17.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = StudyDesign.muted, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun StudySectionChips(selected: StudySection, onSelect: (StudySection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StudySection.entries.forEach { section ->
            FilterChip(
                selected = section == selected,
                onClick = { onSelect(section) },
                label = { Text(section.label, fontWeight = if (section == selected) FontWeight.Bold else FontWeight.Medium) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = StudyDesign.card,
                    labelColor = StudyDesign.muted,
                    selectedContainerColor = StudyDesign.dark,
                    selectedLabelColor = StudyDesign.wheat,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = section == selected,
                    borderColor = StudyDesign.border,
                    selectedBorderColor = StudyDesign.dark,
                ),
            )
        }
    }
}

@Composable
internal fun StudyMessage(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (error) Color(0xFFF7E6E2) else StudyDesign.wheatSoft,
        shape = RoundedCornerShape(15.dp),
    ) {
        Text(
            text,
            Modifier.padding(13.dp),
            color = if (error) StudyDesign.error else StudyDesign.ink,
        )
    }
}

@Composable
internal fun StudyProgress(progress: Float) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = StudyDesign.wheat,
        trackColor = Color(0xFFF0E8DA),
    )
}

internal fun Int.minutesLabel(): String = when {
    this < 60 -> "${this}分钟"
    this % 60 == 0 -> "${this / 60}小时"
    else -> "${this / 60}小时${this % 60}分"
}
