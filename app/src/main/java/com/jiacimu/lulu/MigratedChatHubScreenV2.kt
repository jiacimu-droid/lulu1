package com.jiacimu.lulu

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ChatHubLabels = listOf("消息", "角色", "朋友圈", "我的")
private val ChatHubIcons = listOf(
    Icons.Outlined.ChatBubbleOutline,
    Icons.Outlined.PeopleOutline,
    Icons.Outlined.DynamicFeed,
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

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = { MigratedChatTopBar("聊天", onBack) },
        bottomBar = {
            NavigationBar(containerColor = LuluColors.Card) {
                ChatHubLabels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(ChatHubIcons[index], label) },
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
                2 -> MomentsPlaceholderScreen()
                else -> ChatHubV2Profile(onOpenSettings)
            }
        }
    }
}

@Composable
private fun ChatHubV2Messages(onOpenConversation: (String) -> Unit) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val sorted = remember(conversations) { conversations.sortedByDescending(LuluConversation::updatedAt) }
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
                val character = characters[conversation.characterId]
                    ?: MigratedDomainStores.characters.get(conversation.characterId)
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        MigratedDomainStores.chat.markConversationRead(conversation.id)
                        onOpenConversation(conversation.id)
                    },
                    colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                    border = BorderStroke(1.dp, LuluColors.Border),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatHubV2Avatar(character.displayName.take(1).ifBlank { "角" }, 50)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    conversation.title.ifBlank { character.displayName },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                )
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
                            if (conversation.parentConversationId != null) {
                                Text("聊天分支", color = LuluColors.Muted, fontSize = 10.sp)
                            }
                        }
                        if (conversation.unreadCount > 0) {
                            Spacer(Modifier.width(7.dp))
                            Badge { Text(conversation.unreadCount.toString()) }
                        }
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
    val sortedCharacters = remember(characters) { characters.values.sortedBy { it.displayName } }
    val recentByCharacter = remember(conversations) {
        conversations.groupBy(LuluConversation::characterId)
            .mapValues { (_, values) -> values.maxByOrNull(LuluConversation::updatedAt) }
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledIconButton(
                    onClick = { showCreateDialog = true },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = LuluColors.Wheat,
                        contentColor = LuluColors.OnWheat,
                    ),
                ) { Icon(Icons.Outlined.Add, "新建角色") }
            }
        }
        items(sortedCharacters, key = { it.characterId }, contentType = { "character" }) { character ->
            val recent = recentByCharacter[character.characterId]
            ChatHubV2Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChatHubV2Avatar(character.displayName.take(1).ifBlank { "角" }, 56)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(character.displayName, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(
                            when {
                                character.characterId == "lulu" -> "默认陪伴角色"
                                recent != null -> "已有聊天 · ${recent.updatedAt.atZone(ZoneId.systemDefault()).format(ChatHubV2Time)}"
                                else -> "已创建，尚无聊天记录"
                            },
                            color = LuluColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(
                        onClick = { onCharacterSettings(character.characterId) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("角色设置")
                    }
                    OutlinedButton(
                        onClick = { onWorldBook(character.characterId) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("世界书")
                    }
                }
                Button(
                    onClick = {
                        val conversation = recent ?: MigratedDomainStores.chat.ensureConversation(
                            characterId = character.characterId,
                            title = character.displayName,
                        )
                        onOpenConversation(conversation.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuluColors.Wheat,
                        contentColor = LuluColors.OnWheat,
                    ),
                ) {
                    Text(
                        if (recent == null) "开始和${character.displayName}聊天" else "继续和${character.displayName}聊天",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        ChatHubV2CreateCharacterDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, persona ->
                val created = MigratedDomainStores.characters.create(name, persona)
                MigratedDomainStores.chat.ensureConversation(created.characterId, created.displayName)
                showCreateDialog = false
                onCharacterSettings(created.characterId)
            },
        )
    }
}

@Composable
private fun ChatHubV2Profile(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    var avatar by remember { mutableStateOf(prefs.getString("avatar_text", "主").orEmpty().ifBlank { "主" }) }
    var name by remember { mutableStateOf(prefs.getString("display_name", "主人").orEmpty().ifBlank { "主人" }) }
    var notice by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item(key = "profile") {
            ChatHubV2Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChatHubV2Avatar(avatar.take(2).ifBlank { "主" }, 62)
                    Spacer(Modifier.width(14.dp))
                    Text(name.ifBlank { "主人" }, style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = avatar,
                    onValueChange = { avatar = it.take(2) },
                    label = { Text("头像") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PersonOutline, null)
                    Spacer(Modifier.width(6.dp))
                    Text("个人设置")
                }
                Button(
                    onClick = {
                        avatar = avatar.trim().ifBlank { "主" }
                        name = name.trim().ifBlank { "主人" }
                        prefs.edit().putString("avatar_text", avatar).putString("display_name", name).apply()
                        notice = "个人资料已保存"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                ) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
                if (notice.isNotBlank()) Text(notice, color = LuluColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChatHubV2CreateCharacterDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text("角色核心设定") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), persona.trim()) },
            ) { Text("创建并设置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatHubV2Stat(value: String, label: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = LuluColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChatHubV2Card(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun ChatHubV2Avatar(text: String, size: Int) {
    Surface(shape = CircleShape, color = LuluColors.WheatSoft, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold, fontSize = (size / 2.65).sp, color = LuluColors.Ink)
        }
    }
}

private val ChatHubV2Time = DateTimeFormatter.ofPattern("MM-dd HH:mm")
