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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

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
    readIsland: () -> BlockIslandState = { island },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("game_companion_audio",0) }
    var voice by rememberSaveable { mutableStateOf(prefs.getBoolean("voice",false)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
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
    val speaker = rememberIslandCompanionVoice { error = it }
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
    val readCurrentIsland by rememberUpdatedState(readIsland)
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
        val expectedTurn = readCurrentIsland().world.turns
        val currentSpeech = latestSpeechIndex
        if (lastRequestedTurn == expectedTurn && lastRequestedSpeech == currentSpeech) continue
        val unreadSnapshot = latestTeamInbox
        val allActed = members.all { id ->
            readCurrentIsland().world.events.any {
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
            var workingState = readCurrentIsland()
            for (id in members) {
                val snapshot = readCurrentIsland()
                if (snapshot.world.friends.none { it.id == id && it.hp > 0 }) continue
                val heard = unreadSnapshot.forRole(id).joinToString("\n")
                val alreadyCompleted = snapshot.world.events.any {
                    it.startsWith(IslandActorPlay.eventPrefix(id, snapshot.world.turns))
                }
                if (alreadyCompleted && heard.isBlank()) continue
                // Generate a real decision from this role's own identity, the actual current
                // board, and its own earlier game events. The configured Game model is reused.
                val result = runCatching { IslandActorPlay.decide(snapshot, id, heard) }
                    .getOrElse { if(it is CancellationException)throw it else Result.failure(it) }
                coroutineContext.ensureActive()
                val choice = result.getOrNull()
                if (choice == null) {
                    error = result.exceptionOrNull()?.message ?: "伙伴暂时无法作出决定"
                    continue
                }
                if (readCurrentIsland().world.seed != snapshot.world.seed) break
                val latest = readCurrentIsland()
                val alreadyActed = latest.world.events.any {
                    it.startsWith(IslandActorPlay.eventPrefix(id, latest.world.turns))
                }
                val selectedAction = IslandActorPlay.options(latest, id, !alreadyActed)
                    .firstOrNull { it.id == choice.actionId }
                if (selectedAction == null) {
                    error = "伙伴选择的操作已失效；消息保留待下一次重试"
                    continue
                }
                val name = MigratedDomainStores.characters.get(id).displayName
                if (!alreadyActed || choice.actionId == "stop_travel" ||
                    IslandHouseProjects.isChangeAction(choice.actionId)) {
                    val applied = IslandActorPlay.applyLive(latest, snapshot, id, choice)
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
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (thinking) "伙伴正在看局势…" else if (autonomous) "队伍频道" else "伙伴操作已暂停",
                    color=Color.White,fontWeight=FontWeight.Bold,fontSize=12.sp,modifier=Modifier.weight(1f))
                IconButton(onClick={ voice=!voice; prefs.edit().putBoolean("voice",voice).apply()
                    speaker.setEnabled(voice) },modifier=Modifier.size(34.dp)) {
                    Icon(if(voice)Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        if(voice)"关闭伙伴语音" else "开启伙伴语音",tint=Color.White,modifier=Modifier.size(18.dp))
                }
                IconButton(onClick={speaker.stop();microphone()},modifier=Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Mic,"跟伙伴说话",tint=Color.White,modifier=Modifier.size(18.dp))
                }
                TextButton(onClick={ expanded=!expanded },contentPadding=PaddingValues(4.dp)) {
                    Text(if(expanded)"收起" else "聊天",color=Color(0xFFBDE4D5),fontSize=11.sp)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                island.world.friends.forEach { friend ->
                    val name=MigratedDomainStores.characters.get(friend.id).displayName
                    val travel=island.companionTravel[friend.id]
                    AssistChip(onClick={currentPing((travel?.x ?: friend.x) to (travel?.y ?: friend.y))},
                        label={ Text(name+" · "+friend.hp+"❤"+
                            (travel?.let { " → (${it.x},${it.y})" } ?: ""),fontSize=11.sp) },
                        colors=AssistChipDefaults.assistChipColors(containerColor=Color(0xFF254752),
                            labelColor=Color(0xFFD7F6E5)),
                        border=BorderStroke(1.dp,Color(0xFF46786E)))
                }
            }
            if(expanded) Row(verticalAlignment=Alignment.CenterVertically) {
                Text("角色自主操作",color=Color.White,modifier=Modifier.weight(1f),fontSize=12.sp)
                Switch(checked=autonomous,onCheckedChange=onAutonomousChange)
            }
            if (autonomous) {
                if(expanded) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("一起去打怪吧！", "你能保护我一下吗？", "我们一起把房子建起来").forEach { ping ->
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
                }
                if (thinking) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFA3E5C5),
                    )
                }
                if (error.isNotBlank())
                    Text(error + "（游戏仍可继续）", color = Color(0xFFFFC3BA),
                        fontSize = 11.sp, lineHeight = 17.sp)
                if (expanded && teamInbox.messages.isNotEmpty()) {
                    Text(
                        "${teamInbox.messages.size} 条队伍消息待确认 · 模型失败时保留，下一次行动或发言会继续投递",
                        color = Color(0xFFEFD3A6), fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }
                // New chat is immediate; saved factual world events still reappear after rotation.
                val teamFeed = if (dialogue.isNotEmpty()) dialogue.takeLast(if(expanded)3 else 1)
                    else island.world.events.filter { it.startsWith("队友[") }.takeLast(if(expanded)2 else 1)
                teamFeed.forEach { line ->
                    Surface(
                        color = Color(0xFF254752), shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .06f)),
                    ) {
                        Text(line, modifier = Modifier.fillMaxWidth().padding(10.dp),
                            color = Color(0xFFE7F9F0), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                if(expanded) Text("聊天选择游戏模型；朗读使用此人物已配置的语音服务。选择收费音色时会按次计费，关闭则不合成。",
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
