package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.RoleDayScheduleStore
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleFeatureScreen(
    onBack: () -> Unit,
    initialCharacterId: String = "",
    personal: Boolean = false,
) {
    val context = LocalContext.current
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val revisions by RoleDayScheduleStore.changes.collectAsState()
    val sortedCharacters = remember(characters) { characters.values.sortedBy { it.displayName } }
    var selectedCharacterId by remember(initialCharacterId) {
        mutableStateOf(initialCharacterId.ifBlank { characters.keys.firstOrNull().orEmpty() })
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var characterMenuExpanded by remember { mutableStateOf(false) }
    var cancelId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(characters.keys, selectedCharacterId) {
        if (selectedCharacterId !in characters && characters.isNotEmpty()) {
            selectedCharacterId = characters.keys.first()
        }
    }
    val plans = remember(selectedCharacterId, selectedDate, revisions) {
        if (selectedCharacterId.isBlank()) emptyList() else
            RoleDayScheduleStore.plans(context, selectedCharacterId)
                .filter { it.dueAt.atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate }
    }
    val name = characters[selectedCharacterId]?.displayName.orEmpty()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("日程", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (!personal) Box {
                        TextButton(onClick = { characterMenuExpanded = true }) {
                            Text(name.ifBlank { "选择角色" })
                            Icon(Icons.Outlined.KeyboardArrowDown, "选择角色", Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = characterMenuExpanded,
                            onDismissRequest = { characterMenuExpanded = false }) {
                            sortedCharacters.forEach { character ->
                                DropdownMenuItem(text = { Text(character.displayName) },
                                    onClick = {
                                        selectedCharacterId = character.characterId
                                        characterMenuExpanded = false
                                    })
                            }
                        }
                    } else Text(name, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp))
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                    Icon(Icons.Outlined.ChevronLeft, "前一天")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selectedDate.format(DateTimeFormatter.ofPattern("M月d日  EEEE")),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (selectedDate == LocalDate.now()) "今天" else selectedDate.year.toString(),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { selectedDate = selectedDate.plusDays(1) }) {
                    Icon(Icons.Outlined.ChevronRight, "后一天")
                }
            }
            Text("只看本人亲自安排的事 · 执行依据保留在原始时间线",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 19.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (plans.isEmpty()) item {
                    Text("这一天还没有本人安排的活动。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 30.dp))
                }
                items(plans, key = { it.id }) { plan ->
                    val finished = plan.status in setOf("completed", "cancelled")
                    val stateLabel = when (plan.status) {
                        "completed" -> "已完成 · 有真实执行依据"
                        "cancelled" -> "本人已取消"
                        "due" -> "时间已到 · 待本人决定"
                        else -> "本人安排 · 等待到时"
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (finished) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.Top) {
                            Text(plan.dueAt.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm")),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(56.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(plan.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(plan.intent, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stateLabel, fontSize = 11.sp,
                                    color = if (plan.status == "completed") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (plan.rescheduleCount > 0) Text("曾由本人改期 ${plan.rescheduleCount} 次",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (plan.resultSummary.isNotBlank()) {
                                    Text(plan.resultSummary, fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (!finished) IconButton(onClick = { cancelId = plan.id }) {
                                Icon(Icons.Outlined.Close, "取消安排",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Icon(
                                if (plan.status == "completed") Icons.Outlined.CheckCircleOutline
                                    else Icons.Outlined.RemoveCircleOutline,
                                stateLabel, modifier = Modifier.padding(8.dp).size(19.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    cancelId?.let { id ->
        AlertDialog(
            onDismissRequest = { cancelId = null },
            title = { Text("取消这项安排？") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { RoleDayScheduleStore.cancel(context, selectedCharacterId, id) }
                        .onFailure { error = it.message ?: "取消失败" }
                    cancelId = null
                }) { Text("取消安排") }
            },
            dismissButton = { TextButton(onClick = { cancelId = null }) { Text("返回") } },
        )
    }
}

