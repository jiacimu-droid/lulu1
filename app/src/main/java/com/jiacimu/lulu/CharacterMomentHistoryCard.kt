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
    val moment = CharacterStateHistory.display(row)
    CharacterRecordCard {
        Text(recordTime(row.at), fontSize = 11.sp, color = LuluColors.Muted)
        val heading = row.title.takeUnless {
            it == moment.mood || it in setOf("当时的心声与状态", "当时的感想与取舍", "当时的念头")
        }.orEmpty().ifBlank { "这一刻" }
        Text(heading, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (moment.voice.isNotBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("心声", fontSize = 11.sp, color = LuluColors.Muted)
                moment.voice.lines().filter(String::isNotBlank).forEach { line ->
                    heartVoiceParagraphs(line).forEach { paragraph ->
                        Text(paragraph, fontSize = 15.sp, lineHeight = 27.sp)
                    }
                }
            }
        }
        val mood = moment.mood.split("·").map(String::trim)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        if (mood.isNotBlank() || moment.status.isNotBlank() || moment.gesture.isNotBlank()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (mood.isNotBlank() &&
                    !CharacterHeartVoicePolicy.sameMeaning(mood, moment.voice)) {
                    MomentFactLine("心情", mood)
                }
                if (moment.status.isNotBlank() &&
                    !CharacterHeartVoicePolicy.sameMeaning(moment.status, moment.voice)) {
                    MomentFactLine("状态", moment.status)
                }
                if (moment.gesture.isNotBlank() &&
                    !CharacterHeartVoicePolicy.sameMeaning(moment.gesture, moment.status)) {
                    MomentFactLine("动作", moment.gesture)
                }
            }
        }
        moment.otherThoughts.forEach { (label, content) ->
            MomentFactLine(label, content)
        }
        row.decisions.distinctBy { it.id }.forEach { decision ->
            // "Why" is useful only if it adds something to what the role
            // already thought or what the executor demonstrably accomplished.
            if (decision.action != row.title &&
                !CharacterHeartVoicePolicy.sameMeaning(decision.action, moment.gesture)) {
                MomentFactLine("选择", decision.action)
            }
            val alreadySaid = listOf(moment.voice, moment.status, moment.gesture,
                moment.otherThoughts.joinToString("；") { it.second })
            if (decision.reason.isNotBlank() && alreadySaid.none {
                    CharacterHeartVoicePolicy.sameMeaning(decision.reason, it)
                }) {
                MomentFactLine("缘由", decision.reason)
            }
            if (decision.result.isNotBlank() &&
                !CharacterHeartVoicePolicy.sameMeaning(decision.result, moment.gesture)) {
                MomentFactLine("结果", decision.result)
            }
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

@Composable
private fun MomentFactLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(label, modifier = Modifier.width(51.dp), fontSize = 11.sp,
            color = LuluColors.Muted, lineHeight = 22.sp)
        Text(value, modifier = Modifier.weight(1f), fontSize = 13.sp,
            lineHeight = 22.sp)
    }
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
