package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QqChatImageRow(
    image: QqChatImageMessage,
    mine: Boolean,
    characterName: String,
    characterAvatarUri: String?,
    userAvatar: String,
    userAvatarUri: String?,
    showAvatar: Boolean,
    repliedMessageContent: String?,
    onCharacterAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var descriptionOpen by remember(image.imageUri, image.imageDescription) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine) {
            Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
                if (showAvatar) {
                    QqAvatar(
                        characterName.take(1).ifBlank { "角" },
                        44,
                        characterAvatarUri,
                        Modifier.combinedClickable(onClick = onCharacterAvatarClick, onLongClick = {}),
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
        }

        Surface(
            modifier = Modifier.widthIn(max = if (image.sticker) 186.dp else 265.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick),
            color = if (image.sticker) Color.Transparent else if (mine) QqMine else Color.White,
            contentColor = if (mine) Color.White else QqInk,
            shape = RoundedCornerShape(16.dp),
            border = if (image.sticker || mine) null else BorderStroke(1.dp, QqBorder),
        ) {
            Column(Modifier.padding(if (image.sticker) 0.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repliedMessageContent?.takeIf(String::isNotBlank)?.let { quoted ->
                    Surface(color = if (mine) Color.White.copy(alpha = 0.12f) else QqIconSurface, shape = RoundedCornerShape(8.dp)) {
                        Text(quoted, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), color = if (mine) Color.White.copy(alpha = 0.72f) else QqMuted, fontSize = 10.sp, maxLines = 2)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 28 &&
                    image.imageUri.substringBefore('?').endsWith(".gif", ignoreCase = true) &&
                    image.imageUri.startsWith("file://")) {
                    QqChatAnimatedGif(image.imageUri)
                } else {
                    LuluSelectedPhoto(
                        imageUri = image.imageUri,
                        modifier = if (image.sticker) Modifier.size(154.dp) else Modifier,
                        keepFullImage = !image.sticker,
                    )
                }
                if (image.imageDescription.isNotBlank() && !image.sticker) {
                    TextButton(onClick = { descriptionOpen = true }) {
                        Text("查看完整识图描述 ›", fontSize = 12.sp,
                            color = if (mine) Color.White else QqInk)
                    }
                }
                if (image.caption.isNotBlank()) {
                    Text(
                        image.caption,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        color = if (mine) Color.White else QqInk,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        if (mine) {
            Spacer(Modifier.width(9.dp))
            Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
                if (showAvatar) QqAvatar(userAvatar, 44, userAvatarUri)
            }
        }
    }
    if (descriptionOpen) {
        AlertDialog(
            onDismissRequest = { descriptionOpen = false },
            title = { Text("图片识别详情") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text(image.imageDescription, lineHeight = 21.sp, fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { descriptionOpen = false }) { Text("关闭") }
            },
        )
    }
}
