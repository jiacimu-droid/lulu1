package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

/**
 * Location-first digital world browser.
 * The legacy selection arguments remain only for source compatibility with older callers.
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
        if (code == DigitalWorldStore.ARRIVAL) return "世界入口"
        if (code == DigitalWorldStore.CLOUD_MEADOW) return "云眠原"
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
                MeetingToolButton(
                    icon = Icons.Outlined.Chair,
                    contentDescription = "家具城",
                    onClick = { showCatalog = true },
                )
                MeetingVoiceToggleButton(
                    enabled = voiceEnabled,
                    onToggle = ::toggleVoice,
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
        } else {
            DigitalWorldSceneCanvas(
                modifier = Modifier.fillMaxWidth().weight(1f),
                sceneCode = activeSceneCode,
                homeCharacterId = activeHomeId,
                characters = characters,
                world = world,
                onCharacterClick = { talkTo(it, activeSceneLabel ?: "世界入口") },
            )
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

    if (showCatalog) FurnitureCatalogDialog(onDismiss = { showCatalog = false })
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
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MapPlaceCard(
                    modifier = Modifier.weight(1f),
                    title = "世界入口",
                    icon = Icons.Outlined.AutoAwesome,
                    residents = residentsAt(world, DigitalWorldStore.ARRIVAL, characters),
                    onClick = { onOpenScene(DigitalWorldStore.ARRIVAL) },
                )
                MapPlaceCard(
                    modifier = Modifier.weight(1f),
                    title = "云眠原",
                    icon = Icons.Outlined.Cloud,
                    residents = residentsAt(world, DigitalWorldStore.CLOUD_MEADOW, characters),
                    onClick = { onOpenScene(DigitalWorldStore.CLOUD_MEADOW) },
                )
            }
        }
        digitalCharacters.chunked(2).forEach { rowCharacters ->
            item(key = rowCharacters.joinToString("|") { it.characterId }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowCharacters.forEach { character ->
                        val homeCode = DigitalWorldStore.homeLocation(character.characterId)
                        val itemCount = world.items.count { it.ownerCharacterId == character.characterId }
                        MapHomeCard(
                            modifier = Modifier.weight(1f),
                            character = character,
                            itemCount = itemCount,
                            residents = residentsAt(world, homeCode, characters),
                            onClick = { onOpenScene(homeCode) },
                        )
                    }
                    if (rowCharacters.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
    }
}

@Composable
private fun MapPlaceCard(
    modifier: Modifier,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    residents: List<CharacterSettings>,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(136.dp).clickable(onClick = onClick),
        color = Color(0xFFFCFCFB),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF34322F)),
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
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
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF202020))
                if (residents.isNotEmpty()) {
                    Text(
                        residents.joinToString("、") { it.displayName },
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
        modifier = modifier.height(154.dp).clickable(onClick = onClick),
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
