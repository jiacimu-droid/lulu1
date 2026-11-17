package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    inputState: MutableState<String>,
    groupMode: Boolean,
    characterId: String,
    receiving: Boolean,
    onInputFocusChanged: (Boolean) -> Unit = {},
    onMention: () -> Unit,
    onCall: () -> Unit,
    onVideoCall: (() -> Unit)? = null,
    onWatchTogether: (() -> Unit)? = null,
    onListenTogether: (() -> Unit)? = null,
    onSendOnly: (QqComposerPayload) -> Boolean,
    onWakeOrReply: (QqComposerPayload) -> Boolean,
    onSendSticker: (LuluSticker) -> Boolean,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showTools by remember { mutableStateOf(false) }
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
            inputState.value = listOf(inputState.value.trim(), spoken).filter(String::isNotBlank).joinToString(" ")
        }
    }

    fun payload(): QqComposerPayload = QqComposerPayload(
        text = inputState.value.trim(),
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

        // A single quiet input row at rest. The old always-visible action strip
        // competed with the conversation on every screen, even when unused.
        val hasDraft = inputState.value.isNotBlank() || imageUri != null
        val canSend = hasDraft && !importingPhoto
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilledTonalIconButton(
                onClick = {
                    speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么")
                    })
                },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = QqIconSurface, contentColor = QqInk,
                ),
            ) { Icon(Icons.Outlined.KeyboardVoice, "语音输入", modifier = Modifier.size(26.dp)) }

            TextField(
                value = inputState.value,
                onValueChange = {
                    inputState.value = it
                    if (it.isNotBlank()) {
                        showTools = false
                        showStickerShelf = false
                    }
                },
                modifier = Modifier.weight(1f).onFocusChanged { state ->
                    onInputFocusChanged(state.isFocused)
                    if (state.isFocused) {
                        showTools = false
                        showStickerShelf = false
                    }
                },
                minLines = 1,
                maxLines = 5,
                placeholder = { Text("发消息", color = QqMuted, maxLines = 1) },
                shape = RoundedCornerShape(14.dp),
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

            if (hasDraft) {
                Button(
                    onClick = {
                        // This is the same durable send path as the old "只发送"
                        // button; private/group reply scheduling stays in its store.
                        if (canSend && onSendOnly(payload())) {
                            clearAttachment()
                            showTools = false
                            showStickerShelf = false
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.height(48.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QqMine, contentColor = QqMineInk,
                    ),
                ) { Text("发送", fontSize = 13.sp) }
            } else {
                IconButton(
                    onClick = {
                        val opening = !showTools
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        showStickerShelf = false
                        showTools = opening
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (showTools) Icons.Outlined.Close else Icons.Outlined.AddCircleOutline,
                        if (showTools) "收起更多功能" else "展开更多功能",
                        modifier = Modifier.size(29.dp),
                        tint = QqInk,
                    )
                }
            }
        }

        if (showTools) {
            HorizontalDivider(color = QqBorder.copy(alpha = 0.35f))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QqComposerTool("图片", Icons.Outlined.AddPhotoAlternate, Modifier.weight(1f)) {
                        showTools = false
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    QqComposerTool("表情", Icons.Outlined.EmojiEmotions, Modifier.weight(1f)) {
                        showTools = false
                        showStickerShelf = true
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(58.dp),
                            color = QqIconSurface, shape = RoundedCornerShape(15.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                ModelArchiveIconButton(
                                    usage = ModelUsage.Chat,
                                    characterId = characterId.takeUnless { groupMode },
                                    title = if (groupMode) "公共聊天模型" else "当前角色聊天模型",
                                    subtitle = if (groupMode) "只设置未单独选择的角色的默认模型。"
                                        else "这里只修改当前角色，其他角色原来的模型保持不变。",
                                    icon = Icons.Outlined.SwapHoriz,
                                    contentDescription = "切换聊天模型",
                                    tint = QqInk,
                                    accent = QqInk,
                                    background = QqPage,
                                    ink = QqInk,
                                    muted = QqMuted,
                                    border = QqBorder,
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text("模型", fontSize = 11.sp, color = QqMuted, maxLines = 1)
                    }
                    if (groupMode) {
                        QqComposerTool("@成员", Icons.Outlined.AlternateEmail, Modifier.weight(1f)) {
                            showTools = false
                            onMention()
                        }
                    } else {
                        QqComposerTool("电话", Icons.Outlined.Call, Modifier.weight(1f)) {
                            showTools = false
                            onCall()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (groupMode) {
                        QqComposerTool("电话", Icons.Outlined.Call, Modifier.weight(1f)) {
                            showTools = false
                            onCall()
                        }
                    } else {
                        if (onVideoCall != null) {
                            QqComposerTool("视频", Icons.Outlined.Videocam, Modifier.weight(1f)) {
                                showTools = false
                                onVideoCall()
                            }
                        }
                        if (onWatchTogether != null) {
                            QqComposerTool("一起看", Icons.Outlined.Theaters, Modifier.weight(1f)) {
                                showTools = false
                                onWatchTogether()
                            }
                        }
                        if (onListenTogether != null) {
                            QqComposerTool("一起听", Icons.Outlined.MusicNote, Modifier.weight(1f)) {
                                showTools = false
                                onListenTogether()
                            }
                        }
                    }
                    QqComposerTool(
                        if (receiving) "停止回复" else "催回复",
                        if (receiving) Icons.Outlined.StopCircle else Icons.Outlined.MarkChatRead,
                        Modifier.weight(1f),
                    ) {
                        showTools = false
                        if (receiving) onStop() else onWakeOrReply(QqComposerPayload(""))
                    }
                    if (groupMode) {
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (showStickerShelf) {
            QqStickerShelf(
                onSendSticker = onSendSticker,
                onInsertText = { kaomoji -> inputState.value += kaomoji },
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

    }
}


/** Single-line chat action; each cell retains a 48dp accessible touch target. */
@Composable
private fun QqComposerTool(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(58.dp),
            color = QqIconSurface,
            shape = RoundedCornerShape(15.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
                    Icon(icon, label, modifier = Modifier.size(25.dp), tint = QqInk)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 11.sp, color = QqMuted, maxLines = 1)
    }
}
