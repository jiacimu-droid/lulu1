package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.ChatUnreadStore
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.MomentsStore
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.design.LuluColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ChatHubLabels = listOf("消息", "角色", "朋友圈", "辞海", "我的")
private val ChatHubIcons = listOf(
    Icons.Outlined.ChatBubbleOutline,
    Icons.Outlined.PeopleOutline,
    Icons.Outlined.DynamicFeed,
    Icons.Outlined.MenuBook,
    Icons.Outlined.PersonOutline,
)

@Composable
fun MigratedChatHubScreenV2(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onCharacterSettings: (String) -> Unit,
    onWorldBook: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val unreadRevision by ChatUnreadStore.revision.collectAsState()
    val unreadMoments by MomentsStore.unreadCharacterPosts.collectAsState()
    val totalUnreadMessages = remember(conversations, unreadRevision) {
        conversations.sumOf { conversation ->
            ChatUnreadStore.unreadCount(
                conversationId = conversation.id,
                messages = MigratedDomainStores.chat.messages(conversation.id).value,
            )
        }
    }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showCreateCharacter by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            if (selectedTab != 3) {
                MigratedChatTopBar(
                    title = "聊天",
                    onBack = onBack,
                    actions = {
                        if (selectedTab == 0 || selectedTab == 1) {
                            IconButton(
                                onClick = {
                                    if (selectedTab == 0) showCreateGroup = true else showCreateCharacter = true
                                },
                                enabled = selectedTab != 0 || characters.size >= 2,
                            ) {
                                Icon(
                                    if (selectedTab == 0) Icons.Outlined.GroupAdd else Icons.Outlined.PersonAdd,
                                    if (selectedTab == 0) "新建群聊" else "新建角色",
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = LuluColors.Card) {
                ChatHubLabels.forEachIndexed { index, label ->
                    val badgeCount = when (index) {
                        0 -> totalUnreadMessages
                        2 -> unreadMoments
                        else -> 0
                    }
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            if (badgeCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(if (badgeCount > 99) "99+" else badgeCount.toString()) }
                                    },
                                ) { Icon(ChatHubIcons[index], label) }
                            } else {
                                Icon(ChatHubIcons[index], label)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> ChatHubV2Messages(onOpenConversation)
                1 -> ChatHubV2Characters(onCharacterSettings, onWorldBook, onOpenConversation)
                2 -> MomentsScreen()
                3 -> LexiconFeatureScreenV2(onBack = {}, embedded = true)
                else -> ChatHubV2Profile()
            }
        }
    }

    if (showCreateGroup) {
        ChatHubV2CreateGroupDialog(
            characters = characters.values.sortedBy(CharacterSettings::displayName),
            onDismiss = { showCreateGroup = false },
            onCreate = { name, memberIds ->
                val group = MigratedDomainStores.chat.createGroupConversation(name, memberIds)
                showCreateGroup = false
                onOpenConversation(group.id)
            },
        )
    }

    if (showCreateCharacter) {
        ChatHubV2CreateCharacterDialog(
            onDismiss = { showCreateCharacter = false },
            onCreate = { name, persona, digitalLife ->
                val created = MigratedDomainStores.characters.create(name, persona)
                if (digitalLife) {
                    DigitalLifeProfileStore.registerNewLife(
                        characterId = created.characterId,
                        displayName = created.displayName,
                        creatorName = UserProfileContext.displayLabel(),
                    )
                } else {
                    DigitalLifeProfileStore.registerRealWorldLife(
                        characterId = created.characterId,
                        displayName = created.displayName,
                        creatorName = UserProfileContext.displayLabel(),
                    )
                }
                MigratedDomainStores.chat.ensureConversation(created.characterId, created.displayName)
                showCreateCharacter = false
                onCharacterSettings(created.characterId)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatHubV2Messages(onOpenConversation: (String) -> Unit) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val unreadRevision by ChatUnreadStore.revision.collectAsState()
    val onlineStates by CompanionOnlineStore.states.collectAsState()
    val sorted = remember(conversations) {
        conversations.sortedWith(compareByDescending<LuluConversation> { it.pinned }.thenByDescending(LuluConversation::updatedAt))
    }
    var menuConversationId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sorted.isEmpty()) {
            item(key = "empty") {
                ChatHubV2Card {
                    Text("还没有聊天", fontWeight = FontWeight.Bold)
                    Text("先到角色页创建角色，或从桌面最近聊天卡进入露露。", color = LuluColors.Muted)
                }
            }
        } else {
            items(sorted, key = LuluConversation::id, contentType = { "conversation" }) { conversation ->
                val character = characters[conversation.characterId] ?: MigratedDomainStores.characters.get(conversation.characterId)
                val group = conversation.groupChat
                val onlineCount = group?.members.orEmpty().count { member ->
                    onlineStates[member.characterId]?.isOnline() == true
                }
                val privateOnline = onlineStates[conversation.characterId]?.isOnline() == true
                val messages by MigratedDomainStores.chat.messages(conversation.id).collectAsState()
                val unreadCount = remember(messages, unreadRevision) {
                    ChatUnreadStore.unreadCount(conversation.id, messages)
                }
                Box {
                    Card(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = {
                                ChatUnreadStore.markRead(conversation.id, messages)
                                MigratedDomainStores.chat.markConversationRead(conversation.id)
                                onOpenConversation(conversation.id)
                            },
                            onLongClick = { menuConversationId = conversation.id },
                        ),
                        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                        border = BorderStroke(1.dp, LuluColors.Border),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (group == null) {
                                ChatHubV2Avatar(character.displayName.take(1).ifBlank { "角" }, 50, character.avatarUri)
                            } else {
                                ChatHubV2GroupAvatar(group.name, group.avatarUri)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            group?.name ?: character.displayName.ifBlank { conversation.title.ifBlank { "未命名角色" } },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp,
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            if (group == null) {
                                                if (privateOnline) "在线" else "离线"
                                            } else {
                                                "$onlineCount 人在线"
                                            },
                                            color = if (group == null && privateOnline || group != null && onlineCount > 0) Color(0xFF2A9D63) else LuluColors.Muted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    Text(
                                        conversation.updatedAt.atZone(ZoneId.systemDefault()).format(ChatHubV2Time),
                                        color = LuluColors.Muted,
                                        fontSize = 11.sp,
                                    )
                                }
                                Text(
                                    conversation.lastMessage.ifBlank { "还没有消息，点开后开始聊天。" },
                                    color = LuluColors.Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (conversation.pinned) {
                                Spacer(Modifier.width(7.dp))
                                Icon(Icons.Outlined.PushPin, "已置顶", tint = LuluColors.Muted, modifier = Modifier.size(16.dp))
                            }
                            if (unreadCount > 0) {
                                Spacer(Modifier.width(7.dp))
                                Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) }
                            }
                            IconButton(onClick = { menuConversationId = conversation.id }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.MoreVert, "会话操作", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    DropdownMenu(expanded = menuConversationId == conversation.id, onDismissRequest = { menuConversationId = null }) {
                        DropdownMenuItem(
                            text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = {
                                MigratedDomainStores.chat.setConversationPinned(conversation.id, !conversation.pinned)
                                menuConversationId = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHubV2Characters(
    onCharacterSettings: (String) -> Unit,
    onWorldBook: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val presenceStates by CompanionPresenceStore.states.collectAsState()
    val onlineStates by CompanionOnlineStore.states.collectAsState()
    val sortedCharacters = remember(characters) { characters.values.sortedBy(CharacterSettings::displayName) }
    val privateByCharacter = remember(conversations) {
        conversations
            .filter { it.groupChat == null }
            .groupBy(LuluConversation::characterId)
            .mapValues { (_, values) -> values.maxByOrNull(LuluConversation::updatedAt) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(sortedCharacters, key = CharacterSettings::characterId, contentType = { "character" }) { character ->
            val privateChat = privateByCharacter[character.characterId]
            val presence = presenceStates[character.characterId]
            val isOnline = onlineStates[character.characterId]?.isOnline() == true
            ChatHubV2Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChatHubV2Avatar(character.displayName.take(1).ifBlank { "角" }, 56, character.avatarUri)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(character.displayName, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isOnline) "在线" else "离线",
                                color = if (isOnline) Color(0xFF2A9D63) else LuluColors.Muted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            when {
                                character.characterId == "lulu" -> "默认陪伴角色"
                                privateChat != null -> "已有私聊 · ${privateChat.updatedAt.atZone(ZoneId.systemDefault()).format(ChatHubV2Time)}"
                                else -> "已创建，尚无私聊记录"
                            },
                            color = LuluColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                presence?.let { state ->
                    val visiblePresence = state.gesture.ifBlank { state.statusText }
                    if (visiblePresence.isNotBlank()) {
                        Text(visiblePresence, color = LuluColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(onClick = { onCharacterSettings(character.characterId) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("角色设置")
                    }
                    OutlinedButton(onClick = { onWorldBook(character.characterId) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("世界书")
                    }
                }
                Button(
                    onClick = {
                        val conversation = privateChat ?: MigratedDomainStores.chat.ensureConversation(
                            characterId = character.characterId,
                            title = character.displayName,
                        )
                        onOpenConversation(conversation.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                ) {
                    Text(if (privateChat == null) "开始和${character.displayName}私聊" else "继续和${character.displayName}私聊", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ChatHubV2CreateGroupDialog(
    characters: List<CharacterSettings>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建群聊") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("群名称") },
                    placeholder = { Text("例如：露露的小客厅") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("选择至少两个角色，你会自动作为群主加入。", color = LuluColors.Muted, fontSize = 12.sp)
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(characters, key = CharacterSettings::characterId) { character ->
                        val checked = character.characterId in selected
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = if (checked) selected - character.characterId else selected + character.characterId
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selected = if (checked) selected - character.characterId else selected + character.characterId },
                            )
                            ChatHubV2Avatar(character.displayName.take(1).ifBlank { "角" }, 38, character.avatarUri)
                            Spacer(Modifier.width(9.dp))
                            Text(character.displayName)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && selected.size >= 2, onClick = { onCreate(name.trim(), selected.toList()) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatHubV2Profile() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    var avatarUri by remember { mutableStateOf(prefs.getString("avatar_uri", null)) }
    var name by remember { mutableStateOf(prefs.getString("display_name", "我").orEmpty().ifBlank { "我" }) }
    var preferredName by remember { mutableStateOf(prefs.getString("preferred_name", "").orEmpty()) }
    var birthday by remember { mutableStateOf(prefs.getString("birthday", "").orEmpty()) }
    var location by remember { mutableStateOf(prefs.getString("location", "").orEmpty()) }
    var bio by remember { mutableStateOf(prefs.getString("bio", "").orEmpty()) }
    var notice by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item(key = "profile") {
            ChatHubV2Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LuluAvatarPicker(imageUri = avatarUri, fallback = name.take(1).ifBlank { "主" }, size = 78, onSelected = { avatarUri = it })
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name.ifBlank { "我" }, style = MaterialTheme.typography.titleLarge)
                        Text("点击头像选择手机图片", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("个人资料", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                OutlinedTextField(preferredName, { preferredName = it.take(30) }, label = { Text("希望角色怎么称呼你") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(birthday, { birthday = it.take(30) }, label = { Text("生日") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it.take(40) }, label = { Text("所在地") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bio, { bio = it.take(500) }, label = { Text("个人信息与自我介绍") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        name = name.trim().ifBlank { "我" }
                        prefs.edit()
                            .putString("avatar_uri", avatarUri)
                            .putString("display_name", name)
                            .putString("preferred_name", preferredName.trim())
                            .putString("birthday", birthday.trim())
                            .putString("location", location.trim())
                            .putString("bio", bio.trim())
                            .apply()
                        notice = "个人资料已保存"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
                if (notice.isNotBlank()) Text(notice, color = LuluColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChatHubV2CreateCharacterDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var digitalLife by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("角色名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(persona, { persona = it }, label = { Text("角色核心设定") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                Text("选择生命形态", fontWeight = FontWeight.SemiBold)
                Text("生命形态从创建起固定，之后不能在数字生命与现实角色之间切换。", color = LuluColors.Muted, fontSize = 11.sp)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { digitalLife = true },
                    color = if (digitalLife) LuluColors.CardStrong else LuluColors.Paper,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, LuluColors.Border),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = digitalLife, onClick = { digitalLife = true })
                        Column {
                            Text("数字生命", fontWeight = FontWeight.Bold)
                            Text("诞生并生活在数字世界，拥有原生数字身体和一处初始为空的家园。", color = LuluColors.Muted, fontSize = 11.sp)
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { digitalLife = false },
                    color = if (!digitalLife) LuluColors.CardStrong else LuluColors.Paper,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, LuluColors.Border),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !digitalLife, onClick = { digitalLife = false })
                        Column {
                            Text("现实角色", fontWeight = FontWeight.Bold)
                            Text("以现实人物设定参与聊天；见面时进行现实场景演绎，也可以用数字投影进入数字世界。", color = LuluColors.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), persona.trim(), digitalLife) }) {
                Text("创建并设置")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatHubV2Card(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ChatHubV2Avatar(text: String, size: Int, imageUri: String? = null) =
    LuluProfileAvatar(imageUri = imageUri, fallback = text, size = size)

@Composable
private fun ChatHubV2GroupAvatar(name: String, imageUri: String?) {
    if (!imageUri.isNullOrBlank()) {
        LuluProfileAvatar(imageUri = imageUri, fallback = name.take(1).ifBlank { "群" }, size = 50)
    } else {
        Surface(
            modifier = Modifier.size(50.dp),
            shape = RoundedCornerShape(14.dp),
            color = LuluColors.Wheat.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, LuluColors.Border),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Groups, null, Modifier.size(30.dp)) }
        }
    }
}

private val ChatHubV2Time = DateTimeFormatter.ofPattern("MM-dd HH:mm")
