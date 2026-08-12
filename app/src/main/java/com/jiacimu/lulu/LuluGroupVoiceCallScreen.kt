package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private enum class GroupCallPhase { Ready, Dialing, Connected, Ended }

@Composable
internal fun LuluGroupVoiceCallScreen(
    conversationId: String,
    group: LuluGroupChat,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val voiceArchiveId = library.archiveIdFor(ModelUsage.VoiceCall)
    val activeArchive = library.archives.firstOrNull { it.id == voiceArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接模型"
    val callStartCount = remember(conversationId) { messages.size }
    val transcript = remember(messages, callStartCount) {
        messages.drop(callStartCount).filter { it.sender != LuluChatMessage.Sender.System }
    }
    val listState = rememberLazyListState()
    val speechEngine = remember { LuluSpeechEngine(context) }

    var phase by remember { mutableStateOf(GroupCallPhase.Ready) }
    var activeSpeakerId by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var speakerEnabled by remember { mutableStateOf(true) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var callStartedAt by remember { mutableStateOf<Instant?>(null) }
    val callId = remember { UUID.randomUUID().toString() }

    DisposableEffect(Unit) { onDispose { speechEngine.shutdown() } }

    fun saveGroupCall() {
        val occurredAt = callStartedAt ?: return
        group.members.forEach { member ->
            SharedExperienceTimeline.record(
                eventId = "group-call-$callId-${member.characterId}",
                characterId = member.characterId,
                channel = "群聊电话·${group.name}",
                speaker = "共同通话",
                content = "和用户及群成员进行了一次约 ${elapsedSeconds.coerceAtLeast(1)} 秒的群聊电话。",
                occurredAt = occurredAt,
            )
        }
    }

    fun closeCall() {
        if (phase == GroupCallPhase.Connected) saveGroupCall()
        speechEngine.stop()
        if (phase == GroupCallPhase.Connected) {
            phase = GroupCallPhase.Ended
            scope.launch { delay(650); onDismiss() }
        } else onDismiss()
    }

    LaunchedEffect(phase) {
        if (phase == GroupCallPhase.Dialing) {
            delay(1_800)
            if (phase == GroupCallPhase.Dialing) {
                phase = GroupCallPhase.Connected
                callStartedAt = Instant.now()
            }
            return@LaunchedEffect
        }
        if (phase != GroupCallPhase.Connected) return@LaunchedEffect
        startedAt = SystemClock.elapsedRealtime()
        while (phase == GroupCallPhase.Connected) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            delay(1_000)
        }
    }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        } else ""
        if (spoken.isBlank() || activeArchive == null) return@rememberLauncherForActivityResult
        MigratedDomainStores.chat.sendUserMessage(conversationId, spoken)
        thinking = true
        scope.launch {
            val currentMessages = MigratedDomainStores.chat.messages(conversationId).value
            val names = group.members.associate { member ->
                val character = characters[member.characterId] ?: MigratedDomainStores.characters.get(member.characterId)
                member.characterId to member.groupNickname.ifBlank { character.displayName }
            }
            runGroupReplies(
                conversationId = conversationId,
                group = group,
                pendingText = spoken,
                initialHistory = buildBoundedHistory(currentMessages.dropLast(1), group.name, names),
                activeLabel = activeLabel,
                archiveId = voiceArchiveId,
                characterNames = names,
                onError = {},
                sceneContext = "你正在群聊《${group.name}》的实时多人电话中。用户和其他群成员都在通话里；你听得见刚才的发言，也知道自己的声音和字幕会被所有人听见、看见。具体关系和称呼服从你的人设，回复要口语化。",
                onSpeakerChange = { activeSpeakerId = it },
                afterReply = { characterId, text ->
                    activeSpeakerId = characterId
                    if (speakerEnabled) speechEngine.speak(text, scope)
                    delay((text.length * 95L).coerceIn(1_200L, 6_500L))
                },
            )
            activeSpeakerId = null
            thinking = false
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::closeCall) { Icon(Icons.Outlined.KeyboardArrowDown, "收起群聊电话") }
                    Spacer(Modifier.weight(1f))
                    ModelArchiveTextButton(
                        usage = ModelUsage.VoiceCall,
                        title = "电话模型",
                        subtitle = "单人电话和群聊电话共用电话模型；聊天、游戏和末世求生不会跟着改变。",
                        activeLabel = activeLabel,
                        icon = Icons.Outlined.Tune,
                        accent = Color(0xFF5F77A9),
                        textColor = Color(0xFF1D1D1F),
                        background = Color.White,
                        muted = Color(0xFF77777B),
                        border = Color(0xFFE7E7E7),
                    )
                }

                Text("${group.name}（${group.members.size + 1}）", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(
                    when {
                        activeArchive == null -> "请先选择电话模型"
                        phase == GroupCallPhase.Ready -> "尚未拨打"
                        phase == GroupCallPhase.Dialing -> "正在呼叫群成员…"
                        phase == GroupCallPhase.Ended -> "通话已结束"
                        listening -> "正在听你说话"
                        thinking && activeSpeakerId == null -> "群成员正在回应"
                        else -> "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
                    },
                    color = Color(0xFF77777B),
                    fontSize = 13.sp,
                )

                Spacer(Modifier.height(18.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    items(group.members, key = { it.characterId }) { member ->
                        val character = characters[member.characterId] ?: MigratedDomainStores.characters.get(member.characterId)
                        val speaking = activeSpeakerId == member.characterId
                        val avatarSize by animateDpAsState(if (speaking) 84.dp else 64.dp, label = "group-speaker")
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(avatarSize),
                                shape = RoundedCornerShape(avatarSize * 0.25f),
                                border = BorderStroke(if (speaking) 3.dp else 1.dp, if (speaking) Color(0xFF292929) else Color(0xFFE7E7E7)),
                            ) {
                                LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, avatarSize.value.toInt())
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(member.groupNickname.ifBlank { character.displayName }, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }

                when (phase) {
                    GroupCallPhase.Ready, GroupCallPhase.Dialing -> {
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(
                            onClick = { phase = if (phase == GroupCallPhase.Ready) GroupCallPhase.Dialing else GroupCallPhase.Ready },
                            enabled = activeArchive != null,
                            modifier = Modifier.size(70.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (phase == GroupCallPhase.Ready) Color(0xFF292929) else Color(0xFFE34848),
                                contentColor = Color.White,
                            ),
                        ) { Icon(if (phase == GroupCallPhase.Ready) Icons.Outlined.Call else Icons.Outlined.CallEnd, null, Modifier.size(29.dp)) }
                        Spacer(Modifier.height(8.dp))
                        Text(if (phase == GroupCallPhase.Ready) "拨打群聊电话" else "取消", color = Color(0xFF77777B), fontSize = 12.sp)
                        Spacer(Modifier.height(46.dp))
                    }
                    GroupCallPhase.Connected -> {
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            color = Color(0xFFF7F7F7),
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                        ) {
                            if (transcript.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text("群聊电话字幕会显示在这里", color = Color(0xFF77777B), textAlign = TextAlign.Center)
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(transcript, key = { it.id }) { message ->
                                        val speaker = when (message.sender) {
                                            LuluChatMessage.Sender.User -> group.userGroupNickname
                                            LuluChatMessage.Sender.System -> "系统"
                                            LuluChatMessage.Sender.Character -> {
                                                val member = group.members.firstOrNull { it.characterId == message.authorCharacterId }
                                                val character = message.authorCharacterId?.let { characters[it] }
                                                member?.groupNickname?.ifBlank { character?.displayName.orEmpty() }?.ifBlank { "角色" } ?: "角色"
                                            }
                                        }
                                        Text(speaker, color = Color(0xFF77777B), fontSize = 11.sp)
                                        Text(message.content, fontSize = 15.sp, lineHeight = 22.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            FilledTonalIconButton(onClick = { speakerEnabled = !speakerEnabled; if (!speakerEnabled) speechEngine.stop() }, modifier = Modifier.size(58.dp)) {
                                Icon(if (speakerEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, "扬声器")
                            }
                            FilledIconButton(
                                onClick = {
                                    if (listening || thinking) return@FilledIconButton
                                    listening = true
                                    recognizer.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "在群聊电话里说话")
                                    })
                                },
                                enabled = !thinking,
                                modifier = Modifier.size(66.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0xFF292929),
                                    contentColor = Color.White,
                                    disabledContainerColor = Color(0xFF55565A),
                                    disabledContentColor = Color.White.copy(alpha = 0.72f),
                                ),
                            ) { Icon(if (listening) Icons.Outlined.Hearing else Icons.Outlined.Mic, "说话", tint = Color.White) }
                            FilledIconButton(
                                onClick = ::closeCall,
                                modifier = Modifier.size(58.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE34848)),
                            ) { Icon(Icons.Outlined.CallEnd, "挂断", tint = Color.White) }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    GroupCallPhase.Ended -> {
                        Spacer(Modifier.weight(1f))
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(60.dp))
                    }
                }
            }
        }
    }
}
