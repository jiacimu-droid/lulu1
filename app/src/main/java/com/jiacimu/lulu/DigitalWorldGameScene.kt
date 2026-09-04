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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.jiacimu.lulu.data.DigitalFurnitureKind
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.DigitalWorldActivityCatalog
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldState
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.data.WorldFirstExplorationMemory
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
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

private data class DigitalNpcMotion(
    val character: CharacterSettings,
    val position: WorldVector,
    val target: WorldVector,
    val nextDecisionAt: Long,
    val facingX: Float = 1f,
    val pendingItemId: String? = null,
    val pendingActivityId: String? = null,
    val activityLabel: String = "",
    val busyUntil: Long = 0L,
)

private sealed interface DigitalNearbyTarget {
    val distance: Float

    data class Resident(val motion: DigitalNpcMotion, override val distance: Float) : DigitalNearbyTarget
    data class Prop(val prop: DigitalRoomProp, override val distance: Float) : DigitalNearbyTarget
}

private data class DigitalQueuedAction(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val summary: String,
)

private data class DigitalInteractionChoice(
    val label: String,
    val detail: String,
    val quickSummary: String? = null,
    val storyPrompt: String? = null,
    val dialogueCharacterId: String? = null,
)

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
    controlsEnabled: Boolean = true,
    showExplorationHud: Boolean = true,
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
    val placeLabel = remember(sceneCode, homeCharacterId, world.homes) {
        homeCharacterId?.let { world.homes[it]?.name }
            ?: when (sceneCode) {
                DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
                DigitalWorldStore.ARRIVAL -> "世界入口"
                else -> DigitalWorldPublicPlaces.label(sceneCode) ?: "数字世界"
            }
    }

    var playerPosition by remember(sceneKey) { mutableStateOf(safeStart) }
    var camera by remember(sceneKey) { mutableStateOf(WorldVector.Zero) }
    var joystick by remember(sceneKey) { mutableStateOf(WorldVector.Zero) }
    var tapTarget by remember(sceneKey) { mutableStateOf<WorldVector?>(null) }
    var facingX by remember(sceneKey) { mutableFloatStateOf(1f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var questStage by remember(sceneKey) { mutableIntStateOf(stateStore.loadQuest()) }
    var interactionMessage by remember(sceneKey) { mutableStateOf("") }
    var menuTargetKey by remember(sceneKey) { mutableStateOf<String?>(null) }
    var queuedActions by remember(sceneKey) { mutableStateOf<List<DigitalQueuedAction>>(emptyList()) }
    var activeActionLabel by remember(sceneKey) { mutableStateOf("") }

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

    LaunchedEffect(controlsEnabled) {
        if (!controlsEnabled) {
            joystick = WorldVector.Zero
            tapTarget = null
            interactionMessage = ""
            menuTargetKey = null
        }
    }

    LaunchedEffect(interactionMessage) {
        if (interactionMessage.isBlank()) return@LaunchedEffect
        delay(2_200)
        interactionMessage = ""
    }

    LaunchedEffect(queuedActions.firstOrNull()?.id, controlsEnabled, sceneKey) {
        val queued = queuedActions.firstOrNull() ?: return@LaunchedEffect
        if (!controlsEnabled) return@LaunchedEffect
        delay(360)
        activeActionLabel = queued.label
        interactionMessage = queued.label
        WorldFirstExplorationMemory.record(
            context = context,
            worldId = "digital-world",
            locationId = sceneCode,
            locationLabel = placeLabel,
            action = queued.summary,
        )
        queuedActions = queuedActions.drop(1)
        delay(780)
        if (activeActionLabel == queued.label) activeActionLabel = ""
    }

    LaunchedEffect(sceneKey, obstacles, viewportSize, residents.map { it.characterId }, controlsEnabled, props) {
        var previousFrame = withFrameNanos { it }
        var lastCollisionSound = 0L
        while (isActive) {
            withFrameNanos { frame ->
                val delta = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
                previousFrame = frame

                val destination = if (controlsEnabled) tapTarget else null
                val automatic = destination?.let { target ->
                    val deltaVector = target - playerPosition
                    if (deltaVector.length < 22f) {
                        tapTarget = null
                        WorldVector.Zero
                    } else {
                        deltaVector.normalized()
                    }
                } ?: WorldVector.Zero
                val direction = when {
                    !controlsEnabled -> WorldVector.Zero
                    joystick.length > .08f -> joystick
                    else -> automatic
                }
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
                    if (motion.busyUntil > now) {
                        return@mapIndexed motion.copy(target = motion.position)
                    }
                    if (motion.busyUntil > 0L && now >= motion.busyUntil) {
                        return@mapIndexed motion.copy(
                            activityLabel = "",
                            busyUntil = 0L,
                            nextDecisionAt = now + 3_800L + index * 430L,
                        )
                    }

                    val pendingItem = motion.pendingItemId?.let { id -> props.firstOrNull { it.item.id == id } }
                    if (pendingItem != null && motion.position.distanceTo(pendingItem.bounds.center) < 46f) {
                        val activityId = motion.pendingActivityId.orEmpty()
                        val optionLabel = DigitalWorldActivityCatalog.optionsFor(pendingItem.item)
                            .firstOrNull { it.first == activityId }?.second.orEmpty()
                        val result = if (DigitalLifeProfileStore.isEnabled(motion.character.characterId)) {
                            DigitalWorldStore.performAction(
                                characterId = motion.character.characterId,
                                action = "use_home_item",
                                args = JSONObject()
                                    .put("itemId", pendingItem.item.id)
                                    .put("activityId", activityId),
                            )
                        } else null
                        return@mapIndexed motion.copy(
                            target = motion.position,
                            pendingItemId = null,
                            pendingActivityId = null,
                            activityLabel = if (result?.success == true) optionLabel else "",
                            busyUntil = if (result?.success == true) now + 4_200L + index * 260L else 0L,
                            nextDecisionAt = now + 7_000L,
                        )
                    }

                    var target = motion.target
                    var nextDecision = motion.nextDecisionAt
                    var pendingId = motion.pendingItemId
                    var pendingActivity = motion.pendingActivityId
                    var activityLabel = motion.activityLabel

                    if (now >= motion.nextDecisionAt || motion.position.distanceTo(target) < 22f) {
                        val phase = ((now / 13_000L + motion.character.characterId.hashCode().toLong()) and Long.MAX_VALUE) % 5L
                        val wantsCompany = controlsEnabled && phase == 0L && index == 0
                        val usableProps = if (DigitalLifeProfileStore.isEnabled(motion.character.characterId)) {
                            props.filter { DigitalWorldActivityCatalog.optionsFor(it.item).isNotEmpty() }
                        } else emptyList()
                        val wantsFurniture = usableProps.isNotEmpty() && phase in 1L..3L
                        if (wantsCompany) {
                            target = playerPosition + WorldVector(if (index % 2 == 0) -96f else 96f, -34f)
                            pendingId = null
                            pendingActivity = null
                            activityLabel = "想找你待一会儿"
                            nextDecision = now + 6_000L
                        } else if (wantsFurniture) {
                            val seed = ((motion.character.characterId.hashCode().toLong() * 37L + now / 9_000L) and Long.MAX_VALUE)
                            val prop = usableProps[(seed % usableProps.size).toInt()]
                            val safeOptions = DigitalWorldActivityCatalog.optionsFor(prop.item)
                                .filterNot { it.first in setOf("sleep", "nap", "lie_down", "lie_on_rug") }
                            val option = safeOptions.ifEmpty { DigitalWorldActivityCatalog.optionsFor(prop.item) }
                                .getOrNull((seed / 7L % safeOptions.ifEmpty { DigitalWorldActivityCatalog.optionsFor(prop.item) }.size.coerceAtLeast(1)).toInt())
                            target = prop.bounds.center
                            pendingId = prop.item.id
                            pendingActivity = option?.first
                            activityLabel = option?.second?.let { "准备$it" }.orEmpty()
                            nextDecision = now + 12_000L
                        } else {
                            target = residentWanderTarget(motion.character.characterId, now, index)
                            pendingId = null
                            pendingActivity = null
                            activityLabel = ""
                            nextDecision = now + 5_000L + ((motion.character.characterId.hashCode() and Int.MAX_VALUE) % 3_400)
                        }
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
                        pendingItemId = pendingId,
                        pendingActivityId = pendingActivity,
                        activityLabel = activityLabel,
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
    val actionLabel = if (nearby != null) "互动" else "靠近"

    val selectedTarget = when {
        menuTargetKey?.startsWith("npc:") == true -> {
            val id = menuTargetKey!!.removePrefix("npc:")
            npcMotions.firstOrNull { it.character.characterId == id }?.let {
                DigitalNearbyTarget.Resident(it, playerPosition.distanceTo(it.position))
            }
        }
        menuTargetKey?.startsWith("prop:") == true -> {
            val id = menuTargetKey!!.removePrefix("prop:")
            props.firstOrNull { it.item.id == id }?.let {
                DigitalNearbyTarget.Prop(it, playerPosition.distanceTo(it.bounds.center))
            }
        }
        else -> null
    }?.takeIf { it.distance <= 190f }

    LaunchedEffect(selectedTarget, controlsEnabled) {
        if (selectedTarget == null || !controlsEnabled) menuTargetKey = null
    }

    val interactionChoices = remember(selectedTarget, residents, props) {
        buildInteractionChoices(selectedTarget, residents, props)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF091311))
            .onSizeChanged { viewportSize = it },
    ) {
        val moveInputModifier = if (controlsEnabled && selectedTarget == null) {
            Modifier.pointerInput(sceneKey, viewportSize) {
                detectTapGestures { tap ->
                    tapTarget = WorldVector(tap.x / worldScale + camera.x, tap.y / worldScale + camera.y)
                    GameSoundEffects.play(GameSoundEffect.Move)
                }
            }
        } else Modifier

        Canvas(Modifier.matchParentSize().then(moveInputModifier)) {
            withTransform({
                translate(-camera.x * worldScale, -camera.y * worldScale)
                scale(worldScale, worldScale, pivot = Offset.Zero)
            }) {
                if (homeCharacterId != null) drawDigitalHomeWorld(props, lightPhase)
                else drawDigitalSharedWorld(sceneCode, lightPhase)
            }

            val focus = if (showExplorationHud && controlsEnabled) {
                when (val target = selectedTarget ?: nearby) {
                    is DigitalNearbyTarget.Resident -> target.motion.position
                    is DigitalNearbyTarget.Prop -> target.prop.bounds.center
                    null -> null
                }
            } else null
            focus?.let { worldPoint ->
                val focusPoint = Offset(
                    (worldPoint.x - camera.x) * worldScale,
                    (worldPoint.y - camera.y) * worldScale,
                )
                drawCircle(
                    Color(0xFFC9FFE9).copy(alpha = .10f + lightPhase * .10f),
                    31.dp.toPx() + lightPhase * 3.dp.toPx(),
                    focusPoint,
                )
                drawCircle(
                    Color(0xFFE7FFF5).copy(alpha = .56f),
                    22.dp.toPx() + lightPhase * 2.dp.toPx(),
                    focusPoint,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()),
                )
            }

            repeat(12) { index ->
                val seed = (sceneKey.hashCode() * 31L + index * 977L) and Long.MAX_VALUE
                val x = ((seed % 1_000L) / 1_000f * size.width + lightPhase * 18.dp.toPx()) % size.width
                val y = ((seed / 43L % 1_000L) / 1_000f * size.height)
                drawCircle(
                    Color(0xFFFFF8DE).copy(alpha = .05f + (index % 4) * .018f),
                    (1f + index % 2).dp.toPx(),
                    Offset(x, y),
                )
            }

            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color(0x9A081110)),
                    center = Offset(size.width * .50f, size.height * .45f),
                    radius = size.maxDimension * .76f,
                ),
            )
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .16f), Color.Transparent, Color.Black.copy(alpha = .22f)),
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
                moving = motion.busyUntil <= System.currentTimeMillis() && motion.position.distanceTo(motion.target) > 20f,
                facingX = motion.facingX,
                coat = Color(0xFF526B67),
                modifier = Modifier
                    .offset { IntOffset((screenX - halfPawnWidth).roundToInt(), (screenY - pawnFoot).roundToInt()) }
                    .zIndex(motion.position.y)
                    .clickable(enabled = controlsEnabled && selectedTarget == null) {
                        tapTarget = motion.position
                        interactionMessage = "靠近 ${motion.character.displayName} 后可以选择怎么相处"
                    },
            )
            if (showExplorationHud && motion.activityLabel.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .offset { IntOffset((screenX - with(density) { 54.dp.toPx() }).roundToInt(), (screenY - with(density) { 112.dp.toPx() }).roundToInt()) }
                        .zIndex(motion.position.y + 4f),
                    color = Color(0xD90E1916),
                    shape = RoundedCornerShape(99.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
                ) {
                    Text(
                        motion.activityLabel,
                        color = Color(0xFFDDEAE4),
                        fontSize = 8.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        val playerScreenX = (playerPosition.x - camera.x) * worldScale
        val playerScreenY = (playerPosition.y - camera.y) * worldScale
        GameCharacterPawn(
            avatarUri = userAvatar.second,
            fallback = userAvatar.first,
            moving = controlsEnabled && (joystick.length > .08f || tapTarget != null),
            facingX = facingX,
            player = true,
            coat = Color(0xFF233D38),
            modifier = Modifier
                .offset { IntOffset((playerScreenX - halfPawnWidth).roundToInt(), (playerScreenY - pawnFoot).roundToInt()) }
                .zIndex(playerPosition.y + .5f),
        )

        if (showExplorationHud) {
            DigitalWorldAmbientHud(
                place = placeLabel,
                people = residents.size,
                objects = props.size,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
            if (homeCharacterId == null) {
                DigitalWorldMiniMap(
                    player = playerPosition,
                    residents = npcMotions.map(DigitalNpcMotion::position),
                    props = props,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsEnabled && interactionMessage.isNotBlank(),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
            enter = fadeIn() + scaleIn(initialScale = .94f),
            exit = fadeOut() + scaleOut(targetScale = .96f),
        ) {
            Surface(
                color = Color(0xE3152420),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFB7FFE8).copy(alpha = .26f)),
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

        if (controlsEnabled && queuedActions.isNotEmpty()) {
            DigitalActionQueueStrip(
                active = activeActionLabel,
                queued = queuedActions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = controlsBottomPadding + 88.dp, start = 90.dp, end = 90.dp),
            )
        }

        if (controlsEnabled) {
            WorldVirtualJoystick(
                value = joystick,
                onValueChanged = { next ->
                    if (selectedTarget != null) return@WorldVirtualJoystick
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
                    val target = nearby ?: return@WorldActionButton
                    GameSoundEffects.play(GameSoundEffect.Interact)
                    joystick = WorldVector.Zero
                    tapTarget = null
                    menuTargetKey = when (target) {
                        is DigitalNearbyTarget.Resident -> "npc:${target.motion.character.characterId}"
                        is DigitalNearbyTarget.Prop -> "prop:${target.prop.item.id}"
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = controlsBottomPadding + 12.dp),
                accent = Color(0xFF9EFFE0),
            )
        }

        if (controlsEnabled && selectedTarget != null) {
            DigitalInteractionMenu(
                target = selectedTarget,
                choices = interactionChoices,
                onDismiss = { menuTargetKey = null },
                onChoice = { choice ->
                    menuTargetKey = null
                    when {
                        choice.dialogueCharacterId != null -> {
                            questStage = questStage.coerceAtLeast(1)
                            onCharacterClick(choice.dialogueCharacterId)
                        }
                        choice.quickSummary != null -> {
                            questStage = questStage.coerceAtLeast(2)
                            queuedActions = (queuedActions + DigitalQueuedAction(choice.label, summary = choice.quickSummary)).takeLast(4)
                        }
                        choice.storyPrompt != null -> {
                            val targetCharacterId = (selectedTarget as? DigitalNearbyTarget.Resident)?.motion?.character?.characterId
                            if (onWorldAction != null) onWorldAction(choice.storyPrompt)
                            else if (targetCharacterId != null) onCharacterClick(targetCharacterId)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, bottom = controlsBottomPadding + 88.dp),
            )
        }
    }
}

private fun buildInteractionChoices(
    target: DigitalNearbyTarget?,
    residents: List<CharacterSettings>,
    props: List<DigitalRoomProp>,
): List<DigitalInteractionChoice> = when (target) {
    is DigitalNearbyTarget.Resident -> {
        val name = target.motion.character.displayName
        listOf(
            DigitalInteractionChoice("聊天", "进入沉浸对话", dialogueCharacterId = target.motion.character.characterId),
            DigitalInteractionChoice("抱抱", "让这次接触进入剧情", storyPrompt = "我走到$name身边，轻轻抱了抱对方，看看对方会有什么反应。"),
            DigitalInteractionChoice("牵手", "一起行动时保持接触", storyPrompt = "我伸手去牵$name的手，想和对方一起待一会儿。"),
            DigitalInteractionChoice("一起坐", "找附近能坐的地方", storyPrompt = "我问$name要不要和我一起找个舒服的位置坐下来待一会儿。"),
            DigitalInteractionChoice("跟我来", "邀请对方跟随你的移动", storyPrompt = "我回头招呼$name跟我来，想带对方一起在这里走走。"),
            DigitalInteractionChoice("一起玩", "发起共同活动", storyPrompt = "我问$name想不想和我一起玩点什么，由我们现在所在的数字世界决定具体活动。"),
            DigitalInteractionChoice("去别处", "一起商量下一个地点", storyPrompt = "我问$name想不想和我一起换个地方，并准备从真实存在的地点里选一个。"),
        )
    }
    is DigitalNearbyTarget.Prop -> {
        val item = target.prop.item
        val quick = DigitalWorldActivityCatalog.optionsFor(item).take(6).map { (activityId, label) ->
            DigitalInteractionChoice(
                label = label,
                detail = "立即执行，不强制生成长剧情",
                quickSummary = DigitalWorldActivityCatalog.itemActivitySummary("我", item, activityId)
                    ?: "我在${item.name}旁做了“$label”。",
            )
        }
        val resident = residents.firstOrNull()
        val shared = if (resident != null && target.prop.style.kind in setOf(
                DigitalFurnitureKind.BED,
                DigitalFurnitureKind.SOFA,
                DigitalFurnitureKind.CHAIR,
                DigitalFurnitureKind.RUG,
                DigitalFurnitureKind.TABLE,
                DigitalFurnitureKind.COFFEE_TABLE,
                DigitalFurnitureKind.TV,
            )
        ) {
            val sharedVerb = when (target.prop.style.kind) {
                DigitalFurnitureKind.TV -> "一起看一会儿"
                DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> "一起在这里坐坐或玩点东西"
                DigitalFurnitureKind.BED -> "一起到床边待一会儿"
                else -> "一起坐一会儿"
            }
            listOf(
                DigitalInteractionChoice(
                    label = "叫${resident.displayName}一起",
                    detail = "共同活动会进入人物互动",
                    storyPrompt = "我看向${resident.displayName}，邀请对方和我在“${item.name}”这里$sharedVerb。",
                ),
            )
        } else emptyList()
        quick + shared
    }
    null -> emptyList()
}

@Composable
private fun DigitalInteractionMenu(
    target: DigitalNearbyTarget,
    choices: List<DigitalInteractionChoice>,
    onDismiss: () -> Unit,
    onChoice: (DigitalInteractionChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (target) {
        is DigitalNearbyTarget.Resident -> target.motion.character.displayName
        is DigitalNearbyTarget.Prop -> target.prop.item.name
    }
    val subtitle = when (target) {
        is DigitalNearbyTarget.Resident -> target.motion.activityLabel.ifBlank { "想怎么和对方相处？" }
        is DigitalNearbyTarget.Prop -> "同一件东西可以有不同用法，不再只有一个“互动”"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xF20D1916),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .15f)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Text(subtitle, color = Color(0xFF93AAA2), fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(
                    onClick = onDismiss,
                    color = Color.White.copy(alpha = .07f),
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Text("取消", color = Color(0xFFC5D4CE), fontSize = 8.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(9.dp))
            choices.chunked(2).forEach { rowChoices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowChoices.forEach { choice ->
                        Surface(
                            onClick = { onChoice(choice) },
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = .065f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(choice.label, color = Color(0xFFF0F7F3), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(choice.detail, color = Color(0xFF8FA49D), fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun DigitalActionQueueStrip(
    active: String,
    queued: List<DigitalQueuedAction>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xD90D1916),
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("行动", color = Color(0xFF8FB2A7), fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
            (listOfNotNull(active.takeIf(String::isNotBlank)) + queued.take(3).map { it.label }).take(4).forEachIndexed { index, label ->
                Surface(
                    color = if (index == 0 && active.isNotBlank()) Color(0xFF9EFFE0).copy(alpha = .13f) else Color.White.copy(alpha = .06f),
                    shape = RoundedCornerShape(99.dp),
                ) {
                    Text(label, color = Color(0xFFDCEAE4), fontSize = 7.5.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun DigitalWorldAmbientHud(
    place: String,
    people: Int,
    objects: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 220.dp),
        color = Color(0xC910201C),
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, Color(0xFFCBFFEF).copy(alpha = .16f)),
        shadowElevation = 5.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Explore, null, tint = Color(0xFFB8F8E3), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(place, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text("$people 人 · $objects 物", color = Color(0xFF9DC4B9), fontSize = 7.5.sp)
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
        modifier = modifier.size(62.dp),
        color = Color(0xB90B1715),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .13f)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            drawRoundRect(Color(0xFF738B83).copy(alpha = .16f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
            props.take(28).forEach { prop ->
                val x = prop.bounds.center.x / DIGITAL_WORLD_WIDTH * size.width
                val y = prop.bounds.center.y / DIGITAL_WORLD_HEIGHT * size.height
                drawCircle(Color(0xFFD3C7AD).copy(alpha = .50f), 1.3.dp.toPx(), Offset(x, y))
            }
            residents.forEach { resident ->
                drawCircle(Color(0xFFFFD89C), 2.0.dp.toPx(), Offset(resident.x / DIGITAL_WORLD_WIDTH * size.width, resident.y / DIGITAL_WORLD_HEIGHT * size.height))
            }
            drawCircle(Color(0xFF92FFDA), 2.9.dp.toPx(), Offset(player.x / DIGITAL_WORLD_WIDTH * size.width, player.y / DIGITAL_WORLD_HEIGHT * size.height))
            drawCircle(Color.White.copy(alpha = .75f), 2.9.dp.toPx(), Offset(player.x / DIGITAL_WORLD_WIDTH * size.width, player.y / DIGITAL_WORLD_HEIGHT * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
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
