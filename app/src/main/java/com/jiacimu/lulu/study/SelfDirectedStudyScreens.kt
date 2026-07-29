package com.jiacimu.lulu.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private enum class SelfDirectedTodayView(val label: String) {
    Tasks("我的今日待办"), Weekly("本周任务池"), Tomorrow("明日待办"), Tips("短提醒"),
}

@Composable
internal fun StudyTodayScreenV2(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenFocus: () -> Unit,
) {
    var view by remember { mutableStateOf(SelfDirectedTodayView.Tasks) }
    var newTask by remember { mutableStateOf("") }
    var pomodoros by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.date == today.toString() }
    val tomorrowTasks = state.tasks.filter { it.date == today.plusDays(1).toString() }
    val weekly = state.planItems.filter { it.range == StudyPlanRange.Weekly && !it.completed }
    val todayTips = state.tips.filter { it.date == today.toString() }
    val completed = todayTasks.count(StudyTask::completed)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("今天由你自己安排", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("系统只维护月计划、周任务池和短提醒，不替你决定哪一天休息。", color = StudyDesign.muted)
                    }
                    Surface(color = StudyDesign.wheatSoft, shape = MaterialTheme.shapes.large) {
                        Text("$completed/${todayTasks.size}", Modifier.padding(horizontal = 13.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
                    }
                }
                if (todayTasks.isNotEmpty()) StudyProgress(completed.toFloat() / todayTasks.size)
                Button(onClick = onOpenFocus, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Timer, null)
                    Spacer(Modifier.width(7.dp))
                    Text("开始番茄钟")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StudyMetric("有效分钟", state.profile.totalStudyMinutes.toString(), Modifier.weight(1f))
                StudyMetric("番茄钟", state.profile.totalPomodoros.toString(), Modifier.weight(1f))
                StudyMetric("词汇", state.profile.vocabularyReviewed.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelfDirectedTodayView.entries.forEach { item ->
                    FilterChip(selected = item == view, onClick = { view = item }, label = { Text(item.label) })
                }
            }
        }
        when (view) {
            SelfDirectedTodayView.Tasks -> {
                item {
                    StudyCard {
                        Text("从周任务池里挑今天要做的", fontWeight = FontWeight.Bold)
                        Text("休息日可以一条都不加。普通学习日只挑你今天现实能完成的内容。", color = StudyDesign.muted)
                        OutlinedTextField(
                            value = newTask,
                            onValueChange = { newTask = it },
                            label = { Text("今天自己选择的任务") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("预计番茄钟", color = StudyDesign.muted)
                            IconButton(onClick = { pomodoros = (pomodoros - 1).coerceAtLeast(1) }) { Icon(Icons.Outlined.Remove, null) }
                            Text(pomodoros.toString(), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { pomodoros = (pomodoros + 1).coerceAtMost(12) }) { Icon(Icons.Outlined.Add, null) }
                            Spacer(Modifier.weight(1f))
                            Button(
                                enabled = newTask.isNotBlank(),
                                onClick = { store.addTask(newTask, pomodoros); newTask = ""; pomodoros = 1 },
                            ) { Text("添加") }
                        }
                    }
                }
                if (todayTasks.isEmpty()) {
                    item { StudyCard { Text("今天还没有自行选择待办。完整休息日可以保持为空。", color = StudyDesign.muted) } }
                } else {
                    items(todayTasks, key = StudyTask::id) { task -> SelfDirectedTaskRow(task, store) }
                }
                item {
                    StudyCard {
                        Text("词汇滚动复习", fontWeight = FontWeight.Bold)
                        Text("只记录真实完成量；单词不能替代英语真题主训练。", color = StudyDesign.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(20, 50, 100).forEach { amount ->
                                AssistChip(
                                    onClick = { store.reviewVocabulary(amount); message = "词汇复习 +$amount" },
                                    label = { Text("+$amount") },
                                )
                            }
                        }
                    }
                }
            }
            SelfDirectedTodayView.Weekly -> {
                item {
                    StudyCard {
                        Text("本周任务池", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("这些是周验收目标，不代表每个自然日都必须完成。具体日期和休息日由你自己分配。", color = StudyDesign.muted)
                    }
                }
                if (weekly.isEmpty()) {
                    item { StudyCard { Text("当前没有未完成周计划，可到“计划”页补充。", color = StudyDesign.muted) } }
                } else {
                    items(weekly, key = StudyPlanItem::id) { item ->
                        StudyCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = item.completed, onCheckedChange = { store.togglePlanItem(item.id) })
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, fontWeight = FontWeight.Bold)
                                    if (item.note.isNotBlank()) Text(item.note, color = StudyDesign.muted)
                                }
                            }
                            OutlinedButton(
                                onClick = { newTask = item.title; view = SelfDirectedTodayView.Tasks },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("把它作为今天的候选") }
                        }
                    }
                }
            }
            SelfDirectedTodayView.Tomorrow -> {
                item {
                    StudyCard {
                        Text("明日待办由你决定", fontWeight = FontWeight.Bold)
                        Text("只在你已经明确明天要做什么时添加；不提前制造整天压力。", color = StudyDesign.muted)
                        OutlinedTextField(newTask, { newTask = it }, label = { Text("明日任务") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(
                            enabled = newTask.isNotBlank(),
                            onClick = { store.addTask(newTask, pomodoros, today.plusDays(1)); newTask = "" },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("添加明日待办") }
                    }
                }
                if (tomorrowTasks.isEmpty()) item { StudyCard { Text("明天还没有待办。", color = StudyDesign.muted) } }
                items(tomorrowTasks, key = StudyTask::id) { task -> SelfDirectedTaskRow(task, store) }
            }
            SelfDirectedTodayView.Tips -> {
                item {
                    StudyCard {
                        Text("每日短提醒", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("只提醒健康启动和执行原则，不生成钟点表或固定休息日。", color = StudyDesign.muted)
                    }
                }
                if (todayTips.isEmpty()) item { StudyCard { Text("今天没有短提醒。", color = StudyDesign.muted) } }
                items(todayTips, key = StudyTip::id) { tip -> StudyCard { Text(tip.text) } }
            }
        }
        if (message.isNotBlank()) item { StudyMessage(message, false) }
    }
}

@Composable
internal fun StudyPlanScreenV2(state: StudyState, store: PostgraduateExamStore) {
    var range by remember { mutableStateOf(StudyPlanRange.Weekly) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val items = state.planItems.filter { it.range == range }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("月计划与周任务池", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("助手负责阶段目标和周验收；主人负责具体日期、顺序、时段和休息日。", color = StudyDesign.muted)
                Text("未完成日任务回到周任务池重新分配，不把整套日程机械顺延，也不惩罚式补课。", color = StudyDesign.muted, fontSize = 12.sp)
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
                OutlinedTextField(title, { title = it }, label = { Text("目标或任务池") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("范围、预计分钟或验收标准") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(
                    onClick = { store.addPlanItem(range, title, note); title = ""; note = "" },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存计划") }
            }
        }
        if (items.isEmpty()) item { StudyCard { Text("当前没有计划", color = StudyDesign.muted) } }
        items(items, key = StudyPlanItem::id) { item ->
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = item.completed, onCheckedChange = { store.togglePlanItem(item.id) })
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        if (item.note.isNotBlank()) Text(item.note, color = StudyDesign.muted)
                    }
                    IconButton(onClick = { store.deletePlanItem(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
                }
            }
        }
    }
}

@Composable
private fun SelfDirectedTaskRow(task: StudyTask, store: PostgraduateExamStore) {
    StudyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.completed, onCheckedChange = { store.toggleTask(task.id) })
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold)
                Text("${task.pomodoroCompleted}/${task.pomodoroTarget} 个番茄钟 · 由你自己安排", color = StudyDesign.muted, fontSize = 12.sp)
                StudyProgress(task.pomodoroCompleted.toFloat() / task.pomodoroTarget.coerceAtLeast(1))
            }
            IconButton(onClick = { store.deleteTask(task.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
        }
    }
}
