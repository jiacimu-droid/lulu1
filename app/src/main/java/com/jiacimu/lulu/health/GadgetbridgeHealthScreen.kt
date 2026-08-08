package com.jiacimu.lulu.health

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WearablePaper = Color(0xFFFFFAFB)
private val WearableCard = Color.White
private val WearableSoft = Color(0xFFFFF0F4)
private val WearableAccent = Color(0xFFB65F79)
private val WearableAccentDeep = Color(0xFF925065)
private val WearableInk = Color(0xFF292225)
private val WearableMuted = Color(0xFF81767A)
private val WearableLine = Color(0xFFECE0E4)
private val SleepLavender = Color(0xFFEDE8FA)
private val SleepPurple = Color(0xFF7765A7)
private val OxygenSoft = Color(0xFFE8F3F7)
private val OxygenBlue = Color(0xFF527F91)
private val ActivitySoft = Color(0xFFEAF4EE)
private val ActivityGreen = Color(0xFF567D66)
private val WarmSoft = Color(0xFFFFF2E6)
private val WarmAccent = Color(0xFF9A6A42)

@Composable
internal fun GadgetbridgeHealthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember(context) {
        GadgetbridgeHealthStore.initialize(context.applicationContext)
        Unit
    }
    val state by GadgetbridgeHealthStore.state.collectAsState()
    var refreshedOnOpen by remember { mutableStateOf(false) }
    var selectedDateText by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.connected, refreshedOnOpen) {
        if (state.connected && !refreshedOnOpen) {
            refreshedOnOpen = true
            scope.launch { GadgetbridgeHealthStore.refresh(context) }
        }
    }

    LaunchedEffect(state.latest?.date, selectedDateText) {
        if (selectedDateText == null) selectedDateText = (state.latest?.date ?: LocalDate.now()).toString()
    }

    val selectedDate = selectedDateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: state.latest?.date
        ?: LocalDate.now()
    val firstAvailableDate = state.days.minOfOrNull { it.date } ?: LocalDate.now().minusDays(60)
    val lastSelectableDate = maxOf(LocalDate.now(), state.latest?.date ?: LocalDate.now())
    val selectedDay = state.days.firstOrNull { it.date == selectedDate }
    val selectedSleep = selectedDay?.takeIf { day ->
        day.sleepMinutes != null || day.sleepStartEpochSeconds != null || day.sleepEndEpochSeconds != null ||
            day.deepSleepMinutes != null || day.lightSleepMinutes != null || day.remSleepMinutes != null ||
            day.awakeSleepMinutes != null || day.sleepScore != null
    }
    val historyThroughSelected = state.days.filter { !it.date.isAfter(selectedDate) }.takeLast(14)

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().background(WearablePaper),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            DateSelectorCard(
                selectedDate = selectedDate,
                firstDate = firstAvailableDate,
                lastDate = lastSelectableDate,
                onDateChange = { selectedDateText = it.toString() },
            )
        }

        if (state.connected) item { SyncStatusRow(state) }

        if (state.error.isNotBlank()) {
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(18.dp)) {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        when {
            !state.connected -> item { DisplayOnlyEmptyCard() }
            state.days.isEmpty() && !state.importing -> item {
                DisplayOnlyEmptyCard("已经完成授权，但数据库里暂时没有可展示的健康数据。请先让手环同步到 Gadgetbridge。")
            }
            selectedDay == null -> item { NoDataForDateCard(selectedDate) }
            else -> {
                item { SleepHeroCard(selectedSleep, selectedDate) }
                if (selectedSleep != null) item { SleepStageCard(selectedSleep) }

                item { SectionTitle("身体概览") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard(
                            title = "步数",
                            value = selectedDay.steps.toString(),
                            unit = "步",
                            icon = Icons.Outlined.DirectionsWalk,
                            accent = ActivityGreen,
                            soft = ActivitySoft,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            title = "平均心率",
                            value = selectedDay.averageHeartRate?.toString() ?: "—",
                            unit = "次/分",
                            icon = Icons.Outlined.FavoriteBorder,
                            accent = WearableAccent,
                            soft = WearableSoft,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard(
                            title = "血氧",
                            value = selectedDay.spo2?.toString() ?: "—",
                            unit = "%",
                            icon = Icons.Outlined.WaterDrop,
                            accent = OxygenBlue,
                            soft = OxygenSoft,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            title = "压力",
                            value = selectedDay.stress?.toString() ?: "—",
                            unit = "",
                            icon = Icons.Outlined.Speed,
                            accent = WarmAccent,
                            soft = WarmSoft,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item { ActivitySummaryCard(selectedDay) }
                item { ExtendedVitalsCard(selectedDay) }

                val heartValues = historyThroughSelected.mapNotNull { day ->
                    day.averageHeartRate?.let { day.date.format(dayFormatter) to it.toFloat() }
                }
                if (heartValues.size >= 2) {
                    item {
                        CompactTrendCard(
                            title = "平均心率趋势",
                            values = heartValues,
                            accent = WearableAccent,
                            valueLabel = { "${it.toInt()} 次/分" },
                        )
                    }
                }

                val oxygenValues = historyThroughSelected.mapNotNull { day ->
                    day.spo2?.let { day.date.format(dayFormatter) to it.toFloat() }
                }
                if (oxygenValues.size >= 2) {
                    item {
                        CompactTrendCard(
                            title = "血氧趋势",
                            values = oxygenValues,
                            accent = OxygenBlue,
                            valueLabel = { "${it.toInt()}%" },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun DateSelectorCard(
    selectedDate: LocalDate,
    firstDate: LocalDate,
    lastDate: LocalDate,
    onDateChange: (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onDateChange(selectedDate.minusDays(1)) }, enabled = selectedDate.isAfter(firstDate)) {
                Icon(Icons.Outlined.ChevronLeft, "前一天", tint = WearableInk)
            }
            TextButton(
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth -> onDateChange(LocalDate.of(year, month + 1, dayOfMonth)) },
                        selectedDate.year,
                        selectedDate.monthValue - 1,
                        selectedDate.dayOfMonth,
                    ).apply {
                        datePicker.minDate = firstDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        datePicker.maxDate = lastDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.show()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = WearableAccentDeep, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (selectedDate == LocalDate.now()) "今天 · ${selectedDate.format(longDateFormatter)}" else selectedDate.format(longDateFormatter),
                        color = WearableInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE),
                        color = WearableMuted,
                        fontSize = 10.sp,
                    )
                }
            }
            IconButton(onClick = { onDateChange(selectedDate.plusDays(1)) }, enabled = selectedDate.isBefore(lastDate)) {
                Icon(Icons.Outlined.ChevronRight, "后一天", tint = WearableInk)
            }
        }
    }
}

@Composable
private fun SyncStatusRow(state: GadgetbridgeHealthState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.importing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = WearableAccent)
        } else {
            Icon(Icons.Outlined.CloudDone, null, tint = ActivityGreen, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            if (state.importing) {
                "正在读取最新手环数据"
            } else {
                val time = state.lastImportedAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                if (time == null) "手环数据已连接" else "已自动同步 · $time"
            },
            color = WearableMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(state.sourceName, color = WearableMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DisplayOnlyEmptyCard(message: String = "手环数据尚未授权。请到“设置 → 权限与能力 → Gadgetbridge 健康数据”选择数据库；这里之后只负责展示。") {
    WearablePanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = WearableSoft, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Outlined.Watch, null, tint = WearableAccent, modifier = Modifier.padding(15.dp).size(35.dp))
            }
            Text("等待手环数据", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text(message, color = WearableMuted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun NoDataForDateCard(date: LocalDate) {
    WearablePanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.EventBusy, null, tint = WearableMuted, modifier = Modifier.size(30.dp))
            Text("${date.format(longDateFormatter)} 暂无数据", color = WearableInk, fontWeight = FontWeight.Bold)
            Text("可以切换到前后日期查看 Gadgetbridge 已导出的记录。", color = WearableMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SleepHeroCard(day: GadgetbridgeDaySummary?, selectedDate: LocalDate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = SleepLavender,
        border = BorderStroke(1.dp, SleepPurple.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFFF4F0FC), Color(0xFFFFF1F5))))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = 0.80f), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Bedtime, null, tint = SleepPurple, modifier = Modifier.padding(11.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("睡眠", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        if (day != null) "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日夜间记录" else "这一天没有睡眠记录",
                        color = WearableMuted,
                        fontSize = 11.sp,
                    )
                }
                day?.sleepScore?.let { score -> SleepScoreBadge(score) }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    day?.sleepMinutes?.let(::formatMinutes) ?: "—",
                    color = WearableInk,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(9.dp))
                Text("实际睡眠", color = WearableMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 7.dp))
            }

            val deepRatio = day?.let { value ->
                val actualSleep = listOfNotNull(value.deepSleepMinutes, value.lightSleepMinutes, value.remSleepMinutes).sum()
                    .takeIf { it > 0 }
                    ?: value.sleepMinutes
                    ?: 0
                value.deepSleepMinutes?.takeIf { actualSleep > 0 }?.let { (it * 100f / actualSleep).toInt() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SleepTimeChip("入睡", day?.sleepStartEpochSeconds?.let(::formatClock) ?: "—", Modifier.weight(1f))
                SleepTimeChip("起床", day?.sleepEndEpochSeconds?.let(::formatClock) ?: "—", Modifier.weight(1f))
                SleepTimeChip("深睡占比", deepRatio?.let { "$it%" } ?: "—", Modifier.weight(1f), highlighted = true)
            }
        }
    }
}

@Composable
private fun SleepScoreBadge(score: Int) {
    Surface(
        modifier = Modifier.width(94.dp).height(82.dp),
        color = Color.White.copy(alpha = 0.88f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, SleepPurple.copy(alpha = 0.15f)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(score.toString(), color = SleepPurple, fontSize = 31.sp, fontWeight = FontWeight.Black)
            Text("睡眠评分", color = WearableMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SleepTimeChip(label: String, value: String, modifier: Modifier, highlighted: Boolean = false) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.80f),
        shape = RoundedCornerShape(16.dp),
        border = if (highlighted) BorderStroke(1.dp, SleepPurple.copy(alpha = 0.22f)) else null,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = WearableMuted, fontSize = 10.sp)
            Text(value, color = if (highlighted) SleepPurple else WearableInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

private data class SleepStage(val name: String, val minutes: Int, val color: Color, val isAwake: Boolean = false)

@Composable
private fun SleepStageCard(day: GadgetbridgeDaySummary) {
    val stages = listOfNotNull(
        day.deepSleepMinutes?.takeIf { it > 0 }?.let { SleepStage("深睡", it, Color(0xFF5D4C8C)) },
        day.lightSleepMinutes?.takeIf { it > 0 }?.let { SleepStage("浅睡", it, Color(0xFF9787C0)) },
        day.remSleepMinutes?.takeIf { it > 0 }?.let { SleepStage("快速眼动", it, Color(0xFFC08EC6)) },
        day.awakeSleepMinutes?.takeIf { it > 0 }?.let { SleepStage("清醒", it, Color(0xFFE99B9E), isAwake = true) },
    )
    WearablePanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("睡眠结构", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("深睡、浅睡、快速眼动与清醒", color = WearableMuted, fontSize = 10.sp)
            }
            Icon(Icons.Outlined.NightlightRound, null, tint = SleepPurple)
        }

        if (stages.isEmpty()) {
            Surface(color = SleepLavender.copy(alpha = 0.55f), shape = RoundedCornerShape(17.dp)) {
                Text(
                    "这一天已经有睡眠总时长，但露露机还没有从数据库里解析出睡眠阶段。Gadgetbridge 本身若能显示深睡/浅睡/REM，说明阶段数据确实存在，应该继续按解析问题处理，而不是当成“没有数据”。",
                    color = WearableMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(14.dp),
                )
            }
        } else {
            val sleepTotal = stages.filterNot(SleepStage::isAwake).sumOf(SleepStage::minutes).coerceAtLeast(1)
            val timelineTotal = stages.sumOf(SleepStage::minutes).coerceAtLeast(1)

            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                stages.forEach { stage ->
                    Box(
                        Modifier
                            .weight(stage.minutes.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .background(stage.color, RoundedCornerShape(8.dp)),
                    )
                }
            }

            stages.forEach { stage ->
                val denominator = if (stage.isAwake) timelineTotal else sleepTotal
                val ratio = (stage.minutes * 100f / denominator).toInt()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFAF8FB),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, stage.color.copy(alpha = 0.16f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(Modifier.size(11.dp), RoundedCornerShape(5.dp), stage.color) {}
                        Spacer(Modifier.width(9.dp))
                        Text(stage.name, color = WearableInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(formatMinutes(stage.minutes), color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        Surface(color = stage.color.copy(alpha = 0.11f), shape = RoundedCornerShape(10.dp)) {
                            Text("$ratio%", color = stage.color, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = WearableInk,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 3.dp, start = 2.dp),
    )
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    accent: Color,
    soft: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = WearableCard,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = soft, shape = RoundedCornerShape(11.dp)) {
                    Icon(icon, null, tint = accent, modifier = Modifier.padding(7.dp).size(18.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(title, color = WearableMuted, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = WearableInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = WearableMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}

private data class DetailMetric(val icon: ImageVector, val title: String, val value: String)

@Composable
private fun ActivitySummaryCard(day: GadgetbridgeDaySummary) {
    val metrics = listOfNotNull(
        day.calories?.let { DetailMetric(Icons.Outlined.LocalFireDepartment, "活动热量", "$it 千卡") },
        day.distanceMeters?.let { DetailMetric(Icons.Outlined.Straighten, "活动距离", formatDistance(it)) },
        day.activeMinutes?.let { DetailMetric(Icons.Outlined.Timer, "活跃时间", "$it 分钟") },
        day.floorsClimbed?.let { DetailMetric(Icons.Outlined.Stairs, "爬楼", "$it 层") },
        day.minimumHeartRate?.let { min -> DetailMetric(Icons.Outlined.MonitorHeart, "心率范围", "$min—${day.maximumHeartRate ?: min}") },
    )
    if (metrics.isEmpty()) return
    WearablePanel {
        Text("活动与心率", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        metrics.chunked(2).forEach { rowMetrics ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowMetrics.forEach { metric -> DetailMetricCell(metric, Modifier.weight(1f)) }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExtendedVitalsCard(day: GadgetbridgeDaySummary) {
    val metrics = listOfNotNull(
        day.restingHeartRate?.let { DetailMetric(Icons.Outlined.FavoriteBorder, "静息心率", "$it 次/分") },
        day.hrvMillis?.let { DetailMetric(Icons.Outlined.GraphicEq, "HRV", "$it ms") },
        day.respiratoryRate?.let { DetailMetric(Icons.Outlined.Air, "呼吸率", "%.1f 次/分".format(Locale.getDefault(), it)) },
        day.skinTemperatureCelsius?.let { DetailMetric(Icons.Outlined.Thermostat, "皮肤温度", "%.1f℃".format(Locale.getDefault(), it)) },
        day.bodyEnergy?.let { DetailMetric(Icons.Outlined.BatteryChargingFull, "身体能量", "$it") },
        if (day.systolicBloodPressure != null || day.diastolicBloodPressure != null) {
            DetailMetric(Icons.Outlined.Bloodtype, "血压", "${day.systolicBloodPressure ?: "—"}/${day.diastolicBloodPressure ?: "—"}")
        } else null,
    )
    if (metrics.isEmpty()) return
    WearablePanel {
        Text("扩展指标", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        metrics.chunked(2).forEach { rowMetrics ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowMetrics.forEach { metric -> DetailMetricCell(metric, Modifier.weight(1f)) }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailMetricCell(metric: DetailMetric, modifier: Modifier) {
    Surface(modifier = modifier, color = Color(0xFFFAF7F8), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(metric.icon, null, tint = WearableAccentDeep, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(metric.title, color = WearableMuted, fontSize = 10.sp)
                Text(metric.value, color = WearableInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CompactTrendCard(
    title: String,
    values: List<Pair<String, Float>>,
    accent: Color,
    valueLabel: (Float) -> String,
) {
    val minimum = values.minOfOrNull { it.second } ?: 0f
    val maximum = values.maxOfOrNull { it.second } ?: 1f
    val range = (maximum - minimum).coerceAtLeast(1f)
    WearablePanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            values.lastOrNull()?.let { Text(valueLabel(it.second), color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Canvas(Modifier.fillMaxWidth().height(104.dp)) {
            if (values.size < 2) return@Canvas
            val points = values.mapIndexed { index, item ->
                val x = index * size.width / (values.size - 1)
                val y = size.height - ((item.second - minimum) / range * size.height * 0.72f + size.height * 0.14f)
                Offset(x, y)
            }
            points.zipWithNext().forEach { (first, second) -> drawLine(accent, first, second, strokeWidth = 3.dp.toPx()) }
            points.forEach { point ->
                drawCircle(Color.White, radius = 5.dp.toPx(), center = point)
                drawCircle(accent, radius = 3.3.dp.toPx(), center = point)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(values.firstOrNull()?.first.orEmpty(), color = WearableMuted, fontSize = 9.sp)
            Text(values.lastOrNull()?.first.orEmpty(), color = WearableMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun WearablePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, WearableLine),
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content,
        )
    }
}

private val dayFormatter = DateTimeFormatter.ofPattern("M/d")
private val longDateFormatter = DateTimeFormatter.ofPattern("M月d日")
private fun formatMinutes(minutes: Int): String = "${minutes / 60}时${minutes % 60}分"
private fun formatDistance(meters: Int): String = if (meters >= 1_000) "%.2f 公里".format(Locale.getDefault(), meters / 1_000f) else "$meters 米"
private fun formatClock(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))
