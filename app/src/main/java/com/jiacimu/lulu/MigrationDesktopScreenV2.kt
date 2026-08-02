package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class DesktopV2Launcher(
    val title: String,
    val icon: ImageVector,
    val route: MigrationRoute,
)

private val DesktopV2Launchers = listOf(
    DesktopV2Launcher("聊天", Icons.Outlined.ChatBubbleOutline, MigrationRoute.Chat),
    DesktopV2Launcher("记忆", Icons.Outlined.Psychology, MigrationRoute.Memory),
    DesktopV2Launcher("辞海", Icons.Outlined.MenuBook, MigrationRoute.Lexicon),
    DesktopV2Launcher("世界书", Icons.Outlined.Public, MigrationRoute.WorldBook),
    DesktopV2Launcher("性能监测", Icons.Outlined.MonitorHeart, MigrationRoute.Performance),
    DesktopV2Launcher("阅读", Icons.Outlined.AutoStories, MigrationRoute.Reading),
    DesktopV2Launcher("心愿馆", Icons.Outlined.StarOutline, MigrationRoute.Wishes),
    DesktopV2Launcher("考研", Icons.Outlined.School, MigrationRoute.Study),
    DesktopV2Launcher("游戏", Icons.Outlined.SportsEsports, MigrationRoute.Games),
    DesktopV2Launcher("设置", Icons.Outlined.Settings, MigrationRoute.Settings),
)

private val DesktopPaper = Color(0xFFF8FAF8)
private val DesktopCard = Color(0xFFFCFDFC)
private val DesktopIconSurface = Color(0xFFE9F0EE)
private val DesktopIcon = Color(0xFF607A75)
private val DesktopBorder = Color(0xFFDDE7E3)
private val DesktopInk = Color(0xFF34413F)
private val DesktopMuted = Color(0xFF82908D)

@Composable
internal fun MigrationHomeV2(
    onOpen: (MigrationRoute) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
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

    Surface(modifier = Modifier.fillMaxSize(), color = DesktopPaper) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 38.dp, end = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dateText, color = DesktopMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$greeting，主人",
                            color = DesktopInk,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    DesktopV2Avatar(
                        text = currentCharacter.displayName.take(1).ifBlank { "露" },
                        size = 52,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        recent?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat)
                    },
                    colors = CardDefaults.cardColors(containerColor = DesktopCard),
                    border = BorderStroke(1.dp, DesktopBorder),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DesktopV2Avatar(
                            text = currentCharacter.displayName.take(1).ifBlank { "露" },
                            size = 46,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (recent == null) "开始聊天" else "最近聊天", color = DesktopMuted, fontSize = 11.sp)
                            Text(
                                recent?.title?.ifBlank { currentCharacter.displayName } ?: currentCharacter.displayName,
                                color = DesktopInk,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                recent?.lastMessage?.ifBlank { "点这里去找${currentCharacter.displayName}。" }
                                    ?: "点这里进入消息列表。",
                                color = DesktopMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                        }
                        Icon(Icons.Outlined.ArrowForward, "进入聊天", tint = DesktopIcon)
                    }
                }
            }

            (0 until 16).chunked(4).forEach { positions ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        positions.forEach { index ->
                            val launcher = DesktopV2Launchers.getOrNull(index)
                            if (launcher == null) {
                                DesktopV2EmptySlot(Modifier.weight(1f))
                            } else {
                                DesktopV2LauncherItem(
                                    launcher = launcher,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpen(launcher.route) },
                                )
                            }
                        }
                    }
                }
            }
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
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DesktopIconSurface,
            border = BorderStroke(1.dp, DesktopBorder),
            modifier = Modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(launcher.icon, launcher.title, tint = DesktopIcon, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(launcher.title, color = DesktopInk, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DesktopV2EmptySlot(modifier: Modifier) {
    Column(modifier = modifier.padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, DesktopBorder.copy(alpha = 0.55f)),
            modifier = Modifier.size(58.dp),
        ) {}
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun DesktopV2Avatar(text: String, size: Int) {
    Surface(
        shape = CircleShape,
        color = DesktopIconSurface,
        border = BorderStroke(1.dp, DesktopBorder),
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = (size / 2.75).sp, fontWeight = FontWeight.Bold, color = DesktopIcon)
        }
    }
}
