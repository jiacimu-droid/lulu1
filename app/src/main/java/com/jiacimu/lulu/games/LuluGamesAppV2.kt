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

    LuluGamesApp(onBack = onBack, initialGameId = initialGameId,
        onReplayCenter = { replayCenterVisible = true })

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
    var showReplay by remember(selected?.id) { mutableStateOf(false) }
    BackHandler(enabled = selected != null) {
        if (showReplay) showReplay = false else selected = null
    }
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
                        Text("游戏战绩", style = MaterialTheme.typography.headlineMedium)
                        Text("先看最终得分与胜负，回放过程可按需打开。", color = LuluColors.Muted)
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
                            Text("还没有已结算的游戏", fontWeight = FontWeight.Bold)
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
        } else if (showReplay) {
            UniversalGameReplay(record = selected!!, onBack = { showReplay = false })
        } else {
            UniversalGameResult(record = selected!!,
                onBack = { selected = null }, onReplay = { showReplay = true })
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
                if (!record.playedWithCharacter) Text("角色独自游玩 · 真实行动记录",
                    color = LuluColors.BlueGray, fontSize = 11.sp)
                Text(record.summary, color = LuluColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    record.createdAt.atZone(ZoneId.systemDefault()).format(ReplayDateFormatter),
                    color = LuluColors.Muted,
                    fontSize = 11.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(record.replayScoreLabel(), fontWeight = FontWeight.Bold)
            }
        }
    }
}


/** Results first. Replays are a deliberate secondary action, never the history default. */
@Composable
private fun UniversalGameResult(record: LuluGameRecord, onBack: () -> Unit, onReplay: () -> Unit) {
    val yachtSheets = remember(record.id, record.detailsJson) { parseYachtScoreSheets(record.detailsJson) }
    val details = remember(record.id, record.detailsJson) {
        runCatching { JSONObject(record.detailsJson) }.getOrElse { JSONObject() }
    }
    val competitive = yachtSheets.size > 1 || details.optString("mode") == "competitive"
    val winner = details.optString("winner")
    val accent = Color(0xFF9BE6D8)
    val ink = Color(0xFF102333)
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(.88f).background(Color(0xFF101B27))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.ArrowBack, "返回游戏记录", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(record.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(if (competitive) "竞技结算 · 已分胜负" else "本局结算 · 真实成绩",
                    color = Color(0xFFB0C0C8), fontSize = 12.sp)
            }
            Text(record.createdAt.atZone(ZoneId.systemDefault()).format(ReplayDateFormatter),
                color = Color(0xFF9BB0BC), fontSize = 10.sp)
        }
        Surface(
            color = Color(0xFF193440), shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = .26f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 23.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (competitive) (if (winner == "平局") "◇ 平局" else "✦ 胜者 · ${winner.ifBlank { "查看排名" }}")
                    else "✦ 本局得分", color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${record.score}", color = Color.White,
                    fontWeight = FontWeight.Black, fontSize = 44.sp)
                Text(if (competitive) "你的得分 · 分数表在下方" else "最终成绩",
                    color = Color(0xFFB5C9D3), fontSize = 12.sp)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            if (record.type == LuluGameType.YachtDice && yachtSheets.isNotEmpty()) {
                item {
                    Text(if (competitive) "双方完整计分表" else "13 项最终计分",
                        color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        val firstWidth = 86.dp
                        val scoreWidth = 86.dp
                        Row(
                            Modifier.background(Color(0xFF294050), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .padding(vertical = 12.dp),
                        ) {
                            Text("项目", Modifier.width(firstWidth).padding(start = 12.dp),
                                color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            yachtSheets.forEach { sheet ->
                                Text(sheet.name, Modifier.width(scoreWidth),
                                    color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        YachtCategory.entries.forEachIndexed { index, category ->
                            Row(
                                Modifier.background(if (index % 2 == 0) Color(0xFF1A2A38) else Color(0xFF223342))
                                    .padding(vertical = 11.dp),
                            ) {
                                Text(category.label, Modifier.width(firstWidth).padding(start = 12.dp),
                                    color = Color(0xFFD5E1EA), fontSize = 12.sp)
                                yachtSheets.forEach { sheet ->
                                    Text(sheet.categories[category]?.toString() ?: "—",
                                        Modifier.width(scoreWidth),
                                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                        Row(Modifier.background(Color(0xFF24473E), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                            .padding(vertical = 13.dp)) {
                            Text("合计", Modifier.width(firstWidth).padding(start = 12.dp),
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            yachtSheets.forEach { sheet ->
                                Text("${sheet.total}", Modifier.width(scoreWidth),
                                    color = accent, fontWeight = FontWeight.Black, fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(record.summary, color = Color(0xFFD5E1EA),
                        fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
        OutlinedButton(
            onClick = onReplay,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        ) {
            Icon(Icons.Outlined.PlayCircleOutline, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("想看过程？回放本局")
        }
        Spacer(Modifier.height(12.dp))
    }
}

internal data class YachtRecordedScore(
    val playerId: String,
    val name: String,
    val total: Int,
    val categories: Map<YachtCategory, Int>,
)

/** New multiplayer sheets plus historical solo step logs, without inventing missing scores. */
internal fun parseYachtScoreSheets(raw: String): List<YachtRecordedScore> = runCatching {
    val details = JSONObject(raw)
    if (details.optString("game") != "yacht_dice") return@runCatching emptyList()
    val sheets = details.optJSONObject("scoreSheets")
    if (sheets != null) {
        val results = mutableListOf<YachtRecordedScore>()
        val ids = sheets.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val item = sheets.optJSONObject(id) ?: continue
            val categoriesJson = item.optJSONObject("categories") ?: JSONObject()
            val categories = YachtCategory.entries.filter { categoriesJson.has(it.name) &&
                !categoriesJson.isNull(it.name) }.associateWith { categoriesJson.getInt(it.name) }
            results += YachtRecordedScore(id, item.optString("name", id), item.optInt("total"), categories)
        }
        return@runCatching results.sortedBy { if (it.playerId == "user") 0 else 1 }
    }
    val categories = buildMap<YachtCategory, Int> {
        val sheet = details.optJSONObject("scoreSheet")
        if (sheet != null) {
            YachtCategory.entries.forEach { if (sheet.has(it.name)) put(it, sheet.getInt(it.name)) }
        } else {
            val turns = details.optJSONArray("turns")
            if (turns != null) for (index in 0 until turns.length()) {
                val turn = turns.optJSONObject(index) ?: continue
                val category = runCatching { YachtCategory.valueOf(turn.optString("category")) }.getOrNull()
                if (category != null) put(category, turn.optInt("points"))
            }
        }
    }
    listOf(YachtRecordedScore("solo", "本局", details.optInt("total", categories.values.sum()), categories))
}.getOrDefault(emptyList())

@Composable
internal fun UniversalGameReplay(record: LuluGameRecord, onBack: () -> Unit) {
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
            Text(record.replayScoreLabel(), fontWeight = FontWeight.Bold)
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

private fun LuluGameRecord.replayScoreLabel(): String = when {
    type == LuluGameType.BlockIsland && !playedWithCharacter -> "${score}回合"
    type == LuluGameType.RelicEscape -> "带回价值${score}"
    else -> "${score}分"
}

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
    if (type == LuluGameType.BlockIsland || type == LuluGameType.RelicEscape) {
        val gameData = runCatching { JSONObject(detailsJson) }.getOrNull()
        val events = gameData?.optJSONArray("events")
        val readable = if (events == null) emptyList() else
            (0 until events.length()).mapNotNull { index ->
                when (val event = events.opt(index)) {
                    is JSONObject -> event.optString("text").takeIf(String::isNotBlank)?.let { text ->
                        "第${event.optInt("turn")}回合 · ${event.optString("action")}：$text"
                    }
                    is String -> event.takeIf(String::isNotBlank)
                    else -> null
                }
            }
        val terminal = gameData?.optString("phase") in setOf("SUCCESS", "FAILURE")
        val caption = (readable + summary).mapIndexed { index, description ->
            ReplayStep("$id-$index", index + 1,
                if (index == readable.size) if (terminal) "真实结算" else "本次进度"
                else "实况 ${index + 1}", description)
        }
        return caption.ifEmpty { listOf(ReplayStep("$id-summary",1,"本次冒险",summary)) }
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
    LuluGameType.BlockIsland -> Icons.Outlined.Forest
    LuluGameType.RelicEscape -> Icons.Outlined.Explore
}

private val ReplayDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
