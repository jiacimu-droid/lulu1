package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import kotlin.math.ceil

private data class FocusPalette(
    val background: List<Color>,
    val topGlow: Color,
    val bottomGlow: Color,
    val panel: Color,
    val input: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val track: Color,
)

private fun StudyFocusTheme.palette(): FocusPalette = when (this) {
    StudyFocusTheme.CLOUD -> FocusPalette(
        background = listOf(Color(0xFFF4F6F6), Color(0xFFEEF3F4), Color(0xFFF2F3F1)),
        topGlow = Color.White.copy(alpha = 0.22f),
        bottomGlow = Color(0xFF5C6B7D).copy(alpha = 0.06f),
        panel = Color.White.copy(alpha = 0.34f),
        input = Color(0xFFFFF8FB).copy(alpha = 0.94f),
        accent = Color(0xFF7895A6),
        text = Color(0xFF35434D),
        muted = Color(0xFF667782),
        track = Color.White.copy(alpha = 0.48f),
    )

    StudyFocusTheme.MIDNIGHT -> FocusPalette(
        background = listOf(Color(0xFF07111F), Color(0xFF0B1729), Color(0xFF081321)),
        topGlow = Color(0xFF5EA8E8).copy(alpha = 0.18f),
        bottomGlow = Color(0xFF1C6CA5).copy(alpha = 0.10f),
        panel = Color(0xFF14243A).copy(alpha = 0.72f),
        input = Color(0xFF101D30).copy(alpha = 0.96f),
        accent = Color(0xFF8ABFE6),
        text = Color(0xFFEAF3FB),
        muted = Color(0xFFAFC3D5),
        track = Color(0xFFB8D8F2).copy(alpha = 0.14f),
    )
}

@Composable
internal fun StudyFocusMiniWindow(
    state: StudyState,
    task: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences by StudyFocusSessions.store.state.collectAsState()
    val palette = preferences.theme.palette()
    val total = (state.pomodoro.selectedMinutes * 60).coerceAtLeast(1)
    val progress = 1f - state.pomodoro.remainingSeconds.toFloat() / total

    Surface(
        onClick = onOpen,
        modifier = modifier.widthIn(min = 208.dp, max = 280.dp),
        shape = RoundedCornerShape(22.dp),
        color = palette.input,
        contentColor = palette.text,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.42f)),
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.accent.copy(alpha = 0.18f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.pomodoro.running) Icons.Outlined.Timer else Icons.Outlined.Pause,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "%02d:%02d".format(
                            state.pomodoro.remainingSeconds / 60,
                            state.pomodoro.remainingSeconds % 60,
                        ),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Black,
                        color = palette.text,
                    )
                    Text(
                        if (state.pomodoro.running) "番茄钟进行中" else "番茄钟已暂停",
                        color = palette.muted,
                        fontSize = 11.sp,
                    )
                }
                Icon(Icons.Outlined.OpenInFull, "返回番茄钟", tint = palette.muted, modifier = Modifier.size(18.dp))
            }
            Text(
                task.ifBlank { preferences.activeTask.ifBlank { preferences.task } },
                color = palette.text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = palette.accent,
                trackColor = palette.track,
            )
        }
    }
}

@Composable
internal fun StudyFocusCompleteScreen(
    state: StudyState,
    store: PostgraduateExamStore,
    onBack: () -> Unit,
) {
    val preferences by StudyFocusSessions.store.state.collectAsState()
    val activeCharacterId = preferences.activeCharacterId.ifBlank { state.profile.selectedCharacterId }
    val character = MigratedDomainStores.characters.get(activeCharacterId)
    val conversationId = "$activeCharacterId-study-focus"
    val messageFlow = remember(conversationId) { MigratedDomainStores.chat.messages(conversationId) }
    val messages by messageFlow.collectAsState()
    val scope = rememberCoroutineScope()

    var taskInput by remember(preferences.task) { mutableStateOf(preferences.task) }
    var customMinutes by remember(state.pomodoro.selectedMinutes) {
        mutableStateOf(state.pomodoro.selectedMinutes.toString())
    }
    var inSession by remember {
        mutableStateOf(
            preferences.activeSessionId.isNotBlank() ||
                state.pomodoro.running ||
                state.pomodoro.remainingSeconds < state.pomodoro.selectedMinutes * 60,
        )
    }
    var chatInput by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var systemMessage by remember { mutableStateOf("") }
    var systemError by remember { mutableStateOf(false) }

    val palette = preferences.theme.palette()
    val completedThisSession = preferences.activeSessionId.isNotBlank() && preferences.completionHandled
    val activeTask = preferences.activeTask.ifBlank { preferences.task }
    val sessionStart = preferences.sessionMessageStart.coerceIn(0, messages.size)
    val sessionMessages = remember(messages, sessionStart) {
        messages.drop(sessionStart).filter { it.sender != LuluChatMessage.Sender.System }
    }

    LaunchedEffect(
        preferences.activeSessionId,
        state.pomodoro.running,
        state.pomodoro.remainingSeconds,
        state.pomodoro.selectedMinutes,
    ) {
        if (
            preferences.activeSessionId.isNotBlank() ||
            state.pomodoro.running ||
            state.pomodoro.remainingSeconds < state.pomodoro.selectedMinutes * 60
        ) {
            inSession = true
        }
    }

    fun speak(text: String) {
        StudyFocusSessions.speakIfEnabled(text, state.pomodoro.voiceEnabled)
    }

    fun beginSession() {
        val minutes = customMinutes.toIntOrNull()?.coerceIn(1, 180)
        val cleanTask = taskInput.trim()
        if (minutes == null) {
            systemMessage = "请输入1—180之间的分钟数"
            systemError = true
            return
        }
        if (cleanTask.isBlank()) {
            systemMessage = "请先写下本次专注任务"
            systemError = true
            return
        }

        val selectedCharacter = MigratedDomainStores.characters.get(state.profile.selectedCharacterId)
        ensureStudyFocusConversation(state.profile.selectedCharacterId, selectedCharacter.displayName)
        val selectedConversationId = "${state.profile.selectedCharacterId}-study-focus"
        val messageStart = MigratedDomainStores.chat.messages(selectedConversationId).value.size

        StudyFocusSessions.store.updateTask(cleanTask)
        StudyFocusSessions.beginSession(state.profile.selectedCharacterId, cleanTask, messageStart)
        store.setPomodoroDuration(minutes)
        if (!store.state.value.pomodoro.running) store.togglePomodoro()

        inSession = true
        systemMessage = "计时已经开始"
        systemError = false
        StudyFocusSessions.requestOpeningLine(store.state.value)
    }

    fun settle(actualMinutes: Int, reason: String) {
        val originalMinutes = store.state.value.pomodoro.selectedMinutes
        if (store.state.value.pomodoro.running) store.togglePomodoro()
        store.setPomodoroDuration(actualMinutes.coerceIn(1, 180))
        val reward = store.completePomodoro(actualMinutes.coerceAtLeast(1))
        store.setPomodoroDuration(originalMinutes)
        StudyFocusSessions.completeSession(
            studyStore = store,
            actualMinutes = actualMinutes,
            reason = reason,
            rewardMessage = reward,
            recordExperience = true,
        )
        systemMessage = "已按实际 ${actualMinutes.coerceAtLeast(1)} 分钟结算"
        systemError = false
    }

    fun finishEarly() {
        val timer = store.state.value.pomodoro
        val totalSeconds = timer.selectedMinutes * 60
        val elapsedSeconds = (totalSeconds - timer.remainingSeconds).coerceAtLeast(0)
        val actualMinutes = ceil(elapsedSeconds / 60.0).toInt()
        if (actualMinutes <= 0) {
            if (timer.running) store.togglePomodoro()
            store.resetPomodoro()
            StudyFocusSessions.completeSession(
                studyStore = store,
                actualMinutes = 0,
                reason = "用户在未满1分钟时提前结束",
                rewardMessage = "本次不记录奖励",
                recordExperience = false,
            )
            systemMessage = "还没有满1分钟，本次不记录奖励"
            systemError = false
        } else {
            settle(actualMinutes, "用户提前结束")
        }
    }

    fun sendFocusChat() {
        val clean = chatInput.trim()
        if (clean.isBlank() || generating) return
        MigratedDomainStores.chat.sendUserMessage(conversationId, clean)
        chatInput = ""
        generating = true
        scope.launch {
            val recent = MigratedDomainStores.chat.messages(conversationId).value.takeLast(12)
                .filter { it.sender != LuluChatMessage.Sender.System }
                .joinToString("\n") { message ->
                    val speakerName = if (message.sender == LuluChatMessage.Sender.User) "用户" else character.displayName
                    "$speakerName：${message.content}"
                }
            LuluAiServices.gateway.generate(
                characterId = activeCharacterId,
                facts = buildString {
                    appendLine(store.state.value.roleStudyContext())
                    appendLine("当前专注任务：$activeTask")
                    appendLine(
                        "剩余时间：${store.state.value.pomodoro.remainingSeconds / 60}分" +
                            "${store.state.value.pomodoro.remainingSeconds % 60}秒",
                    )
                    appendLine("以前各轮专注对话仍在上下文中；界面只显示本轮。")
                    appendLine("最近对话：")
                    appendLine(recent)
                },
                instruction = "这是专注中的即时聊天。回应用户最新一句话，保持角色人设和关系边界；不要擅自结束计时或宣称任务完成。1-4句。",
                source = "考研",
                title = "专注中聊天",
                maxTokens = 420,
            ).onSuccess { reply ->
                val text = reply.text.trim()
                if (text.isNotBlank()) {
                    MigratedDomainStores.chat.appendCharacterMessage(conversationId, text)
                    speak(text)
                }
            }.onFailure { error ->
                systemMessage = error.message ?: "专注聊天生成失败"
                systemError = true
            }
            generating = false
        }
    }

    if (!inSession) {
        FocusSetupScreen(
            task = taskInput,
            onTask = { taskInput = it },
            minutes = customMinutes,
            onMinutes = { customMinutes = it.filter(Char::isDigit).take(3) },
            voiceEnabled = state.pomodoro.voiceEnabled,
            onVoice = { store.togglePomodoroVoice() },
            automaticDialogueEnabled = preferences.automaticDialogueEnabled,
            onAutomaticDialogue = StudyFocusSessions.store::updateAutomaticDialogue,
            onStart = ::beginSession,
            onBack = onBack,
            message = systemMessage,
            error = systemError,
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(palette.background)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(palette.topGlow, Color.Transparent, palette.bottomGlow),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回并缩小番茄钟", tint = palette.text)
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            activeTask,
                            color = palette.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 2,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StudyFocusTheme.entries.forEach { theme ->
                                val selected = theme == preferences.theme
                                Surface(
                                    onClick = { StudyFocusSessions.store.updateTheme(theme) },
                                    shape = RoundedCornerShape(99.dp),
                                    color = if (selected) palette.accent else Color.Transparent,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) palette.accent else palette.muted.copy(alpha = 0.42f),
                                    ),
                                ) {
                                    Text(
                                        if (theme == StudyFocusTheme.CLOUD) "浅色" else "深蓝",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = if (selected) palette.background.last() else palette.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { store.togglePomodoroVoice() }) {
                        Icon(
                            if (state.pomodoro.voiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            "角色语音",
                            tint = palette.muted,
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        val totalSeconds = (state.pomodoro.selectedMinutes * 60).coerceAtLeast(1)
                        val remainingSeconds = if (completedThisSession) {
                            0
                        } else {
                            state.pomodoro.remainingSeconds.coerceIn(0, totalSeconds)
                        }
                        val elapsedSeconds = if (completedThisSession) {
                            totalSeconds
                        } else {
                            (totalSeconds - remainingSeconds).coerceIn(0, totalSeconds)
                        }
                        val progress = elapsedSeconds.toFloat() / totalSeconds

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(286.dp)
                                    .background(
                                        brush = Brush.radialGradient(
                                            listOf(
                                                palette.accent.copy(alpha = 0.12f),
                                                palette.accent.copy(alpha = 0.025f),
                                                Color.Transparent,
                                            ),
                                        ),
                                        shape = RoundedCornerShape(999.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.size(252.dp),
                                    color = palette.accent,
                                    trackColor = palette.track,
                                    strokeWidth = 10.dp,
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        "%02d:%02d".format(
                                            remainingSeconds / 60,
                                            remainingSeconds % 60,
                                        ),
                                        color = palette.text,
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        when {
                                            completedThisSession -> "本次已结算"
                                            state.pomodoro.running -> "剩余时间"
                                            else -> "已暂停"
                                        },
                                        color = palette.muted,
                                        fontSize = 13.sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "已专注 %02d:%02d".format(
                                            elapsedSeconds / 60,
                                            elapsedSeconds % 60,
                                        ),
                                        color = palette.accent,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            if (!completedThisSession) {
                                Row(
                                    modifier = Modifier.widthIn(max = 520.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Button(
                                        onClick = { store.togglePomodoro() },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = palette.accent,
                                            contentColor = palette.background.last(),
                                        ),
                                    ) {
                                        Icon(
                                            if (state.pomodoro.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                            null,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (state.pomodoro.running) "暂停" else "继续")
                                    }
                                    OutlinedButton(
                                        onClick = ::finishEarly,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text),
                                        border = BorderStroke(1.dp, palette.muted.copy(alpha = 0.62f)),
                                    ) { Text("提前结束") }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        store.resetPomodoro()
                                        StudyFocusSessions.clearSession()
                                        inSession = false
                                        systemMessage = ""
                                        systemError = false
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accent,
                                        contentColor = palette.background.last(),
                                    ),
                                ) { Text("设置下一次专注") }
                            }
                        }
                    }

                    if (systemMessage.isNotBlank()) {
                        item {
                            Surface(
                                color = if (systemError) Color(0xFF5A3534) else palette.panel,
                                contentColor = palette.text,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(systemMessage, Modifier.fillMaxWidth().padding(13.dp), color = palette.text)
                            }
                        }
                    }

                    items(sessionMessages.takeLast(20), key = { it.id }) { message ->
                        val user = message.sender == LuluChatMessage.Sender.User
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
                        ) {
                            Surface(
                                color = if (user) palette.accent else palette.panel,
                                contentColor = if (user) palette.background.last() else palette.text,
                                shape = RoundedCornerShape(17.dp),
                                modifier = Modifier.widthIn(max = 300.dp),
                            ) {
                                Text(message.content, Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                            }
                        }
                    }
                    if (generating) item { Text("${character.displayName}正在回应…", color = palette.muted) }
                }

                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        placeholder = { Text("专注中和角色说话") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.text,
                            unfocusedTextColor = palette.text,
                            focusedContainerColor = palette.input,
                            unfocusedContainerColor = palette.input,
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.muted,
                            focusedPlaceholderColor = palette.muted,
                            unfocusedPlaceholderColor = palette.muted,
                        ),
                    )
                    FilledIconButton(
                        onClick = ::sendFocusChat,
                        enabled = chatInput.isNotBlank() && !generating,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = palette.accent,
                            contentColor = palette.background.last(),
                        ),
                    ) { Icon(Icons.Outlined.Send, "发送") }
                }
            }
        }
    }
}

@Composable
private fun FocusSetupScreen(
    task: String,
    onTask: (String) -> Unit,
    minutes: String,
    onMinutes: (String) -> Unit,
    voiceEnabled: Boolean,
    onVoice: () -> Unit,
    automaticDialogueEnabled: Boolean,
    onAutomaticDialogue: (Boolean) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    message: String,
    error: Boolean,
) {
    Scaffold(
        containerColor = StudyDesign.paper,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Text("开始一次专注", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StudyCard {
                    Text("本次任务", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = task,
                        onValueChange = onTask,
                        label = { Text("例如：完成2007年英语一阅读复盘") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                StudyCard {
                    Text("专注时长", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(25, 45, 60, 90).forEach { preset ->
                            FilterChip(
                                selected = minutes == preset.toString(),
                                onClick = { onMinutes(preset.toString()) },
                                label = { Text("$preset 分") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = onMinutes,
                        label = { Text("自定义 1—180 分钟") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
            item {
                StudyCard {
                    Text("陪学互动", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("角色语音", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = voiceEnabled, onCheckedChange = { onVoice() })
                    }
                    HorizontalDivider(color = StudyDesign.border)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "开场与结束主动对话",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Switch(
                            checked = automaticDialogueEnabled,
                            onCheckedChange = onAutomaticDialogue,
                        )
                    }
                }
            }
            if (message.isNotBlank()) item { StudyMessage(message, error) }
            item {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(7.dp))
                    Text("开始陪学")
                }
            }
        }
    }
}
