package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.random.Random

private val Paper = Color(0xFFFFFDF7)
private val CardColor = Color(0xFFFFFBF1)
private val Wheat = Color(0xFFF4D57D)
private val Border = Color(0xFFEAE0CC)
private val Muted = Color(0xFF6D7888)

enum class LegacyGamePage { PerfectMan, Roleplay, TurtleSoup, RapportQuiz, RockPaperScissors, YachtDice, Gomoku }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyGameScreen(page: LegacyGamePage, onBack: () -> Unit) {
    val store = remember { LuluGames.store }
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text(page.label(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                LegacyGamePage.PerfectMan -> PerfectMan(store)
                LegacyGamePage.Roleplay -> Roleplay(store)
                LegacyGamePage.TurtleSoup -> TurtleSoup(store)
                LegacyGamePage.RapportQuiz -> RapportQuiz(store)
                LegacyGamePage.RockPaperScissors -> Rps(store)
                LegacyGamePage.YachtDice -> Yacht(store)
                LegacyGamePage.Gomoku -> Gomoku(store)
            }
        }
    }
}

private fun LegacyGamePage.label() = when (this) {
    LegacyGamePage.PerfectMan -> "满分男"
    LegacyGamePage.Roleplay -> "轻量跑团"
    LegacyGamePage.TurtleSoup -> "海龟汤"
    LegacyGamePage.RapportQuiz -> "默契问答"
    LegacyGamePage.RockPaperScissors -> "一起猜拳"
    LegacyGamePage.YachtDice -> "快艇骰子"
    LegacyGamePage.Gomoku -> "五子棋"
}

@Composable
private fun PerfectMan(store: LuluGameStore) {
    var description by remember { mutableStateOf("会认真听你说话，也尊重你的选择") }
    var userScore by remember { mutableIntStateOf(80) }
    var result by remember { mutableStateOf("") }
    GameList {
        item { InfoCard("轮流描述和猜分", "角色会按照自己的人设参与评分。") }
        item { OutlinedTextField(description, { description = it }, label = { Text("描述这个人") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
        item { Text("你的分数：$userScore"); Slider(userScore.toFloat(), { userScore = it.toInt() }, valueRange = 0f..100f) }
        item {
            Button(onClick = {
                val roleScore = (userScore + Random.nextInt(-18, 19)).coerceIn(0, 100)
                result = "你给 $userScore 分，露露给 $roleScore 分"
                store.recordExternalGame(LuluGameType.PerfectMan, "满分男", 100 - abs(roleScore - userScore), 8, "$result：$description")
            }, modifier = Modifier.fillMaxWidth()) { Text("让露露评分") }
        }
        if (result.isNotBlank()) item { InfoCard(result, "本局已经写入游戏回放。") }
    }
}

@Composable
private fun Roleplay(store: LuluGameStore) {
    val actions = listOf("调查前台", "敲开 307 房门", "进入地下钟室", "触碰倒走的主钟")
    var step by remember { mutableIntStateOf(0) }
    var logs by remember { mutableStateOf(listOf("你和露露进入一座所有钟表都在倒走的旅馆。")) }
    GameList {
        item { InfoCard("倒走的钟", "自由行动、d20 判定和角色同伴都保留。") }
        items(logs) { Text(it) }
        if (step < actions.size) {
            item {
                Button(onClick = {
                    val roll = Random.nextInt(1, 21)
                    logs = logs + "${actions[step]}：d20=$roll，${if (roll >= 11) "成功" else "出现麻烦"}。"
                    step += 1
                    if (step == actions.size) store.recordExternalGame(LuluGameType.RoleplayAdventure, "轻量跑团", roll * 5, 18, "完成《倒走的钟》")
                }, modifier = Modifier.fillMaxWidth()) { Text(actions[step]) }
            }
        } else {
            item { Button(onClick = { step = 0; logs = listOf("你和露露再次进入倒走的旅馆。") }, modifier = Modifier.fillMaxWidth()) { Text("重新开团") } }
        }
    }
}

@Composable
private fun TurtleSoup(store: LuluGameStore) {
    var question by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var revealed by remember { mutableStateOf(false) }
    GameList {
        item { InfoCard("汤面", "一个男人每天给空房间打电话。某天电话被接通，他却哭了。为什么？") }
        items(history) { Text(it) }
        if (!revealed) {
            item { OutlinedTextField(question, { question = it }, label = { Text("向主持人提问") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("是", "否", "无关").forEach { answer ->
                        OutlinedButton(
                            onClick = { if (question.isNotBlank()) { history = history + "你：$question\n露露：$answer"; question = "" } },
                            modifier = Modifier.weight(1f),
                        ) { Text(answer) }
                    }
                }
            }
            item { TextButton(onClick = { revealed = true; store.recordExternalGame(LuluGameType.TurtleSoup, "海龟汤", (100 - history.size * 5).coerceAtLeast(30), 10, "用 ${history.size} 个问题查看汤底") }) { Text("揭晓汤底") } }
        } else {
            item { InfoCard("汤底", "男子拨打的是去世恋人的旧号码；号码重新分配后突然被接起，他才真正接受对方已经离开。") }
        }
    }
}

@Composable
private fun RapportQuiz(store: LuluGameStore) {
    val questions = listOf("压力大时更希望被安慰还是先安静？", "学习结束后更喜欢夸奖还是礼物？", "做计划时更在意完整还是马上开始？")
    val options = listOf(listOf("安慰", "安静"), listOf("夸奖", "礼物"), listOf("完整", "开始"))
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var answered by remember { mutableStateOf(false) }
    GameList {
        item { InfoCard("秘密作答", "角色记忆答案接口已保留。") }
        if (index < questions.size) {
            item { Text(questions[index], fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            items(options[index]) { option ->
                OutlinedButton(onClick = { if (!answered) { if (option == options[index].first()) score += 1; answered = true } }, enabled = !answered, modifier = Modifier.fillMaxWidth()) { Text(option) }
            }
            if (answered) item {
                Button(onClick = {
                    index += 1
                    answered = false
                    if (index == questions.size) store.recordExternalGame(LuluGameType.RapportQuiz, "默契问答", score * 33, 6 + score * 3, "三题默契得分 $score")
                }, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
            }
        } else {
            item { InfoCard("默契得分：$score / ${questions.size}", "结果已经进入回放。") }
        }
    }
}

@Composable
private fun Rps(store: LuluGameStore) {
    val choices = listOf("石头", "剪刀", "布")
    var result by remember { mutableStateOf("还没出拳") }
    GameList {
        item { InfoCard("一起猜拳", result) }
        items(choices) { choice ->
            Button(onClick = {
                val role = choices.random()
                val win = (choice == "石头" && role == "剪刀") || (choice == "剪刀" && role == "布") || (choice == "布" && role == "石头")
                val draw = choice == role
                result = "你出$choice，露露出$role：${if (draw) "平局" else if (win) "你赢啦" else "露露赢了"}"
                store.recordExternalGame(LuluGameType.RockPaperScissors, "一起猜拳", if (win) 100 else if (draw) 60 else 30, if (win) 6 else 2, result)
            }, modifier = Modifier.fillMaxWidth()) { Text(choice) }
        }
    }
}

@Composable
private fun Yacht(store: LuluGameStore) {
    var dice by remember { mutableStateOf(List(5) { Random.nextInt(1, 7) }) }
    var held by remember { mutableStateOf(emptySet<Int>()) }
    var rolls by remember { mutableIntStateOf(1) }
    var finished by remember { mutableStateOf(false) }
    GameList {
        item { InfoCard("五颗骰子，最多三次", "点击骰子保留，再重掷其余骰子。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dice.forEachIndexed { index, value ->
                    Surface(
                        modifier = Modifier.weight(1f).aspectRatio(1f).clickable(enabled = !finished) { held = if (index in held) held - index else held + index },
                        shape = RoundedCornerShape(14.dp),
                        color = if (index in held) Wheat else CardColor,
                        border = BorderStroke(1.dp, Border),
                    ) { Box(contentAlignment = Alignment.Center) { Text(value.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
                }
            }
        }
        item { Text("第 $rolls 次掷骰 · 已保留 ${held.size} 颗") }
        if (!finished) {
            item { Button(onClick = { if (rolls < 3) { dice = dice.mapIndexed { i, value -> if (i in held) value else Random.nextInt(1, 7) }; rolls += 1 } }, enabled = rolls < 3, modifier = Modifier.fillMaxWidth()) { Text("重掷") } }
            item { OutlinedButton(onClick = { val score = dice.sum(); finished = true; store.recordExternalGame(LuluGameType.YachtDice, "快艇骰子", score * 5, score / 2, "骰面 ${dice.joinToString()}，本轮 $score 点") }, modifier = Modifier.fillMaxWidth()) { Text("计分并结束") } }
        } else {
            item { Button(onClick = { dice = List(5) { Random.nextInt(1, 7) }; held = emptySet(); rolls = 1; finished = false }, modifier = Modifier.fillMaxWidth()) { Text("新一局") } }
        }
    }
}

@Composable
private fun Gomoku(store: LuluGameStore) {
    var board by remember { mutableStateOf(List(15) { List(15) { 0 } }) }
    var status by remember { mutableStateOf("你执黑先手") }
    var finished by remember { mutableStateOf(false) }

    fun hasFive(player: Int, source: List<List<Int>>): Boolean {
        val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        for (row in 0..14) for (col in 0..14) if (source[row][col] == player) {
            for ((dr, dc) in directions) {
                var count = 0
                for (step in 0..4) {
                    val r = row + dr * step
                    val c = col + dc * step
                    if (r in 0..14 && c in 0..14 && source[r][c] == player) count += 1
                }
                if (count == 5) return true
            }
        }
        return false
    }

    fun place(row: Int, col: Int) {
        if (finished || board[row][col] != 0) return
        val userBoard = board.map { it.toMutableList() }
        userBoard[row][col] = 1
        board = userBoard
        if (hasFive(1, userBoard)) {
            status = "你赢啦"
            finished = true
            store.recordExternalGame(LuluGameType.Gomoku, "五子棋", 100, 20, "你执黑获胜")
            return
        }
        val empty = buildList { for (r in 0..14) for (c in 0..14) if (userBoard[r][c] == 0) add(r to c) }
        if (empty.isNotEmpty()) {
            val (aiRow, aiCol) = empty.minByOrNull { abs(it.first - row) + abs(it.second - col) } ?: empty.random()
            val aiBoard = userBoard.map { it.toMutableList() }
            aiBoard[aiRow][aiCol] = 2
            board = aiBoard
            if (hasFive(2, aiBoard)) {
                status = "露露获胜"
                finished = true
                store.recordExternalGame(LuluGameType.Gomoku, "五子棋", 35, 5, "露露执白获胜")
            } else status = "轮到你了"
        }
    }

    GameList {
        item { InfoCard("15×15 五子棋", status) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(15) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(15) { col ->
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f).background(Color(0xFFF2D99A)).clickable { place(row, col) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (board[row][col] != 0) {
                                    Box(Modifier.fillMaxSize(0.72f).background(if (board[row][col] == 1) Color(0xFF333333) else Color.White, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Button(onClick = { board = List(15) { List(15) { 0 } }; status = "你执黑先手"; finished = false }, modifier = Modifier.fillMaxWidth()) { Text("重新开局") } }
    }
}

@Composable
private fun GameList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun InfoCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardColor), border = BorderStroke(1.dp, Border), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted)
        }
    }
}
