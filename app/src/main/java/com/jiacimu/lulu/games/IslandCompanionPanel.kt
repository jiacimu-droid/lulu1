package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
    onPing: (Pair<Int, Int>) -> Unit,
) {
    val speaker = rememberIslandCompanionVoice()
    var voice by rememberSaveable { mutableStateOf(false) }
    var teamInbox by rememberSaveable(island.world.seed, stateSaver = islandTeamInboxSaver) {
        mutableStateOf(IslandTeamInbox())
    }
    var typedMessage by rememberSaveable { mutableStateOf("") }
    var showTextChat by rememberSaveable { mutableStateOf(false) }
    var speechIndex by remember { mutableIntStateOf(0) }
    var lastRequestedSpeech by remember { mutableIntStateOf(0) }
    var lastRequestedTurn by remember(island.world.seed) { mutableIntStateOf(-1) }
    var thinking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dialogue by rememberSaveable(island.world.seed) { mutableStateOf(emptyList<String>()) }
    fun submitPlayerMessage(raw: String) {
        val message = raw.trim()
        if (message.isBlank()) return
        // Preserve every player message that arrives while the model is busy;
        // the conflated wake signal does not mean conflated conversation content.
        teamInbox = teamInbox.enqueue(message)
        dialogue = (dialogue + ("你： " + message)).takeLast(6)
        speechIndex += 1
    }
    val microphone = rememberGameSpeechInput { heard -> submitPlayerMessage(heard) }
    val currentIsland by rememberUpdatedState(island)
    val currentSave by rememberUpdatedState(onSave)
    val currentPing by rememberUpdatedState(onPing)
    val members = island.world.friends.map { it.id }
    val latestNavigating by rememberUpdatedState(navigating)
    val latestSpeechIndex by rememberUpdatedState(speechIndex)
    val latestTeamInbox by rememberUpdatedState(teamInbox)
    val signals = remember(island.world.seed) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(voice) { speaker.setEnabled(voice) }

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
        val unreadSnapshot = latestTeamInbox
        val allActed = members.all { id ->
            currentIsland.world.events.any {
                it.startsWith(IslandActorPlay.eventPrefix(id, expectedTurn))
            }
        }
        // Reopening a previously completed turn does not pay for a redundant reply.
        if (allActed && members.all { unreadSnapshot.forRole(it).isEmpty() }) {
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
                if (snapshot.world.turns != expectedTurn ||
                    snapshot.world.friends.none { it.id == id }) break
                val heard = unreadSnapshot.forRole(id).joinToString("\n")
                val alreadyCompleted = snapshot.world.events.any {
                    it.startsWith(IslandActorPlay.eventPrefix(id, expectedTurn))
                }
                if (alreadyCompleted && heard.isBlank()) continue
                // Generate a real decision from this role's own identity, the actual current
                // board, and its own earlier game events. The configured Game model is reused.
                val result = runCatching { IslandActorPlay.decide(snapshot, id, heard) }
                    .getOrElse { Result.failure(it) }
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
                val selectedAction = IslandActorPlay.options(latest, id, !alreadyActed)
                    .firstOrNull { it.id == choice.actionId }
                if (selectedAction == null) {
                    error = "伙伴选择的操作已失效；消息保留待下一次重试"
                    continue
                }
                val name = MigratedDomainStores.characters.get(id).displayName
                if (!alreadyActed) {
                    val applied = IslandActorPlay.apply(latest, id, expectedTurn, choice)
                    if (applied == null) {
                        error = "局势已经改变，行动未执行；消息仍待送达"
                        continue
                    }
                    workingState = applied
                    currentSave(applied)
                    if (selectedAction.x >= 0 && selectedAction.y >= 0)
                        currentPing(selectedAction.x to selectedAction.y)
                }
                // Confirm delivery only after a valid role-owned decision was processed.
                // Never clear messages before the API returns: each role has its own ack.
                if (heard.isNotBlank()) {
                    teamInbox = teamInbox.acknowledge(id, unreadSnapshot.messages.size)
                }
                if (choice.speech.isNotBlank()) {
                    dialogue = (dialogue + (name + "： " + choice.speech)).takeLast(6)
                    speaker.enqueue(id, choice.speech)
                } else if (!alreadyActed) {
                    val event = workingState.world.events.lastOrNull().orEmpty()
                    dialogue = (dialogue + (name + " · " + event.substringAfter("：", event))).takeLast(6)
                }
            }
            // Drop only the prefix acknowledged by every current team member; newly
            // typed messages arriving during the request remain in the queue.
            teamInbox = teamInbox.compact(members)
        } finally {
            thinking = false
        }
        }
    }

    Surface(
        shape = RoundedCornerShape(19.dp),
        color = Color(0xFF19313B),
        border = BorderStroke(1.dp, Color(0xFF85BEB3).copy(alpha = .35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = Color(0xFF66D3BD).copy(alpha = .22f),
                            shape = RoundedCornerShape(10.dp)) {
                            Text("TEAM", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = Color(0xFF9DFFE5), fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                        Text("队伍频道", color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
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
                            speaker.setEnabled(voice)
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
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("一起探路", "小心怪物", "我们分头找资源").forEach { ping ->
                        AssistChip(
                            onClick = { submitPlayerMessage(ping) },
                            label = { Text(ping, fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF264951),
                                labelColor = Color(0xFFC7F4E8),
                            ),
                            border = BorderStroke(1.dp, Color(0xFF46786E)),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showTextChat = !showTextChat }) {
                        Text(if (showTextChat) "收起文字聊天" else "打字跟队友说",
                            color = Color(0xFFAEF0D7), fontSize = 12.sp)
                    }
                }
                if (showTextChat) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = typedMessage,
                            onValueChange = { typedMessage = it },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            placeholder = { Text("跟队友说句话…", fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF9DE7D0),
                                unfocusedBorderColor = Color(0xFF658F8A),
                                focusedPlaceholderColor = Color(0xFFA4C9C1),
                                unfocusedPlaceholderColor = Color(0xFFA4C9C1),
                            ),
                        )
                        Button(onClick = {
                            submitPlayerMessage(typedMessage)
                            typedMessage = ""
                        }, enabled = typedMessage.isNotBlank()) {
                            Text("发送", fontSize = 12.sp)
                        }
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
                if (teamInbox.messages.isNotEmpty()) {
                    Text(
                        "${teamInbox.messages.size} 条队伍消息待确认 · 模型失败时保留，下一次行动或发言会继续投递",
                        color = Color(0xFFEFD3A6), fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }
                // New chat is immediate; saved factual world events still reappear after rotation.
                val teamFeed = if (dialogue.isNotEmpty()) dialogue.takeLast(3)
                    else island.world.events.filter { it.startsWith("队友[") }.takeLast(2)
                teamFeed.forEach { line ->
                    Surface(
                        color = Color(0xFF254752), shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .06f)),
                    ) {
                        Text(line, modifier = Modifier.fillMaxWidth().padding(10.dp),
                            color = Color(0xFFE7F9F0), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                Text("聊天选择游戏模型；朗读使用此人物已配置的语音服务。选择收费音色时会按次计费，关闭则不合成。",
                    color = Color(0xFF95BEB4), fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

/** Saved-state only: the real actor identity and game history remain in existing stores. */
private val islandTeamInboxSaver = Saver<IslandTeamInbox, String>(
    save = { it.toJson() },
    restore = { IslandTeamInbox.fromJson(it) },
)
