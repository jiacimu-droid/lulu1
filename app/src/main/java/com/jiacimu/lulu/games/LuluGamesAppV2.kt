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

/** Keeps migrated games unchanged and adds the shared replay entry point. */
@Composable
fun LuluGamesAppV2(onBack: () -> Unit, initialGameId: String? = null) {
    val store = LuluGames.store
    val state by store.state.collectAsState()
    var replayCenterVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LuluGamesApp(onBack = onBack, initialGameId = initialGameId)
        SmallFloatingActionButton(
            onClick = { replayCenterVisible = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 78.dp),
            containerColor = GameDesign.ink,
            contentColor = Color.White,
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
    val memoryReplay = remember(record.id, record.detailsJson) { parseMemoryReplay(record.detailsJson) }
    val yachtReplay = remember(record.id, record.detailsJson) { parseYachtReplay(record.detailsJson) }
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

        when {
            memoryReplay != null -> Box(Modifier.weight(1f)) {
                MemoryMatchReplayScene(memoryReplay, visibleSteps, record.score)
            }
            yachtReplay != null -> Box(Modifier.weight(1f)) {
                YachtReplayScene(yachtReplay, visibleSteps, record.score)
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(steps.take(visibleSteps), key = ReplayStep::id) { step ->
                    ReplayStepCard(step)
                }
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

private data class ReplayStep(
    val id: String,
    val order: Int,
    val label: String,
    val value: String,
)

private fun LuluGameRecord.toReplaySteps(): List<ReplayStep> {
    parseMemoryReplay(detailsJson)?.let { replay ->
        val captions = replay.turns.mapIndexed { index, turn ->
            "第${index + 1}轮" to "翻开第${turn.first + 1}张和第${turn.second + 1}张：${turn.firstValue} / ${turn.secondValue}，${if (turn.matched) "配对成功" else "未配对"}"
        } + listOf("结算" to summary)
        return captions.mapIndexed { index, (label, value) -> ReplayStep("$id-$index", index + 1, label, value) }
    }
    parseYachtReplay(detailsJson)?.let { replay ->
        val captions = replay.mapIndexed { index, turn ->
            "第${index + 1}轮 · ${turn.category}" to "三掷结束：${turn.rolls.lastOrNull()?.joinToString("、").orEmpty()}；本轮${turn.points}分，累计${turn.total}分"
        } + listOf("结算" to summary)
        return captions.mapIndexed { index, (label, value) -> ReplayStep("$id-$index", index + 1, label, value) }
    }
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

/** Rebuild the original face-down board for each move; never expose a hidden card early. */
internal data class MemoryReplayTurn(
    val first: Int,
    val second: Int,
    val firstValue: String,
    val secondValue: String,
    val matched: Boolean,
)
internal data class MemoryReplayMatch(val cards: List<String>, val turns: List<MemoryReplayTurn>)

internal fun parseMemoryReplay(json: String): MemoryReplayMatch? = runCatching {
    val details = JSONObject(json)
    if (details.optString("game") != "memory_match") return@runCatching null
    val cardsRaw = details.optJSONArray("cards") ?: return@runCatching null
    val turnsRaw = details.optJSONArray("turns") ?: return@runCatching null
    if (cardsRaw.length() != 12 || turnsRaw.length() == 0) return@runCatching null
    val cards = (0 until cardsRaw.length()).map { cardsRaw.optString(it) }
    if (cards.any(String::isBlank)) return@runCatching null
    val turns = (0 until turnsRaw.length()).map { index ->
        val item = turnsRaw.getJSONObject(index)
        MemoryReplayTurn(
            first = item.getInt("first"),
            second = item.getInt("second"),
            firstValue = item.getString("firstValue"),
            secondValue = item.getString("secondValue"),
            matched = item.getBoolean("matched"),
        )
    }
    if (turns.any {
        it.first !in cards.indices || it.second !in cards.indices || it.first == it.second ||
            cards[it.first] != it.firstValue || cards[it.second] != it.secondValue ||
            (it.firstValue == it.secondValue) != it.matched
    }) return@runCatching null
    MemoryReplayMatch(cards, turns)
}.getOrNull()

internal data class YachtReplayTurn(
    val rolls: List<List<Int>>,
    val category: String,
    val points: Int,
    val total: Int,
)
internal fun parseYachtReplay(json: String): List<YachtReplayTurn>? = runCatching {
    val details = JSONObject(json)
    if (details.optString("game") != "yacht_dice") return@runCatching null
    val raw = details.optJSONArray("turns") ?: return@runCatching null
    if (raw.length() != 13) return@runCatching null
    val turns = (0 until raw.length()).map { index ->
        val item = raw.getJSONObject(index)
        val throws = item.getJSONArray("rolls")
        val rolls = (0 until throws.length()).map { r ->
            val dice = throws.getJSONArray(r)
            (0 until dice.length()).map { dice.getInt(it) }
        }
        YachtReplayTurn(rolls, item.getString("categoryLabel"), item.getInt("points"), item.getInt("total"))
    }
    if (turns.any { it.rolls.size != 3 || it.rolls.any { dice -> dice.size != 5 || dice.any { value -> value !in 1..6 } } }) return@runCatching null
    turns
}.getOrNull()

@Composable
private fun MemoryMatchReplayScene(replay: MemoryReplayMatch, step: Int, score: Int) {
    val index = (step - 1).coerceAtLeast(0)
    val turn = replay.turns.getOrNull(index)
    val revealed = replay.turns.take(if (turn == null) replay.turns.size else index + 1)
        .filter { it.matched }
        .flatMap { listOf(it.first, it.second) }.toSet()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (turn == null) "完成配对 · $score 分"
            else "第${index + 1}轮 · ${if (turn.matched) "配对成功！" else "没配上，先记住位置"}",
            style = MaterialTheme.typography.titleMedium,
        )
        repeat(3) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { col ->
                    val cell = row * 4 + col
                    val faceUp = cell in revealed || turn?.first == cell || turn?.second == cell
                    Surface(
                        modifier = Modifier.weight(1f).aspectRatio(0.85f),
                        shape = RoundedCornerShape(14.dp),
                        color = when {
                            cell in revealed -> LuluColors.CardStrong
                            faceUp -> LuluColors.Card
                            else -> GameDesign.ink
                        },
                        border = BorderStroke(1.dp, LuluColors.Border),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (faceUp) replay.cards[cell] else "✦",
                                fontSize = if (faceUp) 27.sp else 23.sp,
                                color = if (faceUp) GameDesign.ink else Color.White,
                            )
                            Text("${cell + 1}", modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                fontSize = 10.sp, color = if (faceUp) LuluColors.Muted else Color.LightGray)
                        }
                    }
                }
            }
        }
        Text(
            if (turn == null) "6对全部找齐 · 这一局已结束"
            else "翻开 ${turn.first + 1} 号 ${turn.firstValue} 和 ${turn.second + 1} 号 ${turn.secondValue} · ${if (turn.matched) "保留翻开" else "下一轮盖回"}",
            color = LuluColors.Muted,
        )
    }
}

@Composable
private fun YachtReplayScene(rounds: List<YachtReplayTurn>, step: Int, score: Int) {
    val index = (step - 1).coerceAtLeast(0)
    val round = rounds.getOrNull(index)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (round == null) {
            Text("13轮结束 · 总分 $score", style = MaterialTheme.typography.titleLarge)
            Text("从第一轮开始回看每次投掷与计分结果。", color = LuluColors.Muted)
        } else {
            Text("第${index + 1}轮 · ${round.category}", style = MaterialTheme.typography.titleMedium)
            round.rolls.forEachIndexed { rollIndex, dice ->
                Text("第${rollIndex + 1}掷", color = LuluColors.Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    dice.forEach { pip ->
                        Surface(
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            color = LuluColors.Card,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, LuluColors.Border),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(pip.toString(), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Text("选了${round.category} · 本轮 +${round.points} 分 · 累计 ${round.total} 分", fontWeight = FontWeight.SemiBold)
        }
    }
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
    LuluGameType.PerfectMan -> Icons.Outlined.FavoriteBorder
    LuluGameType.RoleplayAdventure -> Icons.Outlined.AutoStories
    LuluGameType.TurtleSoup -> Icons.Outlined.QuestionMark
    LuluGameType.RapportQuiz -> Icons.Outlined.Groups
    LuluGameType.RockPaperScissors -> Icons.Outlined.BackHand
    LuluGameType.YachtDice -> Icons.Outlined.Casino
    LuluGameType.Gomoku -> Icons.Outlined.GridOn
    LuluGameType.MemoryMatch -> Icons.Outlined.Extension
    LuluGameType.DeepSeaJourney -> Icons.Outlined.Waves
    LuluGameType.MoodGuess -> Icons.Outlined.Mood
}

private val ReplayDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
