package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.LocalDate
import kotlin.math.ceil

@Composable
internal fun StudyTodayScreenV2(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenConversation: (String) -> Unit,
) {
    var newTask by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.date == today.toString() }
    val completed = todayTasks.count(StudyTask::completed)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            PomodoroTodayCard(
                state = state,
                store = store,
                onOpenConversation = onOpenConversation,
            )
        }
        item {
            StudyCard {
                OutlinedTextField(
                    value = newTask,
                    onValueChange = { newTask = it },
                    label = { Text("添加今日待办") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    enabled = newTask.isNotBlank(),
                    onClick = { store.addTask(newTask); newTask = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("添加") }
            }
        }
        if (todayTasks.isEmpty()) {
            item { StudyCard { Text("今天还没有待办。", color = StudyDesign.muted) } }
        } else {
            items(todayTasks, key = StudyTask::id) { task -> SelfDirectedTaskRow(task, store) }
        }
        item {
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$completed/${todayTasks.size}", fontWeight = FontWeight.Bold)
                }
                StudyProgress(if (todayTasks.isEmpty()) 0f else completed.toFloat() / todayTasks.size)
                Text("今日待办", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PomodoroTodayCard(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenConversation: (String) -> Unit,
) {
    val companion by PomodoroCompanionSessions.store.state.collectAsState()
    var task by remember(companion.task) { mutableStateOf(companion.task) }
    var minutesText by remember(state.pomodoro.selectedMinutes) {
        mutableStateOf(state.pomodoro.selectedMinutes.toString())
    }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }

    val active = companion.activeSessionId.isNotBlank() && !companion.completionHandled
    val completed = companion.activeSessionId.isNotBlank() && companion.completionHandled
    val remaining = state.pomodoro.remainingSeconds.coerceAtLeast(0)
    val total = (state.pomodoro.selectedMinutes * 60).coerceAtLeast(1)
    val progress = 1f - remaining.toFloat() / total.toFloat()
    val activeTask = companion.activeTask.ifBlank { companion.task }

    fun privateConversationId(): String {
        val characterId = companion.activeCharacterId.ifBlank { state.profile.selectedCharacterId }
        val character = MigratedDomainStores.characters.get(characterId)
        return MigratedDomainStores.chat.ensureConversation(characterId, character.displayName).id
    }

    fun startPomodoro() {
        val minutes = minutesText.toIntOrNull()?.coerceIn(1, 180)
        val cleanTask = task.trim()
        if (minutes == null) {
            notice = "请输入1—180分钟"
            noticeError = true
            return
        }
        if (cleanTask.isBlank()) {
            notice = "请先写下本次任务"
            noticeError = true
            return
        }

        if (completed) {
            store.resetPomodoro()
            PomodoroCompanionSessions.clearSession()
        }
        val characterId = state.profile.selectedCharacterId
        val character = MigratedDomainStores.characters.get(characterId)
        val conversationId = MigratedDomainStores.chat.ensureConversation(characterId, character.displayName).id
        val messageStart = MigratedDomainStores.chat.messages(conversationId).value.size

        PomodoroCompanionSessions.store.updateTask(cleanTask)
        PomodoroCompanionSessions.beginSession(characterId, cleanTask, messageStart)
        store.setPomodoroDuration(minutes)
        if (!store.state.value.pomodoro.running) store.togglePomodoro()
        notice = "番茄钟已开始"
        noticeError = false
        PomodoroCompanionSessions.requestOpeningLine(store.state.value)
    }

    fun finishEarly() {
        val timer = store.state.value.pomodoro
        val elapsedSeconds = (timer.selectedMinutes * 60 - timer.remainingSeconds).coerceAtLeast(0)
        val actualMinutes = ceil(elapsedSeconds / 60.0).toInt()
        if (timer.running) store.togglePomodoro()

        if (actualMinutes <= 0) {
            store.resetPomodoro()
            PomodoroCompanionSessions.completeSession(
                studyStore = store,
                actualMinutes = 0,
                reason = "用户在未满1分钟时结束",
                rewardMessage = "本次不记录奖励",
                recordExperience = false,
            )
            notice = "未满1分钟，本次不记录"
        } else {
            val originalMinutes = timer.selectedMinutes
            store.setPomodoroDuration(actualMinutes.coerceIn(1, 180))
            val reward = store.completePomodoro(actualMinutes)
            store.setPomodoroDuration(originalMinutes)
            PomodoroCompanionSessions.completeSession(
                studyStore = store,
                actualMinutes = actualMinutes,
                reason = "用户提前结束",
                rewardMessage = reward,
                recordExperience = true,
            )
            notice = "已按实际 $actualMinutes 分钟结算"
        }
        noticeError = false
    }

    StudyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = StudyDesign.wheatSoft,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Timer, null, tint = StudyDesign.ink)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("番茄钟", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(
                    when {
                        active && state.pomodoro.running -> "进行中"
                        active -> "已暂停"
                        completed -> "本轮已结束"
                        else -> "准备开始"
                    },
                    color = StudyDesign.muted,
                    fontSize = 12.sp,
                )
            }
            if (active || completed) {
                Text(
                    "%02d:%02d".format(remaining / 60, remaining % 60),
                    fontWeight = FontWeight.Black,
                    fontSize = 23.sp,
                )
            }
        }

        if (!active && !completed) {
            OutlinedTextField(
                value = task,
                onValueChange = { task = it.take(200) },
                label = { Text("本次任务") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = minutesText,
                onValueChange = { minutesText = it.filter(Char::isDigit).take(3) },
                label = { Text("分钟") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            Text(activeTask, fontWeight = FontWeight.SemiBold)
            StudyProgress(progress)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("角色开场与结束消息", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("直接发送到角色私聊", color = StudyDesign.muted, fontSize = 11.sp)
            }
            Switch(
                checked = companion.automaticDialogueEnabled,
                onCheckedChange = PomodoroCompanionSessions.store::updateAutomaticDialogue,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("角色语音", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Switch(
                checked = state.pomodoro.voiceEnabled,
                onCheckedChange = { store.togglePomodoroVoice() },
            )
        }

        when {
            !active && !completed -> {
                Button(onClick = ::startPomodoro, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("开始番茄钟")
                }
            }
            active -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { store.togglePomodoro() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (state.pomodoro.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.pomodoro.running) "暂停" else "继续")
                    }
                    Button(onClick = ::finishEarly, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Stop, null)
                        Spacer(Modifier.width(4.dp))
                        Text("结束")
                    }
                }
                OutlinedButton(
                    onClick = { onOpenConversation(privateConversationId()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null)
                    Spacer(Modifier.width(6.dp))
                    Text("去角色私聊")
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onOpenConversation(privateConversationId()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("查看私聊") }
                    Button(
                        onClick = {
                            store.resetPomodoro()
                            PomodoroCompanionSessions.clearSession()
                            notice = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("下一轮") }
                }
            }
        }

        StudyMessage(notice, noticeError)
    }
}

@Composable
internal fun StudyPlanScreenV2(state: StudyState, store: PostgraduateExamStore) {
    var range by remember { mutableStateOf(StudyPlanRange.Weekly) }
    var title by remember { mutableStateOf("") }
    val items = state.planItems.filter { it.range == range }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudyPlanRange.entries.forEach { item ->
                        FilterChip(
                            selected = item == range,
                            onClick = { range = item },
                            label = { Text(if (item == StudyPlanRange.Weekly) "周计划" else "月计划") },
                        )
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("添加${if (range == StudyPlanRange.Weekly) "周" else "月"}计划", fontWeight = FontWeight.Bold)
                OutlinedTextField(title, { title = it }, label = { Text("计划内容") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { store.addPlanItem(range, title, ""); title = "" },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存计划") }
            }
        }
        if (items.isEmpty()) item { StudyCard { Text("当前没有计划", color = StudyDesign.muted) } }
        items(items, key = StudyPlanItem::id) { item ->
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { store.deletePlanItem(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
                }
                if (item.note.isNotBlank()) Text(item.note, color = StudyDesign.muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun SelfDirectedTaskRow(task: StudyTask, store: PostgraduateExamStore) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StudyDesign.card,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, StudyDesign.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = task.completed, onCheckedChange = { store.toggleTask(task.id) })
            Text(task.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { store.deleteTask(task.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
        }
    }
}
