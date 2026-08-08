package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
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
    val progressPercent = if (todayTasks.isEmpty()) 0 else ((completed * 100f) / todayTasks.size).toInt()

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onOpenPomodoro,
                    modifier = Modifier.fillMaxWidth(0.96f).height(46.dp),
                    color = Color.White,
                    contentColor = StudyDesign.ink,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.5.dp, StudyDesign.wheat),
                    shadowElevation = 1.dp,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.pomodoro.running) "继续番茄钟" else "开始番茄钟",
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            color = StudyDesign.ink,
                        )
                    }
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
                    OutlinedTextField(
                        value = newTask,
                        onValueChange = { newTask = it },
                        placeholder = { Text("添加今日待办") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudyDesign.wheat,
                            unfocusedBorderColor = StudyDesign.border,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                        ),
                    )
                    Button(
                        enabled = newTask.isNotBlank(),
                        onClick = { store.addTask(newTask); newTask = "" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudyDesign.wheat,
                            contentColor = StudyDesign.ink,
                            disabledContainerColor = Color(0xFFF0EEE8),
                            disabledContentColor = Color(0xFF9D9A93),
                        ),
                    ) { Text("添加", fontWeight = FontWeight.Bold) }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "今日待办",
                    modifier = Modifier.weight(1f),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    color = StudyDesign.ink,
                )
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, StudyDesign.wheat),
                ) {
                    Text(
                        "$completed / ${todayTasks.size}   ·   $progressPercent%",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Black,
                        color = StudyDesign.ink,
                        fontSize = 13.sp,
                    )
                }
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
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.White,
                                selectedContainerColor = StudyDesign.wheatSoft,
                                selectedLabelColor = StudyDesign.ink,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = item == range,
                                borderColor = StudyDesign.border,
                                selectedBorderColor = StudyDesign.wheat,
                            ),
                        )
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("添加${if (range == StudyPlanRange.Weekly) "周" else "月"}计划", fontWeight = FontWeight.Bold, color = StudyDesign.ink)
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("计划内容") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = StudyDesign.wheat, unfocusedBorderColor = StudyDesign.border),
                )
                Button(
                    onClick = { store.addPlanItem(range, title, ""); title = "" },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                ) { Text("保存计划") }
            }
        }
        if (items.isEmpty()) item { StudyCard { Text("当前没有计划", color = StudyDesign.muted) } }
        items(items, key = StudyPlanItem::id) { item ->
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = StudyDesign.ink)
                    IconButton(onClick = { store.deletePlanItem(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除", tint = StudyDesign.muted) }
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
        color = if (task.completed) StudyDesign.wheatSoft.copy(alpha = 0.58f) else Color.White,
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
                colors = CheckboxDefaults.colors(checkedColor = StudyDesign.wheat, checkmarkColor = StudyDesign.ink),
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
