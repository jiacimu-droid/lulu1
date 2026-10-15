package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.ModelUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class QqComposerPayload(
    val text: String,
    val imageUri: String? = null,
    val imageDescription: String = "",
)

@Composable
internal fun QqChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    groupMode: Boolean,
    receiving: Boolean,
    onMention: () -> Unit,
    onCall: () -> Unit,
    onSendOnly: (QqComposerPayload) -> Boolean,
    onWakeOrReply: (QqComposerPayload) -> Boolean,
    onSendSticker: (LuluSticker) -> Boolean,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<String?>(null) }
    var importingPhoto by remember { mutableStateOf(false) }
    var photoImportError by remember { mutableStateOf("") }
    var pickerRevision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var showStickerShelf by remember { mutableStateOf(false) }
    var stickerNotice by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickerRevision += 1
        val current = pickerRevision
        imageUri = null
        photoImportError = ""
        importingPhoto = true
        scope.launch {
            // PhotoPicker may supply only a temporary URI grant. Import
            // outside the UI thread so both direct and delayed perception
            // can read the exact same image after a process restart.
            val imported = runCatching {
                withContext(Dispatchers.IO) { QqChatPhotoAttachmentStore.import(context, uri) }
            }
            if (pickerRevision == current) {
                importingPhoto = false
                imported.onSuccess { imageUri = it }
                    .onFailure { photoImportError = "图片未能保存：${it.message.orEmpty().take(85)}" }
            } else {
                imported.getOrNull()?.let { abandoned ->
                    withContext(Dispatchers.IO) {
                        QqChatPhotoAttachmentStore.deleteIfOwned(context, abandoned)
                    }
                }
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull().orEmpty().trim()
        if (spoken.isNotBlank()) {
            onInputChange(listOf(input.trim(), spoken).filter(String::isNotBlank).joinToString(" "))
        }
    }

    fun payload(): QqComposerPayload = QqComposerPayload(
        text = input.trim(),
        imageUri = imageUri,
        imageDescription = "",
    )

    fun clearAttachment(discard: Boolean = false) {
        pickerRevision += 1
        importingPhoto = false
        photoImportError = ""
        val detached = imageUri
        imageUri = null
        if (discard && detached != null) scope.launch(Dispatchers.IO) {
            QqChatPhotoAttachmentStore.deleteIfOwned(context, detached)
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (showStickerShelf) {
            QqStickerShelf(
                onSendSticker = onSendSticker,
                onInsertText = { kaomoji -> onInputChange(input + kaomoji) },
                onNotice = { stickerNotice = it },
            )
            if (stickerNotice.isNotBlank()) {
                Text(
                    stickerNotice,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                    fontSize = 11.sp,
                    color = QqMuted,
                    maxLines = 2,
                )
            }
        }
        if (importingPhoto) {
            Text("正在准备图片，完成后才能发送…", color = QqMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 12.sp)
        }
        if (photoImportError.isNotBlank()) {
            Text(photoImportError, color = Color(0xFFB04C4C),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 12.sp)
        }
        if (imageUri != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                color = QqIconSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, QqBorder),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    LuluSelectedPhoto(imageUri = imageUri, modifier = Modifier.size(58.dp))
                    Column(Modifier.weight(1f)) {
                        Text("图片已就绪 · 可以直接发送", color = QqInk, fontSize = 12.sp)
                        Text("发送后由当前聊天模型看图回复", color = QqMuted,
                            fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    IconButton(onClick = { clearAttachment(discard = true) }) {
                        Icon(Icons.Outlined.Close, "移除图片", tint = QqMuted)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("图片", fontSize = 11.sp)
            }
            TextButton(
                onClick = { showStickerShelf = !showStickerShelf },
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 5.dp),
            ) {
                Text("☺", fontSize = 19.sp, color = QqInk)
                Spacer(Modifier.width(3.dp))
                Text("表情", fontSize = 11.sp)
            }
            ModelArchiveIconButton(
                usage = ModelUsage.Chat,
                title = "聊天模型",
                subtitle = "只切换聊天使用的模型存档；电话、游戏和末世求生不会跟着改变。",
                icon = Icons.Outlined.SwapHoriz,
                contentDescription = "切换聊天模型",
                tint = QqInk,
                accent = QqInk,
                background = QqPage,
                ink = QqInk,
                muted = QqMuted,
                border = QqBorder,
            )
            if (groupMode) {
                TextButton(onClick = onMention, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
                    Icon(Icons.Outlined.AlternateEmail, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text("@", fontSize = 11.sp)
                }
            }
            TextButton(onClick = onCall, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
                Icon(Icons.Outlined.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("电话", fontSize = 11.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledTonalIconButton(
                onClick = {
                    speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么")
                    })
                },
                modifier = Modifier.size(50.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = QqIconSurface, contentColor = QqInk),
            ) { Icon(Icons.Outlined.KeyboardVoice, "语音输入", modifier = Modifier.size(27.dp)) }

            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 5,
                placeholder = { Text("发消息", color = QqMuted, maxLines = 1) },
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = QqInk,
                    unfocusedTextColor = QqInk,
                    focusedContainerColor = QqIconSurface,
                    unfocusedContainerColor = QqIconSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = QqInk,
                ),
            )

            val canSend = !importingPhoto && (input.isNotBlank() || imageUri != null)
            FilledIconButton(
                onClick = { if (onSendOnly(payload())) clearAttachment() },
                enabled = canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = QqInk,
                    contentColor = Color.White,
                    disabledContainerColor = QqBorder,
                    disabledContentColor = QqMuted,
                ),
            ) { Icon(Icons.Outlined.Send, "只发送") }

            FilledTonalIconButton(
                onClick = {
                    if (receiving) onStop()
                    else if (!importingPhoto && onWakeOrReply(payload())) clearAttachment()
                },
                enabled = receiving || !importingPhoto,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = QqIconSurface,
                    contentColor = QqInk,
                ),
            ) {
                Icon(
                    if (receiving) Icons.Outlined.StopCircle else Icons.Outlined.MarkChatRead,
                    if (receiving) "停止" else "回复",
                )
            }
        }
    }
}
