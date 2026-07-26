package com.jiacimu.lulu.study

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun StudyCompanionScreen(state: StudyState, store: PostgraduateExamStore) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val selected = characters[state.profile.selectedCharacterId]
        ?: characters.values.firstOrNull()
        ?: MigratedDomainStores.characters.get("lulu")
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var sleepText by remember { mutableStateOf("23:30") }
    var wakeText by remember { mutableStateOf("07:30") }
    var durationText by remember { mutableStateOf("7.5") }
    var judgingSleep by remember { mutableStateOf(false) }
    val level = state.profile.level
    val levelStart = StudyLevels.currentLevelStart(level)
    val nextTarget = StudyLevels.nextLevelTarget(level)
    val levelProgress = (state.profile.experience - levelStart).toFloat() / (nextTarget - levelStart).coerceAtLeast(1)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(58.dp).background(StudyDesign.wheat, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(selected.displayName.take(1).ifBlank { "角" }, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected.displayName.ifBlank { "未命名角色" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("学习陪伴角色会读取真实任务、时长、番茄钟和奖励结果。", color = StudyDesign.muted)
                    }
                }
                if (characters.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        characters.values.forEach { character ->
                            FilterChip(
                                selected = state.profile.selectedCharacterId == character.characterId,
                                onClick = { store.selectCharacter(character.characterId) },
                                label = { Text(character.displayName.ifBlank { "未命名" }) },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        message = store.signIn()
                        error = false
                    },
                    enabled = state.profile.lastSignInDate != LocalDate.now().toString(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.profile.lastSignInDate == LocalDate.now().toString()) "今日已签到" else "每日签到") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyMetric("等级", "Lv.$level", Modifier.weight(1f))
                StudyMetric("连续", "${state.profile.streakDays}天", Modifier.weight(1f))
                StudyMetric("夸夸值", state.profile.praisePoints.toString(), Modifier.weight(1f))
            }
        }
        item {
            StudyCard {
                Text("等级进度", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("经验 ${state.profile.experience} · 下一级 $nextTarget", color = StudyDesign.muted)
                StudyProgress(levelProgress)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..level).forEach { claimLevel ->
                        AssistChip(
                            onClick = {
                                message = store.claimLevel(claimLevel)
                                error = false
                            },
                            enabled = claimLevel !in state.profile.claimedLevels,
                            label = { Text(if (claimLevel in state.profile.claimedLevels) "Lv.$claimLevel 已领" else "领 Lv.$claimLevel") },
                        )
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("睡眠习惯奖励", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("时间只提供参考；是否奖励以及如何回应，由当前角色结合实际情况最终判断。", color = StudyDesign.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sleepText,
                        onValueChange = { sleepText = it.take(5) },
                        label = { Text("入睡 HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = wakeText,
                        onValueChange = { wakeText = it.take(5) },
                        label = { Text("起床 HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter { char -> char.isDigit() || char == '.' }.take(4) },
                    label = { Text("实际睡眠小时") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val sleep = runCatching { LocalTime.parse(sleepText) }.getOrNull()
                        val wake = runCatching { LocalTime.parse(wakeText) }.getOrNull()
                        val duration = durationText.toDoubleOrNull()
                        if (sleep == null || wake == null || duration == null) {
                            message = "请按 HH:mm 填写时间，并填写实际睡眠小时"
                            error = true
                        } else {
                            judgingSleep = true
                            scope.launch {
                                store.evaluateSleepReward(sleep, wake, duration)
                                    .onSuccess { message = it; error = false }
                                    .onFailure { message = it.message ?: "角色判断失败"; error = true }
                                judgingSleep = false
                            }
                        }
                    },
                    enabled = !judgingSleep && state.profile.sleepRewardDate != LocalDate.now().toString(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.profile.sleepRewardDate == LocalDate.now().toString() -> "今天已经判断过"
                            judgingSleep -> "角色正在判断…"
                            else -> "交给角色判断"
                        },
                    )
                }
            }
        }
        item {
            StudyCard {
                Text("学习连续性", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("最后学习：${state.profile.lastStudyDate.ifBlank { "尚无记录" }}", color = StudyDesign.muted)
                OutlinedButton(
                    onClick = {
                        message = store.applyInactivityPenalty()
                        error = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("检查未学习惩罚") }
            }
        }
        item { StudyMessage(message, error) }
        item { Text("最近学习事件", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        if (state.events.isEmpty()) {
            item { StudyCard { Text("还没有事件", color = StudyDesign.muted) } }
        } else {
            items(state.events.take(20), key = { it.id }) { event ->
                StudyCard {
                    Text(event.title, fontWeight = FontWeight.Bold)
                    Text(event.detail)
                    Text(
                        event.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                        color = StudyDesign.muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private enum class TodayView(val label: String) { Tasks("待办"), Schedule("今日计划"), Tomorrow("明日待办"), Tips("Tips") }

@Composable
internal fun StudyTodayScreen(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenFocus: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf(TodayView.Tasks) }
    var newTask by remember { mutableStateOf("") }
    var pomodoros by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.date == today.toString() }
    val tomorrowTasks = state.tasks.filter { it.date == today.plusDays(1).toString() }
    val schedules = state.schedules.filter { it.date == today.toString() }
    val completed = todayTasks.count { it.completed }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("今日学习", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("${state.profile.totalStudyMinutes.minutesLabel()}累计 · $completed/${todayTasks.size}项完成", color = StudyDesign.muted)
                    }
                    Box(Modifier.size(58.dp).background(StudyDesign.wheatSoft, CircleShape), contentAlignment = Alignment.Center) {
                        Text(state.profile.praisePoints.toString(), fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                }
                StudyProgress(if (todayTasks.isEmpty()) 0f else completed.toFloat() / todayTasks.size)
                Button(onClick = onOpenFocus, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Timer, null)
                    Spacer(Modifier.width(7.dp))
                    Text("开始番茄钟")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StudyMetric("番茄钟", state.profile.totalPomodoros.toString(), Modifier.weight(1f))
                StudyMetric("词汇", state.profile.vocabularyReviewed.toString(), Modifier.weight(1f))
                StudyMetric("经验", state.profile.experience.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TodayView.entries.forEach { item ->
                    FilterChip(selected = item == view, onClick = { view = item }, label = { Text(item.label) })
                }
            }
        }
        when (view) {
            TodayView.Tasks -> {
                item {
                    StudyCard {
                        Text("添加今日任务", fontWeight = FontWeight.Bold)
                        OutlinedTextField(newTask, { newTask = it }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("预计番茄钟", color = StudyDesign.muted)
                            IconButton(onClick = { pomodoros = (pomodoros - 1).coerceAtLeast(1) }) { Icon(Icons.Outlined.Remove, null) }
                            Text(pomodoros.toString(), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { pomodoros = (pomodoros + 1).coerceAtMost(12) }) { Icon(Icons.Outlined.Add, null) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { store.addTask(newTask, pomodoros); newTask = ""; pomodoros = 1 }) { Text("添加") }
                        }
                    }
                }
                items(todayTasks, key = { it.id }) { task -> StudyTaskRow(task, store) }
                item {
                    StudyCard {
                        Text("词汇复习", fontWeight = FontWeight.Bold)
                        Text("记录真实完成量，角色和性能监测可以读取累计学习数据。", color = StudyDesign.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(20, 50, 100).forEach { amount ->
                                AssistChip(onClick = { store.reviewVocabulary(amount); message = "词汇复习 +$amount"; error = false }, label = { Text("+$amount") })
                            }
                        }
                    }
                }
            }
            TodayView.Schedule -> {
                item {
                    StudyCard {
                        Text("AI 今日时间表", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("使用设置中的主模型，从当前时间开始安排未完成任务，并保留缓冲。", color = StudyDesign.muted)
                        Button(
                            onClick = {
                                generating = true
                                scope.launch {
                                    store.generateTodaySchedule()
                                        .onSuccess { message = "已生成 ${it.size} 个时间块"; error = false }
                                        .onFailure { message = it.message ?: "生成失败"; error = true }
                                    generating = false
                                }
                            },
                            enabled = !generating,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (generating) "正在生成…" else "生成今日计划") }
                        TextButton(onClick = { store.clearSchedule(); message = "今日时间表已清空" }, modifier = Modifier.fillMaxWidth()) { Text("清空时间表") }
                    }
                }
                if (schedules.isEmpty()) item { StudyCard { Text("还没有时间表", color = StudyDesign.muted) } }
                items(schedules, key = { it.id }) { block ->
                    StudyCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = block.completed, onCheckedChange = { store.toggleSchedule(block.id) })
                            Column(Modifier.weight(1f)) {
                                Text(block.title, fontWeight = FontWeight.Bold)
                                Text("${block.start}—${block.end}", color = StudyDesign.muted)
                            }
                        }
                    }
                }
            }
            TodayView.Tomorrow -> {
                item {
                    StudyCard {
                        Text("添加明日待办", fontWeight = FontWeight.Bold)
                        OutlinedTextField(newTask, { newTask = it }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("番茄钟 $pomodoros", color = StudyDesign.muted)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { store.addTask(newTask, pomodoros, today.plusDays(1)); newTask = "" }) { Text("添加") }
                        }
                    }
                }
                if (tomorrowTasks.isEmpty()) item { StudyCard { Text("明天还没有待办", color = StudyDesign.muted) } }
                items(tomorrowTasks, key = { it.id }) { task -> StudyTaskRow(task, store) }
            }
            TodayView.Tips -> {
                item {
                    StudyCard {
                        Text("添加 Tip", fontWeight = FontWeight.Bold)
                        OutlinedTextField(newTask, { newTask = it }, label = { Text("提醒或方法") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { store.addTip(newTask); newTask = "" }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
                    }
                }
                items(state.tips.filter { it.date == today.toString() }, key = { it.id }) { tip -> StudyCard { Text(tip.text) } }
            }
        }
        item { StudyMessage(message, error) }
    }
}

@Composable
private fun StudyTaskRow(task: StudyTask, store: PostgraduateExamStore) {
    StudyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.completed, onCheckedChange = { store.toggleTask(task.id) })
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold)
                Text("${task.pomodoroCompleted}/${task.pomodoroTarget} 个番茄钟 · ${task.source.name}", color = StudyDesign.muted, fontSize = 12.sp)
                StudyProgress(task.pomodoroCompleted.toFloat() / task.pomodoroTarget.coerceAtLeast(1))
            }
            IconButton(onClick = { store.deleteTask(task.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
        }
    }
}

@Composable
internal fun StudyFocusScreen(state: StudyState, store: PostgraduateExamStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = MigratedDomainStores.characters.get(state.profile.selectedCharacterId)
    var roleText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var ttsReady by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        engine.language = Locale.SIMPLIFIED_CHINESE
        tts = engine
        onDispose { engine.stop(); engine.shutdown(); tts = null }
    }

    fun requestRoleCompletion(result: String) {
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.profile.selectedCharacterId,
                facts = "$result\n累计学习：${store.state.value.profile.totalStudyMinutes}分钟；累计番茄钟：${store.state.value.profile.totalPomodoros}。",
                instruction = "根据真实完成结果和角色人设回应1-3句。是否夸奖、如何夸奖由角色判断；不得机械套用固定鼓励。",
                source = "考研",
                title = "番茄钟完成反馈",
                maxTokens = 280,
            ).onSuccess {
                roleText = it.text
                if (store.state.value.pomodoro.voiceEnabled && ttsReady) tts?.speak(it.text, TextToSpeech.QUEUE_FLUSH, null, "study-focus")
            }.onFailure { roleText = it.message ?: "角色反馈生成失败，但学习记录已经保存。" }
        }
    }

    LaunchedEffect(state.pomodoro.running, state.pomodoro.endAtEpochMillis) {
        while (store.state.value.pomodoro.running) {
            delay(500)
            val finished = store.syncPomodoroClock()
            if (finished) {
                val result = "完成 ${store.state.value.pomodoro.selectedMinutes} 分钟番茄钟"
                message = result
                requestRoleCompletion(result)
                break
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = StudyDesign.dark) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("番茄钟", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("与${selected.displayName}一起专注", color = Color(0xFFBFC3CA))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VolumeUp, null, tint = Color(0xFFBFC3CA))
                    Switch(checked = state.pomodoro.voiceEnabled, onCheckedChange = { store.togglePomodoroVoice() })
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val total = state.pomodoro.selectedMinutes * 60
                val progress = 1f - state.pomodoro.remainingSeconds.toFloat() / total.coerceAtLeast(1)
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(260.dp),
                        color = StudyDesign.wheat,
                        trackColor = StudyDesign.darkCard,
                        strokeWidth = 11.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "%02d:%02d".format(state.pomodoro.remainingSeconds / 60, state.pomodoro.remainingSeconds % 60),
                            color = Color.White,
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(if (state.pomodoro.running) "专注中" else "准备开始", color = Color(0xFFBFC3CA))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 45, 60).forEach { minutes ->
                        FilterChip(
                            selected = state.pomodoro.selectedMinutes == minutes,
                            onClick = { store.setPomodoroDuration(minutes) },
                            enabled = !state.pomodoro.running,
                            label = { Text("$minutes 分") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { store.togglePomodoro() }, modifier = Modifier.width(140.dp)) {
                        Icon(if (state.pomodoro.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.pomodoro.running) "暂停" else "开始")
                    }
                    OutlinedButton(
                        onClick = {
                            val result = store.completePomodoro()
                            message = result
                            requestRoleCompletion(result)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("手动完成") }
                }
                TextButton(onClick = { store.resetPomodoro() }) { Text("重置", color = Color(0xFFBFC3CA)) }
            }
            Surface(color = StudyDesign.darkCard, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(selected.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        roleText.ifBlank { message.ifBlank { "专注完成后，角色会读取真实时长再决定如何回应。" } },
                        color = Color(0xFFD8DADE),
                    )
                }
            }
        }
    }
}
