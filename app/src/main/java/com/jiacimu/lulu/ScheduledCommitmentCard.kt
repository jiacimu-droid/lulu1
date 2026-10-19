package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.*
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch

@Composable
internal fun ScheduledCommitmentCard(task: CommitmentTask) {
    var detail by remember(task.id) { mutableStateOf(false) }
    val label = when (task.status) {
        CommitmentTaskStatus.NeedsClarification -> "待明确"
        CommitmentTaskStatus.Scheduled -> "已安排"
        CommitmentTaskStatus.Running -> "执行中"
        CommitmentTaskStatus.WaitingForFeedback -> if (task.dueAt == null) "持续跟进" else "已触发 · 等待反馈"
        CommitmentTaskStatus.Completed -> "已完成"
        CommitmentTaskStatus.Cancelled -> "已取消"
        CommitmentTaskStatus.Blocked -> "执行受阻"
        CommitmentTaskStatus.Expired -> "已过期"
    }
    CharacterRecordCard {
        Text(task.goal, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text((task.dueAt?.let(::recordTime) ?: "持续约定") + " · " + label,
            fontSize = 12.sp, color = LuluColors.Muted)
        if (task.lastActionResult.isNotBlank()) Text(task.lastActionResult, fontSize = 13.sp)
        Row {
            TextButton(onClick = { detail = !detail }) { Text(if (detail) "收起依据" else "查看约定依据") }
            if (task.status.isActive()) TextButton(onClick = { CommitmentTaskStore.cancel(task.id, "用户从日程取消约定") }) {
                Text("取消约定", color = MaterialTheme.colorScheme.error)
            }
        }
        if (detail) CharacterEvidenceDetails(task.characterId, task.sourceEventIds)
    }
}

/** Keeps unscheduled historical promises accessible without inventing a timer or a second task. */
@Composable
internal fun LegacyPromiseCard(entry: LexiconEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CharacterRecordCard {
        Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(entry.content, fontSize = 14.sp)
        Text(if (entry.status == LexiconStatus.Active) "未定时的约定" else "已结束的约定",
            fontSize = 11.sp, color = LuluColors.Muted)
        Row {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起依据" else "查看依据") }
            TextButton(onClick = { editing = true }) { Text("编辑") }
            TextButton(onClick = { scope.launch { LuluRepositories.lexicon.save(entry.copy(
                status = if (entry.status == LexiconStatus.Active) LexiconStatus.Resolved else LexiconStatus.Active,
                updatedAt = java.time.Instant.now())) } }) {
                Text(if (entry.status == LexiconStatus.Active) "标记结束" else "恢复约定")
            }
        }
        if (expanded) CharacterEvidenceDetails(entry.characterId, entry.sourceEventIds)
    }
    if (editing) LexiconEditorDialogV2(entry, false, onDismiss = { editing = false },
        onSave = { scope.launch { LuluRepositories.lexicon.save(it) }; editing = false },
        onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) }; editing = false })
}
