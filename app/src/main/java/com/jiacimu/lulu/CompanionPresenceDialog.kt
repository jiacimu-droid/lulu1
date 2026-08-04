package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    onDismiss: () -> Unit,
) {
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
                if (state == null) {
                    Text("还没有形成可查看的此刻状态。", color = Color(0xFF7A7A7E))
                } else {
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
