package com.jiacimu.lulu.study

import android.speech.tts.TextToSpeech
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.time.Instant
import java.util.UUID
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
        topGlow = Color.White.copy(alpha = 0.18f),
        bottomGlow = Color(0xFF5C6B7D).copy(alpha = 0.06f),
        panel = Color.White.copy(alpha = 0.24f),
        input = Color(0xFFFFF8FB).copy(alpha = 0.92f),
        accent = Color(0xFF7895A6),
        text = Color(0xFF35434D),
        muted = Color(0xFF667782),
        track = Color.White.copy(alpha = 0.34f),
    )
    StudyFocusTheme.MIDNIGHT -> FocusPalette(
        background = listOf(Color(0xFF111827), Color(0xFF172033), Color(0xFF0F172A)),
        topGlow = Color(0xFF88A9C0).copy(alpha = 0.12f),
        bottomGlow = Color.Black.copy(alpha = 0.18f),
        panel = Color(0xFF253247).copy(alpha = 0.72f),
        input = Color(0xFF1C2738).copy(alpha = 0.94f),
        accent = Color(0xFF88A9C0),
        text = Color(0xFFE8EEF5),
        muted = Color(0xFFB2C1CF),
        track = Color.White.copy(alpha = 0.12f),
    )
}

@Composable
internal fun StudyFocusCompleteScreen(
    state: StudyState,
    store: PostgraduateExamStore,
    onBack: () -> Unit,
) {
    val preferences by StudyFocusSessions.store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.profile.selectedCharacterId)
    val conversationId = "${state.profile.selectedCharacterId}-study-focus"
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var taskInput by remember(preferences.task) { mutableStateOf(preferences.task) }
    var customMinutes by remember(state.pomodoro.selectedMinutes) { mutableStateOf(state.pomodoro.selectedMinutes.toString()) }
    var inSession by remember {
        mutableStateOf(
            state.pomodoro.running ||
                state.pomodoro.remainingSeconds < state.pomodoro.selectedMinutes * 60,
        )
    }
    var completedThisSession by remember { mutableStateOf(false) }
    var openingRequested by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var systemMessage by remember { mutableStateOf("") }
    var systemError by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var sessionMessageStart by remember { mutableIntStateOf(messages.size) }
    var sessionExperienceId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sessionStartedAt by remember { mutableStateOf(Instant.now()) }
    val palette = preferences.theme.palette()

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        engine.language = Locale.SIMPLIFIED_CHINESE
        tts = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            tts = null
        }
    }

    fun speak(text: String) {
        if (state.pomodoro.voiceEnabled && ttsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "study-focus-${System.currentTimeMillis()}")
        }
    }

    fun rememberFocusExperience(actualMinutes: Int, reason: String) {
        val transcript = MigratedDomainStores.chat.messages(conversationId).value
            .drop(sessionMessageStart)
            .joinToString("\n") { message ->
                val speaker = if (message.sender == LuluChatMessage.Sender.User) "用户" else character.displayName
                "$speaker：${message.content.trim()}"
            }
        SharedExperienceTimeline.remember(
            memoryId = "focus-$sessionExperienceId",
            characterId = state.profile.selectedCharacterId,
            label = "共同专注",
            detail = buildString {
                append("任务“${preferences.task}”，实际专注 ${actualMinutes.coerceAtLeast(1)} 分钟，$reason。")
                if (transcript.isNotBlank()) append("专注期间的对话：\n$transcript")
            },
            occurredAt = sessionStartedAt,
            strength = 6,
            source = "study-focus",
        )
    }

    fun requestOpeningLine() {
        if (openingRequested || !state.pomodoro.running) return
        openingRequested = true
        generating = true
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.profile.selectedCharacterId,
                facts = buildString {
                    appendLine(state.roleStudyContext())
                    appendLine("本次专注任务：${preferences.task}")
                    appendLine("计划时长：${state.pomodoro.selectedMinutes}分钟")
                    appendLine("番茄钟已经由程序真实启动。")
                },
                instruction = "以角色自己的身份给出本次专注开始时会说的话，1-3句。不得默认温柔、夸奖或亲密；不得虚构用户已经完成任务。",
                source = "考研",
                title = "番茄钟开场",
                maxTokens = 260,
            ).onSuccess { reply ->
                MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                speak(reply.text)
                systemMessage = "角色开场语已生成"
                systemError = false
            }.onFailure { error ->
                systemMessage = error.message ?: "角色开场语生成失败，计时仍在继续"
                systemError = true
            }
            generating = false
        }
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
        StudyFocusSessions.store.updateTask(cleanTask)
        store.setPomodoroDuration(minutes)
        if (!store.state.value.pomodoro.running) store.togglePomodoro()
        sessionMessageStart = MigratedDomainStores.chat.messages(conversationId).value.size
        sessionExperienceId = UUID.randomUUID().toString()
        sessionStartedAt = Instant.now()
        inSession = true
        completedThisSession = false
        openingRequested = false
        systemMessage = "计时已经开始"
        systemError = false
    }

    fun settle(actualMinutes: Int, reason: String) {
        val originalMinutes = store.state.value.pomodoro.selectedMinutes
        if (store.state.value.pomodoro.running) store.togglePomodoro()
        store.setPomodoroDuration(actualMinutes.coerceIn(1, 180))
        val reward = store.completePomodoro(actualMinutes.coerceAtLeast(1))
        store.setPomodoroDuration(originalMinutes)
        completedThisSession = true
        rememberFocusExperience(actualMinutes, reason)
        val facts = buildString {
            appendLine(state.roleStudyContext())
            appendLine("本次任务：${preferences.task}")
            appendLine("实际记录时长：${actualMinutes.coerceAtLeast(1)}分钟")
            appendLine("结束方式：$reason")
            appendLine("程序奖励结果：$reward")
        }
        systemMessage = "已按实际 ${actualMinutes.coerceAtLeast(1)} 分钟结算"
        systemError = false
        generating = true
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.profile.selectedCharacterId,
                facts = facts,
                instruction = "根据真实完成时长、任务和结束方式，以角色自己的身份回应1-3句。是否夸奖以及如何夸奖由角色判断；不得把提前结束说成完整完成。",
                source = "考研",
                title = "番茄钟结算",
                maxTokens = 300,
            ).onSuccess { reply ->
                MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                speak(reply.text)
            }.onFailure { error ->
                systemMessage = "学习记录已保存；${error.message ?: "角色反馈生成失败"}"
                systemError = true
            }
            generating = false
        }
    }

    fun finishEarly() {
        val timer = store.state.value.pomodoro
        val totalSeconds = timer.selectedMinutes * 60
        val elapsedSeconds = (totalSeconds - timer.remainingSeconds).coerceAtLeast(0)
        val actualMinutes = ceil(elapsedSeconds / 60.0).toInt()
        if (actualMinutes <= 0) {
            if (timer.running) store.togglePomodoro()
            store.resetPomodoro()
            completedThisSession = true
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
                .joinToString("\n") { message ->
                    val speakerName = if (message.sender == LuluChatMessage.Sender.User) "用户" else character.displayName
                    "$speakerName：${message.content}"
                }
            LuluAiServices.gateway.generate(
                characterId = state.profile.selectedCharacterId,
                facts = buildString {
                    appendLine(state.roleStudyContext())
                    appendLine("当前专注任务：${preferences.task}")
                    appendLine("剩余时间：${store.state.value.pomodoro.remainingSeconds / 60}分${store.state.value.pomodoro.remainingSeconds % 60}秒")
                    appendLine("最近对话：")
                    appendLine(recent)
                },
                instruction = "这是专注中的即时聊天。回应用户最新一句话，保持角色人设和关系边界；不要擅自结束计时或宣称任务完成。1-4句。",
                source = "考研",
                title = "专注中聊天",
                maxTokens = 420,
            ).onSuccess { reply ->
                MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                speak(reply.text)
            }.onFailure { error ->
                systemMessage = error.message ?: "专注聊天生成失败"
                systemError = true
            }
            generating = false
        }
    }

    LaunchedEffect(state.pomodoro.running, state.pomodoro.endAtEpochMillis, inSession) {
        if (inSession && state.pomodoro.running) requestOpeningLine()
        while (inSession && store.state.value.pomodoro.running) {
            delay(500)
            if (store.syncPomodoroClock()) {
                completedThisSession = true
                rememberFocusExperience(state.pomodoro.selectedMinutes, "番茄钟自然结束")
                val facts = buildString {
                    appendLine(store.state.value.roleStudyContext())
                    appendLine("本次任务：${preferences.task}")
                    appendLine("番茄钟自然结束，完整记录${state.pomodoro.selectedMinutes}分钟。")
                }
                generating = true
                scope.launch {
                    LuluAiServices.gateway.generate(
                        characterId = state.profile.selectedCharacterId,
                        facts = facts,
                        instruction = "番茄钟刚自然结束。根据真实结果以角色自己的身份回应1-3句；是否夸奖由角色判断。",
                        source = "考研",
                        title = "番茄钟自然结束",
                        maxTokens = 300,
                    ).onSuccess { reply ->
                        MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                        speak(reply.text)
                    }.onFailure { error ->
                        systemMessage = "专注已结算；${error.message ?: "角色回应生成失败"}"
                        systemError = true
                    }
                    generating = false
                }
                break
            }
        }
    }

    if (!inSession) {
        FocusSetupScreen(
            task = taskInput,
            onTask = { taskInput = it },
            minutes = customMinutes,
            onMinutes = { customMinutes = it.filter(Char::isDigit).take(3) },
            selectedTheme = preferences.theme,
            onTheme = StudyFocusSessions.store::updateTheme,
            voiceEnabled = state.pomodoro.voiceEnabled,
            onVoice = { store.togglePomodoroVoice() },
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
                .background(Brush.verticalGradient(listOf(palette.topGlow, Color.Transparent, palette.bottomGlow))),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = palette.text) }
                    Column(Modifier.weight(1f)) {
                        Text(preferences.task, color = palette.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2)
                        Text("${character.displayName} · ${preferences.theme.label}", color = palette.muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { store.togglePomodoroVoice() }) {
                        Icon(if (state.pomodoro.voiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, "角色语音", tint = palette.muted)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        Surface(
                            color = palette.panel,
                            contentColor = palette.text,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                val total = state.pomodoro.selectedMinutes * 60
                                val progress = 1f - state.pomodoro.remainingSeconds.toFloat() / total.coerceAtLeast(1)
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(230.dp),
                                        color = palette.accent,
                                        trackColor = palette.track,
                                        strokeWidth = 11.dp,
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "%02d:%02d".format(state.pomodoro.remainingSeconds / 60, state.pomodoro.remainingSeconds % 60),
                                            color = palette.text,
                                            fontSize = 50.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            when {
                                                completedThisSession -> "本次已结算"
                                                state.pomodoro.running -> "专注中"
                                                else -> "已暂停"
                                            },
                                            color = palette.muted,
                                        )
                                    }
                                }
                                if (!completedThisSession) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = { store.togglePomodoro() },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.background.last()),
                                        ) {
                                            Icon(if (state.pomodoro.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (state.pomodoro.running) "暂停" else "继续")
                                        }
                                        OutlinedButton(
                                            onClick = ::finishEarly,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text),
                                            border = BorderStroke(1.dp, palette.muted),
                                        ) { Text("提前结束") }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            store.resetPomodoro()
                                            inSession = false
                                            completedThisSession = false
                                            openingRequested = false
                                            systemMessage = ""
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.background.last()),
                                    ) { Text("返回设置下一次专注") }
                                }
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
                    item { Text("专注中聊天", color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    items(messages.takeLast(20), key = { it.id }) { message ->
                        val user = message.sender == LuluChatMessage.Sender.User
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
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
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = palette.accent, contentColor = palette.background.last()),
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
    selectedTheme: StudyFocusTheme,
    onTheme: (StudyFocusTheme) -> Unit,
    voiceEnabled: Boolean,
    onVoice: () -> Unit,
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
                    Text("专注氛围", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    StudyFocusTheme.entries.forEach { theme ->
                        val preview = theme.palette()
                        Surface(
                            onClick = { onTheme(theme) },
                            modifier = Modifier.fillMaxWidth(),
                            color = preview.background[1],
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(if (selectedTheme == theme) 2.dp else 1.dp, if (selectedTheme == theme) preview.accent else preview.track),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(34.dp),
                                    shape = RoundedCornerShape(50),
                                    color = preview.panel,
                                    border = BorderStroke(3.dp, preview.accent),
                                ) {}
                                Spacer(Modifier.width(12.dp))
                                Text(theme.label, color = preview.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (selectedTheme == theme) Text("已选择", color = preview.muted)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("角色语音", fontWeight = FontWeight.SemiBold)
                            Text("完成反馈和专注聊天可由系统 TTS 朗读", color = StudyDesign.muted, fontSize = 12.sp)
                        }
                        Switch(checked = voiceEnabled, onCheckedChange = { onVoice() })
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
