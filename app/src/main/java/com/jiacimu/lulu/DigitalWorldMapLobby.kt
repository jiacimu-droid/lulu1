package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
                onLocationChanged(
                    DigitalWorldStore.state.value.homes[characterId]?.name ?: "${character.displayName}的家",
                )
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
                else -> home?.name
                    ?: homeCharacterId?.let { "${MigratedDomainStores.characters.get(it).displayName}的家" }
                    ?: "数字世界"
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

    if (showCatalog) FurnitureCatalogDialog(onDismiss = { showCatalog = false })
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
    val digitalCharacters = characters.filter {
        (profiles[it.characterId] ?: DigitalLifeProfileStore.get(it.characterId)).enabled
    }
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
                                    isHome = world.characterLocations[character.characterId] ==
                                        DigitalWorldStore.homeLocation(character.characterId),
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

    LaunchedEffect(sceneLabel) {
        if (sceneLabel.isNotBlank() && locationDraft != sceneLabel) onLocationChanged(sceneLabel)
    }

    Surface(color = LuluColors.Paper, tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                items(characters, key = CharacterSettings::characterId) { character ->
                    val profile = profiles[character.characterId] ?: DigitalLifeProfileStore.get(character.characterId)
                    FilterChip(
                        selected = character.characterId in selectedIds,
                        onClick = { onToggle(character.characterId) },
                        enabled = profile.isResolved,
                        label = { Text(character.displayName, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                if (profile.enabled) Icons.Outlined.Cloud else Icons.Outlined.PersonOutline,
                                null,
                                Modifier.size(15.dp),
                            )
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
                Text(
                    if (selectedIds.size > 1) "和 ${selectedIds.size} 个角色在这里见面" else "开始见面",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp)
        }
    }
}

private fun peopleAt(world: DigitalWorldState, code: String): String {
    val names = world.characterLocations
        .filterValues { it == code }
        .keys
        .map { MigratedDomainStores.characters.get(it).displayName }
    return if (names.isEmpty()) "现在很安静" else names.joinToString("、") + " 在这里"
}
