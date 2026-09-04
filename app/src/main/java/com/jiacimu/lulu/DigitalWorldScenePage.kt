package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.DigitalWorldActivityCatalog
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldState
import com.jiacimu.lulu.data.WorldFirstExplorationMemory
import kotlinx.coroutines.delay

/** Full-screen explorer used when the player enters a digital-world location outside a meeting. */
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
    val context = LocalContext.current
    var rememberedAction by remember(sceneCode) { mutableStateOf("") }

    LaunchedEffect(rememberedAction) {
        if (rememberedAction.isBlank()) return@LaunchedEffect
        delay(2_500)
        rememberedAction = ""
    }

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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF0A1512),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            DigitalWorldSceneCanvas(
                modifier = Modifier.fillMaxSize(),
                sceneCode = sceneCode,
                homeCharacterId = homeCharacterId,
                characters = characters,
                world = world,
                onCharacterClick = onCharacterClick,
                onWorldAction = { action ->
                    WorldFirstExplorationMemory.record(
                        context = context,
                        worldId = "digital-world",
                        locationId = sceneCode,
                        locationLabel = sceneLabel,
                        action = action,
                    )
                    rememberedAction = action
                },
                controlsBottomPadding = 24.dp,
                controlsEnabled = true,
                showExplorationHud = true,
            )

            if (rememberedAction.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    color = Color(0xEB10201C),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFB7FFE8).copy(alpha = .32f)),
                    shadowElevation = 10.dp,
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("世界记住了这件事", color = Color(0xFFBFFFEA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(rememberedAction, color = Color.White, fontSize = 11.5.sp, lineHeight = 16.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}

/**
 * Shared renderer for map exploration and meeting scenes. Exploration controls are explicitly
 * switchable so reading/dialogue mode can become a clean cinematic layer instead of showing a
 * joystick underneath the story UI.
 *
 * Public-place routine actions live here as a small contextual dock. They are deliberately quick
 * world actions: walking around a café or browsing the game hall should not force a long AI scene.
 */
@Composable
internal fun DigitalWorldSceneCanvas(
    modifier: Modifier,
    sceneCode: String,
    homeCharacterId: String?,
    characters: List<CharacterSettings>,
    world: DigitalWorldState,
    onCharacterClick: (String) -> Unit,
    onWorldAction: ((String) -> Unit)? = null,
    controlsBottomPadding: androidx.compose.ui.unit.Dp = 18.dp,
    controlsEnabled: Boolean = true,
    showExplorationHud: Boolean = true,
) {
    val context = LocalContext.current
    var publicActionNotice by remember(sceneCode) { mutableStateOf("") }
    val publicPlace = remember(sceneCode) { DigitalWorldPublicPlaces.all.firstOrNull { it.code == sceneCode } }
    val publicActions = remember(sceneCode) { DigitalWorldActivityCatalog.locationOptions(sceneCode) }

    LaunchedEffect(publicActionNotice) {
        if (publicActionNotice.isBlank()) return@LaunchedEffect
        delay(1_900)
        publicActionNotice = ""
    }

    Box(modifier) {
        DigitalWorldGameScene(
            modifier = Modifier.fillMaxSize(),
            sceneCode = sceneCode,
            homeCharacterId = homeCharacterId,
            characters = characters,
            world = world,
            onCharacterClick = onCharacterClick,
            onWorldAction = onWorldAction,
            controlsBottomPadding = controlsBottomPadding,
            controlsEnabled = controlsEnabled,
            showExplorationHud = showExplorationHud,
        )

        if (controlsEnabled && publicPlace != null && publicActions.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 88.dp, end = 88.dp, bottom = controlsBottomPadding + 86.dp),
                color = Color(0xDB0D1916),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
                shadowElevation = 10.dp,
            ) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Explore, null, tint = Color(0xFFBFFFEA), modifier = Modifier.size(15.dp))
                    publicActions.take(4).forEach { (activityId, label) ->
                        Surface(
                            onClick = {
                                val summary = DigitalWorldActivityCatalog.locationActivitySummary(
                                    characterName = "我",
                                    locationCode = sceneCode,
                                    activityId = activityId,
                                    locationName = publicPlace.label,
                                ) ?: "我在${publicPlace.label}里做了“$label”。"
                                WorldFirstExplorationMemory.record(
                                    context = context,
                                    worldId = "digital-world",
                                    locationId = sceneCode,
                                    locationLabel = publicPlace.label,
                                    action = summary,
                                )
                                publicActionNotice = label
                            },
                            color = Color.White.copy(alpha = .07f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
                        ) {
                            Text(
                                label,
                                color = Color(0xFFE8F4EF),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        if (controlsEnabled && publicActionNotice.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                color = Color(0xE8142520),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, Color(0xFFB7FFE8).copy(alpha = .22f)),
            ) {
                Text(
                    publicActionNotice,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}
