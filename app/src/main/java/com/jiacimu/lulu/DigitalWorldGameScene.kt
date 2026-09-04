package com.jiacimu.lulu

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.jiacimu.lulu.data.DigitalWorldState
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.games.GameCharacterPawn
import com.jiacimu.lulu.games.GameSoundEffect
import com.jiacimu.lulu.games.GameSoundEffects
import com.jiacimu.lulu.games.WorldActionButton
import com.jiacimu.lulu.games.WorldVector
import com.jiacimu.lulu.games.WorldVirtualJoystick
import com.jiacimu.lulu.games.circleIntersects
import com.jiacimu.lulu.games.lerp
import com.jiacimu.lulu.games.moveInWorld
import com.jiacimu.lulu.games.worldCameraTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private data class DigitalNpcMotion(
    val character: CharacterSettings,
    val position: WorldVector,
    val target: WorldVector,
    val nextDecisionAt: Long,
    val facingX: Float = 1f,
)

private sealed interface DigitalNearbyTarget {
    val distance: Float

    data class Resident(val motion: DigitalNpcMotion, override val distance: Float) : DigitalNearbyTarget
    data class Prop(val prop: DigitalRoomProp, override val distance: Float) : DigitalNearbyTarget
}

@Composable
internal fun DigitalWorldGameScene(
    modifier: Modifier,
    sceneCode: String,
    homeCharacterId: String?,
    characters: List<CharacterSettings>,
    world: DigitalWorldState,
    onCharacterClick: (String) -> Unit,
    onWorldAction: ((String) -> Unit)? = null,
    controlsBottomPadding: Dp = 18.dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sceneKey = remember(sceneCode, homeCharacterId) { "$sceneCode:${homeCharacterId.orEmpty()}" }
    val residents = remember(characters, world.characterLocations, sceneCode) {
        characters.filter { world.characterLocations[it.characterId] == sceneCode }
    }
    val roomItems = remember(world.items, homeCharacterId) {
        homeCharacterId?.let { owner -> world.items.filter { it.ownerCharacterId == owner } }.orEmpty()
    }
    val props = remember(roomItems) { buildDigitalRoomProps(roomItems) }
    val obstacles = remember(props) { props.mapNotNull(DigitalRoomProp::obstacle) }
    val stateStore = remember(context, sceneKey) { DigitalWorldPlayState(context, sceneKey) }
    val restored = remember(stateStore) { stateStore.loadPosition() }
    val safeStart = remember(restored, obstacles) { digitalSafeStart(restored, obstacles) }
    val userAvatar = remember(context) { rememberUserAvatar(context) }

    var playerPosition by remember(sceneKey) { mutableStateOf(safeStart) }
    var camera by remember(sceneKey) { mutableStateOf(WorldVector.Zero) }
    var joystick by remember(sceneKey) { mutableStateOf(WorldVector.Zero) }
    var tapTarget by remember(sceneKey) { mutableStateOf<WorldVector?>(null) }
    var facingX by remember(sceneKey) { mutableFloatStateOf(1f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var questStage by remember(sceneKey) { mutableIntStateOf(stateStore.loadQuest()) }
    var interactionMessage by remember(sceneKey) { mutableStateOf("") }

    var npcMotions by remember(sceneKey, residents.map { it.characterId }) {
        mutableStateOf(
            residents.mapIndexed { index, character ->
                val start = residentStart(index, character.characterId)
                DigitalNpcMotion(character, start, start, System.currentTimeMillis() + 2_100L + index * 760L)
            },
        )
    }

    val ambience = rememberInfiniteTransition(label = "digital-world-light")
    val lightPhase by ambience.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_800), RepeatMode.Reverse),
        label = "digital-world-light-phase",
    )

    val worldScale = (viewportSize.height.toFloat() / 650f).coerceAtLeast(.35f)
    val viewportWorldWidth = viewportSize.width.toFloat() / worldScale
    val viewportWorldHeight = viewportSize.height.toFloat() / worldScale

    val latestPosition by rememberUpdatedState(playerPosition)
    val latestQuest by rememberUpdatedState(questStage)
    DisposableEffect(sceneKey, stateStore) {
        onDispose { stateStore.save(latestPosition, latestQuest) }
    }
    LaunchedEffect(sceneKey, stateStore) {
        while (isActive) {
            delay(1_600)
            stateStore.save(playerPosition, questStage)
        }
    }

    LaunchedEffect(interactionMessage) {
        if (interactionMessage.isBlank()) return@LaunchedEffect
        delay(2_200)
        interactionMessage = ""
    }

    LaunchedEffect(sceneKey, obstacles, viewportSize, residents.map { it.characterId }) {
        var previousFrame = withFrameNanos { it }
        var lastCollisionSound = 0L
        while (isActive) {
            withFrameNanos { frame ->
                val delta = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                previousFrame = frame

                val destination = tapTarget
                val automatic = destination?.let { target ->
                    val deltaVector = target - playerPosition
                    if (deltaVector.length < 22f) {
                        tapTarget = null
                        WorldVector.Zero
                    } else {
                        deltaVector.normalized()
                    }
                } ?: WorldVector.Zero
                val direction = if (joystick.length > .08f) joystick else automatic
                if (direction.length > .04f) {
                    if (joystick.length > .08f) tapTarget = null
                    facingX = direction.x.takeIf { kotlin.math.abs(it) > .04f } ?: facingX
                    val result = moveInWorld(
                        position = playerPosition,
                        direction = direction,
                        speed = 255f,
                        deltaSeconds = delta,
                        radius = 31f,
                        bounds = DIGITAL_WORLD_BOUNDS,
                        obstacles = obstacles,
                    )
                    playerPosition = result.position
                    if (result.collided) {
                        tapTarget = null
                        val now = System.currentTimeMillis()
                        if (now - lastCollisionSound > 420L) {
                            GameSoundEffects.play(GameSoundEffect.Bump)
                            lastCollisionSound = now
                        }
                    }
                }

                val targetCamera = worldCameraTarget(
                    focus = playerPosition + direction.normalized() * 62f,
                    viewportWidth = viewportWorldWidth,
                    viewportHeight = viewportWorldHeight,
                    worldWidth = DIGITAL_WORLD_WIDTH,
                    worldHeight = DIGITAL_WORLD_HEIGHT,
                )
                camera = camera.lerp(targetCamera, delta * 5.5f)

                val now = System.currentTimeMillis()
                npcMotions = npcMotions.mapIndexed { index, motion ->
                    var target = motion.target
                    var nextDecision = motion.nextDecisionAt
                    if (now >= motion.nextDecisionAt || motion.position.distanceTo(target) < 22f) {
                        val followsPlayer = index == 0 && ((now / 7_000L + index) % 3L == 0L)
                        target = if (followsPlayer) {
                            playerPosition + WorldVector(if (index % 2 == 0) -96f else 96f, -34f)
                        } else {
                            residentWanderTarget(motion.character.characterId, now, index)
                        }
                        nextDecision = now + 4_500L + ((motion.character.characterId.hashCode() and Int.MAX_VALUE) % 3_400)
                    }
                    val directionToTarget = target - motion.position
                    val moved = if (directionToTarget.length > 16f) {
                        moveInWorld(
                            motion.position,
                            directionToTarget,
                            speed = 76f,
                            deltaSeconds = delta,
                            radius = 28f,
                            bounds = DIGITAL_WORLD_BOUNDS,
                            obstacles = obstacles,
                        ).position
                    } else motion.position
                    motion.copy(
                        position = moved,
                        target = target,
                        nextDecisionAt = nextDecision,
                        facingX = directionToTarget.x.takeIf { kotlin.math.abs(it) > 3f } ?: motion.facingX,
                    )
                }
            }
        }
    }

    val nearestNpc = npcMotions.minByOrNull { playerPosition.distanceTo(it.position) }?.let { motion ->
        DigitalNearbyTarget.Resident(motion, playerPosition.distanceTo(motion.position))
    }
    val nearestProp = props.minByOrNull { playerPosition.distanceTo(it.bounds.center) }?.let { prop ->
        DigitalNearbyTarget.Prop(prop, playerPosition.distanceTo(prop.bounds.center))
    }
    val nearby = listOfNotNull(nearestNpc, nearestProp).minByOrNull(DigitalNearbyTarget::distance)
        ?.takeIf { it.distance <= 142f }
    val actionLabel = when (nearby) {
        is DigitalNearbyTarget.Resident -> "交谈"
        is DigitalNearbyTarget.Prop -> when (nearby.prop.style.kind) {
            com.jiacimu.lulu.data.DigitalFurnitureKind.BED,
            com.jiacimu.lulu.data.DigitalFurnitureKind.SOFA,
            com.jiacimu.lulu.data.DigitalFurnitureKind.CHAIR -> "坐下"
            else -> "互动"
        }
        null -> "靠近"
    }

    val questText = when {
        questStage <= 0 && residents.isNotEmpty() -> "走到 ${residents.first().displayName} 身边并交谈"
        questStage <= 1 && props.isNotEmpty() -> "探索 ${props.first().item.name}"
        residents.isNotEmpty() -> "和房间里的人一起自由行动"
        props.isNotEmpty() -> "在房间里寻找可以互动的陈设"
        else -> "探索这片仍在形成的数字空间"
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF091311))
            .onSizeChanged { viewportSize = it },
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(sceneKey, viewportSize) {
                    detectTapGestures { tap ->
                        tapTarget = WorldVector(tap.x / worldScale + camera.x, tap.y / worldScale + camera.y)
                        GameSoundEffects.play(GameSoundEffect.Move)
                    }
                },
        ) {
            withTransform({
                translate(-camera.x * worldScale, -camera.y * worldScale)
                scale(worldScale, worldScale, pivot = Offset.Zero)
            }) {
                if (homeCharacterId != null) {
                    drawDigitalHomeWorld(props, lightPhase)
                } else {
                    drawDigitalSharedWorld(sceneCode, lightPhase)
                }
            }

            val focus = when (val target = nearby) {
                is DigitalNearbyTarget.Resident -> target.motion.position
                is DigitalNearbyTarget.Prop -> target.prop.bounds.center
                null -> null
            }
            focus?.let { worldPoint ->
                val focusPoint = Offset(
                    (worldPoint.x - camera.x) * worldScale,
                    (worldPoint.y - camera.y) * worldScale,
                )
                drawCircle(
                    Color(0xFFC9FFE9).copy(alpha = .18f + lightPhase * .16f),
                    34.dp.toPx() + lightPhase * 4.dp.toPx(),
                    focusPoint,
                )
                drawCircle(
                    Color(0xFFE7FFF5).copy(alpha = .70f),
                    24.dp.toPx() + lightPhase * 3.dp.toPx(),
                    focusPoint,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()),
                )
            }

            repeat(18) { index ->
                val seed = (sceneKey.hashCode() * 31L + index * 977L) and Long.MAX_VALUE
                val x = ((seed % 1_000L) / 1_000f * size.width + lightPhase * 22.dp.toPx()) % size.width
                val y = ((seed / 43L % 1_000L) / 1_000f * size.height)
                drawCircle(
                    Color(0xFFFFF8DE).copy(alpha = .08f + (index % 4) * .025f),
                    (1f + index % 3).dp.toPx(),
                    Offset(x, y),
                )
            }

            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color(0xB6081110)),
                    center = Offset(size.width * .50f, size.height * .45f),
                    radius = size.maxDimension * .72f,
                ),
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .25f), Color.Transparent, Color.Black.copy(alpha = .30f)),
                ),
            )
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF6FE0C0).copy(alpha = .055f), Color.Transparent, Color(0xFFFFD9A0).copy(alpha = .045f)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }

        val halfPawnWidth = with(density) { 31.dp.toPx() }
        val pawnFoot = with(density) { 84.dp.toPx() }
        npcMotions.forEach { motion ->
            val screenX = (motion.position.x - camera.x) * worldScale
            val screenY = (motion.position.y - camera.y) * worldScale
            GameCharacterPawn(
                avatarUri = motion.character.avatarUri,
                fallback = motion.character.displayName.take(1),
                moving = motion.position.distanceTo(motion.target) > 20f,
                facingX = motion.facingX,
                coat = Color(0xFF526B67),
                modifier = Modifier
                    .offset { IntOffset((screenX - halfPawnWidth).roundToInt(), (screenY - pawnFoot).roundToInt()) }
                    .zIndex(motion.position.y)
                    .clickable {
                        tapTarget = motion.position
                        interactionMessage = "靠近后可以和 ${motion.character.displayName} 交谈"
                    },
            )
        }

        val playerScreenX = (playerPosition.x - camera.x) * worldScale
        val playerScreenY = (playerPosition.y - camera.y) * worldScale
        GameCharacterPawn(
            avatarUri = userAvatar.second,
            fallback = userAvatar.first,
            moving = joystick.length > .08f || tapTarget != null,
            facingX = facingX,
            player = true,
            coat = Color(0xFF233D38),
            modifier = Modifier
                .offset { IntOffset((playerScreenX - halfPawnWidth).roundToInt(), (playerScreenY - pawnFoot).roundToInt()) }
                .zIndex(playerPosition.y + .5f),
        )

        DigitalWorldMissionHud(
            place = homeCharacterId?.let { world.homes[it]?.name } ?: if (sceneCode == DigitalWorldStore.CLOUD_MEADOW) "云眠原" else "世界入口",
            quest = questText,
            people = residents.size,
            objects = props.size,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        DigitalWorldMiniMap(
            player = playerPosition,
            residents = npcMotions.map(DigitalNpcMotion::position),
            props = props,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        AnimatedVisibility(
            visible = interactionMessage.isNotBlank(),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
            enter = fadeIn() + scaleIn(initialScale = .94f),
            exit = fadeOut() + scaleOut(targetScale = .96f),
        ) {
            Surface(
                color = Color(0xE3152420),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFB7FFE8).copy(alpha = .40f)),
                shadowElevation = 7.dp,
            ) {
                Text(
                    interactionMessage,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                )
            }
        }

        WorldVirtualJoystick(
            value = joystick,
            onValueChanged = { next ->
                if (joystick.length <= .05f && next.length > .05f) GameSoundEffects.play(GameSoundEffect.Move)
                joystick = next
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = controlsBottomPadding),
            tint = Color(0xFFC3FFE9),
        )
        WorldActionButton(
            label = actionLabel,
            enabled = nearby != null,
            onClick = {
                when (val target = nearby) {
                    is DigitalNearbyTarget.Resident -> {
                        GameSoundEffects.play(GameSoundEffect.Interact)
                        interactionMessage = "${target.motion.character.displayName} 注意到了你"
                        questStage = questStage.coerceAtLeast(1)
                        onCharacterClick(target.motion.character.characterId)
                    }
                    is DigitalNearbyTarget.Prop -> {
                        GameSoundEffects.play(GameSoundEffect.Interact)
                        interactionMessage = target.prop.item.name
                        questStage = questStage.coerceAtLeast(2)
                        onWorldAction?.invoke(target.prop.obstacle?.action.orEmpty().ifBlank { "我走近${target.prop.item.name}，仔细看了看。" })
                    }
                    null -> Unit
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = controlsBottomPadding + 12.dp),
            accent = Color(0xFF9EFFE0),
        )
    }
}

@Composable
private fun DigitalWorldMissionHud(
    place: String,
    quest: String,
    people: Int,
    objects: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 246.dp),
        color = Color(0xDF10201C),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Color(0xFFCBFFEF).copy(alpha = .24f)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Explore, null, tint = Color(0xFFB8F8E3), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(place, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text("$people 人 · $objects 物", color = Color(0xFF9DC4B9), fontSize = 8.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TaskAlt, null, tint = Color(0xFFE9D39D), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(quest, color = Color(0xFFE9F4EF), fontSize = 9.5.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DigitalWorldMiniMap(
    player: WorldVector,
    residents: List<WorldVector>,
    props: List<DigitalRoomProp>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(72.dp),
        color = Color(0xC90B1715),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            drawRoundRect(Color(0xFF738B83).copy(alpha = .22f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
            props.take(28).forEach { prop ->
                val x = prop.bounds.center.x / DIGITAL_WORLD_WIDTH * size.width
                val y = prop.bounds.center.y / DIGITAL_WORLD_HEIGHT * size.height
                drawCircle(Color(0xFFD3C7AD).copy(alpha = .65f), 1.5.dp.toPx(), Offset(x, y))
            }
            residents.forEach { resident ->
                drawCircle(Color(0xFFFFD89C), 2.3.dp.toPx(), Offset(resident.x / DIGITAL_WORLD_WIDTH * size.width, resident.y / DIGITAL_WORLD_HEIGHT * size.height))
            }
            drawCircle(Color(0xFF92FFDA), 3.2.dp.toPx(), Offset(player.x / DIGITAL_WORLD_WIDTH * size.width, player.y / DIGITAL_WORLD_HEIGHT * size.height))
            drawCircle(Color.White.copy(alpha = .82f), 3.2.dp.toPx(), Offset(player.x / DIGITAL_WORLD_WIDTH * size.width, player.y / DIGITAL_WORLD_HEIGHT * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
        }
    }
}

private fun residentStart(index: Int, id: String): WorldVector {
    val starts = listOf(
        WorldVector(690f, 520f),
        WorldVector(980f, 590f),
        WorldVector(520f, 735f),
        WorldVector(1_180f, 770f),
        WorldVector(780f, 860f),
    )
    val base = starts[index % starts.size]
    val hash = id.hashCode() and Int.MAX_VALUE
    return base + WorldVector((hash % 51 - 25).toFloat(), ((hash / 61) % 41 - 20).toFloat())
}

private fun residentWanderTarget(id: String, now: Long, index: Int): WorldVector {
    val epoch = now / 4_500L
    val seed = (id.hashCode().toLong() * 31L + epoch * 97L + index * 211L) and Long.MAX_VALUE
    return WorldVector(
        170f + (seed % 1_250L).toFloat(),
        300f + ((seed / 37L) % 570L).toFloat(),
    )
}

private fun rememberUserAvatar(context: Context): Pair<String, String?> =
    context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) to prefs.getString("avatar_uri", null)
    }

private fun digitalSafeStart(restored: WorldVector, obstacles: List<com.jiacimu.lulu.games.WorldObstacle>): WorldVector {
    if (obstacles.none { circleIntersects(restored, 34f, it.bounds) }) return restored
    return listOf(
        WorldVector(800f, 930f),
        WorldVector(800f, 235f),
        WorldVector(600f, 920f),
        WorldVector(1_000f, 920f),
        WorldVector(800f, 700f),
    ).firstOrNull { candidate -> obstacles.none { circleIntersects(candidate, 34f, it.bounds) } }
        ?: WorldVector(800f, 930f)
}

private class DigitalWorldPlayState(context: Context, sceneKey: String) {
    private val prefs = context.applicationContext.getSharedPreferences("lulu_digital_world_play", Context.MODE_PRIVATE)
    private val suffix = (sceneKey.hashCode() and Int.MAX_VALUE).toString(36)

    fun loadPosition(): WorldVector {
        val x = prefs.getFloat("x_$suffix", 800f)
        val y = prefs.getFloat("y_$suffix", 820f)
        return WorldVector(
            x.coerceIn(DIGITAL_WORLD_BOUNDS.left + 34f, DIGITAL_WORLD_BOUNDS.right - 34f),
            y.coerceIn(DIGITAL_WORLD_BOUNDS.top + 34f, DIGITAL_WORLD_BOUNDS.bottom - 34f),
        )
    }

    fun loadQuest(): Int = prefs.getInt("quest_$suffix", 0).coerceIn(0, 2)

    fun save(position: WorldVector, quest: Int) {
        prefs.edit()
            .putFloat("x_$suffix", position.x)
            .putFloat("y_$suffix", position.y)
            .putInt("quest_$suffix", quest.coerceIn(0, 2))
            .apply()
    }
}
