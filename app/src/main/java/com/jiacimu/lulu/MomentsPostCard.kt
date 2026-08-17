package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MomentAuthorType
import com.jiacimu.lulu.data.MomentComment
import com.jiacimu.lulu.data.MomentPost
import com.jiacimu.lulu.design.LuluColors
import java.time.Duration
import java.time.Instant

@Composable
internal fun MomentPostCard(
    post: MomentPost,
    userName: String,
    userAvatar: String,
    userAvatarUri: String?,
    characterNames: Map<String, String>,
    onLike: () -> Unit,
    onComment: (String) -> Unit,
    onCallCharacters: () -> Unit,
    onReply: (MomentComment, String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val authorCharacter = post.authorCharacterId?.let(MigratedDomainStores.characters::get)
    val authorName = if (post.authorType == MomentAuthorType.User) userName
    else authorCharacter?.displayName.orEmpty().ifBlank { "角色" }
    val likedByUser = "__user__" in post.likedCharacterIds
    val textOnly = post.content.isNotBlank() && post.imageUri.isNullOrBlank()
    var menuExpanded by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf(false) }
    var replyTarget by remember(post.id) { mutableStateOf<MomentComment?>(null) }
    var deleting by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (textOnly) 10.dp else 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LuluProfileAvatar(
            imageUri = if (post.authorType == MomentAuthorType.User) userAvatarUri else authorCharacter?.avatarUri,
            fallback = if (post.authorType == MomentAuthorType.User) userAvatar else authorName.take(1),
            size = 48,
        )
        Spacer(Modifier.width(11.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (textOnly) 5.dp else 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorName,
                    color = Color(0xFF475A75),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "更多", tint = LuluColors.Muted, modifier = Modifier.size(19.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = LuluColors.Paper,
                    ) {
                        if (post.content.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("复制文字") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("朋友圈", post.content))
                                    menuExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("删除动态", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; deleting = true },
                        )
                    }
                }
            }

            if (post.content.isNotBlank()) {
                if (textOnly) {
                    // Pure text is the post itself, not a caption waiting for a missing image.
                    Text(
                        text = post.content,
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                        color = LuluColors.Ink,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                    )
                } else {
                    Text(post.content, color = LuluColors.Ink, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }

            if (!post.imageUri.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = LuluColors.CardStrong,
                ) {
                    LuluSelectedPhoto(imageUri = post.imageUri, modifier = Modifier.fillMaxWidth().height(220.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = if (textOnly) 0.dp else 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(momentRelativeTime(post.createdAt), color = LuluColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onLike, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (likedByUser) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        "点赞",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(5.dp))
                FilledTonalIconButton(
                    onClick = { if (post.authorType == MomentAuthorType.User) onCallCharacters() else commenting = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (post.authorType == MomentAuthorType.User) Icons.Outlined.MarkChatRead else Icons.Outlined.ChatBubbleOutline,
                        if (post.authorType == MomentAuthorType.User) "呼唤全部角色来看" else "评论",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            val visibleLikes = post.likedCharacterIds
                .filterNot { it == "__user__" }
                .mapNotNull { characterNames[it] } + if (likedByUser) listOf("我") else emptyList()
            if (visibleLikes.isNotEmpty() || post.comments.isNotEmpty()) {
                Surface(
                    color = LuluColors.CardStrong,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (visibleLikes.isNotEmpty()) {
                            Text(
                                "♥ ${visibleLikes.joinToString("、")}",
                                color = Color(0xFF475A75),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                            )
                        }
                        post.comments.forEach { comment ->
                            val commenter = if (comment.characterId == "__user__") "我"
                            else characterNames[comment.characterId] ?: "角色"
                            val targetName = when (comment.replyToCharacterId) {
                                "__user__" -> "我"
                                null -> null
                                else -> characterNames[comment.replyToCharacterId]
                            }
                            val canReply = comment.characterId != "__user__"
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (canReply) Modifier.clickable { replyTarget = comment } else Modifier)
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    if (targetName.isNullOrBlank()) "$commenter：${comment.content}"
                                    else "$commenter 回复 $targetName：${comment.content}",
                                    modifier = Modifier.weight(1f),
                                    color = LuluColors.Ink,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                                if (canReply) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("回复", color = Color(0xFF637A9A), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = LuluColors.Border, modifier = Modifier.padding(start = 75.dp))

    if (commenting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { commenting = false },
            containerColor = LuluColors.Paper,
            title = { Text("评论 $authorName") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(500) },
                    placeholder = { Text("写下评论…") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { onComment(text); commenting = false },
                ) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { commenting = false }) { Text("取消") } },
        )
    }

    replyTarget?.let { target ->
        val targetName = characterNames[target.characterId] ?: "角色"
        var text by remember(target.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { replyTarget = null },
            containerColor = LuluColors.Paper,
            title = { Text("回复 $targetName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = LuluColors.CardStrong, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            target.content,
                            color = LuluColors.Muted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(500) },
                        placeholder = { Text("回复他的这条评论…") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = { onReply(target, text); replyTarget = null },
                ) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { replyTarget = null }) { Text("取消") } },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = LuluColors.Paper,
            title = { Text("删除这条朋友圈？") },
            text = { Text("删除后，这条动态及其对应的原始时间线和派生记忆会一起移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(); deleting = false }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }
}

private fun momentRelativeTime(time: Instant): String {
    val minutes = Duration.between(time, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> "${minutes / (24 * 60)}天前"
    }
}
