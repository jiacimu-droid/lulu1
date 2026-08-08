package com.jiacimu.lulu.study

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
                    modifier = Modifier.fillMaxWidth(0.96f).height(42.dp),
                    color = StudyDesign.dark,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF1F2228)),
                    shadowElevation = 2.dp,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.pomodoro.running) "继续番茄钟" else "开始番茄钟",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White,
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

    if (state.superMomentAvailable) {
        StudyAllTasksCelebrationOverlay(onDismiss = store::dismissSuperMomentCelebration)
    }
}

@Composable
private fun StudyAllTasksCelebrationOverlay(onDismiss: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "今日全清庆祝")
    val sparkle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1800), repeatMode = RepeatMode.Restart),
        label = "庆祝粒子",
    )

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(StudyDesign.wheatSoft, Color.White, StudyDesign.paper),
                    ),
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * .36f)
                repeat(36) { index ->
                    val angle = index * (PI * 2.0 / 36.0) + sparkle * .65f
                    val lane = 0.18f + (index % 7) * .045f
                    val radius = size.minDimension * (lane + sparkle * .12f)
                    val point = Offset(
                        center.x + cos(angle).toFloat() * radius,
                        center.y + sin(angle).toFloat() * radius,
                    )
                    drawCircle(
                        color = if (index % 3 == 0) StudyDesign.wheat else Color(0xFFFFD982),
                        radius = (3.5f + index % 4) * density,
                        center = point,
                        alpha = (1f - sparkle * .55f).coerceIn(.25f, 1f),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("✦  今日全清  ✦", color = StudyDesign.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "今日待办全部完成！",
                    color = StudyDesign.ink,
                    fontSize = 31.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "今天的努力已经一项一项好好收下啦。愿接下来的复习也继续顺顺利利。",
                    color = StudyDesign.muted,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                )
                Surface(
                    color = Color.White.copy(alpha = .92f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.5.dp, StudyDesign.wheat),
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("20连奖励已自动到账", color = StudyDesign.ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text("十连券 +2", color = StudyDesign.ink, fontSize = 27.sp, fontWeight = FontWeight.Black)
                        Text("每个首次完成的今日待办，也已分别结算夸夸值 +100", color = StudyDesign.muted, fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.dark, contentColor = Color.White),
                ) {
                    Text("收下今天的胜利", fontWeight = FontWeight.Black, fontSize = 15.sp)
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
