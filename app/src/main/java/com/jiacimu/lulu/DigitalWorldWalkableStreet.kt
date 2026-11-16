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
import com.jiacimu.lulu.games.WorldVirtualJoystick
import kotlinx.coroutines.isActive

/** All doors lead to existing authoritative scenes, never to invented room copies. */
internal data class DigitalStreetAddress(val code: String, val label: String)

private data class StreetDoor(val address: DigitalStreetAddress, val point: WorldVector)
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
    var player by remember {
        mutableStateOf(WorldVector(
            preferences.getFloat("player_x", 650f).coerceIn(80f, 1_200f),
            preferences.getFloat("player_y", 180f).coerceIn(90f, worldHeight - 100f),
        ))
    }
    val latestPlayer by rememberUpdatedState(player)
    DisposableEffect(preferences) {
        onDispose {
            preferences.edit().putFloat("player_x", latestPlayer.x)
                .putFloat("player_y", latestPlayer.y).apply()
        }
    }
    var visited by remember { mutableStateOf(preferences.getStringSet("found", emptySet()).orEmpty().toSet()) }
    var joystick by remember { mutableStateOf(WorldVector.Zero) }
    var tapTarget by remember { mutableStateOf<WorldVector?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scale = if (viewport.width > 0) viewport.width / worldWidth else 1f
    val visibleHeight = if (scale > 0f) viewport.height / scale else worldHeight
    val cameraY = (player.y - visibleHeight * .48f).coerceIn(0f, (worldHeight - visibleHeight).coerceAtLeast(0f))
    val nearestDoor = doors.minByOrNull { player.distanceTo(it.point) }
        ?.takeIf { player.distanceTo(it.point) < 145f }
    val nearestDiscovery = streetDiscoveries.filter { it.id !in visited }
        .minByOrNull { player.distanceTo(it.point) }
        ?.takeIf { player.distanceTo(it.point) < 118f }
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
                val targetDirection = tapTarget?.let { target ->
                    val delta = target - player
                    if (delta.length < 25f) {
                        tapTarget = null
                        WorldVector.Zero
                    } else delta.normalized()
                } ?: WorldVector.Zero
                val direction = if (joystick.length > .08f) {
                    tapTarget = null
                    joystick
                } else targetDirection
                if (direction.length > .04f) {
                    val delta = direction.normalized() * (dt * 310f)
                    player = WorldVector(
                        (player.x + delta.x).coerceIn(86f, worldWidth - 86f),
                        (player.y + delta.y).coerceIn(90f, worldHeight - 100f),
                    )
                }
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFFBBD2C4)).onSizeChanged { viewport = it }) {
        Canvas(Modifier.fillMaxSize().pointerInput(scale, cameraY) {
            detectTapGestures { point ->
                tapTarget = WorldVector(
                    (point.x / scale).coerceIn(86f, worldWidth - 86f),
                    (point.y / scale + cameraY).coerceIn(90f, worldHeight - 100f),
                )
                GameSoundEffects.play(GameSoundEffect.Move)
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
                streetDiscoveries.forEach { discovery ->
                    val p = discovery.point
                    val found = discovery.id in visited
                    drawCircle(Color(0xFF476F5D).copy(alpha = .17f), 66f, Offset(p.x, p.y + 30f))
                    drawCircle(if (found) Color(0xFF7A9D82) else Color(0xFFEBC88B),
                        42f, Offset(p.x, p.y))
                    drawCircle(Color.White.copy(alpha = if (found) .30f else .75f),
                        10f, Offset(p.x, p.y - 13f))
                }
                val you = Offset(player.x, player.y)
                drawOval(Color(0xFF395B4F).copy(alpha = .26f),
                    Offset(you.x - 30f, you.y + 18f), Size(60f, 16f))
                drawCircle(Color(0xFFF5D5B9), 30f, Offset(you.x, you.y - 30f))
                drawCircle(Color(0xFF547E72), 27f, you)
                drawCircle(Color(0xFFF6FFF0), 7f, Offset(you.x - 9f, you.y - 6f))
                companions.take(3).forEachIndexed { index, person ->
                    val companion = Offset(you.x + 85f + index * 63f, you.y + 26f + index * 28f)
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
                    Text("走到建筑门口再进入 · 已发现 ${visited.size}/${streetDiscoveries.size}",
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
            label = nearestDiscovery?.let { "探索" }
                ?: nearestDoor?.let { if (canEnterScene) "进入" else "等待" } ?: "靠近",
            enabled = nearestDiscovery != null || (nearestDoor != null && canEnterScene),
            onClick = {
                val clue = nearestDiscovery
                val door = nearestDoor
                if (clue != null) {
                    val updated = visited + clue.id
                    if (preferences.edit().putStringSet("found", updated).commit()) {
                        visited = updated
                        WorldFirstExplorationMemory.record(
                            context = context, worldId = "digital-world",
                            locationId = "street:${clue.id}",
                            locationLabel = "云上小镇",
                            action = "走到了${clue.label}，亲自发现：${clue.description}",
                        )
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
        if (nearestDoor != null || nearestDiscovery != null) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 120.dp, start = 85.dp, end = 85.dp),
                color = Color(0xE5172B25), shape = RoundedCornerShape(12.dp)) {
                Text(nearestDiscovery?.let { "${it.label} · ${it.description}" }
                    ?: nearestDoor?.let { "${it.address.label} · 已走到入口" }.orEmpty(),
                    color = Color(0xFFE8F4EA), fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}
