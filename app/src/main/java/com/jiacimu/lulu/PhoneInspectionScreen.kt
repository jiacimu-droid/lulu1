package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * An owner's inspection window into an existing person's phone.
 * This is a projection, not another chat store or a second character runtime.
 * Only actual delivered private mail, owner↔character chats and member group
 * chats appear. The owner never writes invented messages into a person's inbox.
 */
internal data class InspectedThread(
    val key: String,
    val title: String,
    val lastText: String,
    val updatedAt: Instant,
    val conversationId: String? = null,
    val peerId: String? = null,
    val isGroup: Boolean = false,
)

internal fun inspectedThreads(
    roleId: String,
    conversations: List<LuluConversation>,
    mail: List<ResidentDirectMessage>,
    names: Map<String, String>,
): List<InspectedThread> {
    if (roleId.isBlank()) return emptyList()
    val existing = conversations.mapNotNull { chat ->
        val group = chat.groupChat
        if (group != null && group.members.none { it.characterId == roleId }) return@mapNotNull null
        if (group == null && chat.characterId != roleId) return@mapNotNull null
        InspectedThread(
            key = "chat:${chat.id}",
            title = group?.name ?: "主人",
            lastText = chat.lastMessage,
            updatedAt = chat.updatedAt,
            conversationId = chat.id,
            isGroup = group != null,
        )
    }
    val native = mail.asSequence()
        .filter { it.fromId == roleId || it.toId == roleId }
        .groupBy { if (it.fromId == roleId) it.toId else it.fromId }
        .mapNotNull { (peer, messages) ->
            val last = messages.maxByOrNull(ResidentDirectMessage::createdAt) ?: return@mapNotNull null
            InspectedThread(
                key = "mail:$peer",
                title = names[peer] ?: peer,
                lastText = last.content,
                updatedAt = last.createdAt,
                peerId = peer,
            )
        }
    return (existing + native).sortedByDescending(InspectedThread::updatedAt)
}

internal fun inspectedChatMessageIsMine(
    roleId: String,
    conversation: LuluConversation,
    message: LuluChatMessage,
): Boolean = message.sender == LuluChatMessage.Sender.Character &&
    (message.authorCharacterId ?: conversation.characterId.takeIf { conversation.groupChat == null }) == roleId

private val InspectedDate = DateTimeFormatter.ofPattern("M/d HH:mm")
private fun inspectedTime(time: Instant): String =
    InspectedDate.format(time.atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneInspectionScreen(initialRoleId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val people by MigratedDomainStores.characters.settings.collectAsState()
    val allChats by MigratedDomainStores.chat.conversations.collectAsState()
    val posts by MomentsStore.posts.collectAsState()
    val friendsRevision by MomentFriendAccess.revisions.collectAsState()
    val names = remember(people) { people.mapValues { it.value.displayName } }
    val roleOptions = remember(people) {
        people.values.sortedWith(
            compareBy<CharacterSettings> { it.characterId.startsWith("resident:") }
                .thenBy(CharacterSettings::displayName)
        )
    }
    var roleId by rememberSaveable { mutableStateOf(initialRoleId.orEmpty()) }
    val actualRole = roleId.takeIf { it in people } ?: roleOptions.firstOrNull()?.characterId.orEmpty()
    var phoneTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedThreadKey by rememberSaveable { mutableStateOf<String?>(null) }
    var rolesExpanded by remember { mutableStateOf(false) }
    var pinRevision by remember { mutableIntStateOf(0) }
    val prefs = remember(context) {
        context.getSharedPreferences("lulu_phone_inspection_ui_v1", Context.MODE_PRIVATE)
    }
    val pinned = remember(actualRole, pinRevision) {
        prefs.getStringSet("pins:$actualRole", emptySet()).orEmpty().toSet()
    }
    val mailRevision by ResidentSocialMailbox.revisions.collectAsState()
    var mailReady by remember { mutableStateOf(false) }
    val mail by produceState<List<ResidentDirectMessage>>(
        initialValue = emptyList(), actualRole, mailRevision, mailReady,
    ) {
        value = if (mailReady && actualRole.isNotBlank()) withContext(Dispatchers.IO) {
            ResidentSocialMailbox.historyForInspection(actualRole)
        } else emptyList()
    }
    val threads = inspectedThreads(actualRole, allChats, mail, names)
        .sortedWith(compareByDescending<InspectedThread> { it.key in pinned }
            .thenByDescending(InspectedThread::updatedAt))
    val selectedThread = threads.firstOrNull { it.key == selectedThreadKey }
    val visiblePosts = remember(posts, actualRole, friendsRevision) {
        MomentFriendAccess.visiblePosts(actualRole, posts).sortedByDescending { it.createdAt }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun goBack() {
        when {
            selectedThreadKey != null -> selectedThreadKey = null
            else -> onBack()
        }
    }
    BackHandler { goBack() }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            ResidentSocialMailbox.initialize(context)
            MomentFriendAccess.initialize(context)
        }
        mailReady = true
    }

    Scaffold(
        containerColor = QqPage,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(selectedThread?.title ?: "查手机", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        if (selectedThread != null) Text(
                            "来自${names[actualRole] ?: "角色"}的手机",
                            fontSize = 11.sp, color = QqMuted,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::goBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (selectedThread == null) {
                        Box {
                            TextButton(onClick = { rolesExpanded = true }, enabled = roleOptions.isNotEmpty()) {
                                Text(names[actualRole] ?: "选择手机", maxLines = 1)
                                Icon(Icons.Outlined.ExpandMore, null)
                            }
                            DropdownMenu(expanded = rolesExpanded, onDismissRequest = { rolesExpanded = false }) {
                                roleOptions.forEach { person ->
                                    DropdownMenuItem(
                                        text = { Text(person.displayName + if (person.characterId.startsWith("resident:")) " · 居民" else "") },
                                        onClick = {
                                            roleId = person.characterId
                                            selectedThreadKey = null
                                            rolesExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = QqHeader),
            )
        },
        bottomBar = {
            if (selectedThread == null) NavigationBar(containerColor = QqHeader) {
                NavigationBarItem(
                    selected = phoneTab == 0, onClick = { phoneTab = 0 },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, label = { Text("消息") },
                )
                NavigationBarItem(
                    selected = phoneTab == 1, onClick = { phoneTab = 1 },
                    icon = { Icon(Icons.Outlined.DynamicFeed, null) }, label = { Text("朋友圈") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (actualRole.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有可以查看手机的数字人物", color = QqMuted)
                }
            } else if (selectedThread != null) {
                if (selectedThread.conversationId != null) {
                    val original = allChats.firstOrNull { it.id == selectedThread.conversationId }
                    if (original != null) {
                        val history by MigratedDomainStores.chat.messages(original.id).collectAsState()
                        InspectedOriginalChat(
                            roleId = actualRole,
                            conversation = original,
                            messages = history.filter { it.status == LuluChatMessage.Status.Sent },
                            names = names,
                            userName = UserProfileContext.displayLabel(),
                        )
                    }
                } else {
                    InspectedNativeChat(
                        roleId = actualRole,
                        messages = mail.filter {
                            (it.fromId == actualRole && it.toId == selectedThread.peerId) ||
                                (it.toId == actualRole && it.fromId == selectedThread.peerId)
                        },
                        names = names,
                    )
                }
            } else if (phoneTab == 0) {
                if (threads.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有真实的聊天记录", color = QqMuted)
                    }
                } else LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(threads, key = InspectedThread::key) { thread ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { selectedThreadKey = thread.key }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = QqOther, shape = CircleShape, modifier = Modifier.size(46.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(thread.title.take(1).ifBlank { "聊" }, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(thread.title, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(inspectedTime(thread.updatedAt), color = QqMuted, fontSize = 11.sp)
                                }
                                Text(thread.lastText.ifBlank { "暂无消息" }, color = QqMuted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            }
                            IconButton(onClick = {
                                val next = pinned.toMutableSet().apply {
                                    if (!add(thread.key)) remove(thread.key)
                                }
                                if (prefs.edit().putStringSet("pins:$actualRole", next).commit()) pinRevision++
                            }) {
                                Icon(if (thread.key in pinned) Icons.Outlined.PushPin else Icons.Outlined.MoreHoriz,
                                    if (thread.key in pinned) "取消置顶" else "置顶",
                                    tint = if (thread.key in pinned) QqMine else QqMuted,
                                    modifier = Modifier.size(19.dp))
                            }
                        }
                        HorizontalDivider(color = QqBorder.copy(alpha = .22f), modifier = Modifier.padding(start = 74.dp))
                    }
                }
            } else {
                if (visiblePosts.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有可以看到的好友动态", color = QqMuted)
                    }
                } else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(visiblePosts, key = MomentPost::id) { post ->
                        val author = if (post.authorType == MomentAuthorType.User)
                            UserProfileContext.displayLabel() else names[post.authorCharacterId].orEmpty()
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            colors = CardDefaults.cardColors(containerColor = QqHeader),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, QqBorder.copy(alpha = .3f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(author.ifBlank { "朋友" }, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f))
                                    Text(inspectedTime(post.createdAt), fontSize = 11.sp, color = QqMuted)
                                }
                                if (post.content.isNotBlank()) Text(post.content, fontSize = 15.sp)
                                post.imageUri?.takeIf(String::isNotBlank)?.let { uri ->
                                    MomentImageAttachment(uri, Modifier.fillMaxWidth().height(190.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val liked = actualRole in post.likedCharacterIds
                                    Text(
                                        post.likedCharacterIds.map { id ->
                                            if (id == "__user__") UserProfileContext.displayLabel() else names[id] ?: id
                                        }.joinToString("、").ifBlank { "暂无点赞" },
                                        color = QqMuted, fontSize = 12.sp,
                                        modifier = Modifier.weight(1f), maxLines = 2,
                                    )
                                    TextButton(onClick = {
                                        if (!MomentsStore.toggleInspectedCharacterLike(post.id, actualRole)) {
                                            scope.launch { snackbar.showSnackbar("操作未成功：可能已不是好友") }
                                        }
                                    }) {
                                        Icon(if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                            null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (liked) "取消赞" else "点赞")
                                    }
                                }
                                post.comments.forEach { comment ->
                                    Text(
                                        "${if (comment.characterId == "__user__") UserProfileContext.displayLabel()
                                            else names[comment.characterId] ?: "朋友"}：${comment.content}",
                                        fontSize = 12.sp, color = QqMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectedOriginalChat(
    roleId: String,
    conversation: LuluConversation,
    messages: List<LuluChatMessage>,
    names: Map<String, String>,
    userName: String,
) {
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无消息", color = QqMuted)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(messages.asReversed(), key = LuluChatMessage::id) { message ->
            val mine = inspectedChatMessageIsMine(roleId, conversation, message)
            val speaker = when (message.sender) {
                LuluChatMessage.Sender.User -> userName
                LuluChatMessage.Sender.System -> "系统"
                LuluChatMessage.Sender.Character -> {
                    val senderId = message.authorCharacterId
                        ?: conversation.characterId.takeIf { conversation.groupChat == null }
                    senderId?.let(names::get) ?: "群成员"
                }
            }
            InspectedBubble(mine, speaker, message.content, message.createdAt,
                system = message.sender == LuluChatMessage.Sender.System,
                detail = if (message.voiceNote) "语音消息 · 自动转写" else "")
        }
    }
}

@Composable
private fun InspectedNativeChat(
    roleId: String,
    messages: List<ResidentDirectMessage>,
    names: Map<String, String>,
) {
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无消息", color = QqMuted)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(messages.asReversed(), key = ResidentDirectMessage::id) { message ->
            InspectedBubble(
                mine = message.fromId == roleId,
                speaker = names[message.fromId] ?: message.fromId,
                content = message.content,
                at = message.createdAt,
                detail = if (message.author == "owner-on-behalf") "主人代发" else "",
            )
        }
    }
}

@Composable
private fun InspectedBubble(
    mine: Boolean, speaker: String, content: String, at: Instant,
    system: Boolean = false, detail: String = "",
) {
    if (system) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(content, color = QqMuted, fontSize = 12.sp)
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment =
        if (mine) Alignment.End else Alignment.Start) {
        Text("$speaker · ${inspectedTime(at)}" +
            if (detail.isBlank()) "" else " · $detail",
            color = QqMuted, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Surface(
            color = if (mine) QqMine else QqOther,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 305.dp),
        ) {
            val image = remember(content) { decodeQqChatImage(content) }
            if (image != null) {
                Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    MomentImageAttachment(image.imageUri, Modifier.widthIn(max = 275.dp).height(190.dp))
                    val imageText = buildString {
                        if (image.caption.isNotBlank()) append(image.caption)
                        val extraText = stripQqChatImageDirective(content)
                        if (extraText.isNotBlank() && extraText != image.caption) {
                            if (isNotBlank()) append("\n")
                            append(extraText)
                        }
                    }
                    if (imageText.isNotBlank()) Text(imageText,
                        color = if (mine) QqMineInk else QqInk,
                        fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
            } else {
                Text(
                    text = content,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    color = if (mine) QqMineInk else QqInk,
                    fontSize = 15.sp, lineHeight = 22.sp,
                )
            }
        }
    }
}
