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
    val paper = Color(0xFFFFFFFF)
    val card = Color(0xFFFFFFFF)
    val wheat = Color(0xFFF1CF68)
    val wheatSoft = Color(0xFFFFF7D8)
    val muted = Color(0xFF747474)
    val ink = Color(0xFF292929)
    val border = Color(0xFFE9E3D4)
    // Keep a dark ink token for text/icons and optional focus skins, but normal study surfaces stay white.
    val dark = Color(0xFF2B2B2B)
    val darkCard = Color(0xFFF3F3F3)
    val success = Color(0xFF5E7E65)
    val error = Color(0xFFA55C54)
}

/**
 * The study app uses white as its base, with wheat yellow only as an accent. Material components
 * inherit this scheme so menus, sheets and dialogs do not fall back to black backgrounds/yellow text.
 */
internal val StudyColorScheme = lightColorScheme(
    primary = StudyDesign.wheat,
    onPrimary = StudyDesign.ink,
    primaryContainer = StudyDesign.wheatSoft,
    onPrimaryContainer = StudyDesign.ink,
    secondary = Color(0xFFD6B34E),
    onSecondary = StudyDesign.ink,
    secondaryContainer = StudyDesign.wheatSoft,
    onSecondaryContainer = StudyDesign.ink,
    background = StudyDesign.paper,
    onBackground = StudyDesign.ink,
    surface = StudyDesign.card,
    onSurface = StudyDesign.ink,
    surfaceVariant = Color(0xFFF7F5EF),
    onSurfaceVariant = StudyDesign.muted,
    outline = StudyDesign.border,
    error = StudyDesign.error,
    onError = Color.White,
)

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
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = StudyDesign.ink)
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
                    containerColor = Color.White,
                    labelColor = StudyDesign.muted,
                    selectedContainerColor = Color.White,
                    selectedLabelColor = StudyDesign.ink,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = section == selected,
                    borderColor = StudyDesign.border,
                    selectedBorderColor = StudyDesign.wheat,
                    selectedBorderWidth = 2.dp,
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
        trackColor = Color(0xFFF0EDE5),
    )
}

internal fun Int.minutesLabel(): String = when {
    this < 60 -> "${this}分钟"
    this % 60 == 0 -> "${this / 60}小时"
    else -> "${this / 60}小时${this % 60}分"
}
