package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
            modifier = Modifier.widthIn(max = 265.dp).combinedClickable(onClick = {}, onLongClick = onLongClick),
            color = if (mine) QqMine else Color.White,
            contentColor = if (mine) Color.White else QqInk,
            shape = RoundedCornerShape(16.dp),
            border = if (mine) null else BorderStroke(1.dp, QqBorder),
        ) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repliedMessageContent?.takeIf(String::isNotBlank)?.let { quoted ->
                    Surface(color = if (mine) Color.White.copy(alpha = 0.12f) else QqIconSurface, shape = RoundedCornerShape(8.dp)) {
                        Text(quoted, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), color = if (mine) Color.White.copy(alpha = 0.72f) else QqMuted, fontSize = 10.sp, maxLines = 2)
                    }
                }
                LuluSelectedPhoto(
                    imageUri = image.imageUri,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp),
                )
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
}
