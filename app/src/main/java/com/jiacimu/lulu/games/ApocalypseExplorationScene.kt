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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.WorldFirstExplorationMemory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ApocalypseTravelTarget(
    val name: String,
    val detail: String,
    val storyAnchor: Boolean = false,
)

@Composable
internal fun ApocalypseExplorationScene(
    modifier: Modifier,
    save: ApocalypseV3Save,
    party: List<CharacterSettings>,
    userName: String,
    userAvatarUri: String?,
    bottomInset: Dp,
    onMap: () -> Unit,
    onInventory: () -> Unit,
    onSuggestedAction: (String) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val travelTargets = remember(save.director.location, save.director.locations) {
        buildList {
            val storyLocation = save.director.location.ifBlank { "未知区域" }
            add(ApocalypseTravelTarget(storyLocation, "当前剧情所在区域", storyAnchor = true))
            save.director.locations
                .filter { it.unlocked && it.name.isNotBlank() }
                .forEach { location ->
                    if (none { it.name == location.name }) {
                        add(
                            ApocalypseTravelTarget(
                                name = location.name,
                                detail = location.detail.ifBlank { "已经在世界中确认过的位置" },
                            ),
                        )
                    }
                }
            if (none { it.name == "白榆气象观测站" }) {
                add(
                    ApocalypseTravelTarget(
                        name = "白榆气象观测站",
                        detail = "白榆市北部高海拔科研设施。雷达、通信、独立供电、生活区与净水设备使这里具备长期据守价值。",
                    ),
                )
            }
        }
    }
    val worldState = remember(context, save.id) { ApocalypseExplorationWorldState(context, save.id) }
    var activeLocation by remember(save.id, save.director.location, travelTargets.map { it.name }) {
        mutableStateOf(
            worldState.load(
                storyLocation = save.director.location.ifBlank { "未知区域" },
                knownLocations = travelTargets.map { it.name }.toSet(),
            ),
        )
    }
    var showTravelSheet by remember { mutableStateOf(false) }

    LaunchedEffect(activeLocation, save.director.location) {
        worldState.save(
            activeLocation = activeLocation,
            storyLocation = save.director.location.ifBlank { "未知区域" },
        )
    }

    val map = remember(activeLocation, save.director.tension) {
        buildApocalypseExplorationMap(activeLocation, save.director.tension)
    }
    val weatherStationSlice = remember(map.location) {
        map.location.contains("白榆气象观测站") || (map.location.contains("气象") && map.location.contains("观测站"))
    }
    val obstacles = remember(map) { map.objects.mapNotNull(ApocalypseRuinObject::obstacle) }
    val progress = remember(context, save.id, map.location) {
        ApocalypseExplorationProgress(context, save.id, map.location)
    }
    val restored = remember(progress, map) { progress.loadPosition(map.playerStart) }

    var player by remember(save.id, map.location) { mutableStateOf(restored) }
    var camera by remember(save.id, map.location) { mutableStateOf(WorldVector.Zero) }
    var joystick by remember(save.id, map.location) { mutableStateOf(WorldVector.Zero) }
    var tapTarget by remember(save.id, map.location) { mutableStateOf<WorldVector?>(null) }
    var facingX by remember { mutableFloatStateOf(1f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var exploredIds by remember(save.id, map.location) { mutableStateOf(progress.loadExplored()) }
    var threat by remember(save.id, map.location) { mutableStateOf(map.threatStart) }
    var threatTarget by remember(save.id, map.location) {
        mutableStateOf(if (weatherStationSlice) WorldVector(1_700f, 980f) else WorldVector(480f, 520f))
    }
    var nextThreatDecision by remember(save.id, map.location) { mutableLongStateOf(System.currentTimeMillis() + 3_000L) }
    var notice by remember(save.id, map.location) { mutableStateOf("") }
    var partyPositions by remember(save.id, map.location, party.map { it.characterId }) {
        mutableStateOf(
            party.mapIndexed { index, character ->
                character.characterId to (restored + companionOffset(index))
            }.toMap(),
        )
    }

    val ambience = rememberInfiniteTransition(label = "apocalypse-explore-ambience")
    val phase by ambience.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "apocalypse-explore-phase",
    )
    val pulse by ambience.animateFloat(
        initialValue = .72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_300), RepeatMode.Reverse),
        label = "apocalypse-threat-pulse",
    )

    val scale = (viewport.height.toFloat() / 660f).coerceAtLeast(.35f)
    val viewportWorldWidth = viewport.width.toFloat() / scale
    val viewportWorldHeight = viewport.height.toFloat() / scale
    val latestPlayer by rememberUpdatedState(player)
    val latestExplored by rememberUpdatedState(exploredIds)
    DisposableEffect(progress) {
        onDispose { progress.save(latestPlayer, latestExplored) }
    }
    LaunchedEffect(progress) {
        while (isActive) {
            delay(1_600)
            progress.save(player, exploredIds)
        }
    }
    LaunchedEffect(notice) {
        if (notice.isBlank()) return@LaunchedEffect
        delay(2_300)
        notice = ""
    }

    LaunchedEffect(save.id, map.location, weatherStationSlice, viewport, obstacles, party.map { it.characterId }) {
        var previousFrame = withFrameNanos { it }
        var lastDangerAt = 0L
        var lastBumpAt = 0L
        while (isActive) {
            withFrameNanos { frame ->
                val dt = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                previousFrame = frame
                val target = tapTarget
                val automatic = target?.let {
                    val delta = it - player
                    if (delta.length < 24f) {
                        tapTarget = null
                        WorldVector.Zero
                    } else delta.normalized()
                } ?: WorldVector.Zero
                val direction = if (joystick.length > .08f) joystick else automatic
                if (direction.length > .04f) {
                    if (joystick.length > .08f) tapTarget = null
                    if (abs(direction.x) > .04f) facingX = direction.x
                    val moved = moveInWorld(
                        player,
                        direction,
                        speed = 276f,
                        deltaSeconds = dt,
                        radius = 30f,
                        bounds = APOCALYPSE_WORLD_BOUNDS,
                        obstacles = obstacles,
                    )
                    player = moved.position
                    if (moved.collided) {
                        tapTarget = null
                        val now = System.currentTimeMillis()
                        if (now - lastBumpAt > 430L) {
                            GameSoundEffects.play(GameSoundEffect.Bump)
                            lastBumpAt = now
                        }
                    }
                }

                camera = camera.lerp(
                    worldCameraTarget(
                        player + direction.normalized() * 88f,
                        viewportWorldWidth,
                        viewportWorldHeight,
                        APOCALYPSE_WORLD_WIDTH,
                        APOCALYPSE_WORLD_HEIGHT,
                    ),
                    dt * 5.2f,
                )

                val now = System.currentTimeMillis()
                if (now >= nextThreatDecision || threat.distanceTo(threatTarget) < 28f) {
                    val epoch = now / 4_000L
                    val seed = (map.location.hashCode().toLong() * 79L + epoch * 263L) and Long.MAX_VALUE
                    threatTarget = if (weatherStationSlice) {
                        WorldVector(
                            1_565f + (seed % 205L).toFloat(),
                            875f + ((seed / 47L) % 185L).toFloat(),
                        )
                    } else {
                        WorldVector(
                            300f + (seed % 1_300L).toFloat(),
                            250f + ((seed / 47L) % 690L).toFloat(),
                        )
                    }
                    nextThreatDecision = now + if (weatherStationSlice) 5_600L else 3_700L
                }
                threat = moveInWorld(
                    threat,
                    threatTarget - threat,
                    speed = if (weatherStationSlice) 34f else 58f + save.director.tension * 4f,
                    deltaSeconds = dt,
                    radius = 34f,
                    bounds = APOCALYPSE_WORLD_BOUNDS,
                    obstacles = obstacles,
                ).position
                if (player.distanceTo(threat) < 145f && now - lastDangerAt > 2_300L) {
                    GameSoundEffects.play(GameSoundEffect.Bump)
                    notice = if (weatherStationSlice) "外围有威胁贴近围栏，先回到站区内侧" else "危险正在接近，利用障碍拉开距离"
                    lastDangerAt = now
                }

                partyPositions = party.mapIndexed { index, character ->
                    val current = partyPositions[character.characterId] ?: player + companionOffset(index)
                    val desired = player + rotatedCompanionOffset(index, facingX)
                    character.characterId to current.lerp(desired, dt * (2.7f + index * .12f))
                }.toMap()
            }
        }
    }

    val nearestObject = map.objects
        .minByOrNull { player.distanceTo(it.bounds.center) }
        ?.takeIf { player.distanceTo(it.bounds.center) <= 158f }
    val objectiveKinds = if (weatherStationSlice) {
        setOf(
            ApocalypseRuinKind.StationRoom,
            ApocalypseRuinKind.Equipment,
            ApocalypseRuinKind.Radar,
            ApocalypseRuinKind.Cache,
            ApocalypseRuinKind.Wreck,
            ApocalypseRuinKind.Barricade,
            ApocalypseRuinKind.Exit,
        )
    } else {
        setOf(ApocalypseRuinKind.Cache, ApocalypseRuinKind.Anomaly, ApocalypseRuinKind.Exit)
    }
    val nextObjective = map.objects.firstOrNull {
        it.id !in exploredIds && it.kind in objectiveKinds
    }
    val objectiveText = when {
        nextObjective != null && weatherStationSlice -> "恢复观测站：${nextObjective.label}"
        nextObjective != null -> "靠近并调查：${nextObjective.label}"
        weatherStationSlice -> "观测站关键区域已巡查，可以安排修复、值守、外出补给或休息"
        activeLocation != save.director.location -> "自由探索 ${map.location}，调查环境并决定是否把发现带回剧情"
        else -> save.director.sceneGoal.ifBlank { "在当前区域自由侦察，决定下一步行动" }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF07110F))
            .onSizeChanged { viewport = it },
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(save.id, map.location, viewport) {
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
                drawApocalypseExplorationWorld(map, phase, save.director.tension, exploredIds, threat)
            }

            val flashlightCenter = Offset(
                (player.x - camera.x) * scale,
                (player.y - camera.y) * scale - 22.dp.toPx(),
            )
            val beamLength = size.width * .56f
            val beam = Path().apply {
                moveTo(flashlightCenter.x, flashlightCenter.y - 8.dp.toPx())
                lineTo(flashlightCenter.x + facingX * beamLength, flashlightCenter.y - size.height * .19f)
                lineTo(flashlightCenter.x + facingX * beamLength, flashlightCenter.y + size.height * .23f)
                close()
            }
            drawPath(
                beam,
                Brush.linearGradient(
                    listOf(Color(0xFFDDE9D0).copy(alpha = .12f), Color(0xFFC9E0D2).copy(alpha = .035f), Color.Transparent),
                    start = flashlightCenter,
                    end = Offset(flashlightCenter.x + facingX * beamLength, flashlightCenter.y),
                ),
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFCEE7D9).copy(alpha = .11f), Color.Transparent),
                    center = flashlightCenter,
                    radius = size.minDimension * .38f,
                ),
                size.minDimension * .38f,
                flashlightCenter,
            )

            nearestObject?.let { target ->
                val point = Offset((target.bounds.center.x - camera.x) * scale, (target.bounds.center.y - camera.y) * scale)
                drawCircle(
                    Color(0xFFF2D981).copy(alpha = .18f + pulse * .14f),
                    37.dp.toPx() + pulse * 4.dp.toPx(),
                    point,
                )
                drawCircle(
                    Color(0xFFFFE9A0).copy(alpha = .75f),
                    27.dp.toPx(),
                    point,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                )
            }
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color(0xC9050D0C)),
                    center = Offset(size.width * .5f, size.height * .46f),
                    radius = size.maxDimension * .70f,
                ),
            )

            repeat(32) { index ->
                val seed = (index * 379 + map.location.hashCode()) and Int.MAX_VALUE
                val x = ((seed % 1_100) / 1_100f * size.width + phase * size.width * .13f) % size.width
                val y = (((seed / 29) % 1_000) / 1_000f * size.height + phase * size.height) % size.height
                if (save.director.weather.contains("雨")) {
                    drawLine(Color(0xFFB9D5CF).copy(alpha = .18f), Offset(x, y), Offset(x - 11.dp.toPx(), y + 28.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
                } else {
                    drawCircle(Color(0xFFC3D0C9).copy(alpha = .12f), (1 + index % 3).dp.toPx(), Offset(x, y))
                }
            }

            repeat(5) { index ->
                val y = size.height * (.18f + index * .17f) + (phase * 42.dp.toPx() + index * 23.dp.toPx()) % 58.dp.toPx()
                drawOval(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0xFFB5C6BD).copy(alpha = .045f + index * .008f), Color.Transparent),
                    ),
                    topLeft = Offset(-size.width * .15f, y),
                    size = androidx.compose.ui.geometry.Size(size.width * 1.3f, 42.dp.toPx() + index * 5.dp.toPx()),
                )
            }

            val threatDistance = player.distanceTo(threat)
            val danger = ((310f - threatDistance) / 220f).coerceIn(0f, 1f)
            if (danger > 0f) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color(0xFFB7191D).copy(alpha = danger * (.22f + pulse * .12f))),
                        center = Offset(size.width * .5f, size.height * .46f),
                        radius = size.maxDimension * .68f,
                    ),
                )
            }

            drawCircle(Color(0xFF020605).copy(alpha = .60f), 92.dp.toPx(), Offset(-18.dp.toPx(), size.height * .86f))
            drawCircle(Color(0xFF020605).copy(alpha = .55f), 68.dp.toPx(), Offset(size.width + 8.dp.toPx(), size.height * .78f))
        }

        val halfPawn = with(density) { 31.dp.toPx() }
        val foot = with(density) { 84.dp.toPx() }
        party.forEachIndexed { index, character ->
            val position = partyPositions[character.characterId] ?: player + companionOffset(index)
            val x = (position.x - camera.x) * scale
            val y = (position.y - camera.y) * scale
            GameCharacterPawn(
                avatarUri = character.avatarUri,
                fallback = character.displayName.take(1),
                moving = joystick.length > .08f || tapTarget != null,
                facingX = facingX,
                coat = Color(0xFF505C58),
                modifier = Modifier
                    .offset { IntOffset((x - halfPawn).roundToInt(), (y - foot).roundToInt()) }
                    .zIndex(position.y),
            )
        }
        val playerX = (player.x - camera.x) * scale
        val playerY = (player.y - camera.y) * scale
        GameCharacterPawn(
            avatarUri = userAvatarUri,
            fallback = userName.take(1).ifBlank { "我" },
            moving = joystick.length > .08f || tapTarget != null,
            facingX = facingX,
            coat = Color(0xFF243C37),
            player = true,
            modifier = Modifier
                .offset { IntOffset((playerX - halfPawn).roundToInt(), (playerY - foot).roundToInt()) }
                .zIndex(player.y + .5f),
        )

        ApocalypseExploreHud(
            location = map.location,
            goal = objectiveText,
            threatDistance = player.distanceTo(threat),
            explored = exploredIds.count { exploredId -> map.objects.any { it.id == exploredId && it.kind in objectiveKinds } },
            total = map.objects.count { it.kind in objectiveKinds },
            onMap = onMap,
            onTravel = { showTravelSheet = true },
            onInventory = onInventory,
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 7.dp),
        )

        AnimatedVisibility(
            visible = notice.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 38.dp),
        ) {
            Surface(
                color = Color(0xEA210F0F),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFFF7E78).copy(alpha = .65f)),
                shadowElevation = 10.dp,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CrisisAlert, null, tint = Color(0xFFFF8C86), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(notice, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        WorldVirtualJoystick(
            value = joystick,
            onValueChanged = { next ->
                if (joystick.length <= .05f && next.length > .05f) GameSoundEffects.play(GameSoundEffect.Move)
                joystick = next
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 13.dp, bottom = bottomInset),
            tint = Color(0xFFCFDFD7),
        )
        WorldActionButton(
            label = when (nearestObject?.kind) {
                ApocalypseRuinKind.Cache -> "搜索"
                ApocalypseRuinKind.Anomaly -> "感知"
                ApocalypseRuinKind.Exit -> "深入"
                ApocalypseRuinKind.Radar -> "校验"
                ApocalypseRuinKind.Equipment -> "检修"
                ApocalypseRuinKind.StationRoom -> "进入"
                ApocalypseRuinKind.Wreck, ApocalypseRuinKind.Barricade -> "检查"
                null -> "靠近"
                else -> "调查"
            },
            enabled = nearestObject != null,
            onClick = {
                nearestObject?.let { target ->
                    val firstVisit = target.id !in exploredIds
                    exploredIds = exploredIds + target.id
                    GameSoundEffects.play(if (firstVisit) GameSoundEffect.Objective else GameSoundEffect.Interact)
                    notice = if (firstVisit) "已记录线索：${target.label}" else target.label
                    if (firstVisit) {
                        WorldFirstExplorationMemory.record(
                            context = context,
                            worldId = "apocalypse:${save.id}",
                            locationId = map.location,
                            locationLabel = map.location,
                            action = target.action,
                        )
                    }
                    onSuggestedAction(target.action)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 21.dp, bottom = bottomInset + 12.dp),
            accent = if (nearestObject != null) Color(0xFFE7D08B) else Color(0xFFA5B7B1),
        )

        if (nearestObject != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomInset + 98.dp),
                color = Color(0xD90A1311),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
            ) {
                Text(nearestObject.label, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), maxLines = 1)
            }
        }
    }

    if (showTravelSheet) {
        ApocalypseTravelSheet(
            targets = travelTargets,
            activeLocation = activeLocation,
            onDismiss = { showTravelSheet = false },
            onTravel = { target ->
                val from = activeLocation
                val travelAction = "我离开$from，沿已经确认的路线前往${target.name}。抵达后先观察周围环境、威胁和可利用的入口，再决定下一步。"
                activeLocation = target.name
                showTravelSheet = false
                GameSoundEffects.play(GameSoundEffect.Objective)
                WorldFirstExplorationMemory.record(
                    context = context,
                    worldId = "apocalypse:${save.id}",
                    locationId = target.name,
                    locationLabel = target.name,
                    action = travelAction,
                )
                onSuggestedAction(travelAction)
            },
        )
    }
}

@Composable
private fun ApocalypseExploreHud(
    location: String,
    goal: String,
    threatDistance: Float,
    explored: Int,
    total: Int,
    onMap: () -> Unit,
    onTravel: () -> Unit,
    onInventory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xDD0C1513),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(location, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Flag, null, tint = Color(0xFFE5CE89), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(goal, color = Color(0xFFC9D7D1), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Surface(onClick = onMap, color = Color.White.copy(alpha = .08f), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Outlined.Map, "大地图", tint = Color.White, modifier = Modifier.padding(9.dp).size(18.dp))
                }
                Spacer(Modifier.width(5.dp))
                Surface(onClick = onTravel, color = Color(0xFFE5CE89).copy(alpha = .13f), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Outlined.Route, "前往已发现地点", tint = Color(0xFFF0D994), modifier = Modifier.padding(9.dp).size(18.dp))
                }
                Spacer(Modifier.width(5.dp))
                Surface(onClick = onInventory, color = Color.White.copy(alpha = .08f), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Outlined.Inventory2, "物资", tint = Color.White, modifier = Modifier.padding(9.dp).size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Sensors, null, tint = if (threatDistance < 220f) Color(0xFFFF7771) else Color(0xFF91B6AA), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (threatDistance < 160f) "威胁极近" else if (threatDistance < 300f) "侦测到移动威胁" else "周围暂时可控",
                    color = if (threatDistance < 220f) Color(0xFFFF9D98) else Color(0xFF9DBCB2),
                    fontSize = 8.sp,
                )
                Spacer(Modifier.weight(1f))
                Text("探索 $explored/${total.coerceAtLeast(1)}", color = Color(0xFFE5CE89), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ApocalypseTravelSheet(
    targets: List<ApocalypseTravelTarget>,
    activeLocation: String,
    onDismiss: () -> Unit,
    onTravel: (ApocalypseTravelTarget) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101714),
        contentColor = Color.White,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFE5CE89).copy(alpha = .13f), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Outlined.Explore, null, tint = Color(0xFFF0D994), modifier = Modifier.padding(10.dp).size(21.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("已存在的世界", fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Text("去已经发现过的地点，不必等待下一幕替你传送", color = Color(0xFFAEBDB5), fontSize = 10.sp)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                targets.forEach { target ->
                    val active = target.name == activeLocation
                    Surface(
                        onClick = { if (!active) onTravel(target) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (active) Color(0xFF26372F) else Color(0xFF19231F),
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(
                            1.dp,
                            if (active) Color(0xFFE5CE89).copy(alpha = .46f) else Color.White.copy(alpha = .10f),
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (active) Icons.Outlined.MyLocation else Icons.Outlined.Place,
                                null,
                                tint = if (active) Color(0xFFF0D994) else Color(0xFF9FB4AA),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(target.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (target.storyAnchor) {
                                        Text("剧情位置", color = Color(0xFFB9CBBF), fontSize = 8.sp)
                                    } else if (active) {
                                        Text("你在这里", color = Color(0xFFF0D994), fontSize = 8.sp)
                                    }
                                }
                                Text(target.detail, color = Color(0xFFA9B7B0), fontSize = 9.5.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (!active) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Outlined.ChevronRight, null, tint = Color(0xFF81928A), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Text(
                "每个地点的站位和已调查目标会分别保存。你换地方探索后，下一次剧情输入会带上真实移动和调查行动。",
                color = Color(0xFF8FA198),
                fontSize = 9.5.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

private fun companionOffset(index: Int): WorldVector = when (index % 4) {
    0 -> WorldVector(-82f, 54f)
    1 -> WorldVector(82f, 62f)
    2 -> WorldVector(-132f, 104f)
    else -> WorldVector(132f, 108f)
}

private fun rotatedCompanionOffset(index: Int, facingX: Float): WorldVector {
    val base = companionOffset(index)
    return WorldVector(if (facingX < 0f) -base.x else base.x, base.y)
}

private class ApocalypseExplorationProgress(context: Context, saveId: String, location: String) {
    private val prefs = context.applicationContext.getSharedPreferences("apocalypse_exploration_v1", Context.MODE_PRIVATE)
    private val suffix = ((saveId + location).hashCode() and Int.MAX_VALUE).toString(36)

    fun loadPosition(fallback: WorldVector): WorldVector = WorldVector(
        prefs.getFloat("x_$suffix", fallback.x).coerceIn(APOCALYPSE_WORLD_BOUNDS.left + 34f, APOCALYPSE_WORLD_BOUNDS.right - 34f),
        prefs.getFloat("y_$suffix", fallback.y).coerceIn(APOCALYPSE_WORLD_BOUNDS.top + 34f, APOCALYPSE_WORLD_BOUNDS.bottom - 34f),
    )

    fun loadExplored(): Set<String> = prefs.getStringSet("seen_$suffix", emptySet()).orEmpty().toSet()

    fun save(position: WorldVector, explored: Set<String>) {
        prefs.edit()
            .putFloat("x_$suffix", position.x)
            .putFloat("y_$suffix", position.y)
            .putStringSet("seen_$suffix", explored.toSet())
            .apply()
    }
}

private class ApocalypseExplorationWorldState(context: Context, saveId: String) {
    private val prefs = context.applicationContext.getSharedPreferences("apocalypse_exploration_world_v1", Context.MODE_PRIVATE)
    private val suffix = (saveId.hashCode() and Int.MAX_VALUE).toString(36)

    fun load(storyLocation: String, knownLocations: Set<String>): String {
        val previousStoryAnchor = prefs.getString("story_$suffix", null)
        val previousActive = prefs.getString("active_$suffix", null)
        return if (
            previousStoryAnchor == storyLocation &&
            !previousActive.isNullOrBlank() &&
            previousActive in knownLocations
        ) {
            previousActive
        } else {
            storyLocation
        }
    }

    fun save(activeLocation: String, storyLocation: String) {
        prefs.edit()
            .putString("active_$suffix", activeLocation)
            .putString("story_$suffix", storyLocation)
            .apply()
    }
}
