package com.jiacimu.lulu.health

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    LaunchedEffect(state.connected, refreshedOnOpen) {
        if (state.connected && !refreshedOnOpen) {
            refreshedOnOpen = true
            scope.launch { GadgetbridgeHealthStore.refresh(context) }
        }
    }

    val latest = state.latest
    val latestSleep = state.days.lastOrNull { day ->
        day.sleepMinutes != null || day.sleepStartEpochSeconds != null || day.deepSleepMinutes != null
    }
    val recent14 = state.days.takeLast(14)
    val recent7 = state.days.takeLast(7)

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.connected) {
            item { SyncStatusRow(state) }
        }

        if (state.error.isNotBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
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
            latest == null && !state.importing -> item {
                DisplayOnlyEmptyCard("已经完成授权，但数据库里暂时没有可展示的健康数据。请先让手环同步到 Gadgetbridge。")
            }
            latest != null -> {
                item { SleepHeroCard(latestSleep) }
                if (latestSleep != null) {
                    item { SleepStageCard(latestSleep) }
                }

                val sleepDurationValues = recent14.mapNotNull { day ->
                    day.sleepMinutes?.takeIf { it > 0 }?.let { day.date.format(dayFormatter) to it.toFloat() }
                }
                if (sleepDurationValues.size >= 2) {
                    item {
                        BarChartCard(
                            title = "睡眠时长变化",
                            subtitle = "近 ${sleepDurationValues.size} 次记录",
                            values = sleepDurationValues,
                            valueLabel = { formatMinutes(it.toInt()) },
                            accent = SleepPurple,
                        )
                    }
                }

                val bedtimeValues = recent14.mapNotNull { day ->
                    day.sleepStartEpochSeconds?.let { epoch ->
                        day.date.format(dayFormatter) to bedtimeAxisMinutes(epoch)
                    }
                }
                if (bedtimeValues.size >= 2) {
                    item {
                        LineChartCard(
                            title = "入睡时间变化",
                            subtitle = "越靠上代表睡得越晚",
                            values = bedtimeValues,
                            accent = SleepPurple,
                            valueLabel = ::formatClockAxis,
                        )
                    }
                }

                item { SectionTitle("今日身体概览", "基础指标集中展示，不再铺成长长的历史列表") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard(
                            title = "步数",
                            value = latest.steps.toString(),
                            unit = "步",
                            icon = Icons.Outlined.DirectionsWalk,
                            accent = ActivityGreen,
                            soft = ActivitySoft,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            title = "平均心率",
                            value = latest.averageHeartRate?.toString() ?: "—",
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
                            value = latest.spo2?.toString() ?: "—",
                            unit = "%",
                            icon = Icons.Outlined.WaterDrop,
                            accent = OxygenBlue,
                            soft = OxygenSoft,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard(
                            title = "压力",
                            value = latest.stress?.toString() ?: "—",
                            unit = "",
                            icon = Icons.Outlined.Speed,
                            accent = WarmAccent,
                            soft = WarmSoft,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item { ActivitySummaryCard(latest) }
                item { ExtendedHealthCard(latest) }

                val heartValues = recent14.mapNotNull { day ->
                    day.averageHeartRate?.let { day.date.format(dayFormatter) to it.toFloat() }
                }
                if (heartValues.size >= 2) {
                    item {
                        LineChartCard(
                            title = "平均心率趋势",
                            subtitle = "近 ${heartValues.size} 次记录",
                            values = heartValues,
                            accent = WearableAccent,
                            valueLabel = { "${it.toInt()} 次/分" },
                        )
                    }
                }

                val oxygenValues = recent14.mapNotNull { day ->
                    day.spo2?.let { day.date.format(dayFormatter) to it.toFloat() }
                }
                if (oxygenValues.size >= 2) {
                    item {
                        LineChartCard(
                            title = "血氧趋势",
                            subtitle = "近 ${oxygenValues.size} 次记录",
                            values = oxygenValues,
                            accent = OxygenBlue,
                            valueLabel = { "${it.toInt()}%" },
                        )
                    }
                }

                if (recent7.isNotEmpty()) {
                    item { CompactWeekCard(recent7.reversed()) }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
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
                val time = state.lastImportedAt?.atZone(ZoneId.systemDefault())
                    ?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = WearableSoft, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Outlined.Watch, null, tint = WearableAccent, modifier = Modifier.padding(15.dp).size(35.dp))
            }
            Text("等待手环数据", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text(message, color = WearableMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SleepHeroCard(day: GadgetbridgeDaySummary?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = SleepLavender,
        border = BorderStroke(1.dp, SleepPurple.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFFF4F0FC), Color(0xFFFFF1F5))))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = 0.72f), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Bedtime, null, tint = SleepPurple, modifier = Modifier.padding(11.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("睡眠", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(
                        day?.date?.let { "${it.monthValue}月${it.dayOfMonth}日夜间记录" } ?: "暂时没有睡眠记录",
                        color = WearableMuted,
                        fontSize = 11.sp,
                    )
                }
                day?.sleepScore?.let { score ->
                    Surface(color = Color.White.copy(alpha = 0.68f), shape = RoundedCornerShape(14.dp)) {
                        Text("评分 $score", color = SleepPurple, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }

            Text(
                day?.sleepMinutes?.let(::formatMinutes) ?: "—",
                color = WearableInk,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SleepTimeChip(
                    label = "入睡",
                    value = day?.sleepStartEpochSeconds?.let(::formatClock) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                SleepTimeChip(
                    label = "起床",
                    value = day?.sleepEndEpochSeconds?.let(::formatClock) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                val deepRatio = day?.let { value ->
                    val total = value.sleepMinutes ?: 0
                    value.deepSleepMinutes?.takeIf { total > 0 }?.let { it * 100 / total }
                }
                SleepTimeChip(
                    label = "深睡占比",
                    value = deepRatio?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SleepTimeChip(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.72f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = WearableMuted, fontSize = 10.sp)
            Text(value, color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SleepStageCard(day: GadgetbridgeDaySummary) {
    val stages = listOfNotNull(
        day.deepSleepMinutes?.let { SleepStage("深睡", it, Color(0xFF66558F)) },
        day.lightSleepMinutes?.let { SleepStage("浅睡", it, Color(0xFF9B8BC3)) },
        day.remSleepMinutes?.let { SleepStage("快速眼动", it, Color(0xFFC09BC7)) },
        day.awakeSleepMinutes?.let { SleepStage("清醒", it, Color(0xFFE5B4B8)) },
    ).filter { it.minutes > 0 }
    WearablePanel {
        Text("睡眠结构", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (stages.isEmpty()) {
            Text(
                "当前数据库只提供了睡眠总时长或起止时间，没有可可靠识别的深睡、浅睡与快速眼动分段。",
                color = WearableMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        } else {
            val total = stages.sumOf(SleepStage::minutes).coerceAtLeast(1)
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                var x = 0f
                stages.forEach { stage ->
                    val width = size.width * stage.minutes / total.toFloat()
                    drawRoundRect(
                        color = stage.color,
                        topLeft = Offset(x, 0f),
                        size = Size(width.coerceAtLeast(2.dp.toPx()), size.height),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    x += width
                }
            }
            stages.chunked(2).forEach { rowStages ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowStages.forEach { stage ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Surface(modifier = Modifier.size(9.dp), color = stage.color, shape = RoundedCornerShape(4.dp)) {}
                            Spacer(Modifier.width(6.dp))
                            Text(stage.name, color = WearableMuted, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(formatMinutes(stage.minutes), color = WearableInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (rowStages.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private data class SleepStage(val name: String, val minutes: Int, val color: Color)

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 3.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = WearableInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = WearableMuted, fontSize = 11.sp)
    }
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
private fun ExtendedHealthCard(day: GadgetbridgeDaySummary) {
    val metrics = listOfNotNull(
        day.restingHeartRate?.let { DetailMetric(Icons.Outlined.FavoriteBorder, "静息心率", "$it 次/分") },
        day.hrvMillis?.let { DetailMetric(Icons.Outlined.ShowChart, "心率变异性", "$it ms") },
        day.respiratoryRate?.let { DetailMetric(Icons.Outlined.Air, "呼吸频率", "${formatDecimal(it)} 次/分") },
        day.skinTemperatureCelsius?.let { DetailMetric(Icons.Outlined.Thermostat, "皮肤温度", "${formatDecimal(it)}℃") },
        day.bodyEnergy?.let { DetailMetric(Icons.Outlined.BatteryChargingFull, "身体能量", "$it") },
        day.systolicBloodPressure?.let { systolic ->
            day.diastolicBloodPressure?.let { diastolic -> DetailMetric(Icons.Outlined.MonitorHeart, "血压", "$systolic/$diastolic") }
        },
        day.sleepScore?.let { DetailMetric(Icons.Outlined.StarOutline, "睡眠评分", "$it") },
    )
    WearablePanel {
        Text("更多可读取指标", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (metrics.isEmpty()) {
            Text("当前数据库没有提供额外指标；有数据时会自动出现在这里。", color = WearableMuted, fontSize = 12.sp)
        } else {
            metrics.chunked(2).forEach { rowMetrics ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowMetrics.forEach { metric -> DetailMetricCell(metric, Modifier.weight(1f)) }
                    if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private data class DetailMetric(val icon: ImageVector, val title: String, val value: String)

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
private fun BarChartCard(
    title: String,
    subtitle: String,
    values: List<Pair<String, Float>>,
    valueLabel: (Float) -> String,
    accent: Color,
) {
    val maximum = values.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
    WearablePanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(subtitle, color = WearableMuted, fontSize = 10.sp)
            }
            values.lastOrNull()?.let { Text(valueLabel(it.second), color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Canvas(Modifier.fillMaxWidth().height(126.dp)) {
            if (values.isEmpty()) return@Canvas
            val gap = 5.dp.toPx()
            val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(3.dp.toPx())
            values.forEachIndexed { index, item ->
                val height = (item.second / maximum * size.height * 0.9f).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = accent.copy(alpha = 0.78f),
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }
        ChartEdgeLabels(values)
    }
}

@Composable
private fun LineChartCard(
    title: String,
    subtitle: String,
    values: List<Pair<String, Float>>,
    accent: Color,
    valueLabel: (Float) -> String,
) {
    val minimum = values.minOfOrNull { it.second } ?: 0f
    val maximum = values.maxOfOrNull { it.second } ?: 1f
    val range = (maximum - minimum).coerceAtLeast(1f)
    WearablePanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(subtitle, color = WearableMuted, fontSize = 10.sp)
            }
            values.lastOrNull()?.let { Text(valueLabel(it.second), color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Canvas(Modifier.fillMaxWidth().height(126.dp)) {
            if (values.size < 2) return@Canvas
            val points = values.mapIndexed { index, item ->
                val x = index * size.width / (values.size - 1)
                val y = size.height - ((item.second - minimum) / range * size.height * 0.78f + size.height * 0.11f)
                Offset(x, y)
            }
            points.zipWithNext().forEach { (first, second) ->
                drawLine(accent, first, second, strokeWidth = 3.dp.toPx())
            }
            points.forEach { point ->
                drawCircle(Color.White, radius = 5.dp.toPx(), center = point)
                drawCircle(accent, radius = 3.5.dp.toPx(), center = point)
            }
        }
        ChartEdgeLabels(values)
    }
}

@Composable
private fun ChartEdgeLabels(values: List<Pair<String, Float>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(values.firstOrNull()?.first.orEmpty(), color = WearableMuted, fontSize = 9.sp)
        if (values.size > 2) Text(values[values.size / 2].first, color = WearableMuted, fontSize = 9.sp)
        Text(values.lastOrNull()?.first.orEmpty(), color = WearableMuted, fontSize = 9.sp)
    }
}

@Composable
private fun CompactWeekCard(days: List<GadgetbridgeDaySummary>) {
    WearablePanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("近7天概览", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Text("睡眠 · 心率 · 步数", color = WearableMuted, fontSize = 10.sp)
        }
        days.forEachIndexed { index, day ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(52.dp)) {
                    Text(day.date.format(dayFormatter), color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE),
                        color = WearableMuted,
                        fontSize = 9.sp,
                    )
                }
                Text(day.sleepMinutes?.let(::formatMinutes) ?: "—", color = SleepPurple, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(day.averageHeartRate?.let { "$it bpm" } ?: "—", color = WearableAccent, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("${day.steps} 步", color = ActivityGreen, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
            if (index != days.lastIndex) HorizontalDivider(color = WearableLine.copy(alpha = 0.72f))
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
private fun formatMinutes(minutes: Int): String = "${minutes / 60}时${minutes % 60}分"
private fun formatDistance(meters: Int): String = if (meters >= 1_000) "%.2f 公里".format(Locale.getDefault(), meters / 1_000f) else "$meters 米"
private fun formatClock(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))
private fun bedtimeAxisMinutes(epochSeconds: Long): Float {
    val time = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalTime()
    val minutes = time.hour * 60 + time.minute
    return (if (minutes < 12 * 60) minutes + 24 * 60 else minutes).toFloat()
}
private fun formatClockAxis(value: Float): String {
    val total = value.toInt().mod(24 * 60)
    return "%02d:%02d".format(Locale.getDefault(), total / 60, total % 60)
}
private fun formatDecimal(value: Float): String = "%.1f".format(Locale.getDefault(), value)
