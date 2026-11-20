package com.jiacimu.lulu.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/** A full-screen memory arena; the rules stay deterministic while the presentation feels like a game. */
@Composable
internal fun ImmersiveMemoryMatchScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val game = state.memoryMatch
    val opponentId = game.opponentId
    val character = opponentId?.let { MigratedDomainStores.characters.get(it) }
    val solo = opponentId == null
    val personalBest = store.memorySoloBestMoves()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val roomId = remember(game.cards) { java.util.UUID.randomUUID().toString() }
    val participants = remember(opponentId) { listOfNotNull(opponentId) }
    val live = rememberGameLiveSession("memory_match", roomId, participants)
    var ownerSpeech by remember(roomId) { mutableStateOf("") }
    var roleError by remember(roomId) { mutableStateOf("") }
    var choiceRetry by remember(roomId) { mutableIntStateOf(0) }
    var deciding by remember(roomId) { mutableStateOf(false) }

    SideEffect {
        live.onOwnerMessage = { text, fromSpeech ->
            ownerSpeech = text
            GameTeamConversationTimeline.recordOwner(
                context, "memory_match", roomId, participants, text, fromSpeech,
            )
            val actor = participants.firstOrNull()
            if (actor != null && !game.finished) {
                requestGameRoleResponse(
                    scope, store, null,
                    "主人在记忆翻牌中刚说：$text。双方各自拿同一副牌挑战6对，比较更少轮次。" +
                        "主人已用${game.moves}轮、完成${game.userPairs}对；你已用${game.characterMoves}轮、完成${game.characterPairs}对。" +
                        "你自己见过的位置：${game.characterObserved.toSortedMap()}；现在轮到${game.turn}。不知道没翻开的牌面。",
                    "你正在和主人一起玩翻牌。可以随意聊这句话和当前真实局面，别说出没有翻过的牌面，也不要替主人选牌。两句以内。",
                    "记忆翻牌 · 同桌交流",
                    onState = { response ->
                        if (response.text.isNotBlank()) {
                            live.companionSaid(actor, response.text, "reply-${System.nanoTime()}")
                        }
                    },
                    maxTokens = 140, characterIdOverride = actor, liveRoomId = roomId,
                    isLiveRoomCurrent = { store.state.value.memoryMatch.cards == game.cards },
                )
            }
        }
    }

    LaunchedEffect(game.opened, game.characterOpened, game.turn) {
        val open = if (game.turn == MemoryTurn.Character) game.characterOpened else game.opened
        val found = if (game.turn == MemoryTurn.Character) game.characterMatched else game.matched
        if (open.size == 2 && open.any { it !in found }) {
            delay(900)
            store.closeUnmatchedCards()
        }
    }
    LaunchedEffect(game.turn, game.opened, game.characterOpened, game.characterMatched,
        game.characterObserved, game.matched, game.finished, choiceRetry, ownerSpeech) {
        if (participants.isEmpty() || game.turn != MemoryTurn.Character ||
            game.finished || game.characterOpened.size >= 2) return@LaunchedEffect
        roleError = ""
        delay(450)
        deciding = true
        val roleView = game.copy(
            opened = game.characterOpened,
            matched = game.characterMatched,
            observed = game.characterObserved,
            moves = game.characterMoves,
        )
        val decision = requestMemoryRoleChoice(opponentId ?: return@LaunchedEffect, roleView, ownerSpeech)
        deciding = false
        val choice = decision.getOrElse { failure ->
            roleError = failure.message ?: "选牌失败，请重试"
            return@LaunchedEffect
        }
        // The model might finish after the user has left the turn or changed the deck.
        val now = store.state.value.memoryMatch
        if (now.cards != game.cards || now.turn != game.turn ||
            now.characterOpened != game.characterOpened ||
            now.characterMatched != game.characterMatched ||
            now.characterObserved != game.characterObserved || now.finished) return@LaunchedEffect
        choice.modelReply?.let { reply ->
            com.jiacimu.lulu.data.CharacterObservedMoment.recordScene(
                opponentId ?: return@LaunchedEffect, reply, "记忆配对", roomId,
                turnKey = "${game.characterMoves}:${game.characterOpened.size}",
                observation = "角色已找到${game.characterPairs}对，已用${game.characterMoves}轮；" +
                    "本轮已翻开${roleView.opened.size}张，主人已找到${game.userPairs}对。",
                outward = choice.speech,
            )
        }
        if (choice.speech.isNotBlank()) {
            live.companionSaid(opponentId ?: return@LaunchedEffect, choice.speech,
                "flip-${game.characterMoves}-${game.characterOpened.size}")
        }
        GameSoundEffects.play(GameSoundEffect.Interact)
        store.openCharacterMemoryCard(choice.index)
    }
    LaunchedEffect(game.finished) {
        if (game.finished) GameSoundEffects.play(GameSoundEffect.Objective)
    }

    val ambience = rememberInfiniteTransition(label = "memory-arena")
    val phase by ambience.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "memory-arena-phase",
    )

    Box(Modifier.fillMaxSize().imePadding()) {
        MemoryArenaBackground(phase)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MemoryArenaScoreboard(
                userPairs = game.userPairs,
                characterPairs = game.characterPairs,
                characterName = character?.displayName.orEmpty(),
                characterAvatar = character?.avatarUri,
                solo = solo,
                bestMoves = personalBest,
                turn = game.turn,
                moves = game.moves,
                characterMoves = game.characterMoves,
            )
            MemoryArenaBoard(
                cards = game.cards,
                opened = if (game.turn == MemoryTurn.Character) game.characterOpened else game.opened,
                matched = if (game.turn == MemoryTurn.Character) game.characterMatched else game.matched,
                enabled = !game.finished && game.turn == MemoryTurn.User && game.opened.size < 2,
                phase = phase,
                onOpen = { index ->
                    GameSoundEffects.play(GameSoundEffect.Interact)
                    store.openMemoryCard(index)
                },
                modifier = Modifier.weight(1f),
            )
            if (participants.isNotEmpty()) {
                Surface(color = Color(0xEFFFFFF9), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(7.dp)) {
                        GameTalkBar(live)
                        if (deciding) Text("${character?.displayName ?: "对手"}正在回忆刚才翻到的牌…",
                            color = Color(0xFF6C5989), fontSize = 11.sp)
                        if (roleError.isNotBlank()) {
                            Text(roleError, color = Color(0xFFB24F53), fontSize = 11.sp)
                            Button(onClick = { choiceRetry++ }) { Text("重新选牌") }
                        }
                    }
                }
            }
            Surface(
                color = Color(0xF9FFFFFF),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFDED8CC)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(7.dp).graphicsLayer { alpha = .62f + .38f * sin(phase * 6.283f) },
                    ) {
                        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = Color(0xFF846A9B)) {}
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(game.lastEvent, color = Color(0xFF495367), fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(if (solo) "配对 ${game.userPairs}/6"
                        else "你 ${game.userPairs}/6 · ${character?.displayName} ${game.characterPairs}/6",
                        color = Color(0xFF657184), fontSize = 9.sp)
                }
            }
        }

        AnimatedVisibility(
            visible = game.finished,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center).padding(28.dp),
        ) {
            val result = when {
                solo -> "挑战完成！"
                game.moves < game.characterMoves -> "你用更少轮次获胜！"
                game.moves > game.characterMoves -> "${character?.displayName}用更少轮次获胜！"
                else -> "这局平手"
            }
            Surface(
                color = Color(0xFFFFFDF9),
                shape = RoundedCornerShape(27.dp),
                border = BorderStroke(1.dp, Color(0xFFD2BADD).copy(alpha = .42f)),
                shadowElevation = 22.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFFB58A4C), modifier = Modifier.size(36.dp))
                    Text(result, color = GameDesign.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(if (solo) "${game.moves} 轮找齐全部 6 对 · 最佳 ${personalBest ?: game.moves} 轮"
                        else "你 ${game.moves} 轮 · ${character?.displayName} ${game.characterMoves} 轮 · 各找齐6对", color = Color(0xFF657184), fontSize = 11.sp)
                    Button(
                        onClick = store::resetMemoryMatch,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF846A9B), contentColor = Color.White),
                    ) {
                        Icon(Icons.Outlined.Replay, null)
                        Spacer(Modifier.size(7.dp))
                        Text("重新洗牌", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryArenaBackground(phase: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFF8F1EA), Color(0xFFF7F4F5), Color(0xFFECF3EF))))
        val drift = sin(phase * 6.283f)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFCEB1C5).copy(alpha = .22f), Color.Transparent)),
            radius = size.minDimension * .65f,
            center = Offset(size.width * (.28f + drift * .04f), size.height * .26f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFA6CDBB).copy(alpha = .15f), Color.Transparent)),
            radius = size.minDimension * .58f,
            center = Offset(size.width * (.76f - drift * .05f), size.height * .74f),
        )
        val horizon = size.height * .54f
        repeat(8) { index ->
            val y = horizon + (index * index) * size.height * .0075f
            drawLine(Color(0xFF929E96).copy(alpha = .12f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        repeat(9) { index ->
            val bottomX = size.width * index / 8f
            val topX = size.width * .5f + (bottomX - size.width * .5f) * .16f
            drawLine(Color(0xFF929E96).copy(alpha = .11f), Offset(topX, horizon), Offset(bottomX, size.height), 1f)
        }
    }
}

@Composable
private fun MemoryArenaScoreboard(
    userPairs: Int,
    characterPairs: Int,
    characterName: String,
    characterAvatar: String?,
    turn: MemoryTurn,
    moves: Int,
    characterMoves: Int,
    solo: Boolean,
    bestMoves: Int?,
) {
    if (solo) {
        Surface(
            color = Color(0xFFFFFDF9), shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFDED8CC)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("单人挑战 · 找齐 6 对", color = GameDesign.muted, fontSize = 11.sp)
                    Text("已翻 ${moves} 轮", color = GameDesign.ink,
                        fontSize = 23.sp, fontWeight = FontWeight.Black)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("个人最佳", color = GameDesign.muted, fontSize = 11.sp)
                    Text(if (bestMoves == null) "等待第一局" else "${bestMoves} 轮",
                        color = Color(0xFF7C6495), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }
    Surface(
        color = Color(0xF9FFFFFF),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFFDED8CC)),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = Color(0xFFECE6F1), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = Color(0xFF61596C), modifier = Modifier.size(21.dp)) }
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text("你", color = GameDesign.ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${moves} 轮", color = Color(0xFF74558D), fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text("完成 ${userPairs}/6 对", color = GameDesign.muted, fontSize = 9.sp)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF846A9B).copy(alpha = .12f)) {
                    Text(if (turn == MemoryTurn.User) "你的回合" else "$characterName 思考中", color = Color(0xFF634F7D), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                }
                Text(if (turn == MemoryTurn.User) "我的牌桌" else "对手牌桌", color = Color(0xFF7B8191), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.End) {
                Text(characterName, color = GameDesign.ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${characterMoves} 轮", color = Color(0xFF367F7A), fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text("完成 ${characterPairs}/6 对", color = GameDesign.muted, fontSize = 9.sp)
            }
            LuluProfileAvatar(characterAvatar, characterName.take(1).ifBlank { "角" }, 38)
        }
    }
}

@Composable
private fun MemoryArenaBoard(
    cards: List<String>,
    opened: Set<Int>,
    matched: Set<Int>,
    enabled: Boolean,
    phase: Float,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        cards.indices.chunked(4).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { index ->
                    val visible = index in opened || index in matched
                    val rotation by animateFloatAsState(
                        targetValue = if (visible) 180f else 0f,
                        animationSpec = spring(dampingRatio = .66f, stiffness = 390f),
                        label = "memory-card-$index",
                    )
                    val matchedCard = index in matched
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                rotationY = rotation
                                cameraDistance = 15f * density
                                translationY = if (matchedCard) sin(phase * 6.283f + index) * 2.5f else 0f
                            }
                            .clickable(enabled = enabled && !visible) { onOpen(index) },
                        color = when {
                            matchedCard -> Color(0xFFE0F0EA)
                            visible -> Color(0xFFF7EBD7)
                            else -> Color(0xFFFFFFFF)
                        },
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(
                            if (visible) 1.4.dp else 1.dp,
                            if (matchedCard) Color(0xFF6EA696).copy(alpha = .65f) else Color(0xFF846A9B).copy(alpha = if (visible) .55f else .20f),
                        ),
                        shadowElevation = if (visible) 12.dp else 4.dp,
                    ) {
                        Box(
                            Modifier.fillMaxSize().graphicsLayer { rotationY = if (rotation > 90f) 180f else 0f },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (visible) {
                                Text(cards[index], fontSize = 29.sp, textAlign = TextAlign.Center)
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("◇", color = Color(0xFF846A9B), fontSize = 25.sp, fontWeight = FontWeight.Light)
                                    Text("${rowIndex + 1}·${index % 4 + 1}", color = Color(0xFF7D8492), fontSize = 7.sp, letterSpacing = 1.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
