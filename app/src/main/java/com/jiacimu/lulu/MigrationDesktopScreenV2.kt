package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
    DesktopV2Launcher("theater", "剧场", Icons.Outlined.AutoStories, MigrationRoute.Theater),
    DesktopV2Launcher("study", "考研", Icons.Outlined.School, MigrationRoute.Study),
    DesktopV2Launcher("meeting", "见面", Icons.Outlined.Cloud, MigrationRoute.Meeting),
    DesktopV2Launcher("games", "游戏", Icons.Outlined.SportsEsports, MigrationRoute.Games),
    DesktopV2Launcher("apocalypse", "末世求生", Icons.Outlined.Map, MigrationRoute.ApocalypseSurvival),
    DesktopV2Launcher("settings", "设置", Icons.Outlined.Settings, MigrationRoute.Settings),
    DesktopV2Launcher("appearance", "美化", Icons.Outlined.Palette, MigrationRoute.Appearance),
    DesktopV2Launcher("playground", "动作试验场", Icons.Outlined.Face, MigrationRoute.Playground),
)

private const val DESKTOP_COLUMNS = 4
private const val DESKTOP_ROWS = 4
private const val DESKTOP_SLOT_COUNT = DESKTOP_COLUMNS * DESKTOP_ROWS

/** One shared layout for local and cross-page drag; moving to an empty slot leaves a hole. */
internal fun desktopMoveLauncher(slots: List<String?>, from: Int, to: Int): List<String?> {
    if (from !in slots.indices || to !in slots.indices || from == to || slots[from] == null) return slots
    val updated = slots.toMutableList()
    updated[from] = slots[to]
    updated[to] = slots[from]
    val lastOccupied = updated.indexOfLast { it != null } + 1
    val wanted = desktopAllocatedPageCount(maxOf(DESKTOP_SLOT_COUNT, lastOccupied)) * DESKTOP_SLOT_COUNT
    return if (wanted <= updated.size) updated.take(wanted)
        else updated + List<String?>(wanted - updated.size) { null }
}


// 至少保留一张空白桌面页；如果最后一张已经放了应用，就预留下一张。
internal fun desktopAllocatedPageCount(neededSlots: Int): Int =
    maxOf(2, (neededSlots + DESKTOP_SLOT_COUNT - 1) / DESKTOP_SLOT_COUNT + 1)

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
        ?: characters["lulu"]
        ?: MigratedDomainStores.characters.get("lulu")
    val recentGroup = shortcutConversation?.groupChat
    val recentDisplayName = recentGroup?.name?.ifBlank { "群聊" } ?: currentCharacter.displayName
    val recentAvatarUri = recentGroup?.avatarUri ?: currentCharacter.avatarUri
    val recentFallback = if (recentGroup != null) {
        recentDisplayName.take(1).ifBlank { "群" }
    } else {
        currentCharacter.displayName.take(1).ifBlank { "露" }
    }
    val today = remember { LocalDate.now() }
    val dateText = remember(today) {
        today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))
    }
    val greeting = when (LocalTime.now().hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
    var slots by remember { mutableStateOf(loadDesktopSlots(context)) }
    val desktopPrefs = remember { context.getSharedPreferences("lulu_desktop_moment", Context.MODE_PRIVATE) }
    var momentPhotoUri by remember { mutableStateOf(desktopPrefs.getString("photo_uri", null)) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            momentPhotoUri = uri.toString()
            desktopPrefs.edit().putString("photo_uri", momentPhotoUri).apply()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = DesktopPaper) {
        Box(Modifier.fillMaxSize()) {
            if (!journal) LuluAppearancePattern(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (journal) Modifier.journalLinen(appearance) else Modifier)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$greeting，主人",
                        color = DesktopInk,
                        fontSize = if (journal) 28.sp else 31.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(dateText, color = DesktopMuted, fontSize = 11.sp, letterSpacing = 0.8.sp)
                    if (journal) Text("✦ A LITTLE PAGE OF TODAY", color = DesktopAccent,
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(5.dp), CircleShape, DesktopAccent) {}
                        Spacer(Modifier.width(7.dp))
                        Text(if (journal) "HAND MADE" else "DAY MODE",
                            color = DesktopMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                        if (journal) JournalPressedFlower(Modifier.size(30.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            DesktopCompanionCard(
                displayName = recentDisplayName,
                avatarFallback = recentFallback,
                avatarUri = recentAvatarUri,
                isGroup = recentGroup != null,
                recentMessage = shortcutConversation?.lastMessage
                    ?.ifBlank {
                        if (recentGroup != null) "点这里回到群聊。" else "点这里去找${currentCharacter.displayName}。"
                    }
                    ?: "今天也来找我说说话吧。",
                onClick = { shortcutConversation?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat) },
            )

            Spacer(Modifier.height(10.dp))
            if (journal) {
                Box {
                    DesktopWorldBulletinCard()
                    JournalWashiTape(Modifier.align(Alignment.TopCenter).offset(y = (-5).dp)
                        .width(62.dp).height(14.dp))
                }
            } else {
                DesktopWorldBulletinCard()
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(Modifier.size(5.dp), CircleShape, DesktopAccent) {}
                Spacer(Modifier.width(9.dp))
                Surface(Modifier.weight(1f).height(1.dp), color = DesktopLine) {}
                Spacer(Modifier.width(9.dp))
                Text(
                    if (journal) "✿  我的拼贴收藏  /  APPS" else "APPS",
                    color = DesktopMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
            }

            Spacer(Modifier.height(9.dp))
            // 手势属于整张桌面，不属于某一页上的图标：翻页时仍能拖着同一图标。
            val pageCount = slots.size / DESKTOP_SLOT_COUNT
            val pagerState = rememberPagerState(pageCount = { pageCount })
            val scope = rememberCoroutineScope()
            var draggingSource by remember { mutableStateOf<Int?>(null) }
            var dragPosition by remember { mutableStateOf(Offset.Zero) }
            var dragGrabOffset by remember { mutableStateOf(Offset.Zero) }
            var edgePageHandled by remember { mutableIntStateOf(-1) }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val density = LocalDensity.current
                val cellWidth = maxWidth / DESKTOP_COLUMNS
                val cellHeight = maxHeight / DESKTOP_ROWS
                val cellWidthPx = with(density) { cellWidth.toPx() }
                val cellHeightPx = with(density) { cellHeight.toPx() }
                val edgePx = with(density) { 32.dp.toPx() }
                val iconHeightPx = with(density) { 86.dp.toPx() }
                Box(
                    Modifier.fillMaxSize().pointerInput(slots, cellWidthPx, cellHeightPx) {
                        if (cellWidthPx <= 0f || cellHeightPx <= 0f) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start ->
                                val col = (start.x / cellWidthPx).toInt().coerceIn(0, DESKTOP_COLUMNS - 1)
                                val row = (start.y / cellHeightPx).toInt().coerceIn(0, DESKTOP_ROWS - 1)
                                val source = pagerState.currentPage * DESKTOP_SLOT_COUNT + row * DESKTOP_COLUMNS + col
                                // Long-pressing empty page space should not start moving an icon.
                                if (slots.getOrNull(source) != null &&
                                    start.y - row * cellHeightPx <= iconHeightPx) {
                                    draggingSource = source
                                    dragPosition = start
                                    dragGrabOffset = Offset(start.x - col * cellWidthPx, start.y - row * cellHeightPx)
                                    edgePageHandled = -1
                                }
                            },
                            onDrag = { change, _ ->
                                if (draggingSource != null) {
                                    change.consume()
                                    dragPosition = change.position
                                    val currentPage = pagerState.currentPage
                                    val edgeTarget = when {
                                        dragPosition.x > size.width - edgePx -> currentPage + 1
                                        dragPosition.x < edgePx -> currentPage - 1
                                        else -> -1
                                    }
                                    if (edgeTarget == -1) {
                                        edgePageHandled = -1
                                    } else if (edgeTarget in 0 until pageCount && edgePageHandled == -1) {
                                        edgePageHandled = currentPage
                                        scope.launch { pagerState.scrollToPage(edgeTarget) }
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingSource?.let { from ->
                                    val col = (dragPosition.x / cellWidthPx).toInt().coerceIn(0, DESKTOP_COLUMNS - 1)
                                    val row = (dragPosition.y / cellHeightPx).toInt().coerceIn(0, DESKTOP_ROWS - 1)
                                    val to = pagerState.currentPage * DESKTOP_SLOT_COUNT + row * DESKTOP_COLUMNS + col
                                    val updated = desktopMoveLauncher(slots, from, to)
                                    if (updated != slots) {
                                        slots = updated
                                        saveDesktopSlots(context, updated)
                                    }
                                }
                                draggingSource = null
                                edgePageHandled = -1
                            },
                            onDragCancel = {
                                draggingSource = null
                                edgePageHandled = -1
                            },
                        )
                    },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        userScrollEnabled = draggingSource == null,
                    ) { page ->
                        DesktopLauncherGrid(
                            slots = slots.drop(page * DESKTOP_SLOT_COUNT).take(DESKTOP_SLOT_COUNT),
                            modifier = Modifier.fillMaxSize(),
                            draggedLauncherId = draggingSource?.let(slots::get),
                            onOpen = onOpen,
                        )
                    }
                    val active = draggingSource?.let { source ->
                        slots.getOrNull(source)?.let { id -> DesktopV2Launchers.firstOrNull { it.id == id } }
                    }
                    if (active != null) {
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
                }
            }
            if (pageCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(pageCount) { index ->
                        Surface(
                            modifier = Modifier.padding(horizontal = 3.dp)
                                .size(if (index == pagerState.currentPage) 7.dp else 5.dp),
                            shape = CircleShape,
                            color = if (index == pagerState.currentPage) DesktopAccent else DesktopLine,
                        ) {}
                    }
                }
            }
        }
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
) {
    val journal = LocalLuluAppearance.current.isJournal()
    val appearance = LocalLuluAppearance.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DesktopCard,
        shape = RoundedCornerShape(com.jiacimu.lulu.design.LocalLuluAppearance.current.corner.dp),
        border = BorderStroke(1.dp, DesktopLine),
        shadowElevation = 2.dp,
        onClick = onClick,
    ) {
        Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (journal) Modifier.journalPaper(appearance) else Modifier)
                .padding(horizontal = 17.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopV2Avatar(avatarFallback, 72, avatarUri)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayName, color = DesktopInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isGroup) "GROUP" else "ONLINE",
                        color = DesktopMuted,
                        fontSize = 8.sp,
                        letterSpacing = 1.1.sp,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "“${recentMessage.trim()}”",
                    color = DesktopMuted,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DesktopTinyTag(if (isGroup) "群聊" else "陪伴中")
                    DesktopTinyTag(if (isGroup) "进入群聊" else "点我聊天")
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                if (isGroup) "进入群聊" else "进入聊天",
                tint = DesktopMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (journal) {
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
) {
    BoxWithConstraints(modifier) {
        val cellWidth = maxWidth / DESKTOP_COLUMNS
        val cellHeight = maxHeight / DESKTOP_ROWS
        slots.forEachIndexed { index, launcherId ->
            val launcher = DesktopV2Launchers.firstOrNull { it.id == launcherId } ?: return@forEachIndexed
            val column = index % DESKTOP_COLUMNS
            val row = index / DESKTOP_COLUMNS
            DesktopV2LauncherItem(
                launcher = launcher,
                modifier = Modifier.width(cellWidth).height(cellHeight)
                    .offset { IntOffset((cellWidth * column).roundToPx(), (cellHeight * row).roundToPx()) }
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
        JournalPatchIcon(launcher.title, launcher.icon, modifier, onClick)
        return
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Surface(
            shape = RoundedCornerShape(com.jiacimu.lulu.design.LocalLuluAppearance.current.corner.dp),
            color = DesktopIconSurface,
            border = BorderStroke(1.dp, DesktopLine),
            shadowElevation = 1.dp,
            modifier = Modifier.width(57.dp).height(61.dp),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(launcher.icon, launcher.title, tint = DesktopIcon, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            launcher.title,
            color = DesktopInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopV2Avatar(text: String, size: Int, imageUri: String? = null, modifier: Modifier = Modifier) =
    LuluProfileAvatar(imageUri = imageUri, fallback = text, size = size, modifier = modifier)

private fun loadDesktopSlots(context: Context): List<String?> {
    val known = DesktopV2Launchers.mapTo(mutableSetOf()) { it.id }
    val raw = context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        .getString("slots_v1", null)
    val saved = raw?.split('|')
        ?.map { value -> value.takeIf(String::isNotBlank) }
        ?.map { id -> id?.takeIf { it in known } }
        ?.toMutableList()
        ?: mutableListOf()
    // 旧版只有一页 16 格；新版按页扩：已有格子保持原位，新应用往后排进空位，
    // 格子不够就加一页，绝不丢应用。位置以最远已有格子为准，避免后页应用被裁掉。
    val existing = saved.filterNotNull().toMutableSet()
    val missing = DesktopV2Launchers.filterNot { it.id in existing }
    val lastOccupied = saved.indexOfLast { it != null }
    val totalNeeded = maxOf(existing.size + missing.size, DESKTOP_SLOT_COUNT, lastOccupied + 1)
    val pageCount = desktopAllocatedPageCount(totalNeeded)
    val sized = MutableList<String?>(pageCount * DESKTOP_SLOT_COUNT) { null }
    saved.take(sized.size).forEachIndexed { index, id -> sized[index] = id }
    missing.forEach { launcher ->
        val empty = sized.indexOfFirst { it == null }
        if (empty >= 0) sized[empty] = launcher.id
    }
    // 不丢弃最后一张空白桌面；保存的历史位置仍保持不变。
    return sized
}

private fun saveDesktopSlots(context: Context, slots: List<String?>) {
    context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        .edit()
        .putString("slots_v1", slots.joinToString("|") { it.orEmpty() })
        .apply()
}
