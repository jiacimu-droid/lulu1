package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudyPomodoroScreen(
    state: StudyState,
    store: PostgraduateExamStore,
    onBack: () -> Unit,
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

    Scaffold(
        containerColor = StudyDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text("番茄钟", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyDesign.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StudyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = StudyDesign.wheatSoft,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Timer, null, tint = StudyDesign.ink)
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    active && state.pomodoro.running -> "正在专注"
                                    active -> "已暂停"
                                    completed -> "本轮已结束"
                                    else -> "设置这一轮"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                            )
                            if (active || completed) Text(activeTask, color = StudyDesign.muted, fontSize = 12.sp)
                        }
                        if (active || completed) {
                            Text(
                                "%02d:%02d".format(remaining / 60, remaining % 60),
                                fontWeight = FontWeight.Black,
                                fontSize = 25.sp,
                            )
                        }
                    }

                    if (!active && !completed) {
                        OutlinedTextField(
                            value = task,
                            onValueChange = { task = it.take(200) },
                            label = { Text("本次任务") },
                            placeholder = { Text("例如：刑法第14章课程") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { minutesText = it.filter(Char::isDigit).take(3) },
                            label = { Text("专注分钟") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    } else {
                        StudyProgress(progress)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("角色开场消息", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("开始时由学习陪同角色在私聊里说一句话；结束时不再自动发消息。", color = StudyDesign.muted, fontSize = 11.sp)
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
        }
    }
}
