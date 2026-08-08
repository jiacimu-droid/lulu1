package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
internal fun StudyTodayScreenV2(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenPomodoro: () -> Unit,
) {
    var newTask by remember { mutableStateOf("") }
    val today = LocalDate.now()
    val todayTasks = state.tasks.filter { it.date == today.toString() }
    val completed = todayTasks.count(StudyTask::completed)
    val progress = if (todayTasks.isEmpty()) 0f else completed.toFloat() / todayTasks.size

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                onClick = onOpenPomodoro,
                modifier = Modifier.fillMaxWidth(),
                color = StudyDesign.dark,
                contentColor = Color.White,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFF1F2228)),
                shadowElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = StudyDesign.wheat,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Timer, null, tint = StudyDesign.dark)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.pomodoro.running) "继续番茄钟" else "开始番茄钟",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                        )
                        Text(
                            if (state.pomodoro.running) "这一轮还在计时，回去继续" else "选好任务，开始今天的一段专注",
                            color = Color(0xFFC9CDD4),
                            fontSize = 11.sp,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, "进入番茄钟", tint = StudyDesign.wheat)
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = StudyDesign.card,
                border = BorderStroke(1.dp, StudyDesign.border),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = StudyDesign.wheatSoft) {
                            Icon(
                                Icons.Outlined.AddTask,
                                null,
                                tint = StudyDesign.dark,
                                modifier = Modifier.padding(7.dp).size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Text("加一件今天要完成的事", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    OutlinedTextField(
                        value = newTask,
                        onValueChange = { newTask = it },
                        placeholder = { Text("例如：刑法第14章课程") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                    )
                    Button(
                        enabled = newTask.isNotBlank(),
                        onClick = { store.addTask(newTask); newTask = "" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudyDesign.dark,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE3E0D8),
                            disabledContentColor = Color(0xFF9D9A93),
                        ),
                    ) { Text("添加到今日", fontWeight = FontWeight.Bold) }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("今日待办", fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Text("完成一项就划掉一项", color = StudyDesign.muted, fontSize = 11.sp)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = StudyDesign.wheatSoft) {
                    Text(
                        "$completed / ${todayTasks.size}",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Black,
                        color = StudyDesign.dark,
                    )
                }
            }
        }
        if (todayTasks.isEmpty()) {
            item { StudyCard { Text("今天还没有待办。", color = StudyDesign.muted) } }
        } else {
            items(todayTasks, key = StudyTask::id) { task -> SelfDirectedTaskRow(task, store) }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                shape = RoundedCornerShape(22.dp),
                color = StudyDesign.dark,
                contentColor = Color.White,
            ) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("今天的完成度", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("${(progress * 100).toInt()}%", color = StudyDesign.wheat, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = StudyDesign.wheat,
                        trackColor = Color(0xFF474B54),
                    )
                    Text(
                        if (todayTasks.isNotEmpty() && completed == todayTasks.size) "今天的清单已经全部完成。" else "不用一次做完，先把下一项推进。",
                        color = Color(0xFFC9CDD4),
                        fontSize = 11.sp,
                    )
                }
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
        color = if (task.completed) StudyDesign.wheatSoft.copy(alpha = 0.62f) else StudyDesign.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (task.completed) StudyDesign.wheat else StudyDesign.border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = task.completed,
                onCheckedChange = { store.toggleTask(task.id) },
                colors = CheckboxDefaults.colors(checkedColor = StudyDesign.dark, checkmarkColor = StudyDesign.wheat),
            )
            Text(
                task.title,
                Modifier.weight(1f),
                fontWeight = if (task.completed) FontWeight.Medium else FontWeight.Bold,
                color = if (task.completed) StudyDesign.muted else StudyDesign.ink,
            )
            IconButton(onClick = { store.deleteTask(task.id) }) {
                Icon(Icons.Outlined.DeleteOutline, "删除", tint = StudyDesign.muted)
            }
        }
    }
}
