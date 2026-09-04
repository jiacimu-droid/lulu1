package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.DigitalWorldState

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
        DigitalWorldSceneCanvas(
            modifier = Modifier.weight(1f).fillMaxSize(),
            sceneCode = sceneCode,
            homeCharacterId = homeCharacterId,
            characters = characters,
            world = world,
            onCharacterClick = onCharacterClick,
            controlsBottomPadding = 24.dp,
        )
    }
}

/**
 * The old square illustration was intentionally replaced with a world-space simulation. The
 * wrapper stays stable so map, meeting and future activities all use the same collision scene.
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
) {
    DigitalWorldGameScene(
        modifier = modifier,
        sceneCode = sceneCode,
        homeCharacterId = homeCharacterId,
        characters = characters,
        world = world,
        onCharacterClick = onCharacterClick,
        onWorldAction = onWorldAction,
        controlsBottomPadding = controlsBottomPadding,
    )
}
