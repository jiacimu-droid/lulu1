package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores

/** Explicit recipient selection. The share itself never claims the role visited a page. */
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
        shape = RoundedCornerShape(25.dp),
        containerColor = Color(0xFFFFFEFE),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("分享给谁？", fontWeight = FontWeight.Bold, fontSize = 21.sp,
                    color = Color(0xFF25232B))
                Text("选择一个想一起看笔记的人", fontSize = 12.sp,
                    color = Color(0xFF929099))
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = Color(0xFFFFF4F3), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(43.dp).background(Color(0xFFFFE1E3), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center) {
                            Text("小", color = Color(0xFFE44C5B), fontWeight = FontWeight.Black,
                                fontSize = 23.sp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("小红书 · 笔记分享", fontSize = 10.sp,
                                color = Color(0xFFE44C5B), fontWeight = FontWeight.SemiBold)
                            Text(preview.take(160), maxLines = 2, fontSize = 13.sp,
                                color = Color(0xFF373139), lineHeight = 18.sp,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Column(Modifier.heightIn(max = 322.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recipients.isEmpty()) Text("还没有私聊，先在聊天页创建一个角色会话。",
                        fontSize = 13.sp, color = Color(0xFF858189))
                    recipients.forEach { chat ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(chat) },
                            color = Color(0xFFF9F8F8),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFF0EDEF)),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(38.dp).background(Color(0xFFEDE6F1), CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text(chat.title.take(1).ifBlank { "露" },
                                        color = Color(0xFF675477), fontWeight = FontWeight.SemiBold)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(chat.title.ifBlank { chat.characterId }, fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium, color = Color(0xFF302D34))
                                    Text("发送到私聊", fontSize = 10.sp, color = Color(0xFF9C99A0))
                                }
                                Text("›", fontSize = 21.sp, color = Color(0xFFAAA3AA))
                            }
                        }
                    }
                }
                Text("分享成功后可以在聊天记录里查看，角色是否读到正文以实际阅读结果为准。",
                    fontSize = 11.sp, color = Color(0xFFA09BA1), lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("暂不分享", color = Color(0xFF7D7880)) } },
    )
}
