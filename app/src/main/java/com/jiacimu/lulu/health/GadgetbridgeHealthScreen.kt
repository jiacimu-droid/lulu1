package com.jiacimu.lulu.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WearablePaper = Color(0xFFFFFAFB)
private val WearableCard = Color.White
private val WearableSoft = Color(0xFFFFEEF3)
private val WearableAccent = Color(0xFF91344F)
private val WearableInk = Color(0xFF292225)
private val WearableMuted = Color(0xFF81767A)
private val WearableLine = Color(0xFFECE0E4)

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { GadgetbridgeHealthStore.connect(context, uri) }
        }
    }

    LaunchedEffect(state.connected, refreshedOnOpen) {
        if (state.connected && !refreshedOnOpen) {
            refreshedOnOpen = true
            GadgetbridgeHealthStore.refresh(context)
        }
    }

    val latest = state.latest
    val recent = state.days.takeLast(7)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SourceCard(
                state = state,
                onChoose = {
                    picker.launch(
                        arrayOf(
                            "application/vnd.sqlite3",
                            "application/x-sqlite3",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                onRefresh = { scope.launch { GadgetbridgeHealthStore.refresh(context) } },
                onDisconnect = { GadgetbridgeHealthStore.disconnect(context) },
            )
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

        if (!state.connected) {
            item { EmptyWearableCard() }
        } else if (latest != null) {
            item {
                Text("最新健康摘要", color = WearableInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        title = "今日步数",
                        value = latest.steps.toString(),
                        unit = "步",
                        icon = Icons.Outlined.DirectionsWalk,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        title = "平均心率",
                        value = latest.averageHeartRate?.toString() ?: "—",
                        unit = "次/分",
                        icon = Icons.Outlined.FavoriteBorder,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        title = "睡眠",
                        value = latest.sleepMinutes?.let(::formatMinutes) ?: "—",
                        unit = "",
                        icon = Icons.Outlined.Bedtime,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        title = "血氧",
                        value = latest.spo2?.toString() ?: "—",
                        unit = "%",
                        icon = Icons.Outlined.WaterDrop,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (latest.stress != null || latest.calories != null || latest.distanceMeters != null) {
                item {
                    HealthDetailsCard(latest)
                }
            }
            if (recent.any { it.steps > 0 }) {
                item {
                    BarChartCard(
                        title = "近7天步数",
                        values = recent.map { it.date.format(dayFormatter) to it.steps.toFloat() },
                        valueLabel = { "${it.toInt()}步" },
                    )
                }
            }
            if (recent.any { (it.sleepMinutes ?: 0) > 0 }) {
                item {
                    BarChartCard(
                        title = "近7天睡眠",
                        values = recent.map { it.date.format(dayFormatter) to (it.sleepMinutes ?: 0).toFloat() },
                        valueLabel = { formatMinutes(it.toInt()) },
                    )
                }
            }
            if (recent.count { it.averageHeartRate != null } >= 2) {
                item {
                    LineChartCard(
                        title = "近7天平均心率",
                        values = recent.mapNotNull { day ->
                            day.averageHeartRate?.let { day.date.format(dayFormatter) to it.toFloat() }
                        },
                    )
                }
            }
            item {
                Text("最近记录", color = WearableInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            items(state.days.takeLast(14).reversed(), key = { it.date.toString() }) { day ->
                DaySummaryRow(day)
            }
        } else if (!state.importing) {
            item { EmptyWearableCard(message = "已经获得文件授权，但还没有解析到健康数据。请确认 Gadgetbridge 已同步手环并完成导出。") }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun SourceCard(
    state: GadgetbridgeHealthState,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, WearableLine),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = WearableSoft, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Storage, null, tint = WearableAccent, modifier = Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Gadgetbridge 数据库", color = WearableInk, fontWeight = FontWeight.Bold)
                    Text(
                        if (state.connected) state.sourceName.ifBlank { "Gadgetbridge.db" }
                        else "选择 /Download/手环/Gadgetbridge.db",
                        color = WearableMuted,
                        fontSize = 11.sp,
                    )
                }
                if (state.importing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Text(
                "只读取你选择的数据库文件。选择一次后会保留读取授权，并与 Gadgetbridge 的每小时自动导出同步刷新。",
                color = WearableMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            if (state.lastImportedAt != null) {
                val time = state.lastImportedAt.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                Text("上次解析：$time · ${state.tableName}", color = WearableMuted, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onChoose,
                    colors = ButtonDefaults.buttonColors(containerColor = WearableAccent),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.connected) "重新选择" else "选择数据库")
                }
                if (state.connected) {
                    OutlinedButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, "刷新", modifier = Modifier.size(18.dp))
                    }
                    OutlinedButton(onClick = onDisconnect) {
                        Icon(Icons.Outlined.LinkOff, "断开", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWearableCard(message: String = "先在 Gadgetbridge 中完成手环同步，并保持自动导出到 Gadgetbridge.db；然后在这里选择该文件。") {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Watch, null, tint = WearableAccent, modifier = Modifier.size(42.dp))
            Text("等待手环数据", color = WearableInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(message, color = WearableMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = WearableCard,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = WearableAccent, modifier = Modifier.size(19.dp))
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
private fun HealthDetailsCard(day: GadgetbridgeDaySummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("更多数据", color = WearableInk, fontWeight = FontWeight.Bold)
            day.minimumHeartRate?.let { min ->
                Text("心率范围  $min—${day.maximumHeartRate ?: min} 次/分", color = WearableMuted, fontSize = 12.sp)
            }
            day.stress?.let { Text("平均压力  $it", color = WearableMuted, fontSize = 12.sp) }
            day.calories?.let { Text("活动热量  $it 千卡", color = WearableMuted, fontSize = 12.sp) }
            day.distanceMeters?.let { Text("活动距离  ${formatDistance(it)}", color = WearableMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun BarChartCard(
    title: String,
    values: List<Pair<String, Float>>,
    valueLabel: (Float) -> String,
) {
    val maximum = values.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = WearableInk, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                values.lastOrNull()?.let { Text(valueLabel(it.second), color = WearableAccent, fontSize = 11.sp) }
            }
            Canvas(Modifier.fillMaxWidth().height(118.dp)) {
                if (values.isEmpty()) return@Canvas
                val gap = 7.dp.toPx()
                val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(3.dp.toPx())
                values.forEachIndexed { index, item ->
                    val height = (item.second / maximum * size.height).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = WearableAccent.copy(alpha = 0.76f),
                        topLeft = Offset(index * (width + gap), size.height - height),
                        size = Size(width, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                values.forEach { item ->
                    Text(item.first, color = WearableMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LineChartCard(title: String, values: List<Pair<String, Float>>) {
    val minimum = values.minOfOrNull { it.second } ?: 0f
    val maximum = values.maxOfOrNull { it.second } ?: 1f
    val range = (maximum - minimum).coerceAtLeast(1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = WearableInk, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                values.lastOrNull()?.let { Text("${it.second.toInt()}次/分", color = WearableAccent, fontSize = 11.sp) }
            }
            Canvas(Modifier.fillMaxWidth().height(118.dp)) {
                if (values.size < 2) return@Canvas
                val points = values.mapIndexed { index, item ->
                    val x = index * size.width / (values.size - 1)
                    val y = size.height - ((item.second - minimum) / range * size.height * 0.82f + size.height * 0.09f)
                    Offset(x, y)
                }
                points.zipWithNext().forEach { (first, second) ->
                    drawLine(WearableAccent, first, second, strokeWidth = 3.dp.toPx())
                }
                points.forEach { drawCircle(WearableAccent, radius = 4.dp.toPx(), center = it) }
            }
            Row(Modifier.fillMaxWidth()) {
                values.forEach { item ->
                    Text(item.first, color = WearableMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DaySummaryRow(day: GadgetbridgeDaySummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WearableCard,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, WearableLine),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(54.dp)) {
                Text(day.date.format(dayFormatter), color = WearableInk, fontWeight = FontWeight.Bold)
                Text(day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE), color = WearableMuted, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("${day.steps} 步", color = WearableInk, fontSize = 13.sp)
                Text(
                    listOfNotNull(
                        day.sleepMinutes?.let { "睡眠 ${formatMinutes(it)}" },
                        day.averageHeartRate?.let { "心率 $it" },
                        day.spo2?.let { "血氧 $it%" },
                    ).joinToString(" · ").ifBlank { "暂无其他数据" },
                    color = WearableMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private val dayFormatter = DateTimeFormatter.ofPattern("M/d")
private fun formatMinutes(minutes: Int): String = "${minutes / 60}时${minutes % 60}分"
private fun formatDistance(meters: Int): String = if (meters >= 1_000) "%.2f 公里".format(meters / 1_000f) else "$meters 米"
