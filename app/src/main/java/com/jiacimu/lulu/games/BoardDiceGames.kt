package com.jiacimu.lulu.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

private enum class YachtCategory(val label: String) {
    Ones("一点"), Twos("二点"), Threes("三点"), Fours("四点"), Fives("五点"), Sixes("六点"),
    ThreeKind("三条"), FourKind("四条"), FullHouse("葫芦"), SmallStraight("小顺"),
    LargeStraight("大顺"), Yacht("快艇"), Chance("机会"),
}

@Composable
internal fun YachtDiceScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    var userScores by remember { mutableStateOf(emptyMap<YachtCategory, Int>()) }
    var roleScores by remember { mutableStateOf(emptyMap<YachtCategory, Int>()) }
    var dice by remember { mutableStateOf(rollFive()) }
    var held by remember { mutableStateOf(emptySet<Int>()) }
    var rolls by remember { mutableIntStateOf(1) }
    var lastResult by remember { mutableStateOf("") }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }
    val unused = YachtCategory.entries.filterNot { it in userScores }
    val gameOver = unused.isEmpty()

    fun reroll() {
        if (rolls >= 3 || gameOver) return
        dice = dice.mapIndexed { index, value -> if (index in held) value else Random.nextInt(1, 7) }
        rolls += 1
    }

    fun score(category: YachtCategory) {
        if (category in userScores || gameOver) return
        val userScore = scoreYacht(category, dice)
        val roleTurn = playRoleYachtTurn(YachtCategory.entries.filterNot { it in roleScores })
        val roleCategory = roleTurn.category
        val roleScore = roleTurn.score
        val nextUser = userScores + (category to userScore)
        val nextRole = roleScores + (roleCategory to roleScore)
        userScores = nextUser
        roleScores = nextRole
        val outcome = when {
            userScore > roleScore -> "本轮用户胜"
            userScore < roleScore -> "本轮角色胜"
            else -> "本轮平局"
        }
        lastResult = "你将${category.label}记为${userScore}分；${character.displayName}将${roleCategory.label}记为${roleScore}分。$outcome。"
        val recordId = store.recordExternalGame(
            LuluGameType.YachtDice,
            "快艇骰子 · 第${nextUser.size}轮",
            userScore * 3,
            if (userScore >= roleScore) 6 else 3,
            "$lastResult 当前总分：你${nextUser.values.sum()}，${character.displayName}${nextRole.values.sum()}。",
            JSONObject()
                .put("round", nextUser.size)
                .put("user_category", category.name)
                .put("user_score", userScore)
                .put("user_dice", JSONArray(dice))
                .put("role_category", roleCategory.name)
                .put("role_score", roleScore)
                .put("role_dice", JSONArray(roleTurn.dice))
                .put("user_total", nextUser.values.sum())
                .put("role_total", nextRole.values.sum())
                .toString(),
        )
        saveGameAsSharedMemory(scope, store, recordId)
        requestGameRoleResponse(
            scope, store, recordId, lastResult,
            "根据真实骰面、计分类别和胜负，以角色自己的语气回应1-3句；不得修改分数。",
            "快艇骰子第${nextUser.size}轮", { roleResponse = it }, maxTokens = 240,
        )
        dice = rollFive()
        held = emptySet()
        rolls = 1

        if (nextUser.size == YachtCategory.entries.size) {
            val userTotal = nextUser.values.sum()
            val roleTotal = nextRole.values.sum()
            val final = "整局结束：用户${userTotal}分，角色${roleTotal}分，${when {
                userTotal > roleTotal -> "用户获胜"
                userTotal < roleTotal -> "角色获胜"
                else -> "平局"
            }}。"
            val finalRecord = store.recordExternalGame(
                LuluGameType.YachtDice,
                "快艇骰子 · 完整计分表",
                userTotal,
                if (userTotal >= roleTotal) 20 else 10,
                final,
                JSONObject().put("user_total", userTotal).put("role_total", roleTotal).put("completed", true).toString(),
            )
            saveGameAsSharedMemory(scope, store, finalRecord)
        }
    }

    fun reset() {
        userScores = emptyMap()
        roleScores = emptyMap()
        dice = rollFive()
        held = emptySet()
        rolls = 1
        lastResult = ""
        roleResponse = GameRoleResponse()
    }

    GamePageList {
        item {
            GameCard {
                Text("快艇骰子", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("五颗骰子、每轮最多三掷、完整13类计分表。角色也独立掷骰并选择计分项。", color = GameDesign.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("你的总分：${userScores.values.sum()}", fontWeight = FontWeight.Bold)
                    Text("${character.displayName}：${roleScores.values.sum()}", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { GameRolePanel(character.displayName, roleResponse) }
        item {
            GameCard {
                Text("第${userScores.size + 1}轮 · 第${rolls}次掷骰", color = GameDesign.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dice.forEachIndexed { index, value ->
                        Surface(
                            onClick = { held = if (index in held) held - index else held + index },
                            enabled = !gameOver,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = if (index in held) GameDesign.wheat else GameDesign.card,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GameDesign.border),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text(value.toString(), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Button(onClick = ::reroll, enabled = rolls < 3 && !gameOver, modifier = Modifier.fillMaxWidth()) { Text("重掷未保留骰子") }
                if (lastResult.isNotBlank()) GameResultBanner(lastResult, success = lastResult.contains("用户胜") || lastResult.contains("平局"))
            }
        }
        item { Text("选择本轮计分类别", Modifier.padding(horizontal = 16.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        items(YachtCategory.entries) { category ->
            val used = category in userScores
            OutlinedButton(
                onClick = { score(category) },
                enabled = !used && !gameOver,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(category.label)
                    Text(if (used) "已记 ${userScores[category]}" else "本轮可得 ${scoreYacht(category, dice)}")
                }
            }
        }
        if (gameOver) {
            item {
                GameCard {
                    val userTotal = userScores.values.sum()
                    val roleTotal = roleScores.values.sum()
                    Text("完整计分表已结束", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("你${userTotal}分 · ${character.displayName}${roleTotal}分")
                    Button(onClick = ::reset, modifier = Modifier.fillMaxWidth()) { Text("新一局") }
                }
            }
        }
    }
}

private data class RoleYachtTurn(val dice: List<Int>, val category: YachtCategory, val score: Int)

private fun rollFive(): List<Int> = List(5) { Random.nextInt(1, 7) }

private fun playRoleYachtTurn(unused: List<YachtCategory>): RoleYachtTurn {
    var dice = rollFive()
    repeat(2) {
        val counts = dice.groupingBy { it }.eachCount()
        val keepValue = counts.maxByOrNull { it.value }?.key ?: dice.first()
        dice = dice.map { value -> if (value == keepValue) value else Random.nextInt(1, 7) }
    }
    val category = unused.maxByOrNull { scoreYacht(it, dice) } ?: YachtCategory.Chance
    return RoleYachtTurn(dice, category, scoreYacht(category, dice))
}

private fun scoreYacht(category: YachtCategory, dice: List<Int>): Int {
    val counts = dice.groupingBy { it }.eachCount()
    val distinct = dice.distinct().sorted()
    return when (category) {
        YachtCategory.Ones -> dice.filter { it == 1 }.sum()
        YachtCategory.Twos -> dice.filter { it == 2 }.sum()
        YachtCategory.Threes -> dice.filter { it == 3 }.sum()
        YachtCategory.Fours -> dice.filter { it == 4 }.sum()
        YachtCategory.Fives -> dice.filter { it == 5 }.sum()
        YachtCategory.Sixes -> dice.filter { it == 6 }.sum()
        YachtCategory.ThreeKind -> if (counts.values.any { it >= 3 }) dice.sum() else 0
        YachtCategory.FourKind -> if (counts.values.any { it >= 4 }) dice.sum() else 0
        YachtCategory.FullHouse -> if (counts.values.sorted() == listOf(2, 3)) 25 else 0
        YachtCategory.SmallStraight -> if (
            listOf(1, 2, 3, 4).all { it in distinct } ||
            listOf(2, 3, 4, 5).all { it in distinct } ||
            listOf(3, 4, 5, 6).all { it in distinct }
        ) 30 else 0
        YachtCategory.LargeStraight -> if (distinct == listOf(1, 2, 3, 4, 5) || distinct == listOf(2, 3, 4, 5, 6)) 40 else 0
        YachtCategory.Yacht -> if (counts.values.any { it == 5 }) 50 else 0
        YachtCategory.Chance -> dice.sum()
    }
}

@Composable
internal fun GomokuScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf(List(225) { 0 }) }
    var status by remember { mutableStateOf("你执黑先手") }
    var finished by remember { mutableStateOf(false) }
    var moves by remember { mutableIntStateOf(0) }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }

    fun finish(outcome: String, finalBoard: List<Int>) {
        finished = true
        status = outcome
        val recordId = store.recordExternalGame(
            LuluGameType.Gomoku,
            "五子棋",
            when (outcome) { "用户获胜" -> 100; "平局" -> 60; else -> 35 },
            if (outcome == "用户获胜") 20 else 5,
            "用户执黑、${character.displayName}执白，共走${moves}手，结果：$outcome。",
            JSONObject()
                .put("outcome", outcome)
                .put("moves", moves)
                .put("board", JSONArray(finalBoard))
                .toString(),
        )
        saveGameAsSharedMemory(scope, store, recordId)
        requestGameRoleResponse(
            scope, store, recordId, "五子棋结果：$outcome，共走${moves}手。",
            "根据真实棋局结果，以角色自己的语气回应1-3句，不得修改胜负。",
            "五子棋结算", { roleResponse = it }, maxTokens = 240,
        )
    }

    fun place(row: Int, col: Int) {
        if (finished) return
        val index = row * 15 + col
        if (board[index] != 0) return
        var next = board.toMutableList().also { it[index] = 1 }.toList()
        moves += 1
        if (hasGomokuFive(next, 1)) {
            board = next
            finish("用户获胜", next)
            return
        }
        val open = next.indices.filter { next[it] == 0 }
        if (open.isEmpty()) {
            board = next
            finish("平局", next)
            return
        }
        val roleIndex = chooseGomokuMove(next)
        next = next.toMutableList().also { it[roleIndex] = 2 }.toList()
        moves += 1
        board = next
        if (hasGomokuFive(next, 2)) finish("角色获胜", next) else status = "轮到你了"
    }

    fun reset() {
        board = List(225) { 0 }
        status = "你执黑先手"
        finished = false
        moves = 0
        roleResponse = GameRoleResponse()
    }

    GamePageList {
        item {
            GameCard {
                Text("15×15 五子棋", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("你执黑，${character.displayName}执白；角色会优先取胜、拦截你的四连，再评估进攻位置。", color = GameDesign.muted)
                Text(status, fontWeight = FontWeight.SemiBold)
            }
        }
        item { GameRolePanel(character.displayName, roleResponse) }
        item {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(board, finished) {
                        detectTapGestures { offset ->
                            if (finished) return@detectTapGestures
                            val cell = size.width / 15f
                            val col = (offset.x / cell).toInt().coerceIn(0, 14)
                            val row = (offset.y / cell).toInt().coerceIn(0, 14)
                            place(row, col)
                        }
                    },
            ) {
                drawRect(GameDesign.board)
                val cell = size.width / 15f
                for (index in 0 until 15) {
                    val p = cell * (index + 0.5f)
                    drawLine(Color(0xFF7A6335), Offset(cell / 2, p), Offset(size.width - cell / 2, p), strokeWidth = 1.3f)
                    drawLine(Color(0xFF7A6335), Offset(p, cell / 2), Offset(p, size.height - cell / 2), strokeWidth = 1.3f)
                }
                board.forEachIndexed { index, stone ->
                    if (stone == 0) return@forEachIndexed
                    val row = index / 15
                    val col = index % 15
                    drawCircle(
                        color = if (stone == 1) Color(0xFF292929) else Color(0xFFF8F5ED),
                        radius = cell * 0.38f,
                        center = Offset(cell * (col + 0.5f), cell * (row + 0.5f)),
                    )
                }
            }
        }
        item { Button(onClick = ::reset, modifier = Modifier.fillMaxWidth()) { Text("重新开局") } }
    }
}

private fun chooseGomokuMove(board: List<Int>): Int {
    val open = board.indices.filter { board[it] == 0 }
    open.firstOrNull { candidate -> board.withStone(candidate, 2).let { hasGomokuFive(it, 2) } }?.let { return it }
    open.firstOrNull { candidate -> board.withStone(candidate, 1).let { hasGomokuFive(it, 1) } }?.let { return it }
    val nearby = open.filter { index ->
        val row = index / 15
        val col = index % 15
        board.indices.any { other ->
            board[other] != 0 && abs(other / 15 - row) <= 2 && abs(other % 15 - col) <= 2
        }
    }.ifEmpty { open }
    return nearby.maxByOrNull { index ->
        linePotential(board.withStone(index, 2), index, 2) * 3 +
            linePotential(board.withStone(index, 1), index, 1) * 2 -
            distanceFromCenter(index)
    } ?: open.random()
}

private fun List<Int>.withStone(index: Int, player: Int): List<Int> = toMutableList().also { it[index] = player }

private fun distanceFromCenter(index: Int): Int = abs(index / 15 - 7) + abs(index % 15 - 7)

private fun linePotential(board: List<Int>, index: Int, player: Int): Int {
    val row = index / 15
    val col = index % 15
    return listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1).maxOf { (dr, dc) ->
        1 + countDirection(board, row, col, dr, dc, player) + countDirection(board, row, col, -dr, -dc, player)
    }
}

private fun countDirection(board: List<Int>, row: Int, col: Int, dr: Int, dc: Int, player: Int): Int {
    var count = 0
    var r = row + dr
    var c = col + dc
    while (r in 0..14 && c in 0..14 && board[r * 15 + c] == player) {
        count += 1
        r += dr
        c += dc
    }
    return count
}

private fun hasGomokuFive(board: List<Int>, player: Int): Boolean {
    for (row in 0..14) {
        for (col in 0..14) {
            if (board[row * 15 + col] != player) continue
            for ((dr, dc) in listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)) {
                if ((0..4).all { step ->
                        val r = row + dr * step
                        val c = col + dc * step
                        r in 0..14 && c in 0..14 && board[r * 15 + c] == player
                    }
                ) return true
            }
        }
    }
    return false
}
