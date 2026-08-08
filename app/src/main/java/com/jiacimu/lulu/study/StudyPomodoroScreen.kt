package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores

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
    var minutesText by remember(state.pomodoro.selectedMinutes) { mutableStateOf(state.pomodoro.selectedMinutes.toString()) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }

    val active = companion.activeSessionId.isNotBlank() && !companion.completionHandled
    val completed = companion.activeSessionId.isNotBlank() && companion.completionHandled
    val mode = companion.timerMode
    val palette = remember(companion.skin) { pomodoroPalette(companion.skin) }
    val countdownTotal = (state.pomodoro.selectedMinutes * 60).coerceAtLeast(1)
    val countdownRemaining = state.pomodoro.remainingSeconds.coerceAtLeast(0)
    val countUpElapsed = companion.countUpElapsedSeconds.coerceAtLeast(0)
    val elapsedSeconds = if (mode == PomodoroTimerMode.CountUp) countUpElapsed else (countdownTotal - countdownRemaining).coerceAtLeast(0)
    val displaySeconds = if (mode == PomodoroTimerMode.CountUp) countUpElapsed else countdownRemaining
    val running = if (mode == PomodoroTimerMode.CountUp) companion.countUpRunning else state.pomodoro.running
    val progress = if (mode == PomodoroTimerMode.Countdown) {
        elapsedSeconds.toFloat() / countdownTotal.toFloat()
    } else {
        (countUpElapsed % 3600).toFloat() / 3600f
    }

    fun privateConversationId(): String {
        val characterId = companion.activeCharacterId.ifBlank { state.profile.selectedCharacterId }
        val character = MigratedDomainStores.characters.get(characterId)
        return MigratedDomainStores.chat.ensureConversation(characterId, character.displayName).id
    }

    fun startPomodoro() {
        val cleanTask = task.trim()
        val minutes = minutesText.toIntOrNull()?.coerceIn(1, 180)
        if (cleanTask.isBlank()) {
            notice = "先写下这一轮要做什么"
            noticeError = true
            return
        }
        if (mode == PomodoroTimerMode.Countdown && minutes == null) {
            notice = "倒计时请输入1—180分钟"
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
        if (mode == PomodoroTimerMode.Countdown) {
            store.setPomodoroDuration(minutes ?: 25)
            if (!store.state.value.pomodoro.running) store.togglePomodoro()
        } else {
            store.resetPomodoro()
        }
        PomodoroCompanionSessions.beginSession(characterId, cleanTask, messageStart, mode)
        notice = ""
        noticeError = false
        PomodoroCompanionSessions.requestOpeningLine(store.state.value)
    }

    fun finishPomodoro() {
        val (minutes, reward) = PomodoroCompanionSessions.finishActiveSession(store)
        notice = if (minutes > 0) "已学习 $minutes 分钟 · $reward" else reward
        noticeError = false
    }

    if (active) {
        PomodoroFocusScreen(
            task = companion.activeTask.ifBlank { task },
            mode = mode,
            running = running,
            displaySeconds = displaySeconds,
            elapsedSeconds = elapsedSeconds,
            progress = progress,
            skin = companion.skin,
            palette = palette,
            onBack = onBack,
            onToggleSkin = {
                PomodoroCompanionSessions.store.updateSkin(
                    if (companion.skin == PomodoroSkin.Light) PomodoroSkin.Dark else PomodoroSkin.Light,
                )
            },
            onPauseResume = {
                if (mode == PomodoroTimerMode.CountUp) PomodoroCompanionSessions.toggleCountUp() else store.togglePomodoro()
            },
            onFinish = ::finishPomodoro,
            onOpenConversation = { onOpenConversation(privateConversationId()) },
        )
        return
    }

    Scaffold(
        containerColor = Color(0xFFF7F8FA),
        topBar = {
            TopAppBar(
                title = { Text("番茄钟", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = {
                        PomodoroCompanionSessions.store.updateSkin(
                            if (companion.skin == PomodoroSkin.Light) PomodoroSkin.Dark else PomodoroSkin.Light,
                        )
                    }) { Text(if (companion.skin == PomodoroSkin.Light) "浅色" else "深色") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F8FA)),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (completed) {
                item {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFFE4E7EB)),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(44.dp), tint = Color(0xFF687D72))
                            Text("这一轮结束了", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(companion.activeTask, color = Color(0xFF747B84), textAlign = TextAlign.Center)
                            if (notice.isNotBlank()) Text(notice, color = Color(0xFF747B84), fontSize = 12.sp, textAlign = TextAlign.Center)
                            Button(onClick = {
                                store.resetPomodoro()
                                PomodoroCompanionSessions.clearSession()
                                notice = ""
                            }) { Text("再开一轮") }
                            OutlinedButton(onClick = { onOpenConversation(privateConversationId()) }) {
                                Text("查看角色私聊")
                            }
                        }
                    }
                }
            } else {
                item {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFFE4E7EB)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("这一轮要做什么", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("番茄钟里的陪伴消息直接进入角色原本的私聊，不再创建学习专用聊天。", color = Color(0xFF7A8089), fontSize = 12.sp)
                            }
                            OutlinedTextField(
                                value = task,
                                onValueChange = { task = it.take(200) },
                                placeholder = { Text("例如：刑法第14章课程") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                            )
                            Text("计时方式", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = mode == PomodoroTimerMode.Countdown,
                                    onClick = { PomodoroCompanionSessions.store.updateTimerMode(PomodoroTimerMode.Countdown) },
                                    label = { Text("倒计时") },
                                    leadingIcon = { Icon(Icons.Outlined.HourglassBottom, null, Modifier.size(17.dp)) },
                                )
                                FilterChip(
                                    selected = mode == PomodoroTimerMode.CountUp,
                                    onClick = { PomodoroCompanionSessions.store.updateTimerMode(PomodoroTimerMode.CountUp) },
                                    label = { Text("正计时") },
                                    leadingIcon = { Icon(Icons.Outlined.Timer, null, Modifier.size(17.dp)) },
                                )
                            }
                            if (mode == PomodoroTimerMode.Countdown) {
                                Text("倒计时时长", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    listOf(20, 25, 40, 50).forEach { value ->
                                        FilterChip(
                                            selected = minutesText == value.toString(),
                                            onClick = { minutesText = value.toString() },
                                            label = { Text("$value") },
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = minutesText,
                                    onValueChange = { minutesText = it.filter(Char::isDigit).take(3) },
                                    label = { Text("自定义分钟") },
                                    suffix = { Text("分钟") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                )
                            } else {
                                Surface(color = Color(0xFFF3F5F7), shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.AllInclusive, null, tint = Color(0xFF687482))
                                        Spacer(Modifier.width(10.dp))
                                        Text("从 00:00 开始往上计时，直到你手动结束。", color = Color(0xFF5F6670), fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFFE4E7EB)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            Text("陪伴设置", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            SettingSwitchRow(
                                title = "角色开场消息",
                                subtitle = "开始时让当前角色在原私聊里自然说一句。",
                                checked = companion.automaticDialogueEnabled,
                                onCheckedChange = PomodoroCompanionSessions.store::updateAutomaticDialogue,
                            )
                            HorizontalDivider(color = Color(0xFFECEEF1))
                            SettingSwitchRow(
                                title = "角色语音",
                                subtitle = "番茄钟开场消息生成后自动读出来。",
                                checked = state.pomodoro.voiceEnabled,
                                onCheckedChange = { store.togglePomodoroVoice() },
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = ::startPomodoro,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A31)),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (mode == PomodoroTimerMode.Countdown) "开始倒计时" else "开始正计时", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (notice.isNotBlank()) item {
                    Text(notice, color = if (noticeError) MaterialTheme.colorScheme.error else Color(0xFF687D72), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PomodoroFocusScreen(
    task: String,
    mode: PomodoroTimerMode,
    running: Boolean,
    displaySeconds: Int,
    elapsedSeconds: Int,
    progress: Float,
    skin: PomodoroSkin,
    palette: PomodoroPalette,
    onBack: () -> Unit,
    onToggleSkin: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(palette.background)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = palette.primaryText)) {
                Icon(Icons.Outlined.ArrowBack, null)
                Spacer(Modifier.width(4.dp))
                Text("返回")
            }
            TextButton(onClick = onToggleSkin, colors = ButtonDefaults.textButtonColors(contentColor = palette.primaryText)) {
                Icon(if (skin == PomodoroSkin.Light) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (skin == PomodoroSkin.Light) "浅色" else "深色")
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.8f))
            Text(
                if (mode == PomodoroTimerMode.Countdown) "倒计时" else "正计时",
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.size(274.dp).clip(CircleShape).background(palette.timerSurface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(246.dp),
                    strokeWidth = 6.dp,
                    color = palette.ring,
                    trackColor = palette.track,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(
                        formatPomodoroClock(displaySeconds),
                        color = palette.primaryText,
                        fontSize = if (displaySeconds >= 3600) 41.sp else 51.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        task.ifBlank { "专注这一轮" },
                        color = palette.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "已经过去 ${formatPomodoroElapsed(elapsedSeconds)}",
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onPauseResume,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primaryText),
                    border = BorderStroke(1.dp, palette.controlBorder),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(if (running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (running) "暂停" else "继续")
                }
                Button(
                    onClick = onFinish,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.action, contentColor = palette.actionText),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Outlined.StopCircle, null)
                    Spacer(Modifier.width(5.dp))
                    Text("结束")
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onOpenConversation, colors = ButtonDefaults.textButtonColors(contentColor = palette.primaryText)) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("去角色私聊 · 记录与主私聊同步")
            }
            Text(
                "返回不会结束计时；离开后会显示可拖动的小窗。",
                color = palette.secondaryText.copy(alpha = 0.85f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFF7A8089), fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private data class PomodoroPalette(
    val background: List<Color>,
    val primaryText: Color,
    val secondaryText: Color,
    val ring: Color,
    val track: Color,
    val timerSurface: Color,
    val action: Color,
    val actionText: Color,
    val controlBorder: Color,
)

private fun pomodoroPalette(skin: PomodoroSkin): PomodoroPalette = when (skin) {
    PomodoroSkin.Light -> PomodoroPalette(
        background = listOf(Color(0xFFF7F8F9), Color(0xFFEEF2F4), Color(0xFFF5F6F7)),
        primaryText = Color(0xFF2B3238),
        secondaryText = Color(0xFF71808A),
        ring = Color(0xFF7E98A8),
        track = Color(0xFFDDE5E9),
        timerSurface = Color.White.copy(alpha = 0.68f),
        action = Color(0xFF394650),
        actionText = Color.White,
        controlBorder = Color(0xFFAAB7BF),
    )
    PomodoroSkin.Dark -> PomodoroPalette(
        background = listOf(Color(0xFF10151D), Color(0xFF17202B), Color(0xFF0D131B)),
        primaryText = Color(0xFFF0F4F7),
        secondaryText = Color(0xFFA9B7C3),
        ring = Color(0xFF8EAABD),
        track = Color(0xFF2D3A46),
        timerSurface = Color(0xFF1D2834).copy(alpha = 0.82f),
        action = Color(0xFFD6E0E7),
        actionText = Color(0xFF26313A),
        controlBorder = Color(0xFF566675),
    )
}
