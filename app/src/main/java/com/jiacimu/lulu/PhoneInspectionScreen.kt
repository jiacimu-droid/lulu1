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
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.design.LuluColors
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
    val lifeStates by CharacterLifeStore.states.collectAsState()
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
    val identity = people[actualRole]
    val ownPhoneLife = remember(actualRole, lifeStates) { CharacterLifeStore.state(actualRole) }
    val appearance = ownPhoneLife.optJSONObject("phoneAppearance")
    val coverPreset = appearance?.optString("momentsCover", "night").orEmpty().ifBlank { "night" }
    val coverPhotoPostId = appearance?.optString("momentsPhotoPostId").orEmpty()
    val coverPhoto = if (coverPreset == "photo") posts.firstOrNull {
        it.id == coverPhotoPostId && it.authorCharacterId == actualRole
    }?.imageUri else null
    var phoneTab by rememberSaveable { mutableIntStateOf(0) }
    var showRoleFavorites by rememberSaveable { mutableStateOf(false) }
    var selectedThreadKey by rememberSaveable { mutableStateOf<String?>(null) }
    var rolesExpanded by remember { mutableStateOf(false) }
    var pinRevision by remember { mutableIntStateOf(0) }
    var menuThreadKey by remember { mutableStateOf<String?>(null) }
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
    val userProfilePrefs = remember(context) {
        context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE)
    }
    val userName = userProfilePrefs.getString("display_name", "我").orEmpty().ifBlank { "我" }
    val userAvatar = userProfilePrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2)
    val userAvatarUri = userProfilePrefs.getString("avatar_uri", null)
    val availableFriends = remember(actualRole, friendsRevision, people) {
        people.values.filter { it.characterId != actualRole &&
            MomentFriendAccess.areFriends(actualRole, it.characterId)
        }.sortedBy(CharacterSettings::displayName)
    }


    fun goBack() {
        when {
            showRoleFavorites -> showRoleFavorites = false
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
                        Text(
                            when {
                                showRoleFavorites -> "我的收藏"
                                selectedThread != null -> selectedThread.title
                                else -> "聊天"
                            },
                            fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        )
                        if (selectedThread != null) Text(
                            names[actualRole] ?: "角色",
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
            if (selectedThread == null && !showRoleFavorites) {
                ChatHubV2Navigation(
                    selectedTab = phoneTab,
                    labels = listOf("消息", "好友", "朋友圈", "我的"),
                    onSelect = { phoneTab = it },
                    badgeCounts = mapOf(2 to CharacterPhoneInbox.unreadCount(actualRole)),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showRoleFavorites) {
                RolePhoneFavorites(actualRole)
            } else if (actualRole.isBlank()) {
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
                        val chat = allChats.firstOrNull { it.id == thread.conversationId }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                            ChatHubV2ConversationRow(
                                title = thread.title,
                                preview = thread.lastText,
                                updatedAt = thread.updatedAt,
                                avatarText = thread.title.take(1).ifBlank { "聊" },
                                avatarUri = if (thread.isGroup) chat?.groupChat?.avatarUri
                                    else people[thread.peerId]?.avatarUri,
                                isGroup = thread.isGroup,
                                pinned = thread.key in pinned,
                                unreadCount = 0, statusText = "", statusOnline = false,
                                onOpen = { selectedThreadKey = thread.key },
                                onMore = { menuThreadKey = thread.key },
                            )
                            DropdownMenu(expanded = menuThreadKey == thread.key,
                                onDismissRequest = { menuThreadKey = null }) {
                                DropdownMenuItem(
                                    text = { Text(if (thread.key in pinned) "取消置顶" else "置顶") },
                                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                                    onClick = {
                                        val next = pinned.toMutableSet().apply {
                                            if (!add(thread.key)) remove(thread.key)
                                        }
                                        if (prefs.edit().putStringSet("pins:$actualRole", next).commit())
                                            pinRevision++
                                        menuThreadKey = null
                                    },
                                )
                            }
                        }
                    }
                }
            } else if (phoneTab == 1) {
                LazyColumn(contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (MomentFriendAccess.areFriends(actualRole, MomentFriendAccess.OWNER)) {
                        item("owner-friend") {
                            ChatHubV2Card {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ChatHubV2Avatar(userAvatar, 50, userAvatarUri)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(userName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Text("好友", fontSize = 12.sp, color = QqMuted)
                                    }
                                }
                            }
                        }
                    }
                    items(availableFriends, key = CharacterSettings::characterId) { friend ->
                        ChatHubV2Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChatHubV2Avatar(friend.displayName.take(1), 50, friend.avatarUri)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(friend.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Text("好友", fontSize = 12.sp, color = QqMuted)
                                }
                            }
                        }
                    }
                    if (availableFriends.isEmpty() &&
                        !MomentFriendAccess.areFriends(actualRole, MomentFriendAccess.OWNER)) {
                        item { Text("还没有确认的好友", color = QqMuted) }
                    }
                }
            } else if (phoneTab == 2) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item("moments-header") {
                        MomentsHeader(
                            userName = identity?.displayName ?: "角色",
                            userAvatar = identity?.displayName?.take(1).orEmpty().ifBlank { "角" },
                            userAvatarUri = identity?.avatarUri,
                            coverUri = coverPhoto,
                            signature = ownPhoneLife.optJSONObject("socialNames")
                                ?.optString("selfNickname").orEmpty().ifBlank { "生活正在发生" },
                            coverPalette = rolePhoneCoverPalette(coverPreset),
                            onCoverClick = {}, onSignatureClick = {},
                            editable = false,
                        )
                    }
                    if (visiblePosts.isEmpty()) {
                        item("moments-empty") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center) {
                                Text("还没有可以看到的好友动态", color = QqMuted)
                            }
                        }
                    } else {
                        items(visiblePosts, key = MomentPost::id) { post ->
                            MomentPostCard(
                                post = post,
                                userName = userName, userAvatar = userAvatar,
                                userAvatarUri = userAvatarUri,
                                characterNames = names,
                                viewerId = actualRole,
                                allowDelete = false, allowComment = false,
                                onLike = {
                                    if (!MomentsStore.toggleInspectedCharacterLike(post.id, actualRole)) {
                                        scope.launch {
                                            snackbar.showSnackbar("操作未成功：可能已不是好友")
                                        }
                                    }
                                },
                                onComment = {}, onCallCharacters = {},
                                onReply = { _, _ -> }, onRetryImageUnderstanding = {},
                                onDelete = {},
                            )
                        }
                    }
                }
            } else {
                RolePhoneMyPage(
                    roleId = actualRole,
                    roleName = identity?.displayName ?: "角色",
                    roleAvatarUri = identity?.avatarUri,
                    onFavorites = { showRoleFavorites = true },
                )
            }
        }
    }
}

/** Skin presets are purely presentation; saved selection is role-owned in CharacterLifeStore. */
internal fun rolePhoneCoverPalette(preset: String): List<Color> = when (preset) {
    "sky" -> listOf(Color(0xFF436A98), Color(0xFF7EA5C7), Color(0xFFCEE3ED))
    "forest" -> listOf(Color(0xFF264E40), Color(0xFF628E75), Color(0xFFC9DDCB))
    "rose" -> listOf(Color(0xFF784F68), Color(0xFFBD8FA7), Color(0xFFF5D5DE))
    "ocean" -> listOf(Color(0xFF133A56), Color(0xFF38748A), Color(0xFF8EBEC5))
    "sunset" -> listOf(Color(0xFF674660), Color(0xFFD18E80), Color(0xFFF6D6AC))
    else -> listOf(Color(0xFF1F2329), Color(0xFF59616C), Color(0xFFC9CED2))
}

@Composable
private fun RolePhoneMyPage(
    roleId: String, roleName: String, roleAvatarUri: String?, onFavorites: () -> Unit,
) {
    val lifeStates by CharacterLifeStore.states.collectAsState()
    val life = remember(roleId, lifeStates) { CharacterLifeStore.state(roleId) }
    val socialName = life.optJSONObject("socialNames")
        ?.optString("selfNickname").orEmpty().ifBlank { roleName }
    val profile = life.optJSONObject("profile")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item("favorites") {
            Surface(onClick = onFavorites, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.StarOutline, null)
                    Spacer(Modifier.width(12.dp))
                    Text("我的收藏", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        item("profile") {
            ChatHubV2Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChatHubV2Avatar(roleName.take(1).ifBlank { "角" }, 78, roleAvatarUri)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(socialName, style = MaterialTheme.typography.titleLarge)
                        Text("个人资料", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = LuluColors.Border)
                Text("名字", color = LuluColors.Muted, fontSize = 12.sp)
                Text(roleName)
                val bio = profile?.optString("summary").orEmpty()
                if (bio.isNotBlank()) {
                    Text("个人介绍", fontWeight = FontWeight.SemiBold)
                    Text(bio)
                }
            }
        }
    }
}

@Composable
private fun RolePhoneFavorites(roleId: String) {
    // These are the original character-owned Favorite lexicon entries,
    // not the real user's collection, and contain no synthesized copies.
    val items = remember(roleId) {
        LuluRepositories.lexicon.snapshot(roleId)
            .filter { it.section == LexiconSection.Favorite }
            .sortedByDescending { it.updatedAt }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有收藏的消息", color = QqMuted)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { entry ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold)
                    Text(entry.content, lineHeight = 23.sp)
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
