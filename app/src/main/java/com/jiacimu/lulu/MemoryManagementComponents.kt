package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.data.InMemoryLexiconRepository
import com.jiacimu.lulu.data.InMemoryWorldBookRepository
import com.jiacimu.lulu.data.CoreMemoryDelivery
import com.jiacimu.lulu.data.CoreMemoryInjectionAudit
import com.jiacimu.lulu.data.LocalMemoryRepository
import com.jiacimu.lulu.data.MemorySemanticConsolidation
import com.jiacimu.lulu.data.CharacterDevelopmentRuntime
import com.jiacimu.lulu.data.MemoryReviewPhase
import com.jiacimu.lulu.data.MemoryValidityStore
import com.jiacimu.lulu.data.LocalPerformanceRepository
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val FeaturePaper = com.jiacimu.lulu.design.LuluColors.Paper
private val FeatureCard = Color.White
private val FeatureBlueGray = com.jiacimu.lulu.design.LuluColors.Muted
private val FeatureBorder = com.jiacimu.lulu.design.LuluColors.Border

@Composable
internal fun CoreMemoryInjectionCard(characterId: String, memories: List<MemoryEntry>) {
    val active = remember(characterId, memories) { CoreMemoryDelivery.active(characterId) }
    var auditRevision by remember(characterId) { mutableIntStateOf(0) }
    val recentAudit = remember(characterId, auditRevision) { CoreMemoryInjectionAudit.recent(characterId) }
    val latest = recentAudit.firstOrNull()
    val injected = latest?.coreIds.orEmpty().toSet()
    FeatureCardBox {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("核心记忆 · 常驻上下文", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${active.size} 条有效核心记忆，不占普通召回名额",
                    color = FeatureBlueGray, fontSize = 12.sp)
            }
            Icon(Icons.Outlined.PushPin, contentDescription = null, tint = FeatureBlueGray)
        }
        Text("可点击下方记忆的「取消核心」调整；也可以点右上角 + 创建并打开核心记忆开关。已删除、已更正或来源失效的旧条目不会继续注入。",
            color = FeatureBlueGray, fontSize = 12.sp, lineHeight = 18.sp)
        HorizontalDivider(color = FeatureBorder)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("最近一次核心记忆下发凭据", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { auditRevision++ }) { Text("刷新", fontSize = 12.sp) }
        }
        if (latest == null) {
            Text("尚无可核对的成功请求。更新后先让角色聊天一次再回来查看；这里不会把检索候选或失败请求冒充已注入。",
                color = FeatureBlueGray, fontSize = 12.sp)
        } else {
            Text(latest.at.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter),
                color = FeatureBlueGray, fontSize = 12.sp)
            Text("${latest.source} · ${latest.contextMode} · 当次 ${latest.coreIds.size} 条",
                fontSize = 12.sp)
            val currentIds = active.mapTo(mutableSetOf()) { it.id }
            val newIds = currentIds - injected
            if (newIds.isNotEmpty()) Text("${newIds.size} 条当前有效核心记忆尚未在最近这次请求中出现；新建后可再发起一次角色交互验证。",
                color = FeatureBlueGray, fontSize = 12.sp)
            if (injected.isNotEmpty()) {
                Text("当次已注入：", color = FeatureBlueGray, fontSize = 12.sp)
                latest.coreIds.forEach { id ->
                    val item = active.firstOrNull { it.id == id }
                    Text("• " + (item?.content?.take(95) ?: "历史记忆已删除／已更正／来源失效"),
                        fontSize = 12.sp, color = FeatureBlueGray)
                }
            } else Text("这次请求没有有效核心记忆可注入。", color = FeatureBlueGray, fontSize = 12.sp)
            Text("普通模型调用返回成功后记录；Agent 记录只证明上下文同步成功，不代表已经完成一次角色回答或理解全部内容。",
                fontSize = 11.sp, color = FeatureBlueGray)
        }
        val history = recentAudit.drop(1).take(4)
        if (history.isNotEmpty()) {
            Text("更早的成功请求", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            history.forEach { record ->
                Text("${record.at.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter)} · ${record.source} · ${record.coreIds.size} 条",
                    fontSize = 11.sp, color = FeatureBlueGray)
            }
        }
    }
}

@Composable
internal fun MemoryEditorDialog(
    memory: MemoryEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemoryEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var content by remember(memory.id) { mutableStateOf(memory.content) }
    var source by remember(memory.id) { mutableStateOf(memory.source) }
    var kind by remember(memory.id) { mutableStateOf(memory.kind) }
    var strength by remember(memory.id) { mutableFloatStateOf(memory.strength.toFloat()) }
    var pinned by remember(memory.id) { mutableStateOf(memory.pinned || memory.tier == com.jiacimu.lulu.core.MemoryTier.Core) }
    var recall by remember(memory.id) { mutableStateOf(memory.canRecallProactively) }
    var occurredAtText by remember(memory.id) {
        mutableStateOf(memory.occurredAt?.atZone(ZoneId.systemDefault())?.format(MemoryInputFormatter).orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加记忆" else "编辑记忆") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("记忆内容") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryKind.entries.forEach { item ->
                        FilterChip(
                            selected = kind == item,
                            onClick = { kind = item },
                            label = { Text(item.memoryKindLabel()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("来源") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = occurredAtText,
                    onValueChange = { occurredAtText = it },
                    label = { Text("发生时间（可空）") },
                    supportingText = { Text("格式：yyyy-MM-dd HH:mm；无法解析时保留为空") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("记忆强度：${strength.toInt()}/10", fontWeight = FontWeight.SemiBold)
                Slider(value = strength, onValueChange = { strength = it }, valueRange = 1f..10f, steps = 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("核心记忆 · 每轮角色上下文常驻")
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("允许角色主动回忆")
                    Switch(checked = recall, onCheckedChange = { recall = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = {
                    val occurredAt = occurredAtText.trim().takeIf(String::isNotBlank)?.let { text ->
                        runCatching {
                            java.time.LocalDateTime.parse(text, MemoryInputFormatter)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                        }.getOrNull()
                    }
                    onSave(
                        memory.copy(
                            content = content.trim(),
                            kind = kind,
                            source = source.trim().ifBlank { "手动" },
                            occurredAt = occurredAt,
                            strength = strength.toInt().coerceIn(1, 10),
                            pinned = pinned,
                            tier = if (pinned) com.jiacimu.lulu.core.MemoryTier.Core else if (kind == MemoryKind.Fact) com.jiacimu.lulu.core.MemoryTier.Stable else com.jiacimu.lulu.core.MemoryTier.Episode,
                            canRecallProactively = recall,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun MemoryMetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = FeatureBlueGray, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.weight(1f), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun MemoryKind.memoryKindLabel(): String = when (this) {
    MemoryKind.Fact -> "长期事实"
    MemoryKind.Emotion -> "情绪印象"
    MemoryKind.Timeline -> "重要经历"
}

private val MemoryDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val MemoryInputFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Configuration and diagnostics stay out of the everyday memory list. */
@Composable
internal fun MemoryManagementSheet(characterId: String, personal: Boolean, memories: List<MemoryEntry>,
    onDismiss: () -> Unit, onNotice: (String) -> Unit) {
    val repository = LuluRepositories.memory
    val policy by repository.observePolicy(characterId).collectAsState(initial = MemoryPolicy())
    var excluded by remember(policy) { mutableStateOf(policy.excludedRecentMessages.toString()) }
    var threshold by remember(policy) { mutableStateOf(policy.readableThreshold.toString()) }
    var automatic by remember(policy) { mutableStateOf(policy.autoSummarize) }
    var audit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val debug by repository.debugState.collectAsState()
    val appContext = LocalContext.current
    remember(appContext) { MemorySemanticConsolidation.initialize(appContext); true }
    val allReports by MemorySemanticConsolidation.reports.collectAsState()
    val report = allReports[characterId] ?: MemorySemanticConsolidation.report(characterId)
    val validityRevision by MemoryValidityStore.revisions.collectAsState()
    val eligible = remember(characterId, memories, validityRevision) {
        MemorySemanticConsolidation.eligibleCount(characterId)
    }
    val reviewing = report.phase == MemoryReviewPhase.Waiting || report.phase == MemoryReviewPhase.Running
    val aliases = remember(characterId, memories, validityRevision) {
        val byId = memories.associateBy(MemoryEntry::id)
        MemoryValidityStore.equivalenceSnapshot().mapNotNull { (oldId, keptId) ->
            val old = byId[oldId] ?: return@mapNotNull null
            val kept = byId[keptId] ?: return@mapNotNull null
            old.content to kept.content
        }
    }
    var showAliases by remember(characterId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("记忆管理", fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF4F6F8),
                border = BorderStroke(1.dp, FeatureBorder)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("记忆整理 · 审核记录", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("现有 ${memories.size} 条 · 有来源可审核 ${eligible} 条 · 其余 ${(memories.size - eligible).coerceAtLeast(0)} 条",
                        fontSize = 12.sp, color = FeatureBlueGray)
                    Text("经历、情绪仅核对同一事件的重复；手动记忆与无效来源不会自动归档。",
                        fontSize = 12.sp, color = FeatureBlueGray)
                    val statusText = when (report.phase) {
                        MemoryReviewPhase.Idle -> "尚无可追溯的整理报告（旧版点击结果无法确认）"
                        MemoryReviewPhase.Waiting -> "排队中"
                        MemoryReviewPhase.Running -> "正在整理 · ${report.reviewedBatches}/${report.totalBatches} 批"
                        MemoryReviewPhase.Partial -> "本轮完成一部分 · ${report.reviewedBatches}/${report.totalBatches} 批"
                        MemoryReviewPhase.Completed -> "本轮已完成 · ${report.reviewedBatches}/${report.totalBatches} 批"
                        MemoryReviewPhase.Skipped -> "本轮未调用 API"
                        MemoryReviewPhase.Failed -> "整理失败"
                        MemoryReviewPhase.Interrupted -> "上次整理中断"
                    }
                    Text(statusText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = if (report.phase == MemoryReviewPhase.Failed) MaterialTheme.colorScheme.error else FeatureBlueGray)
                    if (reviewing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (report.phase != MemoryReviewPhase.Idle) {
                        Text(report.detail, fontSize = 12.sp, lineHeight = 18.sp,
                            color = if (report.phase == MemoryReviewPhase.Failed) MaterialTheme.colorScheme.error else FeatureBlueGray)
                        Text("本轮确认归档 ${report.archived} 条 · 最近状态 ${if (report.updatedAt == Instant.EPOCH) "未知" else report.updatedAt.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter)}",
                            fontSize = 12.sp, color = FeatureBlueGray)
                    }
                    OutlinedButton(enabled = !busy && !reviewing, onClick = {
                        if (!MemorySemanticConsolidation.startManual(characterId))
                            onNotice("这个角色已有整理任务正在进行")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(when {
                            reviewing -> "后台正在审核…"
                            report.phase == MemoryReviewPhase.Partial -> "继续审核剩余批次"
                            else -> "审核重复记忆"
                        })
                    }
                    if (aliases.isNotEmpty()) {
                        TextButton(onClick = { showAliases = !showAliases }) {
                            Text(if (showAliases) "收起同义归档记录（${aliases.size}条）"
                                else "查看同义归档记录（${aliases.size}条）")
                        }
                        if (showAliases) aliases.takeLast(8).forEach { (old, kept) ->
                            Text("已归档：${old.take(90)}\n保留：${kept.take(90)}",
                                fontSize = 12.sp, lineHeight = 18.sp, color = FeatureBlueGray)
                        }
                    }
                    Text("审核使用后台记忆模型，可能产生费用。", fontSize = 11.sp, color = FeatureBlueGray)
                }
            }
            Text("每日小整理 · 每周大整理", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("每日维护、每周分批审核；相处习惯从真实经历进入角色成长。",
                fontSize = 12.sp, color = FeatureBlueGray)
            val habitRevision by CharacterDevelopmentRuntime.revisions.collectAsState()
            val latestHabits = remember(characterId, habitRevision) {
                CharacterDevelopmentRuntime.lastStatus(characterId)
            }
            Text(latestHabits.ifBlank { "习惯学习 · 暂无记录" },
                fontSize = 12.sp, color = FeatureBlueGray)
            Text("自动记忆规则", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            OutlinedTextField(excluded, { excluded = it.filter(Char::isDigit) }, singleLine = true,
                label = { Text("最近 N 条暂不总结") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(threshold, { threshold = it.filter(Char::isDigit) }, singleLine = true,
                label = { Text("每 N 条总结一次") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动整理", Modifier.weight(1f)); Switch(automatic, { automatic = it })
            }
            Button(onClick = { scope.launch {
                val saved = MemoryPolicy(excluded.toIntOrNull()?.coerceAtLeast(0) ?: policy.excludedRecentMessages,
                    threshold.toIntOrNull()?.coerceAtLeast(1) ?: policy.readableThreshold, automatic)
                if (personal) repository.updatePersonalPolicy(characterId, saved) else repository.updatePolicy(characterId, saved)
                onNotice("记忆规则已保存")
            } }, modifier = Modifier.fillMaxWidth()) { Text("保存规则") }
            if (personal) TextButton(onClick = { scope.launch { repository.followGlobalPolicy(characterId) } }) {
                Text("跟随统一设置")
            }
            OutlinedButton(enabled = !busy, onClick = { busy = true; scope.launch {
                try {
                    val removed = repository.maintain(characterId)
                    repository.flushNow(characterId)
                    onNotice(if (removed > 0) "已整理并合并 $removed 条重复记忆" else "已检查当前角色的整理队列")
                } catch (error: Exception) { onNotice(error.message ?: "整理失败") }
                finally { busy = false }
            } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在整理…" else "整理当前角色记忆") }
            Text("待提取的原始消息：${repository.pendingMessageCount(characterId)} 条", fontSize = 12.sp, color = FeatureBlueGray)
            debug.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            TextButton(onClick = { audit = !audit }) { Text(if (audit) "收起下发记录" else "查看置顶记忆下发记录") }
            if (audit) CoreMemoryInjectionCard(characterId, memories)
        }
    }
}

