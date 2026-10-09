package com.jiacimu.lulu

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay

private val CallInk = Color(0xFF243047)
private val CallMuted = Color(0xFF6F7890)
private val CallDanger = Color(0xFFEE5963)
private val CallBlue = Color(0xFF6C91D8)
private val CallGlass = Color(0xEFFFFFFF)
private val CallLine = Color(0xFFDDE3F0)

@Composable
fun LuluVoiceCallScreen(
    conversationId: String,
    characterId: String,
    characterName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by LuluVoiceCallSession.state.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val activeConversationId = state.conversationId.ifBlank { conversationId }
    val messagesFlow = remember(activeConversationId) { MigratedDomainStores.chat.messages(activeConversationId) }
    val messages by messagesFlow.collectAsState()
    val character = MigratedDomainStores.characters.get(state.characterId.ifBlank { characterId })
    val listState = rememberLazyListState()

    LaunchedEffect(conversationId, characterId, characterName) {
        LuluVoiceCallSession.prepare(context, conversationId, characterId, characterName)
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            LuluVoiceCallSession.dial() else LuluVoiceCallSession.reportPermissionDenied()
    }

    val voiceArchiveId = library.archiveIdFor(ModelUsage.VoiceCall)
    val activeArchive = library.archives.firstOrNull { it.id == voiceArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接电话模型"
    // Only messages belonging to this *connected* call may appear as call
    // captions. List position alone leaked old chat after a cold screen restore.
    val callMessages = remember(messages, state.callExperienceId, state.callStartedAt) {
        state.callStartedAt?.let { since ->
            messages.filter { message ->
                message.createdAt >= since &&
                    message.sender != LuluChatMessage.Sender.System
            }
        }.orEmpty()
    }
    val visibleCallMessages = remember(callMessages) { callMessages.takeLast(12) }

    LaunchedEffect(visibleCallMessages.lastOrNull()?.id, visibleCallMessages.lastOrNull()?.content,
        state.partialTranscript, state.playingTranscript) {
        val rows = visibleCallMessages.size + (if (state.partialTranscript.isNotBlank()) 1 else 0) + (if (state.playingTranscript.isNotBlank()) 1 else 0)
        if (rows > 0) {
            withFrameNanos { it }
            listState.animateScrollToItem(rows)
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.Ended) {
            delay(420)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFAFBFC), Color(0xFFF2F4F7), Color(0xFFFAFBFC)),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallTopBar(onMinimize = onDismiss)
                Spacer(Modifier.height(14.dp))
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(132.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = .45f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .86f)),
                        shadowElevation = 18.dp,
                    ) {}
                    Surface(
                        modifier = Modifier.size(120.dp),
                        shape = RoundedCornerShape(34.dp),
                        color = Color.White,
                        border = BorderStroke(4.dp, Color.White),
                        shadowElevation = 10.dp,
                    ) {
                        LuluProfileAvatar(
                            imageUri = character.avatarUri,
                            fallback = state.characterName.ifBlank { characterName }.take(1).ifBlank { "露" },
                            size = 120,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    state.characterName.ifBlank { characterName },
                    color = CallInk,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when (state.phase) {
                        CallPhase.Ready -> if (activeArchive == null) "请选择电话模型" else "准备拨打"
                        CallPhase.Dialing -> "正在呼叫…"
                        CallPhase.Connected -> formatCallDuration(state.elapsedSeconds)
                        CallPhase.Ended -> "通话已结束"
                        CallPhase.Idle -> ""
                    },
                    color = CallMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(11.dp))
                
                if (state.errorMessage.isNotBlank()) Text(state.errorMessage, color = CallDanger, fontSize = 12.sp)
                if (state.connected) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (state.errorMessage.isNotBlank()) TextButton(onClick = LuluVoiceCallSession::retryListening) {
                            Text("重新收音", fontSize = 12.sp)
                        }
                        TextButton(onClick = LuluVoiceCallSession::toggleSleepMode) {
                            Icon(Icons.Outlined.NightsStay, contentDescription = null,
                                tint = if (state.sleepMode) Color(0xFF9A6BB5) else CallMuted)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.sleepMode) "结束哄睡" else "哄睡", fontSize = 12.sp,
                                color = if (state.sleepMode) Color(0xFF9A6BB5) else CallInk)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(28.dp),
                        color = CallGlass,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .9f)),
                        shadowElevation = 8.dp,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Subtitles, null, tint = CallBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("实时字幕", color = CallInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                if (state.connected) Text(formatCallDuration(state.elapsedSeconds), color = CallMuted, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = CallLine.copy(alpha = .7f))
                            if (callMessages.isEmpty() && state.partialTranscript.isBlank() && state.playingTranscript.isBlank()) {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        when (state.phase) {
                                            CallPhase.Ready -> "接通后麦克风会自动打开\n像普通电话一样，直接说话就好"
                                            CallPhase.Dialing -> "正在连接语音服务"
                                            CallPhase.Connected -> if (state.sleepMode) "正在陪伴" else ""
                                            else -> "通话字幕会显示在这里"
                                        },
                                        color = CallMuted,
                                        fontSize = 14.sp,
                                        lineHeight = 21.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(visibleCallMessages, key = { it.id }) { message ->
                                        val mine = message.sender == LuluChatMessage.Sender.User
                                        CallCaptionLines(
                                            speaker = if (mine) "你" else state.characterName.ifBlank { characterName },
                                            content = message.content, mine = mine,
                                        )
                                    }
                                    if (state.playingTranscript.isNotBlank()) {
                                        item(key = "playing-line") {
                                            CallCaptionLines(
                                                speaker = state.characterName.ifBlank { characterName },
                                                content = state.playingTranscript,
                                                mine = false, live = true,
                                            )
                                        }
                                    }
                                    if (state.partialTranscript.isNotBlank()) {
                                        item(key = "partial-line") {
                                            CallCaptionLines(speaker = "你", content = state.partialTranscript, mine = true, live = true)
                                        }
                                    }
                                    item(key = "subtitle-bottom") { Spacer(Modifier.height(1.dp)) }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                when (state.phase) {
                    CallPhase.Ready -> {
                        // Model choice stays available before dialing, without
                        // cluttering the portrait of an active phone call.
                        if (activeArchive == null) ModelArchiveTextButton(
                            usage = ModelUsage.VoiceCall, title = "选择电话模型",
                            subtitle = "用于这次语音通话", activeLabel = activeLabel,
                            icon = Icons.Outlined.Tune, accent = CallBlue, textColor = CallInk,
                            background = Color(0xFFF3F6FF), muted = CallMuted, border = CallLine,
                        )
                        FilledIconButton(
                            onClick = {
                                val permissions = buildList {
                                    add(Manifest.permission.RECORD_AUDIO)
                                    if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                                }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                                if (permissions.isEmpty()) {
                                    LuluVoiceCallSession.dial()
                                } else {
                                    microphonePermission.launch(permissions.toTypedArray())
                                }
                            },
                            enabled = activeArchive != null,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CallBlue,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFBCC5D7),
                            ),
                        ) {
                            Icon(Icons.Outlined.Call, "拨打电话", modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("拨打电话", color = CallMuted, fontSize = 12.sp)
                    }
                    CallPhase.Dialing -> CallPrimaryHangup(label = "取消呼叫", onClick = LuluVoiceCallSession::cancelDial)
                    CallPhase.Connected -> {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CallControl(
                                icon = when {
                                    state.speakerEnabled -> Icons.Outlined.VolumeUp
                                    state.audioRouteLabel.contains("耳机") -> Icons.Outlined.Headphones
                                    else -> Icons.Outlined.PhoneInTalk
                                },
                                label = state.audioRouteLabel,
                                active = true,
                                onClick = LuluVoiceCallSession::toggleSpeaker,
                            )
                            CallControl(
                                icon = if (state.microphoneMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                label = if (state.microphoneMuted) "麦克风已关" else "麦克风已开",
                                active = !state.microphoneMuted,
                                onClick = LuluVoiceCallSession::toggleMicrophone,
                            )
                                                        CallControl(Icons.Outlined.CallEnd, "挂断", true, danger = true, onClick = LuluVoiceCallSession::endCall)
                        }
                    }
                    CallPhase.Ended -> {
                        Icon(Icons.Outlined.CallEnd, contentDescription = null,
                            tint = CallMuted, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(state.statusMessage.ifBlank { "通话已结束" }, color = CallInk, fontSize = 13.sp)
                    }
                    CallPhase.Idle -> Spacer(Modifier.height(24.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CallCaptionLines(speaker: String, content: String, mine: Boolean, live: Boolean = false) {
    // LazyItemScope.animateItem() cannot be called inside a standalone composable.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PhoneSubtitleLayout.lines(content).forEach { line ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    "$speaker：",
                    color = if (mine) CallBlue else Color(0xFF9A6BB5),
                    fontWeight = FontWeight.Bold,
                    fontSize = if (live) 13.sp else 14.sp,
                )
                Text(
                    line,
                    modifier = Modifier.weight(1f),
                    color = if (live) CallMuted else CallInk,
                    fontSize = if (live) 14.sp else 15.sp,
                    lineHeight = 23.sp,
                )
            }
        }
    }
}

@Composable
private fun CallActivityIndicator(state: LuluVoiceCallState) {
    val active = state.listening || state.thinking || state.speaking
    Row(
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = .58f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(7) { index ->
            val height = when {
                !active -> 4
                state.thinking -> 5 + (index % 3) * 3
                else -> 6 + ((index * 5) % 4) * 3
            }
            Box(
                Modifier.width(3.dp).height(height.dp).clip(CircleShape).background(
                    when {
                        state.speaking -> Color(0xFF9A6BB5)
                        state.listening -> CallBlue
                        else -> CallMuted.copy(alpha = .55f)
                    },
                ),
            )
        }
    }
}

@Composable
private fun CallTopBar(onMinimize: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onMinimize,
            modifier = Modifier.size(42.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = .68f),
                contentColor = CallInk,
            ),
        ) { Icon(Icons.Outlined.KeyboardArrowDown, "缩小通话") }
        Spacer(Modifier.weight(1f))
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
            modifier = Modifier.size(58.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = when {
                    danger -> CallDanger
                    active -> Color.White.copy(alpha = .92f)
                    else -> Color.White.copy(alpha = .55f)
                },
                contentColor = if (danger) Color.White else CallInk,
            ),
        ) { Icon(icon, label, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = CallMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CallPrimaryHangup(label: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallDanger, contentColor = Color.White),
    ) { Icon(Icons.Outlined.CallEnd, label, modifier = Modifier.size(30.dp)) }
    Spacer(Modifier.height(8.dp))
    Text(label, color = CallMuted, fontSize = 12.sp)
}

private fun callStatusText(state: LuluVoiceCallState, modelConnected: Boolean): String = when {
    !modelConnected -> "请先在右上角选择电话模型"
    state.phase == CallPhase.Ready -> state.statusMessage.ifBlank { "准备好以后拨打" }
    state.phase == CallPhase.Dialing -> "正在呼叫 ${state.characterName}…"
    state.phase == CallPhase.Ended -> state.statusMessage.ifBlank { "通话已结束" }
    state.microphoneMuted -> "麦克风已静音"
    state.speaking -> "${state.characterName} 正在说话"
    state.thinking -> "${state.characterName} 正在回应"
    state.listening -> "正在听你说话"
    state.connected -> "通话中"
    else -> state.statusMessage
}

private fun formatCallDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remain = seconds % 60
    return "%02d:%02d".format(minutes, remain)
}
