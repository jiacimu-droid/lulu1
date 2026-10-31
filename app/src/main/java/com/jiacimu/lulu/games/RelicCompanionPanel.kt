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
import kotlin.coroutines.coroutineContext

/** Event-driven scene adapter. No per-frame model calls, free-running paid timer or shadow identity. */
@Composable
internal fun RelicCompanionPanel(run: RelicRun, readRun: () -> RelicRun = { run },
    onChoice: (String, IslandActorChoice) -> Boolean) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("game_companion_audio",0) }
    var voice by remember { mutableStateOf(prefs.getBoolean("voice",false)) }
    var busy by remember { mutableStateOf(false) }
    var line by remember(run.id) { mutableStateOf("") }
    var failure by remember(run.id) { mutableStateOf("") }
    var expanded by rememberSaveable(run.id){mutableStateOf(false)}
    var typedMessage by rememberSaveable(run.id){mutableStateOf("")}
    val speaker = rememberIslandCompanionVoice { failure = it }
    var inbox by rememberSaveable(run.id,stateSaver=relicInboxSaver) { mutableStateOf(IslandTeamInbox()) }
    var speechVersion by remember { mutableIntStateOf(0) }
    fun send(raw:String) {
        val message=raw.trim()
        if(message.isBlank())return
        inbox=inbox.enqueue(message)
        speechVersion++
    }
    val mic = rememberGameSpeechInput { send(it) }
    val currentRun by rememberUpdatedState(readRun)
    val latestChoice by rememberUpdatedState(onChoice)
    val latestInbox by rememberUpdatedState(inbox)
    val latestSpeech by rememberUpdatedState(speechVersion)
    val signals = remember(run.id) { Channel<Unit>(Channel.CONFLATED) }
    val members = run.allies.map { it.id }
    LaunchedEffect(voice) { speaker.setEnabled(voice) }
    LaunchedEffect(run.id,run.turns,speechVersion) { signals.trySend(Unit) }
    LaunchedEffect(run.id,members) {
        var requestedTurn = -1
        var requestedSpeech = 0
        for (signal in signals) {
            if (currentRun().phase !in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) continue
            val turn = currentRun().turns
            val version = latestSpeech
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
                    if (message.isBlank() && RelicActorPlay.acted(snapshot,id)) continue
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
                    val canApply = !alreadyActed || choice.actionId.startsWith("reserve:")
                    if (canApply && !latestChoice(id,choice)) {
                        failure = "伙伴行动未能保存，请在行囊中查看错误"
                        continue
                    }
                    if(message.isNotBlank())inbox=inbox.acknowledge(id,unread.messages.size)
                    val name = MigratedDomainStores.characters.get(id).displayName
                    line = name + "：" + choice.speech.ifBlank {
                        currentRun().events.lastOrNull { it.who == id && it.action == "decision" }?.text.orEmpty()
                    }
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
                IconButton(onClick={speaker.stop();mic()},modifier=Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Mic,"跟伙伴说话",tint=Color.White,modifier=Modifier.size(18.dp))
                }
            }
            if(members.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("宝箱留给我开，好不好？","我们一起打怪！","你能保护我吗？").forEach { line ->
                        AssistChip(onClick={send(line)},
                            label={Text(line,fontSize=10.sp)},
                            colors=AssistChipDefaults.assistChipColors(
                                containerColor=Color(0xFF304759),
                                labelColor=Color(0xFFE6F3EA)))
                    }
                }
                TextButton(onClick={expanded=!expanded},
                    contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp)) {
                    Text(if(expanded)"收起" else "打字和伙伴商量",color=Color(0xFFFFD894),fontSize=11.sp)
                }
                if(expanded)Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value=typedMessage,onValueChange={typedMessage=it},
                        modifier=Modifier.weight(1f),maxLines=2,
                        placeholder={Text("跟伙伴说句话…",fontSize=11.sp)})
                    TextButton(onClick={send(typedMessage);typedMessage=""},enabled=typedMessage.isNotBlank()) {
                        Text("发送",fontSize=11.sp)
                    }
                }
            }
            if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth(),color=Color(0xFFFFD894))
            if(line.isNotBlank())Text(line,color=Color(0xFFE6F3EA),fontSize=12.sp,maxLines=2)
            if(failure.isNotBlank())Text(failure,color=Color(0xFFFFB4AA),fontSize=11.sp,maxLines=2)
        }
    }
}

private val relicInboxSaver = Saver<IslandTeamInbox,String>(
    save={it.toJson()},restore={IslandTeamInbox.fromJson(it)},
)
