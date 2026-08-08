package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.window.Dialog
import com.jiacimu.lulu.ai.VisionModelService
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

    if (composing) {
        MomentsComposePage(
            onBack = { composing = false },
            onPublish = { content, imageUri, imageDescription ->
                val post = MomentsStore.publishUser(content, imageUri, imageDescription)
                composing = false
                if (post != null) scope.launch { MomentsStore.letCharactersReact(post.id) }
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(LuluColors.Paper)) {
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
                            Text("可以发文字或图片，角色会像真实朋友圈一样互相点赞、评论和回复。", color = LuluColors.Muted)
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

    if (signatureEditing) {
        var draft by remember(signature) { mutableStateOf(signature) }
        Dialog(onDismissRequest = { signatureEditing = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = LuluColors.Paper,
                contentColor = LuluColors.Ink,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, LuluColors.Border),
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("修改签名", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(80) },
                        placeholder = { Text("写一句此刻的签名") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LuluColors.Ink,
                            unfocusedBorderColor = LuluColors.Border,
                            focusedContainerColor = LuluColors.Card,
                            unfocusedContainerColor = LuluColors.Card,
                        ),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { signatureEditing = false },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, LuluColors.Border),
                        ) { Text("取消", color = LuluColors.Ink) }
                        Button(
                            onClick = {
                                signature = draft.trim().ifBlank { "生活正在发生" }
                                prefs.edit().putString("moments_signature", signature).apply()
                                signatureEditing = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LuluColors.Wheat,
                                contentColor = LuluColors.OnWheat,
                            ),
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentsComposePage(
    onBack: () -> Unit,
    onPublish: (String, String?, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<String?>(null) }
    var publishing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            imageUri = uri.toString()
            notice = ""
        }
    }
    BackHandler(enabled = !publishing, onBack = onBack)

    fun publish() {
        if (publishing || (content.isBlank() && imageUri.isNullOrBlank())) return
        val selectedImage = imageUri
        if (selectedImage.isNullOrBlank()) {
            onPublish(content, null, "")
            return
        }
        publishing = true
        notice = "正在让识图模型看这张图片…"
        scope.launch {
            VisionModelService.describeImage(context, selectedImage, content)
                .onSuccess { description -> onPublish(content, selectedImage, description) }
                .onFailure { error ->
                    publishing = false
                    notice = error.message ?: "识图失败，请检查识图模型设置"
                }
        }
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("发动态", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !publishing) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    Button(
                        onClick = ::publish,
                        enabled = !publishing && (content.isNotBlank() || !imageUri.isNullOrBlank()),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuluColors.Wheat,
                            contentColor = LuluColors.OnWheat,
                            disabledContainerColor = LuluColors.CardStrong,
                            disabledContentColor = LuluColors.Muted,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (publishing) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (publishing) "识图中" else "发布", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = LuluColors.Card,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, LuluColors.Border),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(2_000) },
                    placeholder = { Text(if (imageUri == null) "分享此刻发生的事情…" else "给这张图片配一句话…") },
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    minLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
            }
            if (!imageUri.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = LuluColors.Card,
                    border = BorderStroke(1.dp, LuluColors.Border),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LuluSelectedPhoto(imageUri = imageUri, modifier = Modifier.fillMaxWidth().height(190.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !publishing,
                            ) {
                                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("换一张")
                            }
                            OutlinedButton(
                                onClick = { imageUri = null; notice = "" },
                                modifier = Modifier.weight(1f),
                                enabled = !publishing,
                            ) {
                                Icon(Icons.Outlined.Close, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("移除")
                            }
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !publishing,
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("添加图片")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (notice.isNotBlank()) {
                    Text(
                        notice,
                        modifier = Modifier.weight(1f),
                        color = if (publishing) LuluColors.Muted else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text("${content.length} / 2000", color = LuluColors.Muted, fontSize = 11.sp)
            }
        }
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
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
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
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("朋友圈", post.content))
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
                Text(post.content, color = LuluColors.Ink, fontSize = 15.sp, lineHeight = 22.sp)
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
                            val replyTarget = when (comment.replyToCharacterId) {
                                "__user__" -> "我"
                                null -> null
                                else -> characterNames[comment.replyToCharacterId]
                            }
                            Text(
                                if (replyTarget.isNullOrBlank()) "$commenter：${comment.content}" else "$commenter 回复 $replyTarget：${comment.content}",
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
