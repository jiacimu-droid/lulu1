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

private val DesktopPaper = Color(0xFFFFFFFF)
private val DesktopWarmWash = Color(0xFFFFFDF4)
private val DesktopWarmCard = Color(0xFFFFFFFF)
private val DesktopIconSurface = Color(0xFFFFF9E8)
private val DesktopIcon = Color(0xFF2F2F2F)
private val DesktopBorder = Color(0xFFEAE7DE)
private val DesktopInk = Color(0xFF222222)
private val DesktopMuted = Color(0xFF77736A)

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
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DesktopWarmWash,
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, DesktopBorder.copy(alpha = 0.7f)),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dateText, color = DesktopMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$greeting，主人",
                                color = DesktopInk,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        DesktopV2Avatar(
                            text = currentCharacter.displayName.take(1).ifBlank { "露" },
                            size = 46,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = DesktopWarmCard,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, DesktopBorder),
                        onClick = {
                            recent?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat)
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DesktopV2Avatar(
                                text = currentCharacter.displayName.take(1).ifBlank { "露" },
                                size = 40,
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    recent?.title?.ifBlank { currentCharacter.displayName }
                                        ?: currentCharacter.displayName,
                                    color = DesktopInk,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    recent?.lastMessage?.ifBlank { "点这里去找${currentCharacter.displayName}。" }
                                        ?: "点这里进入聊天。",
                                    color = DesktopMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                )
                            }
                            Icon(Icons.Outlined.ArrowForward, "进入聊天", tint = DesktopInk)
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            DesktopLauncherGrid(
                slots = slots,
                modifier = Modifier.fillMaxWidth().height(374.dp),
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

            Spacer(Modifier.weight(1f))
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
        val cellWidth = maxWidth / 4
        val cellHeight = 93.5.dp
        slots.forEachIndexed { index, launcherId ->
            val launcher = DesktopV2Launchers.firstOrNull { it.id == launcherId } ?: return@forEachIndexed
            val column = index % 4
            val row = index / 4
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
                        scaleX = if (dragging) 1.07f else 1f
                        scaleY = if (dragging) 1.07f else 1f
                        shadowElevation = if (dragging) 12f else 0f
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
                                val targetColumn = (column + dragX / cellWidth.toPx()).roundToInt().coerceIn(0, 3)
                                val targetRow = (row + dragY / cellHeight.toPx()).roundToInt().coerceIn(0, 3)
                                onMove(index, targetRow * 4 + targetColumn)
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
            shape = RoundedCornerShape(21.dp),
            color = DesktopIconSurface,
            border = BorderStroke(1.dp, DesktopBorder),
            modifier = Modifier.size(64.dp),
            onClick = onClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(launcher.icon, launcher.title, tint = DesktopIcon, modifier = Modifier.size(29.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            launcher.title,
            color = DesktopInk,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopV2Avatar(text: String, size: Int) {
    Surface(
        shape = CircleShape,
        color = Color.White,
        border = BorderStroke(1.dp, DesktopBorder),
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = (size / 2.75).sp, fontWeight = FontWeight.Bold, color = DesktopInk)
        }
    }
}

private fun loadDesktopSlots(context: Context): List<String?> {
    val defaults = MutableList<String?>(16) { null }
    DesktopV2Launchers.forEachIndexed { index, launcher -> defaults[index] = launcher.id }
    val raw = context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
        .getString("slots_v1", null)
        ?: return defaults
    val parsed = raw.split('|').map { value -> value.takeIf(String::isNotBlank) }.toMutableList()
    while (parsed.size < 16) parsed.add(null)
    val known = DesktopV2Launchers.mapTo(mutableSetOf()) { it.id }
    val sanitized = parsed.take(16).map { id -> id?.takeIf { it in known } }.toMutableList()
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
        .putString("slots_v1", slots.joinToString("|") { it.orEmpty() })
        .apply()
}
