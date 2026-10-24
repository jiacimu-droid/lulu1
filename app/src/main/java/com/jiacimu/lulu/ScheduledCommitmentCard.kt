package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.*
import com.jiacimu.lulu.data.*
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared visual primitives: one language for self-plans, timed and undated promises. */
@Composable internal fun ScheduleCardShell(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)), tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable internal fun ScheduleStatusChip(
    label: String, warning: Boolean = false, complete: Boolean = false,
) {
    val background = when {
        warning -> Color(0xFFFFF2EB)
        complete -> Color(0xFFF0F5F1)
        else -> Color(0xFFF4F4F4)
    }
    val foreground = when {
        warning -> Color(0xFF9B5634)
        complete -> Color(0xFF426850)
        else -> Color(0xFF69696F)
    }
    Surface(color = background, shape = RoundedCornerShape(9.dp)) {
        Text(label, color = foreground, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable internal fun ScheduleSectionHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 16.sp, color = Color(0xFF262629), fontWeight = FontWeight.SemiBold)
        Text(count.toString(), fontSize = 12.sp, color = Color(0xFF96969A))
    }
}

@Composable internal fun ScheduledCommitmentCard(task: CommitmentTask) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    var menu by remember(task.id) { mutableStateOf(false) }
    var confirm by remember(task.id) { mutableStateOf<String?>(null) }
    var error by remember(task.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val label = when (task.status) {
        CommitmentTaskStatus.NeedsClarification -> "待明确"
        CommitmentTaskStatus.Scheduled -> "已安排"
        CommitmentTaskStatus.Running -> "执行中"
        CommitmentTaskStatus.WaitingForFeedback -> if (task.dueAt == null) "持续跟进" else "等待反馈"
        CommitmentTaskStatus.Completed -> "已完成"
        CommitmentTaskStatus.Cancelled -> "已取消"
        CommitmentTaskStatus.Blocked -> "执行受阻"
        CommitmentTaskStatus.Expired -> "已过期"
    }
    val done = task.status in setOf(CommitmentTaskStatus.Completed, CommitmentTaskStatus.Cancelled)
    val problem = task.status in setOf(CommitmentTaskStatus.Blocked, CommitmentTaskStatus.Expired)
    val time = task.dueAt?.atZone(ZoneId.systemDefault())
    val method = when (task.deliveryAction) {
        "start_call" -> "角色来电"
        "alarm_only" -> "本机铃声"
        "send_private_message" -> "私聊提醒"
        else -> if (time == null) "" else "方式待明确"
    }
    ScheduleCardShell {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(58.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "待定",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
                Text(if (task.repeat == "daily") "每天" else if (time != null) "约定" else "长期",
                    fontSize = 11.sp, color = Color(0xFF909096))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.goal, fontSize = 15.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium, color = Color(0xFF262629))
                ScheduleStatusChip(label, warning = problem, complete = done)
                if (task.repeat == "daily" && time != null)
                    Text("下次 · " + time.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                        color = Color(0xFF7A7A7E), fontSize = 12.sp)
                if (method.isNotBlank())
                    Text(method, fontSize = 12.sp, color = Color(0xFF7A7A7E))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, "约定操作", tint = Color(0xFF6F6F73))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (task.status.isActive()) DropdownMenuItem(text = { Text("取消约定") },
                        onClick = { menu = false; confirm = "cancel" })
                    DropdownMenuItem(text = { Text("删除记录") },
                        onClick = { menu = false; confirm = "delete" })
                }
            }
        }
        if (task.lastActionResult.isNotBlank()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF7F7F7)) {
                Text(task.lastActionResult, Modifier.fillMaxWidth().padding(11.dp),
                    fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xFF69696F),
                    maxLines = if (expanded) Int.MAX_VALUE else 2)
            }
        }
        TextButton(onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (expanded) "收起详情" else "查看详情与依据", fontSize = 12.sp)
        }
        if (expanded) {
            Text("执行方式 · " + method.ifBlank { "未设定" },
                fontSize = 12.sp, color = Color(0xFF7A7A7E))
            CharacterEvidenceDetails(task.characterId, task.sourceEventIds)
        }
        if (error.isNotBlank())
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (action == "delete") "删除这条约定？" else "取消这项约定？") },
            text = { Text(if (action == "delete") "记录删除后无法在日程中查看。" else "停止后续执行，已有记录仍保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (action == "cancel") {
                        runCatching { CommitmentTaskStore.cancel(task.id, "用户从日程取消约定") }
                            .onFailure { error = it.message ?: "取消失败" }
                    } else scope.launch {
                        runCatching {
                            check(CommitmentTaskStore.remove(task.id)) { "约定不存在" }
                            task.lexiconEntryId?.let { LuluRepositories.lexicon.delete(it) }
                        }.onFailure { error = it.message ?: "删除失败" }
                    }
                }) { Text(if (action == "delete") "删除" else "取消约定",
                    color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("返回") }
            })
    }
}

@Composable internal fun LegacyPromiseCard(entry: LexiconEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    var menu by remember(entry.id) { mutableStateOf(false) }
    var editing by remember(entry.id) { mutableStateOf(false) }
    var deleting by remember(entry.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScheduleCardShell {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("未定时", Modifier.width(58.dp), fontSize = 13.sp,
                color = Color(0xFF7A7A7E), fontWeight = FontWeight.Medium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    lineHeight = 22.sp, color = Color(0xFF262629))
                if (entry.content.isNotBlank()) Text(entry.content, fontSize = 13.sp,
                    lineHeight = 20.sp, color = Color(0xFF69696F))
                ScheduleStatusChip(if (entry.status == LexiconStatus.Active) "持续约定" else "已结束",
                    complete = entry.status != LexiconStatus.Active)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, "旧约定操作", tint = Color(0xFF6F6F73))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; editing = true })
                    DropdownMenuItem(text = { Text(if (entry.status == LexiconStatus.Active) "标记结束" else "恢复约定") },
                        onClick = {
                            menu = false
                            scope.launch { LuluRepositories.lexicon.save(entry.copy(
                                status = if (entry.status == LexiconStatus.Active) LexiconStatus.Resolved
                                    else LexiconStatus.Active,
                                updatedAt = java.time.Instant.now())) }
                        })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = true })
                }
            }
        }
        TextButton(onClick = { expanded = !expanded },
            contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (expanded) "收起依据" else "查看依据", fontSize = 12.sp)
        }
        if (expanded) CharacterEvidenceDetails(entry.characterId, entry.sourceEventIds)
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("删除这条约定？") },
        text = { Text("删除后无法在日程中查看。") },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                scope.launch { LuluRepositories.lexicon.delete(entry.id) }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("返回") } })
    if (editing) LexiconEditorDialogV2(entry, false, onDismiss = { editing = false },
        onSave = { scope.launch { LuluRepositories.lexicon.save(it) }; editing = false },
        onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) }; editing = false })
}
