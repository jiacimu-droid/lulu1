package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CompletePaper = Color(0xFFFFFDF7)
private val CompleteCard = Color(0xFFFFFBF1)
private val CompleteBorder = Color(0xFFEAE0CC)
private val CompleteMuted = Color(0xFF6D7888)

private sealed interface CompleteGameRoute {
    data object Home : CompleteGameRoute
    data object NewGames : CompleteGameRoute
    data class Legacy(val page: LegacyGamePage) : CompleteGameRoute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteGamesApp(onBack: () -> Unit) {
    var route by remember { mutableStateOf<CompleteGameRoute>(CompleteGameRoute.Home) }
    when (val current = route) {
        CompleteGameRoute.Home -> Scaffold(
            containerColor = CompletePaper,
            topBar = {
                TopAppBar(
                    title = { Text("游戏", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CompletePaper),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { GameHubCard("信号追踪", "原有角色协作、网格探测与回放入口；同时保留新增小游戏。") { route = CompleteGameRoute.NewGames } }
                item { GameHubCard("满分男", "轮流描述和猜分，角色按人设参与。") { route = CompleteGameRoute.Legacy(LegacyGamePage.PerfectMan) } }
                item { GameHubCard("轻量跑团", "自由行动、d20 判定，与角色共同探索倒走的钟。") { route = CompleteGameRoute.Legacy(LegacyGamePage.Roleplay) } }
                item { GameHubCard("海龟汤", "角色主持汤底，自由提问并逐步还原真相。") { route = CompleteGameRoute.Legacy(LegacyGamePage.TurtleSoup) } }
                item { GameHubCard("默契问答", "分别作答，使用角色记忆检验彼此了解。") { route = CompleteGameRoute.Legacy(LegacyGamePage.RapportQuiz) } }
                item { GameHubCard("一起猜拳", "生成真实结果，并记录角色回应。") { route = CompleteGameRoute.Legacy(LegacyGamePage.RockPaperScissors) } }
                item { GameHubCard("快艇骰子", "五颗骰子、三次机会、保留骰子并结算。") { route = CompleteGameRoute.Legacy(LegacyGamePage.YachtDice) } }
                item { GameHubCard("五子棋", "15×15 棋盘，你执黑先手，角色执白。") { route = CompleteGameRoute.Legacy(LegacyGamePage.Gomoku) } }
                item { GameHubCard("新增游戏合集", "记忆配对、心情猜猜看和统一游戏回放。") { route = CompleteGameRoute.NewGames } }
            }
        }
        CompleteGameRoute.NewGames -> LuluGamesApp { route = CompleteGameRoute.Home }
        is CompleteGameRoute.Legacy -> LegacyGameScreen(current.page) { route = CompleteGameRoute.Home }
    }
}

@Composable
private fun GameHubCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CompleteCard),
        border = BorderStroke(1.dp, CompleteBorder),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = CompleteMuted, fontSize = 13.sp)
            }
            Text("›", fontSize = 26.sp, color = CompleteMuted)
        }
    }
}
