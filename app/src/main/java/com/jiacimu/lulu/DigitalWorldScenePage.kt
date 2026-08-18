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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    val residents = characters.filter { character -> world.characterLocations[character.characterId] == sceneCode }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackToMap) { Icon(Icons.Outlined.Map, "返回地图") }
            Column(Modifier.weight(1f)) {
                Text(sceneLabel, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (homeCharacterId != null) "真实家园场景 · 点击家具查看 · 点击小人开始互动"
                    else "共享场景 · 点击小人开始互动",
                    color = LuluColors.Muted,
                    fontSize = 10.5.sp,
                )
            }
            if (homeCharacterId != null) {
                IconButton(onClick = onOpenCatalog) { Icon(Icons.Outlined.Chair, "家具城") }
            }
        }
        if (homeCharacterId != null) {
            DigitalHomeRoom(
                modifier = Modifier.fillMaxWidth().weight(1f),
                characterId = homeCharacterId,
                residents = residents,
                world = world,
                onCharacterClick = onCharacterClick,
            )
        } else {
            SharedWorldScene(
                modifier = Modifier.fillMaxWidth().weight(1f),
                sceneCode = sceneCode,
                residents = residents,
                onCharacterClick = onCharacterClick,
            )
        }
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
    val owner = MigratedDomainStores.characters.get(characterId)
    val items = world.items.filter { it.ownerCharacterId == characterId }
    var selectedItem by remember { mutableStateOf<DigitalWorldItem?>(null) }

    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = Color(0xFFF7F3EA),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, Color(0xFFD9D1C4)),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    val floorTop = size.height * 0.22f
                    drawRect(
                        Color(0xFFF1ECE2),
                        topLeft = Offset(0f, floorTop),
                        size = Size(size.width, size.height - floorTop),
                    )
                    val gap = 28.dp.toPx()
                    var y = floorTop
                    while (y < size.height) {
                        drawLine(
                            Color(0xFFE2D9CC),
                            Offset(0f, y),
                            Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                        y += gap
                    }
                    drawLine(
                        Color(0xFFDDD4C8),
                        Offset(0f, floorTop),
                        Offset(size.width, floorTop),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                items.sortedBy {
                    if (DigitalFurnitureCatalog.resolve(it).kind == DigitalFurnitureKind.RUG) 0 else 1
                }.forEachIndexed { index, item ->
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
                    Column(
                        modifier = Modifier
                            .align(if (index % 2 == 0) Alignment.Center else Alignment.BottomEnd)
                            .padding(18.dp)
                            .clickable { onCharacterClick(character.characterId) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 5.dp) {
                            LuluProfileAvatar(
                                character.avatarUri,
                                character.displayName.take(1).ifBlank { "角" },
                                58,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Surface(color = Color.White.copy(alpha = .88f), shape = RoundedCornerShape(9.dp)) {
                            Text(
                                character.displayName,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                if (items.isEmpty()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.HomeWork, null, tint = LuluColors.Muted, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("这里还空空的", color = LuluColors.Muted, fontSize = 12.sp)
                        Text("角色以后创建的家具会真实摆进这个房间", color = LuluColors.Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            if (residents.any { it.characterId == owner.characterId }) "${owner.displayName}现在在家"
            else "${owner.displayName}现在不在家 · 你仍然可以看看已经存在的家具",
            color = LuluColors.Muted,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }

    selectedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            icon = { Icon(Icons.Outlined.Chair, null) },
            title = { Text(item.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(item.appearance, lineHeight = 20.sp)
                    Text("位置：${item.position}", color = LuluColors.Muted, fontSize = 12.sp)
                    Text(
                        "贴图：${DigitalFurnitureCatalog.resolve(item).displayName}",
                        color = LuluColors.BlueGray,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { selectedItem = null }) { Text("好") } },
        )
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
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        color = if (cloud) Color(0xFFF0F5F7) else Color(0xFFF5F4F0),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Canvas(Modifier.matchParentSize()) {
                if (cloud) {
                    repeat(7) { i ->
                        val x = size.width * ((i % 4) + 0.45f) / 4.7f
                        val y = size.height * ((i / 4) + 1.1f) / 3.2f
                        drawCircle(
                            Color.White.copy(alpha = .86f),
                            radius = 42.dp.toPx(),
                            center = Offset(x, y),
                        )
                    }
                } else {
                    drawCircle(
                        Color(0xFFE7E1D6),
                        radius = 78.dp.toPx(),
                        center = Offset(size.width * .5f, size.height * .48f),
                        style = Stroke(2.dp.toPx()),
                    )
                    drawCircle(
                        Color(0xFFFDFCF9),
                        radius = 54.dp.toPx(),
                        center = Offset(size.width * .5f, size.height * .48f),
                    )
                }
            }
            if (residents.isEmpty()) {
                Text(
                    "现在这里没有角色",
                    color = LuluColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                residents.forEachIndexed { index, character ->
                    Column(
                        modifier = Modifier
                            .align(
                                when (index % 4) {
                                    0 -> Alignment.Center
                                    1 -> Alignment.CenterStart
                                    2 -> Alignment.CenterEnd
                                    else -> Alignment.BottomCenter
                                },
                            )
                            .padding(18.dp)
                            .clickable { onCharacterClick(character.characterId) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LuluProfileAvatar(
                            character.avatarUri,
                            character.displayName.take(1).ifBlank { "角" },
                            62,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(character.displayName, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
