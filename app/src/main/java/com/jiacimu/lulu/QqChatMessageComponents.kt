package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QqMessageRow(
    message: LuluChatMessage,
    characterName: String,
    characterAvatarUri: String?,
    characterLabel: String,
    showCharacterName: Boolean,
    repliedMessageContent: String?,
    showAvatar: Boolean,
    showTime: Boolean,
    userAvatar: String,
    userAvatarUri: String?,
    onCharacterAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
    onAcceptGame: (String) -> Unit,
) {
    if (message.sender == LuluChatMessage.Sender.System) {
        val context = LocalContext.current
        val notice = remember(message.content) { parseSystemActivityNotice(message.content) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                color = Color(0xFFF1F1F1),
                shape = RoundedCornerShape(99.dp),
                border = BorderStroke(1.dp, QqBorder),
            ) {
                if (notice.link == null) {
                    Text(
                        notice.visibleText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = QqMuted,
                        fontSize = 11.sp,
                    )
                } else {
                    val annotated = remember(notice) {
                        buildAnnotatedString {
                            append(notice.visibleText)
                            if (notice.linkStart in 0 until notice.visibleText.length && notice.linkEnd > notice.linkStart) {
                                addStyle(
                                    SpanStyle(
                                        color = QqInk,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    notice.linkStart,
                                    notice.linkEnd.coerceAtMost(notice.visibleText.length),
                                )
                                addStringAnnotation(
                                    tag = "system_activity",
                                    annotation = "open",
                                    start = notice.linkStart,
                                    end = notice.linkEnd.coerceAtMost(notice.visibleText.length),
                                )
                            }
                        }
                    }
                    ClickableText(
                        text = annotated,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = LocalTextStyle.current.copy(color = QqMuted, fontSize = 11.sp),
                        onClick = { offset ->
                            if (annotated.getStringAnnotations("system_activity", offset, offset).isNotEmpty()) {
                                openSystemActivity(context, message, notice.link)
                            }
                        },
                    )
                }
            }
        }
        return
    }
    val mine = message.sender == LuluChatMessage.Sender.User
    val gameInvite = remember(message.content, mine) { if (mine) null else parseGameInvite(message.content) }
    val visibleContent = gameInvite?.message ?: message.content
    val bubbles = remember(visibleContent, mine, gameInvite) {
        when {
            gameInvite != null -> emptyList()
            mine -> listOf(visibleContent)
            else -> splitCharacterBubbles(visibleContent)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
            if (!mine && showAvatar) {
                QqAvatar(
                    characterName.take(1).ifBlank { "露" },
                    44,
                    characterAvatarUri,
                    Modifier.clickable(onClick = onCharacterAvatarClick),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine && showCharacterName && showAvatar) {
                Text(
                    characterLabel,
                    color = QqMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }
            if (gameInvite != null) {
                GameInviteMessageCard(gameInvite, onAccept = { onAcceptGame(gameInvite.gameId) })
                Spacer(Modifier.height(5.dp))
            }
            bubbles.filter { it.isNotBlank() }.forEachIndexed { index, bubble ->
                val bubbleWidth = if (bubble.length >= 52) Modifier.fillMaxWidth() else Modifier.widthIn(max = 300.dp)
                Surface(
                    modifier = bubbleWidth.combinedClickable(onClick = {}, onLongClick = onLongClick),
                    color = if (mine) QqMine else QqOther,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, if (mine) QqMine else QqBorder),
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        repliedMessageContent?.let { quoted ->
                            Surface(
                                color = if (mine) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.78f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    quoted,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                    color = if (mine) QqMineInk.copy(alpha = 0.72f) else QqMuted,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            bubble,
                            color = if (mine) QqMineInk else QqInk,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                        if (message.favorite && index == bubbles.lastIndex) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "★ 已收藏",
                                color = if (mine) Color.White.copy(alpha = 0.68f) else QqMuted,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                if (index != bubbles.lastIndex) Spacer(Modifier.height(5.dp))
            }
            if (showTime) {
                Spacer(Modifier.height(4.dp))
                Text(
                    message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                    color = QqMuted,
                    fontSize = 10.sp,
                    textAlign = if (mine) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
            if (mine && showAvatar) QqAvatar(userAvatar, 44, userAvatarUri)
        }
    }
}

private enum class SystemActivityType { Diary, Reading }

private data class SystemActivityLink(
    val type: SystemActivityType,
    val targetTitle: String,
)

private data class SystemActivityNotice(
    val visibleText: String,
    val link: SystemActivityLink? = null,
    val linkStart: Int = -1,
    val linkEnd: Int = -1,
)

private fun parseSystemActivityNotice(content: String): SystemActivityNotice {
    val visible = content
        .removePrefix("[共同活动]")
        .removePrefix("[群成员变更]")
        .trim()

    Regex("刚刚写了一篇日记《([^》]+)》").find(visible)?.let { match ->
        val diaryWordStart = visible.indexOf("日记")
        return SystemActivityNotice(
            visibleText = visible,
            link = SystemActivityLink(SystemActivityType.Diary, match.groupValues[1].trim()),
            linkStart = diaryWordStart,
            linkEnd = diaryWordStart + 2,
        )
    }

    Regex("刚刚读了《([^》]+)》").find(visible)?.let { match ->
        val bookStart = visible.indexOf('《')
        val bookEnd = visible.indexOf('》', bookStart).let { if (it >= 0) it + 1 else -1 }
        return SystemActivityNotice(
            visibleText = visible,
            link = SystemActivityLink(SystemActivityType.Reading, match.groupValues[1].trim()),
            linkStart = bookStart,
            linkEnd = bookEnd,
        )
    }

    return SystemActivityNotice(visibleText = visible)
}

private fun openSystemActivity(context: Context, message: LuluChatMessage, link: SystemActivityLink) {
    val characterId = MigratedDomainStores.chat.conversations.value
        .firstOrNull { it.id == message.conversationId }
        ?.characterId
        .orEmpty()
    val intent = Intent(context, MigrationActivity::class.java).apply {
        when (link.type) {
            SystemActivityType.Diary -> {
                putExtra("open_route", MigrationRoute.Lexicon.name)
                putExtra("open_character_id", characterId)
                putExtra("open_diary_title", link.targetTitle)
            }
            SystemActivityType.Reading -> {
                putExtra("open_route", MigrationRoute.Reading.name)
                putExtra("open_reading_title", link.targetTitle)
            }
        }
    }
    context.startActivity(intent)
}

private data class GameInviteMessage(val gameId: String, val title: String, val message: String)

private fun parseGameInvite(content: String): GameInviteMessage? {
    val match = Regex("^\\[游戏邀约\\|([^|\\]]+)\\|([^\\]]+)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(content.trim())
        ?: return null
    return GameInviteMessage(
        gameId = match.groupValues[1].trim(),
        title = match.groupValues[2].trim().ifBlank { "一起玩游戏" },
        message = match.groupValues[3].trim(),
    )
}

@Composable
private fun GameInviteMessageCard(invite: GameInviteMessage, onAccept: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, QqBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(86.dp).background(Color(0xFF292929)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.14f), modifier = Modifier.size(50.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SportsEsports, null, tint = Color.White) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("游戏邀约", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                        Text(invite.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (invite.message.isNotBlank()) Text(invite.message, color = QqInk, lineHeight = 20.sp)
                Button(
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = QqMine, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("接受邀约")
                }
            }
        }
    }
}

private fun splitCharacterBubbles(text: String): List<String> {
    return text.replace("\r\n", "\n").trim()
        .split(Regex("\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
}

@Composable
internal fun QqGroupAvatar(group: LuluGroupChat, size: Int) {
    if (!group.avatarUri.isNullOrBlank()) {
        QqAvatar(group.name.take(1).ifBlank { "群" }, size, group.avatarUri)
    } else {
        Surface(
            modifier = Modifier.size(size.dp),
            color = QqOther,
            shape = RoundedCornerShape((size * 0.28f).dp),
            border = BorderStroke(1.dp, QqBorder),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, tint = QqInk, modifier = Modifier.size((size * 0.55f).dp))
            }
        }
    }
}

@Composable
internal fun QqAvatar(label: String, size: Int, imageUri: String? = null, modifier: Modifier = Modifier) {
    LuluProfileAvatar(imageUri = imageUri, fallback = label, size = size, modifier = modifier)
}
