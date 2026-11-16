package com.jiacimu.lulu

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import com.jiacimu.lulu.data.CharacterSettings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.WorldFirstExplorationMemory
import com.jiacimu.lulu.games.GameSoundEffect
import com.jiacimu.lulu.games.GameSoundEffects
import com.jiacimu.lulu.games.WorldActionButton
import com.jiacimu.lulu.games.WorldVector
import com.jiacimu.lulu.games.WorldRectangle
import com.jiacimu.lulu.games.WorldObstacle
import com.jiacimu.lulu.games.moveInWorld
import com.jiacimu.lulu.games.worldFollowPath
import com.jiacimu.lulu.games.WorldVirtualJoystick
import kotlinx.coroutines.isActive

/** All doors lead to existing authoritative scenes, never to invented room copies. */
internal data class DigitalStreetAddress(val code: String, val label: String)

private data class StreetDoor(val address: DigitalStreetAddress, val point: WorldVector) {
    // The building footprint is solid; its door is approached from the sidewalk.
    val entrance = WorldVector(point.x, point.y + 112f)
    val obstacle = WorldObstacle(address.code, WorldRectangle(
        point.x - 145f, point.y - 104f, point.x + 145f, point.y + 67f),
        address.label, "沿人行道走到真正的建筑入口")
}
private data class StreetDiscovery(val id: String, val label: String, val description: String, val point: WorldVector)

private val streetDiscoveries = listOf(
    StreetDiscovery("old-book-stall", "旧书摊", "街角的旧书摊留着一些泛黄书签。", WorldVector(625f, 590f)),
    StreetDiscovery("wishing-tree", "许愿树", "树枝间挂着来访者留下的手写心愿。", WorldVector(650f, 1_170f)),
    StreetDiscovery("viewpoint", "观景台", "走到小镇高处，能望见屋顶与远处的云。", WorldVector(630f, 1_850f)),
)

@Composable
internal fun DigitalWorldWalkableStreet(
    addresses: List<DigitalStreetAddress>,
    onEnterScene: (String) -> Unit,
    onReturnToAtlas: () -> Unit,
    modifier: Modifier = Modifier,
    companions: List<CharacterSettings> = emptyList(),
    hudTopPadding: Dp = 10.dp,
    canEnterScene: Boolean = true,
    spawnAtLocationCode: String? = null,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("lulu_walkable_street_v1", android.content.Context.MODE_PRIVATE)
    }
    val doors = remember(addresses) {
        addresses.distinctBy { it.code }.mapIndexed { index, address ->
            StreetDoor(address, WorldVector(if (index % 2 == 0) 270f else 1_010f, 365f + index / 2 * 370f))
        }
    }
    val worldHeight = maxOf(2_380f, 690f + ((doors.size + 1) / 2) * 370f)
    val worldWidth = 1_280f
    val walkBounds = remember(worldHeight) { WorldRectangle(86f, 90f, worldWidth - 86f, worldHeight - 100f) }
    val buildingObstacles = remember(doors) { doors.map { it.obstacle } }
    val doorwaySpawn = remember(doors, spawnAtLocationCode) {
        doors.firstOrNull { it.address.code == spawnAtLocationCode }?.let { door ->
            // Spawn on the public path just outside the exact building exited.
            WorldVector(if (door.point.x < 640f) 600f else 680f, door.point.y + 28f)
        }
    }
    var player by remember(spawnAtLocationCode) {
        mutableStateOf(doorwaySpawn ?: WorldVector(
            preferences.getFloat("player_x", 650f).coerceIn(80f, 1_200f),
            preferences.getFloat("player_y", 180f).coerceIn(90f, worldHeight - 100f),
        ))
    }
    val latestPlayer by rememberUpdatedState(player)
    // Share this single physical observation stream with the EXISTING meeting
    // brain. This is local scene sensing, not an additional billable LLM loop.
    var streetLiveToken by remember { mutableStateOf("") }
    DisposableEffect(companions.map { it.characterId }) {
        val token = if (companions.isNotEmpty())
            com.jiacimu.lulu.data.DigitalWorldLiveScene.begin(
                context, com.jiacimu.lulu.data.DigitalWorldStore.ARRIVAL, "云上小镇")
        else ""
        streetLiveToken = token
        onDispose { com.jiacimu.lulu.data.DigitalWorldLiveScene.end(token) }
    }
    LaunchedEffect(streetLiveToken, companions.map { it.characterId }) {
        if (streetLiveToken.isBlank()) return@LaunchedEffect
        var previous = latestPlayer
        while (isActive) {
            val current = latestPlayer
            val nearest = doors.minByOrNull { current.distanceTo(it.point) }
            val nearName = nearest?.takeIf { current.distanceTo(it.point) < 235f }
                ?.address?.label
            val moving = current.distanceTo(previous) > 20f
            com.jiacimu.lulu.data.DigitalWorldLiveScene.update(
                streetLiveToken, current.x, current.y,
                companions.map { it.characterId }.toSet(),
                if (nearName != null) "在云上小镇的${nearName}附近，${if (moving) "正在散步" else "暂时停留"}"
                else "在云上小镇街道上，${if (moving) "正在散步" else "暂时停留"}",
            )
            previous = current
            kotlinx.coroutines.delay(1_600L)
        }
    }
    DisposableEffect(preferences) {
        onDispose {
            preferences.edit().putFloat("player_x", latestPlayer.x)
                .putFloat("player_y", latestPlayer.y).apply()
        }
    }
    var visited by remember { mutableStateOf(preferences.getStringSet("found", emptySet()).orEmpty().toSet()) }
    val mementoKey = remember(companions.map { it.characterId }) {
        "memento:" + DigitalStreetJourney.participantKey(companions.map { it.characterId })
    }
    var memento by remember(mementoKey) { mutableStateOf(preferences.getString(mementoKey, "").orEmpty()) }
    var showMemento by remember { mutableStateOf(false) }
    var revealClue by remember { mutableStateOf<StreetDiscovery?>(null) }
    val mementoPoint = remember { WorldVector(635f, 1_545f) }
    var companionPositions by remember(companions.map { it.characterId }, spawnAtLocationCode) {
        mutableStateOf(companions.mapIndexed { index, _ ->
            WorldVector(
                (player.x + 78f + index * 36f).coerceIn(90f, worldWidth - 90f),
                (player.y + 70f + index * 36f).coerceIn(90f, worldHeight - 95f),
            )
        })
    }
    var joystick by remember { mutableStateOf(WorldVector.Zero) }
    var tapTarget by remember { mutableStateOf<WorldVector?>(null) }
    var tapPath by remember { mutableStateOf<List<WorldVector>>(emptyList()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scale = if (viewport.width > 0) viewport.width / worldWidth else 1f
    val visibleHeight = if (scale > 0f) viewport.height / scale else worldHeight
    val cameraY = (player.y - visibleHeight * .48f).coerceIn(0f, (worldHeight - visibleHeight).coerceAtLeast(0f))
    val nearestDoor = doors.minByOrNull { player.distanceTo(it.entrance) }
        ?.takeIf { player.distanceTo(it.entrance) < 112f }
    val nearestDiscovery = streetDiscoveries
        .filter { it.id in DigitalStreetJourney.visibleClues(visited) }
        .minByOrNull { player.distanceTo(it.point) }
        ?.takeIf { player.distanceTo(it.point) < 118f }
    val nearMemento = DigitalStreetJourney.isMementoUnlocked(visited) &&
        player.distanceTo(mementoPoint) < 130f
    val titlePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 39f
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.rgb(34, 62, 50)
            isFakeBoldText = true
        }
    }

    LaunchedEffect(Unit) {
        var lastFrame = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { frame ->
                val dt = ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                lastFrame = frame
                while (tapPath.isNotEmpty() && player.distanceTo(tapPath.first()) < 18f) {
                    tapPath = tapPath.drop(1)
                }
                val targetDirection = if (tapPath.isEmpty()) WorldVector.Zero
                    else (tapPath.first() - player).normalized()
                if (tapPath.isEmpty()) tapTarget = null
                val direction = if (joystick.length > .08f) {
                    tapTarget = null
                    tapPath = emptyList()
                    joystick
                } else targetDirection
                if (direction.length > .04f) {
                    val moved = moveInWorld(
                        player, direction, 310f, dt, 34f, walkBounds, buildingObstacles)
                    player = moved.position
                    if (moved.collided && tapTarget != null) {
                        // No phantom sliding through a wall after a blocked route.
                        tapTarget = null
                        tapPath = emptyList()
                    }
                }
                if (companionPositions.isNotEmpty()) {
                    val before = companionPositions
                    companionPositions = before.mapIndexed { index, position ->
                        val leader = if (index == 0) player else before[index - 1]
                        val desired = WorldVector(
                            (leader.x + if (index % 2 == 0) 80f else -80f).coerceIn(90f, worldWidth - 90f),
                            (leader.y + 85f).coerceIn(90f, worldHeight - 95f),
                        )
                        val toLeader = desired - position
                        if (toLeader.length <= 10f) position
                        else position + toLeader.normalized() * minOf(toLeader.length, dt * 300f)
                    }
                }
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFFBBD2C4)).onSizeChanged { viewport = it }) {
        Canvas(Modifier.fillMaxSize().pointerInput(scale, cameraY, buildingObstacles) {
            detectTapGestures { point ->
                val target = WorldVector(
                    (point.x / scale).coerceIn(86f, worldWidth - 86f),
                    (point.y / scale + cameraY).coerceIn(90f, worldHeight - 100f),
                )
                val route = worldFollowPath(player, target, walkBounds, buildingObstacles, 34f)
                if (route.isNotEmpty()) {
                    tapTarget = target
                    tapPath = route
                    GameSoundEffects.play(GameSoundEffect.Move)
                }
            }
        }) {
            withTransform({
                translate(0f, -cameraY * scale)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawRect(Brush.verticalGradient(
                    listOf(Color(0xFFD9E7D7), Color(0xFFBBD8C3), Color(0xFFA5C8B7)),
                    startY = 0f, endY = worldHeight), size = Size(worldWidth, worldHeight))
                drawRoundRect(Color(0xFF91B8A2), Offset(550f, 55f),
                    Size(185f, worldHeight - 110f), CornerRadius(90f))
                drawRoundRect(Color(0xFFF3EBD7), Offset(602f, 65f),
                    Size(81f, worldHeight - 130f), CornerRadius(38f))
                doors.forEachIndexed { index, door ->
                    val p = door.point
                    val left = index % 2 == 0
                    val from = if (left) Offset(p.x + 115f, p.y + 24f) else Offset(p.x - 115f, p.y + 24f)
                    val to = Offset(if (left) 610f else 675f, p.y + 24f)
                    drawLine(Color(0xFFF4E9D2), from, to, 34f, cap = StrokeCap.Round)
                    drawOval(Color(0xFF547E69).copy(alpha = .20f),
                        Offset(p.x - 151f, p.y + 78f), Size(302f, 38f))
                    drawRoundRect(Color(0xFFF7EFE3), Offset(p.x - 132f, p.y - 69f),
                        Size(264f, 157f), CornerRadius(20f))
                    drawRoundRect(if (left) Color(0xFF77978B) else Color(0xFF8BA9B1),
                        Offset(p.x - 147f, p.y - 99f), Size(294f, 75f), CornerRadius(16f))
                    drawRoundRect(Color(0xFF90745F), Offset(p.x - 27f, p.y + 12f),
                        Size(54f, 76f), CornerRadius(8f))
                    drawCircle(Color(0xFFEBD08E), 5f, Offset(p.x + 14f, p.y + 50f))
                    drawContext.canvas.nativeCanvas.drawText(
                        door.address.label.take(11), p.x, p.y - 106f, titlePaint)
                }
                streetDiscoveries.filter {
                    it.id in DigitalStreetJourney.visibleClues(visited)
                }.forEach { discovery ->
                    val p = discovery.point
                    val found = discovery.id in visited
                    drawCircle(Color(0xFF476F5D).copy(alpha = .17f), 66f, Offset(p.x, p.y + 30f))
                    drawCircle(if (found) Color(0xFF7A9D82) else Color(0xFFEBC88B),
                        42f, Offset(p.x, p.y))
                    drawCircle(Color.White.copy(alpha = if (found) .30f else .75f),
                        10f, Offset(p.x, p.y - 13f))
                    drawContext.canvas.nativeCanvas.drawText(
                        discovery.label, p.x, p.y - 63f, titlePaint)
                }
                if (DigitalStreetJourney.isMementoUnlocked(visited)) {
                    val p = mementoPoint
                    drawOval(Color(0xFF365B49).copy(alpha = .25f),
                        Offset(p.x - 57f, p.y + 53f), Size(114f, 26f))
                    drawRoundRect(Color(0xFFF8EDCF), Offset(p.x - 45f, p.y - 25f),
                        Size(90f, 95f), CornerRadius(12f))
                    drawRoundRect(Color(0xFFA67862), Offset(p.x - 39f, p.y + 5f),
                        Size(78f, 14f), CornerRadius(3f))
                    drawCircle(if (memento.isBlank()) Color(0xFFE8C487) else Color(0xFF90D0AC),
                        13f, Offset(p.x, p.y - 9f))
                    drawContext.canvas.nativeCanvas.drawText(
                        if (memento.isBlank()) "纪念邮筒" else "我们的旅行卡",
                        p.x, p.y - 47f, titlePaint)
                }
                val you = Offset(player.x, player.y)
                drawOval(Color(0xFF395B4F).copy(alpha = .26f),
                    Offset(you.x - 30f, you.y + 18f), Size(60f, 16f))
                drawCircle(Color(0xFFF5D5B9), 30f, Offset(you.x, you.y - 30f))
                drawCircle(Color(0xFF547E72), 27f, you)
                drawCircle(Color(0xFFF6FFF0), 7f, Offset(you.x - 9f, you.y - 6f))
                companions.take(3).forEachIndexed { index, person ->
                    val body = companionPositions.getOrNull(index) ?: player
                    val companion = Offset(body.x, body.y)
                    drawOval(Color(0xFF456254).copy(alpha = .17f),
                        Offset(companion.x - 27f, companion.y + 12f), Size(54f, 16f))
                    drawCircle(Color(0xFF8DA6A0), 27f, companion)
                    drawCircle(Color(0xFFFFE0C5), 25f, Offset(companion.x, companion.y - 30f))
                    drawContext.canvas.nativeCanvas.drawText(
                        person.displayName.take(4), companion.x, companion.y - 66f, titlePaint)
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(horizontal = 12.dp).padding(top = hudTopPadding, bottom = 10.dp),
            color = Color(0xED172B25),
            shape = RoundedCornerShape(19.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("云上小镇 · 自由散步", color = Color.White, fontSize = 15.sp)
                    Text(DigitalStreetJourney.progressText(visited),
                        color = Color(0xFFBDD4C6), fontSize = 10.sp)
                }
                TextButton(onClick = onReturnToAtlas) { Text("总览", color = Color(0xFFBFECD6)) }
            }
        }
        WorldVirtualJoystick(
            value = joystick,
            onValueChanged = { joystick = it },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 22.dp),
            tint = Color(0xFFC4FFE4),
        )
        WorldActionButton(
            label = if (nearMemento) { if (memento.isBlank()) "留纪念" else "翻看" }
                else nearestDiscovery?.let { if (it.id in visited) "回顾" else "探索" }
                    ?: nearestDoor?.let { if (canEnterScene) "进入" else "等待" } ?: "靠近",
            enabled = nearMemento || nearestDiscovery != null || (nearestDoor != null && canEnterScene),
            onClick = {
                val clue = nearestDiscovery
                val door = nearestDoor
                if (nearMemento) {
                    if (memento.isBlank()) {
                        val names = listOf("我") + companions.map { it.displayName }
                        val note = DigitalStreetJourney.mementoText(
                            names, java.time.LocalDate.now().toString())
                        if (preferences.edit().putString(mementoKey, note).commit()) {
                            memento = note
                            WorldFirstExplorationMemory.record(
                                context = context, worldId = "digital-world",
                                locationId = "street:${DigitalStreetJourney.MEMENTO}",
                                locationLabel = "云上小镇 · 纪念邮筒",
                                action = "在同一真实场景探索了三个街道线索后，${names.joinToString("、")}一起来到纪念邮筒，亲手留下并保存了旅行卡；文字为：$note",
                            )
                        }
                    }
                    showMemento = memento.isNotBlank()
                } else if (clue != null) {
                    if (clue.id in visited) revealClue = clue
                    else {
                        val updated = visited + clue.id
                        if (preferences.edit().putStringSet("found", updated).commit()) {
                            visited = updated
                            WorldFirstExplorationMemory.record(
                                context = context, worldId = "digital-world",
                                locationId = "street:${clue.id}",
                                locationLabel = "云上小镇",
                                action = "步行抵达${clue.label}，亲自发现：${clue.description}",
                            )
                            revealClue = clue
                        }
                    }
                } else if (door != null) {
                    preferences.edit().putFloat("player_x", player.x)
                        .putFloat("player_y", player.y).apply()
                    onEnterScene(door.address.code)
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 19.dp, bottom = 30.dp),
            accent = Color(0xFFE8F7D9),
        )
        if (nearMemento || nearestDoor != null || nearestDiscovery != null) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 120.dp, start = 85.dp, end = 85.dp),
                color = Color(0xE5172B25), shape = RoundedCornerShape(12.dp)) {
                Text(if (nearMemento) "纪念邮筒 · 三处线索的共同纪念"
                    else nearestDiscovery?.let { "${it.label} · ${it.description}" }
                        ?: nearestDoor?.let { "${it.address.label} · 已走到入口" }.orEmpty(),
                    color = Color(0xFFE8F4EA), fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
    revealClue?.let { found ->
        AlertDialog(
            onDismissRequest = { revealClue = null },
            title = { Text("探索 · ${found.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(found.description)
                    Text(DigitalStreetJourney.progressText(visited))
                }
            },
            confirmButton = { TextButton(onClick = { revealClue = null }) { Text("继续散步") } },
        )
    }
    if (showMemento) {
        AlertDialog(
            onDismissRequest = { showMemento = false },
            title = { Text("云上小镇 · 旅行卡") },
            text = {
                Surface(shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF8EFDD)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("三个地方，一段真实走过的路", color = Color(0xFF486C58), fontSize = 14.sp)
                        Text(memento, color = Color(0xFF38463C), fontSize = 12.sp)
                        Text("这张卡会留在邮筒里，下次来到这里还能翻看。",
                            color = Color(0xFF829185), fontSize = 10.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMemento = false }) { Text("收好") } },
        )
    }

}
