package com.jiacimu.lulu

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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
private val CallBlueSoft = Color(0xFFDCE8FF)
private val CallLavender = Color(0xFFE9E2FA)
private val CallWarm = Color(0xFFFFEEE2)
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

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LuluVoiceCallSession.dial() else LuluVoiceCallSession.reportPermissionDenied()
    }

    val voiceArchiveId = library.archiveIdFor(ModelUsage.VoiceCall)
    val activeArchive = library.archives.firstOrNull { it.id == voiceArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接电话模型"
    val callMessages = remember(messages, state.callStartMessageCount) {
        messages.drop(state.callStartMessageCount).filter { it.sender != LuluChatMessage.Sender.System }
    }
    val visibleCallMessages = remember(callMessages) { callMessages.takeLast(12) }

    LaunchedEffect(visibleCallMessages.size) {
        if (visibleCallMessages.isNotEmpty()) listState.animateScrollToItem(visibleCallMessages.lastIndex)
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
                        listOf(Color(0xFFF8F5FF), Color(0xFFEAF2FF), Color(0xFFFFF8F3)),
                    ),
                ),
        ) {
            CallAmbientBackground(state)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallTopBar(activeLabel = activeLabel, onMinimize = onDismiss)

                Spacer(Modifier.height(18.dp))
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(138.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = .45f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .86f)),
                        shadowElevation = 18.dp,
                    ) {}
                    Surface(
                        modifier = Modifier.size(118.dp),
                        shape = RoundedCornerShape(34.dp),
                        color = Color.White,
                        border = BorderStroke(4.dp, Color.White),
                        shadowElevation = 10.dp,
                    ) {
                        LuluProfileAvatar(
                            imageUri = character.avatarUri,
                            fallback = state.characterName.ifBlank { characterName }.take(1).ifBlank { "露" },
                            size = 118,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    state.characterName.ifBlank { characterName },
                    color = CallInk,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    callStatusText(state, activeArchive != null),
                    color = CallMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(11.dp))
                CallActivityIndicator(state)
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 255.dp),
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
                            if (callMessages.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        when (state.phase) {
                                            CallPhase.Ready -> "接通后麦克风会自动打开\n像普通电话一样，直接说话就好"
                                            CallPhase.Dialing -> "正在等待对方接听"
                                            CallPhase.Connected -> if (state.microphoneMuted) {
                                                "麦克风已静音"
                                            } else {
                                                "麦克风已经常开\n直接说话，我会自动听你说完"
                                            }
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
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                if (mine) "你" else state.characterName.ifBlank { characterName },
                                                color = if (mine) CallBlue else Color(0xFF9A6BB5),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                message.content,
                                                modifier = Modifier.padding(top = 3.dp),
                                                color = CallInk,
                                                fontSize = 15.sp,
                                                lineHeight = 21.sp,
                                            )
                                        }
                                    }
                                    if (state.partialTranscript.isNotBlank()) {
                                        item { Text("你：${state.partialTranscript}", color = CallMuted, fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                when (state.phase) {
                    CallPhase.Ready -> {
                        FilledIconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    LuluVoiceCallSession.dial()
                                } else {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
                        Text(
                            if (state.microphoneMuted) "已静音 · 点麦克风恢复" else "麦克风常开 · 直接说话",
                            color = CallMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(9.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CallControl(
                                icon = if (state.speakerEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                                label = if (state.speakerEnabled) "扬声器" else "听筒",
                                active = state.speakerEnabled,
                                onClick = LuluVoiceCallSession::toggleSpeaker,
                            )
                            CallControl(
                                icon = if (state.microphoneMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                label = if (state.microphoneMuted) "取消静音" else "静音",
                                active = state.microphoneMuted,
                                onClick = LuluVoiceCallSession::toggleMicrophone,
                            )
                            CallControl(Icons.Outlined.KeyboardArrowDown, "缩小", false, onClick = onDismiss)
                            CallControl(Icons.Outlined.CallEnd, "挂断", true, danger = true, onClick = LuluVoiceCallSession::endCall)
                        }
                    }
                    CallPhase.Ended, CallPhase.Idle -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = CallBlue)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CallAmbientBackground(state: LuluVoiceCallState) {
    val pulse by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (state.listening || state.speaking) 1f else .65f,
        animationSpec = androidx.compose.animation.core.tween(700),
        label = "通话氛围",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(CallLavender.copy(alpha = .48f), size.minDimension * .48f * pulse, Offset(size.width * .15f, size.height * .22f))
        drawCircle(CallBlueSoft.copy(alpha = .58f), size.minDimension * .52f, Offset(size.width * .88f, size.height * .36f))
        drawCircle(CallWarm.copy(alpha = .68f), size.minDimension * .4f, Offset(size.width * .44f, size.height * .94f))
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
private fun CallTopBar(activeLabel: String, onMinimize: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onMinimize,
            modifier = Modifier.size(42.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = .68f),
                contentColor = CallInk,
            ),
        ) { Icon(Icons.Outlined.KeyboardArrowDown, "缩小通话") }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("语音通话", color = CallInk, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("缩小后仍会继续", color = CallMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        ModelArchiveTextButton(
            usage = ModelUsage.VoiceCall,
            title = "电话模型",
            subtitle = "只切换语音通话使用的模型存档；聊天、游戏和末世求生不会跟着改变。",
            activeLabel = activeLabel,
            icon = Icons.Outlined.Tune,
            accent = CallBlue,
            textColor = CallInk,
            background = Color(0xFFF3F6FF),
            muted = CallMuted,
            border = CallLine,
        )
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
    state.phase == CallPhase.Ended -> "通话已结束"
    state.microphoneMuted -> "麦克风已静音 · ${formatCallDuration(state.elapsedSeconds)}"
    state.thinking -> "${state.characterName} 正在回应"
    state.speaking -> "${state.characterName} 正在说话 · ${formatCallDuration(state.elapsedSeconds)}"
    state.listening -> "正在听你说话 · ${formatCallDuration(state.elapsedSeconds)}"
    state.connected -> "麦克风常开 · ${formatCallDuration(state.elapsedSeconds)}"
    else -> state.statusMessage
}

private fun formatCallDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remain = seconds % 60
    return "%02d:%02d".format(minutes, remain)
}
