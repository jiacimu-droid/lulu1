package com.jiacimu.lulu.games

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

@Composable
internal fun DeepSeaJourneyScreen(store: LuluGameStore) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val companion = if (state.playWithCharacter) {
        state.selectedCharacterIds.firstOrNull()?.let(MigratedDomainStores.characters::get)
    } else null
    val map = remember { buildDeepSeaMap() }
    val progress = remember(context) { DeepSeaJourneyProgress(context) }
    val restored = remember(progress) { progress.load(map.start) }
    val obstacles = remember(map) { map.reefs.map(DeepSeaReef::obstacle) }
    val safeRestoredPosition = remember(restored.position, obstacles) {
        if (obstacles.none { circleIntersects(restored.position, 38f, it.bounds) }) restored.position else map.start
    }

    var player by remember { mutableStateOf(safeRestoredPosition) }
    var camera by remember { mutableStateOf(WorldVector.Zero) }
    var joystick by remember { mutableStateOf(WorldVector.Zero) }
    var tapTarget by remember { mutableStateOf<WorldVector?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var collected by remember { mutableStateOf(restored.collected.intersect(map.echoes.map(DeepSeaEcho::id).toSet())) }
    var companionPosition by remember { mutableStateOf(map.start + WorldVector(-90f, 55f)) }
    var notice by remember { mutableStateOf("寻找五枚回声晶体，珊瑚和沉船都是真实障碍") }
    var completed by remember { mutableStateOf(restored.completed) }
    var score by remember { mutableIntStateOf(restored.score) }
    var runStartedAt by remember { mutableLongStateOf(restored.startedAt) }
    var recorded by remember { mutableStateOf(restored.recorded) }

    val ambience = rememberInfiniteTransition(label = "deep-sea-ambience")
    val phase by ambience.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "deep-sea-phase",
    )
    val scale = (viewport.height.toFloat() / 710f).coerceAtLeast(.32f)
    val viewportWorldWidth = viewport.width.toFloat() / scale
    val viewportWorldHeight = viewport.height.toFloat() / scale

    val latestPlayer by rememberUpdatedState(player)
    val latestCollected by rememberUpdatedState(collected)
    val latestCompleted by rememberUpdatedState(completed)
    val latestScore by rememberUpdatedState(score)
    DisposableEffect(progress) {
        onDispose { progress.save(latestPlayer, latestCollected, latestCompleted, latestScore) }
    }
    LaunchedEffect(progress) {
        while (isActive) {
            delay(1_500)
            progress.save(player, collected, completed, score)
        }
    }
    LaunchedEffect(notice) {
        if (notice.isBlank()) return@LaunchedEffect
        delay(2_600)
        notice = ""
    }

    LaunchedEffect(viewport, completed) {
        var previousFrame = withFrameNanos { it }
        var lastBump = 0L
        while (isActive && !completed) {
            withFrameNanos { frame ->
                val dt = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                previousFrame = frame
                val automatic = tapTarget?.let { target ->
                    val delta = target - player
                    if (delta.length < 24f) {
                        tapTarget = null
                        WorldVector.Zero
                    } else delta.normalized()
                } ?: WorldVector.Zero
                val direction = if (joystick.length > .08f) joystick else automatic
                if (direction.length > .04f) {
                    if (joystick.length > .08f) tapTarget = null
                    val moved = moveInWorld(
                        player,
                        direction,
                        speed = 300f,
                        deltaSeconds = dt,
                        radius = 36f,
                        bounds = DEEP_SEA_WORLD_BOUNDS,
                        obstacles = obstacles,
                    )
                    player = moved.position
                    if (moved.collided) {
                        tapTarget = null
                        val now = System.currentTimeMillis()
                        if (now - lastBump > 400L) {
                            GameSoundEffects.play(GameSoundEffect.Bump)
                            notice = "洋流被礁体挡住了，沿边缘绕行"
                            lastBump = now
                        }
                    }
                }
                camera = camera.lerp(
                    worldCameraTarget(
                        player,
                        viewportWorldWidth,
                        viewportWorldHeight,
                        DEEP_SEA_WORLD_WIDTH,
                        DEEP_SEA_WORLD_HEIGHT,
                    ),
                    dt * 4.8f,
                )
                companionPosition = companionPosition.lerp(
                    player + WorldVector(if (direction.x < -.05f) 92f else -92f, 58f),
                    dt * 2.3f,
                )
            }
        }
    }

    val nearbyEcho = map.echoes
        .filterNot { it.id in collected }
        .minByOrNull { player.distanceTo(it.position) }
        ?.takeIf { player.distanceTo(it.position) < 122f }
    val gateReady = collected.size == map.echoes.size
    val nearGate = gateReady && player.distanceTo(map.gate) < 145f
    val targetEcho = map.echoes.firstOrNull { it.id !in collected }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF051724))
            .onSizeChanged { viewport = it },
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(viewport) {
                    detectTapGestures { tap ->
                        tapTarget = WorldVector(tap.x / scale + camera.x, tap.y / scale + camera.y)
                        GameSoundEffects.play(GameSoundEffect.Move)
                    }
                },
        ) {
            withTransform({
                translate(-camera.x * scale, -camera.y * scale)
                scale(scale, scale, Offset.Zero)
            }) {
                drawDeepSeaWorld(
                    map = map,
                    phase = phase,
                    collected = collected,
                    player = player,
                    companion = companion?.let { companionPosition },
                )
            }
            val playerScreen = Offset((player.x - camera.x) * scale, (player.y - camera.y) * scale)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFB9FFEE).copy(alpha = .15f), Color.Transparent),
                    center = playerScreen,
                    radius = size.minDimension * .28f,
                ),
                size.minDimension * .28f,
                playerScreen,
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color(0xB804101C)),
                    center = Offset(size.width * .50f, size.height * .45f),
                    radius = size.maxDimension * .73f,
                ),
            )
            repeat(24) { index ->
                val seed = index * 397 + 71
                val x = ((seed % 1_100) / 1_100f * size.width + phase * size.width * .08f) % size.width
                val y = (((seed / 19) % 1_000) / 1_000f * size.height - phase * size.height * .45f + size.height) % size.height
                drawCircle(Color(0xFFB9F4EA).copy(alpha = .18f), (2 + index % 4).dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
            }
        }

        DeepSeaHud(
            collected = collected.size,
            total = map.echoes.size,
            distance = targetEcho?.let { player.distanceTo(it.position) },
            companionName = companion?.displayName,
            depth = 38 + (player.y / DEEP_SEA_WORLD_HEIGHT * 212f).toInt(),
            modifier = Modifier.align(Alignment.TopCenter).padding(11.dp),
        )

        AnimatedVisibility(
            visible = notice.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
        ) {
            Surface(
                color = Color(0xE30A2734),
                shape = RoundedCornerShape(17.dp),
                border = BorderStroke(1.dp, Color(0xFFAAF9E8).copy(alpha = .40f)),
                shadowElevation = 10.dp,
            ) {
                Text(notice, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }

        if (!completed) {
            WorldVirtualJoystick(
                value = joystick,
                onValueChanged = { next ->
                    if (joystick.length <= .05f && next.length > .05f) GameSoundEffects.play(GameSoundEffect.Move)
                    joystick = next
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 15.dp, bottom = 18.dp),
                tint = Color(0xFFB3FFF0),
            )
            WorldActionButton(
                label = when {
                    nearGate -> "归航"
                    nearbyEcho != null -> "共鸣"
                    else -> "靠近"
                },
                enabled = nearbyEcho != null || nearGate,
                onClick = {
                    when {
                        nearbyEcho != null -> {
                            collected = collected + nearbyEcho.id
                            GameSoundEffects.play(if (collected.size == map.echoes.size) GameSoundEffect.Objective else GameSoundEffect.Collect)
                            notice = if (collected.size == map.echoes.size) "五枚回声已经连成归航路线" else "获得：${nearbyEcho.title}"
                            progress.save(player, collected, completed, score)
                        }
                        nearGate -> {
                            val elapsedSeconds = ((System.currentTimeMillis() - runStartedAt) / 1_000L).coerceAtLeast(1L)
                            score = (1_500 - elapsedSeconds.toInt() * 2).coerceAtLeast(350)
                            completed = true
                            GameSoundEffects.play(GameSoundEffect.Objective)
                            if (!recorded) {
                                store.recordExternalGame(
                                    type = LuluGameType.DeepSeaJourney,
                                    title = "深海回声",
                                    score = score,
                                    reward = collected.size * 3,
                                    summary = buildString {
                                        append("在深海遗迹中收集了 ${collected.size} 枚回声并成功归航")
                                        companion?.let { append("，${it.displayName}一同完成了潜航") }
                                        append("。")
                                    },
                                    detailsJson = JSONObject()
                                        .put("game", "deep_sea_journey")
                                        .put("echoes", JSONArray(collected.toList()))
                                        .put("elapsed_seconds", elapsedSeconds)
                                        .put("solo", companion == null)
                                        .toString(),
                                    characterIdOverride = companion?.characterId.orEmpty(),
                                    playedWithCharacterOverride = companion != null,
                                )
                                progress.markRecorded()
                                recorded = true
                            }
                            progress.save(player, collected, true, score)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 30.dp),
                accent = Color(0xFF9FFFF0),
            )
            val nearbyLabel = nearbyEcho?.title ?: if (nearGate) "回声门已开启" else null
            if (nearbyLabel != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 116.dp),
                    color = Color(0xD9082230),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
                ) {
                    Text(nearbyLabel, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
        } else {
            DeepSeaCompletion(
                score = score,
                companionName = companion?.displayName,
                onRestart = {
                    progress.reset()
                    player = map.start
                    camera = WorldVector.Zero
                    collected = emptySet()
                    completed = false
                    score = 0
                    runStartedAt = System.currentTimeMillis()
                    recorded = false
                    notice = "新的潜航开始了"
                },
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@Composable
private fun DeepSeaHud(
    collected: Int,
    total: Int,
    distance: Float?,
    companionName: String?,
    depth: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xD9082230),
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, Color(0xFFB5FFF0).copy(alpha = .22f)),
        shadowElevation = 9.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF9EFFE9).copy(alpha = .12f), modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Waves, null, tint = Color(0xFFAFFFF0), modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("回声海沟 · ${depth}m", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Text(companionName?.let { "$it 正在跟随潜航" } ?: "单人自由潜航", color = Color(0xFF93BFB8), fontSize = 8.5.sp)
                }
                Text("$collected / $total", color = Color(0xFFAFFFF0), fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NearMe, null, tint = Color(0xFFE8C985), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    when {
                        collected >= total -> "归航门已开启，前往东南侧信标"
                        distance == null -> "搜索回声晶体"
                        else -> "下一枚回声约 ${distance.toInt()}m"
                    },
                    color = Color(0xFFC7DCD7),
                    fontSize = 8.5.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("氧气 100%", color = Color(0xFF9EFFE9), fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun DeepSeaCompletion(
    score: Int,
    companionName: String?,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xF20A2532),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFFB5FFF0).copy(alpha = .38f)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFFE9D38D), modifier = Modifier.size(38.dp))
            Text("回声门回应了你", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text(
                companionName?.let { "你和 $it 穿过沉城与珊瑚林，带着五段回声归航。" }
                    ?: "你独自穿过沉城与珊瑚林，带着五段回声归航。",
                color = Color(0xFFB8D3CD),
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
            Text("$score", color = Color(0xFFAFFFF0), fontSize = 42.sp, fontWeight = FontWeight.Black)
            Text("潜航评分", color = Color(0xFF8DAFA8), fontSize = 9.sp)
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9EFFE9), contentColor = Color(0xFF09242D)),
            ) {
                Icon(Icons.Outlined.Replay, null)
                Spacer(Modifier.width(6.dp))
                Text("再次潜航", fontWeight = FontWeight.Black)
            }
        }
    }
}

private data class DeepSeaRestoredState(
    val position: WorldVector,
    val collected: Set<String>,
    val completed: Boolean,
    val score: Int,
    val startedAt: Long,
    val recorded: Boolean,
)

private class DeepSeaJourneyProgress(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lulu_deep_sea_journey_v1", Context.MODE_PRIVATE)

    fun load(fallback: WorldVector): DeepSeaRestoredState {
        val startedAt = prefs.getLong("started_at", 0L).takeIf { it > 0L } ?: System.currentTimeMillis().also {
            prefs.edit().putLong("started_at", it).apply()
        }
        return DeepSeaRestoredState(
            position = WorldVector(
                prefs.getFloat("x", fallback.x).coerceIn(DEEP_SEA_WORLD_BOUNDS.left + 40f, DEEP_SEA_WORLD_BOUNDS.right - 40f),
                prefs.getFloat("y", fallback.y).coerceIn(DEEP_SEA_WORLD_BOUNDS.top + 40f, DEEP_SEA_WORLD_BOUNDS.bottom - 40f),
            ),
            collected = prefs.getStringSet("collected", emptySet()).orEmpty().toSet(),
            completed = prefs.getBoolean("completed", false),
            score = prefs.getInt("score", 0),
            startedAt = startedAt,
            recorded = prefs.getBoolean("recorded", false),
        )
    }

    fun save(position: WorldVector, collected: Set<String>, completed: Boolean, score: Int) {
        prefs.edit()
            .putFloat("x", position.x)
            .putFloat("y", position.y)
            .putStringSet("collected", collected.toSet())
            .putBoolean("completed", completed)
            .putInt("score", score)
            .apply()
    }

    fun markRecorded() {
        prefs.edit().putBoolean("recorded", true).apply()
    }

    fun reset() {
        prefs.edit().clear().putLong("started_at", System.currentTimeMillis()).apply()
    }
}
