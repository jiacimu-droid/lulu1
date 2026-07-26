package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val GamePaper = Color(0xFFFFFDF7)
private val GameCard = Color(0xFFFFFBF1)
private val GameWheat = Color(0xFFF4D57D)
private val GameMuted = Color(0xFF6D7888)
private val GameBorder = Color(0xFFEAE0CC)

private enum class GamePage { Home, Signal, Memory, Mood, Records }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluGamesApp(onBack: () -> Unit) {
    val store = remember { LuluGames.store }
    val state by store.state.collectAsState()
    var page by remember { mutableStateOf(GamePage.Home) }

    Scaffold(
        containerColor = GamePaper,
        topBar = {
            TopAppBar(
                title = { Text(if (page == GamePage.Home) "游戏" else page.title(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { if (page == GamePage.Home) onBack() else page = GamePage.Home }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GamePaper),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                GamePage.Home -> GamesHome(state, store) { page = it }
                GamePage.Signal -> SignalHuntScreen(state, store)
                GamePage.Memory -> MemoryMatchScreen(state, store)
                GamePage.Mood -> MoodGuessScreen(state, store)
                GamePage.Records -> GameRecordsScreen(state)
            }
        }
    }
}

private fun GamePage.title(): String = when (this) {
    GamePage.Home -> "游戏"
    GamePage.Signal -> "信号追踪"
    GamePage.Memory -> "记忆配对"
    GamePage.Mood -> "心情猜猜看"
    GamePage.Records -> "游戏回放"
}

@Composable
private fun GamesHome(state: LuluGameState, store: LuluGameStore, onOpen: (GamePage) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GameCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(GameWheat, CircleShape), contentAlignment = Alignment.Center) {
                        Text("露", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("和露露一起玩", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("角色会参与结果、陪伴语和回放记录。", color = GameMuted)
                    }
                    Switch(checked = state.playWithCharacter, onCheckedChange = store::setPlayWithCharacter)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("游戏币", state.coins.toString(), Modifier.weight(1f))
                MetricCard("已玩", state.records.size.toString(), Modifier.weight(1f))
                MetricCard("最高分", (state.records.maxOfOrNull { it.score } ?: 0).toString(), Modifier.weight(1f))
            }
        }
        item { GameEntry("信号追踪", "在 3×3 信号阵列中定位隐藏信号。", Icons.Outlined.Radar) { onOpen(GamePage.Signal) } }
        item { GameEntry("记忆配对", "翻开卡片，找到所有与露露有关的成对符号。", Icons.Outlined.GridView) { onOpen(GamePage.Memory) } }
        item { GameEntry("心情猜猜看", "根据情境判断露露此刻更接近哪种心情。", Icons.Outlined.FavoriteBorder) { onOpen(GamePage.Mood) } }
        item { GameEntry("游戏回放", "查看每局得分、奖励、角色参与情况和结果摘要。", Icons.Outlined.History) { onOpen(GamePage.Records) } }
    }
}

@Composable
private fun SignalHuntScreen(state: LuluGameState, store: LuluGameStore) {
    val game = state.signalHunt
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            GameCardBox {
                Text("从九个区域中找到隐藏信号", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("露露会记录每一次尝试。越快找到，得分越高。", color = GameMuted)
                Text("已尝试：${game.attempts.size} 次", color = GameMuted)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..9).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { position ->
                            val attempted = position in game.attempts
                            val found = game.finished && position == game.target
                            Button(
                                onClick = { store.guessSignal(position) },
                                enabled = !game.finished && !attempted,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (found) GameWheat else GameCard),
                                border = BorderStroke(1.dp, GameBorder),
                            ) {
                                Text(if (found) "✓" else if (attempted) "×" else position.toString(), fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
        }
        if (game.finished) {
            item {
                GameCardBox {
                    Text("定位成功", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("第 ${game.attempts.size} 次找到信号 ${game.target}，获得 12 游戏币。", color = GameMuted)
                    Button(onClick = store::resetSignalHunt, modifier = Modifier.fillMaxWidth()) { Text("再玩一次") }
                }
            }
        }
    }
}

@Composable
private fun MemoryMatchScreen(state: LuluGameState, store: LuluGameStore) {
    val game = state.memoryMatch
    LaunchedEffect(game.opened) {
        if (game.opened.size == 2 && game.opened.any { it !in game.matched }) {
            kotlinx.coroutines.delay(650)
            store.closeUnmatchedCards()
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            GameCardBox {
                Text("记住位置，找到四组配对", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("步数：${game.moves} · 已配对：${game.matched.size / 2}/4", color = GameMuted)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                game.cards.indices.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { index ->
                            val visible = index in game.opened || index in game.matched
                            Card(
                                modifier = Modifier.weight(1f).aspectRatio(0.8f).clickable(enabled = !visible && !game.finished) { store.openMemoryCard(index) },
                                colors = CardDefaults.cardColors(containerColor = if (visible) GameWheat else GameCard),
                                border = BorderStroke(1.dp, GameBorder),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(if (visible) game.cards[index] else "?", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (game.finished) {
            item {
                GameCardBox {
                    Text("全部配对完成", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("共用了 ${game.moves} 步，获得 15 游戏币。", color = GameMuted)
                    Button(onClick = store::resetMemoryMatch, modifier = Modifier.fillMaxWidth()) { Text("重新洗牌") }
                }
            }
        }
    }
}

@Composable
private fun MoodGuessScreen(state: LuluGameState, store: LuluGameStore) {
    val round = state.moodRound
    val answered = state.moodAnswered
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            GameCardBox {
                Text("读懂露露此刻的心情", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(round.clue, fontSize = 17.sp)
            }
        }
        items(round.options) { option ->
            OutlinedButton(
                onClick = { store.answerMood(option) },
                enabled = answered == null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(option, fontSize = 16.sp)
            }
        }
        if (answered != null) {
            item {
                GameCardBox {
                    val correct = answered == round.answer
                    Text(if (correct) "猜对啦" else "这次更接近：${round.answer}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (correct) "露露觉得主人很懂她，获得 10 游戏币。" else "没关系，露露把这次心情留进了回放。", color = GameMuted)
                    Button(onClick = store::resetMoodGuess, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
                }
            }
        }
    }
}

@Composable
private fun GameRecordsScreen(state: LuluGameState) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.records.isEmpty()) {
            item {
                GameCardBox {
                    Text("还没有游戏记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("完成任意小游戏后，这里会保存回放摘要。", color = GameMuted)
                }
            }
        } else {
            items(state.records, key = { it.id }) { record ->
                GameCardBox {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(record.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(record.summary, color = GameMuted)
                            Text(if (record.playedWithCharacter) "和露露一起玩" else "单人模式", color = GameMuted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${record.score} 分", fontWeight = FontWeight.Bold)
                            Text("+${record.rewardCoins} 币", color = GameMuted)
                        }
                    }
                    Text(record.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), color = GameMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun GameEntry(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    GameCardBox(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFFFFF4D5), shape = CircleShape) {
                Icon(icon, null, tint = GameMuted, modifier = Modifier.padding(12.dp).size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = GameMuted, fontSize = 13.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = GameMuted)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = GameCard), border = BorderStroke(1.dp, GameBorder), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(title, color = GameMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GameCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GameCard),
        border = BorderStroke(1.dp, GameBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
