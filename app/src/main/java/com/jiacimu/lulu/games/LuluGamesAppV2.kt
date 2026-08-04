package com.jiacimu.lulu.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps all migrated games unchanged and adds a replay center that works for every record type. */
@Composable
fun LuluGamesAppV2(onBack: () -> Unit, initialGameId: String? = null) {
    val store = LuluGames.store
    val state by store.state.collectAsState()
    var replayCenterVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LuluGamesApp(onBack = onBack, initialGameId = initialGameId)
        SmallFloatingActionButton(
            onClick = { replayCenterVisible = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 18.dp, bottom = 78.dp),
            containerColor = LuluColors.Wheat,
            contentColor = LuluColors.OnWheat,
        ) {
            Icon(Icons.Outlined.Replay, "游戏回放")
        }
    }

    if (replayCenterVisible) {
        UniversalReplayCenter(
            records = state.records,
            onClear = store::clearRecords,
            onDismiss = { replayCenterVisible = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniversalReplayCenter(
    records: List<LuluGameRecord>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<LuluGameRecord?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LuluColors.Paper,
    ) {
        if (selected == null) {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("游戏回放", style = MaterialTheme.typography.headlineMedium)
                        Text("所有游戏记录都可以按步骤回看；旧记录缺少步骤时会按结算字段依次展开。", color = LuluColors.Muted)
                    }
                    if (records.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (records.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.History, null, modifier = Modifier.size(44.dp), tint = LuluColors.BlueGray)
                            Spacer(Modifier.height(10.dp))
                            Text("还没有可回放的游戏记录", fontWeight = FontWeight.Bold)
                            Text("完成一局后会自动保存。", color = LuluColors.Muted)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(records, key = LuluGameRecord::id) { record ->
                            ReplayRecordCard(record = record, onClick = { selected = record })
                        }
                    }
                }
            }
        } else {
            UniversalGameReplay(
                record = selected!!,
                onBack = { selected = null },
            )
        }
    }
}

@Composable
private fun ReplayRecordCard(record: LuluGameRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = LuluColors.CardStrong, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(gameReplayIcon(record.type), null, tint = LuluColors.BlueGray)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, fontWeight = FontWeight.Bold)
                Text(record.summary, color = LuluColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    record.createdAt.atZone(ZoneId.systemDefault()).format(ReplayDateFormatter),
                    color = LuluColors.Muted,
                    fontSize = 11.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${record.score}分", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun UniversalGameReplay(record: LuluGameRecord, onBack: () -> Unit) {
    val steps = remember(record.id, record.detailsJson, record.characterReply) { record.toReplaySteps() }
    var visibleSteps by remember(record.id) { mutableIntStateOf(1.coerceAtMost(steps.size)) }
    var playing by remember(record.id) { mutableStateOf(false) }

    LaunchedEffect(playing, record.id, steps.size) {
        while (playing && visibleSteps < steps.size) {
            delay(720)
            visibleSteps += 1
        }
        if (visibleSteps >= steps.size) playing = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回记录") }
            Column(Modifier.weight(1f)) {
                Text(record.title, style = MaterialTheme.typography.titleLarge)
                Text("步骤 ${visibleSteps.coerceAtMost(steps.size)}/${steps.size}", color = LuluColors.Muted)
            }
            Text("${record.score}分", fontWeight = FontWeight.Bold)
        }

        if (record.type == LuluGameType.SignalHunt) {
            SignalReplayBoard(record = record, visibleSteps = visibleSteps)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(steps.take(visibleSteps), key = ReplayStep::id) { step ->
                ReplayStepCard(step)
            }
        }

        LinearProgressIndicator(
            progress = { if (steps.isEmpty()) 1f else visibleSteps.toFloat() / steps.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { playing = false; visibleSteps = (visibleSteps - 1).coerceAtLeast(1) },
                enabled = visibleSteps > 1,
                modifier = Modifier.weight(1f),
            ) { Icon(Icons.Outlined.SkipPrevious, null); Text("上一步") }
            Button(
                onClick = {
                    if (visibleSteps >= steps.size) {
                        visibleSteps = 1.coerceAtMost(steps.size)
                        playing = steps.size > 1
                    } else {
                        playing = !playing
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
            ) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                Text(
                    when {
                        visibleSteps >= steps.size -> "重新播放"
                        playing -> "暂停"
                        else -> "自动播放"
                    },
                )
            }
            OutlinedButton(
                onClick = { playing = false; visibleSteps = (visibleSteps + 1).coerceAtMost(steps.size) },
                enabled = visibleSteps < steps.size,
                modifier = Modifier.weight(1f),
            ) { Text("下一步"); Icon(Icons.Outlined.SkipNext, null) }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun ReplayStepCard(step: ReplayStep) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = CircleShape, color = LuluColors.CardStrong, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(step.order.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(step.label, fontWeight = FontWeight.Bold)
                Text(step.value, color = LuluColors.Muted)
            }
        }
    }
}

@Composable
private fun SignalReplayBoard(record: LuluGameRecord, visibleSteps: Int) {
    val moves = remember(record.detailsJson) {
        runCatching {
            val array = JSONObject(record.detailsJson).optJSONArray("moves") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val move = array.optJSONObject(index) ?: continue
                    add(move.optInt("cell") to move.optBoolean("found_signal"))
                }
            }
        }.getOrDefault(emptyList())
    }
    val shown = moves.take((visibleSteps - 1).coerceAtLeast(0))
    Card(
        colors = CardDefaults.cardColors(containerColor = LuluColors.CardStrong),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { column ->
                        val cell = row * 3 + column
                        val result = shown.lastOrNull { it.first == cell }
                        Surface(
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = when (result?.second) {
                                true -> Color(0xFFDCEBD8)
                                false -> Color(0xFFF4DFDB)
                                null -> LuluColors.Card
                            },
                            border = BorderStroke(1.dp, LuluColors.Border),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(when (result?.second) { true -> "✓"; false -> "×"; null -> (cell + 1).toString() })
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ReplayStep(
    val id: String,
    val order: Int,
    val label: String,
    val value: String,
)

private fun LuluGameRecord.toReplaySteps(): List<ReplayStep> {
    val raw = mutableListOf<Pair<String, String>>()
    raw += "开局" to "${title} · ${createdAt.atZone(ZoneId.systemDefault()).format(ReplayDateFormatter)}"
    raw += "本局摘要" to summary
    val details = runCatching { JSONObject(detailsJson) }.getOrNull()
    if (details != null) flattenReplayJson(details, "结算", raw)
    raw += "本局得分" to "$score 分"
    if (playedWithCharacter) {
        val name = MigratedDomainStores.characters.get(characterId).displayName
        raw += "共同参与" to "$name 参与了这局游戏"
    }
    if (characterReply.isNotBlank()) raw += "角色回应" to characterReply
    return raw
        .filter { (_, value) -> value.isNotBlank() }
        .mapIndexed { index, (label, value) -> ReplayStep("$id-$index", index + 1, label, value) }
}

private fun flattenReplayJson(value: Any?, path: String, output: MutableList<Pair<String, String>>) {
    when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList()
            keys.forEach { key -> flattenReplayJson(value.opt(key), replayLabel(key, path), output) }
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                flattenReplayJson(value.opt(index), "$path · 第${index + 1}步", output)
            }
        }
        null, JSONObject.NULL -> Unit
        else -> output += path to value.toString()
    }
}

private fun replayLabel(key: String, parent: String): String = when (key) {
    "moves" -> "行动记录"
    "questions" -> "问答记录"
    "phase" -> "阶段"
    "round" -> "轮次"
    "score" -> "真实分 / 得分"
    "guess" -> "猜测"
    "description" -> "描述"
    "scenario" -> "剧本"
    "chapter" -> "章节"
    "action" -> "行动"
    "roll" -> "骰点"
    "result" -> "判定"
    "role_text" -> "角色推进"
    "surface" -> "题面"
    "truth" -> "答案"
    "q" -> "问题"
    "a" -> "回答"
    "found_signal" -> "发现信号"
    "points" -> "本步得分"
    "cell" -> "格子"
    else -> "$parent · ${key.replace('_', ' ')}"
}

private fun gameReplayIcon(type: LuluGameType) = when (type) {
    LuluGameType.SignalHunt -> Icons.Outlined.Radar
    LuluGameType.PerfectMan -> Icons.Outlined.FavoriteBorder
    LuluGameType.RoleplayAdventure -> Icons.Outlined.AutoStories
    LuluGameType.TurtleSoup -> Icons.Outlined.QuestionMark
    LuluGameType.RapportQuiz -> Icons.Outlined.Groups
    LuluGameType.RockPaperScissors -> Icons.Outlined.BackHand
    LuluGameType.YachtDice -> Icons.Outlined.Casino
    LuluGameType.Gomoku -> Icons.Outlined.GridOn
    LuluGameType.MemoryMatch -> Icons.Outlined.Extension
    LuluGameType.MoodGuess -> Icons.Outlined.Mood
}

private val ReplayDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
