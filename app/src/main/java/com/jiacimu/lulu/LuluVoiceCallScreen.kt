package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import java.util.Locale

private val CallBackground = Color(0xFFFFFFFF)
private val CallSurface = Color(0xFFFCFCFC)
private val CallInk = Color(0xFF1D1D1F)
private val CallMuted = Color(0xFF7A7A7E)
private val CallLine = Color(0xFFE7E7E7)
private val CallDark = Color(0xFF292929)
private val CallDanger = Color(0xFFE74B4B)

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

    val tts = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) Unit
        }
    }
    DisposableEffect(Unit) {
        tts.language = Locale.SIMPLIFIED_CHINESE
        tts.setSpeechRate(1.02f)
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        startedAt = SystemClock.elapsedRealtime()
        while (connected) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L
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
            val recentHistory = buildCallHistory(MigratedDomainStores.chat.messages(conversationId).value, characterName)
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
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lulu-call-${System.nanoTime()}")
                    }
                }
            }
            thinking = false
        }
    }

    val callMessages = remember(messages) { messages.takeLast(10) }
    LaunchedEffect(callMessages.size) {
        if (callMessages.isNotEmpty()) listState.animateScrollToItem(callMessages.lastIndex)
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = CallBackground) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        tts.stop()
                        onDismiss()
                    }) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "收起通话", tint = CallInk)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { modelExpanded = true }) {
                            Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(17.dp), tint = CallInk)
                            Spacer(Modifier.width(5.dp))
                            Text(activeLabel, maxLines = 1, fontSize = 12.sp, color = CallInk)
                        }
                        DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                            library.archives.forEach { archive ->
                                val selected = archive.id == library.activeArchiveId
                                DropdownMenuItem(
                                    text = { Text(LuluAiServices.connectionStore.archiveLabel(archive)) },
                                    leadingIcon = {
                                        Icon(
                                            if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                            null,
                                        )
                                    },
                                    onClick = {
                                        LuluAiServices.connectionStore.selectArchive(archive.id)
                                        modelExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.size(92.dp),
                    shape = CircleShape,
                    color = CallSurface,
                    border = BorderStroke(1.dp, CallLine),
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            characterName.take(1).ifBlank { "露" },
                            color = CallInk,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(characterName, color = CallInk, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !connected -> "等待接通"
                        listening -> "正在听你说"
                        thinking -> "对方正在回应"
                        else -> formatCallDuration(elapsedSeconds)
                    },
                    color = CallMuted,
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    color = CallSurface,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, CallLine),
                ) {
                    if (!connected) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("给 $characterName 打电话", color = CallInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                "接通后点麦克风说话。电话内容会继续写入同一段聊天记录。",
                                color = CallMuted,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            items(callMessages, key = { it.id }) { message ->
                                val mine = message.sender == LuluChatMessage.Sender.User
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                ) {
                                    Text(if (mine) "你" else characterName, color = CallMuted, fontSize = 10.sp)
                                    Surface(
                                        color = if (mine) CallDark else Color(0xFFF4F4F4),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, if (mine) CallDark else CallLine),
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
                                item { Text("$characterName 正在说话…", color = CallMuted, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (!connected) {
                    FilledIconButton(
                        onClick = { if (activeArchive != null) connected = true },
                        enabled = activeArchive != null,
                        modifier = Modifier.size(62.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallDark),
                    ) {
                        Icon(Icons.Outlined.Call, "接通", tint = Color.White, modifier = Modifier.size(27.dp))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CallControl(
                            icon = if (speakerEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            label = "扬声器",
                            active = speakerEnabled,
                            onClick = {
                                speakerEnabled = !speakerEnabled
                                if (!speakerEnabled) tts.stop()
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
                            modifier = Modifier.size(64.dp),
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
                                tts.stop()
                                onDismiss()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
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
            modifier = Modifier.size(50.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (danger) CallDanger else if (active) Color(0xFFE5E5E7) else Color(0xFFF1F1F3),
                contentColor = if (danger) Color.White else CallInk,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
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
