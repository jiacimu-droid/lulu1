package com.jiacimu.lulu.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context

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
    var onOwnerMessage: (String, Boolean) -> Unit = { _, _ -> }

    fun selectVoice(enabled: Boolean) {
        if (participantIds.isEmpty() && !allowSoloHost) return
        voiceMode = enabled
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
        GameTeamConversationTimeline.recordCompanion(
            context, gameId, runId, participantIds, actorId, line, utteranceId,
        )
        if (voiceMode) voice.enqueue(actorId, line)
    }

    /** A sealed puzzle's fixed AI host is not a companion participant. */
    fun fixedHostSaid(raw: String) {
        val line = raw.trim()
        if (line.isBlank()) return
        lastCompanionLine = "主持：$line"
        if (voiceMode) voice.enqueue("lulu", line)
    }

    fun close() {
        voice.setEnabled(false)
        mic.stop()
    }

    fun connectAudio() {
        voice.setSpeakingListener { speaking -> mic.setCompanionSpeaking(speaking) }
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
    val voice = rememberIslandCompanionVoice()
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !session.voiceMode,
                onClick = { session.selectVoice(false) },
                label = { Text("文字") },
            )
            FilterChip(
                selected = session.voiceMode,
                onClick = { session.selectVoice(true) },
                label = { Text("连麦") },
            )
        }
        if (session.voiceMode) {
            Text(session.mic.status.ifBlank { "连麦中" }, color = Color(0xFFB8CFE1))
            if (session.mic.partial.isNotBlank()) {
                Text("你：${session.mic.partial}", color = Color(0xFFB8CFE1))
            }
            Button(onClick = {
                if (session.mic.active) session.mic.stop() else session.selectVoice(true)
            }) { Text(if (session.mic.active) "关闭麦克风" else "重新开启麦克风") }
        } else if (showComposer) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(
                    value = session.draft,
                    onValueChange = { session.draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("和伙伴说句话") },
                    maxLines = 2,
                )
                Button(
                    onClick = {
                        val message = session.draft.trim()
                        if (message.isNotEmpty()) {
                            session.receiveOwner(message, fromSpeech = false)
                            session.draft = ""
                        }
                    },
                    enabled = session.draft.isNotBlank(),
                ) { Text("发送") }
            }
        }
        if (session.lastOwnerLine.isNotBlank()) {
            Text("你：${session.lastOwnerLine}", color = Color(0xFFB8CFE1))
        }
        if (session.lastCompanionLine.isNotBlank()) {
            Text("伙伴：${session.lastCompanionLine}", color = Color(0xFFD8E8F2))
        }
    }
}
