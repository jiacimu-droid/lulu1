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
                FastMetric("接口输入（实测）", record.reportedInputTokens.takeIf { it > 0 }?.toString() ?: "未返回")
                FastMetric("接口输出（实测）", record.reportedOutputTokens.takeIf { it > 0 }?.toString() ?: "未返回")
                val totalEstimate = record.breakdown.sumOf { it.estimatedTokens.toLong() }
                FastMetric("全文本粗估", totalEstimate.toString() + " tok")
                val empirical = com.jiacimu.lulu.data.TokenConsolePresentation.calibratedEstimate(record, records)
                if (empirical != null) {
                    FastMetric("历史同线路校准估算", empirical.tokens.toString() + " tok")
                    Text(
                        "由之前 " + empirical.sampleCount + " 次同模型/服务商的完整纯文本记录得到" +
                            (if (empirical.sameSource) "（同调用来源）" else "") +
                            "；仍不是这次的实际分项 Token。",
                        color = FastPerformanceMuted, fontSize = 11.sp,
                    )
                }
                if (record.reportedInputTokens > 0L) {
                    val difference = record.reportedInputTokens - totalEstimate
                    FastMetric("实测与粗估差额",
                        (if (difference > 0) "+" else "") + difference.toString() + " tok")
                }
                val groups = com.jiacimu.lulu.data.TokenConsolePresentation.groups(record.breakdown)
                val canAllocate = record.auditVersion > 0 && record.imageCount == 0 &&
                    record.reportedInputTokens > 0 && record.coveredTextCharacters > 0L &&
                    record.coveredTextCharacters == record.textCharacters.toLong()
                Text("输入组成 · " + groups.size + " 大类", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 9.dp))
                var allocatedSoFar = 0L
                groups.forEachIndexed { index, group ->
                    val share = if (!canAllocate) null else if (index == groups.lastIndex) {
                        record.reportedInputTokens - allocatedSoFar
                    } else {
                        (record.reportedInputTokens.toDouble() * group.chars /
                            record.coveredTextCharacters).toLong().also { allocatedSoFar += it }
                    }
                    FastTokenSourceGroup(record.id, group, share)
                }
                var diagnosticsExpanded by rememberSaveable(record.id, "token-diagnostics") { mutableStateOf(false) }
                TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                    Text(if (diagnosticsExpanded) "收起请求核对" else "查看请求核对与技术数据")
                    Icon(if (diagnosticsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, modifier = Modifier.size(17.dp))
                }
                if (diagnosticsExpanded) {
                    HorizontalDivider(color = FastPerformanceBorder)
                    FastMetric("缓存 Token", record.cachedTokens.toString())
                    if (record.requestSource.isNotBlank()) FastMetric("原始调用来源", record.requestSource)
                    if (record.contextMode.isNotBlank()) FastMetric("实际角色上下文", record.contextMode)
                    if (record.providerHost.isNotBlank()) FastMetric("API服务域名", record.providerHost)
                    if (record.auditVersion > 0) {
                        FastMetric("请求方式", if (record.streamedRequest) "流式" else "非流式")
                        FastMetric("输入文本字符", record.textCharacters.toString())
                        FastMetric("分项覆盖字符", record.coveredTextCharacters.toString())
                        val coverageOk = record.textCharacters.toLong() == record.coveredTextCharacters
                        Text(
                            if (coverageOk) "文本覆盖完整：所有分项字符之和与实际发送文本相等。"
                            else "文本覆盖不完整！差 " +
                                (record.textCharacters.toLong() - record.coveredTextCharacters) + " 字符。",
                            color = if (coverageOk) FastPerformanceMuted else MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                        )
                        FastMetric("实际消息数（含 system）", record.messageCount.toString())
                        FastMetric("文本 UTF-8", record.textUtf8Bytes.toString() + " 字节")
                        FastMetric("图片数量", record.imageCount.toString())
                        FastMetric("图片 DataURL 载荷", record.imageDataUrlBytes.toString() + " 字节")
                        FastMetric("实际请求 JSON", record.requestBodyBytes.toString() + " 字节")
                        if (record.requestBodyBytes > 0L) FastMetric("JSON封装/转义/媒体差额",
                            (record.requestBodyBytes - record.textUtf8Bytes -
                                record.imageDataUrlBytes).toString() + " 字节")
                        if (record.reducedRequest) Text("本次统计的是 Relay 精简重试实际发送的版本。",
                            color = FastPerformanceMuted, fontSize = 11.sp)
                    } else Text("旧版记录未保存完整请求审计，不能反推遗漏的请求细节。",
                        color = FastPerformanceMuted, fontSize = 11.sp)
                    Text(
                        "服务商只提供整次真实 Token；分项为字符粗估。无图片且覆盖完整时，" +
                            "大类可按字符比例分摊实测总量供定位；字节不等于 Token，差额不能直接视为漏统计。",
                        color = FastPerformanceMuted, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FastTokenSourceGroup(
    recordId: String, group: com.jiacimu.lulu.data.TokenSourceGroup, allocated: Long?,
) {
    var details by rememberSaveable(recordId, group.title) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(group.estimatedTokens.toString() + " tok（粗估） · " +
                    group.chars.toString() + " 字符 · " + group.items.size + " 项",
                    color = FastPerformanceMuted, fontSize = 11.sp)
                if (allocated != null) Text("等比分摊约 " + allocated + " tok（非实测）",
                    color = FastPerformanceMuted, fontSize = 11.sp)
            }
            TextButton(onClick = { details = !details }) {
                Text(if (details) "收起" else "明细", fontSize = 12.sp)
                Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(17.dp))
            }
        }
        if (details) {
            group.items.forEach { part -> FastTokenPartLine(part, null) }
        }
        HorizontalDivider(color = FastPerformanceBorder)
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
