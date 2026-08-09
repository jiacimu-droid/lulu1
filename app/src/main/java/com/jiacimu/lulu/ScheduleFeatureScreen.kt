package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluColors
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.SharedTimelineEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleFeatureScreen(onBack: () -> Unit) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val sortedCharacters = remember(characters) { characters.values.sortedBy { it.displayName } }
    var selectedCharacterId by remember { mutableStateOf(characters.keys.firstOrNull().orEmpty()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var characterMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(characters.keys, selectedCharacterId) {
        if (selectedCharacterId !in characters && characters.isNotEmpty()) selectedCharacterId = characters.keys.first()
    }
    val events = remember(selectedCharacterId, selectedDate, characters) {
        if (selectedCharacterId.isBlank()) emptyList() else SharedExperienceTimeline.all(selectedCharacterId)
            .filter { event -> event.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate }
            .filter(::isScheduleActivity)
            .sortedBy(SharedTimelineEvent::occurredAt)
    }
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("日程", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    Box {
                        TextButton(onClick = { characterMenuExpanded = true }) {
                            Text(characters[selectedCharacterId]?.displayName.orEmpty().ifBlank { "选择角色" }, color = LuluColors.Ink)
                            Icon(Icons.Outlined.KeyboardArrowDown, "选择角色", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = characterMenuExpanded,
                            onDismissRequest = { characterMenuExpanded = false },
                        ) {
                            sortedCharacters.forEach { character ->
                                DropdownMenuItem(
                                    text = { Text(character.displayName) },
                                    onClick = {
                                        selectedCharacterId = character.characterId
                                        characterMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "date") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                    border = BorderStroke(1.dp, LuluColors.Border),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                            Icon(Icons.Outlined.ChevronLeft, "前一天")
                        }
                        Surface(shape = RoundedCornerShape(14.dp), color = LuluColors.CardStrong) {
                            Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.padding(11.dp).size(25.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selectedDate.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(if (selectedDate == LocalDate.now()) "今天真实发生的生活" else "这一天真实发生的生活", color = LuluColors.Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { selectedDate = selectedDate.plusDays(1) }) {
                            Icon(Icons.Outlined.ChevronRight, "后一天")
                        }
                    }
                }
            }
            if (events.isEmpty()) {
                item(key = "empty") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                        border = BorderStroke(1.dp, LuluColors.Border),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("这一天还没有活动", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("角色真正玩过游戏、阅读、发动态、写日记或执行自主动作后，会按发生时间留在这里。", color = LuluColors.Muted)
                        }
                    }
                }
            } else {
                items(events, key = SharedTimelineEvent::id) { event -> ScheduleActivityCard(event) }
            }
        }
    }
}

private fun isScheduleActivity(event: SharedTimelineEvent): Boolean =
    event.channel == "角色日程" ||
        event.id.startsWith("game-raw-") ||
        event.channel.startsWith("共同阅读《") ||
        event.channel.startsWith("群聊电话·")

@Composable
private fun ScheduleActivityCard(event: SharedTimelineEvent) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Text(
                event.occurredAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                color = LuluColors.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(45.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.channel.removePrefix("角色日程").ifBlank { "真实活动" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(event.content, color = LuluColors.Ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}
