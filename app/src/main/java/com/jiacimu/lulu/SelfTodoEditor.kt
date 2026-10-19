package com.jiacimu.lulu

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.util.Locale

@Composable
private fun TodoFormLabel(number: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(number, color = TodoInk.coral, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(name, color = TodoInk.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TodoRepeatButton(text: String, active: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(45.dp),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (active) TodoInk.violet else TodoInk.line),
        color = if (active) TodoInk.violet else Color.White) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (active) Color.White else TodoInk.secondary,
                fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TodoDateTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 88.dp),
        shape = RoundedCornerShape(15.dp), color = Color.White,
        border = BorderStroke(1.dp, TodoInk.line)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(16.dp), tint = TodoInk.violet)
                Text(label, color = TodoInk.secondary, fontSize = 11.sp)
            }
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = TodoInk.title, maxLines = 2)
        }
    }
}

@Composable
internal fun TodoEditorDialog(
    original: TodoTask?,
    onDismiss: () -> Unit,
    onDelete: (TodoTask) -> Unit,
    onSave: (String, String, LocalDate, Int?, TodoRepeat) -> Boolean,
) {
    val context = LocalContext.current
    var title by remember(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var note by remember(original?.id) { mutableStateOf(original?.note.orEmpty()) }
    var date by remember(original?.id) { mutableStateOf(original?.firstDate ?: LocalDate.now()) }
    var minutes by remember(original?.id) { mutableStateOf(original?.minutes) }
    var repeat by remember(original?.id) { mutableStateOf(original?.repeat ?: TodoRepeat.ONCE) }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                .heightIn(max = 640.dp).imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = TodoInk.background,
            shadowElevation = 16.dp,
        ) {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(100.dp)
                        .background(Brush.horizontalGradient(
                            listOf(TodoInk.violetLight, Color(0xFFF8EAF0), Color(0xFFFAF4F1)))),
                ) {
                    TodoBotanical(Modifier.align(Alignment.CenterEnd).size(102.dp, 104.dp),
                        ink = TodoInk.violet.copy(alpha = .26f))
                    Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("A LITTLE THING TO DO", color = TodoInk.violet,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp)
                        Text(if (original == null) "新建待办" else "编辑待办",
                            color = TodoInk.title, fontSize = 25.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                        Icon(Icons.Outlined.Close, "取消并关闭",
                            tint = TodoInk.secondary, modifier = Modifier.size(21.dp))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 17.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TodoFormLabel("01", "要完成什么？")
                    TextField(value = title, onValueChange = { title = it },
                        placeholder = { Text("例如：背完今天的单词", color = TodoInk.secondary) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TodoInk.title, unfocusedTextColor = TodoInk.title,
                        ))
                    TextField(value = note, onValueChange = { note = it },
                        placeholder = { Text("补充一点备注（选填）", color = TodoInk.secondary) },
                        minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TodoInk.title, unfocusedTextColor = TodoInk.title,
                        ))
                    Spacer(Modifier.height(3.dp))
                    TodoFormLabel("02", "重复频率")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        TodoRepeatButton("仅一次", repeat == TodoRepeat.ONCE, Modifier.weight(1f)) {
                            repeat = TodoRepeat.ONCE
                        }
                        TodoRepeatButton("每天", repeat == TodoRepeat.DAILY, Modifier.weight(1f)) {
                            repeat = TodoRepeat.DAILY
                        }
                        TodoRepeatButton("每周", repeat == TodoRepeat.WEEKLY, Modifier.weight(1f)) {
                            repeat = TodoRepeat.WEEKLY
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    TodoFormLabel("03", "安排日期与时间")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        TodoDateTile(Icons.Outlined.CalendarMonth,
                            if (repeat == TodoRepeat.ONCE) "截止日期"
                            else if (repeat == TodoRepeat.DAILY) "开始日期" else "每周执行日",
                            date.format(todoVisualDate), Modifier.weight(1.14f)) {
                            DatePickerDialog(context,
                                { _, year, month, day -> date = LocalDate.of(year, month + 1, day) },
                                date.year, date.monthValue - 1, date.dayOfMonth).show()
                        }
                        TodoDateTile(Icons.Outlined.Schedule, "具体时间",
                            minutes?.let {
                                String.format(Locale.getDefault(), "%02d:%02d", it / 60, it % 60)
                            } ?: "不设时间", Modifier.weight(.86f)) {
                            val current = minutes ?: 9 * 60
                            TimePickerDialog(context,
                                { _, hour, minute -> minutes = hour * 60 + minute },
                                current / 60, current % 60, true).show()
                        }
                    }
                    if (minutes != null) {
                        TextButton(onClick = { minutes = null },
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("清除具体时间", fontSize = 11.sp, color = TodoInk.secondary)
                        }
                    }
                }
                HorizontalDivider(color = TodoInk.line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (original == null) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = TodoInk.secondary, fontSize = 13.sp)
                        }
                    } else {
                        IconButton(onClick = { onDelete(original) }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除待办", tint = TodoInk.coral)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (onSave(title.trim(), note.trim(), date, minutes, repeat)) onDismiss()
                        },
                        enabled = title.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TodoInk.violet,
                            disabledContainerColor = TodoInk.lavender,
                            disabledContentColor = TodoInk.secondary,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Text(if (original == null) "加入清单" else "保存修改",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TodoDeleteDialog(task: TodoTask, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(25.dp), containerColor = TodoInk.paper,
        icon = {
            Icon(Icons.Outlined.DeleteOutline, null,
                tint = TodoInk.coral, modifier = Modifier.size(30.dp))
        },
        title = { Text("删除这件待办？",
            color = TodoInk.title, fontWeight = FontWeight.SemiBold) },
        text = { Text("这项待办及其完成记录都会被删除，无法恢复。",
            color = TodoInk.secondary) },
        confirmButton = {
            Button(onClick = onConfirm,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TodoInk.coral)) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("保留", color = TodoInk.violet) }
        },
    )
}
