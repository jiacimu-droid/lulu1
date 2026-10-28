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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.atan2
import kotlin.math.roundToInt

@Composable
internal fun DeepSeaJourneyScreen(store: LuluGameStore) {
    val context = LocalContext.current
    val state by store.state.collectAsState()
    val map = remember { buildDeepSeaMap() }
    val progress = remember(context) { DeepSeaJourneyProgress(context) }
    val restored = remember(progress) { progress.load(map.start) }
    val selectedCompanionId = if (state.playWithCharacter) state.selectedCharacterIds.firstOrNull().orEmpty() else ""
    // Lock one character to the actual dive so a later lobby selection cannot inherit somebody else's memories.
    val sessionCompanionId = remember(progress) { restored.sessionCompanionId.ifBlank { selectedCompanionId } }
    val companion = sessionCompanionId.takeIf(String::isNotBlank)?.let(MigratedDomainStores.characters::get)
    val obstacles = remember(map) { map.reefs.map(DeepSeaReef::obstacle) }
    val safeRestoredPosition = remember(restored.position, obstacles) {
        if (obstacles.none { circleIntersects(restored.position, 38f, it.bounds) }) restored.position else map.start
    }

    var player by remember { mutableStateOf(safeRestoredPosition) }
    var camera by remember { mutableStateOf(WorldVector.Zero) }
    var cameraInitialized by remember { mutableStateOf(false) }
    var joystick by remember { mutableStateOf(WorldVector.Zero) }
    var swimVelocity by remember { mutableStateOf(WorldVector.Zero) }
    var companionRoute by remember { mutableStateOf(emptyList<WorldVector>()) }
    var navigationRoute by remember { mutableStateOf(emptyList<WorldVector>()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var collected by remember { mutableStateOf(restored.collected.intersect(map.echoes.map(DeepSeaEcho::id).toSet())) }
    var scanned by remember { mutableStateOf(restored.scanned.intersect(map.echoes.map(DeepSeaEcho::id).toSet())) }
    var visited by remember { mutableStateOf(restored.visited.intersect(deepSeaSites.map(DeepSeaSite::id).toSet())) }
    var companionAssists by remember { mutableIntStateOf(if (restored.sessionCompanionId == sessionCompanionId) restored.companionAssists else 0) }
    var survey by remember { mutableStateOf(restored.survey) }
    var focusSalvage by remember { mutableStateOf(false) }
    var sonarCharge by remember { mutableFloatStateOf(1f) }
    var sonarPulse by remember { mutableFloatStateOf(0f) }
    var pickupPulse by remember { mutableFloatStateOf(0f) }
    var lastPickupPosition by remember { mutableStateOf<WorldVector?>(null) }
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
    val latestScanned by rememberUpdatedState(scanned)
    val latestVisited by rememberUpdatedState(visited)
    val latestAssists by rememberUpdatedState(companionAssists)
    val latestSurvey by rememberUpdatedState(survey)
    DisposableEffect(progress) {
        onDispose { progress.save(latestPlayer, latestCollected, latestCompleted, latestScore, latestScanned, latestVisited, latestAssists, sessionCompanionId, latestSurvey) }
    }
    LaunchedEffect(progress) {
        while (isActive) {
            delay(1_500)
            progress.save(player, collected, completed, score, scanned, visited, companionAssists, sessionCompanionId, survey)
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
        var nextCompanionRouteAt = 0f
        while (isActive && !completed) {
            withFrameNanos { frame ->
                val dt = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                previousFrame = frame
                if (viewport == IntSize.Zero) return@withFrameNanos
                if (!cameraInitialized) {
                    camera = worldCameraTarget(player, viewportWorldWidth, viewportWorldHeight,
                        DEEP_SEA_WORLD_WIDTH, DEEP_SEA_WORLD_HEIGHT)
                    cameraInitialized = true
                }

                if (navigationRoute.firstOrNull()?.let { player.distanceTo(it) <= 6f } == true) {
                    navigationRoute = navigationRoute.drop(1)
                }
                val waypoint = navigationRoute.firstOrNull()
                val toWaypoint = waypoint?.minus(player) ?: WorldVector.Zero
                val manual = joystick.length > .08f
                if (manual && navigationRoute.isNotEmpty()) navigationRoute = emptyList()
                val direction = if (manual) joystick.normalized() else toWaypoint.normalized()
                val speed = (if (manual) 300f else (toWaypoint.length * 5f).coerceAtMost(300f)) *
                    (deepSeaCurrentAt(player)?.speedFactor ?: 1f)
                // Manual steering keeps inertia; autopilot follows exact safe segments without cutting corners.
                swimVelocity = if (manual || waypoint == null)
                    swimVelocity.lerp(direction * speed, (dt * 7.5f).coerceIn(0f, 1f))
                else direction * speed
                if (swimVelocity.length > 1f) {
                    val moved = moveInWorld(player, swimVelocity, swimVelocity.length, dt,
                        DEEP_SEA_PLAYER_RADIUS, DEEP_SEA_WORLD_BOUNDS, obstacles)
                    player = moved.position
                    if (moved.collided) {
                        swimVelocity = WorldVector.Zero
                        navigationRoute = emptyList()
                        val now = System.currentTimeMillis()
                        if (now - lastBump > 650L) {
                            GameSoundEffects.play(GameSoundEffect.Bump)
                            notice = if (companion == null) "前方是礁体，试着绕开它"
                                else "${companion.displayName}：前面有礁石，向侧边绕行"
                            lastBump = now
                        }
                    }
                }
                sonarPulse = (sonarPulse - dt * .95f).coerceAtLeast(0f)
                sonarCharge = (sonarCharge + dt / 4.8f).coerceAtMost(1f)
                pickupPulse = (pickupPulse - dt * 1.65f).coerceAtLeast(0f)
                camera = camera.lerp(
                    worldCameraTarget(player, viewportWorldWidth, viewportWorldHeight,
                        DEEP_SEA_WORLD_WIDTH, DEEP_SEA_WORLD_HEIGHT),
                    (dt * 4.8f).coerceIn(0f, 1f),
                )
                if (companion != null) {
                    nextCompanionRouteAt -= dt
                    if (nextCompanionRouteAt <= 0f) {
                        val intended = player + WorldVector(-75f, 68f)
                        val safeGoal = if (obstacles.none { circleIntersects(intended, 23f, it.bounds) }) intended else player
                        companionRoute = worldFollowPath(companionPosition, safeGoal,
                            DEEP_SEA_WORLD_BOUNDS, obstacles, 23f)
                        nextCompanionRouteAt = .75f
                    }
                    // A companion can locate actual nearby echoes but cannot collect for the player.
                    val companionClues = deepSeaSonarFind(map, companionPosition, collected, 290f) - scanned
                    if (companionClues.isNotEmpty()) {
                        scanned = scanned + companionClues
                        companionAssists++
                        notice = "${companion.displayName}发现附近的回声线索，已标入声呐"
                        GameSoundEffects.play(GameSoundEffect.Interact)
                    }
                    val follow = companionRoute.firstOrNull()
                    if (follow != null) {
                        val gap = companionPosition.distanceTo(follow)
                        if (gap < 6f) companionRoute = companionRoute.drop(1)
                        else companionPosition = moveInWorld(companionPosition, follow - companionPosition,
                            (gap * 5f).coerceAtMost(230f), dt, 23f,
                            DEEP_SEA_WORLD_BOUNDS, obstacles).position
                    }
                }
            }
        }
    }

    val nearbyEcho = deepSeaNearbyEcho(map, player, collected, obstacles)
    val nearbySite = deepSeaNearbySite(player, visited, obstacles)
    val nearbyRelic = deepSeaNearbyRelic(player, survey.revealedRelics, survey.salvagedRelics, obstacles)
    val nearbyCreature = deepSeaNearbyCreature(player, survey.observedCreatures, obstacles)
    val optionalRelic = deepSeaOptionalTarget(player, survey.revealedRelics, survey.salvagedRelics)
    val activeSalvage = focusSalvage && optionalRelic != null
    val localCurrent = deepSeaCurrentAt(player)
    val gateReady = map.echoes.all { it.id in collected }
    val nearGate = deepSeaCanReturn(map, player, collected, obstacles)
    val revealedTarget = map.echoes.asSequence()
        .filterNot { it.id in collected }
        .filter { it.id in scanned || player.distanceTo(it.position) <= 260f }
        .minByOrNull { player.distanceTo(it.position) }
    val navigationTarget = when {
        activeSalvage -> optionalRelic?.position
        gateReady -> map.gate
        else -> revealedTarget?.position
    }
    val navigationDistance = navigationTarget?.let { destination ->
        navigationRoute.takeIf { it.lastOrNull() == destination }?.let { deepSeaRouteDistance(player, it) }
    }

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
                        val tapped = WorldVector(tap.x / scale + camera.x, tap.y / scale + camera.y)
                        val route = worldFollowPath(player, tapped, DEEP_SEA_WORLD_BOUNDS, obstacles, 36f)
                        if (route.isEmpty()) notice = "礁石阻断了这条航线，试试旁边的水域"
                        else {
                            navigationRoute = route
                            GameSoundEffects.play(GameSoundEffect.Move)
                        }
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
                    visited = visited,
                    revealedRelics = survey.revealedRelics,
                    salvagedRelics = survey.salvagedRelics,
                    observedCreatures = survey.observedCreatures,
                )
                if (sonarPulse > 0f) {
                    drawCircle(Color(0xFFB5FFF0).copy(alpha = sonarPulse * .55f),
                        radius = 80f + (1f - sonarPulse) * DEEP_SEA_SONAR_RADIUS,
                        center = Offset(player.x, player.y),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                }
                if (pickupPulse > 0f) {
                    lastPickupPosition?.let { where ->
                        val glow = where.lerp(player, 1f - pickupPulse)
                        drawLine(Color(0xFF9CFFE7).copy(alpha = pickupPulse * .7f),
                            Offset(where.x, where.y), Offset(glow.x, glow.y), strokeWidth = 5f)
                        drawCircle(Color(0xFFE1FFFA).copy(alpha = pickupPulse),
                            radius = 7f + 15f * pickupPulse, center = Offset(glow.x, glow.y))
                        drawCircle(Color(0xFFD7FFF5).copy(alpha = pickupPulse * .85f),
                            radius = 30f + (1f - pickupPulse) * 105f,
                            center = Offset(where.x, where.y),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(8f * pickupPulse + 1f))
                    }
                }
                if (navigationRoute.isNotEmpty()) {
                    (listOf(player) + navigationRoute).zipWithNext().forEach { (start, finish) ->
                        drawLine(Color(0xFF91FFE6).copy(alpha = .60f),
                            start = Offset(start.x, start.y),
                            end = Offset(finish.x, finish.y),
                            strokeWidth = 5f, cap = StrokeCap.Round)
                    }
                    navigationRoute.lastOrNull()?.let { goal ->
                        drawCircle(Color(0xFFADFFEF).copy(alpha = .55f), radius = 30f,
                            center = Offset(goal.x, goal.y),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                    }
                }
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
            distance = navigationTarget?.let { player.distanceTo(it) },
            gateReady = gateReady,
            relics = survey.salvagedRelics.size,
            totalRelics = deepSeaRelics.size,
            wildlife = survey.observedCreatures.size,
            totalWildlife = deepSeaCreatures.size,
            current = localCurrent?.title,
            companionName = companion?.displayName,
            depth = 38 + (player.y / DEEP_SEA_WORLD_HEIGHT * 212f).toInt(),
            modifier = Modifier.align(Alignment.TopCenter).padding(11.dp),
        )

        if (!completed && navigationTarget != null) {
            DeepSeaNavigationPanel(
                destination = if (gateReady) "归航门" else revealedTarget?.title.orEmpty(),
                delta = navigationTarget - player,
                distance = player.distanceTo(navigationTarget),
                routeDistance = navigationDistance,
                navigating = navigationDistance != null,
                onNavigate = {
                    val route = worldFollowPath(player, navigationTarget, DEEP_SEA_WORLD_BOUNDS, obstacles, 36f)
                    if (route.isEmpty()) notice = "目标方向被礁石挡住了，试着绕到开阔水域"
                    else {
                        navigationRoute = route
                        notice = if (gateReady) "归航航线已标出，跟随荧光轨迹前进" else "下一枚晶体的航线已标出"
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 110.dp, end = 11.dp),
            )
        }

        if (!completed) {
            Button(
                onClick = {
                    val baseSignals = deepSeaSonarFind(map, player, collected)
                    val companionSignals = if (companion == null) emptySet() else
                        deepSeaSonarFind(map, companionPosition, collected, DEEP_SEA_SONAR_RADIUS + 140f) - baseSignals
                    val assistedSignals = companionSignals - scanned
                    val newlyFound = (baseSignals + companionSignals) - scanned
                    scanned = scanned + newlyFound
                    sonarPulse = 1f
                    GameSoundEffects.play(GameSoundEffect.Interact)
                    if (assistedSignals.isNotEmpty()) companionAssists++
                    notice = when {
                        assistedSignals.isNotEmpty() && companion != null ->
                            "${companion.displayName}增强了声呐，额外发现 ${assistedSignals.size} 处回声"
                        newlyFound.isNotEmpty() && companion != null ->
                            "你和 ${companion.displayName}扫描到 ${newlyFound.size} 处回声信号"
                        newlyFound.isNotEmpty() -> "声呐定位到 ${newlyFound.size} 处新回声，选择导航前往"
                        else -> "附近暂无新回声，继续探索未测绘水域"
                    }
                    progress.save(player, collected, completed, score, scanned, visited, companionAssists, sessionCompanionId)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).height(38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xCC103B49), contentColor = Color(0xFFC5FFF1)),
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(Icons.Outlined.Radar, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("声呐扫描", fontSize = 11.sp)
            }
        }

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
                    nearbySite != null -> "探索"
                    gateReady -> "接近归航门"
                    else -> "搜索"
                },
                enabled = nearbyEcho != null || nearGate || nearbySite != null,
                onClick = {
                    when {
                        nearbyEcho != null -> {
                            collected = collected + nearbyEcho.id
                            scanned = scanned + nearbyEcho.id
                            lastPickupPosition = nearbyEcho.position
                            pickupPulse = 1f
                            navigationRoute = emptyList()
                            GameSoundEffects.play(if (collected.size == map.echoes.size) GameSoundEffect.Objective else GameSoundEffect.Collect)
                            notice = if (collected.size == map.echoes.size) "晶体集齐！右上方「导航前往」可带你找归航门" else "获得：${nearbyEcho.title}"
                            progress.save(player, collected, completed, score, scanned, visited, companionAssists, sessionCompanionId)
                        }
                        nearbySite != null -> {
                            visited = visited + nearbySite.id
                            GameSoundEffects.play(GameSoundEffect.Collect)
                            notice = "${nearbySite.name}：${deepSeaSiteStory(nearbySite, runStartedAt)}"
                            progress.save(player, collected, completed, score, scanned, visited, companionAssists, sessionCompanionId)
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
                                        if (visited.isNotEmpty()) append("，探索了 ${visited.size} 处海底遗迹")
                                        companion?.let { append("，${it.displayName}同行，实际协助声呐定位 ${companionAssists} 次") }
                                        append("。")
                                    },
                                    detailsJson = JSONObject()
                                        .put("game", "deep_sea_journey")
                                        .put("echoes", JSONArray(collected.toList()))
                                        .put("elapsed_seconds", elapsedSeconds)
                                        .put("sites_visited", JSONArray(visited.sorted()))
                                        .put("sonar_assists", if (companion == null) 0 else companionAssists)
                                        .put("session_companion_id", sessionCompanionId)
                                        .put("solo", companion == null)
                                        .toString(),
                                    characterIdOverride = companion?.characterId.orEmpty(),
                                    playedWithCharacterOverride = companion != null,
                                )
                                progress.markRecorded()
                                recorded = true
                            }
                            progress.save(player, collected, true, score, scanned, visited, companionAssists, sessionCompanionId)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 30.dp),
                accent = Color(0xFF9FFFF0),
            )
            val nearbyLabel = if (nearGate) "已到达归航门，点击归航"
                else nearbyEcho?.title ?: nearbySite?.name
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
                sitesVisited = visited.size,
                companionAssists = companionAssists,
                onRestart = {
                    progress.reset()
                    player = map.start
                    camera = WorldVector.Zero
                    cameraInitialized = false
                    joystick = WorldVector.Zero
                    companionPosition = map.start + WorldVector(-90f, 55f)
                    companionRoute = emptyList()
                    navigationRoute = emptyList()
                    swimVelocity = WorldVector.Zero
                    collected = emptySet()
                    scanned = emptySet()
                    visited = emptySet()
                    companionAssists = 0
                    pickupPulse = 0f
                    sonarPulse = 0f
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

/** Choose the nearest actual remaining echo; map order is not spatial order. */
internal fun deepSeaClosestEcho(map: DeepSeaMap, player: WorldVector, collected: Set<String>): DeepSeaEcho? =
    map.echoes.asSequence().filterNot { it.id in collected }
        .minByOrNull { player.distanceTo(it.position) }

@Composable
private fun DeepSeaNavigationPanel(
    destination: String,
    delta: WorldVector,
    distance: Float,
    routeDistance: Float?,
    navigating: Boolean,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heading = atan2(delta.y, delta.x) * 180f / Math.PI.toFloat()
    Surface(
        modifier = modifier.widthIn(max = 210.dp),
        color = Color(0xE80A2837),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF8FECD8).copy(alpha = .34f)),
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("声呐指引 · $destination", color = Color(0xFFE1FFF8), fontSize = 11.sp, fontWeight = FontWeight.Black)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ArrowForward, contentDescription = "目标方向",
                    tint = Color(0xFF9BFFE9),
                    modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = heading })
                Column {
                    Text("直线 ${distance.roundToInt()} 世界单位", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(routeDistance?.let { "避障航线约 ${it.roundToInt()} 世界单位" }
                        ?: if (navigating) "正在沿航线前进" else "导航可绘制避障航线",
                        color = Color(0xFF9DBEB8), fontSize = 9.sp)
                }
            }
            Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth().height(35.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAAFFE8), contentColor = Color(0xFF08252D))) {
                Icon(Icons.Outlined.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (navigating) "重新导航" else "导航前往", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DeepSeaHud(
    collected: Int,
    total: Int,
    distance: Float?,
    gateReady: Boolean,
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
                    Text(companionName?.let { "与 $it 共同探测海沟" } ?: "单人自由潜航", color = Color(0xFF93BFB8), fontSize = 8.5.sp)
                }
                Text("$collected / $total", color = Color(0xFFAFFFF0), fontSize = 19.sp, fontWeight = FontWeight.Black)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NearMe, null, tint = Color(0xFFE8C985), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    when {
                        gateReady -> "归航门已开启 · 直线 ${distance?.roundToInt() ?: 0} 世界单位"
                        distance == null -> "搜索回声晶体"
                        else -> "最近回声 · 直线 ${distance.roundToInt()} 世界单位"
                    },
                    color = Color(0xFFC7DCD7),
                    fontSize = 8.5.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (gateReady) "可以返航" else "声呐在线", color = Color(0xFF9EFFE9), fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun DeepSeaCompletion(
    score: Int,
    companionName: String?,
    sitesVisited: Int,
    companionAssists: Int,
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
            Text("带回 5 枚晶体 · 探索 $sitesVisited 处海底秘境",
                color = Color(0xFFE1EABF), fontSize = 10.sp)
            if (companionName != null && companionAssists > 0) {
                Text("$companionName 真实发现了 $companionAssists 次回声线索",
                    color = Color(0xFF9AE6DB), fontSize = 10.sp)
            }
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

internal data class DeepSeaSurvey(
    val revealedRelics: Set<String> = emptySet(),
    val salvagedRelics: Set<String> = emptySet(),
    val observedCreatures: Set<String> = emptySet(),
)

internal data class DeepSeaRestoredState(
    val position: WorldVector,
    val collected: Set<String>,
    val completed: Boolean,
    val score: Int,
    val startedAt: Long,
    val recorded: Boolean,
    val scanned: Set<String>,
    val visited: Set<String>,
    val companionAssists: Int,
    val sessionCompanionId: String,
    val survey: DeepSeaSurvey,
)

internal class DeepSeaJourneyProgress(context: Context) {
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
            scanned = prefs.getStringSet("scanned", emptySet()).orEmpty().toSet(),
            visited = prefs.getStringSet("visited", emptySet()).orEmpty().toSet(),
            companionAssists = prefs.getInt("companion_assists", 0).coerceAtLeast(0),
            sessionCompanionId = prefs.getString("session_companion_id", "").orEmpty(),
            survey = DeepSeaSurvey(
                revealedRelics = prefs.getStringSet("revealed_relics", emptySet()).orEmpty().toSet(),
                salvagedRelics = prefs.getStringSet("salvaged_relics", emptySet()).orEmpty().toSet(),
                observedCreatures = prefs.getStringSet("observed_creatures", emptySet()).orEmpty().toSet(),
            ),
        )
    }

    fun save(position: WorldVector, collected: Set<String>, completed: Boolean, score: Int,
        scanned: Set<String>, visited: Set<String>, companionAssists: Int,
        sessionCompanionId: String, survey: DeepSeaSurvey = DeepSeaSurvey()) {
        prefs.edit()
            .putFloat("x", position.x)
            .putFloat("y", position.y)
            .putStringSet("collected", collected.toSet())
            .putStringSet("scanned", scanned.toSet())
            .putStringSet("visited", visited.toSet())
            .putInt("companion_assists", companionAssists)
            .putString("session_companion_id", sessionCompanionId)
            .putStringSet("revealed_relics", survey.revealedRelics.toSet())
            .putStringSet("salvaged_relics", survey.salvagedRelics.toSet())
            .putStringSet("observed_creatures", survey.observedCreatures.toSet())
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
