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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val userX by animateFloatAsState(userTargetX, tween(430), label = "digital-user-x")
    val userY by animateFloatAsState(userTargetY, tween(430), label = "digital-user-y")

    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color(0xFFF8F5EE),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2927)),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(5.dp)
                .pointerInput(characterId) {
                    detectTapGestures { tap ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val height = size.height.toFloat().coerceAtLeast(1f)
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
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text(item.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.appearance, lineHeight = 20.sp)
                    Text("位置：${item.position}", color = LuluColors.Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { selectedItem = null }) { Text("好") } },
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
    val floorLine = Color(0xFF9E8F7E).copy(alpha = .22f)
    val ink = Color(0xFF514B44).copy(alpha = .35f)

    drawRect(wall)
    drawRect(
        wallShadow.copy(alpha = .48f),
        topLeft = Offset(0f, 0f),
        size = Size(size.width * .055f, wallBottom),
    )
    drawRect(
        wallShadow.copy(alpha = .28f),
        topLeft = Offset(size.width * .94f, 0f),
        size = Size(size.width * .06f, wallBottom),
    )
    drawRect(
        floor,
        topLeft = Offset(0f, wallBottom),
        size = Size(size.width, size.height - wallBottom),
    )

    drawRect(
        Color(0xFFCEC0AF),
        topLeft = Offset(0f, wallBottom - 4.dp.toPx()),
        size = Size(size.width, 5.dp.toPx()),
    )
    drawLine(
        Color.White.copy(alpha = .64f),
        Offset(0f, wallBottom - 6.dp.toPx()),
        Offset(size.width, wallBottom - 6.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
    )

    val floorHeight = size.height - wallBottom
    val vanishing = Offset(size.width * .50f, wallBottom)
    listOf(0f, .18f, .36f, .64f, .82f, 1f).forEach { ratio ->
        drawLine(
            floorLine,
            Offset(size.width * ratio, size.height),
            vanishing,
            strokeWidth = .7.dp.toPx(),
        )
    }
    listOf(.31f, .52f, .72f, .88f).forEach { depth ->
        val y = wallBottom + floorHeight * depth * depth
        drawLine(
            floorLine.copy(alpha = floorLine.alpha * .82f),
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = .7.dp.toPx(),
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
        Brush.verticalGradient(listOf(Color(0xFFDDE9EF), Color(0xFFC9D9E0))),
        topLeft = Offset(windowLeft, windowTop),
        size = Size(windowWidth, windowHeight),
    )
    drawLine(
        ink,
        Offset(windowLeft + windowWidth / 2f, windowTop),
        Offset(windowLeft + windowWidth / 2f, windowTop + windowHeight),
        strokeWidth = 1.4.dp.toPx(),
    )
    drawLine(
        ink,
        Offset(windowLeft, windowTop + windowHeight / 2f),
        Offset(windowLeft + windowWidth, windowTop + windowHeight / 2f),
        strokeWidth = 1.4.dp.toPx(),
    )
    drawCircle(
        Color.White.copy(alpha = .72f),
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
    drawPath(lightPatch, Color.White.copy(alpha = .14f))

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
    onClick: () -> Unit,
) {
    ScenePersonSprite(
        avatarUri = character.avatarUri,
        avatarText = character.displayName.take(1).ifBlank { "角" },
        modifier = modifier,
        bodyColor = Color(0xFF4B4B4B),
        onClick = onClick,
    )
}

@Composable
private fun ScenePersonSprite(
    avatarUri: String?,
    avatarText: String,
    modifier: Modifier = Modifier,
    bodyColor: Color,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Column(
        modifier = clickableModifier,
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
                Color.Black.copy(alpha = .10f),
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
    val userX by animateFloatAsState(userTargetX, tween(430), label = "shared-user-x")
    val userY by animateFloatAsState(userTargetY, tween(430), label = "shared-user-y")
    val minWalkY = if (cloud) .53f else .34f

    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (cloud) Color(0xFFF0F5F7) else Color(0xFFF5F4F0),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2927)),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .pointerInput(sceneCode) {
                    detectTapGestures { tap ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val height = size.height.toFloat().coerceAtLeast(1f)
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
                Color(0xFFE8F2F6),
                Color(0xFFDCE9EE),
                Color(0xFFF7F9F8),
            ),
            startY = 0f,
            endY = size.height,
        ),
    )

    drawCircle(
        Color.White.copy(alpha = .24f),
        radius = size.minDimension * .13f,
        center = Offset(size.width * .77f, size.height * .19f),
    )
    drawCircle(
        Color.White.copy(alpha = .78f),
        radius = size.minDimension * .055f,
        center = Offset(size.width * .77f, size.height * .19f),
    )

    repeat(12) { index ->
        val row = index / 6
        val column = index % 6
        val x = size.width * (.04f + column * .19f)
        val y = size.height * (.34f + row * .10f)
        drawOval(
            Color.White.copy(alpha = if (row == 0) .52f else .68f),
            topLeft = Offset(x, y),
            size = Size(size.width * (.18f + (index % 3) * .025f), size.height * .085f),
        )
    }

    val meadowTop = size.height * .55f
    drawRoundRect(
        Color(0xFFB9CDD5).copy(alpha = .18f),
        topLeft = Offset(size.width * .04f, meadowTop + size.height * .055f),
        size = Size(size.width * .92f, size.height * .34f),
        cornerRadius = CornerRadius(size.height * .16f),
    )
    drawRoundRect(
        Color.White.copy(alpha = .91f),
        topLeft = Offset(size.width * .02f, meadowTop),
        size = Size(size.width * .96f, size.height * .32f),
        cornerRadius = CornerRadius(size.height * .15f),
    )
    repeat(10) { index ->
        val x = size.width * (.02f + index * .105f)
        val radius = size.height * (.050f + (index % 3) * .008f)
        drawCircle(
            Color.White.copy(alpha = .96f),
            radius = radius,
            center = Offset(x, meadowTop + size.height * .015f),
        )
    }

    repeat(4) { index ->
        val y = meadowTop + size.height * (.10f + index * .055f)
        drawOval(
            Color(0xFFAFC4CD).copy(alpha = .10f),
            topLeft = Offset(size.width * (.16f + index * .12f), y),
            size = Size(size.width * .25f, size.height * .032f),
        )
    }

    repeat(8) { index ->
        val x = size.width * (.12f + index * .105f)
        val baseY = meadowTop + size.height * (.20f + (index % 2) * .035f)
        drawLine(
            Color(0xFF9EB4BD).copy(alpha = .44f),
            Offset(x, baseY),
            Offset(x + size.width * .008f, baseY - size.height * .045f),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(
            Color.White.copy(alpha = .88f),
            radius = 2.2.dp.toPx(),
            center = Offset(x + size.width * .009f, baseY - size.height * .048f),
        )
    }
    repeat(11) { index ->
        val x = size.width * ((index * 37 % 91) / 100f + .04f)
        val y = size.height * ((index * 19 % 43) / 100f + .12f)
        drawCircle(
            Color.White.copy(alpha = .60f),
            radius = (1.2f + index % 3).dp.toPx(),
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
    val centerPoint = Offset(size.width * .5f, size.height * .48f)
    drawCircle(
        Color.White.copy(alpha = .72f),
        radius = 92.dp.toPx(),
        center = centerPoint,
    )
    drawCircle(
        Color(0xFF9F988C).copy(alpha = .42f),
        radius = 82.dp.toPx(),
        center = centerPoint,
        style = Stroke(2.dp.toPx()),
    )
    drawCircle(
        Color(0xFFB8B0A4).copy(alpha = .24f),
        radius = 64.dp.toPx(),
        center = centerPoint,
        style = Stroke(1.dp.toPx()),
    )
    repeat(8) { index ->
        val x = if (index % 2 == 0) size.width * .18f else size.width * .82f
        val y = size.height * (.12f + (index / 2) * .20f)
        drawCircle(Color(0xFFCDC6BA), radius = 3.dp.toPx(), center = Offset(x, y))
    }
}
