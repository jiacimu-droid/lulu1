package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface GameRoute {
    data object Home : GameRoute
    data object SignalHunt : GameRoute
    data object PerfectMan : GameRoute
    data object Roleplay : GameRoute
    data object TurtleSoup : GameRoute
    data object RapportQuiz : GameRoute
    data object RockPaperScissors : GameRoute
    data object YachtDice : GameRoute
    data object Gomoku : GameRoute
    data object MemoryMatch : GameRoute
    data object MoodGuess : GameRoute
    data object Records : GameRoute
    data class Replay(val recordId: String) : GameRoute
}

private data class GameLauncher(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: GameRoute,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluGamesApp(onBack: () -> Unit) {
    val store = remember { LuluGames.store }
    val state by store.state.collectAsState()
    var route by remember { mutableStateOf<GameRoute>(GameRoute.Home) }

    Scaffold(
        containerColor = GameDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text(route.title(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (route == GameRoute.Home) onBack() else route = GameRoute.Home
                    }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GameDesign.paper),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = route) {
                GameRoute.Home -> GameHome(state, store, onOpen = { route = it })
                GameRoute.SignalHunt -> SignalHuntScreen(store)
                GameRoute.PerfectMan -> PerfectManScreen(store)
                GameRoute.Roleplay -> RoleplayAdventureScreen(store)
                GameRoute.TurtleSoup -> TurtleSoupScreen(store)
                GameRoute.RapportQuiz -> RapportQuizScreen(store)
                GameRoute.RockPaperScissors -> RockPaperScissorsScreen(store)
                GameRoute.YachtDice -> YachtDiceScreen(store)
                GameRoute.Gomoku -> GomokuScreen(store)
                GameRoute.MemoryMatch -> MemoryMatchScreen(store)
                GameRoute.MoodGuess -> MoodGuessScreen(store)
                GameRoute.Records -> GameRecordsScreen(state, store, onReplay = { route = GameRoute.Replay(it) })
                is GameRoute.Replay -> GameReplayScreen(
                    record = state.records.firstOrNull { it.id == current.recordId },
                    onDeleteAll = store::clearRecords,
                )
            }
        }
    }
}

private fun GameRoute.title(): String = when (this) {
    GameRoute.Home -> "游戏"
    GameRoute.SignalHunt -> "信号追踪"
    GameRoute.PerfectMan -> "满分男"
    GameRoute.Roleplay -> "轻量跑团"
    GameRoute.TurtleSoup -> "海龟汤"
    GameRoute.RapportQuiz -> "默契问答"
    GameRoute.RockPaperScissors -> "一起猜拳"
    GameRoute.YachtDice -> "快艇骰子"
    GameRoute.Gomoku -> "五子棋"
    GameRoute.MemoryMatch -> "记忆配对"
    GameRoute.MoodGuess -> "心情猜猜看"
    GameRoute.Records -> "游戏记录"
    is GameRoute.Replay -> "游戏回放"
}

@Composable
private fun GameHome(
    state: LuluGameState,
    store: LuluGameStore,
    onOpen: (GameRoute) -> Unit,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val modelConnection by LuluAiServices.connectionStore.state.collectAsState()
    val selectedCharacter = characters[state.selectedCharacterId]
        ?: characters.values.firstOrNull()
        ?: MigratedDomainStores.characters.get("lulu")
    val legacyGames = listOf(
        GameLauncher("信号追踪", "3×3 网格、三枚信号、五次探测与逐步路线回放。", Icons.Outlined.Radar, GameRoute.SignalHunt),
        GameLauncher("满分男", "双方轮流描述和猜分，角色按人设、记忆与世界书参与。", Icons.Outlined.PersonSearch, GameRoute.PerfectMan),
        GameLauncher("轻量跑团", "自由文本行动、真实 d20 判定和角色共同主持。", Icons.Outlined.AutoStories, GameRoute.Roleplay),
        GameLauncher("海龟汤", "固定汤底、自由提问，角色严格回答是／否／无关。", Icons.Outlined.HelpOutline, GameRoute.TurtleSoup),
        GameLauncher("默契问答", "角色先根据记忆秘密作答，再比较彼此答案。", Icons.Outlined.QuestionAnswer, GameRoute.RapportQuiz),
        GameLauncher("一起猜拳", "引擎锁定出拳和胜负，角色只对真实结果回应。", Icons.Outlined.BackHand, GameRoute.RockPaperScissors),
        GameLauncher("快艇骰子", "五骰三掷、保留骰子和完整十三类计分表。", Icons.Outlined.Casino, GameRoute.YachtDice),
        GameLauncher("五子棋", "15×15 棋盘，角色会取胜、拦截并评估进攻。", Icons.Outlined.GridOn, GameRoute.Gomoku),
    )
    val additions = listOf(
        GameLauncher("记忆配对", "翻开卡片并完成四组配对。", Icons.Outlined.GridView, GameRoute.MemoryMatch),
        GameLauncher("心情猜猜看", "根据给定情境判断更接近的情绪。", Icons.Outlined.FavoriteBorder, GameRoute.MoodGuess),
    )

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GameCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(54.dp).background(GameDesign.wheat, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(selectedCharacter.displayName.take(1).ifBlank { "角" }, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("与角色共同游戏", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("玩法结果由引擎锁定；角色按真实人设、记忆和世界书回应。", color = GameDesign.muted)
                    }
                    Switch(checked = state.playWithCharacter, onCheckedChange = store::setPlayWithCharacter)
                }
                if (characters.isNotEmpty()) {
                    Text("选择本局角色", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        characters.values.forEach { character ->
                            FilterChip(
                                selected = state.selectedCharacterId == character.characterId,
                                onClick = { store.selectCharacter(character.characterId) },
                                label = { Text(character.displayName.ifBlank { "未命名角色" }) },
                            )
                        }
                    }
                }
                Surface(
                    color = if (modelConnection.enabled && modelConnection.apiKey.isNotBlank() && modelConnection.model.isNotBlank()) {
                        Color(0xFFE8F1E8)
                    } else {
                        Color(0xFFF7E9E4)
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (modelConnection.enabled && modelConnection.apiKey.isNotBlank() && modelConnection.model.isNotBlank()) {
                            "角色模型已连接：${modelConnection.model}"
                        } else {
                            "角色模型尚未配置。规则游戏仍可玩，但角色生成类游戏会提示前往设置。"
                        },
                        Modifier.fillMaxWidth().padding(12.dp),
                        color = GameDesign.muted,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameMetric("游戏币", state.coins.toString(), Modifier.weight(1f))
                GameMetric("记录", state.records.size.toString(), Modifier.weight(1f))
                GameMetric("最高分", (state.records.maxOfOrNull { it.score } ?: 0).toString(), Modifier.weight(1f))
            }
        }
        item { SectionTitle("原有游戏 · 8 个") }
        items(legacyGames, key = { it.title }) { launcher -> GameEntry(launcher, onOpen) }
        item { SectionTitle("新增游戏 · 不替代原功能") }
        items(additions, key = { it.title }) { launcher -> GameEntry(launcher, onOpen) }
        item {
            GameEntry(
                GameLauncher("游戏记录与回放", "查看得分、规则细节、角色回应和信号追踪逐步路线。", Icons.Outlined.History, GameRoute.Records),
                onOpen,
            )
        }
    }
}

@Composable
private fun SignalHuntScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val game = state.signalHunt
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }
    val moveByCell = game.moves.associateBy { it.cell }

    fun tap(cell: Int) {
        val wasFinished = store.state.value.signalHunt.finished
        store.guessSignal(cell)
        val after = store.state.value
        if (!wasFinished && after.signalHunt.finished) {
            val record = after.records.firstOrNull { it.type == LuluGameType.SignalHunt } ?: return
            saveGameAsSharedMemory(scope, store, record.id)
            requestGameRoleResponse(
                scope = scope,
                store = store,
                recordId = record.id,
                facts = record.summary,
                instruction = "根据真实探测路线和得分，以角色自己的语气回应1-3句；不得修改找到的信号数量。",
                title = "信号追踪结算",
                onState = { roleResponse = it },
                maxTokens = 240,
            )
        }
    }

    GamePageList {
        item {
            GameCard {
                Text("三枚信号，最多探测五格", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("连续找到信号会有额外得分。每一步都会进入可播放回放。", color = GameDesign.muted)
                Text("已找到 ${game.moves.count { it.foundSignal }}/3 · 已探测 ${game.moves.size}/5", fontWeight = FontWeight.SemiBold)
            }
        }
        item { GameRolePanel(character.displayName, roleResponse) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                repeat(3) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        repeat(3) { column ->
                            val cell = row * 3 + column
                            val move = moveByCell[cell]
                            Surface(
                                onClick = { tap(cell) },
                                enabled = game.started && !game.finished && move == null,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                color = when {
                                    move?.foundSignal == true -> Color(0xFFDDEEDF)
                                    move != null -> Color(0xFFE9E9E6)
                                    else -> GameDesign.card
                                },
                                border = BorderStroke(1.dp, GameDesign.border),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        when {
                                            move?.foundSignal == true -> "✦\n信号"
                                            move != null -> "已探测"
                                            game.started -> "?"
                                            else -> "·"
                                        },
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { if (game.started) store.resetSignalHunt() else store.startSignalHunt() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (!game.started) "开始这一局" else if (game.finished) "再来一局" else "重置本局")
            }
        }
        if (game.finished) {
            item {
                val score = game.moves.sumOf { it.points }
                GameCard {
                    Text("本局结束", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("找到 ${game.moves.count { it.foundSignal }}/3 个信号 · 得分 $score")
                    Text("完整路线已经保存到游戏记录。", color = GameDesign.muted)
                }
            }
        }
    }
}

@Composable
private fun MemoryMatchScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val game = state.memoryMatch
    LaunchedEffect(game.opened) {
        if (game.opened.size == 2 && game.opened.any { it !in game.matched }) {
            delay(650)
            store.closeUnmatchedCards()
        }
    }
    GamePageList {
        item { GameCard { Text("找到四组配对", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("步数 ${game.moves} · 已配对 ${game.matched.size / 2}/4", color = GameDesign.muted) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                game.cards.indices.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.forEach { index ->
                            val visible = index in game.opened || index in game.matched
                            Card(
                                modifier = Modifier.weight(1f).aspectRatio(0.8f).clickable(enabled = !visible && !game.finished) { store.openMemoryCard(index) },
                                colors = CardDefaults.cardColors(containerColor = if (visible) GameDesign.wheat else GameDesign.card),
                                border = BorderStroke(1.dp, GameDesign.border),
                                shape = RoundedCornerShape(17.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if (visible) game.cards[index] else "?", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (game.finished) item { GameCard { Text("全部配对完成", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("共用 ${game.moves} 步。", color = GameDesign.muted); Button(onClick = store::resetMemoryMatch, modifier = Modifier.fillMaxWidth()) { Text("重新洗牌") } } }
    }
}

@Composable
private fun MoodGuessScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val round = state.moodRound
    val answered = state.moodAnswered
    GamePageList {
        item { GameCard { Text("判断情境中的情绪", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(round.clue); Text("这是额外小游戏，情境不自动写成角色真实经历。", color = GameDesign.muted, fontSize = 12.sp) } }
        items(round.options) { option ->
            OutlinedButton(onClick = { store.answerMood(option) }, enabled = answered == null, modifier = Modifier.fillMaxWidth()) { Text(option) }
        }
        if (answered != null) {
            item {
                GameCard {
                    val correct = answered == round.answer
                    Text(if (correct) "判断正确" else "更接近：${round.answer}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("你的选择：$answered", color = GameDesign.muted)
                    Button(onClick = store::resetMoodGuess, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
                }
            }
        }
    }
}

@Composable
private fun GameRecordsScreen(
    state: LuluGameState,
    store: LuluGameStore,
    onReplay: (String) -> Unit,
) {
    GamePageList {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${state.records.size} 条记录", fontWeight = FontWeight.Bold)
                TextButton(onClick = store::clearRecords, enabled = state.records.isNotEmpty()) { Text("清空") }
            }
        }
        if (state.records.isEmpty()) {
            item { GameCard { Text("还没有游戏记录", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("完成任意游戏后，这里会保存事实、规则详情和角色回应。", color = GameDesign.muted) } }
        } else {
            items(state.records, key = { it.id }) { record ->
                GameCard(Modifier.clickable { onReplay(record.id) }) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(record.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(record.summary, color = GameDesign.muted, maxLines = 3)
                            val character = MigratedDomainStores.characters.get(record.characterId)
                            Text(if (record.playedWithCharacter) "参与角色：${character.displayName}" else "单人模式", color = GameDesign.muted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${record.score} 分", fontWeight = FontWeight.Bold)
                            Text("+${record.rewardCoins} 币", color = GameDesign.muted)
                        }
                    }
                    Text(record.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), color = GameDesign.muted, fontSize = 12.sp)
                    Text("查看回放 ›", color = GameDesign.muted)
                }
            }
        }
    }
}

@Composable
private fun GameReplayScreen(record: LuluGameRecord?, onDeleteAll: () -> Unit) {
    if (record == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这条记录不存在或已被清除") }
        return
    }
    val signalMoves = remember(record.id, record.detailsJson) { parseSignalMoves(record) }
    var visibleMoves by remember(record.id) { mutableIntStateOf(if (signalMoves.isEmpty()) 0 else signalMoves.size) }
    var playing by remember(record.id) { mutableStateOf(false) }
    LaunchedEffect(playing, record.id) {
        if (!playing || signalMoves.isEmpty()) return@LaunchedEffect
        visibleMoves = 0
        while (visibleMoves < signalMoves.size) {
            delay(650)
            visibleMoves += 1
        }
        playing = false
    }

    GamePageList {
        item {
            GameCard {
                Text(record.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(record.summary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("得分 ${record.score}")
                    Text("奖励 ${record.rewardCoins} 游戏币")
                }
                Text(record.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), color = GameDesign.muted, fontSize = 12.sp)
            }
        }
        if (signalMoves.isNotEmpty()) {
            item {
                GameCard {
                    Text("信号路线回放", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("正在显示 $visibleMoves/${signalMoves.size} 步", color = GameDesign.muted)
                    Button(onClick = { playing = true }, enabled = !playing, modifier = Modifier.fillMaxWidth()) { Text(if (playing) "播放中…" else "从头播放") }
                }
            }
            item { SignalReplayBoard(signalMoves.take(visibleMoves)) }
        }
        item {
            GameCard {
                Text("规则事实", fontWeight = FontWeight.Bold)
                Text(prettyDetails(record.detailsJson), color = GameDesign.muted)
            }
        }
        if (record.playedWithCharacter) {
            item {
                val character = MigratedDomainStores.characters.get(record.characterId)
                GameCard {
                    Text("${character.displayName}当时的回应", fontWeight = FontWeight.Bold)
                    Text(record.characterReply.ifBlank { "该局已保存，但没有成功生成角色回应。" }, color = if (record.characterReply.isBlank()) GameDesign.muted else GameDesign.ink)
                }
            }
        }
        item { TextButton(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth()) { Text("清空全部游戏记录") } }
    }
}

@Composable
private fun SignalReplayBoard(moves: List<SignalHuntMove>) {
    val map = moves.associateBy { it.cell }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { column ->
                    val move = map[row * 3 + column]
                    Surface(
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        color = when {
                            move?.foundSignal == true -> Color(0xFFDDEEDF)
                            move != null -> Color(0xFFE9E9E6)
                            else -> GameDesign.card
                        },
                        border = BorderStroke(1.dp, GameDesign.border),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (move?.foundSignal == true) "✦\n+${move.points}" else if (move != null) "×" else "·", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun parseSignalMoves(record: LuluGameRecord): List<SignalHuntMove> = runCatching {
    if (record.type != LuluGameType.SignalHunt) return@runCatching emptyList()
    val array = JSONObject(record.detailsJson).optJSONArray("moves") ?: return@runCatching emptyList()
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(SignalHuntMove(item.optInt("cell"), item.optBoolean("found_signal"), item.optInt("points")))
        }
    }
}.getOrDefault(emptyList())

private fun prettyDetails(raw: String): String = runCatching {
    JSONObject(raw).toString(2)
}.getOrElse { raw.ifBlank { "没有额外规则详情" } }

@Composable
private fun GameEntry(launcher: GameLauncher, onOpen: (GameRoute) -> Unit) {
    GameCard(Modifier.clickable { onOpen(launcher.route) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = GameDesign.wheatSoft, shape = CircleShape) {
                Icon(launcher.icon, null, tint = GameDesign.muted, modifier = Modifier.padding(12.dp).size(25.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(launcher.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(launcher.subtitle, color = GameDesign.muted, fontSize = 13.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = GameDesign.muted)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun GameMetric(title: String, value: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = GameDesign.card),
        border = BorderStroke(1.dp, GameDesign.border),
        shape = RoundedCornerShape(17.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(title, color = GameDesign.muted, fontSize = 12.sp)
        }
    }
}
