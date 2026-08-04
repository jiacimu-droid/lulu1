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
import java.time.LocalDate

@Composable
internal fun StudyTodayScreenV2(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenFocus: () -> Unit,
) {
    var newTask by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.date == today.toString() }
    val completed = todayTasks.count(StudyTask::completed)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("今日待办", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Surface(color = StudyDesign.wheatSoft, shape = MaterialTheme.shapes.large) {
                        Text("$completed/${todayTasks.size}", Modifier.padding(horizontal = 13.dp, vertical = 10.dp), fontWeight = FontWeight.Bold)
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
                    onClick = { store.addTask(newTask, 1); newTask = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("添加") }
            }
        }
        if (todayTasks.isEmpty()) {
            item { StudyCard { Text("今天还没有待办。", color = StudyDesign.muted) } }
        } else {
            items(todayTasks, key = StudyTask::id) { task -> SelfDirectedTaskRow(task, store) }
        }
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
