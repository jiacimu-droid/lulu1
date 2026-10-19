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
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.SharedTimelineEvent
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
    var showHistory by remember { mutableStateOf(false) }
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
                .filter { it.dueAt.atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate &&
                    it.status != "cancelled" }
    }
    val events = remember(selectedCharacterId, selectedDate, characters, showHistory) {
        if (!showHistory || selectedCharacterId.isBlank()) emptyList() else
            SharedExperienceTimeline.all(selectedCharacterId)
                .filter { it.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate }
                .filter(::isScheduleActivity)
                .sortedBy(SharedTimelineEvent::occurredAt)
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
            TabRow(selectedTabIndex = if (showHistory) 1 else 0) {
                Tab(selected = !showHistory, onClick = { showHistory = false },
                    text = { Text("自己的安排") })
                Tab(selected = showHistory, onClick = { showHistory = true },
                    text = { Text("经历") })
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 19.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!showHistory) {
                    if (plans.isEmpty()) item {
                        Text("这一天还没有安排。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 30.dp))
                    }
                    items(plans, key = { it.id }) { plan ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.Top) {
                                Text(plan.dueAt.atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(55.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(plan.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    Text(plan.intent, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (plan.status == "due") "已到时间 · 等待本人选择"
                                        else "本人安排 · 等待到时",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { cancelId = plan.id }) {
                                    Icon(Icons.Outlined.Close, "取消安排",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    if (events.isEmpty()) item {
                        Text("这一天还没有留下活动记录。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 30.dp))
                    }
                    items(events, key = SharedTimelineEvent::id) { event ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(event.occurredAt.atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                                    fontSize = 12.sp, modifier = Modifier.width(56.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(event.channel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(event.content, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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

private fun isScheduleActivity(event: SharedTimelineEvent): Boolean =
    event.channel == "角色日程" ||
        event.id.startsWith("game-raw-") ||
        event.channel.startsWith("共同阅读《") ||
        event.channel.startsWith("独自阅读《") ||
        event.channel.startsWith("群聊电话·") ||
        event.channel == "私人日记"
