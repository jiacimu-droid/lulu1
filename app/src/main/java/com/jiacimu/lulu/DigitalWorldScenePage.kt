package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

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
                    IconButton(onClick = onOpenCatalog) { Icon(Icons.Outlined.Chair, "家具城") }
                }
            },
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
    var selectedItem by remember { mutableStateOf<DigitalWorldItem?>(null) }

    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color(0xFFF8F5EE),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2927)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
            Canvas(Modifier.matchParentSize()) {
                drawIllustratedRoom()
            }

            items.sortedWith(compareBy({ furnitureLayer(it) }, { it.createdAt })).forEachIndexed { index, item ->
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
                        .clickable { selectedItem = item },
                )
            }

            residents.forEachIndexed { index, character ->
                val alignment = when (index % 5) {
                    0 -> Alignment.Center
                    1 -> Alignment.CenterStart
                    2 -> Alignment.CenterEnd
                    3 -> Alignment.BottomStart
                    else -> Alignment.BottomEnd
                }
                SceneCharacterSprite(
                    character = character,
                    modifier = Modifier.align(alignment).padding(20.dp),
                    onClick = { onCharacterClick(character.characterId) },
                )
            }
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIllustratedRoom() {
    val wallBottom = size.height * .40f
    val wall = Color(0xFFF8F5EF)
    val wallShadow = Color(0xFFEDE7DE)
    val floor = Color(0xFFE4D8C8)
    val floorLine = Color(0xFFC8B9A7).copy(alpha = .72f)
    val ink = Color(0xFF514B44).copy(alpha = .38f)

    drawRect(wall)
    drawRect(
        wallShadow.copy(alpha = .52f),
        topLeft = Offset(0f, 0f),
        size = Size(size.width * .055f, wallBottom),
    )
    drawRect(
        wallShadow.copy(alpha = .30f),
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
        topLeft = Offset(0f, wallBottom - 5.dp.toPx()),
        size = Size(size.width, 6.dp.toPx()),
    )
    drawLine(
        Color.White.copy(alpha = .68f),
        Offset(0f, wallBottom - 7.dp.toPx()),
        Offset(size.width, wallBottom - 7.dp.toPx()),
        strokeWidth = 2.dp.toPx(),
    )

    val vanishing = Offset(size.width * .50f, wallBottom)
    repeat(9) { index ->
        val bottomX = size.width * index / 8f
        drawLine(
            floorLine,
            Offset(bottomX, size.height),
            vanishing,
            strokeWidth = 1.dp.toPx(),
        )
    }
    listOf(.12f, .25f, .41f, .60f, .80f).forEach { fraction ->
        val y = wallBottom + (size.height - wallBottom) * fraction
        drawLine(floorLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
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
        Color(0xFFDCE8ED),
        topLeft = Offset(windowLeft, windowTop),
        size = Size(windowWidth, windowHeight),
    )
    drawLine(ink, Offset(windowLeft + windowWidth / 2f, windowTop), Offset(windowLeft + windowWidth / 2f, windowTop + windowHeight), 1.5.dp.toPx())
    drawLine(ink, Offset(windowLeft, windowTop + windowHeight / 2f), Offset(windowLeft + windowWidth, windowTop + windowHeight / 2f), 1.5.dp.toPx())
    drawCircle(
        Color.White.copy(alpha = .68f),
        radius = windowWidth * .10f,
        center = Offset(windowLeft + windowWidth * .76f, windowTop + windowHeight * .28f),
    )

    val lightPatch = Path().apply {
        moveTo(windowLeft + windowWidth * .12f, wallBottom)
        lineTo(windowLeft + windowWidth * .95f, wallBottom)
        lineTo(size.width * .93f, size.height * .82f)
        lineTo(size.width * .60f, size.height * .76f)
        close()
    }
    drawPath(lightPatch, Color.White.copy(alpha = .17f))

    drawLine(
        ink,
        Offset(size.width * .055f, 0f),
        Offset(size.width * .055f, wallBottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        ink,
        Offset(size.width * .94f, 0f),
        Offset(size.width * .94f, wallBottom),
        strokeWidth = 1.dp.toPx(),
    )
}

private fun furnitureLayer(item: DigitalWorldItem): Int = when (DigitalFurnitureCatalog.resolve(item).kind) {
    DigitalFurnitureKind.WALL_ART,
    DigitalFurnitureKind.CLOCK,
    DigitalFurnitureKind.MIRROR -> 0
    DigitalFurnitureKind.RUG -> 1
    else -> 2
}

@Composable
private fun SceneCharacterSprite(
    character: CharacterSettings,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(15.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFF2B2B2B)),
            shadowElevation = 3.dp,
        ) {
            LuluProfileAvatar(
                character.avatarUri,
                character.displayName.take(1).ifBlank { "角" },
                48,
            )
        }
        Canvas(Modifier.size(width = 36.dp, height = 34.dp)) {
            val body = Color(0xFF3A3A3A)
            drawRoundRect(
                body,
                topLeft = Offset(size.width * .18f, size.height * .02f),
                size = Size(size.width * .64f, size.height * .60f),
                cornerRadius = CornerRadius(size.width * .20f),
            )
            drawLine(body, Offset(size.width * .34f, size.height * .55f), Offset(size.width * .27f, size.height * .96f), strokeWidth = 5.dp.toPx())
            drawLine(body, Offset(size.width * .66f, size.height * .55f), Offset(size.width * .73f, size.height * .96f), strokeWidth = 5.dp.toPx())
        }
        Surface(color = Color.White.copy(alpha = .90f), shape = RoundedCornerShape(8.dp)) {
            Text(
                character.displayName,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (cloud) Color(0xFFF0F5F7) else Color(0xFFF5F4F0),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2927)),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Canvas(Modifier.matchParentSize()) {
                if (cloud) {
                    drawRect(Color(0xFFE9F0F3))
                    repeat(9) { i ->
                        val x = size.width * ((i % 5) + .25f) / 5.0f
                        val y = size.height * ((i / 5) + 1.12f) / 2.65f
                        drawCircle(
                            Color.White.copy(alpha = .88f),
                            radius = (34 + (i % 3) * 8).dp.toPx(),
                            center = Offset(x, y),
                        )
                    }
                    drawLine(
                        Color(0xFFB9C8CE).copy(alpha = .48f),
                        Offset(0f, size.height * .70f),
                        Offset(size.width, size.height * .70f),
                        strokeWidth = 1.dp.toPx(),
                    )
                } else {
                    drawRect(Color(0xFFF2EFE8))
                    drawCircle(
                        Color(0xFF9F988C).copy(alpha = .40f),
                        radius = 82.dp.toPx(),
                        center = Offset(size.width * .5f, size.height * .48f),
                        style = Stroke(2.dp.toPx()),
                    )
                    drawCircle(
                        Color(0xFFFDFCF9),
                        radius = 58.dp.toPx(),
                        center = Offset(size.width * .5f, size.height * .48f),
                    )
                    repeat(8) { index ->
                        val angleX = if (index % 2 == 0) .18f else .82f
                        val y = size.height * (.12f + (index / 2) * .20f)
                        drawCircle(Color(0xFFCDC6BA), radius = 3.dp.toPx(), center = Offset(size.width * angleX, y))
                    }
                }
            }
            residents.forEachIndexed { index, character ->
                SceneCharacterSprite(
                    character = character,
                    modifier = Modifier
                        .align(
                            when (index % 5) {
                                0 -> Alignment.Center
                                1 -> Alignment.CenterStart
                                2 -> Alignment.CenterEnd
                                3 -> Alignment.BottomStart
                                else -> Alignment.BottomEnd
                            },
                        )
                        .padding(18.dp),
                    onClick = { onCharacterClick(character.characterId) },
                )
            }
        }
    }
}
