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

private val DesktopPaper = Color(0xFFFFFEFA)
private val DesktopWarmWash = Color(0xFFFFF7DE)
private val DesktopWarmCard = Color(0xFFFFFBEE)
private val DesktopIconSurface = Color(0xFFFFF1C4)
private val DesktopIcon = Color(0xFF8A7240)
private val DesktopBorder = Color(0xFFF0E1B5)
private val DesktopInk = Color(0xFF4B4437)
private val DesktopMuted = Color(0xFF988E7C)

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // 上半部分只保留轻量欢迎和最近聊天，不再让应用图标挤到顶部。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DesktopWarmWash,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, DesktopBorder.copy(alpha = 0.72f)),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dateText, color = DesktopMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "$greeting，主人",
                                color = DesktopInk,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        DesktopV2Avatar(
                            text = currentCharacter.displayName.take(1).ifBlank { "露" },
                            size = 48,
                        )
                    }

                    Spacer(Modifier.height(15.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            recent?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat)
                        },
                        color = DesktopWarmCard,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, DesktopBorder),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DesktopV2Avatar(
                                text = currentCharacter.displayName.take(1).ifBlank { "露" },
                                size = 42,
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
                            Icon(Icons.Outlined.ArrowForward, "进入聊天", tint = DesktopIcon)
                        }
                    }
                }
            }

            // 用弹性留白将 4×4 应用区稳定放在屏幕中下部。
            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (0 until 16).chunked(4).forEach { positions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
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
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(19.dp),
            color = DesktopIconSurface,
            border = BorderStroke(1.dp, DesktopBorder),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(launcher.icon, launcher.title, tint = DesktopIcon, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
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
private fun DesktopV2EmptySlot(modifier: Modifier) {
    Column(modifier = modifier.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(19.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, DesktopBorder.copy(alpha = 0.48f)),
            modifier = Modifier.size(56.dp),
        ) {}
        Spacer(Modifier.height(18.dp))
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
