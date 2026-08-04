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
import androidx.compose.ui.text.font.FontStyle
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
private const val DESKTOP_ROWS = 4
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
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "$greeting，主人",
                        color = DesktopInk,
                        fontSize = 31.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(dateText, color = DesktopMuted, fontSize = 11.sp, letterSpacing = 0.8.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(5.dp), CircleShape, DesktopAccent) {}
                        Spacer(Modifier.width(7.dp))
                        Text("DAY MODE", color = DesktopMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    DesktopV2Avatar(currentCharacter.displayName.take(1).ifBlank { "露" }, 44)
                }
            }

            Spacer(Modifier.height(12.dp))
            DesktopCompanionCard(
                characterName = currentCharacter.displayName,
                recentMessage = recent?.lastMessage
                    ?.ifBlank { "点这里去找${currentCharacter.displayName}。" }
                    ?: "今天也来找我说说话吧。",
                onClick = { recent?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat) },
            )

            Spacer(Modifier.height(10.dp))
            DesktopMomentCard(
                characterName = currentCharacter.displayName,
                dateLabel = today.format(DateTimeFormatter.ofPattern("MM / dd")),
                onClick = { onOpen(MigrationRoute.Wishes) },
            )

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
                    "APPS",
                    color = DesktopMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
            }

            Spacer(Modifier.height(9.dp))
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
private fun DesktopCompanionCard(
    characterName: String,
    recentMessage: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DesktopCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, DesktopLine),
        shadowElevation = 2.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopV2Avatar(characterName.take(1).ifBlank { "露" }, 52)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(characterName, color = DesktopInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("ONLINE", color = DesktopMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
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
                    DesktopTinyTag("陪伴中")
                    DesktopTinyTag("点我聊天")
                }
            }
            Icon(Icons.Outlined.ChevronRight, "进入聊天", tint = DesktopMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DesktopMomentCard(
    characterName: String,
    dateLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(108.dp),
        color = DesktopCard,
        shape = RoundedCornerShape(24.dp),
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
            DesktopPhotoPlaceholder()
        }
    }
}

@Composable
private fun DesktopPhotoPlaceholder() {
    Surface(
        modifier = Modifier.width(96.dp).fillMaxHeight(),
        color = DesktopSoft,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, DesktopLine),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Image, null, tint = DesktopMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text("PHOTO", color = DesktopInk, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(2.dp))
                Text("待插入", color = DesktopMuted, fontSize = 9.sp)
            }
        }
    }
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
