package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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

private val CallBackgroundTop = Color(0xFFF8F8F8)
private val CallBackgroundMiddle = Color(0xFFF1F1F1)
private val CallBackgroundBottom = Color(0xFFEAEAEA)
private val CallGlowPrimary = Color(0x22FFFFFF)
private val CallGlowSecondary = Color(0x14000000)
private val CallGlowTertiary = Color(0x10FFFFFF)
private val CallGlass = Color(0x88FFFFFF)
private val CallGlassStrong = Color(0xB3FFFFFF)
private val CallGlassBorder = Color(0x66FFFFFF)
private val CallGlassBorderSoft = Color(0x33FFFFFF)
private val CallAccent = Color(0xFF161616)
private val CallInk = Color(0xFF202020)
private val CallMuted = Color(0xFF6F6F6F)
private val CallDanger = Color(0xFFE74B4B)
private val CallUserBubble = Color(0xD9131313)
private val CallOtherBubble = Color(0x99FFFFFF)

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            CallBackgroundTop,
                            CallBackgroundMiddle,
                            CallBackgroundBottom,
                        ),
                    ),
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            GlassGlow(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-56).dp, y = 22.dp),
                size = 200.dp,
                color = CallGlowPrimary,
            )
            GlassGlow(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 34.dp, y = 112.dp),
                size = 160.dp,
                color = CallGlowSecondary,
            )
            GlassGlow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 62.dp),
                size = 220.dp,
                color = CallGlowTertiary,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniGlassButton(
                        icon = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "收起通话",
                        onClick = {
                            tts.stop()
                            onDismiss()
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Box {
                        GlassCapsule(onClick = { modelExpanded = true }) {
                            Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(16.dp), tint = CallAccent)
                            Spacer(Modifier.width(6.dp))
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

                Spacer(Modifier.height(if (connected) 20.dp else 34.dp))

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(if (connected) 138.dp else 158.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .blur(4.dp),
                    )
                    Surface(
                        modifier = Modifier
                            .size(if (connected) 108.dp else 126.dp)
                            .shadow(16.dp, CircleShape),
                        shape = CircleShape,
                        color = CallGlassStrong,
                        border = BorderStroke(1.dp, CallGlassBorder),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                characterName.take(1).ifBlank { "露" },
                                color = CallAccent,
                                fontSize = if (connected) 39.sp else 46.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    characterName,
                    color = CallInk,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        !connected && activeArchive == null -> "请先连接一个模型"
                        !connected -> "等待接通"
                        listening -> "正在听你说话…"
                        thinking -> "$characterName 正在回应…"
                        else -> formatCallDuration(elapsedSeconds)
                    },
                    color = if (listening || thinking) CallAccent else CallMuted,
                    fontSize = 14.sp,
                    fontWeight = if (listening || thinking) FontWeight.Medium else FontWeight.Normal,
                )

                if (!connected) {
                    Spacer(Modifier.weight(1f))
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "想听听 $characterName 的声音吗？",
                                color = CallInk,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "接通后直接点麦克风说话，通话内容会自动留在聊天记录里。",
                                color = CallMuted,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(22.dp))
                            FilledIconButton(
                                onClick = { if (activeArchive != null) connected = true },
                                enabled = activeArchive != null,
                                modifier = Modifier.size(74.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = CallAccent,
                                    disabledContainerColor = Color(0xFFB9B9B9),
                                ),
                            ) {
                                Icon(Icons.Outlined.Call, "接通", tint = Color.White, modifier = Modifier.size(30.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("拨打电话", color = CallMuted, fontSize = 12.sp)
                        }
                    }
                } else {
                    Spacer(Modifier.height(18.dp))
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(30.dp),
                    ) {
                        if (callMessages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(28.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "点一下麦克风，和 $characterName 说句话吧",
                                    color = CallMuted,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(18.dp),
                                verticalArrangement = Arrangement.spacedBy(11.dp),
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
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                        Surface(
                                            color = if (mine) CallUserBubble else CallOtherBubble,
                                            shape = RoundedCornerShape(
                                                topStart = 18.dp,
                                                topEnd = 18.dp,
                                                bottomStart = if (mine) 18.dp else 6.dp,
                                                bottomEnd = if (mine) 6.dp else 18.dp,
                                            ),
                                            border = BorderStroke(
                                                1.dp,
                                                if (mine) Color(0x33FFFFFF) else CallGlassBorderSoft,
                                            ),
                                        ) {
                                            Text(
                                                message.content,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                color = if (mine) Color.White else CallInk,
                                                fontSize = 14.sp,
                                                lineHeight = 21.sp,
                                            )
                                        }
                                    }
                                }
                                if (thinking) {
                                    item {
                                        Text("$characterName 正在说话…", color = CallAccent, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
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
                                modifier = Modifier.size(68.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallAccent),
                            ) {
                                Icon(
                                    if (listening) Icons.Outlined.Hearing else Icons.Outlined.Mic,
                                    if (listening) "正在听" else "说话",
                                    tint = Color.White,
                                    modifier = Modifier.size(29.dp),
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
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = CallGlass,
        border = BorderStroke(1.dp, CallGlassBorder),
        shadowElevation = 10.dp,
    ) {
        Column(content = content)
    }
}

@Composable
private fun GlassCapsule(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = CallGlassStrong,
        border = BorderStroke(1.dp, CallGlassBorder),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun MiniGlassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = CallGlassStrong,
        border = BorderStroke(1.dp, CallGlassBorder),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = CallInk)
        }
    }
}

@Composable
private fun GlassGlow(
    modifier: Modifier = Modifier,
    size: Dp,
    color: Color,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .blur(48.dp),
    )
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
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = when {
                    danger -> CallDanger
                    active -> Color(0xAFFFFFFF)
                    else -> Color(0x73FFFFFF)
                },
                contentColor = if (danger) Color.White else CallAccent,
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
