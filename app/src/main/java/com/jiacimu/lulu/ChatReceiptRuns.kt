package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluChatMessage

internal data class ChatReceiptRun(val messages: List<LuluChatMessage>) {
    val key: String get() = messages.first().id
}

/** Display projection only: keeps every original receipt, link and timestamp. */
internal fun groupChatReceiptRuns(messages: List<LuluChatMessage>): List<ChatReceiptRun> {
    // Repeated prior.messages + message copies an ever-growing list for every
    // adjacent receipt. A long tool-activity streak used to become O(n²) before
    // a LazyColumn could even begin measuring its visible items.
    val grouped = mutableListOf<MutableList<LuluChatMessage>>()
    val seen = HashSet<String>()
    for (message in messages) {
        if (!seen.add(message.id)) continue
        val previous = grouped.lastOrNull()
        if (message.sender == LuluChatMessage.Sender.System &&
            previous?.firstOrNull()?.sender == LuluChatMessage.Sender.System) {
            previous.add(message)
        } else grouped.add(mutableListOf(message))
    }
    return grouped.map { ChatReceiptRun(it.toList()) }
}

@Composable
internal fun QqSystemReceiptGroup(run: ChatReceiptRun) {
    var expanded by rememberSaveable(run.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(.86f)) {
                Text("活动记录 · ${run.messages.size} 条", color = QqMuted, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = QqMuted)
            }
        }
        if (expanded) run.messages.forEach { QqSystemReceiptRow(it) }
    }
}
