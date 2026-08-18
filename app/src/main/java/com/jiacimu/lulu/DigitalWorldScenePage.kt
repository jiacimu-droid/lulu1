package com.jiacimu.lulu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

private data class SceneUserProfile(
    val avatarText: String,
    val avatarUri: String?,
)

private data class SceneAnchor(
    val x: Float,
    val y: Float,
)

@Composable
private fun rememberSceneUserProfile(): SceneUserProfile {
    val context = LocalContext.current
    return remember(context) {
        val prefs = context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE)
        SceneUserProfile(
            avatarText = prefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2),
            avatarUri = prefs.getString("avatar_uri", null),
        )
    }
}

@Composable
internal fun DigitalWorldScenePage(
    modifier: Modifier,
    sceneCode: String,
    sceneLabel: String,
    homeCharacterId: String?,
    characters: List<CharacterSettings>,
    world: DigitalWorldState,
    onBackToMap: () -> Unit,
    onCharacterClick: (String) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(sceneLabel, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBackToMap) { Icon(Icons.Outlined.ArrowBack, "返回地图") }
            },
            actions = {
                if (homeCharacterId != null) {
                    MeetingToolButton(
                        icon = Icons.Outlined.Chair,
                        contentDescription = "家具城",
                        onClick = onOpenCatalog,
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
        )
        DigitalWorldSceneCanvas(
            modifier = Modifier.fillMaxWidth().weight(1f),
            sceneCode = sceneCode,
            homeCharacterId = homeCharacterId,
            characters = characters,
            world = world,
            onCharacterClick = onCharacterClick,
        )
    }
}

@Composable
internal fun DigitalWorldSceneCanvas(
    modifier: Modifier,
    sceneCode: String,
    homeCharacterId: String?,
    characters: List<CharacterSettings>,
    world: DigitalWorldState,
    onCharacterClick: (String) -> Unit,
) {
    val residents = characters.filter { character ->
        world.characterLocations[character.characterId] == sceneCode
    }
    if (homeCharacterId != null) {
        DigitalHomeRoom(
            modifier = modifier,
            characterId = homeCharacterId,
            residents = residents,
            world = world,
            onCharacterClick = onCharacterClick,
        )
    } else {
        SharedWorldScene(
            modifier = modifier,
            sceneCode = sceneCode,
            residents = residents,
            onCharacterClick = onCharacterClick,
        )
    }
}

@Composable
private fun DigitalHomeRoom(
    modifier: Modifier,
    characterId: String,
    residents: List<CharacterSettings>,
    world: DigitalWorldState,
    onCharacterClick: (String) -> Unit,
) {
    val items = world.items.filter { it.ownerCharacterId == characterId }
    val userProfile = rememberSceneUserProfile()
    var selectedItem by remember { mutableStateOf<DigitalWorldItem?>(null) }
    var userTargetX by rememberSaveable(characterId) { mutableStateOf(.76f) }
    var userTargetY by rememberSaveable(characterId) { mutableStateOf(.72f) }
    var userWalking by rememberSaveable(characterId) { mutableStateOf(false) }
    val userX by animateFloatAsState(userTargetX, tween(430), label = "digital-user-x")
    val userY by animateFloatAsState(
        userTargetY,
        tween(430),
        label = "digital-user-y",
        finishedListener = { userWalking = false },
    )

    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color(0xFFF8F5EE),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2927)),
        shadowElevation = 1.dp,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(5.dp)
                .pointerInput(characterId) {
                    detectTapGestures { tap ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        userWalking = true
                        userTargetX = (tap.x / width).coerceIn(.04f, .88f)
                        userTargetY = (tap.y / height).coerceIn(.43f, .84f)
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawIllustratedRoom()
            }

            items.forEachIndexed { index, item ->
                val style = DigitalFurnitureCatalog.resolve(item)
                val placement = furniturePlacement(item, index)
                FurnitureSticker(
                    item = item,
                    style = style,
                    modifier = Modifier
                        .offset(
                            x = (maxWidth - stickerWidth(style.kind)) * placement.first,
                            y = (maxHeight - stickerHeight(style.kind)) * placement.second,
                        )
                        .zIndex(furnitureDepthZ(style.kind, placement.second))
                        .clickable { selectedItem = item },
                )
            }

            residents.forEachIndexed { index, character ->
                val anchor = residentAnchor(index, character.characterId, shared = false)
                SceneCharacterSprite(
                    character = character,
                    scale = personDepthScale(anchor.y),
                    modifier = Modifier
                        .offset(
                            x = (maxWidth - 54.dp) * anchor.x,
                            y = (maxHeight - 80.dp) * anchor.y,
                        )
                        .zIndex(personDepthZ(anchor.y)),
                    onClick = { onCharacterClick(character.characterId) },
                )
            }

            ScenePersonSprite(
                avatarUri = userProfile.avatarUri,
                avatarText = userProfile.avatarText,
                scale = personDepthScale(userY),
                walking = userWalking,
                modifier = Modifier
                    .offset(
                        x = (maxWidth - 54.dp) * userX,
                        y = (maxHeight - 80.dp) * userY,
                    )
                    .zIndex(personDepthZ(userY) + .05f),
                bodyColor = Color(0xFF8C8881),
            )
        }
    }

    selectedItem?.let { item ->
        DigitalFurnitureDetailDialog(
            item = item,
            onDismiss = { selectedItem = null },
        )
    }
}

private fun residentAnchor(index: Int, stableId: String, shared: Boolean): SceneAnchor {
    val anchors = if (shared) {
        listOf(
            SceneAnchor(.44f, .56f),
            SceneAnchor(.20f, .64f),
            SceneAnchor(.69f, .62f),
            SceneAnchor(.31f, .75f),
            SceneAnchor(.60f, .77f),
            SceneAnchor(.80f, .72f),
        )
    } else {
        listOf(
            SceneAnchor(.43f, .50f),
            SceneAnchor(.19f, .59f),
            SceneAnchor(.68f, .58f),
            SceneAnchor(.31f, .70f),
            SceneAnchor(.59f, .73f),
            SceneAnchor(.78f, .67f),
        )
    }
    val base = anchors[index % anchors.size]
    val positiveHash = stableId.hashCode() and Int.MAX_VALUE
    val jitterX = ((positiveHash % 9) - 4) * .005f
    val jitterY = (((positiveHash / 11) % 7) - 3) * .004f
    return SceneAnchor(
        x = (base.x + jitterX).coerceIn(.05f, .86f),
        y = (base.y + jitterY).coerceIn(if (shared) .52f else .46f, .82f),
    )
}

private fun personDepthZ(y: Float): Float = 2.6f + y.coerceIn(0f, 1f) * 10f

private fun personDepthScale(y: Float): Float {
    val normalized = ((y.coerceIn(.42f, .84f) - .42f) / .42f).coerceIn(0f, 1f)
    return .84f + normalized * .16f
}

private fun furnitureDepthZ(kind: DigitalFurnitureKind, y: Float): Float = when (kind) {
    DigitalFurnitureKind.WALL_ART,
    DigitalFurnitureKind.CLOCK,
    DigitalFurnitureKind.MIRROR -> .6f

    DigitalFurnitureKind.RUG -> 1.1f
    else -> 2f + y.coerceIn(0f, 1f) * 10f
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIllustratedRoom() {
    val wallBottom = size.height * .39f
    val wall = Color(0xFFF8F5EF)
    val wallShadow = Color(0xFFEDE7DE)
    val floor = Color(0xFFE5DACB)
    val floorLine = Color(0xFF9E8F7E).copy(alpha = .18f)
    val ink = Color(0xFF514B44).copy(alpha = .34f)

    drawRect(wall)
    drawRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .20f), Color.Transparent),
            startY = 0f,
            endY = wallBottom,
        ),
        size = Size(size.width, wallBottom),
    )
    drawRect(
        wallShadow.copy(alpha = .44f),
        topLeft = Offset(0f, 0f),
        size = Size(size.width * .055f, wallBottom),
    )
    drawRect(
        wallShadow.copy(alpha = .26f),
        topLeft = Offset(size.width * .94f, 0f),
        size = Size(size.width * .06f, wallBottom),
    )
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFFEBE1D4), floor),
            startY = wallBottom,
            endY = size.height,
        ),
        topLeft = Offset(0f, wallBottom),
        size = Size(size.width, size.height - wallBottom),
    )

    drawRect(
        Color(0xFFCEC0AF),
        topLeft = Offset(0f, wallBottom - 4.dp.toPx()),
        size = Size(size.width, 5.dp.toPx()),
    )
    drawLine(
        Color.White.copy(alpha = .62f),
        Offset(0f, wallBottom - 6.dp.toPx()),
        Offset(size.width, wallBottom - 6.dp.toPx()),
        strokeWidth = 1.3.dp.toPx(),
    )

    val floorHeight = size.height - wallBottom
    val vanishing = Offset(size.width * .50f, wallBottom)
    listOf(0f, .20f, .38f, .62f, .80f, 1f).forEach { ratio ->
        drawLine(
            floorLine,
            Offset(size.width * ratio, size.height),
            vanishing,
            strokeWidth = .65.dp.toPx(),
        )
    }
    listOf(.34f, .56f, .75f, .90f).forEach { depth ->
        val y = wallBottom + floorHeight * depth * depth
        drawLine(
            floorLine.copy(alpha = floorLine.alpha * .80f),
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = .65.dp.toPx(),
        )
    }

    val windowLeft = size.width * .63f
    val windowTop = size.height * .075f
    val windowWidth = size.width * .23f
    val windowHeight = size.height * .20f
    drawRoundRect(
        Color(0xFF31302E),
        topLeft = Offset(windowLeft - 3.dp.toPx(), windowTop - 3.dp.toPx()),
        size = Size(windowWidth + 6.dp.toPx(), windowHeight + 6.dp.toPx()),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFFE2EDF1), Color(0xFFC7D9E1))),
        topLeft = Offset(windowLeft, windowTop),
        size = Size(windowWidth, windowHeight),
    )
    drawLine(
        ink,
        Offset(windowLeft + windowWidth / 2f, windowTop),
        Offset(windowLeft + windowWidth / 2f, windowTop + windowHeight),
        strokeWidth = 1.3.dp.toPx(),
    )
    drawLine(
        ink,
        Offset(windowLeft, windowTop + windowHeight / 2f),
        Offset(windowLeft + windowWidth, windowTop + windowHeight / 2f),
        strokeWidth = 1.3.dp.toPx(),
    )
    drawCircle(
        Color.White.copy(alpha = .76f),
        radius = windowWidth * .10f,
        center = Offset(windowLeft + windowWidth * .76f, windowTop + windowHeight * .28f),
    )

    val lightPatch = Path().apply {
        moveTo(windowLeft + windowWidth * .12f, wallBottom)
        lineTo(windowLeft + windowWidth * .95f, wallBottom)
        lineTo(size.width * .91f, size.height * .78f)
        lineTo(size.width * .64f, size.height * .72f)
        close()
    }
    drawPath(lightPatch, Color.White.copy(alpha = .16f))

    // A tiny framed print keeps the opposite wall from feeling unfinished without adding another color.
    val frameLeft = size.width * .16f
    val frameTop = size.height * .10f
    drawRoundRect(
        Color(0xFF4D4944).copy(alpha = .42f),
        topLeft = Offset(frameLeft, frameTop),
        size = Size(size.width * .15f, size.height * .12f),
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )
    drawLine(
        Color(0xFFAFA59A).copy(alpha = .45f),
        Offset(frameLeft + size.width * .025f, frameTop + size.height * .078f),
        Offset(frameLeft + size.width * .075f, frameTop + size.height * .045f),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        Color(0xFFAFA59A).copy(alpha = .45f),
        Offset(frameLeft + size.width * .075f, frameTop + size.height * .045f),
        Offset(frameLeft + size.width * .125f, frameTop + size.height * .082f),
        strokeWidth = 1.dp.toPx(),
    )

    drawLine(
        ink,
        Offset(size.width * .055f, 0f),
        Offset(size.width * .055f, wallBottom),
        strokeWidth = .8.dp.toPx(),
    )
    drawLine(
        ink,
        Offset(size.width * .94f, 0f),
        Offset(size.width * .94f, wallBottom),
        strokeWidth = .8.dp.toPx(),
    )
}

@Composable
private fun SceneCharacterSprite(
    character: CharacterSettings,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    onClick: () -> Unit,
) {
    ScenePersonSprite(
        avatarUri = character.avatarUri,
        avatarText = character.displayName.take(1).ifBlank { "角" },
        modifier = modifier,
        bodyColor = Color(0xFF4B4B4B),
        scale = scale,
        onClick = onClick,
    )
}

@Composable
private fun ScenePersonSprite(
    avatarUri: String?,
    avatarText: String,
    modifier: Modifier = Modifier,
    bodyColor: Color,
    scale: Float = 1f,
    walking: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val stepScaleY by animateFloatAsState(
        targetValue = if (walking) .94f else 1f,
        animationSpec = tween(if (walking) 120 else 180),
        label = "scene-person-step",
    )
    val clickableModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Column(
        modifier = clickableModifier.graphicsLayer {
            scaleX = scale
            scaleY = scale * stepScaleY
            transformOrigin = TransformOrigin(.5f, 1f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFF2B2B2B)),
            shadowElevation = 2.dp,
        ) {
            LuluProfileAvatar(avatarUri, avatarText, 46)
        }
        Canvas(Modifier.size(width = 44.dp, height = 38.dp).offset(y = (-4).dp)) {
            val ink = Color(0xFF353535)
            val softBody = bodyColor.copy(alpha = .18f)
            val paw = bodyColor.copy(alpha = .32f)

            drawOval(
                Color.Black.copy(alpha = if (walking) .07f else .10f),
                topLeft = Offset(size.width * .18f, size.height * .79f),
                size = Size(size.width * .64f, size.height * .13f),
            )
            drawOval(
                paw,
                topLeft = Offset(size.width * .01f, size.height * .25f),
                size = Size(size.width * .25f, size.height * .41f),
            )
            drawOval(
                paw,
                topLeft = Offset(size.width * .74f, size.height * .25f),
                size = Size(size.width * .25f, size.height * .41f),
            )
            drawRoundRect(
                softBody,
                topLeft = Offset(size.width * .13f, size.height * .01f),
                size = Size(size.width * .74f, size.height * .72f),
                cornerRadius = CornerRadius(size.width * .28f),
            )
            drawRoundRect(
                ink.copy(alpha = .68f),
                topLeft = Offset(size.width * .13f, size.height * .01f),
                size = Size(size.width * .74f, size.height * .72f),
                cornerRadius = CornerRadius(size.width * .28f),
                style = Stroke(1.dp.toPx()),
            )
            drawOval(
                Color.White.copy(alpha = .78f),
                topLeft = Offset(size.width * .31f, size.height * .22f),
                size = Size(size.width * .38f, size.height * .31f),
            )
            drawCircle(
                bodyColor.copy(alpha = .72f),
                radius = 2.2.dp.toPx(),
                center = Offset(size.width * .50f, size.height * .16f),
            )
            drawOval(
                bodyColor.copy(alpha = .78f),
                topLeft = Offset(size.width * .20f, size.height * .67f),
                size = Size(size.width * .27f, size.height * .20f),
            )
            drawOval(
                bodyColor.copy(alpha = .78f),
                topLeft = Offset(size.width * .53f, size.height * .67f),
                size = Size(size.width * .27f, size.height * .20f),
            )
        }
    }
}

@Composable
private fun SharedWorldScene(
    modifier: Modifier,
    sceneCode: String,
    residents: List<CharacterSettings>,
    onCharacterClick: (String) -> Unit,
) {
    val cloud = sceneCode == DigitalWorldStore.CLOUD_MEADOW
    val userProfile = rememberSceneUserProfile()
    var userTargetX by rememberSaveable(sceneCode) { mutableStateOf(.73f) }
    var userTargetY by rememberSaveable(sceneCode) { mutableStateOf(.70f) }
    var userWalking by rememberSaveable(sceneCode) { mutableStateOf(false) }
    val userX by animateFloatAsState(userTargetX, tween(430), label = "shared-user-x")
    val userY by animateFloatAsState(
        userTargetY,
        tween(430),
        label = "shared-user-y",
        finishedListener = { userWalking = false },
    )
    val minWalkY = if (cloud) .53f else .34f

    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (cloud) Color(0xFFF0F5F7) else Color(0xFFF5F4F0),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2927)),
        shadowElevation = 1.dp,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .pointerInput(sceneCode) {
                    detectTapGestures { tap ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        userWalking = true
                        userTargetX = (tap.x / width).coerceIn(.04f, .88f)
                        userTargetY = (tap.y / height).coerceIn(minWalkY, .84f)
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                if (cloud) drawCloudMeadow() else drawArrivalScene()
            }

            residents.forEachIndexed { index, character ->
                val anchor = residentAnchor(index, character.characterId, shared = true)
                SceneCharacterSprite(
                    character = character,
                    scale = personDepthScale(anchor.y),
                    modifier = Modifier
                        .offset(
                            x = (maxWidth - 54.dp) * anchor.x,
                            y = (maxHeight - 80.dp) * anchor.y,
                        )
                        .zIndex(personDepthZ(anchor.y)),
                    onClick = { onCharacterClick(character.characterId) },
                )
            }

            ScenePersonSprite(
                avatarUri = userProfile.avatarUri,
                avatarText = userProfile.avatarText,
                scale = personDepthScale(userY),
                walking = userWalking,
                modifier = Modifier
                    .offset(
                        x = (maxWidth - 54.dp) * userX,
                        y = (maxHeight - 80.dp) * userY,
                    )
                    .zIndex(personDepthZ(userY) + .05f),
                bodyColor = Color(0xFF8C8881),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloudMeadow() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE6F0F4),
                Color(0xFFD9E7EC),
                Color(0xFFF7F9F8),
            ),
            startY = 0f,
            endY = size.height,
        ),
    )

    // Diffused sun, kept almost monochrome so it stays inside the app's restrained palette.
    drawCircle(
        Color.White.copy(alpha = .20f),
        radius = size.minDimension * .17f,
        center = Offset(size.width * .78f, size.height * .18f),
    )
    drawCircle(
        Color.White.copy(alpha = .76f),
        radius = size.minDimension * .052f,
        center = Offset(size.width * .78f, size.height * .18f),
    )

    // Far cloud banks create an actual horizon instead of a row of identical circles.
    listOf(
        Triple(.03f, .31f, .34f),
        Triple(.28f, .35f, .30f),
        Triple(.61f, .30f, .37f),
    ).forEachIndexed { index, (x, y, width) ->
        drawOval(
            Color.White.copy(alpha = .35f + index * .08f),
            topLeft = Offset(size.width * x, size.height * y),
            size = Size(size.width * width, size.height * (.075f + index * .006f)),
        )
    }

    // Two distant floating cloud-islands make the space feel much larger than the viewport.
    drawOval(
        Color(0xFFA9BEC7).copy(alpha = .13f),
        topLeft = Offset(size.width * .12f, size.height * .45f),
        size = Size(size.width * .23f, size.height * .035f),
    )
    drawOval(
        Color.White.copy(alpha = .72f),
        topLeft = Offset(size.width * .10f, size.height * .405f),
        size = Size(size.width * .26f, size.height * .055f),
    )
    drawOval(
        Color(0xFFA9BEC7).copy(alpha = .11f),
        topLeft = Offset(size.width * .69f, size.height * .43f),
        size = Size(size.width * .20f, size.height * .030f),
    )
    drawOval(
        Color.White.copy(alpha = .64f),
        topLeft = Offset(size.width * .68f, size.height * .395f),
        size = Size(size.width * .22f, size.height * .050f),
    )

    val meadowTop = size.height * .55f
    drawRoundRect(
        Color(0xFF9FB7C0).copy(alpha = .18f),
        topLeft = Offset(size.width * .045f, meadowTop + size.height * .065f),
        size = Size(size.width * .91f, size.height * .34f),
        cornerRadius = CornerRadius(size.height * .16f),
    )
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .98f), Color(0xFFF4F8F8).copy(alpha = .96f)),
            startY = meadowTop,
            endY = size.height * .88f,
        ),
        topLeft = Offset(size.width * .025f, meadowTop),
        size = Size(size.width * .95f, size.height * .32f),
        cornerRadius = CornerRadius(size.height * .15f),
    )

    // Uneven cloud rim avoids the old scalloped-copy-paste look.
    val rim = listOf(.045f, .09f, .055f, .082f, .050f, .090f, .058f, .078f, .052f)
    rim.forEachIndexed { index, radiusRatio ->
        val x = size.width * (.03f + index * .118f)
        drawCircle(
            Color.White.copy(alpha = .98f),
            radius = size.height * radiusRatio,
            center = Offset(x.coerceAtMost(size.width * .97f), meadowTop + size.height * .012f),
        )
    }

    // A soft winding trail gives the eye a path into the scene and also hints where people can stand.
    val trail = Path().apply {
        moveTo(size.width * .46f, size.height * .86f)
        cubicTo(
            size.width * .39f,
            size.height * .79f,
            size.width * .57f,
            size.height * .74f,
            size.width * .50f,
            size.height * .68f,
        )
        cubicTo(
            size.width * .45f,
            size.height * .63f,
            size.width * .53f,
            size.height * .60f,
            size.width * .50f,
            size.height * .57f,
        )
    }
    drawPath(
        trail,
        Color(0xFFB7CAD1).copy(alpha = .16f),
        style = Stroke(width = 15.dp.toPx()),
    )
    drawPath(
        trail,
        Color.White.copy(alpha = .58f),
        style = Stroke(width = 8.dp.toPx()),
    )

    repeat(7) { index ->
        val x = size.width * (.12f + index * .12f)
        val baseY = meadowTop + size.height * (.20f + (index % 2) * .028f)
        drawLine(
            Color(0xFF9EB4BD).copy(alpha = .42f),
            Offset(x, baseY),
            Offset(x + size.width * .007f, baseY - size.height * .040f),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(
            Color.White.copy(alpha = .90f),
            radius = 2.0.dp.toPx(),
            center = Offset(x + size.width * .008f, baseY - size.height * .043f),
        )
    }

    repeat(12) { index ->
        val x = size.width * ((index * 37 % 91) / 100f + .04f)
        val y = size.height * ((index * 19 % 43) / 100f + .11f)
        drawCircle(
            Color.White.copy(alpha = .55f),
            radius = (1.1f + index % 3).dp.toPx(),
            center = Offset(x.coerceAtMost(size.width * .95f), y),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrivalScene() {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFFF8F6F0), Color(0xFFEDE9E0)),
            startY = 0f,
            endY = size.height,
        ),
    )

    val horizon = size.height * .42f
    drawLine(
        Color(0xFFBDB5AA).copy(alpha = .20f),
        Offset(size.width * .08f, horizon),
        Offset(size.width * .92f, horizon),
        strokeWidth = 1.dp.toPx(),
    )
    drawOval(
        Color(0xFF8D867D).copy(alpha = .10f),
        topLeft = Offset(size.width * .18f, size.height * .68f),
        size = Size(size.width * .64f, size.height * .10f),
    )
    drawOval(
        Color.White.copy(alpha = .54f),
        topLeft = Offset(size.width * .20f, size.height * .61f),
        size = Size(size.width * .60f, size.height * .12f),
    )
    drawOval(
        Color(0xFFA59D92).copy(alpha = .24f),
        topLeft = Offset(size.width * .26f, size.height * .635f),
        size = Size(size.width * .48f, size.height * .075f),
        style = Stroke(1.2.dp.toPx()),
    )

    val portalTop = size.height * .17f
    val portalLeft = size.width * .31f
    val portalWidth = size.width * .38f
    val portalHeight = size.height * .45f
    drawOval(
        Color.White.copy(alpha = .24f),
        topLeft = Offset(portalLeft - size.width * .035f, portalTop - size.height * .025f),
        size = Size(portalWidth + size.width * .07f, portalHeight + size.height * .05f),
    )
    drawOval(
        Color(0xFF8F887E).copy(alpha = .50f),
        topLeft = Offset(portalLeft, portalTop),
        size = Size(portalWidth, portalHeight),
        style = Stroke(2.dp.toPx()),
    )
    drawOval(
        Color.White.copy(alpha = .72f),
        topLeft = Offset(portalLeft + size.width * .035f, portalTop + size.height * .035f),
        size = Size(portalWidth - size.width * .07f, portalHeight - size.height * .07f),
        style = Stroke(1.2.dp.toPx()),
    )
    drawOval(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .22f), Color(0xFFD7E1E3).copy(alpha = .16f)),
            startY = portalTop,
            endY = portalTop + portalHeight,
        ),
        topLeft = Offset(portalLeft + size.width * .055f, portalTop + size.height * .055f),
        size = Size(portalWidth - size.width * .11f, portalHeight - size.height * .11f),
    )

    // Sparse stones around the platform break the perfect symmetry without clutter.
    listOf(
        Offset(.18f, .73f),
        Offset(.29f, .78f),
        Offset(.73f, .76f),
        Offset(.84f, .70f),
        Offset(.12f, .58f),
        Offset(.88f, .55f),
    ).forEachIndexed { index, ratio ->
        drawOval(
            Color(0xFFA8A095).copy(alpha = .34f),
            topLeft = Offset(size.width * ratio.x, size.height * ratio.y),
            size = Size((5 + index % 3 * 2).dp.toPx(), (3 + index % 2 * 2).dp.toPx()),
        )
    }
}
