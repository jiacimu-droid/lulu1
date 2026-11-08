package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.DigitalWorldExpansionStore
import com.jiacimu.lulu.data.ResidentDailyPolicy
import com.jiacimu.lulu.data.ResidentDailyDecision
import com.jiacimu.lulu.ai.ScopedModelSelections
import java.time.Instant
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluResidentManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        DigitalWorldExpansionStore.initialize(context)
        ScopedModelSelections.initialize(context.applicationContext)
        true
    }
    val revision by DigitalWorldExpansionStore.revisions.collectAsState()
    val residents = remember(revision) { DigitalWorldExpansionStore.residents() }
    var allowNew by remember(context) { mutableStateOf(ResidentDailyPolicy.allowNewResidents(context)) }
    var maximum by remember(context) { mutableIntStateOf(ResidentDailyPolicy.maxResidents(context)) }
    var calls by remember(context) { mutableIntStateOf(ResidentDailyPolicy.maxModelDecisions(context)) }
    var agenda by remember(context) { mutableIntStateOf(ResidentDailyPolicy.maxAgendaItems(context)) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("居民管理") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("现有居民：" + residents.size + " 位", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动生成新居民", fontWeight = FontWeight.SemiBold)
                        Text("默认关闭；不再自动增员和调用世界扩张提案。现有人物、聊天和历史保留。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(checked = allowNew, onCheckedChange = { next ->
                        if (ResidentDailyPolicy.setAllowNewResidents(context, next)) allowNew = next
                    })
                }
            }
            item {
                Text("人数上限：" + maximum + "（不自动删除已有居民）")
                Slider(value = maximum.toFloat(), valueRange = 0f..ResidentDailyPolicy.MAX_RESIDENTS.toFloat(),
                    steps = ResidentDailyPolicy.MAX_RESIDENTS - 1,
                    onValueChange = { next ->
                        val count = next.roundToInt().coerceIn(0, ResidentDailyPolicy.MAX_RESIDENTS)
                        if (count != maximum && ResidentDailyPolicy.setMaxResidents(context, count)) maximum = count
                    })
            }
            item {
                Text("每位居民每日 AI 请求总上限：" + calls + " 次")
                Slider(value = calls.toFloat(), valueRange = 0f..ResidentDailyPolicy.MAX_MODEL_DECISIONS.toFloat(),
                    steps = ResidentDailyPolicy.MAX_MODEL_DECISIONS - 1,
                    onValueChange = { next ->
                        val count = next.roundToInt().coerceIn(0, ResidentDailyPolicy.MAX_MODEL_DECISIONS)
                        if (count != calls && ResidentDailyPolicy.setMaxModelDecisions(context, count)) calls = count
                    },
                    onValueChangeFinished = {
                        // A raised cap after 08:00 may make today's previously
                        // skipped plan affordable. Reuse the existing catch-up
                        // scheduler; never auto-create a new paid decision.
                        ResidentDailyDecision.schedule(context.applicationContext)
                    })
                Text("这是新模型请求的硬上限：规划、到点决策、临时社交、记忆及格式补正均计入；0 会停止新请求。已排定行程优先占用预算，普通社交通知可在到点决定中顺便处理。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("每位居民每日新排行程：" + agenda + " 项")
                Slider(value = agenda.toFloat(), valueRange = 0f..ResidentDailyPolicy.MAX_AGENDA_ITEMS.toFloat(),
                    steps = ResidentDailyPolicy.MAX_AGENDA_ITEMS - 1,
                    onValueChange = { next ->
                        val count = next.roundToInt().coerceIn(0, ResidentDailyPolicy.MAX_AGENDA_ITEMS)
                        if (count != agenda && ResidentDailyPolicy.setMaxAgendaItems(context, count)) agenda = count
                    })
                val minimum = agenda + 1
                Text("基础模型次数＝1 次本人规划＋每个到点行程 1 次决策。当前选择 ${agenda} 项，若希望全部排入，通常至少要 ${minimum} 次每日额度。私聊、群聊、世界行动可在已有一次决定中作为独立步骤同时执行。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (agenda > 0 && calls < minimum) {
                    Text("目前每日额度仅 ${calls} 次，不足以保证 ${agenda} 个到点行程；不自动增加收费调用。遇到额外格式补正/临时来信，还需另外预留额度。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    if (minimum <= ResidentDailyPolicy.MAX_MODEL_DECISIONS) {
                        TextButton(onClick = {
                            if (ResidentDailyPolicy.setMaxModelDecisions(context, minimum)) {
                                calls = minimum
                                ResidentDailyDecision.schedule(context.applicationContext)
                            }
                        }) { Text("明确调整：每天每位居民最多 ${minimum} 次") }
                    }
                }
            }
            item {
                if (ScopedModelSelections.selectedArchiveId(ScopedModelSelections.RESIDENT_DAILY) == null) {
                    Text("尚未选择居民每日规划模型，今天可能不会生成任何行程。请先到角色设置里配置居民模型。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            item { HorizontalDivider() }
            items(residents, key = { it.id }) { person ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(person.name, fontWeight = FontWeight.Bold)
                        Text(listOf(person.gender, person.identity).filter(String::isNotBlank).joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(person.temperament.ifBlank { "尚未补充性格" }, maxLines = 2, fontSize = 12.sp)
                        val roleId = "resident:${person.id}"
                        val today = Instant.now()
                        val used = ResidentDailyPolicy.modelDecisionsUsed(context, roleId, today)
                        val plan = ResidentDailyDecision.planFor(context, person.id, today)
                        val attempted = ResidentDailyDecision.attemptedToday(context, person.id, today)
                        val recovery = ResidentDailyDecision.dayRecovery(context, person.id, today)
                        val progress = when {
                            plan != null -> "今日规划已保存"
                            recovery != null -> "今日规划需恢复：${recovery.phase}"
                            attempted -> "今日规划已尝试，但未保存有效计划"
                            else -> "今天尚未开始规划"
                        }
                        Text("今日模型额度：${used} / ${calls} 次 · ${progress}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val dueLeft = ResidentDailyPolicy.remainingTodayAgendaDecisions(context, roleId, today)
                        Text("今天仍待决定的已排定行程：${dueLeft} 项",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { editingId = person.id }) { Text("编辑档案") }
                            TextButton(onClick = { deletingId = person.id; error = "" }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    residents.firstOrNull { it.id == editingId }?.let { person ->
        ResidentProfileEditDialog(person, onDismiss = { editingId = null }, onSaved = { editingId = null })
    }
    residents.firstOrNull { it.id == deletingId }?.let { person ->
        AlertDialog(onDismissRequest = { deletingId = null },
            title = { Text("删除居民“" + person.name + "”？") },
            text = {
                Column {
                    Text("从数字世界移除并停用其角色身份。真实发送过的聊天和他人的共同回忆不会被抹除，已删除模板不再自动生成。")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { check(DigitalWorldExpansionStore.removeResident(person.id)) }
                        .onSuccess { deletingId = null }
                        .onFailure { error = it.message ?: "删除失败" }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("取消") } })
    }
}
