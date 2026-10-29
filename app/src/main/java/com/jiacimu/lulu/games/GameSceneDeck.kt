package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared touch HUD for explorable games.
 * Navigation and contextual actions NEVER live in an inventory sheet.
 * Left thumb moves; right thumb acts; center stays unobstructed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameSceneDeck(
    accent: Color,
    status: String,
    notice: String = "",
    scene: @Composable () -> Unit,
    companion: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    movement: @Composable () -> Unit = {},
    quickActions: @Composable () -> Unit = {},
    tools: @Composable () -> Unit,
) {
    var drawer by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().background(Color(0xFF09151D))) {
        val wide = maxWidth > maxHeight
        val sideWidth = minOf(maxWidth * .43f, 330.dp)
        val panelHeight = minOf(if (wide) maxHeight * .32f else maxHeight * .18f, 130.dp)
        val teamState = rememberSaveableStateHolder()
        val teamPanel: @Composable () -> Unit = {
            teamState.SaveableStateProvider("team") {
                Column(Modifier.heightIn(max = panelHeight).verticalScroll(rememberScrollState())) {
                    companion()
                }
            }
        }
        scene()
        // Passive shading must not intercept map gestures.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color(0xBD09151D), Color.Transparent, Color.Transparent, Color(0xBD09151D),
        ))))
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 5.dp)
                .widthIn(max = if (wide) maxWidth * .48f else maxWidth * .59f),
            color = Color(0xD9132932), shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = .30f)),
        ) {
            Text(status, color = Color.White, fontSize = 11.sp, maxLines = 2,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp))
        }
        if (notice.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 9.dp, top = 44.dp)
                    .widthIn(max = if (wide) maxWidth * .42f else maxWidth * .56f),
                color = Color(0xA0102630), shape = RoundedCornerShape(9.dp),
            ) {
                Text(notice, color = Color(0xFFE5EEE9), fontSize = 10.sp,
                    maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
        if (!drawer) {
            Box(Modifier.align(Alignment.TopEnd).width(sideWidth).padding(top = 5.dp, end = 8.dp)) {
                teamPanel()
            }
        }
        // Two independent thumb zones, leaving the middle of the world entirely playable.
        Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding()
            .padding(start = 9.dp, bottom = 8.dp)) {
            movement()
        }
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .width(if (wide) 206.dp else (maxWidth - 158.dp).coerceIn(152.dp, 206.dp))
                .padding(end = 9.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.End,
        ) {
            quickActions()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                actions()
            }
            FilledTonalButton(
                onClick = { drawer = !drawer },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xD92A414B), contentColor = Color.White),
            ) {
                Text(if (drawer) "关闭背包" else "背包 / 建造 / 更多", fontSize = 10.sp)
            }
        }
        // Landscape management is an in-scene side panel, NOT an opaque full-screen dialog.
        // The map and the left movement control remain usable while it is open.
        if (drawer && wide) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp, bottom = 6.dp)
                    .width(sideWidth)
                    .heightIn(max = (maxHeight - 18.dp).coerceAtLeast(100.dp)),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF5112630),
                border = BorderStroke(1.dp, accent.copy(alpha = .35f)),
                shadowElevation = 10.dp,
            ) {
                Box {
                    tools()
                    FilledTonalButton(
                        onClick = { drawer = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).height(30.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp),
                    ) { Text("关闭 ×", fontSize = 10.sp) }
                }
            }
        }
    }
    // Portrait remains usable without squeezing the management panel beside a tiny map.
    if (drawer) {
        androidx.compose.runtime.key("portrait-sheet") {
            // The wide panel is already rendered above. Determine current window size anew.
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            if (configuration.screenWidthDp <= configuration.screenHeightDp) {
                ModalBottomSheet(
                    onDismissRequest = { drawer = false },
                    containerColor = Color(0xFF132832),
                    contentColor = Color.White,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                ) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(.72f)) { tools() }
                }
            }
        }
    }
}

/** Discrete directional pad: one press = one actual tile move, with no hidden menu. */
@Composable
internal fun GameDirectionalPad(
    accent: Color,
    enabled: Boolean = true,
    onStep: (dx: Int, dy: Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameDirectionKey("↑", accent, enabled) { onStep(0, -1) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameDirectionKey("←", accent, enabled) { onStep(-1, 0) }
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    color = Color(0x9615222B),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, accent.copy(alpha = .3f)),
                ) { Box(contentAlignment = Alignment.Center) {
                    Text("✦", color = accent, fontSize = 12.sp)
                } }
            }
            GameDirectionKey("→", accent, enabled) { onStep(1, 0) }
        }
        GameDirectionKey("↓", accent, enabled) { onStep(0, 1) }
    }
}

@Composable
private fun GameDirectionKey(
    text: String, accent: Color, enabled: Boolean, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(13.dp),
        color = if (enabled) Color(0xD51C3540) else Color(0x88101E26),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) .7f else .18f)),
        shadowElevation = if (enabled) 3.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Color.White else Color.Gray,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun GameHudQuickAction(
    label: String, accent: Color, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(35.dp),
        color = if (enabled) Color(0xE21F3741) else Color(0xA516252C),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) .64f else .16f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (enabled) Color.White else Color(0xFF7D9298),
                fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
        }
    }
}
