package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.data.CompanionPresenceState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CompanionPresenceDialog(
    characterName: String,
    state: CompanionPresenceState?,
    history: List<CompanionPresenceState>,
    onDismiss: () -> Unit,
) {
    var historySelected by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            color = Color.White,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("INNER MOMENT", color = Color(0xFF7A7A7E), fontSize = 9.sp, letterSpacing = 1.5.sp)
                Text("$characterName · 此刻", color = Color(0xFF1D1D1F), fontWeight = FontWeight.Bold, fontSize = 23.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !historySelected, onClick = { historySelected = false }, label = { Text("当前") })
                    FilterChip(selected = historySelected, onClick = { historySelected = true }, label = { Text("历史") })
                }
                if (!historySelected) {
                    if (state == null) {
                        Text("还没有形成可查看的此刻状态。", color = Color(0xFF7A7A7E))
                    } else {
                        PresenceStateContent(state)
                    }
                } else {
                    val past = history.drop(if (history.firstOrNull()?.updatedAt == state?.updatedAt) 1 else 0)
                    if (past.isEmpty()) {
                        Text("还没有更早的心声与动作。", color = Color(0xFF7A7A7E))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(past, key = { it.updatedAt.toEpochMilli() }) { item ->
                                Surface(
                                    color = Color(0xFFF7F7F7),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                                ) {
                                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(item.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), color = Color(0xFF7A7A7E), fontSize = 11.sp)
                                        if (item.innerThought.isNotBlank()) Text("心声 · ${item.innerThought}", color = Color(0xFF1D1D1F), fontSize = 14.sp)
                                        if (item.gesture.isNotBlank()) Text("动作 · ${item.gesture}", color = Color(0xFF5F5F63), fontSize = 13.sp)
                                        val status = listOf(item.mood, item.statusText).filter(String::isNotBlank).distinct().joinToString(" · ")
                                        if (status.isNotBlank()) Text(status, color = Color(0xFF7A7A7E), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292929), contentColor = Color.White),
                ) { Text("收好这一刻") }
            }
        }
    }
}

@Composable
private fun PresenceStateContent(state: CompanionPresenceState) {
    if (state.statusText.isNotBlank() || state.gesture.isNotBlank() || state.innerThought.isNotBlank() || state.mood.isNotBlank()) {
        PresenceDialogSection("此刻动作", state.gesture.ifBlank { "此刻没有留下明确的动作或神态。" })
        if (state.innerThought.isNotBlank()) PresenceDialogSection("心声", state.innerThought)
        val status = listOf(state.mood, state.statusText).filter(String::isNotBlank).distinct().joinToString(" · ")
        if (status.isNotBlank()) PresenceDialogSection("状态", status)
        Text(
            state.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
            color = Color(0xFF7A7A7E),
            fontSize = 11.sp,
        )
    }
    state.lastPerceptionAt?.let { perceivedAt ->
        val line = buildString {
            append(perceivedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
            state.lastPerceptionNote.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
        }
        PresenceDialogSection("感知线路", line)
    }
}

@Composable
private fun PresenceDialogSection(title: String, content: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF4F4F4),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color(0xFF7A7A7E), fontSize = 11.sp)
            Text(content, color = Color(0xFF1D1D1F), fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}
