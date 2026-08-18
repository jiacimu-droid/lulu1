package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlin.math.absoluteValue

/** Map-first lobby for the Meeting app. Existing meeting generation remains the dialogue engine. */
@Composable
internal fun DigitalWorldMapLobby(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    selectedIds: Set<String>,
    locationDraft: String,
    onToggle: (String) -> Unit,
    onLocationChanged: (String) -> Unit,
    onStart: () -> Unit,
    errorText: String,
) {
    val context = LocalContext.current
    val world by DigitalWorldStore.state.collectAsState()
    var openSceneCode by remember { mutableStateOf<String?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var pendingQuickStart by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(characters, profiles) {
        characters.forEach { character ->
            val profile = profiles[character.characterId] ?: DigitalLifeProfileStore.get(character.characterId)
            if (profile.enabled) runCatching { DigitalWorldStore.ensureHome(character.characterId, character.displayName) }
        }
        DigitalWorldNavigationStore.consumeHome(context)?.let { characterId ->
            if (DigitalLifeProfileStore.isEnabled(characterId)) {
                openSceneCode = DigitalWorldStore.homeLocation(characterId)
                val character = MigratedDomainStores.characters.get(characterId)
                onLocationChanged(DigitalWorldStore.state.value.homes[characterId]?.name ?: "${character.displayName}的家")
            }
        }
    }

    LaunchedEffect(pendingQuickStart, selectedIds, locationDraft) {
        val characterId = pendingQuickStart ?: return@LaunchedEffect
        if (characterId in selectedIds && locationDraft.isNotBlank()) {
            pendingQuickStart = null
            onStart()
        }
    }

    fun openScene(code: String, label: String) {
        openSceneCode = code
        onLocationChanged(label)
    }

    fun quickTalk(characterId: String, locationLabel: String) {
        if (characterId !in selectedIds) onToggle(characterId)
        onLocationChanged(locationLabel)
        pendingQuickStart = characterId
    }

    Column(modifier.background(LuluColors.Paper)) {
        if (openSceneCode == null) {
            DigitalWorldMapPage(
                modifier = Modifier.weight(1f),
                characters = characters,
                profiles = profiles,
                world = world,
                onOpenScene = ::openScene,
                onOpenCatalog = { showCatalog = true },
            )
            MeetingSelectionStrip(
                characters = characters,
                profiles = profiles,
                selectedIds = selectedIds,
                locationDraft = locationDraft,
                sceneLabel = "",
                onToggle = onToggle,
                onLocationChanged = onLocationChanged,
                onStart = onStart,
                errorText = errorText,
            )
        } else {
            val sceneCode = openSceneCode!!
            val homeCharacterId = sceneCode.removePrefix("home:").takeIf { sceneCode.startsWith("home:") }
            val home = homeCharacterId?.let(world.homes::get)
            val sceneLabel = when (sceneCode) {
                DigitalWorldStore.ARRIVAL -> "世界入口"
                DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
                else -> home?.name ?: homeCharacterId?.let { "${MigratedDomainStores.characters.get(it).displayName}的家" } ?: "数字世界"
            }
            DigitalWorldScenePage(
                modifier = Modifier.weight(1f),
                sceneCode = sceneCode,
                sceneLabel = sceneLabel,
                homeCharacterId = homeCharacterId,
                characters = characters,
                world = world,
                onBackToMap = { openSceneCode = null },
                onCharacterClick = { quickTalk(it, sceneLabel) },
                onOpenCatalog = { showCatalog = true },
            )
            MeetingSelectionStrip(
                characters = characters,
                profiles = profiles,
                selectedIds = selectedIds,
                locationDraft = locationDraft,
                sceneLabel = sceneLabel,
                onToggle = onToggle,
                onLocationChanged = onLocationChanged,
                onStart = onStart,
                errorText = errorText,
            )
        }
    }

    if (showCatalog) {
        FurnitureCatalogDialog(onDismiss = { showCatalog = false })
    }
}

@Composable
private fun DigitalWorldMapPage(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    world: DigitalWorldState,
    onOpenScene: (String, String) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val digitalCharacters = characters.filter { (profiles[it.characterId] ?: DigitalLifeProfileStore.get(it.characterId)).enabled }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("数字世界", fontSize = 25.sp, fontWeight = FontWeight.Black, color = LuluColors.Ink)
                    Text("点地点进入场景 · 点场景里的小人直接开始见面", color = LuluColors.Muted, fontSize = 11.sp)
                }
                OutlinedButton(onClick = onOpenCatalog, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Chair, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("家具城", fontSize = 12.sp)
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF7F6F2),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, LuluColors.Border),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MapPlaceCard(
                            modifier = Modifier.weight(1f),
                            title = "世界入口",
                            subtitle = peopleAt(world, DigitalWorldStore.ARRIVAL),
                            icon = Icons.Outlined.AutoAwesome,
                            onClick = { onOpenScene(DigitalWorldStore.ARRIVAL, "世界入口") },
                        )
                        MapPlaceCard(
                            modifier = Modifier.weight(1f),
                            title = "云眠原",
                            subtitle = peopleAt(world, DigitalWorldStore.CLOUD_MEADOW),
                            icon = Icons.Outlined.Cloud,
                            onClick = { onOpenScene(DigitalWorldStore.CLOUD_MEADOW, "云眠原") },
                        )
                    }
                    digitalCharacters.chunked(2).forEach { rowCharacters ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowCharacters.forEach { character ->
                                val home = world.homes[character.characterId]
                                val itemCount = world.items.count { it.ownerCharacterId == character.characterId }
                                MapHomeCard(
                                    modifier = Modifier.weight(1f),
                                    character = character,
                                    itemCount = itemCount,
                                    isHome = world.characterLocations[character.characterId] == DigitalWorldStore.homeLocation(character.characterId),
                                    onClick = {
                                        onOpenScene(
                                            DigitalWorldStore.homeLocation(character.characterId),
                                            home?.name ?: "${character.displayName}的家",
                                        )
                                    },
                                )
                            }
                            if (rowCharacters.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            Text(
                "这里展示的是角色真实的数字世界存档。家具、位置和串门都来自权威状态，不会因为页面渲染凭空新增。",
                color = LuluColors.Muted,
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun MapPlaceCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(126.dp).clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(21.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
    ) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF1F3F3), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = LuluColors.BlueGray) }
            }
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, color = LuluColors.Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MapHomeCard(
    modifier: Modifier,
    character: CharacterSettings,
    itemCount: Int,
    isHome: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(150.dp).clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(21.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF3EFE7), modifier = Modifier.size(46.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Home, null, tint = Color(0xFF6F665A)) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (isHome) {
                        Surface(color = Color(0xFFE9F4EC), shape = RoundedCornerShape(10.dp)) {
                            Text("在家", color = Color(0xFF4F7C5A), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 34)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${character.displayName}的家", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        Text("$itemCount 件家具", color = LuluColors.Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DigitalWorldScenePage(
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
                    if (homeCharacterId != null) "真实家园场景 · 点击家具查看 · 点击小人开始互动" else "共享场景 · 点击小人开始互动",
                    color = LuluColors.Muted,
                    fontSize = 10.5.sp,
                )
            }
            if (homeCharacterId != null) IconButton(onClick = onOpenCatalog) { Icon(Icons.Outlined.Chair, "家具城") }
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
    val roomHeight = 440.dp

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
                    drawRect(Color(0xFFF1ECE2), topLeft = Offset(0f, floorTop), size = Size(size.width, size.height - floorTop))
                    val gap = 28.dp.toPx()
                    var y = floorTop
                    while (y < size.height) {
                        drawLine(Color(0xFFE2D9CC), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += gap
                    }
                    drawLine(Color(0xFFDDD4C8), Offset(0f, floorTop), Offset(size.width, floorTop), strokeWidth = 1.dp.toPx())
                }

                items.sortedBy { if (DigitalFurnitureCatalog.resolve(it).kind == DigitalFurnitureKind.RUG) 0 else 1 }
                    .forEachIndexed { index, item ->
                        val style = DigitalFurnitureCatalog.resolve(item)
                        val placement = furniturePlacement(item, index)
                        FurnitureSticker(
                            item = item,
                            style = style,
                            modifier = Modifier.offset(
                                x = (maxWidth - stickerWidth(style.kind)) * placement.first,
                                y = (roomHeight - stickerHeight(style.kind)) * placement.second,
                            ).clickable { selectedItem = item },
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
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 58)
                        }
                        Spacer(Modifier.height(4.dp))
                        Surface(color = Color.White.copy(alpha = .88f), shape = RoundedCornerShape(9.dp)) {
                            Text(character.displayName, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
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
            if (owner.characterId in residents.map(CharacterSettings::characterId)) "${owner.displayName}现在在家" else "${owner.displayName}现在不在家 · 你仍然可以看看已经存在的家具",
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
                    Text("贴图：${DigitalFurnitureCatalog.resolve(item).displayName}", color = LuluColors.BlueGray, fontSize = 11.sp)
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
                        drawCircle(Color.White.copy(alpha = .86f), radius = 42.dp.toPx(), center = Offset(x, y))
                    }
                } else {
                    drawCircle(Color(0xFFE7E1D6), radius = 78.dp.toPx(), center = Offset(size.width * .5f, size.height * .48f), style = Stroke(2.dp.toPx()))
                    drawCircle(Color(0xFFFDFCF9), radius = 54.dp.toPx(), center = Offset(size.width * .5f, size.height * .48f))
                }
            }
            if (residents.isEmpty()) {
                Text("现在这里没有角色", color = LuluColors.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
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
                        LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 62)
                        Spacer(Modifier.height(5.dp))
                        Text(character.displayName, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FurnitureSticker(item: DigitalWorldItem, style: DigitalFurnitureStyle, modifier: Modifier = Modifier) {
    val base = stickerColor(style.colorKey)
    val dark = base.copy(alpha = .92f)
    Box(modifier.size(stickerWidth(style.kind), stickerHeight(style.kind)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            when (style.kind) {
                DigitalFurnitureKind.BED -> {
                    drawRoundRect(base, cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()), size = size)
                    drawRoundRect(Color.White.copy(alpha = .86f), topLeft = Offset(8.dp.toPx(), 7.dp.toPx()), size = Size(size.width * .34f, size.height * .26f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                    drawRoundRect(Color.White.copy(alpha = .78f), topLeft = Offset(size.width * .55f, 7.dp.toPx()), size = Size(size.width * .34f, size.height * .26f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                    if (style.pattern == "stripe") {
                        var x = 10.dp.toPx()
                        while (x < size.width) {
                            drawLine(Color.White.copy(alpha = .38f), Offset(x, size.height * .36f), Offset(x, size.height - 5.dp.toPx()), strokeWidth = 5.dp.toPx())
                            x += 16.dp.toPx()
                        }
                    }
                }
                DigitalFurnitureKind.SOFA -> {
                    drawRoundRect(dark, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), size = Size(size.width - 8.dp.toPx(), size.height * .48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()))
                    drawRoundRect(base, topLeft = Offset(0f, size.height * .34f), size = Size(size.width, size.height * .58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(15.dp.toPx()))
                    drawLine(Color.White.copy(alpha = .45f), Offset(size.width / 2f, size.height * .45f), Offset(size.width / 2f, size.height * .82f), strokeWidth = 1.5.dp.toPx())
                }
                DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> {
                    drawRoundRect(base, size = Size(size.width, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()))
                    drawLine(dark, Offset(size.width * .16f, size.height * .55f), Offset(size.width * .12f, size.height), strokeWidth = 4.dp.toPx())
                    drawLine(dark, Offset(size.width * .84f, size.height * .55f), Offset(size.width * .88f, size.height), strokeWidth = 4.dp.toPx())
                }
                DigitalFurnitureKind.CHAIR -> {
                    drawRoundRect(base, size = Size(size.width, size.height * .55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
                    drawLine(dark, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .16f, size.height), strokeWidth = 3.dp.toPx())
                    drawLine(dark, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .84f, size.height), strokeWidth = 3.dp.toPx())
                }
                DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> {
                    drawRoundRect(base, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                    repeat(3) { i ->
                        val y = size.height * (i + 1) / 4f
                        drawLine(Color.White.copy(alpha = .5f), Offset(5.dp.toPx(), y), Offset(size.width - 5.dp.toPx(), y), strokeWidth = 1.5.dp.toPx())
                    }
                }
                DigitalFurnitureKind.FLOOR_LAMP -> {
                    drawLine(dark, Offset(size.width / 2f, size.height * .28f), Offset(size.width / 2f, size.height * .86f), strokeWidth = 4.dp.toPx())
                    drawCircle(base, radius = size.width * .34f, center = Offset(size.width / 2f, size.height * .22f))
                    drawOval(dark, topLeft = Offset(size.width * .18f, size.height * .84f), size = Size(size.width * .64f, size.height * .12f))
                }
                DigitalFurnitureKind.TABLE_LAMP -> {
                    drawCircle(base, radius = size.width * .36f, center = Offset(size.width / 2f, size.height * .32f))
                    drawLine(dark, Offset(size.width / 2f, size.height * .55f), Offset(size.width / 2f, size.height * .8f), strokeWidth = 3.dp.toPx())
                    drawOval(dark, topLeft = Offset(size.width * .22f, size.height * .78f), size = Size(size.width * .56f, size.height * .14f))
                }
                DigitalFurnitureKind.RUG -> {
                    drawRoundRect(base.copy(alpha = .62f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                    if (style.pattern == "stripe") repeat(5) { i ->
                        val y = size.height * (i + 1) / 6f
                        drawLine(Color.White.copy(alpha = .56f), Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), strokeWidth = 3.dp.toPx())
                    }
                }
                DigitalFurnitureKind.PLANT -> {
                    drawRoundRect(Color(0xFFC9A780), topLeft = Offset(size.width * .28f, size.height * .62f), size = Size(size.width * .44f, size.height * .34f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                    repeat(5) { i ->
                        val angle = i - 2
                        drawOval(Color(0xFF6E936C), topLeft = Offset(size.width * (.34f + angle * .08f), size.height * (.08f + angle.absoluteValue * .07f)), size = Size(size.width * .34f, size.height * .5f))
                    }
                }
                DigitalFurnitureKind.DECOR -> {
                    drawCircle(base, radius = size.minDimension * .38f, center = center)
                    drawCircle(Color.White.copy(alpha = .5f), radius = size.minDimension * .17f, center = center)
                }
            }
        }
        Text(item.name, fontSize = 8.5.sp, color = Color(0xFF4E4A45), maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).background(Color.White.copy(alpha = .72f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

@Composable
private fun MeetingSelectionStrip(
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    selectedIds: Set<String>,
    locationDraft: String,
    sceneLabel: String,
    onToggle: (String) -> Unit,
    onLocationChanged: (String) -> Unit,
    onStart: () -> Unit,
    errorText: String,
) {
    val selectedHasDigital = selectedIds.any { (profiles[it] ?: DigitalLifeProfileStore.get(it)).enabled }
    val selectedResolved = selectedIds.all { (profiles[it] ?: DigitalLifeProfileStore.get(it)).isResolved }
    var realisticLocation by remember { mutableStateOf("") }
    Surface(color = LuluColors.Paper, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                items(characters, key = CharacterSettings::characterId) { character ->
                    val profile = profiles[character.characterId] ?: DigitalLifeProfileStore.get(character.characterId)
                    FilterChip(
                        selected = character.characterId in selectedIds,
                        onClick = { onToggle(character.characterId) },
                        enabled = profile.isResolved,
                        label = { Text(character.displayName, maxLines = 1) },
                        leadingIcon = {
                            if (profile.enabled) Icon(Icons.Outlined.Cloud, null, Modifier.size(15.dp))
                            else Icon(Icons.Outlined.PersonOutline, null, Modifier.size(15.dp))
                        },
                    )
                }
            }
            if (selectedIds.isNotEmpty() && !selectedHasDigital) {
                OutlinedTextField(
                    value = realisticLocation,
                    onValueChange = {
                        realisticLocation = it.take(80)
                        onLocationChanged(realisticLocation)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("现实场景") },
                    placeholder = { Text("例如：傍晚的咖啡馆") },
                )
            } else if (sceneLabel.isNotBlank() && locationDraft != sceneLabel) {
                LaunchedEffect(sceneLabel) { onLocationChanged(sceneLabel) }
            }
            Button(
                onClick = onStart,
                enabled = selectedIds.isNotEmpty() && selectedResolved && locationDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292929), contentColor = Color.White),
            ) {
                Icon(Icons.Outlined.Forum, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (selectedIds.size > 1) "和 ${selectedIds.size} 个角色在这里见面" else "开始见面", fontWeight = FontWeight.Bold)
            }
            if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun FurnitureCatalogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Chair, null) },
        title = { Text("家具城", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DigitalFurnitureCatalog.styles, key = DigitalFurnitureStyle::id) { style ->
                    Surface(color = Color(0xFFF7F7F5), shape = RoundedCornerShape(13.dp)) {
                        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                                FurnitureCatalogPreview(style)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(style.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("${style.kind.name.lowercase()} · ${style.colorKey}${if (style.pattern != "plain") " · ${style.pattern}" else ""}", color = LuluColors.Muted, fontSize = 9.5.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun FurnitureCatalogPreview(style: DigitalFurnitureStyle) {
    val fake = DigitalWorldItem("preview-${style.id}", "preview", style.kind.name.lowercase(), style.displayName, style.displayName, "预览", java.time.Instant.EPOCH, java.time.Instant.EPOCH)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FurnitureSticker(fake, style, Modifier.scaleToFit(style.kind))
    }
}

private fun Modifier.scaleToFit(kind: DigitalFurnitureKind): Modifier {
    val w = stickerWidth(kind)
    val h = stickerHeight(kind)
    val scale = minOf(56f / w.value, 48f / h.value, 1f)
    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}

private fun peopleAt(world: DigitalWorldState, code: String): String {
    val names = world.characterLocations.filterValues { it == code }.keys.map { MigratedDomainStores.characters.get(it).displayName }
    return if (names.isEmpty()) "现在很安静" else names.joinToString("、") + " 在这里"
}

private fun furniturePlacement(item: DigitalWorldItem, index: Int): Pair<Float, Float> {
    val text = item.position.lowercase()
    val x = when {
        listOf("最左", "左侧", "靠左", "左边").any(text::contains) -> .06f
        listOf("最右", "右侧", "靠右", "右边").any(text::contains) -> .72f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .38f
        else -> ((item.id.hashCode().absoluteValue % 67) / 100f).coerceIn(.04f, .72f)
    }
    val y = when {
        listOf("上方", "里面", "后方", "靠墙", "墙边", "窗边").any(text::contains) -> .14f
        listOf("下方", "门边", "前方", "入口").any(text::contains) -> .69f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .42f
        else -> (((item.id.reversed().hashCode().absoluteValue + index * 19) % 64) / 100f).coerceIn(.12f, .72f)
    }
    return x to y
}

private fun stickerWidth(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 122.dp
    DigitalFurnitureKind.SOFA -> 116.dp
    DigitalFurnitureKind.RUG -> 138.dp
    DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 82.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 66.dp
    DigitalFurnitureKind.CHAIR -> 48.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 46.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 48.dp
}

private fun stickerHeight(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 82.dp
    DigitalFurnitureKind.SOFA -> 72.dp
    DigitalFurnitureKind.RUG -> 82.dp
    DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 56.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 94.dp
    DigitalFurnitureKind.CHAIR -> 58.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 96.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 62.dp
}

private fun stickerColor(key: String): Color = when (key) {
    "sky" -> Color(0xFFABC9D9)
    "sage" -> Color(0xFFA9BEA3)
    "wood" -> Color(0xFFC9A77D)
    "charcoal" -> Color(0xFF666A6B)
    "white" -> Color(0xFFF8F8F5)
    "warm" -> Color(0xFFF2C97D)
    "leaf" -> Color(0xFF80A47A)
    else -> Color(0xFFE8DCC7)
}
