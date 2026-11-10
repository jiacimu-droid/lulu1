package com.jiacimu.lulu.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.style.TextAlign
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
    /** Read-only projections of real saved health and secondary progress. */
    healthFraction: Float? = null,
    secondaryFraction: Float? = null,
    secondaryLabel: String = "进度",
    notice: String = "",
    toolsLabel: String = "行囊 / 更多",
    /** An explicit selection change can dismiss the tools panel without changing other games. */
    closeToolsSignal: Any? = null,
    /** An explicit nearby-workbench interaction may open the existing menu. */
    openToolsSignal: Any? = null,
    scene: @Composable () -> Unit,
    companion: @Composable () -> Unit,
    companionAvailable: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
    movement: @Composable () -> Unit = {},
    quickActions: @Composable () -> Unit = {},
    tools: @Composable () -> Unit,
) {
    var drawer by remember { mutableStateOf(false) }
    LaunchedEffect(closeToolsSignal) { drawer = false }
    LaunchedEffect(openToolsSignal) { if (openToolsSignal != null) drawer = true }
    var teamExpanded by remember { mutableStateOf(false) }
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
            Color(0x7909151D), Color.Transparent, Color.Transparent, Color(0x7909151D),
        ))))
        // Capture BoxWithConstraints dimensions before entering ColumnScope:
        // nested layout scopes cannot resolve BoxWithConstraints.maxWidth directly.
        val hudStatusWidth = if (wide) maxWidth * .48f else maxWidth * .59f
        val hudNoticeWidth = if (wide) maxWidth * .42f else maxWidth * .56f
        // Capture BoxWithConstraints height in its own receiver scope. Inside
        // the bottom-right Column, ColumnScope shadows the constraint receiver
        // and Kotlin cannot implicitly resolve maxHeight (CI #857 / #858).
        val quickInfoMaxHeight =
            if (wide) minOf(maxHeight * .39f, 195.dp)
            else minOf(maxHeight * .30f, 260.dp)
        // Measure the HUD naturally: on narrow screens and with large fonts,
        // status can wrap to two lines. A fixed 44dp notice offset overlapped it.
        Column(
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 9.dp, top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Surface(
                modifier = Modifier.widthIn(
                    max = hudStatusWidth),
                color = Color(0xE0162935), shape = RoundedCornerShape(17.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = .62f)),
                shadowElevation = 5.dp,
            ) {
                Column(Modifier.padding(horizontal=12.dp,vertical=9.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        Surface(modifier=Modifier.size(29.dp),shape=CircleShape,
                            color=accent.copy(alpha=.19f),
                            border=BorderStroke(1.dp,accent.copy(alpha=.60f))) {
                            Box(contentAlignment=Alignment.Center) {
                                Text("✦",color=accent,fontSize=17.sp,
                                    fontWeight=FontWeight.Bold)
                            }
                        }
                        Text(status,color=Color.White,fontSize=11.sp,
                            fontWeight=FontWeight.SemiBold,maxLines=2,lineHeight=16.sp)
                    }
                    if(healthFraction!=null || secondaryFraction!=null) {
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(healthFraction!=null)
                                GameHudConditionTrack("生命",healthFraction,
                                    Color(0xFFFFA78C))
                            if(secondaryFraction!=null)
                                GameHudConditionTrack(secondaryLabel,
                                    secondaryFraction,accent)
                        }
                    }
                }
            }
            if (notice.isNotBlank()) {
                Surface(
                    modifier = Modifier.widthIn(
                        max = hudNoticeWidth),
                    color = Color(0xA0102630), shape = RoundedCornerShape(9.dp),
                ) {
                    Text(notice, color = Color(0xFFE5EEE9), fontSize = 11.sp,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
        }
        if (companionAvailable) {
            Column(Modifier.align(Alignment.TopEnd).width(sideWidth)
                .padding(top = 5.dp, end = 8.dp)) {
                if (!drawer) {
                    Surface(
                        color = Color(0xD9132932), shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = .24f)),
                    ) {
                        Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 10.dp, end = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("♡ 队伍与语音", modifier = Modifier.weight(1f), color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            TextButton(onClick = { teamExpanded = !teamExpanded },
                                contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (teamExpanded) "收起" else "展开", fontSize = 10.sp,
                                    color = accent)
                            }
                        }
                    }
                }
                // Keep the exact same composition mounted when visually collapsed.
                // Both companion panels own live AI decisions, inbox and voice effects.
                // Removing the composition here would cancel their in-flight work.
                Box(Modifier.then(
                    if (teamExpanded && !drawer) Modifier.heightIn(max = panelHeight)
                    else Modifier.height(0.dp),
                ).clipToBounds()) {
                    teamPanel()
                }
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
            // The objective/threat commentary can be arbitrarily long during
            // co-op. Cap and scroll that informational layer, not the player's
            // combat/build buttons. Small landscape windows stay playable.
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = quickInfoMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(5.dp),
            ) {
                quickActions()
            }
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

@Composable
private fun GameHudConditionTrack(label:String,fraction:Float,accent:Color) {
    Row(verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(label,color=Color(0xFFE3F1EA),fontSize=9.sp,
            fontWeight=FontWeight.Bold,modifier=Modifier.width(28.dp),
            maxLines=1)
        Box(Modifier.weight(1f).height(6.dp)
            .background(Color(0xFF40555A),RoundedCornerShape(10.dp))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f,1f))
                .height(6.dp).background(
                    Brush.horizontalGradient(listOf(
                        accent.copy(alpha=.74f),accent)),
                    RoundedCornerShape(10.dp)))
        }
    }
}

/**
 * One continuous thumb zone, not four independent gesture detectors.
 * Keeping the pressed pointer in one parent permits sliding between directions;
 * the simulation still validates collisions, loot and combat on each actual step.
 */
@Composable
internal fun GameDirectionalPad(
    accent: Color,
    enabled: Boolean = true,
    holdRepeatMs: Long = 340L,
    stopWhen: Any? = null,
    interactLabel: String = "互动",
    interactEnabled: Boolean = true,
    onInteract: (() -> Unit)? = null,
    onHoldChange: ((Boolean) -> Unit)? = null,
    onStep: (dx: Int, dy: Int) -> Unit,
) {
    val latestStep by rememberUpdatedState(onStep)
    val latestInteract by rememberUpdatedState(onInteract)
    val latestInteractEnabled by rememberUpdatedState(interactEnabled)
    val latestHoldChange by rememberUpdatedState(onHoldChange)
    var activeDirection by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var centerPressed by remember { mutableStateOf(false) }
    val repeatMs = holdRepeatMs.coerceAtLeast(135L)
    Box(
        modifier = Modifier.size(144.dp)
            .pointerInput(enabled, repeatMs, stopWhen) {
                if (!enabled) return@pointerInput
                // Launch the held-repeat coroutine outside the restricted
                // AwaitPointerEventScope; pointer events stay on that scope.
                coroutineScope {
                    awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var direction = gameJoystickDirection(down.position.x, down.position.y,
                        size.width.toFloat(), size.height.toFloat())
                    var centerTap = direction == null
                    var moving = direction != null
                    if (moving) latestHoldChange?.invoke(true)
                    activeDirection = direction
                    centerPressed = centerTap
                    direction?.let { latestStep(it.first, it.second) }
                    val repeating = launch {
                            delay(repeatMs)
                            while (isActive) {
                                direction?.let { latestStep(it.first, it.second) }
                                delay(repeatMs)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!pointer.pressed) {
                                    if (centerTap && direction == null &&
                                        latestInteractEnabled) latestInteract?.invoke()
                                    break
                                }
                                val next = gameJoystickDirection(pointer.position.x,
                                    pointer.position.y,
                                    size.width.toFloat(), size.height.toFloat())
                                if (next != null) {
                                    centerTap = false
                                    if (!moving) {
                                        moving = true
                                        latestHoldChange?.invoke(true)
                                    }
                                }
                                if (next != direction) {
                                    direction = next
                                    activeDirection = next
                                    centerPressed = centerTap && next == null
                                    next?.let { latestStep(it.first, it.second) }
                                }
                                pointer.consume()
                            }
                        } finally {
                            repeating.cancel()
                            activeDirection = null
                            centerPressed = false
                            if (moving) latestHoldChange?.invoke(false)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // A single circular joystick shell makes this read as a game control, while
        // preserving four independent accessibility semantics and hold-to-walk input.
        Surface(modifier=Modifier.matchParentSize(),shape=CircleShape,
            color=Color(0xB5162D37),
            border=BorderStroke(2.dp,accent.copy(alpha=.52f)),
            shadowElevation=4.dp) {}
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GameDirectionCell("↑", "向上移动", accent, enabled,
                activeDirection == (0 to -1)) { latestStep(0, -1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameDirectionCell("←", "向左移动", accent, enabled,
                    activeDirection == (-1 to 0)) { latestStep(-1, 0) }
                Surface(
                    modifier = Modifier.size(46.dp).semantics {
                        role = Role.Button
                        contentDescription = interactLabel
                        if (enabled && interactEnabled && onInteract != null) {
                            onClick { latestInteract?.invoke(); true }
                        } else disabled()
                    },
                    shape = CircleShape,
                    color = if (enabled && interactEnabled) {
                        if (centerPressed) accent.copy(alpha = .5f) else Color(0xE3375158)
                    } else Color(0x8815222B),
                    border = BorderStroke(1.dp, accent.copy(alpha =
                        if (enabled && interactEnabled) .8f else .2f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✦", color = if (enabled && interactEnabled) accent else Color.Gray,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                GameDirectionCell("→", "向右移动", accent, enabled,
                    activeDirection == (1 to 0)) { latestStep(1, 0) }
            }
            GameDirectionCell("↓", "向下移动", accent, enabled,
                activeDirection == (0 to 1)) { latestStep(0, 1) }
        }
    }
}

@Composable
private fun GameDirectionCell(
    text: String,
    description: String,
    accent: Color,
    enabled: Boolean,
    active: Boolean,
    onActivate: () -> Unit,
) {
    val currentActivate by rememberUpdatedState(onActivate)
    Surface(
        modifier = Modifier.size(46.dp).semantics {
            role = Role.Button
            contentDescription = description
            if (enabled) onClick { currentActivate(); true } else disabled()
        },
        shape = CircleShape,
        color = if (!enabled) Color(0x88101E26)
            else if (active) accent.copy(alpha = .62f) else Color(0xE0223944),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) .7f else .18f)),
        shadowElevation = if (active) 1.dp else 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = if (enabled) Color.White else Color.Gray,
                fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Pure hit testing for both touch down and finger dragging on the same joystick. */
internal fun gameJoystickDirection(x: Float, y: Float, width: Float, height: Float):
    Pair<Int, Int>? {
    if (width <= 0f || height <= 0f) return null
    val dx = x - width / 2f
    val dy = y - height / 2f
    val deadZone = minOf(width, height) * .155f
    if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) < deadZone) return null
    return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy))
        (if (dx >= 0f) 1 else -1) to 0
    else 0 to (if (dy >= 0f) 1 else -1)
}

@Composable
internal fun GameHudQuickAction(
    label: String, accent: Color, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.height(52.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.5.dp,accent.copy(alpha=if(enabled).72f else .18f)),
        shadowElevation = if(enabled)3.dp else 0.dp,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            if(enabled)listOf(Color(0xF0425359),Color(0xF01A303B))
            else listOf(Color(0xBB24313A),Color(0xBB192832)))),
            contentAlignment=Alignment.Center) {
            Text(label,color=if(enabled)Color.White else Color(0xFF9DADB0),
                fontWeight=FontWeight.Bold,fontSize=if(label.length>4)10.sp else 12.sp,
                lineHeight=13.sp,maxLines=2,textAlign=TextAlign.Center,
                modifier=Modifier.padding(horizontal=3.dp))
        }
    }
}
