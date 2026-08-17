package com.jiacimu.lulu

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
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
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
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MomentAuthorType
import com.jiacimu.lulu.data.MomentsStore
import com.jiacimu.lulu.design.LuluColors

@Composable
fun MomentsScreen() {
    val context = LocalContext.current
    val posts by MomentsStore.posts.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val prefs = remember { context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE) }
    val userName = remember { prefs.getString("display_name", "我").orEmpty().ifBlank { "我" } }
    val userAvatar = remember { prefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) }
    val userAvatarUri = remember { prefs.getString("avatar_uri", null) }
    var composing by remember { mutableStateOf(false) }
    var signatureEditing by remember { mutableStateOf(false) }
    var coverUri by remember { mutableStateOf(prefs.getString("moments_cover_uri", null)) }
    var signature by remember {
        mutableStateOf(prefs.getString("moments_signature", "生活正在发生").orEmpty().ifBlank { "生活正在发生" })
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            coverUri = uri.toString()
            prefs.edit().putString("moments_cover_uri", coverUri).apply()
        }
    }

    LaunchedEffect(Unit) { MomentsStore.markCharacterPostsSeen() }

    if (composing) {
        MomentsComposePage(
            onBack = { composing = false },
            onPublish = { content, imageUri, imageDescription ->
                MomentsStore.publishUser(content, imageUri, imageDescription)
                composing = false
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
                    Box(
                        modifier = Modifier.fillParentMaxHeight(0.55f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Collections, null, tint = LuluColors.Muted, modifier = Modifier.size(46.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("朋友圈还是空的", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "朋友圈是分享日常，不需要定期更新。角色看见后会按自己的性格决定点赞、评论或只是看看。",
                                color = LuluColors.Muted,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(horizontal = 28.dp),
                            )
                        }
                    }
                }
            } else {
                items(posts, key = { it.id }) { post ->
                    MomentPostCard(
                        post = post,
                        userName = userName,
                        userAvatar = userAvatar,
                        userAvatarUri = userAvatarUri,
                        characterNames = characters.mapValues { it.value.displayName },
                        onLike = { MomentsStore.toggleUserLike(post.id) },
                        onComment = { text -> MomentsStore.addUserComment(post.id, text) },
                        onCallCharacters = { MomentsStore.requestCharactersReact(post.id) },
                        onReply = { comment, text -> MomentsStore.addUserReply(post.id, comment.id, text) },
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
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
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
                            colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                        ) { Text("保存") }
                    }
                }
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
            modifier = Modifier.align(Alignment.BottomStart)
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
            Row(
                Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("更换背景", color = Color.White, fontSize = 10.sp)
            }
        }
    }
    Spacer(Modifier.height(46.dp))
}
