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
import com.jiacimu.lulu.design.LuluColors
import com.jiacimu.lulu.design.LuluRadii
import com.jiacimu.lulu.design.LuluSpacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class DesktopV2Launcher(
    val title: String,
    val icon: ImageVector,
    val route: MigrationRoute,
    val tint: Color,
    val background: Color,
)

private val DesktopV2Launchers = listOf(
    DesktopV2Launcher("聊天", Icons.Outlined.ChatBubbleOutline, MigrationRoute.Chat, Color(0xFF79574E), Color(0xFFFFE9E0)),
    DesktopV2Launcher("记忆", Icons.Outlined.Psychology, MigrationRoute.Memory, Color(0xFF62597B), Color(0xFFEDE8F7)),
    DesktopV2Launcher("辞海", Icons.Outlined.MenuBook, MigrationRoute.Lexicon, Color(0xFF516B57), Color(0xFFE8F1E5)),
    DesktopV2Launcher("世界书", Icons.Outlined.Public, MigrationRoute.WorldBook, Color(0xFF526B78), Color(0xFFE6F0F3)),
    DesktopV2Launcher("性能监测", Icons.Outlined.MonitorHeart, MigrationRoute.Performance, Color(0xFF80544E), Color(0xFFFFE9E4)),
    DesktopV2Launcher("阅读", Icons.Outlined.AutoStories, MigrationRoute.Reading, Color(0xFF755F3C), Color(0xFFFFEFD7)),
    DesktopV2Launcher("心愿馆", Icons.Outlined.StarOutline, MigrationRoute.Wishes, Color(0xFF745D72), Color(0xFFF3E8F2)),
    DesktopV2Launcher("考研", Icons.Outlined.School, MigrationRoute.Study, Color(0xFF4F6A6D), Color(0xFFE4EFF0)),
    DesktopV2Launcher("游戏", Icons.Outlined.SportsEsports, MigrationRoute.Games, Color(0xFF615A78), Color(0xFFECE8F5)),
    DesktopV2Launcher("设置", Icons.Outlined.Settings, MigrationRoute.Settings, Color(0xFF625F59), Color(0xFFEDEBE6)),
)

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

    Surface(modifier = Modifier.fillMaxSize(), color = LuluColors.Paper) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(LuluSpacing.Large),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dateText, color = LuluColors.Muted, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(3.dp))
                        Text("$greeting，主人", style = MaterialTheme.typography.headlineLarge)
                        Text("今天也慢慢来，露露会把每一件小事陪好。", color = LuluColors.Muted)
                    }
                    DesktopV2Avatar(
                        text = currentCharacter.displayName.take(1).ifBlank { "露" },
                        size = 58,
                        background = Color(0xFFFFE5DC),
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        recent?.let { onOpenConversation(it.id) } ?: onOpen(MigrationRoute.Chat)
                    },
                    colors = CardDefaults.cardColors(containerColor = LuluColors.CardStrong),
                    border = BorderStroke(1.dp, LuluColors.Border),
                    shape = RoundedCornerShape(LuluRadii.Hero),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DesktopV2Avatar(
                            text = currentCharacter.displayName.take(1).ifBlank { "露" },
                            size = 48,
                            background = Color.White.copy(alpha = 0.78f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (recent == null) "开始第一段聊天" else "最近聊天", color = LuluColors.Muted, fontSize = 12.sp)
                            Text(
                                recent?.title?.ifBlank { currentCharacter.displayName } ?: currentCharacter.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                recent?.lastMessage?.ifBlank { "还没有消息，点这里去找${currentCharacter.displayName}。" }
                                    ?: "还没有最近聊天，点这里进入消息列表。",
                                color = LuluColors.Muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Outlined.ArrowForward, "进入聊天", tint = LuluColors.BlueGray)
                    }
                }
            }

            item {
                Text("我的桌面", style = MaterialTheme.typography.titleLarge)
            }

            DesktopV2Launchers.chunked(4).forEach { row ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { launcher ->
                            DesktopV2LauncherItem(
                                launcher = launcher,
                                modifier = Modifier.weight(1f),
                                onClick = { onOpen(launcher.route) },
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            item {
                Text(
                    "阅读暂时保留为空入口；日志已经合并进性能监测，角色与“我的”已经合并进聊天。",
                    color = LuluColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
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
            color = launcher.background,
            border = BorderStroke(1.dp, LuluColors.Border.copy(alpha = 0.78f)),
            modifier = Modifier.size(58.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(launcher.icon, launcher.title, tint = launcher.tint, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(launcher.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DesktopV2Avatar(text: String, size: Int, background: Color) {
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = (size / 2.65).sp, fontWeight = FontWeight.Bold, color = LuluColors.Ink)
        }
    }
}
