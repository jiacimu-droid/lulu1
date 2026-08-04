package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.launch

private val CallPage = Color(0xFFFFFFFF)
private val CallSurface = Color(0xFFF7F7F7)
private val CallLine = Color(0xFFE7E7E7)
private val CallInk = Color(0xFF1D1D1F)
private val CallMuted = Color(0xFF77777B)
private val CallDark = Color(0xFF242426)
private val CallDanger = Color(0xFFE34848)
private val CallMine = Color(0xFF292929)

@Composable
fun LuluVoiceCallScreen(
    conversationId: String,
    characterId: String,
    characterName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val activeArchive = library.archives.firstOrNull { it.id == library.activeArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接模型"
    val listState = rememberLazyListState()

    var connected by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var speakerEnabled by remember { mutableStateOf(true) }
    var modelExpanded by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    val speechEngine = remember { LuluSpeechEngine(context) }
    DisposableEffect(Unit) {
        onDispose { speechEngine.shutdown() }
    }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        startedAt = SystemClock.elapsedRealtime()
        while (connected) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            kotlinx.coroutines.delay(1_000L)
        }
    }

    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isBlank()) return@rememberLauncherForActivityResult

        MigratedDomainStores.chat.sendUserMessage(conversationId, spoken)
        if (activeArchive == null) return@rememberLauncherForActivityResult

        thinking = true
        scope.launch {
            val recentHistory = buildCallHistory(
                MigratedDomainStores.chat.messages(conversationId).value,
                characterName,
            )
            LuluDeviceToolBridge.respond(
                characterId = characterId,
                history = recentHistory,
                userText = spoken,
                title = activeLabel,
            ).onSuccess { reply ->
                val text = reply.text.trim()
                if (text.isNotBlank()) {
                    MigratedDomainStores.chat.appendCharacterMessage(conversationId, text)
                    if (speakerEnabled) {
                        speechEngine.speak(text, scope)
                    }
                }
            }
            thinking = false
        }
    }

    val callMessages = remember(messages) { messages.takeLast(12) }
    LaunchedEffect(callMessages.size) {
        if (connected && callMessages.isNotEmpty()) {
            listState.animateScrollToItem(callMessages.lastIndex)
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = CallPage) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallTopBar(
                    activeLabel = activeLabel,
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    archives = library.archives,
                    activeArchiveId = library.activeArchiveId,
                    onSelectArchive = {
                        LuluAiServices.connectionStore.selectArchive(it)
                        modelExpanded = false
                    },
                    onClose = {
                        speechEngine.stop()
                        onDismiss()
                    },
                )

                Spacer(Modifier.height(if (connected) 18.dp else 34.dp))

                Surface(
                    modifier = Modifier.size(if (connected) 84.dp else 96.dp),
                    shape = CircleShape,
                    color = CallSurface,
                    border = BorderStroke(1.dp, CallLine),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            characterName.take(1).ifBlank { "露" },
                            color = CallInk,
                            fontSize = if (connected) 34.sp else 38.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    characterName,
                    color = CallInk,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !connected && activeArchive == null -> "请先选择模型"
                        !connected -> "等待接通"
                        listening -> "正在听你说话"
                        thinking -> "$characterName 正在回应"
                        else -> formatCallDuration(elapsedSeconds)
                    },
                    color = CallMuted,
                    fontSize = 13.sp,
                )

                if (!connected) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "接通后点麦克风说话，通话内容会同步保存在聊天记录里。",
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = CallMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(30.dp))
                    FilledIconButton(
                        onClick = { if (activeArchive != null) connected = true },
                        enabled = activeArchive != null,
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CallDark,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFD5D5D5),
                        ),
                    ) {
                        Icon(Icons.Outlined.Call, "拨打电话", modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("拨打电话", color = CallMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(22.dp))
                } else {
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(22.dp),
                        color = CallSurface,
                        border = BorderStroke(1.dp, CallLine),
                    ) {
                        if (callMessages.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "点一下麦克风，和 $characterName 说句话",
                                    color = CallMuted,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(callMessages, key = { it.id }) { message ->
                                    val mine = message.sender == LuluChatMessage.Sender.User
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                    ) {
                                        Text(
                                            if (mine) "你" else characterName,
                                            color = CallMuted,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                                        )
                                        Surface(
                                            color = if (mine) CallMine else Color.White,
                                            shape = RoundedCornerShape(16.dp),
                                            border = BorderStroke(1.dp, if (mine) CallMine else CallLine),
                                        ) {
                                            Text(
                                                message.content,
                                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                                color = if (mine) Color.White else CallInk,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                            )
                                        }
                                    }
                                }
                                if (thinking) {
                                    item {
                                        Text("$characterName 正在说话…", color = CallMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CallControl(
                            icon = if (speakerEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            label = "扬声器",
                            active = speakerEnabled,
                            onClick = {
                                speakerEnabled = !speakerEnabled
                                if (!speakerEnabled) speechEngine.stop()
                            },
                        )
                        FilledIconButton(
                            onClick = {
                                if (listening || thinking) return@FilledIconButton
                                listening = true
                                recognizer.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "对 $characterName 说话")
                                })
                            },
                            enabled = !thinking,
                            modifier = Modifier.size(66.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallDark),
                        ) {
                            Icon(
                                if (listening) Icons.Outlined.Hearing else Icons.Outlined.Mic,
                                if (listening) "正在听" else "说话",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        CallControl(
                            icon = Icons.Outlined.CallEnd,
                            label = "挂断",
                            active = true,
                            danger = true,
                            onClick = {
                                connected = false
                                speechEngine.stop()
                                onDismiss()
                            },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun CallTopBar(
    activeLabel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    archives: List<com.jiacimu.lulu.ai.ModelArchive>,
    activeArchiveId: String?,
    onSelectArchive: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.KeyboardArrowDown, "收起通话", tint = CallInk)
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { onExpandedChange(true) }) {
                Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(16.dp), tint = CallInk)
                Spacer(Modifier.width(5.dp))
                Text(activeLabel, maxLines = 1, fontSize = 12.sp, color = CallInk)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                if (archives.isEmpty()) {
                    DropdownMenuItem(text = { Text("还没有模型存档") }, enabled = false, onClick = {})
                } else {
                    archives.forEach { archive ->
                        val selected = archive.id == activeArchiveId
                        DropdownMenuItem(
                            text = { Text(LuluAiServices.connectionStore.archiveLabel(archive)) },
                            leadingIcon = {
                                Icon(
                                    if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                )
                            },
                            onClick = { onSelectArchive(archive.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(54.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = when {
                    danger -> CallDanger
                    active -> Color(0xFFE8E8E8)
                    else -> Color(0xFFF2F2F2)
                },
                contentColor = if (danger) Color.White else CallInk,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, color = CallMuted, fontSize = 10.sp)
    }
}

private fun buildCallHistory(messages: List<LuluChatMessage>, characterName: String): String = messages
    .filter { it.sender != LuluChatMessage.Sender.System }
    .takeLast(24)
    .joinToString("\n") { message ->
        val role = if (message.sender == LuluChatMessage.Sender.User) "主人" else characterName
        "$role：${message.content.trim()}"
    }

private fun formatCallDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remain = seconds % 60
    return "%02d:%02d".format(minutes, remain)
}
