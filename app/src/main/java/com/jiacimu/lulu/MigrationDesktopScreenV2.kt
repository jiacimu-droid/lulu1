package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import com.jiacimu.lulu.design.luluCssElement
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.JournalPatchIcon
import com.jiacimu.lulu.design.JournalPressedFlower
import com.jiacimu.lulu.design.JournalWashiTape
import com.jiacimu.lulu.design.isJournal
import com.jiacimu.lulu.design.journalLinen
import com.jiacimu.lulu.design.journalPaper
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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

private const val DESKTOP_COLUMNS = 4
private const val DESKTOP_ROWS = 8
private const val DESKTOP_SLOT_COUNT = DESKTOP_COLUMNS * DESKTOP_ROWS

/** Every desktop page has the same four columns and eight rows. */
internal fun desktopPageStart(page: Int): Int = page.coerceAtLeast(0) * DESKTOP_SLOT_COUNT
internal fun desktopPageForSlot(slot: Int): Int = slot.coerceAtLeast(0) / DESKTOP_SLOT_COUNT
internal fun desktopPageRows(page: Int): Int = DESKTOP_ROWS
internal fun desktopSlotIndex(page: Int, row: Int, col: Int): Int =
    desktopPageStart(page) + row * DESKTOP_COLUMNS + col
internal fun desktopStoredPageCount(slotCount: Int): Int =
    maxOf(1, (slotCount + DESKTOP_SLOT_COUNT - 1) / DESKTOP_SLOT_COUNT)

/** Legacy 4x4 pages; home icons now start on row five. */
internal fun desktopMigrateLegacySlots(legacy: List<String?>): List<String?> {
    val pages = maxOf(2, (legacy.size + 15) / 16)
    val result = MutableList<String?>(desktopPageStart(pages)) { null }
    legacy.forEachIndexed { i, id ->
        if (id != null) {
            val page = i / 16
            val row = (i % 16) / DESKTOP_COLUMNS + if (page == 0) 4 else 0
            result[desktopSlotIndex(page, row, i % DESKTOP_COLUMNS)] = id
        }
    }
    return result
}

/** Recent layout with 4x4 dashboard and 4x8 secondary pages. */
internal fun desktopMigrateMixedSlots(mixed: List<String?>): List<String?> {
    val pages = maxOf(2, if (mixed.size <= 16) 1 else 1 + (mixed.size - 16 + 31) / 32)
    val result = MutableList<String?>(desktopPageStart(pages)) { null }
    mixed.forEachIndexed { i, id ->
        if (id != null) {
            val page = if (i < 16) 0 else 1 + (i - 16) / 32
            val local = if (i < 16) i else (i - 16) % 32
            val row = local / DESKTOP_COLUMNS + if (page == 0) 4 else 0
            result[desktopSlotIndex(page, row, local % DESKTOP_COLUMNS)] = id
        }
    }
    return result
}

/** One shared layout for local and cross-page drag; moving to an empty slot leaves a hole. */
internal fun desktopMoveLauncher(slots: List<String?>, from: Int, to: Int): List<String?> {
    if (from !in slots.indices || to !in slots.indices || from == to || slots[from] == null) return slots
    val updated = slots.toMutableList()
    updated[from] = slots[to]
    updated[to] = slots[from]
    val lastOccupied = updated.indexOfLast { it != null } + 1
    val wanted = desktopPageStart(desktopAllocatedPageCount(maxOf(DESKTOP_SLOT_COUNT, lastOccupied)))
    return if (wanted <= updated.size) updated.take(wanted)
        else updated + List<String?>(wanted - updated.size) { null }
}


// 至少保留一张空白桌面页；如果最后一张已经放了应用，就预留下一张。
internal fun desktopAllocatedPageCount(neededSlots: Int): Int =
    maxOf(2, desktopPageForSlot(maxOf(neededSlots - 1, 0)) + 2)

// Resolve colors inside each composable, from the chosen desktop recipe.
private val DesktopPaper: Color @Composable get() = MaterialTheme.colorScheme.background
private val DesktopSoft: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val DesktopIconSurface: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val DesktopCard: Color @Composable get() = MaterialTheme.colorScheme.surface
private val DesktopLine: Color @Composable get() = MaterialTheme.colorScheme.outline
private val DesktopAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val DesktopInk: Color @Composable get() = MaterialTheme.colorScheme.onBackground
private val DesktopMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val DesktopIcon: Color @Composable get() = MaterialTheme.colorScheme.onSurface

@Composable
internal fun MigrationHomeV2(
    onOpen: (MigrationRoute) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val appearance = LocalLuluAppearance.current
    val journal = appearance.isJournal()
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
    val dateText = remember(today) {
        today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))
    }
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
    val desktopPrefs = remember { context.getSharedPreferences("lulu_desktop_moment", Context.MODE_PRIVATE) }
    var momentPhotoUri by remember { mutableStateOf(desktopPrefs.getString("photo_uri", null)) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            momentPhotoUri = uri.toString()
            desktopPrefs.edit().putString("photo_uri", momentPhotoUri).apply()
        }
    }

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
    var viewportTopInWindow by remember { mutableFloatStateOf(0f) }
    var homeGridTopInWindow by remember { mutableFloatStateOf(0f) }
    var homeGridHeightPx by remember { mutableFloatStateOf(0f) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            if (!journal) LuluAppearancePattern(Modifier.fillMaxSize())
            Column(
                modifier = Modifier.fillMaxSize()
                    .then(if (journal) Modifier.journalLinen(appearance) else Modifier)
                    .statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                // The pager owns the ENTIRE desktop content, not only the last 4x4
                // icon grid. Page 0 is the familiar companion dashboard; subsequent
                // pages have the full 4x8 canvas, with usable lower cells.
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(1f)
                        .onGloballyPositioned {
                            viewportTopInWindow = it.positionInWindow().y
                        }
                        .pointerInput(slots, widgets, homeGridTopInWindow, homeGridHeightPx, viewportTopInWindow) {
                            val cellWidth = size.width.toFloat() / DESKTOP_COLUMNS
                            if (cellWidth <= 0f) return@pointerInput
                            val edgePx = 32.dp.toPx()
                            val iconHeightPx = 86.dp.toPx()

                            fun gridTop(page: Int): Float = 0f
                            fun gridHeight(page: Int): Float =
                                DESKTOP_ROWS.toFloat() * luluDesktopFullCellHeight(
                                    size.width.toFloat(), size.height.toFloat(), DESKTOP_ROWS,
                                )
                            fun slotAt(position: Offset, page: Int): Int? {
                                val top = gridTop(page)
                                val height = gridHeight(page)
                                if (height <= 0f || position.y < top || position.y >= top + height) return null
                                val rows = desktopPageRows(page)
                                val cellHeight = height / rows
                                val row = ((position.y - top) / cellHeight).toInt().coerceIn(0, rows - 1)
                                val col = (position.x / cellWidth).toInt().coerceIn(0, DESKTOP_COLUMNS - 1)
                                return desktopSlotIndex(page, row, col)
                            }

                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    val page = pagerState.currentPage
                                    val source = slotAt(start, page)
                                    if (source != null && slots.getOrNull(source) != null) {
                                        val local = source - desktopPageStart(page)
                                        val row = local / DESKTOP_COLUMNS
                                        val col = local % DESKTOP_COLUMNS
                                        val height = gridHeight(page) / desktopPageRows(page)
                                        if (start.y - gridTop(page) - row * height <= iconHeightPx) {
                                            draggingSource = source
                                            dragPosition = start
                                            dragGrabOffset = Offset(
                                                start.x - col * cellWidth,
                                                start.y - gridTop(page) - row * height,
                                            )
                                            edgePageHandled = -1
                                        }
                                    } else if (source != null) {
                                        val x = (start.x / cellWidth).toInt().coerceIn(0, 3)
                                        val y = ((start.y - gridTop(page)) /
                                            (gridHeight(page) / desktopPageRows(page))).toInt().coerceIn(0, desktopPageRows(page) - 1)
                                        widgets.firstOrNull { w ->
                                            w.page == page && x in w.col until w.col + w.columns &&
                                                y in w.row until w.row + w.rows
                                        }?.let { w ->
                                            draggingWidgetId = w.id
                                            dragPosition = start
                                            dragGrabOffset = Offset(
                                                start.x - w.col * cellWidth,
                                                start.y - gridTop(page) -
                                                    w.row * gridHeight(page) / desktopPageRows(page),
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
                                    draggingSource?.let { from ->
                                        val target = slotAt(dragPosition, pagerState.currentPage)
                                        if (target != null && widgets.none { w ->
                                            val destPage = desktopPageForSlot(target)
                                            val local = target - desktopPageStart(destPage)
                                            val x = local % DESKTOP_COLUMNS
                                            val y = local / DESKTOP_COLUMNS
                                            w.page == destPage &&
                                                x in w.col until w.col + w.columns &&
                                                y in w.row until w.row + w.rows
                                        }) {
                                            val updated = desktopMoveLauncher(slots, from, target)
                                            if (updated != slots) {
                                                slots = updated
                                                saveDesktopSlots(context, updated)
                                            }
                                        }
                                    }
                                    draggingWidgetId?.let { id ->
                                        val target = slotAt(dragPosition, pagerState.currentPage)
                                        val original = widgets.firstOrNull { it.id == id }
                                        if (target != null && original != null) {
                                            val moved = original.copy(
                                                page = desktopPageForSlot(target),
                                                col = (target - desktopPageStart(desktopPageForSlot(target))) % DESKTOP_COLUMNS,
                                                row = (target - desktopPageStart(desktopPageForSlot(target))) / DESKTOP_COLUMNS,
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
                    // Capture constraints before nested layout scopes hide this receiver.
                    val desktopViewportWidth = maxWidth
                    val desktopViewportHeight = maxHeight
                    val cellWidth = desktopViewportWidth / DESKTOP_COLUMNS
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
                                page = page, widgets = widgets,
                                launcherSlots = slots, today = today,
                                characterNames = widgetCharacterNames,
                                conversationTexts = widgetConversationTexts,
                                roleReadings = dailyReadings, roleNotes = roleNotes,
                                plannerTasks = widgetTodayTasks,
                                onCompleteTask = onWidgetTodoCheck,
                                modifier = Modifier.fillMaxSize(),
                                onOpenWidget = { widget ->
                                    when (widget.kind) {
                                        LuluWidgetKind.CHAT, LuluWidgetKind.BULLETIN,
                                        LuluWidgetKind.HOROSCOPE -> Unit
                                        LuluWidgetKind.PLANNER -> showingWidgetPlanner = true
                                        LuluWidgetKind.NOTE, LuluWidgetKind.COUNTDOWN ->
                                            editingWidgetId = widget.id
                                    }
                                },
                                specialContent = { widget, foreground ->
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
                                            embedded = true, foreground = foreground,
                                        )
                                        LuluWidgetKind.BULLETIN -> DesktopWorldBulletinCard(
                                            modifier = Modifier.fillMaxSize(), embedded = true,
                                            foreground = foreground,
                                        )
                                        else -> Unit
                                    }
                                },
                                onEditWidget = { editingWidgetId = it.id },
                                onBlankLongPress = { showWidgetPicker = true },
                            )
                            DesktopLauncherGrid(
                                slots = slots.drop(desktopPageStart(page)).take(DESKTOP_SLOT_COUNT),
                                modifier = Modifier.fillMaxSize(),
                                draggedLauncherId = draggingSource?.let(slots::get),
                                onOpen = onOpen,
                                fullPageGrid = true,
                            )
                        }
                    }
                    draggingSource?.let { source ->
                        slots.getOrNull(source)?.let { id ->
                            DesktopV2Launchers.firstOrNull { it.id == id }
                        }
                    }?.let { active ->
                        val activePage = pagerState.currentPage
                        val cellHeight = minOf(
                            desktopViewportHeight / DESKTOP_ROWS,
                            cellWidth * LULU_DESKTOP_CELL_ASPECT,
                        )
                        DesktopV2LauncherItem(
                            launcher = active,
                            modifier = Modifier.width(cellWidth).height(cellHeight)
                                .offset { IntOffset(
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
                            val cellWidth = desktopViewportWidth / DESKTOP_COLUMNS
                            val cellHeight = minOf(
                                desktopViewportHeight / DESKTOP_ROWS,
                                cellWidth * LULU_DESKTOP_CELL_ASPECT,
                            )
                            LuluDesktopWidgetCard(
                                widget = w, today = today,
                                characterName = widgetCharacterNames[w.characterId],
                                lastConversationText = widgetConversationTexts[w.characterId],
                                roleReading = dailyReadings[w.id],
                                roleNote = roleNotes[w.id],
                                modifier = Modifier.width(cellWidth * w.columns).height(cellHeight * w.rows)
                                    .offset {
                                        IntOffset(
                                            (dragPosition.x - dragGrabOffset.x).roundToInt(),
                                            (dragPosition.y - dragGrabOffset.y).roundToInt(),
                                        )
                                    }.graphicsLayer { alpha = .93f; shadowElevation = 8f },
                                onOpen = {},
                            )
                        }
                    }
                }
                // PagerState.currentPage changes during a swipe. The indicator must
                // own that observation, not invalidate the entire desktop dashboard.
                if (pageCount > 1) DesktopV2PageIndicator(pagerState, pageCount)
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
    greeting: String = "",
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    foreground: Color? = null,
) {
    val journal = LocalLuluAppearance.current.isJournal()
    val ink = foreground ?: DesktopInk
    val secondary = foreground?.copy(alpha = .78f) ?: DesktopMuted
    Surface(
        modifier = modifier.fillMaxSize().luluCssElement("companion"),
        color = if (embedded) Color.Transparent else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(LocalLuluAppearance.current.corner.dp),
        border = if (embedded) null else BorderStroke(1.dp, DesktopLine),
        shadowElevation = if (embedded) 0.dp else 2.dp,
        onClick = onClick,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 250.dp || maxHeight < 116.dp
            Row(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = if (compact) 9.dp else 15.dp,
                        vertical = if (compact) 7.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopV2Avatar(avatarFallback, if (compact) 42 else 62, avatarUri)
                Spacer(Modifier.width(if (compact) 8.dp else 13.dp))
                Column(Modifier.weight(1f)) {
                    if (!compact && greeting.isNotBlank()) {
                        Text(greeting, color = secondary, fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName, color = ink,
                            fontSize = if (compact) 14.sp else 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (!compact) {
                            Spacer(Modifier.width(7.dp))
                            Text(if (isGroup) "GROUP" else "ONLINE",
                                color = secondary, fontSize = 8.sp)
                        }
                    }
                    Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
                    Text("“${recentMessage.trim()}”", color = secondary,
                        fontSize = if (compact) 10.sp else 12.sp,
                        fontStyle = FontStyle.Italic, maxLines = if (compact) 2 else 1,
                        overflow = TextOverflow.Ellipsis)
                    if (!compact) {
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DesktopTinyTag(if (isGroup) "群聊" else "陪伴中")
                            DesktopTinyTag(if (isGroup) "进入群聊" else "点我聊天")
                        }
                    }
                }
                if (!compact) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.ChevronRight, "进入聊天",
                        tint = secondary, modifier = Modifier.size(18.dp))
                }
            }
            if (journal && !embedded) {
                JournalWashiTape(Modifier.align(Alignment.TopEnd).padding(end = 18.dp)
                    .offset(y = (-2).dp).width(58.dp).height(13.dp))
            }
        }
    }
}

@Composable
private fun DesktopMomentCard(
    characterName: String,
    dateLabel: String,
    photoUri: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(108.dp),
        color = DesktopCard,
        shape = RoundedCornerShape(com.jiacimu.lulu.design.LocalLuluAppearance.current.corner.dp),
        border = BorderStroke(1.dp, DesktopLine),
        shadowElevation = 1.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("TODAY MOMENT", color = DesktopMuted, fontSize = 8.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(7.dp))
                Text("给今天留一个位置", color = DesktopInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$characterName 想和你一起保存一点心情。",
                    color = DesktopMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Text(dateLabel, color = DesktopInk, fontSize = 10.sp, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.width(12.dp))
            DesktopPhotoPlaceholder(photoUri)
        }
    }
}

@Composable
private fun DesktopPhotoPlaceholder(photoUri: String?) {
    LuluSelectedPhoto(imageUri = photoUri, modifier = Modifier.width(96.dp).fillMaxHeight())
}

@Composable
private fun DesktopTinyTag(text: String) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, DesktopLine),
    ) {
        Text(text, color = DesktopMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun DesktopLauncherGrid(
    slots: List<String?>,
    modifier: Modifier,
    draggedLauncherId: String?,
    onOpen: (MigrationRoute) -> Unit,
    fullPageGrid: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val cellWidth = maxWidth / DESKTOP_COLUMNS
        val cellHeight = if (fullPageGrid)
            minOf(maxHeight / DESKTOP_ROWS, cellWidth * LULU_DESKTOP_CELL_ASPECT)
            else maxHeight / DESKTOP_ROWS
        slots.forEachIndexed { index, launcherId ->
            val launcher = DesktopV2Launchers.firstOrNull { it.id == launcherId } ?: return@forEachIndexed
            val column = index % DESKTOP_COLUMNS
            val row = index / DESKTOP_COLUMNS
            DesktopV2LauncherItem(
                launcher = launcher,
                modifier = Modifier.width(cellWidth).height(cellHeight)
                    .offset { IntOffset((cellWidth * column).roundToPx(), (cellHeight * row).roundToPx()) }
                    .padding(top = if (fullPageGrid) 2.dp else 0.dp)
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
    if (LocalLuluAppearance.current.isJournal()) {
        JournalPatchIcon(launcher.title, launcher.icon, modifier.luluCssElement("launcher"), onClick)
        return
    }
    BoxWithConstraints(modifier.luluCssElement("launcher")) {
        // Reserve space for the label first: icon labels must not be clipped by
        // a fixed 61dp icon box on compact or eight-row desktop pages.
        val smallCell = maxHeight < 64.dp
        val twoLines = !smallCell && launcher.title.length > 4 && launcher.title != "Live2D"
        val labelSpace = if (smallCell) 12.dp else if (twoLines) 28.dp else 18.dp
        val space = if (smallCell) 1.dp else 3.dp
        val iconHeight = minOf(61.dp, (maxHeight - labelSpace - space).coerceAtLeast(12.dp))
        val iconWidth = minOf(57.dp, (maxWidth - 6.dp).coerceAtLeast(24.dp))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top) {
            Surface(
                shape = RoundedCornerShape(com.jiacimu.lulu.design.LocalLuluAppearance.current.corner.dp),
                color = DesktopIconSurface,
                border = BorderStroke(1.dp, DesktopLine),
                shadowElevation = 1.dp,
                modifier = Modifier.width(iconWidth).height(iconHeight),
                onClick = onClick,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(launcher.icon, launcher.title, tint = DesktopIcon,
                        modifier = Modifier.size(minOf(30.dp, iconHeight - 8.dp)))
                }
            }
            Spacer(Modifier.height(space))
            Text(
                launcher.title, color = DesktopInk,
                fontSize = if (smallCell) 8.sp else 11.sp,
                lineHeight = if (smallCell) 11.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (twoLines) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DesktopV2Avatar(text: String, size: Int, imageUri: String? = null, modifier: Modifier = Modifier) =
    LuluProfileAvatar(imageUri = imageUri, fallback = text, size = size, modifier = modifier)

private fun loadDesktopSlots(context: Context): List<String?> {
    LuluDesktopWidgetStore.ensureHomeCards(context)
    val known = DesktopV2Launchers.mapTo(mutableSetOf()) { it.id }
    val prefs = context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
    val version = when {
        prefs.contains("slots_v3") -> 3
        prefs.contains("slots_v2") -> 2
        else -> 1
    }
    val raw = prefs.getString("slots_v" + version, null)
    val saved = raw?.split('|')?.map { it.takeIf(String::isNotBlank) }
        ?.map { it?.takeIf { id -> id in known } }.orEmpty()
    val positioned = when (version) {
        3 -> saved
        2 -> desktopMigrateMixedSlots(saved)
        else -> desktopMigrateLegacySlots(saved)
    }
    val widgetCells = LuluDesktopWidgetStore.load(context).flatMap { widget ->
        (widget.row until widget.row + widget.rows).flatMap { row ->
            (widget.col until widget.col + widget.columns).map { col ->
                desktopSlotIndex(widget.page, row, col)
            }
        }
    }.toSet()
    val lastOccupied = maxOf(positioned.indexOfLast { it != null }, widgetCells.maxOrNull() ?: -1)
    val required = maxOf(DESKTOP_SLOT_COUNT, lastOccupied + 1, known.size + widgetCells.size)
    val sized = MutableList<String?>(desktopPageStart(desktopAllocatedPageCount(required))) { null }
    positioned.take(sized.size).forEachIndexed { index, id ->
        if (id != null && index !in widgetCells) sized[index] = id
    }
    val present = sized.filterNotNull().toSet()
    DesktopV2Launchers.filterNot { it.id in present }.forEach { launcher ->
        var empty = sized.indices.firstOrNull { sized[it] == null && it !in widgetCells }
        if (empty == null) {
            empty = sized.size
            repeat(DESKTOP_SLOT_COUNT) { sized.add(null) }
        }
        sized[empty] = launcher.id
    }
    if (version != 3) saveDesktopSlots(context, sized)
    return sized
}

private fun saveDesktopSlots(context: Context, slots: List<String?>) {
    context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        .edit()
        .putString("slots_v3", slots.joinToString("|") { it.orEmpty() })
        .apply()
}
