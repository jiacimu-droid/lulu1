package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.DurationSummary
import com.jiacimu.lulu.data.ApiUsageRecord
import com.jiacimu.lulu.data.PerformanceErrorRecord
import com.jiacimu.lulu.data.PerformanceTimingRecord
import com.jiacimu.lulu.data.TokenConsoleRecord
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FastPerformancePaper = Color(0xFFFFFDF7)
private val FastPerformanceCard = Color(0xFFFFFBF1)
private val FastPerformanceBorder = Color(0xFFEAE0CC)
private val FastPerformanceMuted = Color(0xFF6D7888)

@Composable
fun OptimizedPerformanceFeatureScreen(onBack: () -> Unit) {
    val repository = LuluRepositories.performance
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val tabs = remember { listOf("报错日志", "缓存", "控制台", "时长监测") }

    Scaffold(
        containerColor = FastPerformancePaper,
        topBar = { FeatureTopBar("性能监测", onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = FastPerformanceCard,
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1) },
                    )
                }
            }
            key(selectedTab) {
                when (selectedTab) {
                    0 -> FastErrorTab(onClear = { scope.launch { repository.clearErrors() } })
                    1 -> FastUsageTab(onClear = { scope.launch { repository.clearCache() } })
                    2 -> FastConsoleTab(onClear = { scope.launch { repository.clearConsole() } })
                    else -> FastDurationTab(onClear = { scope.launch { repository.clearTimings() } })
                }
            }
        }
    }
}

@Composable
private fun FastErrorTab(onClear: () -> Unit) {
    val records by LuluRepositories.performance.errorRecords.collectAsState(initial = emptyList())
    PaginatedPerformanceList(
        title = "报错日志",
        subtitle = "仅渲染当前可见批次，避免大量历史日志拖慢页面。",
        records = records,
        keyOf = PerformanceErrorRecord::id,
        onClear = onClear,
    ) { record ->
        FastPerformanceCard {
            Text(record.source + record.title.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), fontWeight = FontWeight.Bold)
            Text(record.createdAtMillis.fastDateTime(), color = FastPerformanceMuted, fontSize = 11.sp)
            HorizontalDivider(color = FastPerformanceBorder)
            Text(record.message, color = MaterialTheme.colorScheme.error)
            record.durationMillis?.let { FastMetric("耗时", "$it ms") }
        }
    }
}

@Composable
private fun FastUsageTab(onClear: () -> Unit) {
    val records by LuluRepositories.performance.usageRecords.collectAsState(initial = emptyList())
    PaginatedPerformanceList(
        title = "缓存统计",
        subtitle = "逐条显示每次调用的输入、输出与缓存 Token。",
        records = records,
        keyOf = ApiUsageRecord::id,
        onClear = onClear,
    ) { record ->
        FastPerformanceCard {
            Text(record.title.ifBlank { record.source.label }, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(record.model.ifBlank { "未返回模型名" }, color = FastPerformanceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FastMetric("输入", record.promptTokens.fastTokens())
            FastMetric("输出", record.completionTokens.fastTokens())
            FastMetric("缓存", record.cachedTokens.fastTokens())
        }
    }
}

@Composable
private fun FastConsoleTab(onClear: () -> Unit) {
    val records by LuluRepositories.performance.consoleRecords.collectAsState(initial = emptyList())
    PaginatedPerformanceList(
        title = "Token 控制台",
        subtitle = "默认显示最近50条；输入来源按需展开。",
        records = records,
        keyOf = TokenConsoleRecord::id,
        onClear = onClear,
    ) { record ->
        var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
        FastPerformanceCard(
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(record.model.ifBlank { "未返回模型名" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${record.source.label} · ${record.title}", color = FastPerformanceMuted, fontSize = 12.sp, maxLines = 1)
                }
                Text(record.createdAtMillis.fastTime(), color = FastPerformanceMuted, fontSize = 11.sp)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起" else "展开",
                    modifier = Modifier.size(20.dp),
                    tint = FastPerformanceMuted,
                )
            }
            FastMetric("计入输入", record.effectiveInputTokens.fastTokens())
            FastMetric("计入输出", record.effectiveOutputTokens.fastTokens())
            if (expanded) {
                HorizontalDivider(color = FastPerformanceBorder)
                FastMetric("接口输入", record.reportedInputTokens.takeIf { it > 0 }?.toString() ?: "未返回")
                FastMetric("接口输出", record.reportedOutputTokens.takeIf { it > 0 }?.toString() ?: "未返回")
                FastMetric("缓存 Token", record.cachedTokens.toString())
                val estimatedParts = record.breakdown.sumOf { it.estimatedTokens.toLong() }
                FastMetric("全部文本分项估算", estimatedParts.toString() + " tok")
                if (record.reportedInputTokens > 0L) {
                    val difference = record.reportedInputTokens - estimatedParts
                    FastMetric("接口与估算差额", (if (difference > 0) "+" else "") + difference.toString() + " tok")
                } else {
                    Text("服务商没有返回输入用量，此次计入输入使用本地估算。", color = FastPerformanceMuted, fontSize = 11.sp)
                }
                if (record.auditVersion > 0) {
                    FastMetric("输入文本字符（实际）", record.textCharacters.toString())
                    FastMetric("逐项已覆盖字符", record.coveredTextCharacters.toString())
                    val coverageOk = record.textCharacters.toLong() == record.coveredTextCharacters
                    Text(
                        if (coverageOk) "文本来源覆盖完整：分项字符之和与实际发送文本完全相等。"
                        else "文本来源未完全覆盖！差异为 " +
                            (record.textCharacters.toLong() - record.coveredTextCharacters) + " 字符，请检查拼装链路。",
                        color = if (coverageOk) FastPerformanceMuted else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                    FastMetric("实际消息数（含 system）", record.messageCount.toString())
                    FastMetric("文本 UTF-8", record.textUtf8Bytes.toString() + " 字节")
                    FastMetric("图片数量", record.imageCount.toString())
                    FastMetric("图片 DataURL 载荷", record.imageDataUrlBytes.toString() + " 字节")
                    FastMetric("实际请求 JSON", record.requestBodyBytes.toString() + " 字节")
                    if (record.requestBodyBytes > 0) {
                        FastMetric("JSON封装/转义/媒体差额",
                            (record.requestBodyBytes - record.textUtf8Bytes - record.imageDataUrlBytes).toString() + " 字节")
                    }
                    if (record.reducedRequest) Text(
                        "本次实际发送的是 Relay 精简重试版本，下面只统计最终发送版本。",
                        color = FastPerformanceMuted, fontSize = 11.sp,
                    )
                    Text(
                        "真实总 Token 来自 API；单独栏目只提供文本字符和粗略 Token 估算。" +
                            "若包含图片，服务商通常不会逐张提供准确 Token；不能把 JSON 字节当 Token，也不能把差额断定为漏统计。",
                        color = FastPerformanceMuted, fontSize = 11.sp,
                    )
                } else {
                    Text(
                        "旧版记录没有保存完整请求审计信息，无法追溯当时的图片、JSON 体积和字符覆盖率；更新后新调用才具备这些数据。",
                        color = FastPerformanceMuted, fontSize = 11.sp,
                    )
                }
                val sortedParts = record.breakdown.sortedByDescending { it.estimatedTokens }
                val canAllocate = record.auditVersion > 0 && record.imageCount == 0 &&
                    record.reportedInputTokens > 0 && record.coveredTextCharacters > 0L &&
                    record.coveredTextCharacters == record.textCharacters.toLong()
                if (canAllocate) Text(
                    "参考分布：右侧“等比分摊”按各部分字符数占比，将服务商总 Token 分摊到各栏目。" +
                        "这是帮助定位最大输入来源的推算，不是服务商逐栏目实测，不能用于精确计费。",
                    color = FastPerformanceMuted, fontSize = 11.sp,
                )
                sortedParts.forEach { part ->
                    val allocated = if (canAllocate) (
                        record.reportedInputTokens.toDouble() * part.chars /
                            record.coveredTextCharacters
                    ).toLong() else null
                    FastTokenPartLine(part, allocated)
                }
            }
        }
    }
}

@Composable
private fun FastTokenPartLine(part: com.jiacimu.lulu.data.TokenBreakdownItem, allocated: Long?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(part.label, color = FastPerformanceMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(part.estimatedTokens.toString() + " tok（粗估）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(part.chars.toString() + " 字符", color = FastPerformanceMuted, fontSize = 11.sp)
            if (allocated != null) Text("等比分摊约 " + allocated.toString() + " tok",
                color = FastPerformanceMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FastDurationTab(onClear: () -> Unit) {
    val timings by LuluRepositories.performance.timingRecords.collectAsState(initial = emptyList())
    val durations by LuluRepositories.performance.observeDurations()
        .collectAsState(initial = DurationSummary(0, 0, 0))
    val latest = remember(timings) {
        timings.groupBy(PerformanceTimingRecord::stage)
            .mapNotNull { (_, values) -> values.maxByOrNull(PerformanceTimingRecord::recordedAtMillis) }
            .sortedByDescending(PerformanceTimingRecord::recordedAtMillis)
    }
    PaginatedPerformanceList(
        title = "时长监测",
        subtitle = "每个调用阶段只显示最新记录。",
        records = latest,
        keyOf = PerformanceTimingRecord::id,
        onClear = onClear,
        summary = {
            FastPerformanceCard {
                FastMetric("学习", "${durations.studyMinutes} 分钟")
                FastMetric("聊天", "${durations.chatMinutes} 分钟")
                FastMetric("通话", "${durations.callMinutes} 分钟")
            }
        },
    ) { record ->
        FastPerformanceCard {
            Text(record.stage, fontWeight = FontWeight.Bold)
            FastMetric("最新耗时", "${record.durationMillis} ms")
            if (record.detail.isNotBlank()) Text(record.detail, color = FastPerformanceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun <T> PaginatedPerformanceList(
    title: String,
    subtitle: String,
    records: List<T>,
    keyOf: (T) -> Any,
    onClear: () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    var visibleCount by rememberSaveable(title) { mutableIntStateOf(50) }
    LaunchedEffect(records.size) {
        if (records.size < visibleCount) visibleCount = maxOf(50, records.size)
    }
    val visibleRecords = remember(records, visibleCount) { records.take(visibleCount) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header-$title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text(subtitle, color = FastPerformanceMuted, fontSize = 12.sp)
                }
                TextButton(onClick = onClear, enabled = records.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空")
                }
            }
        }
        summary?.let { block -> item(key = "summary-$title") { block() } }
        if (records.isEmpty()) {
            item(key = "empty-$title") {
                FastPerformanceCard {
                    Text("暂无记录", fontWeight = FontWeight.Bold)
                    Text("产生新的调用记录后会显示在这里。", color = FastPerformanceMuted)
                }
            }
        } else {
            items(visibleRecords, key = keyOf) { record -> itemContent(record) }
            if (visibleCount < records.size) {
                item(key = "more-$title-$visibleCount") {
                    OutlinedButton(
                        onClick = { visibleCount = (visibleCount + 50).coerceAtMost(records.size) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("再加载 ${minOf(50, records.size - visibleCount)} 条")
                    }
                }
            }
        }
    }
}

@Composable
private fun FastPerformanceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FastPerformanceCard),
        border = BorderStroke(1.dp, FastPerformanceBorder),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

@Composable
private fun FastMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = FastPerformanceMuted, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val fastTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val fastDateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private fun Long.fastTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(fastTimeFormatter)
private fun Long.fastDateTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(fastDateFormatter)
private fun Long.fastTokens(): String = when {
    this >= 1_000_000L -> String.format(java.util.Locale.getDefault(), "%.2fM", this / 1_000_000.0)
    this >= 1_000L -> String.format(java.util.Locale.getDefault(), "%.1fK", this / 1_000.0)
    else -> toString()
}
