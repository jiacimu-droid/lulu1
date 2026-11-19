package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluAppearancePattern
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.design.LuluCssArtwork
import com.jiacimu.lulu.design.cssColor
import com.jiacimu.lulu.design.cssDp
import com.jiacimu.lulu.design.cssFontSize
import com.jiacimu.lulu.design.luluCssDecor
import com.jiacimu.lulu.design.luluCssElement
import com.jiacimu.lulu.design.luluCssStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.JournalPatchIcon
import com.jiacimu.lulu.design.isJournal
import com.jiacimu.lulu.design.journalLinen
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class DesktopV2Launcher(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val route: MigrationRoute,
)

private val DesktopV2Launchers = listOf(
    DesktopV2Launcher("chat", "聊天", Icons.Outlined.ChatBubbleOutline, MigrationRoute.Chat),
    DesktopV2Launcher("selfpreface", "自序", Icons.Outlined.EditNote, MigrationRoute.SelfPreface),
    DesktopV2Launcher("memory", "记忆", Icons.Outlined.Psychology, MigrationRoute.Memory),
    DesktopV2Launcher("worldbook", "世界书", Icons.Outlined.Public, MigrationRoute.WorldBook),
    DesktopV2Launcher("performance", "性能监测", Icons.Outlined.MonitorHeart, MigrationRoute.Performance),
    DesktopV2Launcher("health", "健康", Icons.Outlined.FavoriteBorder, MigrationRoute.Health),
    DesktopV2Launcher("scroll", "画卷", Icons.Outlined.Collections, MigrationRoute.Scroll),
    DesktopV2Launcher("theater", "阅读", Icons.Outlined.AutoStories, MigrationRoute.Theater),
    DesktopV2Launcher("meeting", "见面", Icons.Outlined.Cloud, MigrationRoute.Meeting),
    DesktopV2Launcher("games", "游戏", Icons.Outlined.SportsEsports, MigrationRoute.Games),
    DesktopV2Launcher("apocalypse", "末世求生", Icons.Outlined.Map, MigrationRoute.ApocalypseSurvival),
    DesktopV2Launcher("settings", "设置", Icons.Outlined.Settings, MigrationRoute.Settings),
    DesktopV2Launcher("appearance", "美化", Icons.Outlined.Palette, MigrationRoute.Appearance),
    DesktopV2Launcher("playground", "Live2D", Icons.Outlined.Face, MigrationRoute.Playground),
    DesktopV2Launcher("guide", "指南", Icons.Outlined.MenuBook, MigrationRoute.Guide),
)

internal fun desktopCssApps(): List<Pair<String, String>> =
    DesktopV2Launchers.map { it.id to it.title }


// Resolve colors inside each composable, from the chosen desktop recipe.
private val DesktopIconSurface: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val DesktopLine: Color @Composable get() = MaterialTheme.colorScheme.outline
private val DesktopAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val DesktopInk: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val DesktopIcon: Color @Composable get() = MaterialTheme.colorScheme.onSurface

@Composable
internal fun MigrationHomeV2(
    onOpen: (MigrationRoute) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val appearance = LocalLuluAppearance.current
    val journal = appearance.isJournal()
    val gridBreathingRoom = (LocalConfiguration.current.screenHeightDp - 520).coerceIn(0, 18).dp
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val ownerChats = ownerVisibleQqConversations(conversations)
    val shortcutConversation = ownerChats
        .filter(LuluConversation::pinned)
        .maxByOrNull(LuluConversation::updatedAt)
        ?: ownerChats.maxByOrNull(LuluConversation::updatedAt)
    val currentCharacter = shortcutConversation?.characterId
        ?.let { characters[it] ?: MigratedDomainStores.characters.get(it) }
        ?: characters.values.firstOrNull()
    val recentGroup = shortcutConversation?.groupChat
    val recentDisplayName = recentGroup?.name?.ifBlank { "群聊" } ?: currentCharacter?.displayName ?: "创建你的第一位角色"
    val recentAvatarUri = recentGroup?.avatarUri ?: currentCharacter?.avatarUri
    val recentFallback = if (recentGroup != null) {
        recentDisplayName.take(1).ifBlank { "群" }
    } else {
        currentCharacter?.displayName?.take(1).orEmpty().ifBlank { "+" }
    }
    val today = rememberLuluDesktopDate()
    val greeting = when (LocalTime.now().hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    // Do not address a brand-new user as somebody else's "master".
    val ownerDisplayName = context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
        .getString("display_name", null)?.trim()?.takeUnless { it.isBlank() || it == "我" }
    val personalizedGreeting = ownerDisplayName?.let { "$greeting，$it" } ?: greeting
    var slots by remember { mutableStateOf(loadDesktopSlots(context)) }
    var widgets by remember { mutableStateOf(LuluDesktopWidgetStore.load(context)) }
    val widgetRevision by LuluDesktopWidgetStore.revision.collectAsState()
    val dailyReadingRevision by LuluWidgetDailyRole.revision.collectAsState()
    val authoredNoteRevision by LuluWidgetRoleActions.revision.collectAsState()
    LaunchedEffect(widgetRevision) {
        if (widgetRevision > 0L) widgets = LuluDesktopWidgetStore.load(context)
    }
    val roleNotes = remember(widgets, authoredNoteRevision) {
        widgets.mapNotNull { card ->
            LuluWidgetRoleActions.readNote(context, card)?.let { card.id to it.content }
        }.toMap()
    }
    val dailyReadings = remember(widgets, today, dailyReadingRevision) {
        widgets.mapNotNull { card ->
            LuluWidgetDailyRole.read(context, card, today)?.let { card.id to it }
        }.toMap()
    }
    LaunchedEffect(today, widgets.map { listOf(it.id, it.characterId, it.sign.toString(),
        it.dailyReadingEnabled.toString()) }) {
        // Opening the desktop must never issue a second paid horoscope request.
        // WorkManager handles today's missing reading and each next local morning.
        runCatching { LuluWidgetDailyRole.schedule(context, widgets) }
    }
    var showWidgetPicker by remember { mutableStateOf(false) }
    var editingWidgetId by remember { mutableStateOf<String?>(null) }
    var showingWidgetPlanner by remember { mutableStateOf(false) }
    val todoRevision by SelfTodoStore.revision.collectAsState()
    val widgetTodayTasks = remember(todoRevision, today) {
        SelfTodoStore.readForRole(context)?.let { saved ->
            SelfTodoCalendar.allPending(saved, today).filter { it.date == today }
        }.orEmpty()
    }
    val onWidgetTodoCheck: (TodoOccurrence) -> Unit = { occurrence ->
        if (!SelfTodoStore.completeFromWidget(context, occurrence.task.id, occurrence.date)) {
            android.widget.Toast.makeText(context, "待办未更新，原记录已变化或保存失败",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val widgetCharacterNames = characters.mapValues { it.value.displayName }
    val widgetConversationTexts = ownerChats.associate { it.characterId to it.lastMessage }
    val pageCount = maxOf(
        desktopStoredPageCount(slots.size),
        (widgets.maxOfOrNull { it.page } ?: 0) + 2,
    )
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val dragScope = rememberCoroutineScope()
    var draggingSource by remember { mutableStateOf<Int?>(null) }
    var draggingWidgetId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var dragGrabOffset by remember { mutableStateOf(Offset.Zero) }
    var edgePageHandled by remember { mutableIntStateOf(-1) }

    Surface(modifier = Modifier.luluCssElement("page").fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (!journal) LuluAppearancePattern(Modifier.fillMaxSize())
            Column(
                modifier = Modifier.fillMaxSize()
                    .then(if (journal) Modifier.journalLinen(appearance) else Modifier)
                    .statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(1f)
                        .padding(top = 12.dp + gridBreathingRoom, bottom = 16.dp + gridBreathingRoom)
                        .pointerInput(slots, widgets, pageCount) {
                            val edgePx = 32.dp.toPx()
                            fun currentGrid() = DesktopGridGeometry.fit(size.width, size.height)

                            fun slotAt(grid: DesktopGridGeometry, position: Offset, page: Int): Int? =
                                grid.cellAt(position.x, position.y)?.let { cell ->
                                    desktopSlotIndex(page, cell.row, cell.column)
                                }

                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    val page = pagerState.currentPage
                                    val grid = currentGrid()
                                    val source = slotAt(grid, start, page)
                                    if (source != null) {
                                        val local = source - desktopPageStart(page)
                                        val row = local / DESKTOP_COLUMNS
                                        val col = local % DESKTOP_COLUMNS
                                        val widget = widgets.firstOrNull { candidate ->
                                            candidate.page == page &&
                                                col in candidate.col until candidate.col + candidate.columns &&
                                                row in candidate.row until candidate.row + candidate.rows
                                        }
                                        if (slots.getOrNull(source) != null || widget != null) {
                                            draggingWidgetId = widget?.id
                                            draggingSource = if (widget == null) source else null
                                            dragPosition = start
                                            dragGrabOffset = Offset(
                                                start.x - grid.x(widget?.col ?: col),
                                                start.y - grid.y(widget?.row ?: row),
                                            )
                                            edgePageHandled = -1
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (draggingSource != null || draggingWidgetId != null) {
                                        change.consume()
                                        dragPosition = change.position
                                        val currentPage = pagerState.currentPage
                                        val edgeTarget = when {
                                            dragPosition.x >= size.width - edgePx -> currentPage + 1
                                            dragPosition.x <= edgePx -> currentPage - 1
                                            else -> -1
                                        }
                                        if (edgeTarget == -1) {
                                            edgePageHandled = -1
                                        } else if (edgeTarget in 0 until pageCount && edgePageHandled == -1) {
                                            edgePageHandled = currentPage
                                            dragScope.launch { pagerState.scrollToPage(edgeTarget) }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val grid = currentGrid()
                                    draggingSource?.let { from ->
                                        val target = slotAt(grid, dragPosition, pagerState.currentPage)
                                        if (target != null && widgets.none { w ->
                                            val destPage = desktopPageForSlot(target)
                                            val local = target - desktopPageStart(destPage)
                                            val x = local % DESKTOP_COLUMNS
                                            val y = local / DESKTOP_COLUMNS
                                            w.page == destPage &&
                                                x in w.col until w.col + w.columns &&
                                                y in w.row until w.row + w.rows
                                        }) {
                                            val targetSlots = if (target in slots.indices) slots
                                                else List(desktopPageStart(pageCount)) { slots.getOrNull(it) }
                                            val updated = desktopMoveLauncher(targetSlots, from, target)
                                            if (updated != slots) {
                                                slots = updated
                                                saveDesktopSlots(context, updated)
                                            }
                                        }
                                    }
                                    draggingWidgetId?.let { id ->
                                        val original = widgets.firstOrNull { it.id == id }
                                        if (original != null && grid.cellAt(dragPosition.x, dragPosition.y) != null) {
                                            val origin = dragPosition - dragGrabOffset
                                            val moved = original.copy(
                                                page = pagerState.currentPage,
                                                col = ((origin.x - grid.leftPx) / grid.cellPx).roundToInt()
                                                    .coerceIn(0, DESKTOP_COLUMNS - original.columns),
                                                row = ((origin.y - grid.topPx) / grid.rowStridePx).roundToInt()
                                                    .coerceIn(0, DESKTOP_ROWS - original.rows),
                                            )
                                            if (luluWidgetFits(moved, widgets, slots)) {
                                                val updated = widgets.map { if (it.id == id) moved else it }
                                                if (LuluDesktopWidgetStore.save(context, updated)) widgets = updated
                                            }
                                        }
                                    }
                                    draggingSource = null
                                    draggingWidgetId = null
                                    edgePageHandled = -1
                                },
                                onDragCancel = {
                                    draggingSource = null
                                    draggingWidgetId = null
                                    edgePageHandled = -1
                                },
                            )
                        },
                ) {
                    val density = LocalDensity.current
                    val grid = with(density) {
                        DesktopGridGeometry.fit(maxWidth.roundToPx(), maxHeight.roundToPx())
                    }
                    val cellEdge = with(density) { grid.cellPx.toDp() }
                    // The page root is the drag surface so a long press can cross a
                    // page boundary without destroying its pointer gesture owner.
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = false,
                        userScrollEnabled = draggingSource == null && draggingWidgetId == null,
                    ) { page ->
                        Box(Modifier.fillMaxSize()) {
                            LuluDesktopWidgetCanvas(
                                page = page, grid = grid, isSettledPage = pagerState.settledPage == page,
                                widgets = widgets, draggingWidgetId = draggingWidgetId,
                                launcherSlots = slots, today = today,
                                characterNames = widgetCharacterNames,
                                conversationTexts = widgetConversationTexts,
                                roleReadings = dailyReadings, roleNotes = roleNotes,
                                plannerTasks = widgetTodayTasks,
                                onCompleteTask = onWidgetTodoCheck,
                                modifier = Modifier.fillMaxSize(),
                                onOpenWidget = { widget ->
                                    when (widget.kind) {
                                        LuluWidgetKind.CHAT, LuluWidgetKind.BULLETIN -> Unit
                                        LuluWidgetKind.PLANNER -> showingWidgetPlanner = true
                                        else -> editingWidgetId = widget.id
                                    }
                                },
                                specialContent = { widget, foreground, interaction ->
                                    when (widget.kind) {
                                        LuluWidgetKind.CHAT -> DesktopCompanionCard(
                                            displayName = recentDisplayName,
                                            avatarFallback = recentFallback,
                                            avatarUri = recentAvatarUri,
                                            isGroup = recentGroup != null,
                                            recentMessage = shortcutConversation?.lastMessage
                                                ?.ifBlank { "点这里回到聊天。" }
                                                ?: "从这里开始，创建属于你的角色。",
                                            greeting = personalizedGreeting,
                                            onClick = {
                                                shortcutConversation?.let { onOpenConversation(it.id) }
                                                    ?: onOpen(MigrationRoute.Chat)
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                            foreground = foreground, interaction = interaction,
                                        )
                                        LuluWidgetKind.BULLETIN -> DesktopWorldBulletinCard(
                                            modifier = Modifier.fillMaxSize(), embedded = true,
                                            foreground = foreground, interactionSource = interaction,
                                        )
                                        else -> Unit
                                    }
                                },
                                onEditWidget = { editingWidgetId = it.id },
                                onBlankLongPress = { showWidgetPicker = true },
                            )
                            DesktopLauncherGrid(
                                grid = grid,
                                slots = slots.drop(desktopPageStart(page)).take(DESKTOP_SLOT_COUNT),
                                modifier = Modifier.fillMaxSize(),
                                draggedLauncherId = draggingSource?.let(slots::get),
                                onOpen = onOpen,
                            )
                        }
                    }
                    draggingSource?.let { source ->
                        slots.getOrNull(source)?.let { id ->
                            DesktopV2Launchers.firstOrNull { it.id == id }
                        }
                    }?.let { active ->
                        DesktopV2LauncherItem(
                            launcher = active,
                            modifier = Modifier.size(cellEdge)
                                .absoluteOffset { IntOffset(
                                    (dragPosition.x - dragGrabOffset.x).roundToInt(),
                                    (dragPosition.y - dragGrabOffset.y).roundToInt(),
                                ) }
                                .graphicsLayer {
                                    scaleX = 1.08f
                                    scaleY = 1.08f
                                    shadowElevation = 9f
                                },
                            onClick = {},
                        )
                    }
                    draggingWidgetId?.let { id ->
                        widgets.firstOrNull { it.id == id }?.let { w ->
                            LuluDesktopWidgetCard(
                                widget = w, today = today,
                                characterName = widgetCharacterNames[w.characterId],
                                lastConversationText = widgetConversationTexts[w.characterId],
                                roleReading = dailyReadings[w.id],
                                roleNote = roleNotes[w.id],
                                modifier = Modifier.size(cellEdge * w.columns, cellEdge * w.rows)
                                    .absoluteOffset {
                                        IntOffset(
                                            (dragPosition.x - dragGrabOffset.x).roundToInt(),
                                            (dragPosition.y - dragGrabOffset.y).roundToInt(),
                                        )
                                    }.graphicsLayer { alpha = .93f; shadowElevation = 8f },
                                onOpen = {},
                                onEdit = null,
                                specialContent = { item, ink, _ ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(item.kind.title, color = ink, fontWeight = FontWeight.SemiBold)
                                    }
                                },
                            )
                        }
                    }
                }
                // PagerState.currentPage changes during a swipe. The indicator must
                // own that observation, not invalidate the entire desktop dashboard.
                if (pageCount > 1) Box(Modifier.luluCssElement("desktop-dock")) {
                    DesktopV2PageIndicator(pagerState, pageCount)
                }
            }
        }
    }
    if (showWidgetPicker) LuluDesktopWidgetPicker(
        onAdd = { kind ->
            val fresh = luluPlaceWidget(
                kind = kind, startPage = pagerState.currentPage,
                widgets = widgets, launcherSlots = slots,
                sign = LuluDesktopWidgetStore.profileSign(context),
            )
            if (fresh != null) {
                val next = widgets + fresh
                if (LuluDesktopWidgetStore.save(context, next)) {
                    widgets = next
                    // A widget page remains a first-class desktop page for later icon moves.
                    if (slots.size < desktopPageStart(fresh.page + 2)) {
                        slots = slots + List<String?>(
                            desktopPageStart(fresh.page + 2) - slots.size,
                        ) { null }
                        saveDesktopSlots(context, slots)
                    }
                    dragScope.launch { pagerState.scrollToPage(fresh.page) }
                    editingWidgetId = fresh.id
                }
            }
            showWidgetPicker = false
        },
        onClose = { showWidgetPicker = false },
    )
    widgets.firstOrNull { it.id == editingWidgetId }?.let { selected ->
        LuluDesktopWidgetEditor(
            initial = selected, characters = widgetCharacterNames,
            onSave = { next ->
                if (!luluWidgetFits(next, widgets, slots)) {
                    false
                } else {
                    val changed = widgets.map { if (it.id == next.id) next else it }
                    if (LuluDesktopWidgetStore.save(context, changed)) {
                        widgets = changed
                        if (next.kind == LuluWidgetKind.HOROSCOPE) {
                            LuluDesktopWidgetStore.setProfileSign(context, next.sign)
                        }
                        editingWidgetId = null
                        true
                    } else false
                }
            },
            onDelete = {
                val changed = widgets.filterNot { it.id == selected.id }
                if (LuluDesktopWidgetStore.save(context, changed)) {
                    widgets = changed
                    editingWidgetId = null
                }
            },
            onClose = { editingWidgetId = null },
        )
    }
    if (showingWidgetPlanner) LuluDesktopPlannerDialog(
        tasks = widgetTodayTasks, onCheck = onWidgetTodoCheck,
        onOpenSelfPreface = {
            showingWidgetPlanner = false
            onOpen(MigrationRoute.SelfPreface)
        },
        onClose = { showingWidgetPlanner = false },
    )
}

@Composable
private fun DesktopV2PageIndicator(pagerState: PagerState, pageCount: Int) {
    val selectedPage = pagerState.currentPage
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            Surface(
                modifier = Modifier.padding(horizontal = 3.dp)
                    .size(if (index == selectedPage) 7.dp else 5.dp),
                shape = CircleShape,
                color = if (index == selectedPage) DesktopAccent else DesktopLine,
            ) {}
        }
    }
}

@Composable
private fun DesktopCompanionCard(
    displayName: String,
    avatarFallback: String,
    avatarUri: String?,
    isGroup: Boolean,
    recentMessage: String,
    onClick: () -> Unit,
    greeting: String,
    modifier: Modifier = Modifier,
    foreground: Color,
    interaction: MutableInteractionSource,
) {
    val secondary = foreground.copy(alpha = .79f)
    BoxWithConstraints(modifier.luluCssElement("companion")
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        val narrow = maxWidth < 232.dp
        val short = maxHeight < 106.dp
        val showActionLabel = !narrow && maxHeight >= 132.dp
        if (narrow && maxHeight >= 210.dp) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LuluProfileAvatar(imageUri = avatarUri, fallback = avatarFallback, size = 60)
                Spacer(Modifier.height(12.dp))
                Text(displayName, color = foreground, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(recentMessage.trim(), color = secondary, fontSize = 14.sp, lineHeight = 19.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            val avatarSize = if (short) 42 else if (narrow) 48 else 60
            Row(
                modifier = Modifier.fillMaxSize().padding(
                    start = if (narrow) 10.dp else 17.dp,
                    end = if (narrow) 12.dp else 18.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LuluProfileAvatar(imageUri = avatarUri, fallback = avatarFallback, size = avatarSize)
                Spacer(Modifier.width(if (narrow) 9.dp else 14.dp))
                Column(Modifier.weight(1f)) {
                    if (!short && !narrow) {
                        Text(if (isGroup) "群聊" else greeting, color = secondary,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 34.dp))
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(displayName, color = foreground,
                        fontSize = if (short || narrow) 17.sp else 23.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (short) Modifier.padding(end = 22.dp) else Modifier)
                    Spacer(Modifier.height(3.dp))
                    Text(recentMessage.trim(), color = secondary, fontSize = if (short) 12.sp else 14.sp,
                        fontStyle = FontStyle.Italic, lineHeight = if (short) 16.sp else 19.sp,
                        maxLines = if (short || showActionLabel) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (showActionLabel) {
                        Spacer(Modifier.height(7.dp))
                        Surface(shape = CircleShape, color = Color.Transparent,
                            border = BorderStroke(1.dp, foreground.copy(alpha = foreground.alpha * .14f))) {
                            Text(if (isGroup) "进入群聊" else "点我聊天",
                                Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                color = secondary, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
                if (!narrow && !short) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.ChevronRight, "进入聊天", tint = secondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun DesktopLauncherGrid(
    grid: DesktopGridGeometry,
    slots: List<String?>,
    modifier: Modifier,
    draggedLauncherId: String?,
    onOpen: (MigrationRoute) -> Unit,
) {
    val cellEdge = with(LocalDensity.current) { grid.cellPx.toDp() }
    Box(modifier) {
        slots.forEachIndexed { index, launcherId ->
            val launcher = DesktopV2Launchers.firstOrNull { it.id == launcherId } ?: return@forEachIndexed
            val column = index % DESKTOP_COLUMNS
            val row = index / DESKTOP_COLUMNS
            DesktopV2LauncherItem(
                launcher = launcher,
                modifier = Modifier.size(cellEdge)
                    .absoluteOffset { IntOffset(grid.x(column), grid.y(row)) }
                    .graphicsLayer { alpha = if (launcherId == draggedLauncherId) 0f else 1f },
                onClick = { onOpen(launcher.route) },
            )
        }
    }
}

@Composable
private fun DesktopV2LauncherItem(
    launcher: DesktopV2Launcher,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tile = luluCssStyle("launcher", "app-" + launcher.id)
    val label = luluCssStyle("launcher-label")
    val cover = LuluCssSheet.assetToken(tile["background-image"])
    if (LocalLuluAppearance.current.isJournal() && tile.isEmpty() && label.isEmpty()) {
        JournalPatchIcon(launcher.title, launcher.icon, modifier, onClick)
        return
    }
    BoxWithConstraints(modifier.luluCssDecor(tile, includeArtwork = false)) {
        // Reserve space for the label first: icon labels must not be clipped by
        // a fixed 61dp icon box on short desktop pages.
        val smallCell = maxHeight < 64.dp
        val twoLines = !smallCell && launcher.title.length > 4 && launcher.title != "Live2D"
        val labelSpace = if (smallCell) 12.dp else if (twoLines) 28.dp else 18.dp
        val space = if (smallCell) 1.dp else 3.dp
        val iconHeight = minOf(tile.cssDp("height") ?: 61.dp, (maxHeight - labelSpace - space).coerceAtLeast(12.dp))
        val iconWidth = minOf(tile.cssDp("width") ?: 57.dp, (maxWidth - 6.dp).coerceAtLeast(24.dp))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            val borderWidth = tile.cssDp("border-width") ?: 1.dp
            Surface(
                shape = RoundedCornerShape(tile.cssDp("border-radius")
                    ?: LocalLuluAppearance.current.corner.dp),
                color = tile.cssColor("background-color") ?: DesktopIconSurface,
                border = if (borderWidth > 0.dp) BorderStroke(borderWidth,
                    tile.cssColor("border-color") ?: DesktopLine) else null,
                shadowElevation = 1.dp,
                modifier = Modifier.width(iconWidth).height(iconHeight)
                    .semantics { contentDescription = launcher.title },
                onClick = onClick,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LuluCssArtwork(cover, Modifier.matchParentSize(), tile, maxEdge = 256) {
                        Icon(launcher.icon, null, tint = tile.cssColor("color") ?: DesktopIcon,
                            modifier = Modifier.size(minOf(30.dp, (iconHeight - 8.dp).coerceAtLeast(0.dp))))
                    }
                }
            }
            Spacer(Modifier.height(space))
            Text(
                launcher.title, modifier = Modifier.luluCssDecor(label),
                color = label.cssColor("color") ?: DesktopInk,
                fontSize = label.cssFontSize() ?: if (smallCell) 8.sp else 11.sp,
                lineHeight = label.cssFontSize()?.let { (it.value + 2).sp }
                    ?: if (smallCell) 11.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (twoLines) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun loadDesktopSlots(context: Context): List<String?> {
    val launcherIds = DesktopV2Launchers.map { it.id }
    val slots = LuluDesktopWidgetStore.loadOrMigrateGrid(context, launcherIds)
    val complete = completeDesktopSlots(slots, LuluDesktopWidgetStore.load(context), launcherIds)
    if (complete != slots) saveDesktopSlots(context, complete)
    return complete
}

private fun saveDesktopSlots(context: Context, slots: List<String?>) {
    LuluDesktopWidgetStore.saveLauncherSlots(context, slots)
}
