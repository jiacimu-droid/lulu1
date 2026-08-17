package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MeetingExperienceStore
import com.jiacimu.lulu.data.MeetingInvitationStatus
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    onSwipeReply: () -> Unit,
    onAcceptGame: (String) -> Unit,
) {
    val context = LocalContext.current
    if (message.sender != LuluChatMessage.Sender.System) {
        val recalledIds = recalledMessageIds(MigratedDomainStores.chat.messages(message.conversationId).value)
        if (message.id in recalledIds) return
    }

    if (message.sender == LuluChatMessage.Sender.System) {
        val notice = remember(message.content) { parseSystemActivityNotice(message.content) }
        val receiptTime = remember(message.createdAt) {
            message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(receiptTime, color = QqMuted.copy(alpha = 0.72f), fontSize = 9.sp)
            Surface(
                color = Color(0xFFF5F5F4),
                shape = RoundedCornerShape(9.dp),
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(modifier = Modifier.size(5.dp), shape = CircleShape, color = QqMuted.copy(alpha = 0.45f)) {}
                    if (notice.link == null) {
                        Text(notice.visibleText, color = QqMuted, fontSize = 10.5.sp, lineHeight = 15.sp)
                    } else {
                        val annotated = remember(notice) {
                            buildAnnotatedString {
                                append(notice.visibleText)
                                if (notice.linkStart in 0 until notice.visibleText.length && notice.linkEnd > notice.linkStart) {
                                    addStyle(
                                        SpanStyle(
                                            color = QqInk.copy(alpha = 0.82f),
                                            fontWeight = FontWeight.Medium,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                        notice.linkStart,
                                        notice.linkEnd.coerceAtMost(length),
                                    )
                                    addStringAnnotation(
                                        tag = "system_activity",
                                        annotation = "open",
                                        start = notice.linkStart,
                                        end = notice.linkEnd.coerceAtMost(length),
                                    )
                                }
                            }
                        }
                        ClickableText(
                            text = annotated,
                            style = LocalTextStyle.current.copy(color = QqMuted, fontSize = 10.5.sp, lineHeight = 15.sp),
                            onClick = { offset ->
                                if (annotated.getStringAnnotations("system_activity", offset, offset).isNotEmpty()) {
                                    openSystemActivity(context, message, notice.link)
                                }
                            },
                        )
                    }
                }
            }
        }
        return
    }

    val mine = message.sender == LuluChatMessage.Sender.User
    val forwardBundle = remember(message.content) { decodeQqForwardedChat(message.content) }
    var forwardOpen by remember(message.id) { mutableStateOf(false) }
    val gameInvite = remember(message.content, mine, forwardBundle) {
        if (mine || forwardBundle != null) null else parseGameInvite(message.content)
    }
    val worldInvite = remember(message.content, mine, forwardBundle) {
        if (mine || forwardBundle != null) null else parseWorldInvite(message.content)
    }
    val visibleContent = remember(message.content, gameInvite, worldInvite, forwardBundle) {
        when {
            forwardBundle != null -> ""
            gameInvite != null -> gameInvite.message
            worldInvite != null -> worldInvite.message
            else -> stripCharacterReplyDirective(message.content)
        }
    }
    val bubbles = remember(visibleContent, mine, gameInvite, worldInvite, forwardBundle) {
        when {
            forwardBundle != null -> emptyList()
            gameInvite != null || worldInvite != null -> emptyList()
            mine -> listOf(visibleContent)
            else -> splitCharacterBubbles(visibleContent)
        }
    }
    val density = LocalDensity.current
    val triggerPx = remember(density) { with(density) { 58.dp.toPx() } }
    val maxDragPx = remember(density) { with(density) { 92.dp.toPx() } }
    var swipeOffset by remember(message.id) { mutableFloatStateOf(0f) }
    val timeText = remember(message.createdAt) {
        message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val anchorCharacterId = remember(message.authorCharacterId, message.conversationId, mine) {
        if (mine) null else message.authorCharacterId
            ?: MigratedDomainStores.chat.conversations.value.firstOrNull { it.id == message.conversationId }?.characterId
    }

    Box(Modifier.fillMaxWidth()) {
        if (swipeOffset < -6f) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Reply, null, Modifier.size(19.dp), tint = if (-swipeOffset >= triggerPx) QqInk else QqMuted)
                Text("引用", fontSize = 11.sp, color = if (-swipeOffset >= triggerPx) QqInk else QqMuted)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().offset { IntOffset(swipeOffset.roundToInt(), 0) }.pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragEnd = { val shouldReply = -swipeOffset >= triggerPx; swipeOffset = 0f; if (shouldReply) onSwipeReply() },
                    onDragCancel = { swipeOffset = 0f },
                    onHorizontalDrag = { change, dragAmount -> change.consume(); swipeOffset = (swipeOffset + dragAmount).coerceIn(-maxDragPx, 0f) },
                )
            },
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(44.dp).padding(top = if (!mine && showCharacterName && showAvatar) 8.dp else 0.dp), contentAlignment = Alignment.TopCenter) {
                if (!mine && showAvatar) {
                    QqAvatar(
                        characterName.take(1).ifBlank { "露" }, 44, characterAvatarUri,
                        Modifier.combinedClickable(
                            onClick = { anchorCharacterId?.let { CompanionPresenceStore.selectMessageAnchor(it, message.createdAt) }; onCharacterAvatarClick() },
                            onDoubleClick = { MigratedDomainStores.chat.appendSystemMessage(message.conversationId, "[戳一戳] 你戳了戳$characterLabel。") },
                        ),
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (!mine && showCharacterName && showAvatar) {
                    Text(characterLabel, color = QqMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
                }
                if (gameInvite != null) {
                    MessageLineWithTime(mine, showTime, timeText) { bubbleModifier -> GameInviteMessageCard(gameInvite, { onAcceptGame(gameInvite.gameId) }, bubbleModifier) }
                    Spacer(Modifier.height(5.dp))
                }
                if (worldInvite != null) {
                    MessageLineWithTime(mine, showTime, timeText) { bubbleModifier ->
                        WorldInviteMessageCard(
                            worldInvite,
                            onAccept = {
                                context.startActivity(
                                    Intent(context, MigrationActivity::class.java)
                                        .putExtra("open_route", MigrationRoute.Meeting.name)
                                        .putExtra("open_character_id", worldInvite.characterId)
                                        .putExtra("open_meeting_invitation_text", worldInvite.message)
                                        .putExtra("open_meeting_location", worldInvite.location)
                                        .putExtra("open_meeting_invitation_id", worldInvite.invitationId),
                                )
                            },
                            onReject = { worldInvite.invitationId.takeIf(String::isNotBlank)?.let { MeetingExperienceStore.rejectInvitation(it) } },
                            modifier = bubbleModifier,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                }
                if (forwardBundle != null) {
                    MessageLineWithTime(mine, showTime, timeText) { bubbleModifier ->
                        QqForwardedChatCard(forwardBundle, bubbleModifier.combinedClickable(onClick = { forwardOpen = true }, onLongClick = onLongClick))
                    }
                    Spacer(Modifier.height(5.dp))
                }
                bubbles.filter(String::isNotBlank).forEachIndexed { index, bubble ->
                    val isLastBubble = index == bubbles.lastIndex
                    MessageLineWithTime(mine, showTime && isLastBubble, timeText) { bubbleModifier ->
                        Surface(
                            modifier = bubbleModifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                            color = if (mine) QqMine else QqOther,
                            shape = RoundedCornerShape(15.dp),
                            border = BorderStroke(1.dp, if (mine) QqMine else QqBorder),
                            shadowElevation = 0.dp,
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                repliedMessageContent?.let { quoted ->
                                    Surface(color = if (mine) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.78f), shape = RoundedCornerShape(8.dp)) {
                                        Text(quoted, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), color = if (mine) QqMineInk.copy(alpha = 0.72f) else QqMuted, fontSize = 11.sp, maxLines = 2)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(bubble, color = if (mine) QqMineInk else QqInk, fontSize = 15.sp, lineHeight = 22.sp)
                                if (message.favorite && isLastBubble) {
                                    Spacer(Modifier.height(4.dp)); Text("★ 已收藏", color = if (mine) Color.White.copy(alpha = 0.68f) else QqMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    if (!isLastBubble) Spacer(Modifier.height(5.dp))
                }
            }
            Spacer(Modifier.width(9.dp))
            Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) { if (mine && showAvatar) QqAvatar(userAvatar, 44, userAvatarUri) }
        }
    }

    if (forwardOpen && forwardBundle != null) {
        AlertDialog(
            onDismissRequest = { forwardOpen = false },
            title = { Text(forwardBundle.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    forwardBundle.entries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.sender, color = QqInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Spacer(Modifier.weight(1f))
                                if (entry.timeLabel.isNotBlank()) Text(entry.timeLabel, color = QqMuted, fontSize = 10.sp)
                            }
                            Text(entry.content, color = QqInk, fontSize = 13.sp, lineHeight = 19.sp); HorizontalDivider(color = QqBorder)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { forwardOpen = false }) { Text("关闭", color = QqInk) } },
        )
    }
}

@Composable
private fun MessageLineWithTime(mine: Boolean, showTime: Boolean, timeText: String, content: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.BottomEnd else Alignment.BottomStart) {
        Layout(
            modifier = Modifier.widthIn(max = 300.dp),
            content = {
                content(Modifier.widthIn(max = 300.dp))
                if (showTime) Text(timeText, Modifier.width(38.dp), color = QqMuted, fontSize = 10.sp, maxLines = 1, softWrap = false)
            },
        ) { measurables, constraints ->
            val bubble = measurables.first().measure(constraints.copy(minWidth = 0))
            val time = if (showTime && measurables.size > 1) measurables[1].measure(constraints.copy(minWidth = 0)) else null
            val gapPx = 6.dp.roundToPx()
            val height = maxOf(bubble.height, time?.height ?: 0)
            layout(bubble.width, height) {
                bubble.placeRelative(0, height - bubble.height)
                time?.let { it.placeRelative(if (mine) -it.width - gapPx else bubble.width + gapPx, height - it.height) }
            }
        }
    }
}

@Composable
private fun QqForwardedChatCard(bundle: QqForwardedChatBundle, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, QqBorder), shadowElevation = 0.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(bundle.title, color = QqInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Spacer(Modifier.height(8.dp))
            bundle.entries.take(3).forEach { entry ->
                Text("${entry.sender}：${entry.content.replace("\n", " ")}", color = QqMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(3.dp))
            }
            Spacer(Modifier.height(6.dp)); HorizontalDivider(color = QqBorder); Spacer(Modifier.height(7.dp))
            Text("查看 ${bundle.entries.size} 条聊天记录", color = QqMuted, fontSize = 10.sp)
        }
    }
}

private enum class SystemActivityType { Diary, Reading }
private data class SystemActivityLink(val type: SystemActivityType, val targetTitle: String)
private data class SystemActivityNotice(val visibleText: String, val link: SystemActivityLink? = null, val linkStart: Int = -1, val linkEnd: Int = -1)

private fun parseSystemActivityNotice(content: String): SystemActivityNotice {
    val rawVisible = stripRecallReceiptDirective(content).removePrefix("[共同活动]").removePrefix("[群成员变更]").removePrefix("[戳一戳]").removePrefix("[撤回]").trim()
    val visible = if (rawVisible.startsWith("刚刚更新了自己的此刻") || rawVisible.startsWith("更新了自己的此刻") || rawVisible.startsWith("刚刚更新了此刻")) "更新了此刻" else rawVisible
    Regex("刚刚写了一篇日记《([^》]+)》").find(visible)?.let { match ->
        val start = visible.indexOf("日记")
        return SystemActivityNotice(visible, SystemActivityLink(SystemActivityType.Diary, match.groupValues[1].trim()), start, start + 2)
    }
    Regex("刚刚读了《([^》]+)》").find(visible)?.let { match ->
        val start = visible.indexOf('《'); val end = visible.indexOf('》', start).let { if (it >= 0) it + 1 else -1 }
        return SystemActivityNotice(visible, SystemActivityLink(SystemActivityType.Reading, match.groupValues[1].trim()), start, end)
    }
    return SystemActivityNotice(visible)
}

private fun openSystemActivity(context: Context, message: LuluChatMessage, link: SystemActivityLink) {
    val characterId = MigratedDomainStores.chat.conversations.value.firstOrNull { it.id == message.conversationId }?.characterId.orEmpty()
    val intent = Intent(context, MigrationActivity::class.java).apply {
        when (link.type) {
            SystemActivityType.Diary -> { putExtra("open_route", MigrationRoute.Lexicon.name); putExtra("open_character_id", characterId); putExtra("open_diary_title", link.targetTitle) }
            SystemActivityType.Reading -> { putExtra("open_route", MigrationRoute.Reading.name); putExtra("open_reading_title", link.targetTitle) }
        }
    }
    context.startActivity(intent)
}

private data class WorldInviteMessage(val characterId: String, val location: String, val invitationId: String, val message: String)
private fun parseWorldInvite(content: String): WorldInviteMessage? {
    val match = Regex("^\\[见面邀约\\|([^|\\]]+)(?:\\|([^|\\]]+))?(?:\\|([^\\]]+))?]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(content.trim()) ?: return null
    return WorldInviteMessage(match.groupValues[1].trim(), match.groupValues[2].trim().ifBlank { "世界入口" }, match.groupValues[3].trim(), match.groupValues[4].trim()).takeIf { it.characterId.isNotBlank() }
}

@Composable
private fun WorldInviteMessageCard(invite: WorldInviteMessage, onAccept: () -> Unit, onReject: () -> Unit, modifier: Modifier = Modifier) {
    val experience by MeetingExperienceStore.state.collectAsState()
    val invitationStatus = invite.invitationId.takeIf(String::isNotBlank)?.let { id -> experience.invitations.firstOrNull { it.id == id }?.status }
    val invitationRecord = invite.invitationId.takeIf(String::isNotBlank)?.let { id -> experience.invitations.firstOrNull { it.id == id } }
    val canRespond = invite.invitationId.isBlank() || invitationStatus == MeetingInvitationStatus.PENDING
    LaunchedEffect(invitationRecord?.id, invitationRecord?.status, invitationRecord?.expiresAt) {
        val record = invitationRecord?.takeIf { it.status == MeetingInvitationStatus.PENDING } ?: return@LaunchedEffect
        val waitMillis = Duration.between(Instant.now(), record.expiresAt).toMillis().coerceAtLeast(0L)
        if (waitMillis > 0L) delay(waitMillis + 100L)
        MeetingExperienceStore.expireInvitations()
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, QqBorder), shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(86.dp).background(Color(0xFF8EA7B8)), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.18f), modifier = Modifier.size(50.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Cloud, null, tint = Color.White) } }
                    Spacer(Modifier.width(12.dp)); Column { Text("数字世界邀约", color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp); Text("在${invite.location}见面", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (invite.message.isNotBlank()) Text(invite.message, color = QqInk, lineHeight = 20.sp)
                if (canRespond) {
                    Button(onAccept, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = QqMine, contentColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.Cloud, null, Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text("接受邀请，进入世界")
                    }
                    if (invite.invitationId.isNotBlank()) TextButton(onReject, Modifier.fillMaxWidth()) { Text("这次先不去", color = QqMuted) }
                } else {
                    Text(
                        when (invitationStatus) {
                            MeetingInvitationStatus.ACCEPTED -> "已接受 · 见面已经开始"
                            MeetingInvitationStatus.REJECTED -> "已婉拒这次邀请"
                            MeetingInvitationStatus.EXPIRED -> "邀请已过期"
                            else -> "邀请已失效"
                        },
                        color = QqMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private data class GameInviteMessage(val gameId: String, val title: String, val message: String)
private fun parseGameInvite(content: String): GameInviteMessage? {
    val match = Regex("^\\[游戏邀约\\|([^|\\]]+)\\|([^\\]]+)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(content.trim()) ?: return null
    return GameInviteMessage(match.groupValues[1].trim(), match.groupValues[2].trim().ifBlank { "一起玩游戏" }, match.groupValues[3].trim())
}

@Composable
private fun GameInviteMessageCard(invite: GameInviteMessage, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, QqBorder), shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(86.dp).background(Color(0xFF292929)), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.14f), modifier = Modifier.size(50.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SportsEsports, null, tint = Color.White) } }
                    Spacer(Modifier.width(12.dp)); Column { Text("游戏邀约", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp); Text(invite.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (invite.message.isNotBlank()) Text(invite.message, color = QqInk, lineHeight = 20.sp)
                Button(onAccept, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = QqMine, contentColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text("接受邀约")
                }
            }
        }
    }
}

private fun splitCharacterBubbles(text: String): List<String> = text.replace("\r\n", "\n").trim().split(Regex("\n+")).map(String::trim).filter(String::isNotBlank)

@Composable
internal fun QqGroupAvatar(group: LuluGroupChat, size: Int) {
    if (!group.avatarUri.isNullOrBlank()) QqAvatar(group.name.take(1).ifBlank { "群" }, size, group.avatarUri)
    else Surface(Modifier.size(size.dp), color = QqOther, shape = RoundedCornerShape((size * 0.28f).dp), border = BorderStroke(1.dp, QqBorder)) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Groups, null, tint = QqInk, modifier = Modifier.size((size * 0.55f).dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QqAvatar(label: String, size: Int, imageUri: String? = null, modifier: Modifier = Modifier) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val presenceStates by CompanionPresenceStore.states.collectAsState()
    val presenceHistories by CompanionPresenceStore.histories.collectAsState()
    val latestPresenceCharacter = remember(size, label, imageUri, characters) {
        if (size != 42) null else characters.values.firstOrNull { !imageUri.isNullOrBlank() && it.avatarUri == imageUri }
            ?: characters.values.firstOrNull { it.displayName.take(1).ifBlank { "露" } == label }
    }
    var latestPresenceOpen by remember(latestPresenceCharacter?.characterId) { mutableStateOf(false) }
    val avatarModifier = if (latestPresenceCharacter == null) modifier else modifier.combinedClickable(
        onClick = { CompanionPresenceStore.clearMessageAnchor(); latestPresenceOpen = true }, onLongClick = {},
    )
    LuluProfileAvatar(imageUri = imageUri, fallback = label, size = size, modifier = avatarModifier)
    if (latestPresenceOpen && latestPresenceCharacter != null) {
        CompanionPresenceDialog(
            characterName = latestPresenceCharacter.displayName,
            state = presenceStates[latestPresenceCharacter.characterId],
            history = presenceHistories[latestPresenceCharacter.characterId].orEmpty(),
            onDismiss = { latestPresenceOpen = false },
        )
    }
}
