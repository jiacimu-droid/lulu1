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
import androidx.compose.ui.platform.LocalUriHandler
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
    val display = remember(image) { qqChatImageDisplay(image) }
    val uriHandler = LocalUriHandler.current

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

        // One historical chat item remains one persisted message, but its media,
        // spoken caption and source credit have separate visual surfaces.
        Column(
            modifier = Modifier.widthIn(max = if (image.sticker) 186.dp else 265.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Surface(
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                color = if (image.sticker) Color.Transparent else Color.White,
                contentColor = QqInk,
                shape = RoundedCornerShape(16.dp),
                border = if (image.sticker) null else BorderStroke(1.dp, QqBorder),
            ) {
                Column(
                    modifier = Modifier.padding(if (image.sticker) 0.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repliedMessageContent?.takeIf(String::isNotBlank)?.let { quoted ->
                        Surface(color = QqIconSurface, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                quoted,
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                color = QqMuted, fontSize = 10.sp, maxLines = 2,
                            )
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
                        TextButton(
                            onClick = { descriptionOpen = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("识图详情 ›", fontSize = 11.sp, color = QqMuted)
                        }
                    }
                }
            }

            if (display.caption.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                    color = if (mine) QqMine else QqOther,
                    contentColor = if (mine) QqMineInk else QqInk,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, if (mine) QqMine else QqBorder),
                ) {
                    Text(
                        display.caption,
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (mine) QqMineInk else QqInk,
                        fontSize = 15.sp, lineHeight = 22.sp,
                    )
                }
            }

            if (display.sourceUrl.isNotBlank()) {
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(display.sourceUrl) } },
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                ) {
                    Text("${display.sourceLabel} ↗", fontSize = 11.sp, color = QqMuted)
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
