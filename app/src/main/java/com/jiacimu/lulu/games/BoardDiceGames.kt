package com.jiacimu.lulu.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

private enum class YachtCategory(val label: String) {
    Ones("一点"), Twos("二点"), Threes("三点"), Fours("四点"), Fives("五点"), Sixes("六点"),
    ThreeKind("三条"), FourKind("四条"), FullHouse("葫芦"), SmallStraight("小顺"),
    LargeStraight("大顺"), Yacht("快艇"), Chance("机会"),
}

private data class YachtPlayer(
    val id: String,
    val name: String,
    val characterId: String? = null,
)

@Composable
internal fun YachtDiceScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val opponentIds = remember(state.selectedCharacterIds, state.selectedCharacterId) {
        state.selectedCharacterIds.take(3).ifEmpty { listOf(state.selectedCharacterId) }
    }
    val players = remember(opponentIds, characters) {
        listOf(YachtPlayer("user", "你")) + opponentIds.distinct().take(3).map { id ->
            val character = characters[id] ?: MigratedDomainStores.characters.get(id)
            YachtPlayer("role:$id", character.displayName, id)
        }
    }
    var scores by remember { mutableStateOf<Map<String, Map<YachtCategory, Int>>>(emptyMap()) }
    var currentPlayerIndex by remember { mutableIntStateOf(0) }
    var dice by remember { mutableStateOf(emptyList<Int>()) }
    var held by remember { mutableStateOf(emptySet<Int>()) }
    var rolls by remember { mutableIntStateOf(0) }
    var rolling by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf("") }
    var turnLog by remember { mutableStateOf(emptyList<String>()) }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }
    var responseSpeaker by remember { mutableStateOf("") }
    val currentPlayer = players[currentPlayerIndex.coerceIn(players.indices)]
    val currentScores = scores[currentPlayer.id].orEmpty()
    val gameOver = players.all { scores[it.id].orEmpty().size == YachtCategory.entries.size }

    fun reset() {
        scores = emptyMap()
        currentPlayerIndex = 0
        dice = emptyList()
        held = emptySet()
        rolls = 0
        rolling = false
        lastResult = ""
        turnLog = emptyList()
        roleResponse = GameRoleResponse()
        responseSpeaker = ""
    }

    fun saveTurn(player: YachtPlayer, category: YachtCategory, scoredDice: List<Int>, points: Int) {
        val nextPlayerScores = scores[player.id].orEmpty() + (category to points)
        val nextScores = scores + (player.id to nextPlayerScores)
        scores = nextScores
        lastResult = "${player.name}把 ${scoredDice.joinToString("、")} 填入${category.label}，得到${points}分。"
        val recordId = store.recordExternalGame(
            LuluGameType.YachtDice,
            "快艇骰子 · ${player.name}第${nextPlayerScores.size}轮",
            points,
            0,
            lastResult,
            JSONObject()
                .put("player", player.name)
                .put("category", category.name)
                .put("score", points)
                .put("dice", JSONArray(scoredDice))
                .put("rolls", rolls)
                .toString(),
            characterIdOverride = player.characterId,
        )
        saveGameAsSharedMemory(scope, store, recordId)
        if (player.characterId != null) {
            responseSpeaker = player.name
            requestGameRoleResponse(
                scope, store, recordId, lastResult,
                "你刚刚亲自完成了这一回合。根据真实骰面和计分类别，以角色自己的语气回应1-2句，不得修改分数。",
                "快艇骰子 · ${player.name}", { roleResponse = it }, maxTokens = 180,
                characterIdOverride = player.characterId,
            )
        }
        val finished = players.all { candidate ->
            val count = if (candidate.id == player.id) nextPlayerScores.size else nextScores[candidate.id].orEmpty().size
            count == YachtCategory.entries.size
        }
        if (finished) {
            val ranking = players.sortedByDescending { nextScores[it.id].orEmpty().values.sum() }
            val summary = ranking.joinToString("；") { "${it.name}${nextScores[it.id].orEmpty().values.sum()}分" }
            store.recordExternalGame(
                LuluGameType.YachtDice,
                "快艇骰子 · 完整对局",
                nextScores["user"].orEmpty().values.sum(),
                0,
                "整局结束：$summary。",
                JSONObject().put("completed", true).put("ranking", summary).toString(),
            )
        }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        dice = emptyList()
        held = emptySet()
        rolls = 0
    }

    fun rollForUser() {
        if (rolling || currentPlayer.id != "user" || rolls >= 3 || gameOver) return
        scope.launch {
            rolling = true
            repeat(9) {
                dice = List(5) { index -> if (index in held && dice.size == 5) dice[index] else Random.nextInt(1, 7) }
                kotlinx.coroutines.delay(55)
            }
            dice = List(5) { index -> if (index in held && dice.size == 5) dice[index] else Random.nextInt(1, 7) }
            rolls += 1
            turnLog = turnLog + "第${rolls}掷：${dice.joinToString("、")}${if (held.isEmpty()) "" else "（保留${held.size}颗）"}"
            rolling = false
        }
    }

    LaunchedEffect(currentPlayerIndex, scores, gameOver) {
        if (gameOver || currentPlayer.characterId == null || currentScores.size == YachtCategory.entries.size) return@LaunchedEffect
        turnLog = emptyList()
        held = emptySet()
        dice = emptyList()
        rolls = 0
        repeat(3) { rollIndex ->
            rolling = true
            repeat(9) {
                dice = List(5) { index -> if (index in held && dice.size == 5) dice[index] else Random.nextInt(1, 7) }
                kotlinx.coroutines.delay(60)
            }
            dice = List(5) { index -> if (index in held && dice.size == 5) dice[index] else Random.nextInt(1, 7) }
            rolls = rollIndex + 1
            val beforeHold = dice
            if (rollIndex < 2) held = chooseYachtHolds(dice)
            turnLog = turnLog + buildString {
                append("第${rollIndex + 1}掷：${beforeHold.joinToString("、")}")
                if (rollIndex < 2) append(" · 保留 ${held.sorted().joinToString("、") { beforeHold[it].toString() }}")
            }
            rolling = false
            kotlinx.coroutines.delay(700)
        }
        val category = YachtCategory.entries
            .filterNot { it in currentScores }
            .maxByOrNull { scoreYacht(it, dice) }
            ?: YachtCategory.Chance
        turnLog = turnLog + "选择${category.label}，本轮${scoreYacht(category, dice)}分"
        kotlinx.coroutines.delay(850)
        saveTurn(currentPlayer, category, dice, scoreYacht(category, dice))
    }

    GamePageList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    players.forEachIndexed { index, player ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${if (index == currentPlayerIndex && !gameOver) "▶ " else ""}${player.name} ${scores[player.id].orEmpty().values.sum()}分") },
                        )
                    }
                }
            }
        }
        if (currentPlayer.characterId != null || roleResponse.text.isNotBlank() || roleResponse.loading) {
            item { GameRolePanel(responseSpeaker.ifBlank { currentPlayer.name }, roleResponse) }
        }
        item {
            GameCard {
                Text("${currentPlayer.name}的回合 · 已掷 $rolls/3 次", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until 5).forEach { index ->
                        val value = dice.getOrNull(index)
                        Surface(
                            onClick = { held = if (index in held) held - index else held + index },
                            enabled = currentPlayer.id == "user" && value != null && !rolling && rolls in 1..2,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = if (index in held) GameDesign.wheat else GameDesign.card,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GameDesign.border),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text(value?.toString() ?: "·", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (currentPlayer.id == "user") {
                    Button(onClick = ::rollForUser, enabled = !rolling && rolls < 3 && !gameOver, modifier = Modifier.fillMaxWidth()) {
                        Text(if (rolls == 0) "投骰子" else "重掷未保留骰子")
                    }
                }
                turnLog.takeLast(4).forEach { Text(it, color = GameDesign.muted, fontSize = 13.sp) }
                if (lastResult.isNotBlank()) GameResultBanner(lastResult, success = true)
            }
        }
        item {
            YachtScoreTable(
                player = currentPlayer,
                scores = currentScores,
                dice = dice,
                canScore = currentPlayer.id == "user" && dice.size == 5 && !rolling,
                onScore = { category -> saveTurn(currentPlayer, category, dice, scoreYacht(category, dice)) },
            )
        }
        if (gameOver) {
            item {
                GameCard {
                    val ranking = players.sortedByDescending { scores[it.id].orEmpty().values.sum() }
                    Text("本局排名", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    ranking.forEachIndexed { index, player -> Text("${index + 1}. ${player.name}　${scores[player.id].orEmpty().values.sum()}分") }
                    Button(onClick = ::reset, modifier = Modifier.fillMaxWidth()) { Text("新一局") }
                }
            }
        }
    }
}

@Composable
private fun YachtScoreTable(
    player: YachtPlayer,
    scores: Map<YachtCategory, Int>,
    dice: List<Int>,
    canScore: Boolean,
    onScore: (YachtCategory) -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = GameDesign.card, border = androidx.compose.foundation.BorderStroke(1.dp, GameDesign.border)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${player.name}的计分表", fontWeight = FontWeight.Bold)
                Text("总分 ${scores.values.sum()}", fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = GameDesign.border)
            YachtCategory.entries.forEach { category ->
                val usedScore = scores[category]
                val preview = if (dice.size == 5) scoreYacht(category, dice) else 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canScore && usedScore == null) { onScore(category) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(category.label)
                    Text(
                        when {
                            usedScore != null -> "$usedScore"
                            canScore -> "$preview  ›"
                            else -> "—"
                        },
                        fontWeight = if (usedScore != null) FontWeight.Bold else FontWeight.Normal,
                        color = if (usedScore != null) MaterialTheme.colorScheme.primary else GameDesign.muted,
                    )
                }
                if (category != YachtCategory.entries.last()) HorizontalDivider(color = GameDesign.border.copy(alpha = 0.55f))
            }
        }
    }
}

private fun chooseYachtHolds(dice: List<Int>): Set<Int> {
    val counts = dice.groupingBy { it }.eachCount()
    val bestValue = counts.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key
        ?: return emptySet()
    return dice.indices.filterTo(mutableSetOf()) { dice[it] == bestValue }
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
    var status by remember { mutableStateOf("轮到你了") }
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
        status = "轮到你了"
        finished = false
        moves = 0
        roleResponse = GameRoleResponse()
    }

    GamePageList {
        item { GameRolePanel(character.displayName, roleResponse) }
        item { Text(status, Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.SemiBold) }
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
