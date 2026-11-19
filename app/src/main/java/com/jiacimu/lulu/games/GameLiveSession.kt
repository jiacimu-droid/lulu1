package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import com.jiacimu.lulu.data.MigratedDomainStores

/**
 * One scene-scoped talk transport shared by the small games. The phone's real
 * microphone/STT and character voice settings are reused; text mode makes no
 * capture or synthesis calls. Game rules and moves remain with the game engine.
 */
internal class GameLiveSession(
    private val context: Context,
    private val gameId: String,
    private val runId: String,
    val participantIds: List<String>,
    val allowSoloHost: Boolean,
    val mic: IslandGameOpenMic,
    private val voice: IslandCompanionVoice,
    private val toggleMic: () -> Unit,
) {
    var companionAudioEnabled by mutableStateOf(false)
        private set
    // Compatibility with game pacing: audio mode means either side opted into
    // speech. It does not turn on the other side's microphone or TTS.
    val voiceMode: Boolean get() = mic.active || companionAudioEnabled
    var draft by mutableStateOf("")
    var lastOwnerLine by mutableStateOf("")
        private set
    var lastCompanionLine by mutableStateOf("")
        private set
    var lastSpeakerId by mutableStateOf<String?>(null)
        private set
    var conversationStatus by mutableStateOf("")
    var companionSpeaking by mutableStateOf(false)
        private set
    var playbackError by mutableStateOf("")
    var onOwnerMessage: (String, Boolean) -> Unit = { _, _ -> }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (participantIds.isEmpty() && !allowSoloHost) return
        if (enabled) {
            if (!mic.active) toggleMic()
        } else {
            // Do not discard the provider's final recognized words on mute.
            mic.mute()
        }
    }

    fun setCompanionAudioEnabled(enabled: Boolean) {
        if (participantIds.isEmpty() && !allowSoloHost) return
        companionAudioEnabled = enabled
        playbackError = ""
        // Disabling cancels current speech and queued TTS immediately while
        // preserving actual character decisions and visible captions.
        voice.setEnabled(enabled)
    }

    fun receiveOwner(raw: String, fromSpeech: Boolean) {
        val text = raw.trim()
        // A final STT callback may arrive after the owner tapped mute.
        // IslandGameOpenMic already validates the actual capture generation.
        if (text.isEmpty()) return
        lastOwnerLine = text
        onOwnerMessage(text, fromSpeech)
    }

    /** Call only for speech the character really produced, not for an intended move. */
    fun companionSaid(actorId: String, raw: String, utteranceId: String) {
        val line = raw.trim()
        if (actorId !in participantIds || line.isBlank()) return
        lastCompanionLine = line
        lastSpeakerId = actorId
        GameTeamConversationTimeline.recordCompanion(
            context, gameId, runId, participantIds, actorId, line, utteranceId,
        )
        if (companionAudioEnabled) voice.enqueue(actorId, line)
    }

    /** A sealed puzzle's fixed AI host is not a companion participant. */
    fun fixedHostSaid(raw: String) {
        val line = raw.trim()
        if (line.isBlank()) return
        lastSpeakerId = null
        lastCompanionLine = "主持：$line"
        if (companionAudioEnabled) voice.enqueue("lulu", line)
    }

    fun close() {
        companionAudioEnabled = false
        voice.setEnabled(false)
        mic.stop()
    }

    fun connectAudio() {
        voice.setSpeakingListener { speaking ->
            companionSpeaking = speaking
            mic.setCompanionSpeaking(speaking)
        }
    }
}

@Composable
internal fun rememberGameLiveSession(
    gameId: String,
    runId: String,
    participantIds: List<String>,
    allowSoloHost: Boolean = false,
): GameLiveSession {
    val context = LocalContext.current.applicationContext
    // The current session is assigned after composing the microphone; callbacks
    // from a prior screen are invalidated by IslandGameOpenMic's generation gate.
    val holder = remember { arrayOfNulls<GameLiveSession>(1) }
    val (mic, toggleMic) = rememberIslandGameOpenMic { utterance ->
        holder[0]?.receiveOwner(utterance, fromSpeech = true)
    }
    val voice = rememberIslandCompanionVoice { holder[0]?.playbackError = it }
    val session = remember(gameId, runId, participantIds, allowSoloHost, mic, voice) {
        GameLiveSession(context, gameId, runId, participantIds, allowSoloHost, mic, voice, toggleMic)
    }
    SideEffect {
        holder[0] = session
        session.connectAudio()
    }
    DisposableEffect(session) {
        onDispose {
            if (holder[0] === session) holder[0] = null
            session.close()
        }
    }
    return session
}

@Composable
internal fun GameTalkBar(
    session: GameLiveSession,
    modifier: Modifier = Modifier,
    showComposer: Boolean = true,
) {
    if (session.participantIds.isEmpty() && !session.allowSoloHost) return
    val roster by MigratedDomainStores.characters.settings.collectAsState()
    val mint = Color(0xFF477B6D)
    val talkInk = GameDesign.ink
    val talkMuted = GameDesign.muted
    val conversationTitle = session.participantIds.mapNotNull { roster[it]?.displayName }.joinToString("、")
        .ifBlank { if (session.allowSoloHost) "主持人" else "同桌" }
    var textExpanded by remember(session) { mutableStateOf(false) }
    val microphoneOn = session.mic.active
    val speakerOn = session.companionAudioEnabled
    // Live captions are a scrollable reading surface, not two truncated lines.
    // Mute must not erase the last recognized words or spoken role reply.
    val ownerCaption = session.mic.partial.ifBlank { session.lastOwnerLine }
    val companionCaption = session.lastCompanionLine.takeIf(String::isNotBlank)?.let { line ->
        if (session.lastSpeakerId == null) line
        else "${roster[session.lastSpeakerId.orEmpty()]?.displayName ?: "角色"}：$line"
    }.orEmpty()
    val captionScroll = rememberScrollState()
    LaunchedEffect(ownerCaption, companionCaption) {
        // Follow freshly arriving speech only while the reader is at the end.
        // A user scrolling upward is never yanked back by the next partial.
        val followLatest = !captionScroll.isScrollInProgress &&
            captionScroll.value >= captionScroll.maxValue - 24
        withFrameNanos { }
        if (followLatest) captionScroll.scrollTo(captionScroll.maxValue)
    }
    val status = when {
        session.voiceMode && session.conversationStatus.isNotBlank() -> session.conversationStatus
        session.voiceMode && !session.mic.active -> session.mic.status.ifBlank { "已静音 · 字幕仍会保留" }
        session.voiceMode -> session.mic.status.ifBlank { "连麦中 · 直接说话" }
        else -> "点麦克风接通，随时边玩边聊"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFFFFCF7),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        border = BorderStroke(1.dp, Color(0xFFDDD8CD)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (session.voiceMode) "$conversationTitle · 连麦" else "和${conversationTitle}说话",
                        color = talkInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    )
                    Text(status, color = talkMuted, fontSize = 11.sp, maxLines = 2)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { session.setMicrophoneEnabled(!microphoneOn) }) {
                        Icon(
                            if (microphoneOn) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                            contentDescription = if (microphoneOn) "关闭我的麦克风" else "开启我的麦克风",
                            tint = if (microphoneOn) mint else talkMuted,
                        )
                    }
                    Text("我说话", fontSize = 10.sp, color = talkMuted)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { session.setCompanionAudioEnabled(!speakerOn) }) {
                        Icon(
                            if (speakerOn) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            contentDescription = if (speakerOn) "让角色改用文字" else "播放角色语音",
                            tint = if (speakerOn) mint else talkMuted,
                        )
                    }
                    Text(if (speakerOn) "角色语音" else "角色文字",
                        fontSize = 10.sp, color = talkMuted)
                }
                if (showComposer) {
                    TextButton(onClick = { textExpanded = !textExpanded }) {
                        Text(if (textExpanded) "收起" else "打字", color = mint, fontSize = 12.sp)
                    }
                }
            }
            // Entire last owner/companion utterances remain readable by swiping.
            // Keep the transcript bounded so it never pushes controls offscreen.
            if (ownerCaption.isNotBlank() || companionCaption.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 112.dp)
                        .background(Color(0xFFF0F5EF), RoundedCornerShape(12.dp))
                        .verticalScroll(captionScroll)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (ownerCaption.isNotBlank()) {
                        Text("你：$ownerCaption", color = talkInk,
                            fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    if (companionCaption.isNotBlank()) {
                        Text(companionCaption, color = Color(0xFF356458),
                            fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
            if (session.voiceMode && session.mic.recoverablePartial) {
                TextButton(onClick = session.mic::sendRecoveredPartial) {
                    Text("发送已识别的文字", color = mint, fontSize = 12.sp)
                }
            }
            if (showComposer && (!microphoneOn || textExpanded)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(
                        value = session.draft,
                        onValueChange = { session.draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说点什么…", fontSize = 12.sp) },
                        maxLines = 2,
                        singleLine = false,
                    )
                    Button(
                        enabled = session.draft.isNotBlank(),
                        onClick = {
                            val message = session.draft.trim()
                            if (message.isNotEmpty()) {
                                session.receiveOwner(message, fromSpeech = false)
                                session.draft = ""
                            }
                        },
                    ) { Text("发送") }
                }
            }
        }
    }
}
