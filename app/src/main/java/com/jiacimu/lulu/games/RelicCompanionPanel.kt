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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.coroutineContext

/** Event-driven scene adapter. No per-frame model calls, free-running paid timer or shadow identity. */
@Composable
internal fun RelicCompanionPanel(run: RelicRun, continuousTravel: Boolean = false,
    readRun: () -> RelicRun = { run },
    onTeamPulse: (String) -> RelicRun? = { null },
    onChoice: (String, IslandActorChoice) -> Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("game_companion_audio",0) }
    var voice by remember { mutableStateOf(prefs.getBoolean("voice",true)) }
    var busy by remember { mutableStateOf(false) }
    var line by remember(run.id) { mutableStateOf("") }
    var failure by remember(run.id) { mutableStateOf("") }
    var expanded by rememberSaveable(run.id){mutableStateOf(false)}
    var typedMessage by rememberSaveable(run.id){mutableStateOf("")}
    val speaker = rememberIslandCompanionVoice { failure = it }
    var inbox by rememberSaveable(run.id,stateSaver=relicInboxSaver) { mutableStateOf(IslandTeamInbox()) }
    var speechVersion by remember { mutableIntStateOf(0) }
    fun send(raw:String, fromSpeech:Boolean=false) {
        val message=raw.trim()
        if(message.isBlank())return
        inbox=inbox.enqueue(message)
        line="你：" + message
        GameTeamConversationTimeline.recordOwner(
            context, "relic_escape", run.id, run.allies.map { it.id }, message, fromSpeech,
        )
        speechVersion++
    }
    val mic = rememberGameSpeechInput { send(it, fromSpeech = true) }
    val (openMic, toggleOpenMic) = rememberIslandGameOpenMic { spoken ->
        send(spoken, fromSpeech = true)
    }
    DisposableEffect(speaker, openMic) {
        speaker.setSpeakingListener { openMic.setCompanionSpeaking(it) }
        onDispose { speaker.setSpeakingListener(null); openMic.stop() }
    }
    val currentRun by rememberUpdatedState(readRun)
    val latestChoice by rememberUpdatedState(onChoice)
    val latestPulse by rememberUpdatedState(onTeamPulse)
    val latestInbox by rememberUpdatedState(inbox)
    val latestSpeech by rememberUpdatedState(speechVersion)
    val latestTraveling by rememberUpdatedState(continuousTravel)
    var notifiedSpeech by remember(run.id) { mutableIntStateOf(0) }
    val signals = remember(run.id) { Channel<Unit>(Channel.CONFLATED) }
    var lastTeamPulseTurn by remember(run.id) { mutableIntStateOf(-1) }
    var lastTeamPulseFinished by remember(run.id) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val members = run.allies.map { it.id }
    LaunchedEffect(voice) { speaker.setEnabled(voice) }
    // A slow local game clock only resumes a previously actor-selected route.
    // No model calls while that route takes legal physical steps.
    LaunchedEffect(run.id, members) {
        while (isActive) {
            delay(GameCoopPacing.PLANNED_STEP_MS)
            val now = currentRun()
            if (now.id != run.id) break
            if (!GameCoopPacing.canTick(
                    hasPlan = GameCoopPacing.relicHasPlan(now),
                    isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                    navigating = latestTraveling,
                    thinking = busy,
                    playerSpeaking = openMic.hasUncommittedSpeech,
                    nearbyThreat = GameCoopPacing.relicThreat(now),
                )) continue
            val after = latestPulse(now.id)
            if (after != null && after.id == now.id && after.turns > now.turns) {
                lastTeamPulseTurn = after.turns
                lastTeamPulseFinished = now.coopPlans.keys - after.coopPlans.keys
            }
        }
    }
    // Moving on a held joystick may create many local movement turns.
    // Do not charge a fresh companion decision for every tile of one gesture.
    // Explicit player messages still wake the real role while traveling.
    LaunchedEffect(run.id,run.turns,speechVersion,continuousTravel) {
        if (!continuousTravel || speechVersion > notifiedSpeech) {
            notifiedSpeech = speechVersion
            signals.trySend(Unit)
        }
    }
    LaunchedEffect(run.id,members) {
        var requestedTurn = -1
        var requestedSpeech = 0
        for (signal in signals) {
            if (currentRun().phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) continue
            val turn = currentRun().turns
            val version = latestSpeech
            if (latestTraveling && requestedSpeech == version) continue
            if (requestedTurn == turn && requestedSpeech == version) continue
            val unread = latestInbox
            requestedTurn = turn
            requestedSpeech = version
            busy = true
            failure = ""
            try {
                for (id in members) {
                    val snapshot = currentRun()
                    if (snapshot.allies.none { it.id == id && it.hp > 0 }) continue
                    val message=unread.forRole(id).joinToString("\n")
                    val urgentDown=snapshot.events.any {
                        it.who=="player" && it.action=="down" && it.turn==snapshot.turns
                    }
                    if (!GameCoopPacing.mayReplanAfterTick(
                            snapshot.turns, lastTeamPulseTurn, id,
                            lastTeamPulseFinished, message.isNotBlank())) continue
                    val finishedOwnGoal = snapshot.turns == lastTeamPulseTurn &&
                        id in lastTeamPulseFinished
                    if (message.isBlank() &&
                        (RelicActorPlay.acted(snapshot,id) || id in snapshot.coopPlans) &&
                        !urgentDown && !finishedOwnGoal) continue
                    val result = runCatching { RelicActorPlay.decide(snapshot,id,message) }
                        .getOrElse { if(it is CancellationException)throw it else Result.failure(it) }
                    coroutineContext.ensureActive()
                    val choice = result.getOrNull()
                    if (choice == null) {
                        failure = result.exceptionOrNull()?.message ?: "伙伴决定未完成"
                        continue
                    }
                    if (currentRun().id != snapshot.id || currentRun().phase !in
                        setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) break
                    val alreadyActed = RelicActorPlay.acted(currentRun(),id)
                    if (RelicActorPlay.options(currentRun(),id).none { it.id == choice.actionId }) {
                        failure = "局势已变化，伙伴选择的操作未执行"
                        continue
                    }
                    val canApply = !alreadyActed || choice.actionId.startsWith("reserve:") ||
                        choice.actionId=="stop_travel" || choice.actionId.startsWith("plan_")
                    if (canApply && !latestChoice(id,choice)) {
                        failure = "伙伴行动未能保存，请在行囊中查看错误"
                        continue
                    }
                    if (canApply && choice.actionId != "none") {
                        val appliedRun = currentRun()
                        val actual = appliedRun.events.lastOrNull {
                            it.who == id && it.action == "decision" && it.turn >= snapshot.turns
                        }?.text
                        GameTeamConversationTimeline.recordEngineAcceptedAction(
                            context, "relic_escape", snapshot.id, id, snapshot.turns,
                            choice.actionId,
                            actual?.takeIf(String::isNotBlank)
                                ?: "游戏规则已接受 ${choice.actionId}；持续计划仍待实际推进",
                        )
                    }
                    if(message.isNotBlank())inbox=inbox.acknowledge(id,unread.messages.size)
                    val name = MigratedDomainStores.characters.get(id).displayName
                    line = name + "：" + choice.speech.ifBlank {
                        currentRun().events.lastOrNull { it.who == id && it.action == "decision" }?.text.orEmpty()
                    }
                    if (choice.speech.isNotBlank()) GameTeamConversationTimeline.recordCompanion(
                        context, "relic_escape", snapshot.id, members, id, choice.speech,
                    )
                    speaker.enqueue(id,choice.speech)
                }
                inbox=inbox.compact(members)
            } finally { busy = false }
        }
    }
    Surface(color=Color(0xEE182C38),shape=RoundedCornerShape(18.dp),
        border=BorderStroke(1.dp,Color(0x557DADAD))) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if (busy) "伙伴正在看局势…" else "伙伴自主同行",color=Color.White,
                    fontSize=12.sp,modifier=Modifier.weight(1f))
                IconButton(onClick={ voice=!voice; prefs.edit().putBoolean("voice",voice).apply()
                    if(!voice)speaker.stop() },modifier=Modifier.size(34.dp)) {
                    Icon(if(voice)Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        if(voice)"关闭伙伴语音" else "开启伙伴语音",tint=Color.White,modifier=Modifier.size(18.dp))
                }
                Surface(onClick=toggleOpenMic,
                    color=if(openMic.active) Color(0xFFFFD7BA) else Color(0xFFFFD894),
                    shape=RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(horizontal=8.dp,vertical=7.dp),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Outlined.Mic,
                            if(openMic.active)"关闭游戏连麦" else "开启游戏连麦",
                            tint=Color(0xFF15202B),modifier=Modifier.size(16.dp))
                        Text(if(openMic.active)"关麦" else "开麦",
                            color=Color(0xFF15202B),fontSize=11.sp)
                    }
                }
                TextButton(onClick={expanded=true},contentPadding=PaddingValues(4.dp)) {
                    Text("⋯",color=Color(0xFFFFD894),fontSize=15.sp)
                }
            }
            if(openMic.active) Text(
                if(openMic.partial.isNotBlank()) "你：" + openMic.partial
                else openMic.status.ifBlank { "游戏连麦中 · 直接说话" },
                color=Color(0xFFE6F3EA),fontSize=10.sp,maxLines=2)
            else if(openMic.status.isNotBlank()) Text(openMic.status,
                color=Color(0xFFFFCBAF),fontSize=10.sp,maxLines=2)
            if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth(),color=Color(0xFFFFD894))
            if(line.isNotBlank())Text(line,color=Color(0xFFE6F3EA),fontSize=12.sp,maxLines=2)
            if(failure.isNotBlank())Text(failure,color=Color(0xFFFFB4AA),fontSize=11.sp,maxLines=2)
        }
    }
    // Full conversation controls appear only on demand, outside the tiny
    // bottom-centre play toolbar. The world remains the first visual priority.
    if(expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        containerColor = Color(0xFF182C38),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE6F3EA),
        title = { Text("队伍交流",fontSize=17.sp) },
        text = {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("角色会继续自己选定的行动；你可以随时商量改变路线。",
                    fontSize=11.sp,color=Color(0xFFB5D3D6))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf(if(run.phase==RelicPhase.DOWNED) "你能来救救我吗？"
                        else "宝箱留给我开，好不好？",
                        "你能把宝物送给我吗？","我们一起打怪！","你能保护我吗？"
                    ).forEach { suggestion ->
                        AssistChip(onClick={send(suggestion);expanded=false},
                            label={Text(suggestion,fontSize=10.sp)},
                            colors=AssistChipDefaults.assistChipColors(
                                containerColor=Color(0xFF304759),
                                labelColor=Color(0xFFE6F3EA)))
                    }
                }
                OutlinedTextField(value=typedMessage,
                    onValueChange={typedMessage=it},
                    modifier=Modifier.fillMaxWidth(),maxLines=3,
                    placeholder={Text("跟伙伴说句话…",fontSize=12.sp)})
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={speaker.stop();mic();expanded=false}) {
                        Text("单次语音",color=Color(0xFFFFD894))
                    }
                    TextButton(onClick={send(typedMessage);typedMessage="";expanded=false},
                        enabled=typedMessage.isNotBlank()) {
                        Text("发送",color=Color(0xFFFFD894))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick={expanded=false}) {
                Text("回到游戏",color=Color(0xFFFFD894))
            }
        },
    )
}

private val relicInboxSaver = Saver<IslandTeamInbox,String>(
    save={it.toJson()},restore={IslandTeamInbox.fromJson(it)},
)
