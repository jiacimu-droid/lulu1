package com.jiacimu.lulu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores

/** Pick a real private conversation; sharing does not imply the recipient already read a URL. */
@Composable
internal fun XhsShareToRoleDialog(
    preview: String,
    onDismiss: () -> Unit,
    onSelect: (LuluConversation) -> Unit,
) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val recipients = conversations.filter { it.groupChat == null }.distinctBy { it.characterId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享小红书给谁？") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(preview.take(180), maxLines = 3, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                    if (recipients.isEmpty()) Text("还没有私聊。先在聊天页创建一个角色对话。")
                    recipients.forEach { chat ->
                        TextButton(onClick = { onSelect(chat) }, modifier = Modifier.fillMaxWidth()) {
                            Text(chat.title.ifBlank { chat.characterId }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
