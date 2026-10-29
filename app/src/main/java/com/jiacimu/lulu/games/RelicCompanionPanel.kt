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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Event-driven scene adapter. No per-frame model calls, free-running paid timer or shadow identity. */
@Composable
internal fun RelicCompanionPanel(run: RelicRun, onChoice: (String, IslandActorChoice) -> Boolean) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("game_companion_audio",0) }
    var voice by remember { mutableStateOf(prefs.getBoolean("voice",false)) }
    var busy by remember { mutableStateOf(false) }
    var line by remember(run.id) { mutableStateOf("") }
    var failure by remember(run.id) { mutableStateOf("") }
    val speaker = rememberIslandCompanionVoice { failure = it }
    var heard by remember { mutableStateOf("") }
    var speechVersion by remember { mutableIntStateOf(0) }
    val mic = rememberGameSpeechInput { heard = it; speechVersion++ }
    val latest by rememberUpdatedState(run)
    val latestChoice by rememberUpdatedState(onChoice)
    val latestHeard by rememberUpdatedState(heard)
    val latestSpeech by rememberUpdatedState(speechVersion)
    val signals = remember(run.id) { Channel<Unit>(Channel.CONFLATED) }
    val members = run.allies.map { it.id }
    LaunchedEffect(voice) { speaker.setEnabled(voice) }
    LaunchedEffect(run.id,run.turns,speechVersion) { signals.trySend(Unit) }
    LaunchedEffect(run.id,members) {
        var requestedTurn = -1
        var requestedSpeech = 0
        for (signal in signals) {
            if (latest.phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) continue
            val turn = latest.turns
            val version = latestSpeech
            if (requestedTurn == turn && requestedSpeech == version) continue
            val message = if (version != requestedSpeech) latestHeard else ""
            requestedTurn = turn
            requestedSpeech = version
            busy = true
            failure = ""
            try {
                for (id in members) {
                    val snapshot = latest
                    if (snapshot.allies.none { it.id == id && it.hp > 0 }) continue
                    if (message.isBlank() && RelicActorPlay.acted(snapshot,id)) continue
                    val result = runCatching { RelicActorPlay.decide(snapshot,id,message) }
                        .getOrElse { if(it is CancellationException)throw it else Result.failure(it) }
                    coroutineContext.ensureActive()
                    val choice = result.getOrNull()
                    if (choice == null) {
                        failure = result.exceptionOrNull()?.message ?: "伙伴决定未完成"
                        continue
                    }
                    if (latest.id != snapshot.id || latest.phase !in
                        setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) break
                    val alreadyActed = RelicActorPlay.acted(latest,id)
                    if (RelicActorPlay.options(latest,id).none { it.id == choice.actionId }) {
                        failure = "局势已变化，伙伴选择的操作未执行"
                        continue
                    }
                    if (!alreadyActed && !latestChoice(id,choice)) {
                        failure = "伙伴行动未能保存，请在行囊中查看错误"
                        continue
                    }
                    val name = MigratedDomainStores.characters.get(id).displayName
                    line = name + "：" + choice.speech.ifBlank {
                        latest.events.lastOrNull { it.who == id && it.action == "decision" }?.text.orEmpty()
                    }
                    speaker.enqueue(id,choice.speech)
                }
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
                IconButton(onClick={speaker.stop();mic()},modifier=Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Mic,"跟伙伴说话",tint=Color.White,modifier=Modifier.size(18.dp))
                }
            }
            if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth(),color=Color(0xFFFFD894))
            if(line.isNotBlank())Text(line,color=Color(0xFFE6F3EA),fontSize=12.sp,maxLines=2)
            if(failure.isNotBlank())Text(failure,color=Color(0xFFFFB4AA),fontSize=11.sp,maxLines=2)
        }
    }
}
