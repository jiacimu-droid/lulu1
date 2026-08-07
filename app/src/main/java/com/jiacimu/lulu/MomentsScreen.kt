package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MomentAuthorType
import com.jiacimu.lulu.data.MomentPost
import com.jiacimu.lulu.data.MomentsStore
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@Composable
fun MomentsScreen() {
    val context = LocalContext.current
    val posts by MomentsStore.posts.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val prefs = remember { context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE) }
    val userName = remember { prefs.getString("display_name", "我").orEmpty().ifBlank { "我" } }
    val userAvatar = remember { prefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) }
    val userAvatarUri = remember { prefs.getString("avatar_uri", null) }
    val scope = rememberCoroutineScope()
    var composing by remember { mutableStateOf(false) }
    var signatureEditing by remember { mutableStateOf(false) }
    var coverUri by remember { mutableStateOf(prefs.getString("moments_cover_uri", null)) }
    var signature by remember {
        mutableStateOf(prefs.getString("moments_signature", "生活正在发生").orEmpty().ifBlank { "生活正在发生" })
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            coverUri = uri.toString()
            prefs.edit().putString("moments_cover_uri", coverUri).apply()
        }
    }

    LaunchedEffect(Unit) { MomentsStore.markCharacterPostsSeen() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "moments-header") {
                MomentsHeader(
                    userName = userName,
                    userAvatar = userAvatar,
                    userAvatarUri = userAvatarUri,
                    coverUri = coverUri,
                    signature = signature,
                    onCoverClick = {
                        coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onSignatureClick = { signatureEditing = true },
                )
            }
            if (posts.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillParentMaxHeight(0.55f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Collections, null, tint = LuluColors.Muted, modifier = Modifier.size(46.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("朋友圈还是空的", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("发布第一条动态后，每个角色都会来点赞和评论。", color = LuluColors.Muted)
                        }
                    }
                }
            } else {
                items(posts, key = MomentPost::id) { post ->
                    MomentPostCard(
                        post = post,
                        userName = userName,
                        userAvatar = userAvatar,
                        userAvatarUri = userAvatarUri,
                        characterNames = characters.mapValues { it.value.displayName },
                        onLike = { MomentsStore.toggleUserLike(post.id) },
                        onComment = { text -> MomentsStore.addUserComment(post.id, text) },
                        onDelete = { MomentsStore.delete(post.id) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { composing = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).navigationBarsPadding(),
            containerColor = LuluColors.Wheat,
            contentColor = LuluColors.OnWheat,
            icon = { Icon(Icons.Outlined.Edit, null) },
            text = { Text("发动态") },
        )
    }

    if (composing) {
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { composing = false },
            title = { Text("发布朋友圈") },
            text = {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(2_000) },
                    placeholder = { Text("分享此刻发生的事情…") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = content.isNotBlank(),
                    onClick = {
                        val post = MomentsStore.publishUser(content)
                        composing = false
                        if (post != null) scope.launch { MomentsStore.letCharactersReact(post.id) }
                    },
                ) { Text("发布") }
            },
            dismissButton = { TextButton(onClick = { composing = false }) { Text("取消") } },
        )
    }

    if (signatureEditing) {
        var draft by remember(signature) { mutableStateOf(signature) }
        AlertDialog(
            onDismissRequest = { signatureEditing = false },
            title = { Text("修改朋友圈签名") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(80) },
                    label = { Text("签名") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    signature = draft.trim().ifBlank { "生活正在发生" }
                    prefs.edit().putString("moments_signature", signature).apply()
                    signatureEditing = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { signatureEditing = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MomentsHeader(
    userName: String,
    userAvatar: String,
    userAvatarUri: String?,
    coverUri: String?,
    signature: String,
    onCoverClick: () -> Unit,
    onSignatureClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(220.dp).clickable(onClick = onCoverClick)) {
        if (coverUri.isNullOrBlank()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1F2329), Color(0xFF59616C), Color(0xFFC9CED2)),
                    ),
                ),
            )
        } else {
            LuluSelectedPhoto(imageUri = coverUri, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f)))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp).offset(y = 34.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(userName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(3.dp, Color.White)) {
                LuluProfileAvatar(userAvatarUri, userAvatar, 72)
            }
        }
        Text(
            signature,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 20.dp)
                .clickable(onClick = onSignatureClick)
                .padding(vertical = 4.dp),
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 13.sp,
        )
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            color = Color.Black.copy(alpha = 0.28f),
            shape = RoundedCornerShape(99.dp),
        ) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("更换背景", color = Color.White, fontSize = 10.sp)
            }
        }
    }
    Spacer(Modifier.height(46.dp))
}

@Composable
private fun MomentPostCard(
    post: MomentPost,
    userName: String,
    userAvatar: String,
    userAvatarUri: String?,
    characterNames: Map<String, String>,
    onLike: () -> Unit,
    onComment: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val authorCharacter = post.authorCharacterId?.let(MigratedDomainStores.characters::get)
    val authorName = if (post.authorType == MomentAuthorType.User) userName else authorCharacter?.displayName.orEmpty().ifBlank { "角色" }
    val likedByUser = "__user__" in post.likedCharacterIds
    var menuExpanded by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        LuluProfileAvatar(
            imageUri = if (post.authorType == MomentAuthorType.User) userAvatarUri else authorCharacter?.avatarUri,
            fallback = if (post.authorType == MomentAuthorType.User) userAvatar else authorName.take(1),
            size = 48,
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(authorName, color = Color(0xFF475A75), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "更多", tint = LuluColors.Muted, modifier = Modifier.size(19.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("复制文字") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("朋友圈", post.content))
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除动态", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; deleting = true },
                        )
                    }
                }
            }
            Text(post.content, color = LuluColors.Ink, fontSize = 15.sp, lineHeight = 22.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(momentRelativeTime(post.createdAt), color = LuluColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onLike, modifier = Modifier.size(38.dp)) {
                    Icon(if (likedByUser) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "点赞", modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = { commenting = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, "评论", modifier = Modifier.size(19.dp))
                }
            }
            val visibleLikes = post.likedCharacterIds.filterNot { it == "__user__" }.mapNotNull { characterNames[it] } + if (likedByUser) listOf("我") else emptyList()
            if (visibleLikes.isNotEmpty() || post.comments.isNotEmpty()) {
                Surface(color = LuluColors.CardStrong, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (visibleLikes.isNotEmpty()) {
                            Text("♥ ${visibleLikes.joinToString("、")}", color = Color(0xFF475A75), fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        }
                        post.comments.forEach { comment ->
                            val commenter = if (comment.characterId == "__user__") "我" else characterNames[comment.characterId] ?: "角色"
                            Text(
                                "$commenter：${comment.content}",
                                color = LuluColors.Ink,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
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

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除这条朋友圈？") },
            text = { Text("删除后，这条动态及其对应的原始时间线记录会一起移除。") },
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
