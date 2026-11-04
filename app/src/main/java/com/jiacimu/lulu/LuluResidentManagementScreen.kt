package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.DigitalWorldExpansionStore
import com.jiacimu.lulu.data.DigitalWorldResident
import com.jiacimu.lulu.data.ResidentCharacterBridge
import com.jiacimu.lulu.data.ResidentDailyPolicy
import kotlin.math.roundToInt

/** One settings home for the existing native resident registry and daily policy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluResidentManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision by DigitalWorldExpansionStore.revisions.collectAsState()
    var agendaLimit by remember(context) { mutableIntStateOf(ResidentDailyPolicy.maxAgendaItems(context)) }
    var populationLimit by remember(context) { mutableIntStateOf(ResidentDailyPolicy.maxResidents(context)) }
    var allowNew by remember(context) { mutableStateOf(ResidentDailyPolicy.autoGenerationEnabled(context)) }
    var selectedToDelete by remember { mutableStateOf<DigitalWorldResident?>(null) }
    var selectedToEdit by remember { mutableStateOf<DigitalWorldResident?>(null) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(context) { DigitalWorldExpansionStore.initialize(context) }
    val residents = remember(revision) { DigitalWorldExpansionStore.residents() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("居民管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ResidentManagementCard {
                    Text("居民数量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("目前 " + residents.size + " 位 · 最多自动增加到 " + populationLimit + " 位")
                    Slider(
                        value = populationLimit.toFloat(),
                        onValueChange = {
                            val count = it.roundToInt().coerceIn(0, ResidentDailyPolicy.MAX_RESIDENTS)
                            if (count != populationLimit && ResidentDailyPolicy.setMaxResidents(context, count))
                                populationLimit = count
                        },
                        valueRange = 0f..ResidentDailyPolicy.MAX_RESIDENTS.toFloat(),
                        steps = ResidentDailyPolicy.MAX_RESIDENTS - 1,
                    )
                    Text("调低上限不会删除现有居民；下方可以逐个删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("允许自动新增居民", fontWeight = FontWeight.Medium)
                            Text("关闭后，系统与模型都不能再自动扩充居民数量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = allowNew, onCheckedChange = { enabled ->
                            if (ResidentDailyPolicy.setAutoGenerationEnabled(context, enabled))
                                allowNew = enabled
                        })
                    }
                }
            }
            item {
                ResidentManagementCard {
                    Text("每日自主行程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("每位居民每天最多安排 " + agendaLimit + " 项")
                    Slider(
                        value = agendaLimit.toFloat(),
                        onValueChange = {
                            val count = it.roundToInt().coerceIn(0, ResidentDailyPolicy.MAX_AGENDA_ITEMS)
                            if (count != agendaLimit && ResidentDailyPolicy.setMaxAgendaItems(context, count))
                                agendaLimit = count
                        },
                        valueRange = 0f..ResidentDailyPolicy.MAX_AGENDA_ITEMS.toFloat(),
                        steps = ResidentDailyPolicy.MAX_AGENDA_ITEMS - 1,
                    )
                    Text("只限制新的每日排程，已保存的行程和即时回信不会被删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text("已有居民", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (residents.isEmpty()) Text("目前没有居民。可以关闭自动新增，保持空世界。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(residents, key = { it.id }) { resident ->
                ResidentManagementCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(resident.name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(listOf(resident.gender.ifBlank { "性别未设定" }, resident.identity)
                                .filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (resident.temperament.isNotBlank()) Text(resident.temperament,
                                style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                        IconButton(onClick = { selectedToEdit = resident }) {
                            Icon(Icons.Outlined.Edit, "编辑" + resident.name + "的人设")
                        }
                        IconButton(onClick = { selectedToDelete = resident }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除" + resident.name,
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    selectedToEdit?.let { resident ->
        ResidentProfileEditDialog(
            resident = resident,
            onDismiss = { selectedToEdit = null },
            onSaved = {
                ResidentCharacterBridge.sync(context)
                selectedToEdit = null
                notice = "居民档案已保存"
            },
        )
    }
    selectedToDelete?.let { resident ->
        AlertDialog(
            onDismissRequest = { selectedToDelete = null },
            title = { Text("删除居民「" + resident.name + "」？") },
            text = {
                Text("这位居民会退出当前世界与居民目录，不再有新的自主日程。已经发生的聊天和其他角色的共同经历仍保留，不能一键撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        check(DigitalWorldExpansionStore.removeResident(resident.id)) { "居民不存在或已删除" }
                    }.onSuccess {
                        notice = "已删除「" + resident.name + "」"
                    }.onFailure { error ->
                        notice = error.message ?: "删除失败"
                    }
                    selectedToDelete = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { selectedToDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ResidentManagementCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
