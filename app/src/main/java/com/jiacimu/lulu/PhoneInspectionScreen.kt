package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import java.util.UUID
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
    // Session identity is not a second phone history store; real events live
    // only in the existing SharedExperienceTimeline.
    val inspectionSession = remember(actualRole) { UUID.randomUUID().toString() }
    var showRoleFavorites by rememberSaveable { mutableStateOf(false) }
    var showRoleCalendar by rememberSaveable { mutableStateOf(false) }
    var showRoleBrowser by rememberSaveable { mutableStateOf(false) }
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
    val roleMomentUnread = remember(actualRole, posts, friendsRevision, mailReady) {
        CharacterPhoneInbox.unreadCount(actualRole)
    }
    val mail by produceState<List<ResidentDirectMessage>>(
        initialValue = emptyList(), actualRole, mailRevision, mailReady,
    ) {
        value = if (mailReady && actualRole.isNotBlank()) withContext(Dispatchers.IO) {
            ResidentSocialMailbox.historyForInspection(actualRole)
        } else emptyList()
    }
    val threads = remember(actualRole, allChats, mail, names, pinned) {
        inspectedThreads(actualRole, allChats, mail, names)
            .sortedWith(compareByDescending<InspectedThread> { it.key in pinned }
                .thenByDescending(InspectedThread::updatedAt))
    }
    val selectedThread = threads.firstOrNull { it.key == selectedThreadKey }
        ?: selectedThreadKey?.takeIf { it.startsWith("mail:") }
            ?.removePrefix("mail:")?.takeIf { peer ->
                peer in people && ResidentSocialMailbox.canContact(actualRole, peer)
            }?.let { peer ->
                InspectedThread(
                    key = "mail:$peer", title = names[peer] ?: "好友",
                    lastText = "", updatedAt = Instant.EPOCH, peerId = peer,
                )
            }
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
            showRoleCalendar -> showRoleCalendar = false
            showRoleBrowser -> showRoleBrowser = false
            showRoleFavorites -> showRoleFavorites = false
            selectedThreadKey != null -> selectedThreadKey = null
            else -> onBack()
        }
    }
    BackHandler { goBack() }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            ResidentSocialMailbox.initialize(context)
            CharacterPhoneInbox.initialize(context)
            MomentFriendAccess.initialize(context)
        }
        mailReady = true
    }

    // A real owner-initiated visit becomes a witnessed observation for this
    // character's ordinary perception, not an immediate extra LLM call.
    // Keyed by meaningful navigation rather than recomposition or scrolling.
    LaunchedEffect(
        inspectionSession, actualRole, phoneTab, selectedThreadKey,
        showRoleCalendar, showRoleBrowser, showRoleFavorites, mailReady,
    ) {
        if (!mailReady || actualRole.isBlank()) return@LaunchedEffect
        val section = when {
            showRoleCalendar -> "日历与自己安排的行程"
            showRoleBrowser -> "浏览器和搜索记录"
            showRoleFavorites -> "收藏"
            selectedThread != null -> "与${selectedThread.title}的聊天"
            phoneTab == 1 -> "好友列表"
            phoneTab == 2 -> "朋友圈"
            phoneTab == 3 -> "个人资料与手机应用"
            else -> "消息列表"
        }
        withContext(Dispatchers.IO) {
            SharedExperienceTimeline.record(
                eventId = "owner-phone-visit-${UUID.randomUUID()}",
                characterId = actualRole,
                channel = "手机·查看痕迹",
                speaker = "主人",
                source = "owner-phone-inspection",
                sessionId = inspectionSession,
                content = "主人实际打开你的手机，查看了$section。手机确实被查看，但不知道主人为何查看；这不是你自己选择的操作。",
                occurredAt = Instant.now(),
                triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation,
            )
        }
    }

    if (showRoleCalendar) {
        ScheduleFeatureScreen(
            onBack = { showRoleCalendar = false },
            initialCharacterId = actualRole,
            personal = true,
        )
        return
    }

    Scaffold(
        containerColor = QqPage,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectedThread != null && !showRoleFavorites && !showRoleBrowser) {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!selectedThread.isGroup) {
                                LuluProfileAvatar(
                                    imageUri = selectedThread.peerId?.let { people[it]?.avatarUri }
                                        ?: userAvatarUri,
                                    fallback = selectedThread.title.take(1), size = 42,
                                )
                                Spacer(Modifier.width(9.dp))
                            }
                            Text(selectedThread.title, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = ::goBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = QqHeader),
                )
            } else {
                MigratedChatTopBar(
                    title = when {
                        showRoleFavorites -> "我的收藏"
                        showRoleBrowser -> "浏览器"
                        else -> when (phoneTab) {
                            1 -> "好友"
                            2 -> "朋友圈"
                            3 -> "我的"
                            else -> "消息"
                        }
                    },
                    onBack = ::goBack,
                    actions = {
                        if (!showRoleFavorites && !showRoleBrowser) Box {
                            TextButton(onClick = { rolesExpanded = true },
                                enabled = roleOptions.isNotEmpty()) {
                                Text(names[actualRole] ?: "选择手机", maxLines = 1)
                                Icon(Icons.Outlined.ExpandMore, null)
                            }
                            DropdownMenu(expanded = rolesExpanded,
                                onDismissRequest = { rolesExpanded = false }) {
                                roleOptions.forEach { person ->
                                    DropdownMenuItem(
                                        text = { Text(person.displayName +
                                            if (person.characterId.startsWith("resident:")) " · 居民" else "") },
                                        onClick = {
                                            roleId = person.characterId
                                            selectedThreadKey = null
                                            rolesExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selectedThread != null && !showRoleFavorites && !showRoleBrowser) {
                RolePhoneOnBehalfComposer(
                    roleName = identity?.displayName ?: "角色",
                    roleId = actualRole, thread = selectedThread,
                    onSend = { text ->
                        runCatching {
                            rolePhoneSendOnBehalf(actualRole, selectedThread, text, allChats)
                        }.onFailure { error ->
                            scope.launch { snackbar.showSnackbar(error.message ?: "发送失败") }
                        }.isSuccess
                    },
                )
            } else if (selectedThread == null && !showRoleFavorites && !showRoleBrowser) {
                ChatHubV2Navigation(
                    selectedTab = phoneTab,
                    labels = listOf("消息", "好友", "朋友圈", "我的"),
                    onSelect = { phoneTab = it },
                    badgeCounts = mapOf(2 to roleMomentUnread),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showRoleBrowser) {
                RolePhoneBrowserHistory(actualRole)
            } else if (showRoleFavorites) {
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
                LazyColumn(
                    contentPadding = PaddingValues(top = 12.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    item("phone-summary") {
                        RolePhoneHomeHeader(
                            name = identity?.displayName ?: "角色",
                            avatarUri = identity?.avatarUri,
                            threads = threads.size,
                            friends = availableFriends.size,
                            groups = allChats.count {
                                it.groupChat?.members?.any { member -> member.characterId == actualRole } == true
                            },
                        )
                    }
                    if (threads.isEmpty()) item("phone-empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 54.dp),
                            contentAlignment = Alignment.Center) {
                            Text("还没有聊天，生活故事从这里开始", color = QqMuted)
                        }
                    }
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item("friends-heading") {
                        Column(Modifier.padding(bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("联系人", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("${availableFriends.size + if (MomentFriendAccess.areFriends(actualRole, MomentFriendAccess.OWNER)) 1 else 0} 位好友",
                                color = QqMuted, fontSize = 12.sp)
                        }
                    }
                    if (MomentFriendAccess.areFriends(actualRole, MomentFriendAccess.OWNER)) {
                        item("owner-friend") {
                            Box(Modifier.clickable {
                                allChats.firstOrNull { it.groupChat == null && it.characterId == actualRole }
                                    ?.let { selectedThreadKey = "chat:${it.id}" }
                            }) {
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
                    }
                    items(availableFriends, key = CharacterSettings::characterId) { friend ->
                        Box(Modifier.clickable {
                            selectedThreadKey = "mail:${friend.characterId}"
                        }) {
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
                    onCalendar = { showRoleCalendar = true },
                    onBrowser = { showRoleBrowser = true },
                )
            }
        }
    }
}

@Composable
private fun RolePhoneHomeHeader(
    name: String, avatarUri: String?, threads: Int, friends: Int, groups: Int,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(
                    Color(0xFF303A48), Color(0xFF566474), Color(0xFF788595))))
                .padding(19.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatHubV2Avatar(name.take(1).ifBlank { "角" }, 56, avatarUri)
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp, color = Color.White)
                        Text("私人的小小世界", fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.75f))
                    }
                    Icon(Icons.Outlined.Smartphone, null,
                        tint = Color.White.copy(alpha = 0.72f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    RolePhoneMetric("聊天", threads)
                    RolePhoneMetric("好友", friends)
                    RolePhoneMetric("群聊", groups)
                }
            }
        }
        Text("最近消息", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp), color = LuluColors.Muted)
    }
}

@Composable
private fun RolePhoneMetric(label: String, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(count.toString(), fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.72f))
    }
}

/** A user-triggered real send, attributed in the original evidence timeline to
 * the owner's intervention. Never route a forged action through the character's
 * autonomy planner or claim that the person decided to send it. */
internal fun rolePhoneSendOnBehalf(
    roleId: String, thread: InspectedThread, raw: String,
    conversations: List<LuluConversation>,
): String {
    require(roleId.isNotBlank()) { "先选择角色的手机" }
    val words = raw.trim()
    require(words.isNotBlank() && words.length <= 650) { "消息需要填写，最多650字" }
    if (thread.conversationId == null) {
        val recipient = thread.peerId.orEmpty()
        val sent = ResidentSocialMailbox.send(
            roleId, recipient, words, author = "owner-on-behalf",
        )
        return sent.id
    }
    val conversation = conversations.firstOrNull { it.id == thread.conversationId }
        ?: error("原会话已不存在")
    val members = conversation.groupChat?.members?.map { it.characterId }
    if (members == null) require(conversation.characterId == roleId) {
        "这不是当前角色的私聊"
    } else require(roleId in members) { "这位角色并未加入这个群" }
    val sent = MigratedDomainStores.chat.appendCharacterMessage(
        conversation.id, words, roleId, onBehalfByOwner = true,
    )
    // The message itself persists the origin and projects one single actual
    // timeline event for all participants. No duplicate synthetic audit event.
    return sent.id
}

@Composable
private fun RolePhoneOnBehalfComposer(
    roleName: String, roleId: String, thread: InspectedThread,
    onSend: (String) -> Boolean,
) {
    var draft by rememberSaveable(roleId, thread.key) { mutableStateOf("") }
    Surface(color = LuluColors.Card) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = LuluColors.Muted,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("主人代${roleName}发送", fontSize = 11.sp, color = LuluColors.Muted)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(650) },
                    placeholder = { Text("发送消息…") }, maxLines = 4,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    onClick = { if (onSend(draft)) draft = "" },
                    enabled = draft.isNotBlank(),
                ) { Text("发送") }
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
    roleId: String, roleName: String, roleAvatarUri: String?,
    onFavorites: () -> Unit, onCalendar: () -> Unit, onBrowser: () -> Unit,
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
        item("profile-hero") {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(
                        Color(0xFF303A48), Color(0xFF596474), Color(0xFF747D8D))))
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    ChatHubV2Avatar(socialName.take(1).ifBlank { "角" }, 68, roleAvatarUri)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(socialName, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = Color.White)
                        Text("我的个人主页", fontSize = 12.sp, color = Color.White.copy(alpha = 0.72f))
                    }
                }
            }
        }
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
        item("calendar") {
            RolePhoneMyLink(Icons.Outlined.EventNote, "日历", onCalendar)
        }
        item("browser") {
            RolePhoneMyLink(Icons.Outlined.TravelExplore, "浏览器", onBrowser)
        }
        item("remark") {
            ChatHubV2Card {
                Text("给主人设置的备注", fontWeight = FontWeight.SemiBold)
                Text(life.optJSONObject("socialNames")?.optString("userRemark")
                    .orEmpty().ifBlank { "还没有设置" })
            }
        }
        item("profile") {
            ChatHubV2Card {
                Text("个人资料", style = MaterialTheme.typography.titleMedium)
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
private fun RolePhoneMyLink(icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null)
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
                detail = listOfNotNull(
                    "主人代发".takeIf { message.onBehalfByOwner },
                    "语音消息 · 自动转写".takeIf { message.voiceNote },
                ).joinToString(" · "))
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
    // Same QQ detail-column geometry as the live chat; inspection never
    // dispatches tap, swipe, regenerate, poke, or other real-person actions.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
            if (!mine) QqAvatar(speaker.take(1).ifBlank { "角" }, 42)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Text("$speaker · ${inspectedTime(at)}" +
                if (detail.isBlank()) "" else " · $detail",
                color = QqMuted, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
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
                } else Text(
                    text = stripCharacterReplyDirective(content),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (mine) QqMineInk else QqInk,
                    fontSize = 15.sp, lineHeight = 22.sp,
                )
            }
        }
    }
}
