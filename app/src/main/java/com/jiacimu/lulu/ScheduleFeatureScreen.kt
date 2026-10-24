package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.RoleDayPlan
import com.jiacimu.lulu.data.CommitmentTask
import com.jiacimu.lulu.data.RoleDayScheduleStore
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.isActive
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.LexiconStatus
import java.time.LocalDate
import java.time.Instant
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
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
    var deletePlanId by remember { mutableStateOf<String?>(null) }
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
    val commitments by CommitmentTaskStore.tasks.collectAsState()
    val promiseNotes by LuluRepositories.lexicon.observeEntries(selectedCharacterId, LexiconSection.Promise)
        .collectAsState(initial = emptyList())
    val tasks = commitments.filter { task ->
        if (task.characterId != selectedCharacterId) false
        else if (task.repeat == "daily" && task.dueAt != null && task.status.isActive()) {
            // One repeating record, visible on each relevant day rather than
            // creating a separate stored task for every date.
            !selectedDate.isBefore(task.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()) &&
                !selectedDate.isBefore(task.dueAt.atZone(ZoneId.systemDefault()).toLocalDate())
        } else {
            val targetDate = task.dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
                ?: task.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
            targetDate == selectedDate
        }
    }
    val linkedNotes = commitments.filter { it.characterId == selectedCharacterId }.mapNotNull { it.lexiconEntryId }.toSet()
    val legacyPromises = promiseNotes.filter { entry -> entry.characterId == selectedCharacterId && entry.id !in linkedNotes &&
        (if (entry.status == LexiconStatus.Active) selectedDate == LocalDate.now()
            else entry.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val weekStart = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val week = (0..6).map { weekStart.plusDays(it.toLong()) }
    val allPlans = remember(context, selectedCharacterId, revisions) {
        if (selectedCharacterId.isBlank()) emptyList()
        else RoleDayScheduleStore.plans(context, selectedCharacterId)
    }
    val timeline = (plans.map { ScheduleTimelineItem("plan:" + it.id, it.dueAt, plan = it) } +
        tasks.map { ScheduleTimelineItem("task:" + it.id, it.dueAt ?: Instant.MAX, task = it) })
        .sortedBy { it.time }
    val pending = plans.count { it.status !in setOf("completed", "cancelled") } +
        tasks.count { it.status.isActive() } +
        legacyPromises.count { it.status == LexiconStatus.Active }
    val total = plans.size + tasks.size + legacyPromises.size

    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("日程", fontWeight = FontWeight.SemiBold, fontSize = 21.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    if (!personal) Box {
                        TextButton(onClick = { characterMenuExpanded = true }) {
                            Text(name.ifBlank { "选择角色" }, color = Color(0xFF49494D))
                            Icon(Icons.Outlined.KeyboardArrowDown, "选择角色", Modifier.size(18.dp),
                                tint = Color(0xFF77777D))
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
                    } else Text(name, fontSize = 13.sp, color = Color(0xFF7A7A7E),
                        modifier = Modifier.padding(end = 16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item(key = "calendar") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月")),
                            modifier = Modifier.weight(1f), fontSize = 12.sp,
                            color = Color(0xFF85858B))
                        IconButton(onClick = { selectedDate = selectedDate.minusWeeks(1) }) {
                            Icon(Icons.Outlined.ChevronLeft, "上一周", tint = Color(0xFF444448))
                        }
                        IconButton(onClick = { selectedDate = selectedDate.plusWeeks(1) }) {
                            Icon(Icons.Outlined.ChevronRight, "下一周", tint = Color(0xFF444448))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(selectedDate.format(DateTimeFormatter.ofPattern("M月d日")),
                                fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1D1D1F))
                            Text(
                                (if (selectedDate == today) "今天 · " else "") +
                                    selectedDate.format(DateTimeFormatter.ofPattern("EEEE")),
                                fontSize = 13.sp, color = Color(0xFF89898E))
                        }
                        if (selectedDate != today) TextButton(onClick = { selectedDate = today }) {
                            Text("回到今天", fontSize = 12.sp, color = Color(0xFF27272B))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { day ->
                            val selected = day == selectedDate
                            val marked = allPlans.any { it.dueAt.atZone(zone).toLocalDate() == day } ||
                                commitments.any { it.characterId == selectedCharacterId &&
                                    it.dueAt?.atZone(zone)?.toLocalDate() == day }
                            Column(
                                modifier = Modifier.weight(1f).heightIn(min = 69.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(if (selected) Color(0xFF29292C) else Color(0xFFF7F7F7))
                                    .clickable { selectedDate = day }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("一二三四五六日"[day.dayOfWeek.value - 1].toString(),
                                    fontSize = 11.sp,
                                    color = if (selected) Color.White.copy(alpha = 0.7f) else Color(0xFF919197))
                                Text(day.dayOfMonth.toString(), fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else Color(0xFF313135))
                                Box(Modifier.size(4.dp).background(
                                    if (marked) (if (selected) Color.White else Color(0xFF77777D))
                                    else Color.Transparent, CircleShape))
                            }
                        }
                    }
                    Surface(shape = RoundedCornerShape(17.dp), color = Color(0xFFF7F7F7)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("共 " + total + " 项",
                                modifier = Modifier.weight(1f), fontSize = 13.sp,
                                color = Color(0xFF49494D), fontWeight = FontWeight.Medium)
                            Text("待处理 " + pending, fontSize = 12.sp, color = Color(0xFF7A7A7E))
                            Text("已结束 " + (total - pending), fontSize = 12.sp,
                                color = Color(0xFF7A7A7E))
                        }
                    }
                }
            }
            if (error.isNotBlank()) item(key = "error") {
                Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 5.dp), fontSize = 12.sp)
            }
            if (timeline.isEmpty() && legacyPromises.isEmpty()) item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.EventNote, null, Modifier.size(38.dp),
                        tint = Color(0xFFB5B5B8))
                    Text("这一天还没有安排", fontSize = 15.sp,
                        color = Color(0xFF77777D))
                }
            }
            if (timeline.isNotEmpty()) {
                item(key = "timeline-title") { ScheduleSectionHeading("当天安排", timeline.size) }
                items(timeline, key = { it.key }) { row ->
                    row.task?.let { ScheduledCommitmentCard(it) }
                    row.plan?.let { plan ->
                        val personName = when {
                            plan.targetCharacterId.isNotBlank() ->
                                characters[plan.targetCharacterId]?.displayName ?: plan.targetCharacterId
                            plan.targetResidentId.isNotBlank() ->
                                com.jiacimu.lulu.data.DigitalWorldExpansionStore.residents()
                                    .firstOrNull { it.id == plan.targetResidentId }?.name ?: plan.targetResidentId
                            else -> ""
                        }
                        val placeName = com.jiacimu.lulu.data.DigitalWorldPublicPlaces.all
                            .firstOrNull { it.code == plan.locationCode }?.label ?: plan.locationCode
                        PersonalDayPlanCard(
                            plan = plan, personName = personName, placeName = placeName,
                            onCancel = { cancelId = plan.id },
                            onDelete = { deletePlanId = plan.id },
                        )
                    }
                }
            }
            if (legacyPromises.isNotEmpty()) {
                item(key = "legacy-title") { ScheduleSectionHeading("未定时约定", legacyPromises.size) }
                items(legacyPromises, key = { "promise:" + it.id }) { entry ->
                    LegacyPromiseCard(entry)
                }
            }
        }
    }
    deletePlanId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletePlanId = null },
            title = { Text("删除这条日程？") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        check(RoleDayScheduleStore.remove(context, selectedCharacterId, id)) {
                            "日程不存在"
                        }
                    }.onFailure { error = it.message ?: "删除失败" }
                    deletePlanId = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletePlanId = null }) { Text("返回") } },
        )
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



private data class ScheduleTimelineItem(
    val key: String,
    val time: Instant,
    val plan: RoleDayPlan? = null,
    val task: CommitmentTask? = null,
)

@Composable
private fun PersonalDayPlanCard(
    plan: RoleDayPlan,
    personName: String,
    placeName: String,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember(plan.id) { mutableStateOf(false) }
    var details by remember(plan.id) { mutableStateOf(false) }
    val finished = plan.status in setOf("completed", "cancelled")
    val due = plan.status == "due" || (!finished && plan.dueAt.isBefore(Instant.now()))
    val label = when {
        plan.status == "completed" -> "已完成"
        plan.status == "cancelled" -> "已取消"
        due -> "待处理"
        else -> "已安排"
    }
    ScheduleCardShell {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(58.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(plan.dueAt.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
                Text(if (plan.repeat == "daily") "每天" else "个人",
                    fontSize = 11.sp, color = Color(0xFF909096))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.title, fontSize = 15.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium, color = Color(0xFF262629))
                ScheduleStatusChip(label, warning = due, complete = finished)
                if (plan.intent.isNotBlank())
                    Text(plan.intent, fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFF737379))
                val where = listOfNotNull(
                    personName.takeIf(String::isNotBlank)?.let { "人物 · " + it },
                    placeName.takeIf(String::isNotBlank)?.let { "地点 · " + it },
                ).joinToString("  ·  ")
                if (where.isNotBlank()) Text(where, fontSize = 12.sp, color = Color(0xFF7A7A7E))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, "个人日程操作", tint = Color(0xFF6F6F73))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!finished) DropdownMenuItem(text = { Text("取消安排") },
                        onClick = { menu = false; onCancel() })
                    DropdownMenuItem(text = { Text("删除记录") },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
        if (plan.resultSummary.isNotBlank()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7F7F7)) {
                Text(plan.resultSummary, Modifier.fillMaxWidth().padding(11.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF69696F),
                    maxLines = if (details) Int.MAX_VALUE else 2)
            }
        }
        if (plan.rescheduleCount > 0 || plan.resultSummary.isNotBlank() || plan.repeat == "daily") {
            TextButton(onClick = { details = !details },
                contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (details) "收起详情" else "查看详情", fontSize = 12.sp)
            }
            if (details) {
                if (plan.repeat == "daily")
                    Text("每天重复，由角色自行选择是否调整",
                        fontSize = 12.sp, color = Color(0xFF7A7A7E))
                if (plan.rescheduleCount > 0)
                    Text("已改期 " + plan.rescheduleCount + " 次", fontSize = 12.sp,
                        color = Color(0xFF7A7A7E))
            }
        }
    }
}
