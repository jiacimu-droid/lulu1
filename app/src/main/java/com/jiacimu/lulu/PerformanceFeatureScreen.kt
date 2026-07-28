package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.DurationSummary
import com.jiacimu.lulu.data.ApiUsageRecord
import com.jiacimu.lulu.data.ApiUsageSummary
import com.jiacimu.lulu.data.PerformanceErrorRecord
import com.jiacimu.lulu.data.PerformanceTimingRecord
import com.jiacimu.lulu.data.TokenConsoleRecord
import com.jiacimu.lulu.data.summarizeApiUsage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

private val PerformancePaper = Color(0xFFFFFDF7)
private val PerformanceCard = Color(0xFFFFFBF1)
private val PerformanceBorder = Color(0xFFEAE0CC)
private val PerformanceMuted = Color(0xFF6D7888)
private val PerformanceAccent = Color(0xFFF4D57D)

@Composable
fun PerformanceFeatureScreen(onBack: () -> Unit) {
    val repository = LuluRepositories.performance
    val errors by repository.errorRecords.collectAsState(initial = emptyList())
    val usageRecords by repository.usageRecords.collectAsState(initial = emptyList())
    val consoleRecords by repository.consoleRecords.collectAsState(initial = emptyList())
    val timings by repository.timingRecords.collectAsState(initial = emptyList())
    val durations by repository.observeDurations().collectAsState(initial = DurationSummary(0, 0, 0))
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("报错日志", "缓存", "控制台", "时长监测")
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = PerformancePaper,
        topBar = { FeatureTopBar("性能监测", onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = PerformanceCard,
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
            when (selectedTab) {
                0 -> ErrorLogContent(
                    records = errors,
                    onClear = { scope.launch { repository.clearErrors() } },
                )
                1 -> CacheMonitorContent(
                    records = usageRecords,
                    onClear = { scope.launch { repository.clearCache() } },
                )
                2 -> TokenConsoleContent(
                    records = consoleRecords,
                    onClear = { scope.launch { repository.clearConsole() } },
                )
                else -> DurationMonitorContent(
                    timings = timings,
                    durations = durations,
                    onClearTimings = { scope.launch { repository.clearTimings() } },
                )
            }
        }
    }
}

@Composable
private fun ErrorLogContent(
    records: List<PerformanceErrorRecord>,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionHeader(
                title = "报错日志",
                subtitle = "保留模型与功能调用失败的来源、时间和请求信息。",
                action = "清空",
                actionEnabled = records.isNotEmpty(),
                onAction = onClear,
            )
        }
        if (records.isEmpty()) {
            item { PerformanceEmptyCard("暂时没有日志", "新的调用异常会自动记录在这里。") }
        } else {
            items(records, key = PerformanceErrorRecord::id) { record ->
                PerformanceCardBox {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    append(record.source)
                                    if (record.title.isNotBlank()) append(" · ${record.title}")
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            Text(record.createdAtMillis.asDateTime(), color = PerformanceMuted, fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        buildString {
                                            appendLine("时间：${record.createdAtMillis.asDateTime()}")
                                            appendLine("来源：${record.source}")
                                            if (record.title.isNotBlank()) appendLine("标题：${record.title}")
                                            record.requestUrl?.let { appendLine("地址：$it") }
                                            record.durationMillis?.let { appendLine("耗时：${it}ms") }
                                            append("错误：${record.message}")
                                        },
                                    ),
                                )
                            },
                        ) { Icon(Icons.Outlined.ContentCopy, "复制日志") }
                    }
                    HorizontalDivider(color = PerformanceBorder)
                    Text(record.message, color = MaterialTheme.colorScheme.error)
                    record.requestUrl?.let { url ->
                        MetricLine("请求地址", url, valueMaxLines = 3)
                    }
                    record.durationMillis?.let { MetricLine("失败前耗时", "${it} ms") }
                }
            }
        }
    }
}

@Composable
private fun CacheMonitorContent(
    records: List<ApiUsageRecord>,
    onClear: () -> Unit,
) {
    val summaries = remember(records) { records.summarizeApiUsage() }
    val promptTokens = records.sumOf(ApiUsageRecord::promptTokens)
    val completionTokens = records.sumOf(ApiUsageRecord::completionTokens)
    val cachedTokens = records.sumOf(ApiUsageRecord::cachedTokens)
    val cacheRate = if (promptTokens > 0L) cachedTokens.toFloat() / promptTokens.toFloat() else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "缓存统计",
                subtitle = "按旧版规则保留输入、输出、缓存 Token、调用来源和明细。",
                action = "清空",
                actionEnabled = records.isNotEmpty(),
                onAction = onClear,
            )
        }
        item {
            PerformanceCardBox {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("缓存命中率", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "${(cacheRate * 100f).formatPercent()}%",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { cacheRate.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                MetricLine("输入 Token", promptTokens.formatTokens())
                MetricLine("输出 Token", completionTokens.formatTokens())
                MetricLine("缓存读取", cachedTokens.formatTokens())
                MetricLine("缓存记录", records.size.toString())
            }
        }
        if (summaries.isNotEmpty()) {
            item { Text("调用来源", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            items(summaries, key = { it.source.name }) { summary ->
                CacheSourceSummaryCard(summary)
            }
        }
        item {
            Text("缓存明细", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (records.isEmpty()) {
            item {
                PerformanceEmptyCard(
                    "还没有 Token 记录",
                    "聊天、电话或游戏完成一次模型回复后会自动出现。",
                )
            }
        } else {
            items(records, key = ApiUsageRecord::id) { record ->
                ApiUsageRecordCard(record)
            }
        }
    }
}

@Composable
private fun CacheSourceSummaryCard(summary: ApiUsageSummary) {
    PerformanceCardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(summary.source.label, fontWeight = FontWeight.Bold)
            Text("${(summary.cacheRate * 100f).formatPercent()}%", color = MaterialTheme.colorScheme.primary)
        }
        MetricLine("调用次数", summary.callCount.toString())
        MetricLine("输入 Token", summary.promptTokens.formatTokens())
        MetricLine("输出 Token", summary.completionTokens.formatTokens())
        MetricLine("缓存 Token", summary.cachedTokens.formatTokens())
    }
}

@Composable
private fun ApiUsageRecordCard(record: ApiUsageRecord) {
    val cacheRate = if (record.promptTokens > 0L) {
        record.cachedTokens.toFloat() / record.promptTokens.toFloat() * 100f
    } else {
        0f
    }
    PerformanceCardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.title.ifBlank { record.source.label }, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    record.model.ifBlank { "未返回模型名" },
                    color = PerformanceMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.estimated) {
                    Text("接口未完整返回 usage，缺失部分使用估算值", color = PerformanceMuted, fontSize = 11.sp)
                }
            }
            Text(record.createdAtMillis.asTime(), color = PerformanceMuted, fontSize = 11.sp)
        }
        HorizontalDivider(color = PerformanceBorder)
        MetricLine("输入", record.promptTokens.formatTokens())
        MetricLine("输出", record.completionTokens.formatTokens())
        MetricLine("缓存", record.cachedTokens.formatTokens())
        MetricLine("缓存率", "${cacheRate.formatPercent()}%")
    }
}

@Composable
private fun TokenConsoleContent(
    records: List<TokenConsoleRecord>,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Token 控制台",
                subtitle = "显示模型、输入来源拆分、接口 usage 与缺失时的估算值。",
                action = "清空",
                actionEnabled = records.isNotEmpty(),
                onAction = onClear,
            )
        }
        if (records.isEmpty()) {
            item {
                PerformanceEmptyCard(
                    "还没有 AI 调用日志",
                    "发送一次消息后，这里会显示 Token 来源和模型信息。",
                )
            }
        } else {
            items(records, key = TokenConsoleRecord::id) { record ->
                PerformanceCardBox {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                record.model.ifBlank { "未返回模型名" },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${record.source.label} · ${record.title}",
                                color = PerformanceMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        Text(record.createdAtMillis.asTime(), color = PerformanceMuted, fontSize = 11.sp)
                    }
                    HorizontalDivider(color = PerformanceBorder)
                    MetricLine(
                        "接口输入",
                        record.reportedInputTokens.takeIf { it > 0L }?.formatTokens() ?: "未返回",
                    )
                    MetricLine(
                        "接口输出",
                        record.reportedOutputTokens.takeIf { it > 0L }?.formatTokens() ?: "未返回",
                    )
                    MetricLine("计入输入", record.effectiveInputTokens.formatTokens())
                    MetricLine("计入输出", record.effectiveOutputTokens.formatTokens())
                    MetricLine("缓存 Token", record.cachedTokens.formatTokens())
                    if (record.breakdown.isNotEmpty()) {
                        HorizontalDivider(color = PerformanceBorder)
                        Text("输入来源估算", fontWeight = FontWeight.SemiBold)
                        record.breakdown.forEach { item ->
                            MetricLine(
                                item.label,
                                "${item.estimatedTokens} tok / ${item.chars} 字符",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationMonitorContent(
    timings: List<PerformanceTimingRecord>,
    durations: DurationSummary,
    onClearTimings: () -> Unit,
) {
    val summaries = remember(timings) { buildTimingSummaries(timings) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "时长监测",
                subtitle = "保留角色可读取的生活时长，同时记录模型调用各阶段耗时。",
                action = "清空耗时",
                actionEnabled = timings.isNotEmpty(),
                onAction = onClearTimings,
            )
        }
        item {
            PerformanceCardBox {
                Text("今日活动时长", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                MetricLine("学习", "${durations.studyMinutes} 分钟")
                MetricLine("聊天", "${durations.chatMinutes} 分钟")
                MetricLine("通话", "${durations.callMinutes} 分钟")
                Text("这些数据继续提供给角色读取。", color = PerformanceMuted, fontSize = 12.sp)
            }
        }
        item {
            Text("调用链耗时", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (summaries.isEmpty()) {
            item {
                PerformanceEmptyCard(
                    "还没有耗时记录",
                    "完成一次模型调用后会记录 Prompt、模型请求和总耗时。",
                )
            }
        } else {
            items(summaries, key = TimingSummary::stage) { summary ->
                PerformanceCardBox {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(summary.stage, fontWeight = FontWeight.Bold)
                        Text("${summary.latestMillis} ms", color = MaterialTheme.colorScheme.primary)
                    }
                    MetricLine("平均", "${summary.averageMillis} ms")
                    MetricLine("最大", "${summary.maxMillis} ms")
                    MetricLine("记录", "${summary.count} 次")
                    if (summary.latestDetail.isNotBlank()) {
                        Text(
                            summary.latestDetail,
                            color = PerformanceMuted,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            Text(subtitle, color = PerformanceMuted, fontSize = 12.sp)
        }
        TextButton(onClick = onAction, enabled = actionEnabled) {
            Icon(Icons.Outlined.DeleteSweep, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text(action)
        }
    }
}

@Composable
private fun PerformanceCardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PerformanceCard),
        border = BorderStroke(1.dp, PerformanceBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun PerformanceEmptyCard(title: String, text: String) {
    PerformanceCardBox {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text, color = PerformanceMuted)
    }
}

@Composable
private fun MetricLine(label: String, value: String, valueMaxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = PerformanceMuted, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class TimingSummary(
    val stage: String,
    val latestMillis: Long,
    val averageMillis: Long,
    val maxMillis: Long,
    val count: Int,
    val latestDetail: String,
)

private fun buildTimingSummaries(records: List<PerformanceTimingRecord>): List<TimingSummary> = records
    .groupBy(PerformanceTimingRecord::stage)
    .map { (stage, items) ->
        val latest = items.maxByOrNull(PerformanceTimingRecord::recordedAtMillis) ?: items.first()
        TimingSummary(
            stage = stage,
            latestMillis = latest.durationMillis,
            averageMillis = items.map(PerformanceTimingRecord::durationMillis).average().roundToLong(),
            maxMillis = items.maxOf(PerformanceTimingRecord::durationMillis),
            count = items.size,
            latestDetail = latest.detail,
        )
    }
    .sortedBy { summary -> listOf("Prompt", "首 Token", "模型请求", "工具", "Planner", "Memory", "总耗时").indexOf(summary.stage).let { if (it < 0) Int.MAX_VALUE else it } }

private val shortTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun Long.asTime(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(shortTimeFormatter)

private fun Long.asDateTime(): String = Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(dateTimeFormatter)

private fun Long.formatTokens(): String = when {
    this >= 1_000_000L -> String.format(java.util.Locale.getDefault(), "%.2fM", this / 1_000_000.0)
    this >= 1_000L -> String.format(java.util.Locale.getDefault(), "%.1fK", this / 1_000.0)
    else -> toString()
}

private fun Float.formatPercent(): String = String.format(java.util.Locale.getDefault(), "%.1f", this)
