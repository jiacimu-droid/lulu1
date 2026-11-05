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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluResidentManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) { DigitalWorldExpansionStore.initialize(context); true }
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
                    })
                Text("包含每日规划、到点决策、社交来信回应、记忆提取及格式补正。记忆在额度用尽时保留原始消息等待后续整理，不会超额收费；设为 0 会暂停该居民的所有新 AI 请求。",
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
                Text("每项未来行程到点要再用一次 AI 决策；实际新排行程会随今日剩余请求额度自动减少。一次规划内的即时动作不额外调用，旧日程不会被抹除。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { HorizontalDivider() }
            items(residents, key = { it.id }) { person ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(person.name, fontWeight = FontWeight.Bold)
                        Text(listOf(person.gender, person.identity).filter(String::isNotBlank).joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(person.temperament.ifBlank { "尚未补充性格" }, maxLines = 2, fontSize = 12.sp)
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
