package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

internal enum class YachtCategory(val label: String) {
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

    // The match identity and participants live in durable storage, never in the screen lifecycle.
    var session by remember { mutableStateOf(store.openYachtMatch()) }
    val players = remember(session.id, characters) {
        listOf(YachtPlayer("user", "你")) + session.companionIds.map { id ->
            val character = characters[id] ?: MigratedDomainStores.characters.get(id)
            YachtPlayer("role:$id", character.displayName, id)
        }
    }
    var scores by remember(session.id) { mutableStateOf(session.scores) }
    var currentPlayerIndex by remember(session.id) { mutableIntStateOf(session.currentPlayerIndex) }
    var dice by remember(session.id) { mutableStateOf(session.dice) }
    var held by remember(session.id) { mutableStateOf(session.held) }
    var rolls by remember(session.id) { mutableIntStateOf(session.rolls) }
    var rolling by remember(session.id) { mutableStateOf(false) }
    var lastResult by remember(session.id) { mutableStateOf(session.lastResult) }
    var turnLog by remember(session.id) { mutableStateOf(session.turnLog) }
    var matchLog by remember(session.id) { mutableStateOf(session.matchLog) }
    var roleResponse by remember(session.id) { mutableStateOf(GameRoleResponse()) }
    var responseSpeaker by remember(session.id) { mutableStateOf("") }
    val currentPlayer = players[currentPlayerIndex.coerceIn(players.indices)]
    val currentScores = scores[currentPlayer.id].orEmpty()
    val gameOver = players.all { scores[it.id].orEmpty().size == YachtCategory.entries.size }

    fun persistMatch(completed: Boolean = gameOver) {
        val next = session.copy(
            scores = scores,
            currentPlayerIndex = currentPlayerIndex,
            dice = dice,
            held = held,
            rolls = rolls,
            lastResult = lastResult,
            turnLog = turnLog,
            matchLog = matchLog,
            completed = completed,
        )
        store.saveYachtMatch(next)
        session = next
    }

    fun reset() {
        session = store.restartYachtMatch()
        // The remember(session.id) state resets automatically with the new match ID.
    }

    fun recordFinalResult(finalScores: Map<String, Map<YachtCategory, Int>>, finalLog: List<String>) {
        val ranked = players.sortedByDescending { finalScores[it.id].orEmpty().values.sum() }
        val highest = ranked.firstOrNull()?.let { finalScores[it.id].orEmpty().values.sum() } ?: 0
        val winners = ranked.filter { finalScores[it.id].orEmpty().values.sum() == highest }
        val outcome = when {
            players.size == 1 -> "单人模式，完整完成13轮，没有对抗胜负"
            winners.size == 1 -> "胜者：${winners.first().name}"
            else -> "平局：${winners.joinToString("、") { it.name }}"
        }
        val rankingSummary = ranked.joinToString("；") { "${it.name}${finalScores[it.id].orEmpty().values.sum()}分" }
        val sheet = JSONObject().apply {
            players.forEach { player ->
                put(player.id, JSONObject().apply {
                    put("name", player.name)
                    put("total", finalScores[player.id].orEmpty().values.sum())
                    put("categories", JSONObject().apply {
                        YachtCategory.entries.forEach { category ->
                            put(category.name, finalScores[player.id]?.get(category) ?: JSONObject.NULL)
                        }
                    })
                })
            }
        }
        val recordId = store.recordExternalGame(
            LuluGameType.YachtDice,
            "快艇骰子",
            finalScores["user"].orEmpty().values.sum(),
            0,
            "13轮完整结束：$rankingSummary；$outcome。",
            JSONObject()
                .put("game", "yacht_dice")
                .put("mode", if (session.companionIds.isEmpty()) "solo" else "competitive")
                .put("completed", true)
                .put("ranking", rankingSummary)
                .put("winner", if (players.size == 1) JSONObject.NULL else if (winners.size == 1) winners.first().name else "平局")
                .put("winnerIds", JSONArray(if (players.size == 1) emptyList() else winners.map { it.id }))
                .put("roundCountPerPlayer", YachtCategory.entries.size)
                .put("scoreSheets", sheet)
                .put("turns", JSONArray(finalLog))
                .toString(),
            characterIdOverride = session.companionIds.firstOrNull().orEmpty(),
            playedWithCharacterOverride = session.companionIds.isNotEmpty(),
            recordIdOverride = session.id,
        )
        // The canonical record belongs to the invited role; other participants must
        // receive their own truthful outcome observation rather than remain unaware.
        players.mapNotNull { it.characterId }.forEach { participantId ->
            com.jiacimu.lulu.data.SharedExperienceTimeline.record(
                eventId = "game-raw-$recordId-participant-$participantId",
                characterId = participantId,
                channel = "共同游戏《快艇骰子》",
                speaker = "游戏结算",
                content = "双方完成全部13轮：$rankingSummary；$outcome。最终计分表已保存于对局$recordId。",
                occurredAt = java.time.Instant.now(),
                triggerExtraction = false,
                source = "game-match-result",
                evidenceKind = com.jiacimu.lulu.data.EventEvidenceKind.Observation,
            )
        }
        if (session.companionIds.isNotEmpty()) saveGameAsSharedMemory(scope, store, recordId)
    }

    LaunchedEffect(session.id, session.completed) {
        // After a crash between saving the thirteenth round and recording the result,
        // the deterministic record ID makes settlement safe to retry.
        if (session.completed && gameOver && store.state.value.records.none { it.id == session.id }) {
            recordFinalResult(scores, matchLog)
        }
    }

    fun saveTurn(player: YachtPlayer, category: YachtCategory, scoredDice: List<Int>, points: Int) {
        if (category in scores[player.id].orEmpty()) return
        val nextPlayerScores = scores[player.id].orEmpty() + (category to points)
        val nextScores = scores + (player.id to nextPlayerScores)
        scores = nextScores
        lastResult = "${player.name}把 ${scoredDice.joinToString("、")} 填入${category.label}，得到${points}分。"
        val nextMatchLog = matchLog + "${player.name}第${nextPlayerScores.size}轮：$lastResult"
        matchLog = nextMatchLog

        val finished = players.all { nextScores[it.id].orEmpty().size == YachtCategory.entries.size }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        dice = emptyList()
        held = emptySet()
        rolls = 0
        turnLog = emptyList()
        // The committed turn exists before any model commentary, navigation or settlement.
        persistMatch(completed = finished)

        if (player.characterId != null) {
            responseSpeaker = player.name
            requestGameRoleResponse(
                scope = scope,
                store = store,
                recordId = null,
                facts = lastResult + "\\n" + store.activeGameContext(player.characterId),
                instruction = "你刚刚亲自完成了这一回合。依据真实骰面及累计比分，以本人的语气自然回应；尚未结束不得声称获胜。",
                title = "快艇骰子 · ${player.name}",
                onState = { roleResponse = it },
                maxTokens = 180,
                characterIdOverride = player.characterId,
            )
        }
        if (finished) recordFinalResult(nextScores, nextMatchLog)
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
            persistMatch(completed = false)
        }
    }

    LaunchedEffect(session.id, currentPlayerIndex, scores, gameOver) {
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

    val ocean = Color(0xFF0C1729)
    val mint = Color(0xFF9BE6D8)
    val currentTurnLabel = if (gameOver) "对局结束" else if (currentPlayer.id == "user") "你的回合" else "${currentPlayer.name}的回合"
    val stageHint = when {
        gameOver -> "所有计分格已填满，查看本局排名"
        currentPlayer.id != "user" -> "伙伴正在掷骰和挑选计分格"
        rolls == 0 -> "第一步 · 掷出五颗骰子"
        rolls < 3 -> "第二步 · 点亮想留下的骰子，再重掷其余骰子"
        else -> "第三步 · 选择下方一个计分格，锁定这一轮得分"
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF122C3C), ocean, Color(0xFF080F20))),
        ),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YACHT DICE   /   ${currentScores.size + 1} · 13",
                    color = mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(currentTurnLabel, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(stageHint, color = Color(0xFFC0D8E3), fontSize = 13.sp, lineHeight = 19.sp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    players.forEachIndexed { index, player ->
                        val active = index == currentPlayerIndex && !gameOver
                        Surface(
                            color = if (active) mint else Color.White.copy(alpha = .09f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (active) mint else Color.White.copy(alpha = .14f)),
                        ) {
                            Text(
                                "${if (active) "● " else ""}${player.name}  ${scores[player.id].orEmpty().values.sum()}分",
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                color = if (active) ocean else Color(0xFFE1EBF4),
                                fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(), color = Color(0xFF1B3444),
                shape = RoundedCornerShape(27.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .13f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("骰子桌", color = Color.White, fontWeight = FontWeight.Black,
                            fontSize = 19.sp, modifier = Modifier.weight(1f))
                        Text("已掷 ${rolls}/3 次", color = mint, fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        (0 until 5).forEach { index ->
                            val value = dice.getOrNull(index)
                            val locked = index in held
                            val selectable = currentPlayer.id == "user" && value != null && !rolling && rolls in 1..2
                            Surface(
                                onClick = {
                                    held = if (locked) held - index else held + index
                                    persistMatch(completed = false)
                                },
                                enabled = selectable,
                                modifier = Modifier.weight(1f).aspectRatio(.79f),
                                shape = RoundedCornerShape(15.dp),
                                color = when {
                                    locked -> mint
                                    value != null -> Color(0xFFF5F9FF)
                                    else -> Color.White.copy(alpha = .09f)
                                },
                                border = BorderStroke(2.dp, if (locked) Color.White else Color.Transparent),
                                shadowElevation = if (locked) 7.dp else 0.dp,
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(vertical = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(value?.toString() ?: "?", fontSize = 30.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (value == null) Color.White.copy(alpha = .5f) else ocean)
                                    Text(if (locked) "保留" else if (selectable) "点选" else " ",
                                        color = if (locked) ocean else Color(0xFF50637A),
                                        fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    if (currentPlayer.id == "user" && !gameOver) {
                        Button(
                            onClick = ::rollForUser,
                            enabled = !rolling && rolls < 3,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = ocean),
                        ) {
                            Text(when {
                                rolling -> "骰子滚动中…"
                                rolls == 0 -> "开始掷骰"
                                else -> "重掷剩余 ${5 - held.size} 颗骰子"
                            }, fontWeight = FontWeight.Bold)
                        }
                        if (rolls in 1..2 && !rolling) {
                            Text("想直接计分？不用掷满三次，点击下方任意未填写的计分格即可。",
                                color = Color(0xFFB5CDD5), fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                    if (currentPlayer.id != "user" && !gameOver) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = mint, trackColor = Color.White.copy(alpha = .12f),
                        )
                    }
                    if (lastResult.isNotBlank()) {
                        Text(lastResult, color = mint, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    turnLog.takeLast(2).forEach { Text(it, color = Color(0xFF98B6C6), fontSize = 11.sp) }
                }
            }
        }
        item {
            YachtScoreTable(
                player = currentPlayer,
                scores = currentScores,
                dice = dice,
                canScore = currentPlayer.id == "user" && dice.size == 5 && !rolling && !gameOver,
                onScore = { category -> saveTurn(currentPlayer, category, dice, scoreYacht(category, dice)) },
            )
        }
        if (currentPlayer.characterId != null || roleResponse.text.isNotBlank() || roleResponse.loading || roleResponse.error.isNotBlank()) {
            item { GameRolePanel(responseSpeaker.ifBlank { currentPlayer.name }, roleResponse) }
        }
        if (gameOver) {
            item {
                val ranking = players.sortedByDescending { scores[it.id].orEmpty().values.sum() }
                val highest = ranking.firstOrNull()?.let { scores[it.id].orEmpty().values.sum() } ?: 0
                val winners = ranking.filter { scores[it.id].orEmpty().values.sum() == highest }
                Surface(
                    color = Color(0xFF183744), shape = RoundedCornerShape(25.dp),
                    border = BorderStroke(1.dp, mint.copy(alpha = .48f)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Text("MATCH COMPLETE  ·  13 / 13", color = mint,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(if (players.size == 1) "✦ 13轮完成" else if (winners.size == 1) "✦ ${winners.first().name} 获胜" else "◇ 本局平局",
                            color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text(if (players.size == 1) "最终获得 $highest 分" else if (winners.size == 1) "以 $highest 分赢下这一局" else "最高分同为 $highest 分",
                            color = Color(0xFFB5CDDA), fontSize = 13.sp)
                        ranking.forEachIndexed { index, player ->
                            val total = scores[player.id].orEmpty().values.sum()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. ${player.name}", Modifier.weight(1f),
                                    color = if (total == highest) mint else Color.White,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("$total 分", color = Color.White,
                                    fontWeight = FontWeight.Black, fontSize = 17.sp)
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .15f))
                        Text("完整计分表", Modifier.align(Alignment.Start), color = mint,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Column(Modifier.fillMaxWidth().horizontalScroll(
                            androidx.compose.foundation.rememberScrollState()
                        )) {
                            Row(Modifier.background(Color(0xFF244E53), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                .padding(vertical = 9.dp)) {
                                Text("项目", Modifier.width(80.dp).padding(start = 10.dp),
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                players.forEach { player ->
                                    Text(player.name, Modifier.width(80.dp),
                                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            YachtCategory.entries.forEachIndexed { index, category ->
                                Row(Modifier.background(if (index % 2 == 0) Color(0xFF132934) else Color(0xFF1C333D))
                                    .padding(vertical = 8.dp)) {
                                    Text(category.label, Modifier.width(80.dp).padding(start = 10.dp),
                                        color = Color(0xFFD9E6EF), fontSize = 11.sp)
                                    players.forEach { player ->
                                        Text("${scores[player.id]?.get(category) ?: 0}", Modifier.width(80.dp),
                                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Row(Modifier.background(Color(0xFF244E53), RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                                .padding(vertical = 10.dp)) {
                                Text("总分", Modifier.width(80.dp).padding(start = 10.dp),
                                    color = mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                players.forEach { player ->
                                    Text("${scores[player.id].orEmpty().values.sum()}", Modifier.width(80.dp),
                                        color = mint, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                        Text("13轮全部完成 · 本局战绩已保存",
                            color = Color(0xFF9FB6C8), fontSize = 11.sp)
                        Button(
                            onClick = ::reset, modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = ocean),
                            shape = RoundedCornerShape(15.dp),
                        ) { Text("再玩一局", fontWeight = FontWeight.Bold) }
                    }
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
    val mint = Color(0xFF9BE6D8)
    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF172A3C),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("计分板", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text(if (canScore) "选一项计分 · 每项整局只能使用一次" else "13 个项目，各填一次",
                        color = Color(0xFFABC1D3), fontSize = 11.sp)
                }
                Text("${scores.values.sum()} 分", color = mint, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            YachtCategory.entries.forEach { category ->
                val usedScore = scores[category]
                val preview = if (dice.size == 5) scoreYacht(category, dice) else 0
                val available = canScore && usedScore == null
                val description = when (category) {
                    YachtCategory.Ones, YachtCategory.Twos, YachtCategory.Threes,
                    YachtCategory.Fours, YachtCategory.Fives, YachtCategory.Sixes -> "只累加对应点数"
                    YachtCategory.ThreeKind -> "至少三颗同点 · 五颗点数总和"
                    YachtCategory.FourKind -> "至少四颗同点 · 五颗点数总和"
                    YachtCategory.FullHouse -> "三颗同点 + 两颗同点 · 25分"
                    YachtCategory.SmallStraight -> "连续四个点数 · 30分"
                    YachtCategory.LargeStraight -> "连续五个点数 · 40分"
                    YachtCategory.Yacht -> "五颗同点 · 50分"
                    YachtCategory.Chance -> "无条件累加五颗点数"
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = available) { onScore(category) }
                        .background(if (available) mint.copy(alpha = .065f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(category.label, color = if (usedScore != null) Color(0xFF91A5B5) else Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(description, color = Color(0xFF829CAC), fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Surface(color = when {
                        available -> mint
                        usedScore != null -> Color.White.copy(alpha = .12f)
                        else -> Color.White.copy(alpha = .06f)
                    }, shape = RoundedCornerShape(12.dp)) {
                        Text(when {
                            usedScore != null -> "$usedScore ✓"
                            available -> "+$preview  ›"
                            else -> "—"
                        }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            color = if (available) Color(0xFF102232) else Color(0xFFE1EBF5),
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                if (category != YachtCategory.entries.last())
                    HorizontalDivider(color = Color.White.copy(alpha = .07f))
            }
        }
    }
}

internal fun chooseYachtHolds(dice: List<Int>): Set<Int> {
    val counts = dice.groupingBy { it }.eachCount()
    val bestValue = counts.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key ?: return emptySet()
    return dice.indices.filterTo(mutableSetOf()) { dice[it] == bestValue }
}

internal fun scoreYacht(category: YachtCategory, dice: List<Int>): Int {
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
            JSONObject().put("outcome", outcome).put("moves", moves).put("board", JSONArray(finalBoard)).toString(),
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
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(board, finished) {
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
        board.indices.any { other -> board[other] != 0 && abs(other / 15 - row) <= 2 && abs(other % 15 - col) <= 2 }
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
