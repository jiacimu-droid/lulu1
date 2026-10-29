package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.channels.Channel

/**
 * This is a game scene adapter, not a separate character identity or action store.
 * One game-model choice per meaningful completed player turn (or explicit voice message);
 * no frame-based polling, passive paid thinking or endless reciprocal wake-ups.
 */
@Composable
internal fun IslandCompanionPanel(
    island: BlockIslandState,
    navigating: Boolean,
    autonomous: Boolean,
    onAutonomousChange: (Boolean) -> Unit,
    onSave: (BlockIslandState) -> Unit,
) {
    val speaker = rememberGameSpeaker()
    var voice by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableStateOf("") }
    var speechIndex by remember { mutableIntStateOf(0) }
    var lastRequestedSpeech by remember { mutableIntStateOf(0) }
    var lastRequestedTurn by remember(island.world.seed) { mutableIntStateOf(-1) }
    var thinking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dialogue by remember(island.world.seed) { mutableStateOf(emptyList<String>()) }
    val microphone = rememberGameSpeechInput { heard ->
        lastSpoken = heard
        speechIndex += 1
    }
    val currentIsland by rememberUpdatedState(island)
    val currentSave by rememberUpdatedState(onSave)
    val members = island.world.friends.map { it.id }
    val latestNavigating by rememberUpdatedState(navigating)
    val latestSpeechIndex by rememberUpdatedState(speechIndex)
    val latestSpoken by rememberUpdatedState(lastSpoken)
    val signals = remember(island.world.seed) { Channel<Unit>(Channel.CONFLATED) }
    speaker.enabled = voice

    // Real state changes only enqueue one signal. Fast manual movement does not cancel
    // in-flight model requests or generate unlimited duplicate decisions.
    LaunchedEffect(island.world.seed, island.world.turns, navigating, autonomous, speechIndex, members) {
        if (autonomous && !navigating && members.isNotEmpty()) signals.trySend(Unit)
    }
    LaunchedEffect(island.world.seed, autonomous, members) {
        if (!autonomous || members.isEmpty()) return@LaunchedEffect
        for (signal in signals) {
        if (latestNavigating) continue
        val expectedTurn = currentIsland.world.turns
        val currentSpeech = latestSpeechIndex
        if (lastRequestedTurn == expectedTurn && lastRequestedSpeech == currentSpeech) continue
        val heard = if (lastRequestedSpeech != currentSpeech) latestSpoken else ""
        val allActed = members.all { id ->
            currentIsland.world.events.any {
                it.startsWith(IslandActorPlay.eventPrefix(id, expectedTurn))
            }
        }
        // Reopening a previously completed turn does not pay for a redundant reply.
        if (heard.isBlank() && allActed) {
            lastRequestedTurn = expectedTurn
            lastRequestedSpeech = currentSpeech
            continue
        }
        lastRequestedTurn = expectedTurn
        lastRequestedSpeech = currentSpeech
        error = ""
        thinking = true
        try {
            var workingState = currentIsland
            for (id in members) {
                val snapshot = workingState
                if (snapshot.world.turns != expectedTurn || snapshot.world.friends.none { it.id == id }) break
                // Generate a real decision from this role's own identity, the actual current
                // board, and its own earlier game events. The configured Game model is reused.
                val result = IslandActorPlay.decide(snapshot, id, heard)
                val choice = result.getOrNull()
                if (choice == null) {
                    error = result.exceptionOrNull()?.message ?: "伙伴暂时无法作出决定"
                    continue
                }
                if (currentIsland.world.turns != expectedTurn) break
                val latest = workingState
                val alreadyActed = latest.world.events.any {
                    it.startsWith(IslandActorPlay.eventPrefix(id, expectedTurn))
                }
                val valid = IslandActorPlay.options(latest, id, !alreadyActed)
                    .any { it.id == choice.actionId }
                if (!valid) {
                    error = "伙伴选择的操作已失效；没有替他改选动作"
                    continue
                }
                val name = MigratedDomainStores.characters.get(id).displayName
                if (!alreadyActed) {
                    val applied = IslandActorPlay.apply(latest, id, expectedTurn, choice)
                    if (applied == null) {
                        error = "局势已经改变，本次角色行动未执行"
                        continue
                    }
                    workingState = applied
                    currentSave(applied)
                }
                if (choice.speech.isNotBlank()) {
                    dialogue = (dialogue + (name + "： " + choice.speech)).takeLast(6)
                    speaker.speak(choice.speech, append = true)
                } else if (!alreadyActed) {
                    val event = workingState.world.events.lastOrNull().orEmpty()
                    dialogue = (dialogue + (name + " · " + event.substringAfter("：", event))).takeLast(6)
                }
            }
        } finally {
            thinking = false
        }
        }
    }

    Surface(
        shape = RoundedCornerShape(19.dp),
        color = Color(0xFF1B3A42),
        border = BorderStroke(1.dp, Color(0xFF85BEB3).copy(alpha = .35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("伙伴自主游玩", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(if (autonomous) "每位伙伴自行选择行动，不再听固定跟随指令"
                         else "已关闭模型自主行动，恢复原有队友模式",
                        color = Color(0xFFAFD1C8), fontSize = 11.sp, lineHeight = 16.sp)
                }
                Switch(checked = autonomous, onCheckedChange = onAutonomousChange)
            }
            if (autonomous) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            voice = !voice
                            if (!voice) speaker.stop()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (voice) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, null,
                            modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (voice) "伙伴语音已开" else "开启伙伴语音", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = microphone, enabled = !thinking) {
                        Icon(Icons.Outlined.Mic, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("说一句", fontSize = 11.sp)
                    }
                }
                if (thinking) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFA3E5C5),
                    )
                    Text("伙伴正在看地图，自己决定下一步…", color = Color(0xFFBDE4D5),
                        fontSize = 11.sp)
                }
                if (error.isNotBlank())
                    Text(error + "（游戏仍可继续）", color = Color(0xFFFFC3BA),
                        fontSize = 11.sp, lineHeight = 17.sp)
                dialogue.forEach { line ->
                    Text(line, color = Color(0xFFE7F9F0), fontSize = 12.sp,
                        lineHeight = 18.sp)
                }
                Text("使用游戏馆所选模型；语音为设备朗读，麦克风按次识别。",
                    color = Color(0xFF95BEB4), fontSize = 10.sp)
            }
        }
    }
}
