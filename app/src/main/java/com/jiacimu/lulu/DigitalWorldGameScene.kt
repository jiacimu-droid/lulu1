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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.jiacimu.lulu.data.DigitalWorldExpansionStore
import com.jiacimu.lulu.data.DigitalWorldResident
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
    val pendingVenueAnchorId: String? = null,
    val pendingActivityId: String? = null,
    val activityLabel: String = "",
    val busyUntil: Long = 0L,
    val followPath: List<WorldVector> = emptyList(),
    val pose: ResidentPose = ResidentPose.STAND,
)

private data class DigitalNativeMotion(
    val resident: DigitalWorldResident,
    val position: WorldVector,
    val target: WorldVector,
    val nextDecisionAt: Long,
    val facingX: Float = 1f,
)

private sealed interface DigitalNearbyTarget {
    val distance: Float

    data class Resident(val motion: DigitalNpcMotion, override val distance: Float) : DigitalNearbyTarget
    data class Prop(val prop: DigitalRoomProp, override val distance: Float) : DigitalNearbyTarget
    data class NativeResident(val motion: DigitalNativeMotion, override val distance: Float) : DigitalNearbyTarget
    data class Discovery(val spot: DigitalWorldExplorationSpot, override val distance: Float) : DigitalNearbyTarget
    data class Venue(val anchor: DigitalVenueAnchor, override val distance: Float) : DigitalNearbyTarget
}

private data class DigitalQueuedAction(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val summary: String,
)

private enum class ResidentMovementChoice { HOLD_HANDS, RELEASE_HANDS, FOLLOW, STOP_FOLLOW }

private data class DigitalInteractionChoice(
    val label: String,
    val detail: String,
    val quickSummary: String? = null,
    val venueActivityId: String? = null,
    val storyPrompt: String? = null,
    val dialogueCharacterId: String? = null,
    val gameId: String? = null,
    val movement: ResidentMovementChoice? = null,
    val nativeAction: String? = null,
    val discoveryId: String? = null,
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
    /** Triggered only by a committed in-scene physical action, not by a narrative prompt. */
    onPhysicalInteraction: ((String, String) -> Unit)? = null,
    onObservedBehavior: ((String) -> Unit)? = null,
    controlsBottomPadding: Dp = 18.dp,
    controlsEnabled: Boolean = true,
    showExplorationHud: Boolean = true,
    followerIds: Set<String> = emptySet(),
    initiatedHandHoldingId: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sceneKey = remember(sceneCode, homeCharacterId) { "$sceneCode:${homeCharacterId.orEmpty()}" }
    val residents = remember(characters, world.characterLocations, sceneCode) {
        characters.filter { world.characterLocations[it.characterId] == sceneCode }
    }
    var nativeResidents by remember(sceneCode) {
        mutableStateOf(DigitalWorldExpansionStore.residentsAt(sceneCode))
    }
    LaunchedEffect(sceneCode) {
        while (isActive) {
            val updated = DigitalWorldExpansionStore.residentsAt(sceneCode)
            if (updated != nativeResidents) nativeResidents = updated
            delay(2_500L)
        }
    }
    val nativeHome = DigitalWorldExpansionStore.residentHome(sceneCode)
    val discoveredPlace = remember(sceneCode) {
        DigitalWorldExpansionStore.places().firstOrNull { it.code == sceneCode }
    }
    val roomItems = remember(world.items, homeCharacterId) {
        homeCharacterId?.let { owner -> world.items.filter { it.ownerCharacterId == owner } }.orEmpty()
    }
    val props = remember(roomItems) { buildDigitalRoomProps(roomItems) }
    val nativeHomeProps = remember(nativeHome) { nativeHome?.let(::nativeResidentHomeProps).orEmpty() }
    // NPC furniture is physically present and can be inspected, but isn't user-editable inventory.
    val interactiveProps = if (nativeHome != null) nativeHomeProps else props
    val venueAnchors = remember(sceneCode) { DigitalWorldVenueAnchors.forScene(sceneCode) }
    val discoverySpots = remember(sceneCode) { DigitalWorldExplorationSpots.forScene(sceneCode) }
    val obstacles = remember(props, nativeHomeProps) {
        (props + nativeHomeProps).mapNotNull(DigitalRoomProp::obstacle)
    }
    val stateStore = remember(context, sceneKey) { DigitalWorldPlayState(context, sceneKey) }
    var discoveredSpots by remember(sceneKey) { mutableStateOf(stateStore.loadDiscoveries()) }
    // Real, player-completed venue activities are persisted per location. A
    // returning visitor sees the outcome rather than just an old toast.
    var finishedVenueActivities by remember(sceneKey) { mutableStateOf(stateStore.loadVenueActivities()) }
    var acquaintedResidents by remember(sceneKey) { mutableStateOf(stateStore.loadAcquaintedResidents()) }
    val restored = remember(stateStore) { stateStore.loadPosition() }
    val safeStart = remember(restored, obstacles) { digitalSafeStart(restored, obstacles) }
    // The actual activity ledger survives closing the scene. A portrait alone
    // cannot determine whether somebody is sitting or lying on a real item.
    var residentActivities by remember(sceneKey, residents.map { it.characterId }) {
        mutableStateOf(residents.associate { it.characterId to
            com.jiacimu.lulu.data.DigitalWorldActivityStateStore.ongoingActivity(it.characterId) })
    }
    LaunchedEffect(sceneKey, residents.map { it.characterId }, props) {
        while (isActive) {
            val fresh = residents.associate { it.characterId to
                com.jiacimu.lulu.data.DigitalWorldActivityStateStore.ongoingActivity(it.characterId) }
            if (fresh != residentActivities) residentActivities = fresh
            delay(900L)
        }
    }
    fun furniturePoseFor(id: String): Pair<ResidentPose, WorldVector>? {
        val activity = residentActivities[id] ?: return null
        val prop = props.firstOrNull { it.item.id == activity.first } ?: return null
        val pose = DigitalResidentFurniturePose.forActivity(activity.second, prop.style.kind) ?: return null
        return pose to DigitalResidentFurniturePose.anchor(prop.bounds, pose)
    }
    val userAvatar = remember(context) { rememberUserAvatar(context) }
    val placeLabel = remember(sceneCode, homeCharacterId, world.homes) {
        homeCharacterId?.let { world.homes[it]?.name }
            ?: DigitalWorldExpansionStore.residenceLabel(sceneCode)
            ?: discoveredPlace?.label
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
    var arcadeGameId by rememberSaveable(sceneKey) { mutableStateOf<String?>(null) }
    var interactionMessage by remember(sceneKey) { mutableStateOf("") }
    var roleOffer by remember(sceneKey) {
        mutableStateOf<com.jiacimu.lulu.data.DigitalWorldLiveScene.Offer?>(null)
    }
    LaunchedEffect(sceneKey, controlsEnabled, residents.map { it.characterId }) {
        while (controlsEnabled) {
            roleOffer = residents.asSequence()
                .mapNotNull { com.jiacimu.lulu.data.DigitalWorldLiveScene.offer(it.characterId) }
                .firstOrNull()
            kotlinx.coroutines.delay(600L)
        }
        roleOffer = null
    }
    var menuTargetKey by remember(sceneKey) { mutableStateOf<String?>(null) }
    var queuedActions by remember(sceneKey) { mutableStateOf<List<DigitalQueuedAction>>(emptyList()) }
    var activeActionLabel by remember(sceneKey) { mutableStateOf("") }
    // Physical player interactions are NOT prompts that force an immersive
    // story modal. Keep their mode separate from the narrative conversation.
    var handHoldingId by remember(sceneKey) { mutableStateOf(stateStore.loadHandHoldingId()) }
    var requestedFollowers by remember(sceneKey) { mutableStateOf(stateStore.loadFollowerIds()) }
    var suspendedFollowers by remember(sceneKey) { mutableStateOf(stateStore.loadSuspendedFollowers()) }
    val effectiveHoldingId = handHoldingId ?: initiatedHandHoldingId
    val activeFollowerIds = (followerIds - suspendedFollowers) + requestedFollowers + listOfNotNull(effectiveHoldingId)

    var npcMotions by remember(sceneKey, residents.map { it.characterId }) {
        mutableStateOf(
            residents.mapIndexed { index, character ->
                val base = digitalSafeStart(residentStart(index, character.characterId, venueAnchors), obstacles)
                val resting = furniturePoseFor(character.characterId)
                val start = resting?.second ?: base
                DigitalNpcMotion(character, start, start,
                    System.currentTimeMillis() + 2_100L + index * 760L,
                    pose = resting?.first ?: ResidentPose.STAND)
            },
        )
    }

    // Native residents have actual scene-space positions and gentle local wandering.
    // A different scene can show them only after their persisted currentLocationCode changes.
    var nativeMotions by remember(sceneKey, nativeResidents.map { it.id }) {
        mutableStateOf(nativeResidents.mapIndexed { index, resident ->
            val base = if (sceneCode == resident.homeLocationCode) WorldVector(960f, 630f)
                else residentStart(index + residents.size, resident.id, venueAnchors)
            val safe = digitalSafeStart(stateStore.loadNativePosition(resident.id, base), obstacles)
            DigitalNativeMotion(resident, safe, safe,
                System.currentTimeMillis() + 3_400L + index * 1_000L)
        })
    }

    val ambience = rememberInfiniteTransition(label = "digital-world-light")
    val lightPhase by ambience.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_800), RepeatMode.Reverse),
        label = "digital-world-light-phase",
    )

    arcadeGameId?.let { gameId ->
        DigitalWorldArcadeScreen(gameId, residents, onBack = { arcadeGameId = null }, modifier = modifier)
        return
    }
    com.jiacimu.lulu.games.GameAmbientSoundscape(com.jiacimu.lulu.games.GameSoundscape.Meeting)

    val worldScale = (viewportSize.height.toFloat() / 650f).coerceAtLeast(.35f)
    val viewportWorldWidth = viewportSize.width.toFloat() / worldScale
    val viewportWorldHeight = viewportSize.height.toFloat() / worldScale

    val latestObservedBehavior by rememberUpdatedState(onObservedBehavior)
    val latestResidents by rememberUpdatedState(residents)
    var liveToken by remember(sceneKey) { mutableStateOf("") }
    DisposableEffect(sceneKey, controlsEnabled) {
        val token = if (controlsEnabled) com.jiacimu.lulu.data.DigitalWorldLiveScene.begin(context, sceneCode, placeLabel) else ""
        liveToken = token
        onDispose { com.jiacimu.lulu.data.DigitalWorldLiveScene.end(token) }
    }
    LaunchedEffect(sceneKey, liveToken, controlsEnabled) {
        if (!controlsEnabled || liveToken.isBlank()) return@LaunchedEffect
        var lastDetail = ""
        var lastPosition = playerPosition
        var lastObservationAt = 0L
        var lastMoving = false
        while (isActive) {
            val near = npcMotions.minByOrNull { it.position.distanceTo(playerPosition) }
                ?.takeIf { it.position.distanceTo(playerPosition) < 180f }
            val nearNative = nativeMotions.minByOrNull { it.position.distanceTo(playerPosition) }
                ?.takeIf { it.position.distanceTo(playerPosition) < 180f }
            val anchor = venueAnchors.minByOrNull { it.position.distanceTo(playerPosition) }
                ?.takeIf { it.position.distanceTo(playerPosition) < 180f }
            val prop = props.minByOrNull { it.bounds.center.distanceTo(playerPosition) }
                ?.takeIf { it.bounds.center.distanceTo(playerPosition) < 160f }
            val detail = when {
                near != null -> "在${near.character.displayName}附近"
                nearNative != null -> "在原住民${nearNative.resident.name}附近"
                prop != null -> "在${prop.item.name}附近"
                anchor != null -> "在${anchor.label}附近"
                else -> "在场景${if (playerPosition.x < 800f) "左" else "右"}侧${if (playerPosition.y < 650f) "上" else "下"}方"
            }
            val moving = playerPosition.distanceTo(lastPosition) > 35f
            com.jiacimu.lulu.data.DigitalWorldLiveScene.update(liveToken, playerPosition.x, playerPosition.y,
                latestResidents.map { it.characterId }.toSet(), "$detail，${if (moving) "正在走动" else "暂时停留"}")
            val now = System.currentTimeMillis()
            if ((detail != lastDetail || (lastMoving && !moving)) && now - lastObservationAt >= 6_000L) {
                latestObservedBehavior?.invoke("$detail，${if (moving) "正在走动" else "停下来暂时停留"}")
                lastDetail = detail
                lastObservationAt = now
            }
            lastPosition = playerPosition
            lastMoving = moving
            delay(1_600L)
        }
    }

    val latestPosition by rememberUpdatedState(playerPosition)
    val latestNativeMotions by rememberUpdatedState(nativeMotions)
    val latestQuest by rememberUpdatedState(questStage)
    DisposableEffect(sceneKey, stateStore) {
        onDispose {
            stateStore.save(latestPosition, latestQuest)
            stateStore.saveNativePositions(latestNativeMotions)
            // A hand hold cannot remain physically attached after the player
            // exits this scene. The persisted relationship is in the ledger,
            // not a ghost finger connection when they next enter.
            stateStore.saveCompanionship(null, emptySet(), emptySet())
        }
    }
    LaunchedEffect(sceneKey, stateStore) {
        while (isActive) {
            delay(1_600)
            stateStore.save(playerPosition, questStage)
            stateStore.saveNativePositions(nativeMotions)
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
        delay(320)
        activeActionLabel = queued.label
        interactionMessage = queued.label
        WorldFirstExplorationMemory.record(
            context = context,
            worldId = "digital-world",
            locationId = sceneCode,
            locationLabel = placeLabel,
            action = queued.summary,
        )
        latestObservedBehavior?.invoke(queued.summary)
        queuedActions = queuedActions.drop(1)
        delay(800)
        if (activeActionLabel == queued.label) activeActionLabel = ""
    }

    LaunchedEffect(sceneKey, obstacles, viewportSize, residents.map { it.characterId }, nativeResidents.map { it.id }, controlsEnabled, props, venueAnchors, activeFollowerIds, residentActivities) {
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
                    } else deltaVector.normalized()
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
                    if (motion.busyUntil > now) return@mapIndexed motion.copy(target = motion.position)
                    if (motion.busyUntil > 0L && now >= motion.busyUntil) {
                        return@mapIndexed motion.copy(
                            activityLabel = "",
                            busyUntil = 0L,
                            nextDecisionAt = now + 3_500L + index * 420L,
                        )
                    }

                    // Animation reflects confirmed activity; it never executes random world actions.
                    val characterId = motion.character.characterId
                    val sceneGoal = com.jiacimu.lulu.data.DigitalWorldLiveScene.goal(characterId)
                    val approaching = sceneGoal?.action in setOf(
                        "approach_user", "offer_hand", "offer_hug", "offer_follow")
                    val following = characterId in activeFollowerIds || approaching
                    val handHolding = characterId == effectiveHoldingId
                    val occupiedFurniture = if (!following && sceneGoal == null) furniturePoseFor(characterId) else null
                    if (occupiedFurniture != null) {
                        // A verified resting activity is an occupied furniture
                        // slot. Do not pathfind into the furniture's collision
                        // box and leave the resident standing beside the bed.
                        return@mapIndexed motion.copy(position = occupiedFurniture.second,
                            target = occupiedFurniture.second, followPath = emptyList(),
                            pose = occupiedFurniture.first,
                            activityLabel = residentActivities[characterId]?.second?.let { id ->
                                val prop = props.firstOrNull { it.item.id == residentActivities[characterId]?.first }
                                prop?.let { item -> DigitalWorldActivityCatalog.optionsFor(item.item)
                                    .firstOrNull { it.first == id }?.second }
                            }.orEmpty())
                    }
                    var followPath = if (following || sceneGoal != null) motion.followPath else emptyList()
                    var target = motion.target
                    var nextDecision = motion.nextDecisionAt
                    val pendingItemId: String? = null
                    val pendingVenueId: String? = null
                    val pendingActivityId: String? = null
                    var activityLabel = motion.activityLabel
                    if (following) {
                        val followPosition = if (handHolding) {
                            playerPosition + WorldVector(if (facingX >= 0f) 64f else -64f, 10f)
                        } else playerPosition
                        if (motion.position.distanceTo(followPosition) < (if (handHolding) 18f else 90f)) {
                            followPath = emptyList()
                            target = motion.position
                        } else {
                            if (now >= nextDecision || (followPath.isEmpty() && motion.activityLabel != (if (handHolding) "牵着手同行" else "跟随你"))) {
                                followPath = com.jiacimu.lulu.games.worldFollowPath(motion.position, followPosition,
                                    DIGITAL_WORLD_BOUNDS, obstacles, 28f)
                                nextDecision = now + 750L
                            }
                            while (followPath.isNotEmpty() && motion.position.distanceTo(followPath.first()) < 14f) {
                                followPath = followPath.drop(1)
                            }
                            target = followPath.firstOrNull() ?: motion.position
                        }
                        activityLabel = if (handHolding) "牵着手同行" else if (approaching) "走近你" else "跟随你"
                        if (approaching && motion.position.distanceTo(playerPosition) < 90f) {
                            com.jiacimu.lulu.data.DigitalWorldLiveScene.arrived(characterId, sceneGoal!!.id)
                            activityLabel = "来到你身边"
                        }
                    } else if (sceneGoal?.action == "wait_at_entrance") {
                        val entrance = digitalSafeStart(WorldVector(800f, 820f), obstacles)
                        if (now >= nextDecision || followPath.isEmpty()) {
                            followPath = com.jiacimu.lulu.games.worldFollowPath(motion.position, entrance,
                                DIGITAL_WORLD_BOUNDS, obstacles, 28f)
                            nextDecision = now + 750L
                        }
                        while (followPath.isNotEmpty() && motion.position.distanceTo(followPath.first()) < 14f)
                            followPath = followPath.drop(1)
                        target = followPath.firstOrNull() ?: motion.position
                        activityLabel = "走向入口"
                        if (motion.position.distanceTo(entrance) < 65f) {
                            com.jiacimu.lulu.data.DigitalWorldLiveScene.arrived(characterId, sceneGoal.id)
                            target = motion.position
                            activityLabel = "在入口等候"
                        }
                    } else if (now >= nextDecision) {
                        val activity = com.jiacimu.lulu.data.DigitalWorldActivityStateStore.ongoingActivity(motion.character.characterId)
                        val item = props.firstOrNull { it.item.id == activity?.first }
                        val venue = venueAnchors.firstOrNull { activity?.second in it.activityIds }
                        target = item?.bounds?.center ?: venue?.position ?: motion.position
                        activityLabel = activity?.second?.let { id ->
                            (item?.let { DigitalWorldActivityCatalog.optionsFor(it.item) }
                                ?: DigitalWorldActivityCatalog.locationOptions(sceneCode))
                                .firstOrNull { it.first == id }?.second
                        }.orEmpty()
                        nextDecision = now + 1_000L
                    }

                    val directionToTarget = target - motion.position
                    val moved = if (directionToTarget.length > 16f) {
                        moveInWorld(
                            motion.position,
                            directionToTarget,
                            speed = if (following) { if (motion.position.distanceTo(playerPosition) > 230f) 330f else 260f } else 78f,
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
                        pendingItemId = pendingItemId,
                        pendingVenueAnchorId = pendingVenueId,
                        pendingActivityId = pendingActivityId,
                        activityLabel = activityLabel,
                        followPath = followPath,
                        pose = ResidentPose.STAND,
                    )
                }
                nativeMotions = nativeMotions.mapIndexed { index, motion ->
                    var target = motion.target
                    var nextDecision = motion.nextDecisionAt
                    if (now >= nextDecision) {
                        val epoch = now / 12_000L
                        val hash = (motion.resident.id.hashCode().toLong() * 37L + epoch * 157L + index * 71L) and Long.MAX_VALUE
                        val proposal = if (sceneCode == motion.resident.homeLocationCode) {
                            val corners = listOf(WorldVector(970f, 640f), WorldVector(620f, 755f),
                                WorldVector(1_080f, 790f), WorldVector(720f, 510f))
                            corners[(hash % corners.size).toInt()]
                        } else residentWanderTarget(motion.resident.id, now, index, venueAnchors)
                        target = digitalSafeStart(proposal, obstacles)
                        nextDecision = now + 7_000L + (hash % 3_000L)
                    }
                    val movement = target - motion.position
                    val moved = if (movement.length > 18f) moveInWorld(
                        motion.position, movement, speed = 52f, deltaSeconds = delta,
                        radius = 28f, bounds = DIGITAL_WORLD_BOUNDS, obstacles = obstacles,
                    ).position else motion.position
                    motion.copy(position = moved, target = target, nextDecisionAt = nextDecision,
                        facingX = movement.x.takeIf { kotlin.math.abs(it) > 3f } ?: motion.facingX)
                }
            }
        }
    }

    val nearestNpc = npcMotions.minByOrNull { playerPosition.distanceTo(it.position) }?.let { motion ->
        DigitalNearbyTarget.Resident(motion, playerPosition.distanceTo(motion.position))
    }
    val nearestNative = nativeMotions.minByOrNull { playerPosition.distanceTo(it.position) }?.let { motion ->
        DigitalNearbyTarget.NativeResident(motion, playerPosition.distanceTo(motion.position))
    }
    val nearestDiscovery = discoverySpots.minByOrNull { playerPosition.distanceTo(it.position) }?.let { spot ->
        DigitalNearbyTarget.Discovery(spot, playerPosition.distanceTo(spot.position))
    }
    val nearestProp = interactiveProps.minByOrNull { playerPosition.distanceTo(it.bounds.center) }?.let { prop ->
        DigitalNearbyTarget.Prop(prop, playerPosition.distanceTo(prop.bounds.center))
    }
    val nearestVenue = venueAnchors.minByOrNull { playerPosition.distanceTo(it.position) }?.let { anchor ->
        DigitalNearbyTarget.Venue(anchor, playerPosition.distanceTo(anchor.position))
    }?.takeIf { it.distance <= it.anchor.radius }
    val nearby = listOfNotNull(nearestNpc, nearestNative, nearestProp, nearestVenue, nearestDiscovery)
        .minByOrNull(DigitalNearbyTarget::distance)
        ?.takeIf { target ->
            when (target) {
                is DigitalNearbyTarget.Venue -> target.distance <= target.anchor.radius
                is DigitalNearbyTarget.Discovery -> target.distance <= target.spot.radius
                else -> target.distance <= 142f
            }
        }
    val actionLabel = when (nearby) {
        is DigitalNearbyTarget.Venue -> "使用"
        is DigitalNearbyTarget.Discovery -> "探索"
        null -> "靠近"
        else -> "互动"
    }

    val selectedTarget = when {
        menuTargetKey?.startsWith("npc:") == true -> {
            val id = menuTargetKey!!.removePrefix("npc:")
            npcMotions.firstOrNull { it.character.characterId == id }?.let {
                DigitalNearbyTarget.Resident(it, playerPosition.distanceTo(it.position))
            }
        }
        menuTargetKey?.startsWith("native:") == true -> {
            val id = menuTargetKey!!.removePrefix("native:")
            nativeMotions.firstOrNull { it.resident.id == id }?.let {
                DigitalNearbyTarget.NativeResident(it, playerPosition.distanceTo(it.position))
            }
        }
        menuTargetKey?.startsWith("discovery:") == true -> {
            val id = menuTargetKey!!.removePrefix("discovery:")
            discoverySpots.firstOrNull { it.id == id }?.let {
                DigitalNearbyTarget.Discovery(it, playerPosition.distanceTo(it.position))
            }
        }
        menuTargetKey?.startsWith("prop:") == true -> {
            val id = menuTargetKey!!.removePrefix("prop:")
            interactiveProps.firstOrNull { it.item.id == id }?.let {
                DigitalNearbyTarget.Prop(it, playerPosition.distanceTo(it.bounds.center))
            }
        }
        menuTargetKey?.startsWith("venue:") == true -> {
            val id = menuTargetKey!!.removePrefix("venue:")
            venueAnchors.firstOrNull { it.id == id }?.let {
                DigitalNearbyTarget.Venue(it, playerPosition.distanceTo(it.position))
            }
        }
        else -> null
    }?.takeIf { target ->
        when (target) {
            is DigitalNearbyTarget.Venue -> target.distance <= target.anchor.radius + 45f
            is DigitalNearbyTarget.Discovery -> target.distance <= target.spot.radius + 45f
            else -> target.distance <= 190f
        }
    }

    LaunchedEffect(selectedTarget, controlsEnabled) {
        if (selectedTarget == null || !controlsEnabled) menuTargetKey = null
    }

    val interactionChoices = remember(selectedTarget, residents, interactiveProps, sceneCode, placeLabel, effectiveHoldingId, activeFollowerIds, discoveredSpots) {
        buildInteractionChoices(selectedTarget, residents, sceneCode, placeLabel, effectiveHoldingId, activeFollowerIds, discoveredSpots)
    }

    Box(
        modifier.fillMaxSize().background(Color(0xFF091311)).onSizeChanged { viewportSize = it },
    ) {
        val moveInputModifier = if (controlsEnabled && selectedTarget == null) {
            Modifier.pointerInput(sceneKey, viewportSize) {
                detectTapGestures { tap ->
                    val point = WorldVector(tap.x / worldScale + camera.x, tap.y / worldScale + camera.y)
                    val native = nativeMotions.firstOrNull { motion ->
                        kotlin.math.abs(motion.position.x - point.x) < 45f &&
                            point.y >= motion.position.y - 118f && point.y <= motion.position.y + 20f
                    }
                    val discovery = discoverySpots.minByOrNull { it.position.distanceTo(point) }
                        ?.takeIf { it.position.distanceTo(point) < 48f }
                    when {
                        native != null -> {
                            if (playerPosition.distanceTo(native.position) <= 175f) {
                                menuTargetKey = "native:${native.resident.id}"
                            } else {
                                tapTarget = native.position
                                interactionMessage = "走近${native.resident.name}再打个招呼"
                            }
                        }
                        discovery != null -> {
                            if (playerPosition.distanceTo(discovery.position) <= discovery.radius + 40f) {
                                menuTargetKey = "discovery:${discovery.id}"
                            } else {
                                tapTarget = discovery.position
                                interactionMessage = "走近${discovery.label}再探索"
                            }
                        }
                        else -> {
                            tapTarget = point
                            GameSoundEffects.play(GameSoundEffect.Move)
                        }
                    }
                }
            }
        } else Modifier

        Canvas(Modifier.matchParentSize().then(moveInputModifier)) {
            withTransform({
                translate(-camera.x * worldScale, -camera.y * worldScale)
                scale(worldScale, worldScale, pivot = Offset.Zero)
            }) {
                if (homeCharacterId != null || nativeHome != null) {
                    drawDigitalHomeWorld(if (nativeHome != null) nativeHomeProps else props, lightPhase)
                } else if (discoveredPlace != null) {
                    drawDigitalDiscoveredWorld(discoveredPlace, lightPhase)
                } else if (!drawDigitalPublicPlaceWorld(sceneCode, lightPhase)) {
                    drawDigitalSharedWorld(sceneCode, lightPhase)
                }
                drawDigitalExplorationMarkers(discoverySpots, discoveredSpots, lightPhase)
                drawDigitalVenueOutcomes(sceneCode, venueAnchors, finishedVenueActivities, lightPhase)
                drawNativeWorldResidents(nativeMotions.map { motion ->
                    DigitalNativeResidentPose(motion.resident, motion.position,
                        motion.position.distanceTo(motion.target) > 18f, motion.facingX)
                }, lightPhase)
                // The player actually greeted these residents on an earlier
                // visit. Their tiny recognition marks survive scene reloads.
                nativeMotions.filter { it.resident.id in acquaintedResidents }.forEach { motion ->
                    val at = Offset(motion.position.x, motion.position.y - 116f)
                    drawCircle(Color(0xFFD6EEC9), 18f, at)
                    drawCircle(Color(0xFF4D806E), 13f, at)
                    drawCircle(Color(0xFFF7E4A0), 6f, at)
                }
            }

            val focus = if (showExplorationHud && controlsEnabled) {
                when (val target = selectedTarget ?: nearby) {
                    is DigitalNearbyTarget.Resident -> target.motion.position
                    is DigitalNearbyTarget.NativeResident -> target.motion.position
                    is DigitalNearbyTarget.Discovery -> target.spot.position
                    is DigitalNearbyTarget.Prop -> target.prop.bounds.center
                    is DigitalNearbyTarget.Venue -> target.anchor.position
                    null -> null
                }
            } else null
            focus?.let { worldPoint ->
                val focusPoint = Offset((worldPoint.x - camera.x) * worldScale, (worldPoint.y - camera.y) * worldScale)
                drawCircle(Color(0xFFC9FFE9).copy(alpha = .10f + lightPhase * .10f), 31.dp.toPx() + lightPhase * 3.dp.toPx(), focusPoint)
                drawCircle(Color(0xFFE7FFF5).copy(alpha = .56f), 22.dp.toPx() + lightPhase * 2.dp.toPx(), focusPoint, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
            }

            repeat(12) { index ->
                val seed = (sceneKey.hashCode() * 31L + index * 977L) and Long.MAX_VALUE
                val x = ((seed % 1_000L) / 1_000f * size.width + lightPhase * 18.dp.toPx()) % size.width
                val y = ((seed / 43L % 1_000L) / 1_000f * size.height)
                drawCircle(Color(0xFFFFF8DE).copy(alpha = .05f + (index % 4) * .018f), (1f + index % 2).dp.toPx(), Offset(x, y))
            }
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x8A081110)), center = Offset(size.width * .50f, size.height * .45f), radius = size.maxDimension * .76f))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .13f), Color.Transparent, Color.Black.copy(alpha = .20f))))
        }

        // Draw the actual continuing contact between the two moving pawns.
        // Never stretch an elastic line across the room while navigating
        // around furniture: hands appear linked only when physically close.
        val heldResident = npcMotions.firstOrNull { it.character.characterId == effectiveHoldingId }
        if (heldResident != null && heldResident.position.distanceTo(playerPosition) <= 135f) {
            Canvas(Modifier.matchParentSize()) {
                val playerHand = Offset(
                    (playerPosition.x - camera.x) * worldScale,
                    (playerPosition.y - camera.y) * worldScale - 42.dp.toPx(),
                )
                val companionHand = Offset(
                    (heldResident.position.x - camera.x) * worldScale,
                    (heldResident.position.y - camera.y) * worldScale - 42.dp.toPx(),
                )
                val handDirection = (companionHand - playerHand).let { vector ->
                    val magnitude = vector.getDistance().coerceAtLeast(1f)
                    vector / magnitude
                }
                val nearPlayer = playerHand + handDirection * 17.dp.toPx()
                val nearCompanion = companionHand - handDirection * 17.dp.toPx()
                drawLine(
                    Color(0xFFFFD4BC), nearPlayer, nearCompanion,
                    strokeWidth = 4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawCircle(Color(0xFFFFE5D4), 3.dp.toPx(), nearPlayer)
                drawCircle(Color(0xFFFFE5D4), 3.dp.toPx(), nearCompanion)
            }
        }

        val halfPawnWidth = with(density) { 31.dp.toPx() }
        val pawnFoot = with(density) { 84.dp.toPx() }
        npcMotions.forEach { motion ->
            val screenX = (motion.position.x - camera.x) * worldScale
            val screenY = (motion.position.y - camera.y) * worldScale
            GameCharacterPawn(
                avatarUri = motion.character.avatarUri,
                fallback = motion.character.displayName.take(1),
                moving = motion.pose == ResidentPose.STAND &&
                    motion.busyUntil <= System.currentTimeMillis() && motion.position.distanceTo(motion.target) > 20f,
                pose = motion.pose,
                facingX = motion.facingX,
                coat = Color(0xFF526B67),
                modifier = Modifier
                    .offset {
                        val halfWidth = if (motion.pose == ResidentPose.LIE) with(density) { 47.dp.toPx() } else halfPawnWidth
                        val foot = if (motion.pose == ResidentPose.LIE) with(density) { 61.dp.toPx() }
                            else if (motion.pose == ResidentPose.SIT) with(density) { 72.dp.toPx() } else pawnFoot
                        IntOffset((screenX - halfWidth).roundToInt(), (screenY - foot).roundToInt())
                    }
                    .zIndex(motion.position.y)
                    .clickable(enabled = controlsEnabled && selectedTarget == null) {
                        tapTarget = motion.position
                        interactionMessage = "靠近 ${motion.character.displayName} 后可以选择怎么相处"
                    },
            )
            if (showExplorationHud && motion.activityLabel.isNotBlank()) {
                Surface(
                    modifier = Modifier.offset { IntOffset((screenX - with(density) { 54.dp.toPx() }).roundToInt(), (screenY - with(density) { 112.dp.toPx() }).roundToInt()) }.zIndex(motion.position.y + 4f),
                    color = Color(0xD90E1916),
                    shape = RoundedCornerShape(99.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
                ) {
                    Text(motion.activityLabel, color = Color(0xFFDDEAE4), fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }

        // Labels track the real moving residents; these are not static map annotations.
        if (showExplorationHud) nativeMotions.forEach { motion ->
            val x = (motion.position.x - camera.x) * worldScale
            val y = (motion.position.y - 118f - camera.y) * worldScale
            Surface(
                modifier = Modifier.offset {
                    IntOffset((x - with(density) { 34.dp.toPx() }).roundToInt(), y.roundToInt())
                }.zIndex(motion.position.y + 2f),
                color = Color(0xD7182D26),
                shape = RoundedCornerShape(99.dp),
            ) {
                Text(motion.resident.name, color = Color(0xFFF1F8F3), fontSize = 9.sp,
                    maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
            modifier = Modifier.offset { IntOffset((playerScreenX - halfPawnWidth).roundToInt(), (playerScreenY - pawnFoot).roundToInt()) }.zIndex(playerPosition.y + .5f),
        )

        if (showExplorationHud) {
            DigitalWorldAmbientHud(
                place = placeLabel,
                people = residents.size + nativeMotions.size,
                objects = interactiveProps.size + venueAnchors.size + discoverySpots.size,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
            if (homeCharacterId == null) {
                DigitalWorldMiniMap(
                    player = playerPosition,
                    residents = npcMotions.map(DigitalNpcMotion::position) + nativeMotions.map(DigitalNativeMotion::position),
                    props = interactiveProps,
                    anchors = venueAnchors,
                    discoveries = discoverySpots.map(DigitalWorldExplorationSpot::position),
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
            Surface(color = Color(0xE3152420), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFB7FFE8).copy(alpha = .26f)), shadowElevation = 7.dp) {
                Text(interactionMessage, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
            }
        }

        if (controlsEnabled && roleOffer != null) {
            val proposal = roleOffer!!
            val name = residents.firstOrNull { it.characterId == proposal.characterId }
                ?.displayName ?: "角色"
            val offerText = when (proposal.action) {
                "offer_hand" -> "$name 走近你，主动伸手问你要不要牵手。"
                "offer_hug" -> "$name 走近你，问可不可以抱一下。"
                else -> "$name 走过来，想约你一起逛逛。"
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    com.jiacimu.lulu.data.DigitalWorldLiveScene.replyToOffer(
                        proposal.characterId, proposal.id, false)
                    roleOffer = null
                },
                title = { Text("角色主动邀请") },
                text = { Text(offerText) },
                confirmButton = {
                    TextButton(onClick = {
                        val agreed = com.jiacimu.lulu.data.DigitalWorldLiveScene.replyToOffer(
                            proposal.characterId, proposal.id, true)
                        roleOffer = null
                        if (agreed != null) {
                            when (proposal.action) {
                                "offer_hand" -> {
                                    handHoldingId = proposal.characterId
                                    requestedFollowers = requestedFollowers + proposal.characterId
                                    suspendedFollowers = suspendedFollowers - proposal.characterId
                                    interactionMessage = "你同意了牵手，之后可以继续一起走。"
                                }
                                "offer_follow" -> {
                                    requestedFollowers = requestedFollowers + proposal.characterId
                                    suspendedFollowers = suspendedFollowers - proposal.characterId
                                    interactionMessage = "你同意了结伴探索，之后可以自由走动。"
                                }
                                "offer_hug" -> {
                                    interactionMessage = "你同意了角色主动提出的拥抱。"
                                    // A role-initiated, accepted hug starts a
                                    // character-owned meeting turn, not a user command.
                                    onCharacterClick(proposal.characterId)
                                }
                            }
                            stateStore.saveCompanionship(handHoldingId, requestedFollowers,
                                suspendedFollowers)
                        }
                    }) { Text("接受") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        com.jiacimu.lulu.data.DigitalWorldLiveScene.replyToOffer(
                            proposal.characterId, proposal.id, false)
                        roleOffer = null
                    }) { Text("暂不") }
                },
            )
        }

        if (controlsEnabled && queuedActions.isNotEmpty()) {
            DigitalActionQueueStrip(
                active = activeActionLabel,
                queued = queuedActions,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 90.dp, end = 90.dp, bottom = controlsBottomPadding + 88.dp),
            )
        }

        if (controlsEnabled) {
            WorldVirtualJoystick(
                value = joystick,
                onValueChanged = { next ->
                    if (selectedTarget == null) {
                        if (joystick.length <= .05f && next.length > .05f) GameSoundEffects.play(GameSoundEffect.Move)
                        joystick = next
                    }
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
                        is DigitalNearbyTarget.NativeResident -> "native:${target.motion.resident.id}"
                        is DigitalNearbyTarget.Discovery -> "discovery:${target.spot.id}"
                        is DigitalNearbyTarget.Prop -> "prop:${target.prop.item.id}"
                        is DigitalNearbyTarget.Venue -> "venue:${target.anchor.id}"
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
                        choice.movement != null -> {
                            val characterId = (selectedTarget as? DigitalNearbyTarget.Resident)
                                ?.motion?.character?.characterId
                            if (characterId != null) {
                                when (choice.movement) {
                                    ResidentMovementChoice.HOLD_HANDS -> {
                                        handHoldingId = characterId
                                        suspendedFollowers = suspendedFollowers - characterId
                                        com.jiacimu.lulu.data.DigitalWorldActivityStateStore.pauseActivity(
                                            characterId, "玩家邀请一起牵手走动，原本的家具活动暂时结束")
                                        interactionMessage = "已牵手 · 继续移动，你们会一起走"
                                    }
                                    ResidentMovementChoice.RELEASE_HANDS -> {
                                        if (effectiveHoldingId == characterId) handHoldingId = null
                                        interactionMessage = "已松开手，可以各自行动"
                                    }
                                    ResidentMovementChoice.FOLLOW -> {
                                        requestedFollowers = requestedFollowers + characterId
                                        suspendedFollowers = suspendedFollowers - characterId
                                        com.jiacimu.lulu.data.DigitalWorldActivityStateStore.pauseActivity(
                                            characterId, "玩家邀请一起探索，角色离开当前家具")
                                        interactionMessage = "已开始同行 · 你可以自由探索"
                                    }
                                    ResidentMovementChoice.STOP_FOLLOW -> {
                                        requestedFollowers = requestedFollowers - characterId
                                        suspendedFollowers = suspendedFollowers + characterId
                                        if (effectiveHoldingId == characterId) handHoldingId = null
                                        interactionMessage = "已结束跟随，恢复自由活动"
                                    }
                                }
                                stateStore.saveCompanionship(handHoldingId, requestedFollowers, suspendedFollowers)
                                onPhysicalInteraction?.invoke(characterId, choice.movement.name)
                                WorldFirstExplorationMemory.record(
                                    context = context, worldId = "digital-world", locationId = sceneCode,
                                    locationLabel = placeLabel, action = interactionMessage)
                            }
                        }
                        choice.nativeAction != null -> {
                            val resident = (selectedTarget as? DigitalNearbyTarget.NativeResident)?.motion?.resident
                            if (resident == null || DigitalWorldExpansionStore.residentsAt(sceneCode)
                                .none { it.id == resident.id }) {
                                interactionMessage = "原住民已经离开这里"
                            } else {
                                val summary = when (choice.nativeAction) {
                                    "greet" -> "我在${placeLabel}向原住民${resident.name}打了招呼。"
                                    "identity" -> "我在${placeLabel}认识了${resident.name}，${resident.identity}；对方的家位于这个数字世界。"
                                    else -> "我在${placeLabel}观察到原住民${resident.name}正在这里活动。"
                                }
                                if (choice.nativeAction != "observe") {
                                    val persisted = DigitalWorldExpansionStore.moveResident(resident.id, sceneCode)
                                    if (persisted == null) {
                                        interactionMessage = "互动保存失败"
                                    } else {
                                        val alreadyKnown = resident.id in acquaintedResidents
                                        val known = acquaintedResidents + resident.id
                                        if (stateStore.saveAcquaintedResidents(known)) {
                                            acquaintedResidents = known
                                            queuedActions = (queuedActions + DigitalQueuedAction(
                                                label = if (alreadyKnown) "再见到${resident.name}" else choice.label,
                                                summary = summary + " 这次相遇已留在世界探索档案中。",
                                            )).takeLast(4)
                                        } else interactionMessage = "这次认识还没能存档"
                                    }
                                } else {
                                    queuedActions = (queuedActions + DigitalQueuedAction(label = choice.label, summary = summary)).takeLast(4)
                                }
                            }
                        }
                        choice.discoveryId != null -> {
                            val spot = discoverySpots.firstOrNull { it.id == choice.discoveryId }
                            if (spot != null) {
                                val seen = spot.id in discoveredSpots
                                val updated = discoveredSpots + spot.id
                                if (seen || stateStore.saveDiscoveries(updated)) {
                                    discoveredSpots = updated
                                    queuedActions = (queuedActions + DigitalQueuedAction(
                                        label = if (seen) "再次观察" else "发现 ${spot.label}",
                                        summary = "我在${placeLabel}探查了${spot.label}。${spot.description}",
                                    )).takeLast(4)
                                } else interactionMessage = "探索记录保存失败"
                            }
                        }
                        choice.gameId != null -> {
                            latestObservedBehavior?.invoke("打开游戏馆游戏，开始游玩")
                            arcadeGameId = choice.gameId
                        }
                        choice.dialogueCharacterId != null -> {
                            questStage = questStage.coerceAtLeast(1)
                            latestObservedBehavior?.invoke("主动找${com.jiacimu.lulu.data.MigratedDomainStores.characters.get(choice.dialogueCharacterId).displayName}开始交流")
                            onCharacterClick(choice.dialogueCharacterId)
                        }
                        choice.quickSummary != null -> {
                            val activity = choice.venueActivityId
                            val saved = if (activity == null) true else
                                stateStore.saveVenueActivity(finishedVenueActivities + activity)
                            if (saved) {
                                if (activity != null) finishedVenueActivities = finishedVenueActivities + activity
                                questStage = questStage.coerceAtLeast(2)
                                queuedActions = (queuedActions + DigitalQueuedAction(
                                    label = choice.label, summary = choice.quickSummary)).takeLast(4)
                            } else interactionMessage = "这个活动的场景状态暂时没能保存"
                        }
                        choice.storyPrompt != null -> {
                            val targetCharacterId = (selectedTarget as? DigitalNearbyTarget.Resident)?.motion?.character?.characterId
                            if (onWorldAction != null) onWorldAction(choice.storyPrompt)
                            else if (targetCharacterId != null) onCharacterClick(targetCharacterId)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = controlsBottomPadding + 88.dp),
            )
        }
    }
}

/**
 * Place a few tangible scene objects at real venue anchors after the user has
 * performed the matching in-world action. These aren't random decorations:
 * the retained activity IDs determine what the player sees on the next visit.
 * Never draw a coffee, lit machine or discovered object before its action.
 */
private fun DrawScope.drawDigitalVenueOutcomes(
    sceneCode: String,
    anchors: List<DigitalVenueAnchor>,
    completed: Set<String>,
    phase: Float,
) {
    if (completed.isEmpty()) return
    anchors.forEach { anchor ->
        if (anchor.activityIds.none { it in completed }) return@forEach
        val p = Offset(anchor.position.x, anchor.position.y - 18f)
        val glow = (.20f + phase * .09f).coerceIn(.14f, .40f)
        drawOval(Color(0xFF0F1C1D).copy(alpha = .20f), Offset(p.x - 49f, p.y + 27f), Size(98f, 22f))
        when (sceneCode) {
            DigitalWorldPublicPlaces.CAFE -> {
                // A cup stays on the counter only once it was actually ordered.
                if ("order_drink" in completed || "slow_drink" in completed) {
                    drawOval(Color(0xFFFFE7C1), Offset(p.x - 29f, p.y + 16f), Size(59f, 16f))
                    drawRoundRect(Color(0xFFF6F0D9), Offset(p.x - 19f, p.y - 13f),
                        Size(40f, 35f), CornerRadius(8f))
                    drawOval(Color(0xFF9D715E), Offset(p.x - 17f, p.y - 15f), Size(36f, 10f))
                    drawCircle(Color(0xFFFFFAEB).copy(alpha = glow), 22f, Offset(p.x, p.y - 16f))
                    drawCircle(Color(0xFFFFFAEB), 3f, Offset(p.x + 25f, p.y - 4f))
                }
                if ("order_snack" in completed) {
                    repeat(3) { i ->
                        drawCircle(Color(0xFFC69A62), 7f, Offset(p.x + 35f + i * 5f, p.y + 12f - i * 7f))
                        drawCircle(Color(0xFFF8DC9D), 2f, Offset(p.x + 34f + i * 5f, p.y + 10f - i * 7f))
                    }
                }
            }
            DigitalWorldPublicPlaces.GAME_HALL -> {
                if (completed.any { it in setOf("browse_games", "check_scoreboard", "arcade_linger") }) {
                    drawRoundRect(Color(0xFF162832), Offset(p.x - 30f, p.y - 25f),
                        Size(60f, 58f), CornerRadius(8f))
                    drawRoundRect(Color(0xFF4BC4CF), Offset(p.x - 22f, p.y - 18f),
                        Size(44f, 33f), CornerRadius(5f))
                    repeat(3) { i ->
                        drawCircle(Color(0xFFFFE5A5), 4f,
                            Offset(p.x - 12f + i * 12f, p.y + 24f))
                    }
                    drawCircle(Color(0xFF98F8E8).copy(alpha = glow), 34f, Offset(p.x, p.y))
                }
            }
            DigitalWorldPublicPlaces.COURTYARD -> {
                if (completed.any { it in setOf("courtyard_walk", "courtyard_sit", "watch_sky") }) {
                    repeat(5) { i ->
                        val x = p.x - 30f + 15f * i
                        val y = p.y + if (i % 2 == 0) -5f else 11f
                        drawCircle(Color(0xFFF3C6CB), 6f, Offset(x, y))
                        drawCircle(Color(0xFFFFF1B8), 2.5f, Offset(x, y))
                    }
                }
            }
            DigitalWorldStore.CLOUD_MEADOW -> {
                if (completed.any { it in setOf("cloud_walk", "cloud_sit", "cloud_feel", "cloud_edge") }) {
                    drawOval(Color.White.copy(alpha = .50f), Offset(p.x - 45f, p.y - 17f), Size(90f, 48f))
                    repeat(4) { i ->
                        drawCircle(Color(0xFFD7FFF4).copy(alpha = .48f + phase * .12f),
                            7f + i % 2 * 3f, Offset(p.x - 28f + i * 20f, p.y - 5f + (i % 2) * 12f))
                    }
                }
            }
            else -> {
                // Other locations use a modest physical footprint, not fake rewards.
                drawCircle(Color(0xFFE6D8B4).copy(alpha = .60f), 11f, p)
                drawCircle(Color(0xFF7DAA9D), 5f, p)
            }
        }
    }
}

private fun buildInteractionChoices(
    target: DigitalNearbyTarget?,
    residents: List<CharacterSettings>,
    sceneCode: String,
    placeLabel: String,
    handHoldingId: String?,
    activeFollowers: Set<String>,
    discoveredSpots: Set<String>,
): List<DigitalInteractionChoice> = when (target) {
    is DigitalNearbyTarget.Resident -> {
        val name = target.motion.character.displayName
        val id = target.motion.character.characterId
        val holding = handHoldingId == id
        val following = id in activeFollowers
        listOf(
            DigitalInteractionChoice("聊天", "想说话时再打开对话", dialogueCharacterId = id),
            DigitalInteractionChoice(
                if (holding) "松手" else "牵手",
                if (holding) "松开手，继续自由探索" else "牵手后可以继续走动，不弹剧情",
                movement = if (holding) ResidentMovementChoice.RELEASE_HANDS else ResidentMovementChoice.HOLD_HANDS),
            DigitalInteractionChoice(
                if (following && !holding) "停止跟随" else "跟我来",
                if (following && !holding) "结束随行" else "让对方在场景里跟着你移动",
                movement = if (following && !holding) ResidentMovementChoice.STOP_FOLLOW else ResidentMovementChoice.FOLLOW),
            DigitalInteractionChoice("抱抱（剧情）", "进入角色互动剧情", storyPrompt = "我走到${name}身边，轻轻抱了抱对方，看看对方会有什么反应。"),
            DigitalInteractionChoice("一起坐（剧情）", "讨论或展开共同活动", storyPrompt = "我问${name}要不要和我一起找个舒服的位置坐下来待一会儿。"),
            DigitalInteractionChoice("一起玩（剧情）", "开始共同活动对话", storyPrompt = "我问${name}想不想和我一起玩点什么，由我们现在所在的数字世界决定具体活动。"),
            DigitalInteractionChoice("去别处（剧情）", "协商新的目的地", storyPrompt = "我问${name}想不想和我一起换个地方，并准备从真实存在的地点里选一个。"),
        )
    }
    is DigitalNearbyTarget.NativeResident -> {
        val resident = target.motion.resident
        listOf(
            DigitalInteractionChoice("打招呼", "记录这次真实的相遇", nativeAction = "greet"),
            DigitalInteractionChoice("了解身份", resident.identity, nativeAction = "identity"),
            DigitalInteractionChoice("观察", "看看对方正在什么地方", nativeAction = "observe"),
        )
    }
    is DigitalNearbyTarget.Discovery -> {
        listOf(DigitalInteractionChoice(
            if (target.spot.id in discoveredSpots) "再次观察" else "探索发现",
            target.spot.description,
            discoveryId = target.spot.id,
        ))
    }
    is DigitalNearbyTarget.Prop -> {
        val item = target.prop.item
        val quick = if (item.ownerCharacterId.startsWith("npc:")) {
            listOf(DigitalInteractionChoice(
                label = "观察陈设",
                detail = "看看这件固定家具",
                quickSummary = "我在${placeLabel}仔细看了原住民家中的${item.name}；它是这户住宅实际存在的陈设。",
            ))
        } else DigitalWorldActivityCatalog.optionsFor(item).take(6).map { (activityId, label) ->
            DigitalInteractionChoice(
                label = label,
                detail = "就在这里执行",
                quickSummary = DigitalWorldActivityCatalog.itemActivitySummary("我", item, activityId) ?: "我在${item.name}旁做了“$label”。",
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
                    detail = "共同活动进入人物互动",
                    storyPrompt = "我看向${resident.displayName}，邀请对方和我在“${item.name}”这里$sharedVerb。",
                ),
            )
        } else emptyList()
        quick + shared
    }
    is DigitalNearbyTarget.Venue -> {
        val allowed = DigitalWorldActivityCatalog.locationOptions(sceneCode).filter { it.first in target.anchor.activityIds }
        val games = if (sceneCode == com.jiacimu.lulu.data.DigitalWorldPublicPlaces.GAME_HALL && "choose_arcade" in target.anchor.activityIds) {
            listOf(
                DigitalInteractionChoice("记忆配对", "开始真实对局", gameId = "memory_match"),
                DigitalInteractionChoice("深海回声", "开始潜航探索", gameId = "deep_sea_journey"),
            )
        } else emptyList()
        games + allowed.filterNot { it.first == "choose_arcade" }.map { (activityId, label) ->
            DigitalInteractionChoice(
                label = label,
                detail = target.anchor.label,
                quickSummary = DigitalWorldActivityCatalog.locationActivitySummary("我", sceneCode, activityId, placeLabel)
                    ?: "我在${target.anchor.label}做了“$label”。",
                venueActivityId = activityId,
            )
        }
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
        is DigitalNearbyTarget.NativeResident -> target.motion.resident.name
        is DigitalNearbyTarget.Discovery -> target.spot.label
        is DigitalNearbyTarget.Prop -> target.prop.item.name
        is DigitalNearbyTarget.Venue -> target.anchor.label
    }
    val subtitle = when (target) {
        is DigitalNearbyTarget.Resident -> target.motion.activityLabel.ifBlank { "想怎么和对方相处？" }
        is DigitalNearbyTarget.NativeResident -> target.motion.resident.identity
        is DigitalNearbyTarget.Discovery -> target.spot.description
        is DigitalNearbyTarget.Prop -> "这件东西有真实的不同用法"
        is DigitalNearbyTarget.Venue -> "你已经走到了这个活动区域"
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
                Surface(onClick = onDismiss, color = Color.White.copy(alpha = .07f), shape = RoundedCornerShape(99.dp)) {
                    Text("取消", color = Color(0xFFC5D4CE), fontSize = 8.5.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(9.dp))
            choices.chunked(2).forEach { rowChoices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowChoices.forEach { choice ->
                        Surface(onClick = { onChoice(choice) }, modifier = Modifier.weight(1f), color = Color.White.copy(alpha = .065f), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
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
private fun DigitalActionQueueStrip(active: String, queued: List<DigitalQueuedAction>, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color(0xD90D1916), shape = RoundedCornerShape(99.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("行动", color = Color(0xFF8FB2A7), fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
            (listOfNotNull(active.takeIf(String::isNotBlank)) + queued.take(3).map { it.label }).take(4).forEachIndexed { index, label ->
                Surface(color = if (index == 0 && active.isNotBlank()) Color(0xFF9EFFE0).copy(alpha = .13f) else Color.White.copy(alpha = .06f), shape = RoundedCornerShape(99.dp)) {
                    Text(label, color = Color(0xFFDCEAE4), fontSize = 7.5.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun DigitalWorldAmbientHud(place: String, people: Int, objects: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.widthIn(max = 220.dp), color = Color(0xC910201C), shape = RoundedCornerShape(99.dp), border = BorderStroke(1.dp, Color(0xFFCBFFEF).copy(alpha = .16f)), shadowElevation = 5.dp) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Explore, null, tint = Color(0xFFB8F8E3), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(place, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text("$people 人 · $objects 处", color = Color(0xFF9DC4B9), fontSize = 7.5.sp)
        }
    }
}

@Composable
private fun DigitalWorldMiniMap(
    player: WorldVector,
    residents: List<WorldVector>,
    props: List<DigitalRoomProp>,
    anchors: List<DigitalVenueAnchor>,
    discoveries: List<WorldVector>,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.size(62.dp), color = Color(0xB90B1715), shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .13f))) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            drawRoundRect(Color(0xFF738B83).copy(alpha = .16f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
            props.take(28).forEach { prop ->
                drawCircle(Color(0xFFD3C7AD).copy(alpha = .50f), 1.3.dp.toPx(), Offset(prop.bounds.center.x / DIGITAL_WORLD_WIDTH * size.width, prop.bounds.center.y / DIGITAL_WORLD_HEIGHT * size.height))
            }
            anchors.forEach { anchor ->
                val p = Offset(anchor.position.x / DIGITAL_WORLD_WIDTH * size.width, anchor.position.y / DIGITAL_WORLD_HEIGHT * size.height)
                drawCircle(Color(0xFFB7FFE8).copy(alpha = .34f), 2.1.dp.toPx(), p, style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
            }
            discoveries.forEach { discovery ->
                drawCircle(Color(0xFFF5DFAB).copy(alpha = .72f), 1.5.dp.toPx(),
                    Offset(discovery.x / DIGITAL_WORLD_WIDTH * size.width,
                        discovery.y / DIGITAL_WORLD_HEIGHT * size.height))
            }
            residents.forEach { resident ->
                drawCircle(Color(0xFFFFD89C), 2.0.dp.toPx(), Offset(resident.x / DIGITAL_WORLD_WIDTH * size.width, resident.y / DIGITAL_WORLD_HEIGHT * size.height))
            }
            val playerPoint = Offset(player.x / DIGITAL_WORLD_WIDTH * size.width, player.y / DIGITAL_WORLD_HEIGHT * size.height)
            drawCircle(Color(0xFF92FFDA), 2.9.dp.toPx(), playerPoint)
            drawCircle(Color.White.copy(alpha = .75f), 2.9.dp.toPx(), playerPoint, style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
        }
    }
}

private fun residentStart(index: Int, id: String, anchors: List<DigitalVenueAnchor>): WorldVector {
    if (anchors.isNotEmpty()) {
        val anchor = anchors[index % anchors.size]
        val hash = id.hashCode() and Int.MAX_VALUE
        return anchor.position + WorldVector((hash % 75 - 37).toFloat(), ((hash / 67) % 63 - 31).toFloat())
    }
    val starts = listOf(WorldVector(690f, 520f), WorldVector(980f, 590f), WorldVector(520f, 735f), WorldVector(1_180f, 770f), WorldVector(780f, 860f))
    val base = starts[index % starts.size]
    val hash = id.hashCode() and Int.MAX_VALUE
    return base + WorldVector((hash % 51 - 25).toFloat(), ((hash / 61) % 41 - 20).toFloat())
}

private fun residentWanderTarget(id: String, now: Long, index: Int, anchors: List<DigitalVenueAnchor>): WorldVector {
    val epoch = now / 4_500L
    val seed = (id.hashCode().toLong() * 31L + epoch * 97L + index * 211L) and Long.MAX_VALUE
    if (anchors.isNotEmpty() && seed % 3L != 0L) {
        val anchor = anchors[(seed % anchors.size).toInt()]
        return anchor.position + WorldVector(((seed / 11L) % 121L - 60L).toFloat(), ((seed / 23L) % 101L - 50L).toFloat())
    }
    return WorldVector(170f + (seed % 1_250L).toFloat(), 300f + ((seed / 37L) % 570L).toFloat())
}

private fun rememberUserAvatar(context: Context): Pair<String, String?> =
    context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) to prefs.getString("avatar_uri", null)
    }

private fun digitalSafeStart(restored: WorldVector, obstacles: List<com.jiacimu.lulu.games.WorldObstacle>): WorldVector {
    if (obstacles.none { circleIntersects(restored, 34f, it.bounds) }) return restored
    return listOf(WorldVector(800f, 930f), WorldVector(800f, 260f), WorldVector(600f, 920f), WorldVector(1_000f, 920f), WorldVector(800f, 700f))
        .firstOrNull { candidate -> obstacles.none { circleIntersects(candidate, 34f, it.bounds) } }
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

    fun loadDiscoveries(): Set<String> = prefs.getStringSet("discoveries_$suffix", null).orEmpty().toSet()
    fun loadAcquaintedResidents(): Set<String> =
        prefs.getStringSet("met-residents_$suffix", null).orEmpty().toSet()
    fun saveAcquaintedResidents(ids: Set<String>): Boolean =
        prefs.edit().putStringSet("met-residents_$suffix", ids.toSet()).commit()

    fun loadVenueActivities(): Set<String> =
        prefs.getStringSet("venue-activities_$suffix", null).orEmpty().toSet()

    fun saveVenueActivity(ids: Set<String>): Boolean =
        prefs.edit().putStringSet("venue-activities_$suffix", ids.toSet()).commit()

    fun loadNativePosition(residentId: String, fallback: WorldVector): WorldVector {
        val id = (residentId.hashCode() and Int.MAX_VALUE).toString(36)
        return WorldVector(
            prefs.getFloat("npc_x_${suffix}_$id", fallback.x),
            prefs.getFloat("npc_y_${suffix}_$id", fallback.y),
        )
    }

    fun saveNativePositions(motions: List<DigitalNativeMotion>) {
        val editor = prefs.edit()
        motions.forEach { motion ->
            val id = (motion.resident.id.hashCode() and Int.MAX_VALUE).toString(36)
            editor.putFloat("npc_x_${suffix}_$id", motion.position.x)
                .putFloat("npc_y_${suffix}_$id", motion.position.y)
        }
        editor.apply()
    }

    fun saveDiscoveries(ids: Set<String>): Boolean =
        prefs.edit().putStringSet("discoveries_$suffix", ids.toSet()).commit()

    fun loadHandHoldingId(): String? = prefs.getString("hold_$suffix", null)?.takeIf(String::isNotBlank)

    fun loadFollowerIds(): Set<String> =
        prefs.getStringSet("followers_$suffix", null).orEmpty().toSet()

    fun loadSuspendedFollowers(): Set<String> =
        prefs.getStringSet("suspended_$suffix", null).orEmpty().toSet()

    fun saveCompanionship(handHoldingId: String?, followers: Set<String>, suspended: Set<String>) {
        prefs.edit()
            .putString("hold_$suffix", handHoldingId)
            .putStringSet("followers_$suffix", followers.toSet())
            .putStringSet("suspended_$suffix", suspended.toSet())
            .apply()
    }

    fun save(position: WorldVector, quest: Int) {
        prefs.edit().putFloat("x_$suffix", position.x).putFloat("y_$suffix", position.y).putInt("quest_$suffix", quest.coerceIn(0, 2)).apply()
    }
}
