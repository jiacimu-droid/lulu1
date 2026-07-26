package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val StudyPaper = Color(0xFFFFFDF7)
private val StudyCard = Color(0xFFFFFBF1)
private val StudyWheat = Color(0xFFF4D57D)
private val StudyMuted = Color(0xFF6D7888)
private val StudyInk = Color(0xFF343434)
private val StudyBorder = Color(0xFFEAE0CC)
private val StudyDark = Color(0xFF25282D)

private enum class StudyTab { Today, Focus, Draw, Achievements }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostgraduateExamApp(onBack: () -> Unit) {
    val store = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    var tab by remember { mutableStateOf(StudyTab.Today) }
    var lastDraw by remember { mutableStateOf<List<DrawReward>>(emptyList()) }

    Scaffold(
        containerColor = StudyPaper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("今天也向目标靠近一点", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE")), color = StudyMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyPaper),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = StudyCard) {
                StudyNavigationItem(StudyTab.Today, tab, "今日", Icons.Outlined.Today) { tab = it }
                StudyNavigationItem(StudyTab.Focus, tab, "番茄钟", Icons.Outlined.Timer) { tab = it }
                StudyNavigationItem(StudyTab.Draw, tab, "抽卡", Icons.Outlined.AutoAwesome) { tab = it }
                StudyNavigationItem(StudyTab.Achievements, tab, "成就", Icons.Outlined.EmojiEvents) { tab = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                StudyTab.Today -> TodayStudyScreen(state, store, onStartFocus = { tab = StudyTab.Focus })
                StudyTab.Focus -> PomodoroScreen(state, store)
                StudyTab.Draw -> DrawScreen(state, onSingle = { lastDraw = store.drawSingle() }, onTen = { lastDraw = store.drawTen() }, lastDraw = lastDraw)
                StudyTab.Achievements -> AchievementScreen(state, store)
            }
        }
    }
}

@Composable
private fun StudyNavigationItem(tab: StudyTab, selected: StudyTab, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onSelect: (StudyTab) -> Unit) {
    NavigationBarItem(
        selected = tab == selected,
        onClick = { onSelect(tab) },
        icon = { Icon(icon, null) },
        label = { Text(label) },
    )
}

@Composable
private fun TodayStudyScreen(state: ExamAppState, store: PostgraduateExamStore, onStartFocus: () -> Unit) {
    var newTask by remember { mutableStateOf("") }
    var taskPomodoros by remember { mutableIntStateOf(1) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StudyPaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("今日学习", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("${state.today.studyMinutes} 分钟 · ${state.today.tasks.count { it.completed }}/${state.today.tasks.size} 项完成", color = StudyMuted)
                    }
                    Surface(color = Color(0xFFFFF0BE), shape = CircleShape) {
                        Text("${state.profile.praisePoints}", Modifier.padding(15.dp), fontWeight = FontWeight.Bold)
                    }
                }
                LinearProgressIndicator(
                    progress = { if (state.today.tasks.isEmpty()) 0f else state.today.tasks.count { it.completed }.toFloat() / state.today.tasks.size },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = StudyWheat,
                    trackColor = Color(0xFFF1E9DA),
                )
                Text("夸夸值由角色结合实际表现判断；时间只作为参考，不由系统硬性否决。", color = StudyMuted, fontSize = 12.sp)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyMetricCard("学习", "${state.today.studyMinutes} 分钟", Icons.Outlined.MenuBook, Modifier.weight(1f))
                StudyMetricCard("词汇", "${state.today.vocabularyReviewed} 个", Icons.Outlined.Spellcheck, Modifier.weight(1f))
                StudyMetricCard("连续", "${state.profile.streakDays} 天", Icons.Outlined.LocalFireDepartment, Modifier.weight(1f))
            }
        }

        item {
            Text("今日任务", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        items(state.today.tasks, key = { it.id }) { task ->
            StudyPaperCard(Modifier.clickable { store.toggleTask(task.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = task.completed, onCheckedChange = { store.toggleTask(task.id) })
                    Column(Modifier.weight(1f)) {
                        Text(task.title, fontWeight = FontWeight.SemiBold)
                        Text("${task.completedPomodoros}/${task.pomodoroCount} 个番茄钟", color = StudyMuted, fontSize = 13.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = StudyMuted)
                }
            }
        }

        item {
            StudyPaperCard {
                Text("添加任务", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = newTask,
                    onValueChange = { newTask = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务名称") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("预计番茄钟", color = StudyMuted)
                    IconButton(onClick = { taskPomodoros = (taskPomodoros - 1).coerceAtLeast(1) }) { Icon(Icons.Outlined.Remove, null) }
                    Text(taskPomodoros.toString(), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { taskPomodoros = (taskPomodoros + 1).coerceAtMost(12) }) { Icon(Icons.Outlined.Add, null) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        store.addTask(newTask, taskPomodoros)
                        newTask = ""
                        taskPomodoros = 1
                    }) { Text("添加") }
                }
            }
        }

        item {
            StudyPaperCard {
                Text("词汇复习", fontWeight = FontWeight.Bold)
                Text("按真实完成量记录，角色可以读取今日和累计学习数据。", color = StudyMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { store.reviewVocabulary(20) }, label = { Text("+20") })
                    AssistChip(onClick = { store.reviewVocabulary(50) }, label = { Text("+50") })
                    AssistChip(onClick = { store.reviewVocabulary(100) }, label = { Text("+100") })
                }
            }
        }

        item {
            Button(
                onClick = onStartFocus,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("开始下一次专注", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PomodoroScreen(state: ExamAppState, store: PostgraduateExamStore) {
    LaunchedEffect(state.pomodoro.running) {
        while (state.pomodoro.running) {
            delay(1000)
            store.tick()
        }
    }
    val minutes = state.pomodoro.remainingSeconds / 60
    val seconds = state.pomodoro.remainingSeconds % 60
    val progress = 1f - state.pomodoro.remainingSeconds.toFloat() / (state.pomodoro.durationMinutes * 60).coerceAtLeast(1)

    Box(Modifier.fillMaxSize().background(StudyDark)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("保持安静，露露在这里陪着你", color = Color(0xFFD6D8DC), fontSize = 14.sp)
            }
            item {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(250.dp),
                        strokeWidth = 10.dp,
                        color = StudyWheat,
                        trackColor = Color(0xFF41454C),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(String.format("%02d:%02d", minutes, seconds), color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Light)
                        Text(if (state.pomodoro.running) "专注中" else "准备开始", color = Color(0xFFB6BBC3))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(25, 45, 60).forEach { duration ->
                        FilterChip(
                            selected = state.pomodoro.durationMinutes == duration,
                            onClick = { if (!state.pomodoro.running) store.setPomodoroDuration(duration) },
                            label = { Text("$duration 分") },
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = { store.togglePomodoro() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = StudyWheat),
                    ) { Icon(if (state.pomodoro.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(32.dp), tint = StudyInk) }
                    OutlinedIconButton(onClick = { store.completePomodoro() }, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Outlined.Check, "完成", tint = Color.White)
                    }
                }
            }
            item {
                Surface(color = Color(0xFF34383F), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.RecordVoiceOver, null, tint = Color(0xFFD5D8DE))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("角色语音陪伴", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("开关会记住当前状态，不会每次自动重开。", color = Color(0xFFB6BBC3), fontSize = 12.sp)
                        }
                        Switch(checked = state.pomodoro.voiceEnabled, onCheckedChange = { store.togglePomodoroVoice() })
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawScreen(state: ExamAppState, onSingle: () -> Unit, onTen: () -> Unit, lastDraw: List<DrawReward>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StudyPaperCard {
                Text("学习奖励抽卡", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("单抽券 ${state.wallet.singleTickets} · 十连券 ${state.wallet.tenPullTickets}", color = StudyMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AssistChip(onClick = {}, label = { Text("紫碎片 ${state.wallet.purpleFragments}") })
                    AssistChip(onClick = {}, label = { Text("蓝碎片 ${state.wallet.blueFragments}") })
                    AssistChip(onClick = {}, label = { Text("学习币 ${state.profile.studyCoins}") })
                }
            }
        }
        item {
            StudyPaperCard {
                Text("概率说明", fontWeight = FontWeight.Bold)
                Text("紫色碎片 6% · 抖音 5% · 小剧场 1%", color = StudyMuted)
                Text("蓝色碎片即使达到上限，也会正常展示本次抽中的物品。", color = StudyMuted, fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSingle, enabled = state.wallet.singleTickets > 0, modifier = Modifier.weight(1f)) {
                    Text("单抽 ×1")
                }
                Button(onClick = onTen, enabled = state.wallet.tenPullTickets > 0, modifier = Modifier.weight(1f)) {
                    Text("十连 ×1")
                }
            }
        }
        if (lastDraw.isNotEmpty()) {
            item { Text("本次结果", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(lastDraw, key = { it.id }) { reward ->
                StudyPaperCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = rewardColor(reward.type), shape = CircleShape) {
                            Icon(rewardIcon(reward.type), null, modifier = Modifier.padding(12.dp), tint = StudyInk)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(reward.title, fontWeight = FontWeight.Bold)
                            Text(reward.description, color = StudyMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (state.drawHistory.isNotEmpty()) {
            item { Text("最近抽取", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(state.drawHistory.take(10), key = { it.id }) { reward ->
                ListItem(
                    headlineContent = { Text(reward.title) },
                    supportingContent = { Text(reward.description) },
                    leadingContent = { Icon(rewardIcon(reward.type), null) },
                    colors = ListItemDefaults.colors(containerColor = StudyCard),
                )
            }
        }
    }
}

@Composable
private fun AchievementScreen(state: ExamAppState, store: PostgraduateExamStore) {
    val unlocked = state.achievements.count { it.unlocked }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyPaperCard {
                Text("成就馆", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("已解锁 $unlocked / ${state.achievements.size}", color = StudyMuted)
                LinearProgressIndicator(
                    progress = { unlocked.toFloat() / state.achievements.size.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                    color = StudyWheat,
                )
                Text("奖励：每项成就可领取 1 张单抽券和 20 学习币。", color = StudyMuted, fontSize = 12.sp)
            }
        }
        items(state.achievements, key = { it.id }) { achievement ->
            StudyPaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EmojiEvents, null, tint = if (achievement.unlocked) Color(0xFFC89B23) else StudyMuted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(achievement.title, fontWeight = FontWeight.Bold)
                        Text(achievement.description, color = StudyMuted, fontSize = 13.sp)
                        LinearProgressIndicator(
                            progress = { (achievement.progress.toFloat() / achievement.target).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                            color = StudyWheat,
                        )
                        Text("${achievement.progress.coerceAtMost(achievement.target)} / ${achievement.target}", color = StudyMuted, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { store.claimAchievement(achievement.id) },
                        enabled = achievement.unlocked && !achievement.claimed,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(if (achievement.claimed) "已领" else "领取") }
                }
            }
        }
    }
}

@Composable
private fun StudyMetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = StudyCard),
        border = BorderStroke(1.dp, StudyBorder),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = StudyMuted)
            Spacer(Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(title, color = StudyMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StudyPaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StudyCard),
        border = BorderStroke(1.dp, StudyBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

private fun rewardColor(type: DrawRewardType): Color = when (type) {
    DrawRewardType.PurpleFragment -> Color(0xFFE9D9F4)
    DrawRewardType.Douyin -> Color(0xFFDCE8F4)
    DrawRewardType.SideStory -> Color(0xFFFFE1C8)
    DrawRewardType.BlueFragment -> Color(0xFFD8E8F2)
    DrawRewardType.StudyCoin -> Color(0xFFFFEDB8)
}

private fun rewardIcon(type: DrawRewardType) = when (type) {
    DrawRewardType.PurpleFragment -> Icons.Outlined.Diamond
    DrawRewardType.Douyin -> Icons.Outlined.SmartDisplay
    DrawRewardType.SideStory -> Icons.Outlined.TheaterComedy
    DrawRewardType.BlueFragment -> Icons.Outlined.StarOutline
    DrawRewardType.StudyCoin -> Icons.Outlined.Paid
}
