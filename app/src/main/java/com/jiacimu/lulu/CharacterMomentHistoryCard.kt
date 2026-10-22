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
        if (row.detail.isNotBlank()) {
            // Let an authored inner voice breathe: prose is not a debug log.
            // Render metadata separately, without altering or truncating the saved text.
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.detail.lines().filter(String::isNotBlank).forEach { line ->
                    val isMetadata = listOf("想法：", "状态：", "动作：", "仍在权衡：",
                        "想调整：", "当时怎样理解这段关系：", "当时的念头：",
                        "当时挂心的事：").any(line::startsWith)
                    if (isMetadata) {
                        Text(line, fontSize = 13.sp, lineHeight = 22.sp,
                            color = LuluColors.Muted)
                    } else {
                        heartVoiceParagraphs(line).forEach { paragraph ->
                            Text(paragraph, fontSize = 14.sp, lineHeight = 26.sp,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        row.decisions.distinctBy { Triple(it.action, it.reason, it.result) }.forEach { decision ->
            val voice = row.moment?.innerThought.orEmpty()
            if (row.title != decision.action) Text("当时选择：${decision.action}", fontSize = 13.sp)
            if (decision.reason.isNotBlank() &&
                !CharacterHeartVoicePolicy.sameMeaning(decision.reason, voice) &&
                !CharacterHeartVoicePolicy.sameMeaning(decision.reason, row.detail)) {
                Text("缘由：${decision.reason}", fontSize = 13.sp, lineHeight = 21.sp,
                    color = LuluColors.Muted)
            }
            if (decision.result.isNotBlank()) Text("结果：${decision.result}",
                fontSize = 13.sp, lineHeight = 21.sp, color = LuluColors.Muted)
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

/** Presentation only: preserve every character and every intentional line break.
 * Split long unbroken Chinese prose at sentence boundaries, never mid-word.
 * Deliberately do not impose a maximum length or a fixed number of paragraphs.
 */
internal fun heartVoiceParagraphs(text: String): List<String> {
    if (text.length <= 140) return listOf(text)
    val sentences = Regex("(?<=[。！？!?；;])").split(text).filter(String::isNotEmpty)
    val result = mutableListOf<String>()
    var current = ""
    for (sentence in sentences) {
        if (current.isNotBlank() && current.length + sentence.length > 90) {
            result += current
            current = ""
        }
        current += sentence
    }
    if (current.isNotEmpty()) result += current
    return result.ifEmpty { listOf(text) }
}
