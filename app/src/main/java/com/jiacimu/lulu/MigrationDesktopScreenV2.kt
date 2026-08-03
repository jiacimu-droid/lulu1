package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class DesktopV2Launcher(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val route: MigrationRoute,
)

private val DesktopV2Launchers = listOf(
    DesktopV2Launcher("chat", "聊天", Icons.Outlined.ChatBubbleOutline, MigrationRoute.Chat),
    DesktopV2Launcher("memory", "记忆", Icons.Outlined.Psychology, MigrationRoute.Memory),
    DesktopV2Launcher("lexicon", "辞海", Icons.Outlined.MenuBook, MigrationRoute.Lexicon),
    DesktopV2Launcher("worldbook", "世界书", Icons.Outlined.Public, MigrationRoute.WorldBook),
    DesktopV2Launcher("performance", "性能监测", Icons.Outlined.MonitorHeart, MigrationRoute.Performance),
    DesktopV2Launcher("reading", "阅读", Icons.Outlined.AutoStories, MigrationRoute.Reading),
    DesktopV2Launcher("wishes", "心愿馆", Icons.Outlined.StarOutline, MigrationRoute.Wishes),
    DesktopV2Launcher("study", "考研", Icons.Outlined.School, MigrationRoute.Study),
    DesktopV2Launcher("games", "游戏", Icons.Outlined.SportsEsports, MigrationRoute.Games),
    DesktopV2Launcher("settings", "设置", Icons.Outlined.Settings, MigrationRoute.Settings),
)

private const val DESKTOP_COLUMNS = 4
private const val DESKTOP_ROWS = 5
private const val DESKTOP_SLOT_COUNT = DESKTOP_COLUMNS * DESKTOP_ROWS

private val DesktopPaper = Color(0xFFFFFFFF)
private val DesktopSoft = Color(0xFFF7F7F7)
private val DesktopIconSurface = Color(0xFFF4F4F4)
private val DesktopCard = Color(0xFFFCFCFC)
private val DesktopLine = Color(0xFFE7E7E7)
private val DesktopAccent = Color(0xFF292929)
private val DesktopInk = Color(0xFF1D1D1F)
private val DesktopMuted = Color(0xFF7A7A7E)
private val DesktopIcon = Color(0xFF303033)

@Composable
internal fun MigrationHomeV2(
    onOpen: (MigrationRoute) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val recent = conversations.maxByOrNull { it.updatedAt }
    val currentCharacter = recent?.characterId
        ?.let { characters[it] ?: MigratedDomainStores.characters.get(it) }
        ?: characters["lulu"]
        ?: MigratedDomainStores.characters.get("lulu")
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

    Surface(modifier = Modifier.fillMaxSize(), color = DesktopPaper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dateText, color = DesktopMuted, fontSize = 12.sp, letterSpacing = 0.4.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "$greeting，主人",
                        color = DesktopInk,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                DesktopV2Avatar(currentCharacter.displayName.take(1).ifBlank { "露" }, 48)
            }

            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DesktopCard,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, DesktopLine),
                shadowElevation = 1.dp,
                onClick = { recent?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.width(3.dp).height(42.dp),
                        color = DesktopAccent,
                        shape = RoundedCornerShape(99.dp),
                    ) {}
                    Spacer(Modifier.width(12.dp))
                    DesktopV2Avatar(currentCharacter.displayName.take(1).ifBlank { "露" }, 42)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "最近消息",
                            color = DesktopMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            recent?.lastMessage
                                ?.ifBlank { "点这里去找${currentCharacter.displayName}。" }
                                ?: "点这里进入聊天。",
                            color = DesktopInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, "进入聊天", tint = DesktopMuted, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DesktopInfoCard(
                    Modifier.weight(1f),
                    "TODAY",
                    today.format(DateTimeFormatter.ofPattern("MM / dd")),
                    "今天也慢慢来",
                    Icons.Outlined.CalendarToday,
                )
                DesktopInfoCard(
                    Modifier.weight(1f),
                    "COMPANION",
                    currentCharacter.displayName,
                    "正在这里陪着你",
                    Icons.Outlined.FavoriteBorder,
                )
            }

            Spacer(Modifier.height(34.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(Modifier.size(5.dp), CircleShape, DesktopAccent) {}
                Spacer(Modifier.width(9.dp))
                Surface(Modifier.weight(1f).height(1.dp), color = DesktopLine) {}
                Spacer(Modifier.width(9.dp))
                Text(
                    "APPS",
                    color = DesktopMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
            }

            Spacer(Modifier.height(10.dp))
            DesktopLauncherGrid(
                slots = slots,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onMove = { from, to ->
                    if (from != to) {
                        val updated = slots.toMutableList()
                        val moving = updated[from]
                        updated[from] = updated[to]
                        updated[to] = moving
                        slots = updated
                        saveDesktopSlots(context, updated)
                    }
                },
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun DesktopInfoCard(
    modifier: Modifier,
    eyebrow: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
) {
    Surface(
        modifier = modifier.height(72.dp),
        color = DesktopCard,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, DesktopLine),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.size(34.dp), RoundedCornerShape(11.dp), DesktopSoft) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = DesktopIcon, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(eyebrow, color = DesktopMuted, fontSize = 8.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp)
                Spacer(Modifier.height(2.dp))
                Text(title, color = DesktopInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, color = DesktopMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DesktopLauncherGrid(
    slots: List<String?>,
    modifier: Modifier,
    onMove: (Int, Int) -> Unit,
    onOpen: (MigrationRoute) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cellWidth = maxWidth / DESKTOP_COLUMNS
        val cellHeight = maxHeight / DESKTOP_ROWS
        slots.forEachIndexed { index, launcherId ->
            val launcher = DesktopV2Launchers.firstOrNull { it.id == launcherId } ?: return@forEachIndexed
            val column = index % DESKTOP_COLUMNS
            val row = index / DESKTOP_COLUMNS
            var dragX by remember(launcherId) { mutableFloatStateOf(0f) }
            var dragY by remember(launcherId) { mutableFloatStateOf(0f) }
            var dragging by remember(launcherId) { mutableStateOf(false) }

            DesktopV2LauncherItem(
                launcher = launcher,
                modifier = Modifier
                    .width(cellWidth)
                    .height(cellHeight)
                    .offset { IntOffset((cellWidth * column).roundToPx(), (cellHeight * row).roundToPx()) }
                    .graphicsLayer {
                        translationX = dragX
                        translationY = dragY
                        scaleX = if (dragging) 1.05f else 1f
                        scaleY = if (dragging) 1.05f else 1f
                        shadowElevation = if (dragging) 10f else 0f
                    }
                    .pointerInput(index, cellWidth, cellHeight) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = true },
                            onDragCancel = {
                                dragging = false
                                dragX = 0f
                                dragY = 0f
                            },
                            onDragEnd = {
                                val targetColumn = (column + dragX / cellWidth.toPx()).roundToInt().coerceIn(0, DESKTOP_COLUMNS - 1)
                                val targetRow = (row + dragY / cellHeight.toPx()).roundToInt().coerceIn(0, DESKTOP_ROWS - 1)
                                onMove(index, targetRow * DESKTOP_COLUMNS + targetColumn)
                                dragging = false
                                dragX = 0f
                                dragY = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                        }
                    },
                onClick = { if (!dragging) onOpen(launcher.route) },
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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DesktopIconSurface,
            border = BorderStroke(1.dp, DesktopLine),
            shadowElevation = 1.dp,
            modifier = Modifier.width(61.dp).height(66.dp),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(launcher.icon, launcher.title, tint = DesktopIcon, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
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
private fun DesktopV2Avatar(text: String, size: Int) {
    Surface(
        shape = CircleShape,
        color = DesktopSoft,
        border = BorderStroke(1.dp, DesktopLine),
        shadowElevation = 1.dp,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = (size / 2.75).sp, fontWeight = FontWeight.Bold, color = DesktopInk)
        }
    }
}

private fun loadDesktopSlots(context: Context): List<String?> {
    val defaults = MutableList<String?>(DESKTOP_SLOT_COUNT) { null }
    DesktopV2Launchers.forEachIndexed { index, launcher -> defaults[index] = launcher.id }
    val raw = context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        .getString("slots_v1", null)
        ?: return defaults
    val parsed = raw.split('|').map { value -> value.takeIf(String::isNotBlank) }.toMutableList()
    while (parsed.size < DESKTOP_SLOT_COUNT) parsed.add(null)
    val known = DesktopV2Launchers.mapTo(mutableSetOf()) { it.id }
    val sanitized = parsed.take(DESKTOP_SLOT_COUNT).map { id -> id?.takeIf { it in known } }.toMutableList()
    val existing = sanitized.filterNotNull().toMutableSet()
    DesktopV2Launchers.filterNot { it.id in existing }.forEach { launcher ->
        val empty = sanitized.indexOfFirst { it == null }
        if (empty >= 0) sanitized[empty] = launcher.id
    }
    return sanitized
}

private fun saveDesktopSlots(context: Context, slots: List<String?>) {
    context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        .edit()
        .putString("slots_v1", slots.take(DESKTOP_SLOT_COUNT).joinToString("|") { it.orEmpty() })
        .apply()
}
