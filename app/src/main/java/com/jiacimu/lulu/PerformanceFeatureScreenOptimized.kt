package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluCssScaffold
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
    val tabs = remember { listOf("报错日志", "缓存", "控制台", "API耗时") }

    LuluCssScaffold(
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
    val dailyCounts by LuluRepositories.performance.dailyRequestCounts.collectAsState(initial = emptyList())
    val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
    val todayCounts = dailyCounts.filter { it.localDate == today }
    PaginatedPerformanceList(
        title = "Token 控制台",
        subtitle = "默认显示最近50条；输入来源按需展开。",
        records = records,
        keyOf = TokenConsoleRecord::id,
        onClear = onClear,
        summary = {
            FastPerformanceCard {
                Text("今日模型请求次数 · 本机发送尝试", fontWeight = FontWeight.Bold)
                FastMetric("总计", todayCounts.sumOf { it.attempts }.toString() + " 次")
                todayCounts.groupBy { it.source }
                    .map { (source, rows) -> source to rows.sumOf { it.attempts } }
                    .sortedByDescending { it.second }
                    .forEach { (source, count) -> FastMetric(source, "$count 次") }
                Text("从安装包含本功能的版本起统计：请求交给网络前计一次，失败/超时仍计入；" +
                    "不是服务商最终计费量，也不涵盖中转站独立追加的请求。只保存日期、功能来源、模型和次数；旧记录不能补算。",
                    color = FastPerformanceMuted, fontSize = 11.sp)
            }
        },
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
                if (record.modelOutputCharacters > 0) {
                    FastMetric("模型返回正文（含结构化 JSON）", "${record.modelOutputCharacters} 字符")
                    Text("这里计入 action、工具参数、心声和其他未发送的 JSON 字段，并非仅聊天气泡文字。",
                        color = FastPerformanceMuted, fontSize = 11.sp)
                }
                record.reportedReasoningTokens?.let { reported ->
                    FastMetric("供应商明确上报的推理", "$reported tok")
                    Text("只显示供应商明确返回的推理统计；未返回不代表模型没有推理。不能由剩余 Token 推断都是推理。",
                        color = FastPerformanceMuted, fontSize = 11.sp)
                }
                val totalEstimate = record.breakdown.sumOf { it.estimatedTokens.toLong() }
                // The provider returns only ONE real count. Make reconciliation
                // visible before any rough estimate, so 70k real vs 44k rough
                // never looks like 26k missing messages.
                val groups = com.jiacimu.lulu.data.TokenConsolePresentation.groups(record.breakdown)
                val allocations = com.jiacimu.lulu.data.TokenConsolePresentation
                    .allocateReportedInput(record, groups)
                if (allocations != null) {
                    FastMetric("文本分组参考合计", allocations.sum().toString() + " tok")
                    Text(
                        "已与接口输入总量对账；下面的各类数值按发送文本字符占比分摊，" +
                            "不是服务商逐类实测。不能凭这个比例确定每段实际分词成本。",
                        color = FastPerformanceMuted, fontSize = 11.sp,
                    )
                } else {
                    Text("本条含图片、旧记录或字符核对不完整，不能把接口实测 Token 按文本比例拆分。",
                        color = FastPerformanceMuted, fontSize = 11.sp)
                }
                FastMetric("原始字符公式（仅作参考）", totalEstimate.toString() + " tok")
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
                Text("输入组成 · " + groups.size + " 大类", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 9.dp))
                groups.forEachIndexed { index, group ->
                    FastTokenSourceGroup(record.id, group, allocations?.get(index))
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
                    if (record.reportedInputTokens > 0L) {
                        val cacheRate = (record.cachedTokens.toDouble() * 100.0 /
                            record.reportedInputTokens.toDouble()).coerceIn(0.0, 100.0)
                        FastMetric("输入缓存命中占比", "%.1f%%".format(cacheRate))
                        Text("占比基于本次接口上报的输入量与缓存量；如果中转站未透传缓存统计，零值不代表 Google 没有命中。",
                            color = FastPerformanceMuted, fontSize = 11.sp)
                    }
                    FastMetric("归类明细项数", record.breakdown.size.toString())
                    val groupChars = groups.sumOf { it.chars }
                    val groupEstimated = groups.sumOf { it.estimatedTokens }
                    FastMetric("文本分组字符核对", "$groupChars / ${record.textCharacters}")
                    FastMetric("文本分组粗估核对", "$groupEstimated / $totalEstimate")
                    Text("核对的是客户端实际发送的文字，服务商可能计入消息结构或中转额外输入；" +
                        "只有供应商逐段返回 Token 才能给出严格真实分项。",
                        color = FastPerformanceMuted, fontSize = 11.sp)
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
                        if (record.reducedRequest) Text("本次统计的是实际发送的临时精简上下文；原始记录未删除。",
                            color = FastPerformanceMuted, fontSize = 11.sp)
                    } else Text("旧版记录未保存完整请求审计，不能反推遗漏的请求细节。",
                        color = FastPerformanceMuted, fontSize = 11.sp)
                    Text(
                        "服务商只提供整次真实 Token，图片的 Token 不等于图片 DataURL 字节；分项为字符粗估。无图片且覆盖完整时，" +
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
                if (allocated != null) Text("约 " + allocated.toString() + " tok · 比例归属（非逐项实测）",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(group.estimatedTokens.toString() + " tok（字符粗估） · " +
                    group.chars.toString() + " 字符 · " + group.items.size + " 项",
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

/** Each row is one attempted model generation, independent of the role's full timeline. */
private data class ApiTimingCall(val id: String, val records: List<PerformanceTimingRecord>) {
    val outcome: PerformanceTimingRecord? get() = records.firstOrNull {
        it.stage == "总耗时" || it.stage == "API调用失败"
    }
    val finishedAt: Long get() = outcome?.recordedAtMillis
        ?: records.maxOfOrNull { it.recordedAtMillis } ?: 0L
    val totalMillis: Long? get() = outcome?.durationMillis
    val source: String get() = outcome?.source.orEmpty()
    val model: String get() = outcome?.model.orEmpty()
    val provider: String get() = outcome?.provider.orEmpty()
    val bodyBytes: Long get() = outcome?.requestBodyBytes ?: 0L
    val status: String get() = outcome?.status?.takeIf(String::isNotBlank) ?: "进行中"
    fun duration(key: String): Long? = records.firstOrNull { it.stage == key }?.durationMillis
}

@Composable
private fun FastDurationTab(onClear: () -> Unit) {
    val timings by LuluRepositories.performance.timingRecords.collectAsState(initial = emptyList())
    val durations by LuluRepositories.performance.observeDurations()
        .collectAsState(initial = DurationSummary(0, 0, 0))
    val requests = remember(timings) {
        timings.filter { it.requestId.isNotBlank() }
            .groupBy { it.requestId }
            .map { (id, entries) -> ApiTimingCall(id, entries) }
            .sortedByDescending { it.finishedAt }
    }
    val older = remember(timings) { timings.filter { it.requestId.isBlank() } }
    var showOld by rememberSaveable { mutableStateOf(false) }

    PaginatedPerformanceList(
        title = "每次 API 调用",
        subtitle = "逐次查看调用时刻、模型、来源、总耗时与慢在哪个环节。",
        records = requests,
        keyOf = { it.id },
        onClear = onClear,
        summary = {
            FastPerformanceCard {
                Text("耗时诊断说明", fontWeight = FontWeight.SemiBold)
                Text("本地准备属于 APK；连接和上传受手机网络、代理与中转影响；等待响应头包含中转排队和模型处理，不能直接归咎于某一方。",
                    color = FastPerformanceMuted, fontSize = 12.sp)
                Text("以下网络阶段包含在模型请求总时间内，不要与总时间重复累加。测速不额外发送 API 请求。",
                    color = FastPerformanceMuted, fontSize = 11.sp)
            }
            if (older.isNotEmpty()) {
                FastPerformanceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("其他执行阶段（含旧记录） · " + older.size + " 条",
                            Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showOld = !showOld }) {
                            Text(if (showOld) "收起" else "查看")
                        }
                    }
                    Text("角色排队、气泡送达及旧版阶段并非单独 API 请求，故放在这里供比对。",
                        fontSize = 11.sp, color = FastPerformanceMuted)
                    if (showOld) {
                        older.take(24).forEach { item ->
                            FastMetric(item.stage,
                                com.jiacimu.lulu.data.ChatTimingTrace.duration(item.durationMillis))
                            Text(item.recordedAtMillis.fastDateTime() +
                                item.detail.takeIf(String::isNotBlank)?.let { " · " + it.take(90) }.orEmpty(),
                                color = FastPerformanceMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
            FastPerformanceCard {
                Text("生活累计时长", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("学习 " + durations.studyMinutes + " 分钟 · 聊天 " +
                    durations.chatMinutes + " 分钟 · 通话 " + durations.callMinutes + " 分钟",
                    color = FastPerformanceMuted, fontSize = 12.sp)
            }
        },
    ) { call ->
        var expanded by rememberSaveable(call.id) {
            mutableStateOf(call.id == requests.firstOrNull()?.id)
        }
        FastPerformanceCard(modifier = Modifier.clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(call.finishedAt.fastDateTime() + " · " + call.status,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(call.source.ifBlank { "来源未知" } + " · " +
                        call.model.ifBlank { "模型未知" },
                        fontSize = 12.sp, maxLines = 2)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开", tint = FastPerformanceMuted)
            }
            FastMetric("总耗时", call.totalMillis?.let {
                com.jiacimu.lulu.data.ChatTimingTrace.duration(it)
            } ?: "未结束")
            if (expanded) {
                HorizontalDivider(color = FastPerformanceBorder)
                FastMetric("开始", call.totalMillis?.let {
                    (call.finishedAt - it).fastDateTime()
                } ?: "未知")
                FastMetric("结束", call.finishedAt.fastDateTime())
                FastMetric("模型", call.model.ifBlank { "未知" })
                FastMetric("来源", call.source.ifBlank { "未知" })
                FastMetric("中转/服务商域名", call.provider.ifBlank { "未知" })
                val traceId = call.outcome?.traceId.orEmpty()
                val firstBubble = if (traceId.isBlank()) null else older.firstOrNull {
                    it.traceId == traceId && it.stage == "消息到首条回复入库"
                }
                if (firstBubble != null) FastMetric("整轮到首条气泡",
                    com.jiacimu.lulu.data.ChatTimingTrace.duration(firstBubble.durationMillis))
                if (call.bodyBytes > 0L) FastMetric("请求体大小",
                    String.format(java.util.Locale.getDefault(), "%.1f KB", call.bodyBytes / 1024.0))
                val httpAttempts = call.records.count { it.stage == "上传请求正文" }
                if (httpAttempts > 1) Text("这一轮含 " + httpAttempts +
                    " 次实际 HTTP 请求尝试（例如协议回退），总耗时覆盖整轮。",
                    fontSize = 12.sp, color = FastPerformanceMuted)
                val sections = listOf(
                    "Prompt" to "APK 本地准备（合计）",
                    "APK·场景准备" to "场景与会话准备",
                    "APK·记忆召回" to "本地记忆召回",
                    "APK·人物与世界资料" to "角色/世界资料读取",
                    "APK·拼装与审计" to "提示词拼装与审计",
                    "本地准备" to "APK 本地准备",
                    "模型请求" to "模型请求总阶段",
                    "连接与建链（DNS/代理/TLS）" to "连接阶段（合计）",
                    "DNS解析" to "DNS 解析",
                    "TLS握手" to "TLS 握手",
                    "上传请求正文" to "上传请求正文",
                    "等待响应头（中转站+模型）" to "等待中转/模型响应",
                    "读取响应/流式输出" to "接收回复/流式生成",
                )
                sections.forEach { (key, label) ->
                    call.records.filter { it.stage == key }.forEach { part ->
                        FastMetric(label,
                            com.jiacimu.lulu.data.ChatTimingTrace.duration(part.durationMillis))
                    }
                }
                val prep = call.duration("Prompt") ?: call.duration("本地准备")
                val memory = call.duration("APK·记忆召回")
                val actor = call.duration("APK·人物与世界资料")
                val assembly = call.duration("APK·拼装与审计")
                val connect = call.duration("连接与建链（DNS/代理/TLS）")
                val dns = call.duration("DNS解析")
                val tls = call.duration("TLS握手")
                val upload = call.duration("上传请求正文")
                val wait = call.duration("等待响应头（中转站+模型）")
                val streaming = call.duration("读取响应/流式输出")
                val hint = when {
                    call.status != "成功" -> "检查报错日志中的 HTTP 状态和传输阶段，不能假定请求未被计费。"
                    memory != null && memory >= 3_000 ->
                        "本机记忆召回较慢，排查索引查询、上下文筛选或磁盘读取。"
                    actor != null && actor >= 3_000 ->
                        "本地人物、世界状态资料组装偏慢，检查数据读取与重复拼接。"
                    assembly != null && assembly >= 3_000 ->
                        "提示词拼装和审计偏慢，排查超长文本或重复处理。"
                    prep != null && prep >= 5_000 && prep >= (call.totalMillis ?: 0L) / 3 ->
                        "APK 本地准备明显偏慢，可以继续对照上方分段耗时。"
                    dns != null && dns >= 1_000 ->
                        "DNS 解析用时较长，可能与 DNS 配置、代理路线或网络有关。"
                    tls != null && tls >= 2_000 ->
                        "TLS 握手偏慢，排查网络链路、代理或中转站连接。"
                    connect != null && connect >= 3_000 ->
                        "连接建链偏慢，检查网络、代理/VPN、DNS 或 TLS。"
                    upload != null && upload >= 3_000 ->
                        "上传偏慢，检查网络上行和请求体积。"
                    wait != null && wait >= 5_000 ->
                        "等待服务端响应偏慢，需要对照中转站日志区分排队与模型推理。"
                    streaming != null && streaming >= 5_000 ->
                        "读取/流式输出耗时较长，可能与模型持续生成或传输有关。"
                    else -> "未发现单一明显瓶颈，详情可与中转站请求日志对照。"
                }
                Text("排查提示：" + hint, fontSize = 12.sp, color = FastPerformanceMuted)
                Text("请求编号 " + call.id.takeLast(12), fontSize = 11.sp,
                    color = FastPerformanceMuted)
            }
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
private val fastDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun Long.fastTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(fastTimeFormatter)
private fun Long.fastDateTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(fastDateFormatter)
private fun Long.fastTokens(): String = when {
    this >= 1_000_000L -> String.format(java.util.Locale.getDefault(), "%.2fM", this / 1_000_000.0)
    this >= 1_000L -> String.format(java.util.Locale.getDefault(), "%.1fK", this / 1_000.0)
    else -> toString()
}
