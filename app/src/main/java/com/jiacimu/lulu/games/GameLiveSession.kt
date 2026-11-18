package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
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
    var voiceMode by mutableStateOf(false)
        private set
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

    fun selectVoice(enabled: Boolean) {
        if (participantIds.isEmpty() && !allowSoloHost) return
        voiceMode = enabled
        playbackError = ""
        voice.setEnabled(enabled)
        if (enabled && !mic.active) toggleMic()
        if (!enabled) mic.stop()
    }

    fun receiveOwner(raw: String, fromSpeech: Boolean) {
        val text = raw.trim()
        if (text.isEmpty() || (fromSpeech && !voiceMode)) return
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
        if (voiceMode) voice.enqueue(actorId, line)
    }

    /** A sealed puzzle's fixed AI host is not a companion participant. */
    fun fixedHostSaid(raw: String) {
        val line = raw.trim()
        if (line.isBlank()) return
        lastSpeakerId = null
        lastCompanionLine = "主持：$line"
        if (voiceMode) voice.enqueue("lulu", line)
    }

    fun close() {
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
    val conversationTitle = session.participantIds.mapNotNull { roster[it]?.displayName }.joinToString("、").ifBlank { "同桌" }
    var textExpanded by remember(session) { mutableStateOf(false) }
    val microphoneOn = session.voiceMode && session.mic.active
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
                IconButton(
                    onClick = {
                        if (microphoneOn) session.mic.mute()
                        else session.selectVoice(true)
                    },
                ) {
                    Icon(
                        if (microphoneOn) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                        contentDescription = if (microphoneOn) "关闭麦克风" else "接通或开启麦克风",
                        tint = if (microphoneOn) mint else talkMuted,
                    )
                }
                if (session.voiceMode) {
                    IconButton(onClick = { session.selectVoice(false) }) {
                        Icon(Icons.Outlined.CallEnd, contentDescription = "挂断连麦",
                            tint = Color(0xFFF4B9BD))
                    }
                }
                if (showComposer) {
                    TextButton(onClick = { textExpanded = !textExpanded }) {
                        Text(if (textExpanded) "收起" else "打字", color = mint, fontSize = 12.sp)
                    }
                }
            }
            // Transcript belongs to the conversation, not to the microphone switch.
            val ownerCaption = session.mic.partial.ifBlank { session.lastOwnerLine }
            if (ownerCaption.isNotBlank()) {
                Text("你：$ownerCaption", color = talkInk,
                    fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp)
            }
            if (session.voiceMode && session.mic.recoverablePartial) {
                TextButton(onClick = session.mic::sendRecoveredPartial) {
                    Text("发送已识别的文字", color = mint, fontSize = 12.sp)
                }
            }
            if (session.lastCompanionLine.isNotBlank()) {
                Text(
                    if (session.lastSpeakerId == null) session.lastCompanionLine
                    else "${roster[session.lastSpeakerId]?.displayName ?: "角色"}：${session.lastCompanionLine}",
                    color = talkInk, fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp,
                )
            }
            if (showComposer && (!session.voiceMode || textExpanded)) {
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
