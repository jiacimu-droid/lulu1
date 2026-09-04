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
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)

    LaunchedEffect(game.opened) {
        if (game.opened.size == 2 && game.opened.any { it !in game.matched }) {
            delay(900)
            store.closeUnmatchedCards()
        }
    }
    LaunchedEffect(game.turn, game.opened, game.matched, game.finished) {
        if (game.turn != MemoryTurn.Character || game.finished || game.opened.size >= 2) return@LaunchedEffect
        delay(550)
        val available = game.cards.indices.filter { it !in game.matched && it !in game.opened }
        if (available.isEmpty()) return@LaunchedEffect
        val choice = if (game.opened.isEmpty()) {
            available.random()
        } else {
            val first = game.opened.first()
            available.firstOrNull { game.cards[it] == game.cards[first] }
                ?.takeIf { Random.nextInt(100) < 68 }
                ?: available.random()
        }
        GameSoundEffects.play(GameSoundEffect.Interact)
        store.openCharacterMemoryCard(choice)
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

    Box(Modifier.fillMaxSize()) {
        MemoryArenaBackground(phase)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MemoryArenaScoreboard(
                userPairs = game.userPairs,
                characterPairs = game.characterPairs,
                characterName = character.displayName,
                characterAvatar = character.avatarUri,
                turn = game.turn,
                moves = game.moves,
            )
            MemoryArenaBoard(
                cards = game.cards,
                opened = game.opened,
                matched = game.matched,
                enabled = !game.finished && game.turn == MemoryTurn.User && game.opened.size < 2,
                phase = phase,
                onOpen = { index ->
                    GameSoundEffects.play(GameSoundEffect.Interact)
                    store.openMemoryCard(index)
                },
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = Color(0xC20C1220),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(7.dp).graphicsLayer { alpha = .62f + .38f * sin(phase * 6.283f) },
                    ) {
                        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = Color(0xFFBCA8FF)) {}
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(game.lastEvent, color = Color(0xFFD5D9E8), fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("配对 ${game.matched.size / 2}/6", color = Color(0xFF9EA8BF), fontSize = 9.sp)
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
                game.userPairs > game.characterPairs -> "你赢下了这局"
                game.userPairs < game.characterPairs -> "${character.displayName}赢下了这局"
                else -> "这局平手"
            }
            Surface(
                color = Color(0xF20D1320),
                shape = RoundedCornerShape(27.dp),
                border = BorderStroke(1.dp, Color(0xFFCCB9FF).copy(alpha = .42f)),
                shadowElevation = 22.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFFE9D59A), modifier = Modifier.size(36.dp))
                    Text(result, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("${game.moves} 轮 · 你 ${game.userPairs} : ${game.characterPairs} ${character.displayName}", color = Color(0xFFABB4C9), fontSize = 11.sp)
                    Button(
                        onClick = store::resetMemoryMatch,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBCA8FF), contentColor = Color(0xFF181226)),
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
        drawRect(Brush.verticalGradient(listOf(Color(0xFF090D17), Color(0xFF131224), Color(0xFF080C13))))
        val drift = sin(phase * 6.283f)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF7966BF).copy(alpha = .22f), Color.Transparent)),
            radius = size.minDimension * .65f,
            center = Offset(size.width * (.28f + drift * .04f), size.height * .26f),
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF4FA7B7).copy(alpha = .15f), Color.Transparent)),
            radius = size.minDimension * .58f,
            center = Offset(size.width * (.76f - drift * .05f), size.height * .74f),
        )
        val horizon = size.height * .54f
        repeat(8) { index ->
            val y = horizon + (index * index) * size.height * .0075f
            drawLine(Color.White.copy(alpha = .035f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        repeat(9) { index ->
            val bottomX = size.width * index / 8f
            val topX = size.width * .5f + (bottomX - size.width * .5f) * .16f
            drawLine(Color.White.copy(alpha = .028f), Offset(topX, horizon), Offset(bottomX, size.height), 1f)
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
) {
    Surface(
        color = Color(0xC70B111D),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = Color(0xFF252A3B), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = Color(0xFFE3E7F4), modifier = Modifier.size(21.dp)) }
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text("你", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(userPairs.toString().padStart(2, '0'), color = Color(0xFFCAAFFF), fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFFBCA8FF).copy(alpha = .12f)) {
                    Text(if (turn == MemoryTurn.User) "你的回合" else "$characterName 思考中", color = Color(0xFFD8CBFF), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                }
                Text("ROUND ${moves + 1}", color = Color(0xFF747D94), fontSize = 7.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.End) {
                Text(characterName, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(characterPairs.toString().padStart(2, '0'), color = Color(0xFF8EE4E7), fontSize = 22.sp, fontWeight = FontWeight.Black)
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
                            matchedCard -> Color(0xFF304B55)
                            visible -> Color(0xFF322B48)
                            else -> Color(0xFF161C2A)
                        },
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(
                            if (visible) 1.4.dp else 1.dp,
                            if (matchedCard) Color(0xFF91F0E7).copy(alpha = .65f) else Color(0xFFBCA8FF).copy(alpha = if (visible) .55f else .20f),
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
                                    Text("◇", color = Color(0xFFBCA8FF), fontSize = 25.sp, fontWeight = FontWeight.Light)
                                    Text("${rowIndex + 1}·${index % 4 + 1}", color = Color(0xFF687188), fontSize = 7.sp, letterSpacing = 1.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
