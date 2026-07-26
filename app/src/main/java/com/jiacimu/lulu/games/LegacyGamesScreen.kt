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

private val LegacyPaper = Color(0xFFFFFDF7)
private val LegacyCard = Color(0xFFFFFBF1)
private val LegacyWheat = Color(0xFFF4D57D)
private val LegacyBorder = Color(0xFFEAE0CC)
private val LegacyMuted = Color(0xFF6D7888)

enum class LegacyGamePage {
    PerfectMan, Roleplay, TurtleSoup, RapportQuiz, RockPaperScissors, YachtDice, Gomoku,
}

@Composable
fun LegacyGameScreen(page: LegacyGamePage, onBack: () -> Unit) {
    val store = remember { LuluGames.store }
    Scaffold(
        containerColor = LegacyPaper,
        topBar = { LegacyTopBar(page.title(), onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                LegacyGamePage.PerfectMan -> PerfectManGame(store)
                LegacyGamePage.Roleplay -> RoleplayGame(store)
                LegacyGamePage.TurtleSoup -> TurtleSoupGame(store)
                LegacyGamePage.RapportQuiz -> RapportQuizGame(store)
                LegacyGamePage.RockPaperScissors -> RockPaperScissorsGame(store)
                LegacyGamePage.YachtDice -> YachtDiceGame(store)
                LegacyGamePage.Gomoku -> GomokuGame(store)
            }
        }
    }
}

private fun LegacyGamePage.title() = when (this) {
    LegacyGamePage.PerfectMan -> "满分男"
    LegacyGamePage.Roleplay -> "轻量跑团"
    LegacyGamePage.TurtleSoup -> "海龟汤"
    LegacyGamePage.RapportQuiz -> "默契问答"
    LegacyGamePage.RockPaperScissors -> "一起猜拳"
    LegacyGamePage.YachtDice -> "快艇骰子"
    LegacyGamePage.Gomoku -> "五子棋"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = LegacyPaper),
    )
}

@Composable
private fun PerfectManGame(store: LuluGameStore) {
    var prompt by remember { mutableStateOf("会认真听你说话，也尊重你的选择") }
    var userScore by remember { mutableIntStateOf(80) }
    var characterScore by remember { mutableStateOf<Int?>(null) }
    var rounds by remember { mutableIntStateOf(0) }
    LegacyList {
        item { LegacyCardBox { Text("轮流描述和猜分", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("角色会按照自己的人设参与评分。", color = LegacyMuted) } }
        item { OutlinedTextField(prompt, { prompt = it }, label = { Text("描述这个人") }, modifier = Modifier.fillMaxWidth(), minLines = 3) }
        item { Text("你给出的分数：$userScore"); Slider(userScore.toFloat(), { userScore = it.toInt() }, valueRange = 0f..100f) }
        item {
            Button(
                onClick = {
                    val score = (userScore + Random.nextInt(-18, 19)).coerceIn(0, 100)
                    characterScore = score
                    rounds += 1
                    store.recordExternalGame(LuluGameType.PerfectMan, "满分男", 100 - abs(score - userScore), 8, "你给 $userScore 分，露露给 $score 分：$prompt")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("让露露评分") }
        }
        characterScore?.let { score -> item { LegacyCardBox { Text("露露给出 $score 分", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("本局默契差值：${abs(score - userScore)}", color = LegacyMuted); Text("已完成 $rounds 轮", color = LegacyMuted) } } }
    }
}

@Composable
private fun RoleplayGame(store: LuluGameStore) {
    var scene by remember { mutableIntStateOf(0) }
    var log by remember { mutableStateOf(listOf("你和露露走进一座所有钟表都在倒走的旅馆。")) }
    val scenes = listOf("调查前台", "敲开 307 房门", "进入地下钟室", "触碰倒走的主钟")
    LegacyList {
        item { LegacyCardBox { Text("倒走的钟", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("自由行动配合 d20 判定，露露会作为同伴加入。", color = LegacyMuted) } }
        items(log) { Text(it) }
        if (scene < scenes.size) {
            item {
                Button(onClick = {
                    val roll = Random.nextInt(1, 21)
                    val result = if (roll >= 11) "成功" else "出现了麻烦"
                    log = log + "你选择：${scenes[scene]}。d20=$roll，$result。"
                    scene += 1
                    if (scene == scenes.size) store.recordExternalGame(LuluGameType.RoleplayAdventure, "轻量跑团", roll * 5, 18, "完成《倒走的钟》，共 ${log.size} 段行动")
                }, modifier = Modifier.fillMaxWidth()) { Text(scenes[scene]) }
            }
        } else item { Button(onClick = { scene = 0; log = listOf("你和露露再次来到倒走的旅馆。") }, modifier = Modifier.fillMaxWidth()) { Text("重新开团") } }
    }
}

@Composable
private fun TurtleSoupGame(store: LuluGameStore) {
    val truth = "男子每天给空房间打电话，因为那是去世恋人的旧号码；某天电话被接起，他才发现号码重新分配。"
    var question by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var revealed by remember { mutableStateOf(false) }
    LegacyList {
        item { LegacyCardBox { Text("汤面", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("一个男人每天都给空房间打电话。某天电话突然被接通，他却哭了。为什么？") } }
        items(history) { Text(it) }
        if (!revealed) {
            item { OutlinedTextField(question, { question = it }, label = { Text("向主持人提问") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("是", "否", "无关").forEach { answer ->
                        OutlinedButton(onClick = { if (question.isNotBlank()) { history = history + "你：$question\n露露：$answer"; question = "" } }, modifier = Modifier.weight(1f)) { Text(answer) }
                    }
                }
            }
            item { TextButton(onClick = { revealed = true; store.recordExternalGame(LuluGameType.TurtleSoup, "海龟汤", (100 - history.size * 5).coerceAtLeast(30), 10, "用 ${history.size} 个问题查看汤底") }) { Text("揭晓汤底") } }
        } else item { LegacyCardBox { Text("汤底", fontWeight = FontWeight.Bold); Text(truth); Button(onClick = { history = emptyList(); revealed = false }, modifier = Modifier.fillMaxWidth()) { Text("再来一次") } } }
    }
}

@Composable
private fun RapportQuizGame(store: LuluGameStore) {
    val questions = listOf("主人压力大时更希望被安慰还是先安静？", "主人完成学习后更喜欢哪种奖励？", "主人更在意计划完整还是马上开始？")
    val options = listOf(listOf("安慰", "安静"), listOf("夸奖", "礼物"), listOf("完整", "开始"))
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var answered by remember { mutableStateOf(false) }
    LegacyList {
        item { LegacyCardBox { Text("秘密作答", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("露露的答案会结合角色记忆；当前先用独立本地答案接口。", color = LegacyMuted) } }
        if (index < questions.size) {
            item { Text(questions[index], fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            items(options[index]) { option -> OutlinedButton(onClick = { if (!answered) { val matched = option == options[index].first(); if (matched) score += 1; answered = true } }, enabled = !answered, modifier = Modifier.fillMaxWidth()) { Text(option) } }
            if (answered) item { Button(onClick = { index += 1; answered = false; if (index + 1 == questions.size) store.recordExternalGame(LuluGameType.RapportQuiz, "默契问答", score * 33, 6 + score * 3, "三题默契得分 $score") }, modifier = Modifier.fillMaxWidth()) { Text("下一题") } }
        } else item { LegacyCardBox { Text("默契得分：$score / ${questions.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold); Button(onClick = { index = 0; score = 0; answered = false }, modifier = Modifier.fillMaxWidth()) { Text("重新作答") } } }
    }
}

@Composable
private fun RockPaperScissorsGame(store: LuluGameStore) {
    val choices = listOf("石头", "剪刀", "布")
    var result by remember { mutableStateOf("还没出拳") }
    LegacyList {
        item { LegacyCardBox { Text("一起猜拳", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(result, color = LegacyMuted) } }
        items(choices) { choice ->
            Button(onClick = {
                val other = choices.random()
                val win = (choice == "石头" && other == "剪刀") || (choice == "剪刀" && other == "布") || (choice == "布" && other == "石头")
                val draw = choice == other
                result = "你出$choice，露露出$other：${if (draw) "平局" else if (win) "你赢啦" else "露露赢了"}"
                store.recordExternalGame(LuluGameType.RockPaperScissors, "一起猜拳", if (win) 100 else if (draw) 60 else 30, if (win) 6 else 2, result)
            }, modifier = Modifier.fillMaxWidth()) { Text(choice) }
        }
    }
}

@Composable
private fun YachtDiceGame(store: LuluGameStore) {
    var dice by remember { mutableStateOf(List(5) { Random.nextInt(1, 7) }) }
    var held by remember { mutableStateOf(emptySet<Int>()) }
    var rolls by remember { mutableIntStateOf(1) }
    var scored by remember { mutableStateOf(false) }
    LegacyList {
        item { LegacyCardBox { Text("五颗骰子，最多三次", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("点击骰子保留，剩余骰子继续重掷。", color = LegacyMuted) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dice.forEachIndexed { index, value ->
                    Surface(modifier = Modifier.weight(1f).aspectRatio(1f).clickable(enabled = !scored) { held = if (index in held) held - index else held + index }, shape = RoundedCornerShape(14.dp), color = if (index in held) LegacyWheat else LegacyCard, border = BorderStroke(1.dp, LegacyBorder)) { Box(contentAlignment = Alignment.Center) { Text(value.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
                }
            }
        }
        item { Text("第 $rolls 次掷骰 · 已保留 ${held.size} 颗") }
        if (!scored) {
            item { Button(onClick = { if (rolls < 3) { dice = dice.mapIndexed { i, v -> if (i in held) v else Random.nextInt(1, 7) }; rolls += 1 } }, enabled = rolls < 3, modifier = Modifier.fillMaxWidth()) { Text("重掷未保留骰子") } }
            item { OutlinedButton(onClick = { val score = dice.sum(); scored = true; store.recordExternalGame(LuluGameType.YachtDice, "快艇骰子", score * 5, score / 2, "骰面 ${dice.joinToString()}，本轮 $score 点") }, modifier = Modifier.fillMaxWidth()) { Text("计分并结束") } }
        } else item { Button(onClick = { dice = List(5) { Random.nextInt(1, 7) }; held = emptySet(); rolls = 1; scored = false }, modifier = Modifier.fillMaxWidth()) { Text("新一局") } }
    }
}

@Composable
private fun GomokuGame(store: LuluGameStore) {
    var board by remember { mutableStateOf(List(15) { MutableList(15) { 0 } }) }
    var status by remember { mutableStateOf("你执黑先手") }
    var finished by remember { mutableStateOf(false) }

    fun hasFive(player: Int): Boolean {
        val dirs = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        for (r in 0..14) for (c in 0..14) if (board[r][c] == player) for ((dr, dc) in dirs) {
            var count = 0
            for (k in 0..4) { val rr = r + dr * k; val cc = c + dc * k; if (rr in 0..14 && cc in 0..14 && board[rr][cc] == player) count++ }
            if (count == 5) return true
        }
        return false
    }

    fun place(row: Int, col: Int) {
        if (finished || board[row][col] != 0) return
        val next = board.map { it.toMutableList() }
        next[row][col] = 1
        board = next
        if (hasFive(1)) { status = "你赢啦"; finished = true; store.recordExternalGame(LuluGameType.Gomoku, "五子棋", 100, 20, "你执黑获胜") ; return }
        val empty = buildList { for (r in 0..14) for (c in 0..14) if (board[r][c] == 0) add(r to c) }
        if (empty.isNotEmpty()) { val (rr, cc) = empty.minByOrNull { abs(it.first - row) + abs(it.second - col) } ?: empty.random(); val ai = board.map { it.toMutableList() }; ai[rr][cc] = 2; board = ai }
        if (hasFive(2)) { status = "露露获胜"; finished = true; store.recordExternalGame(LuluGameType.Gomoku, "五子棋", 35, 5, "露露执白获胜") } else status = "轮到你了"
    }

    LegacyList {
        item { LegacyCardBox { Text("15×15 五子棋", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(status, color = LegacyMuted) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                for (r in 0..14) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (c in 0..14) Box(Modifier.weight(1f).aspectRatio(1f).background(Color(0xFFF2D99A)).clickable { place(r, c) }, contentAlignment = Alignment.Center) {
                        if (board[r][c] != 0) Box(Modifier.fillMaxSize(0.72f).background(if (board[r][c] == 1) Color(0xFF333333) else Color.White, CircleShape))
                    }
                }
            }
        }
        item { Button(onClick = { board = List(15) { MutableList(15) { 0 } }; status = "你执黑先手"; finished = false }, modifier = Modifier.fillMaxWidth()) { Text("重新开局") } }
    }
}

private fun LegacyList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) = Unit

@Composable
private fun LegacyList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun LegacyCardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = LegacyCard), border = BorderStroke(1.dp, LegacyBorder), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
