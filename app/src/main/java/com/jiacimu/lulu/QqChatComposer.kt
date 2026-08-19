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
import com.jiacimu.lulu.ai.VisionModelService
import kotlinx.coroutines.launch

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
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf<String?>(null) }
    var imageDescription by remember { mutableStateOf("") }
    var imageBusy by remember { mutableStateOf(false) }
    var imageNotice by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        imageUri = uri.toString()
        imageDescription = ""
        imageNotice = ""
        imageBusy = true
        scope.launch {
            VisionModelService.describeImage(context, uri.toString(), input.trim())
                .onSuccess { description ->
                    imageDescription = description.trim().take(1_800)
                    imageNotice = ""
                }
                .onFailure { error ->
                    imageDescription = ""
                    val reason = error.message
                        .orEmpty()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(260)
                    imageNotice = if (reason.isBlank()) {
                        "识图失败，仍可发送图片；角色只能看到你的配文。"
                    } else {
                        "识图失败：$reason。仍可发送图片；角色只能看到你的配文。"
                    }
                }
            imageBusy = false
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
        imageDescription = imageDescription,
    )

    fun clearAttachment() {
        imageUri = null
        imageDescription = ""
        imageNotice = ""
        imageBusy = false
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
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
                        Text(if (imageBusy) "正在识图…" else "图片已准备", color = QqInk, fontSize = 12.sp)
                        if (imageNotice.isNotBlank()) {
                            Text(imageNotice, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, lineHeight = 14.sp)
                        } else if (imageDescription.isNotBlank()) {
                            Text(imageDescription, color = QqMuted, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                    IconButton(onClick = ::clearAttachment) { Icon(Icons.Outlined.Close, "移除图片", tint = QqMuted) }
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

            val canSend = (input.isNotBlank() || imageUri != null) && !imageBusy
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
                    else if (!imageBusy && onWakeOrReply(payload())) clearAttachment()
                },
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
