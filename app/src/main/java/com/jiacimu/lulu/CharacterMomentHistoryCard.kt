package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

@Composable
internal fun CharacterMomentHistoryCard(characterId: String, row: CharacterStateHistoryRow) {
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    CharacterRecordCard {
        Text(recordTime(row.at), fontSize = 11.sp, color = LuluColors.Muted)
        Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (row.detail.isNotBlank()) Text(row.detail, fontSize = 14.sp, lineHeight = 23.sp)
        row.decisions.forEach { decision ->
            if (row.title != decision.action) Text("当时选择：${decision.action}", fontSize = 13.sp)
            if (decision.reason.isNotBlank()) Text("缘由：${decision.reason}", fontSize = 13.sp, lineHeight = 21.sp)
            if (decision.result.isNotBlank()) Text("结果：${decision.result}", fontSize = 13.sp, lineHeight = 21.sp)
        }
        Row {
            if (row.evidenceIds.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起关联事件" else "关联事件")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { deleting = true }) { Text("删除", color = LuluColors.Muted) }
        }
        if (expanded) CharacterEvidenceDetails(characterId, row.evidenceIds)
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这条状态历史？") },
        text = { Text("删除这条心声、状态或选择记录，保留原始聊天及已发生的行动。") },
        confirmButton = { TextButton(onClick = { CharacterStateHistory.delete(characterId, row); deleting = false }) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}
