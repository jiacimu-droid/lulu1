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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            StudyCard {
                Button(onClick = onOpenFocus, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Timer, null)
                    Spacer(Modifier.width(7.dp))
                    Text("开始番茄钟")
                }
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