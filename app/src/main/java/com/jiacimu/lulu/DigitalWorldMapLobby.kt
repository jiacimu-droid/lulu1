package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import com.jiacimu.lulu.games.GameAmbientAudioButton
import com.jiacimu.lulu.games.GameAmbientSoundscape
import com.jiacimu.lulu.games.GameSoundscape
import com.jiacimu.lulu.games.MeetingAtmosphereOverlay

/**
 * Location-first digital world browser. Public venues stay deliberately few and dense: every place
 * must offer a distinct shared activity instead of becoming another empty card on the map.
 */
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
    onBack: () -> Unit = {},
    onDirectMeeting: ((characterId: String, location: String) -> Unit)? = null,
    onOpenHistory: () -> Unit = {},
    onOpenModelPicker: () -> Unit = {},
    onOpenWritingPicker: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val world by DigitalWorldStore.state.collectAsState()
    val voiceEnabled by MeetingVoicePlayback.enabled.collectAsState()
    val bgmEnabled = GameAmbientSoundscape(GameSoundscape.Meeting)
    var openSceneCode by remember { mutableStateOf<String?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingLegacyQuickStart by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { MeetingVoicePlayback.initialize(context) }

    LaunchedEffect(characters, profiles) {
        characters.forEach { character ->
            val profile = profiles[character.characterId] ?: DigitalLifeProfileStore.get(character.characterId)
            if (profile.enabled) runCatching { DigitalWorldStore.ensureHome(character.characterId, character.displayName) }
        }
        DigitalWorldNavigationStore.consumeHome(context)?.let { characterId ->
            if (DigitalLifeProfileStore.isEnabled(characterId)) {
                openSceneCode = DigitalWorldStore.homeLocation(characterId)
                val character = MigratedDomainStores.characters.get(characterId)
                onLocationChanged(
                    DigitalWorldStore.state.value.homes[characterId]?.name ?: "${character.displayName}的家",
                )
            }
        }
    }

    LaunchedEffect(pendingLegacyQuickStart, selectedIds, locationDraft) {
        val characterId = pendingLegacyQuickStart ?: return@LaunchedEffect
        if (characterId in selectedIds && locationDraft.isNotBlank()) {
            pendingLegacyQuickStart = null
            onStart()
        }
    }

    fun sceneLabel(code: String): String {
        if (code == "preview:3d-room") return "3D 体验间"
        if (code == DigitalWorldStore.ARRIVAL) return "世界入口"
        if (code == DigitalWorldStore.CLOUD_MEADOW) return "云眠原"
        DigitalWorldPublicPlaces.label(code)?.let { return it }
        DigitalWorldExpansionStore.residenceLabel(code)?.let { return it }
        if (code.startsWith("home:")) {
            val id = code.removePrefix("home:")
            return world.homes[id]?.name ?: "${MigratedDomainStores.characters.get(id).displayName}的家"
        }
        return "数字世界"
    }

    fun openScene(code: String) {
        openSceneCode = code
        onLocationChanged(sceneLabel(code))
    }

    fun talkTo(characterId: String, location: String) {
        if (onDirectMeeting != null) {
            onDirectMeeting(characterId, location)
            return
        }
        if (characterId !in selectedIds) onToggle(characterId)
        onLocationChanged(location)
        pendingLegacyQuickStart = characterId
    }

    fun toggleVoice() {
        MeetingVoicePlayback.setEnabled(context, !voiceEnabled)
    }

    val activeSceneCode = openSceneCode
    val activeSceneLabel = activeSceneCode?.let(::sceneLabel)
    val sceneResidents = activeSceneCode?.let { scene ->
        characters.filter { world.characterLocations[it.characterId] == scene }
            .map { it.characterId }
    }.orEmpty()
    DisposableEffect(activeSceneCode) {
        val code = activeSceneCode
        if (code != null && !code.startsWith("preview:")) {
            val label = sceneLabel(code)
            DigitalWorldVisitorAwareness.locationArrival(context, code, label,
                sceneResidents, entering = true)
            onDispose {
                DigitalWorldVisitorAwareness.locationArrival(context, code, label,
                    sceneResidents, entering = false)
            }
        } else onDispose { }
    }
    val activeHomeId = activeSceneCode
        ?.takeIf { it.startsWith("home:") }
        ?.removePrefix("home:")

    Column(modifier.fillMaxSize().background(LuluColors.Paper)) {
        TopAppBar(
            title = {
                Text(
                    activeSceneLabel ?: "数字世界",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (activeSceneCode == null) onBack() else openSceneCode = null
                }) {
                    Icon(Icons.Outlined.ArrowBack, if (activeSceneCode == null) "返回" else "返回地图")
                }
            },
            actions = {
                if (activeHomeId != null || activeSceneCode == null) {
                    MeetingToolButton(
                        icon = Icons.Outlined.Chair,
                        contentDescription = "家具城",
                        onClick = { showCatalog = true },
                    )
                }
                MeetingVoiceToggleButton(
                    enabled = voiceEnabled,
                    onToggle = ::toggleVoice,
                )
                GameAmbientAudioButton(
                    enabled = bgmEnabled,
                    tint = Color(0xFF2E3230),
                )
                Box {
                    MeetingToolButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        onClick = { showMenu = true },
                    )
                    MeetingOverflowMenu(
                        expanded = showMenu,
                        voiceEnabled = voiceEnabled,
                        onDismiss = { showMenu = false },
                        onToggleVoice = ::toggleVoice,
                        onOpenHistory = onOpenHistory,
                        onOpenModelPicker = onOpenModelPicker,
                        onOpenWritingPicker = onOpenWritingPicker,
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
        )

        if (activeSceneCode == null) {
            DigitalWorldMapPage(
                modifier = Modifier.fillMaxWidth().weight(1f),
                characters = characters,
                profiles = profiles,
                world = world,
                onOpenScene = ::openScene,
            )
        } else if (activeSceneCode == "preview:3d-room") {
            // Completely isolated visual sandbox: does not impersonate persistent world props.
            DigitalWorld3DTrialRoom(Modifier.fillMaxWidth().weight(1f))
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF091311)),
            ) {
                DigitalWorldSceneCanvas(
                    modifier = Modifier.fillMaxSize(),
                    sceneCode = activeSceneCode,
                    homeCharacterId = activeHomeId,
                    characters = characters,
                    world = world,
                    onCharacterClick = { talkTo(it, activeSceneLabel ?: "世界入口") },
                    controlsBottomPadding = 92.dp,
                )
                DigitalWorldRouteDock(
                    activeSceneCode = activeSceneCode,
                    characters = characters,
                    profiles = profiles,
                    world = world,
                    sceneLabel = ::sceneLabel,
                    onOpenMap = { openSceneCode = null },
                    onOpenScene = ::openScene,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        if (errorText.isNotBlank()) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                fontSize = 10.5.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
    }

    if (showCatalog) StyledFurnitureCatalogDialog(onDismiss = { showCatalog = false })
}

@Composable
private fun DigitalWorldRouteDock(
    activeSceneCode: String,
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    world: DigitalWorldState,
    sceneLabel: (String) -> String,
    onOpenMap: () -> Unit,
    onOpenScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops = remember(characters, profiles, world.homes, activeSceneCode) {
        buildList {
            add(DigitalWorldStore.ARRIVAL)
            add(DigitalWorldStore.CLOUD_MEADOW)
            addAll(DigitalWorldPublicPlaces.all.map { it.code })
            characters
                .filter { (profiles[it.characterId] ?: DigitalLifeProfileStore.get(it.characterId)).enabled }
                .forEach { character -> add(DigitalWorldStore.homeLocation(character.characterId)) }
            // Residents own addresses too; keep them navigable from inside a scene.
            DigitalWorldExpansionStore.residents().forEach { add(it.homeLocationCode) }
        }.distinct()
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xE70B1512),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .15f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 9.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DigitalWorldRouteButton(
                label = "总览",
                icon = Icons.Outlined.Map,
                selected = false,
                onClick = onOpenMap,
            )
            stops.forEach { code ->
                DigitalWorldRouteButton(
                    label = sceneLabel(code),
                    icon = routeIcon(code),
                    selected = code == activeSceneCode,
                    onClick = { if (code != activeSceneCode) onOpenScene(code) },
                )
            }
        }
    }
}

private fun routeIcon(code: String): androidx.compose.ui.graphics.vector.ImageVector = when (code) {
    DigitalWorldStore.ARRIVAL -> Icons.Outlined.AutoAwesome
    DigitalWorldStore.CLOUD_MEADOW -> Icons.Outlined.Cloud
    DigitalWorldPublicPlaces.GAME_HALL -> Icons.Outlined.SportsEsports
    DigitalWorldPublicPlaces.CAFE -> Icons.Outlined.LocalCafe
    DigitalWorldPublicPlaces.COURTYARD -> Icons.Outlined.Park
    else -> Icons.Outlined.Home
}

@Composable
private fun DigitalWorldRouteButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color(0xFFBEE6D7).copy(alpha = .18f) else Color.White.copy(alpha = .06f),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(
            1.dp,
            if (selected) Color(0xFFBEE6D7).copy(alpha = .44f) else Color.White.copy(alpha = .10f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, tint = if (selected) Color(0xFFCFFFF0) else Color(0xFFB7C8C0), modifier = Modifier.size(15.dp))
            Text(
                label,
                color = if (selected) Color.White else Color(0xFFD0DDD7),
                fontSize = 9.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DigitalWorldMapPage(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    world: DigitalWorldState,
    onOpenScene: (String) -> Unit,
) {
    val digitalCharacters = characters.filter {
        (profiles[it.characterId] ?: DigitalLifeProfileStore.get(it.characterId)).enabled
    }
    val localResidents = DigitalWorldExpansionStore.residents()
    val places = DigitalWorldPublicPlaces.all
    val outdoor = places.filter { it.code == DigitalWorldPublicPlaces.COURTYARD }
    val facilities = places.filter { it.code == DigitalWorldPublicPlaces.CAFE || it.code == DigitalWorldPublicPlaces.GAME_HALL }
    val discovered = places.filter { it.code !in setOf(DigitalWorldPublicPlaces.COURTYARD, DigitalWorldPublicPlaces.CAFE, DigitalWorldPublicPlaces.GAME_HALL) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DigitalWorldLiveHeader(
                homeCount = digitalCharacters.size,
                npcHomeCount = localResidents.size,
                residentCount = world.characterLocations.values.count { it.isNotBlank() } + localResidents.size,
                itemCount = world.items.size,
            )
        }
        item { WorldMapSectionHeading("云境与户外", "真正可以抵达的开放空间", Icons.Outlined.Landscape) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MapPlaceCard(Modifier.weight(1f), "世界入口", "抵达 · 等候", Icons.Outlined.AutoAwesome,
                    residentsAt(world, DigitalWorldStore.ARRIVAL, characters), { onOpenScene(DigitalWorldStore.ARRIVAL) },
                    DigitalWorldStore.ARRIVAL)
                MapPlaceCard(Modifier.weight(1f), "云眠原", "云海 · 漫步", Icons.Outlined.Cloud,
                    residentsAt(world, DigitalWorldStore.CLOUD_MEADOW, characters), { onOpenScene(DigitalWorldStore.CLOUD_MEADOW) },
                    DigitalWorldStore.CLOUD_MEADOW)
            }
        }
        outdoor.forEach { place ->
            item(key = place.code) {
                MapPlaceCard(Modifier.fillMaxWidth(), place.label, place.subtitle,
                    routeIcon(place.code), residentsAt(world, place.code, characters),
                    { onOpenScene(place.code) }, place.code,
                    DigitalWorldExpansionStore.residentsAt(place.code).size)
            }
        }
        if (facilities.isNotEmpty()) {
            item { WorldMapSectionHeading("公共设施", "相遇、游玩与休息", Icons.Outlined.Storefront) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    facilities.forEach { place ->
                        MapPlaceCard(Modifier.weight(1f), place.label, place.subtitle, routeIcon(place.code),
                            residentsAt(world, place.code, characters), { onOpenScene(place.code) }, place.code,
                            DigitalWorldExpansionStore.residentsAt(place.code).size)
                    }
                }
            }
        }
        if (discovered.isNotEmpty()) {
            item { WorldMapSectionHeading("探索发现", "世界中逐渐形成的真实地点", Icons.Outlined.Explore) }
            discovered.chunked(2).forEach { rowPlaces ->
                item(key = rowPlaces.joinToString("|") { it.code }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowPlaces.forEach { place ->
                            MapPlaceCard(Modifier.weight(1f), place.label, place.subtitle,
                                routeIcon(place.code), residentsAt(world, place.code, characters),
                                { onOpenScene(place.code) }, place.code,
                                DigitalWorldExpansionStore.residentsAt(place.code).size)
                        }
                        if (rowPlaces.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (digitalCharacters.isNotEmpty()) {
            item { WorldMapSectionHeading("角色家园", "他们各自生活的私人空间", Icons.Outlined.Home) }
            digitalCharacters.chunked(2).forEach { rowCharacters ->
                item(key = rowCharacters.joinToString("|") { it.characterId }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowCharacters.forEach { character ->
                            val code = DigitalWorldStore.homeLocation(character.characterId)
                            MapHomeCard(Modifier.weight(1f), character,
                                world.items.count { it.ownerCharacterId == character.characterId },
                                residentsAt(world, code, characters), { onOpenScene(code) })
                        }
                        if (rowCharacters.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (localResidents.isNotEmpty()) {
            item { WorldMapSectionHeading("原住民街区", "每位 NPC 都有固定的家", Icons.Outlined.HolidayVillage) }
            localResidents.chunked(2).forEach { rowResidents ->
                item(key = rowResidents.joinToString("|") { it.id }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowResidents.forEach { resident ->
                            MapNativeHomeCard(Modifier.weight(1f), resident,
                                onClick = { onOpenScene(resident.homeLocationCode) })
                        }
                        if (rowResidents.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { WorldMapSectionHeading("实验区域", "独立预览，不占用正式世界", Icons.Outlined.ViewInAr) }
        item {
            MapPlaceCard(Modifier.fillMaxWidth(), "3D 体验间", "独立空间效果预览", Icons.Outlined.ViewInAr,
                emptyList(), { onOpenScene("preview:3d-room") }, "preview:3d-room")
        }
        item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
    }
}

@Composable
private fun WorldMapSectionHeading(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 5.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Color(0xFF506960), modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFF252F2A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF7D8981), fontSize = 10.sp)
        }
    }
}

@Composable
private fun MapNativeHomeCard(
    modifier: Modifier,
    resident: DigitalWorldResident,
    onClick: () -> Unit,
) {
    val atHome = resident.currentLocationCode == resident.homeLocationCode
    val neighborhood = DigitalWorldPublicPlaces.label(resident.neighborhoodCode)
        ?: if (resident.neighborhoodCode == DigitalWorldStore.CLOUD_MEADOW) "云眠原" else "世界入口"
    Surface(
        modifier = modifier.height(174.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(23.dp),
        color = Color(0xFFFBFCFA),
        border = BorderStroke(1.dp, Color(0xFFDCE6DF)),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            WorldMapIllustration(resident.homeLocationCode, Modifier.fillMaxWidth().height(76.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${resident.name}的家", color = Color(0xFF26352D), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(resident.identity, color = Color(0xFF7B8980), fontSize = 9.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${neighborhood}附近 · ${if (atHome) "在家" else "出门了"}",
                    color = if (atHome) Color(0xFF517869) else Color(0xFF8E7F6A), fontSize = 9.5.sp)
            }
        }
    }
}

@Composable
private fun DigitalWorldLiveHeader(
    homeCount: Int,
    npcHomeCount: Int,
    residentCount: Int,
    itemCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF202623),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF3A4540)),
        shadowElevation = 2.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF171C1A), Color(0xFF2A3530), Color(0xFF1E2421)),
                    ),
                ),
        ) {
            MeetingAtmosphereOverlay(Modifier.matchParentSize(), dark = true)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFFBFD6CA).copy(alpha = .14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(.7.dp, Color(0xFFBFD6CA).copy(alpha = .28f)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(Modifier.size(6.dp), shape = RoundedCornerShape(9.dp), color = Color(0xFFAED7C1)) {}
                            Text("世界正在生活", color = Color(0xFFDCE9E2), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Explore, null, tint = Color(0xFFC7D8CF), modifier = Modifier.size(23.dp))
                }
                Text("去他们真正生活的地方", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                Text(
                    "${homeCount + npcHomeCount} 座家 · ${DigitalWorldPublicPlaces.all.size + 2} 个公共地点 · $residentCount 位居民 · $itemCount 件陈设",
                    color = Color(0xFFB9C8C0),
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}

@Composable
private fun MapPlaceCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    residents: List<CharacterSettings>,
    onClick: () -> Unit,
    sceneCode: String = "",
    nativeResidentCount: Int = 0,
) {
    Surface(
        modifier = modifier.height(172.dp).clickable(onClick = onClick),
        color = Color(0xFFFCFCFB),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFFDDE5DF)),
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            WorldMapIllustration(sceneCode, Modifier.fillMaxWidth().height(60.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = Color(0xFFF1F0ED),
                    border = BorderStroke(.7.dp, Color(0xFFE0DED8)),
                    modifier = Modifier.size(43.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Color(0xFF333333), modifier = Modifier.size(21.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                ResidentAvatarStrip(residents)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF202020), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color(0xFF777570), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (residents.isNotEmpty() || nativeResidentCount > 0) {
                    Text(
                        (residents.joinToString("、") { it.displayName } + if (nativeResidentCount > 0) " · ${nativeResidentCount}位原住民" else "").trimStart(),
                        color = Color(0xFF777570),
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapHomeCard(
    modifier: Modifier,
    character: CharacterSettings,
    itemCount: Int,
    residents: List<CharacterSettings>,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(174.dp).clickable(onClick = onClick),
        color = Color(0xFFFCFCFB),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF34322F)),
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF0EFEB),
                    border = BorderStroke(.7.dp, Color(0xFFE0DED8)),
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Home, null, tint = Color(0xFF393632), modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                ResidentAvatarStrip(residents)
            }
            WorldMapIllustration(DigitalWorldStore.homeLocation(character.characterId), Modifier.fillMaxWidth().height(49.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 34)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${character.displayName}的家",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (residents.isEmpty()) {
                            "$itemCount 件家具"
                        } else {
                            "${residents.size} 人在这里 · $itemCount 件家具"
                        },
                        color = Color(0xFF777570),
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResidentAvatarStrip(residents: List<CharacterSettings>) {
    if (residents.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        residents.take(3).forEach { character ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(.7.dp, Color(0xFFD8D6D1)),
                color = Color.White,
            ) {
                LuluProfileAvatar(
                    character.avatarUri,
                    character.displayName.take(1).ifBlank { "角" },
                    22,
                )
            }
        }
        if (residents.size > 3) {
            Surface(
                modifier = Modifier.size(22.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF0EFEC),
                border = BorderStroke(.7.dp, Color(0xFFD8D6D1)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "+${residents.size - 3}",
                        color = Color(0xFF686560),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun residentsAt(
    world: DigitalWorldState,
    code: String,
    characters: List<CharacterSettings>,
): List<CharacterSettings> = characters.filter { world.characterLocations[it.characterId] == code }
