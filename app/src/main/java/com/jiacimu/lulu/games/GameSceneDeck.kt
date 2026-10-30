package com.jiacimu.lulu.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    toolsLabel: String = "行囊 / 更多",
    scene: @Composable () -> Unit,
    companion: @Composable () -> Unit,
    companionAvailable: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
    movement: @Composable () -> Unit = {},
    quickActions: @Composable () -> Unit = {},
    tools: @Composable () -> Unit,
) {
    var drawer by remember { mutableStateOf(false) }
    var teamExpanded by remember { mutableStateOf(true) }
    BackHandler(enabled = drawer) { drawer = false }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().background(Color(0xFF09151D))) {
        val wide = maxWidth > maxHeight
        LaunchedEffect(wide) { drawer = false }
        val sideWidth = minOf(maxWidth * .39f, 310.dp)
        val panelHeight = minOf(if (wide) maxHeight * .46f else maxHeight * .25f, 205.dp)
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
        if (!drawer && companionAvailable) {
            Column(Modifier.align(Alignment.TopEnd).width(sideWidth)
                .padding(top = 5.dp, end = 8.dp)) {
                Surface(
                    color = Color(0xD9132932), shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = .24f)),
                ) {
                    Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 10.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("同行伙伴", modifier = Modifier.weight(1f), color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        TextButton(onClick = { teamExpanded = !teamExpanded },
                            contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(if (teamExpanded) "收起" else "展开", fontSize = 10.sp,
                                color = accent)
                        }
                    }
                }
                if (teamExpanded) teamPanel()
            }
        }
        // Two independent thumb zones, leaving the middle of the world entirely playable.
        Box(Modifier.align(Alignment.BottomStart).navigationBarsPadding()
            .padding(start = 9.dp, bottom = 8.dp)) {
            movement()
        }
        if (!drawer) Column(
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
                Text(toolsLabel, fontSize = 10.sp, maxLines = 1)
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
                Column {
                    Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(toolsLabel, modifier = Modifier.weight(1f),
                            color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        TextButton(onClick = { drawer = false },
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("关闭 ×", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    Box(Modifier.weight(1f)) { tools() }
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

/** Turn-based navigation: a tap is one tile, a hold repeats at a bounded cadence. */
@Composable
internal fun GameDirectionalPad(
    accent: Color,
    enabled: Boolean = true,
    holdRepeatMs: Long = 340L,
    onStep: (dx: Int, dy: Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameDirectionKey("↑", "向上移动", accent, enabled, holdRepeatMs) { onStep(0, -1) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameDirectionKey("←", "向左移动", accent, enabled, holdRepeatMs) { onStep(-1, 0) }
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(26.dp),
                    color = Color(0x9615222B),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, accent.copy(alpha = .3f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✦", color = accent, fontSize = 12.sp)
                    }
                }
            }
            GameDirectionKey("→", "向右移动", accent, enabled, holdRepeatMs) { onStep(1, 0) }
        }
        GameDirectionKey("↓", "向下移动", accent, enabled, holdRepeatMs) { onStep(0, 1) }
    }
}

@Composable
private fun GameDirectionKey(
    text: String,
    accessibilityLabel: String,
    accent: Color,
    enabled: Boolean,
    holdRepeatMs: Long,
    action: () -> Unit,
) {
    val currentAction = rememberUpdatedState(action)
    var pressed by remember { mutableStateOf(false) }
    val repeatMs = holdRepeatMs.coerceAtLeast(250L)
    Surface(
        modifier = Modifier
            .size(46.dp)
            .semantics {
                role = Role.Button
                contentDescription = accessibilityLabel
                if (enabled) {
                    onClick { currentAction.value(); true }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, repeatMs) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    try {
                        // Execute on down, never on release: a held move cannot gain a stray final turn.
                        currentAction.value()
                        coroutineScope {
                            val repeating = launch {
                                delay(420L)
                                while (isActive) {
                                    currentAction.value()
                                    delay(repeatMs)
                                }
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                repeating.cancel()
                            }
                        }
                    } finally {
                        pressed = false
                    }
                })
            },
        shape = RoundedCornerShape(13.dp),
        color = if (!enabled) Color(0x88101E26)
            else if (pressed) accent.copy(alpha = .52f) else Color(0xD51C3540),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) .7f else .18f)),
        shadowElevation = if (pressed) 1.dp else 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Color.White else Color.Gray,
                fontSize = 23.sp, fontWeight = FontWeight.Bold)
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
