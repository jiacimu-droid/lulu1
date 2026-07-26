package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MilestonePaper = Color(0xFFFFFDF7)
private val MilestoneCard = Color(0xFFFFFBF1)
private val MilestoneWheat = Color(0xFFF4D57D)
private val MilestoneInk = Color(0xFF343434)
private val MilestoneMuted = Color(0xFF6D7888)
private val MilestoneBorder = Color(0xFFEAE0CC)

private enum class LuluRoute {
    Home, Chat, ChatDetail, CharacterSettings, Memory, Lexicon, WorldBook,
    Performance, Reading, Wishes, Study, Games, Settings, AppearanceSettings,
    ChatSettings, ModelSettings, NotificationSettings, DataSettings, PermissionSettings,
}

private enum class ChatSection { Messages, Characters, Moments, Me }
private data class LauncherItem(val title: String, val icon: ImageVector, val route: LuluRoute)

@Composable
fun LuluMilestoneApp() {
    var route by remember { mutableStateOf(LuluRoute.Home) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = MilestoneWheat,
            onPrimary = MilestoneInk,
            background = MilestonePaper,
            surface = MilestoneCard,
            onSurface = MilestoneInk,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MilestonePaper) {
            when (route) {
                LuluRoute.Home -> MilestoneHome { route = it }
                LuluRoute.Chat -> MilestoneChat(
                    onBack = { route = LuluRoute.Home },
                    onOpenConversation = { route = LuluRoute.ChatDetail },
                    onOpenCharacterSettings = { route = LuluRoute.CharacterSettings },
                )
                LuluRoute.ChatDetail -> RepositoryChatScreen { route = LuluRoute.Chat }
                LuluRoute.CharacterSettings -> CharacterSettingsScreen { route = LuluRoute.Chat }
                LuluRoute.Memory -> MemoryFeatureScreen { route = LuluRoute.Home }
                LuluRoute.Lexicon -> LexiconFeatureScreen { route = LuluRoute.Home }
                LuluRoute.WorldBook -> CharacterWorldBookScreen { route = LuluRoute.Home }
                LuluRoute.Performance -> PerformanceFeatureScreen { route = LuluRoute.Home }
                LuluRoute.Settings -> MilestoneSettings(
                    onBack = { route = LuluRoute.Home },
                    onOpen = { route = it },
                )
                LuluRoute.AppearanceSettings -> SettingsDetailScreen(
                    title = "外观",
                    rows = listOf("暖纸极简主题" to true, "跟随系统深色模式" to false, "减少页面动效" to false),
                    onBack = { route = LuluRoute.Settings },
                )
                LuluRoute.ChatSettings -> SettingsDetailScreen(
                    title = "聊天",
                    rows = listOf("流式显示回复" to true, "显示消息时间" to true, "自动生成对话建议" to false),
                    onBack = { route = LuluRoute.Settings },
                )
                LuluRoute.ModelSettings -> ModelSettingsScreen { route = LuluRoute.Settings }
                LuluRoute.NotificationSettings -> SettingsDetailScreen(
                    title = "通知与主动联系",
                    rows = listOf("允许角色主动消息" to true, "由角色自适应频率" to true, "夜间勿扰" to true),
                    onBack = { route = LuluRoute.Settings },
                )
                LuluRoute.DataSettings -> SettingsDetailScreen(
                    title = "数据",
                    rows = listOf("自动备份角色数据" to false, "保留性能日志" to true, "导出前包含记忆" to true),
                    onBack = { route = LuluRoute.Settings },
                )
                LuluRoute.PermissionSettings -> SettingsDetailScreen(
                    title = "应用与权限",
                    rows = listOf("通知权限" to true, "麦克风权限" to false, "后台运行权限" to false),
                    onBack = { route = LuluRoute.Settings },
                )
                else -> MilestonePlaceholder(route) { route = LuluRoute.Home }
            }
        }
    }
}

@Composable
private fun MilestoneHome(onOpen: (LuluRoute) -> Unit) {
    val launchers = listOf(
        LauncherItem("聊天", Icons.Outlined.ChatBubbleOutline, LuluRoute.Chat),
        LauncherItem("记忆", Icons.Outlined.Psychology, LuluRoute.Memory),
        LauncherItem("辞海", Icons.Outlined.MenuBook, LuluRoute.Lexicon),
        LauncherItem("世界书", Icons.Outlined.Public, LuluRoute.WorldBook),
        LauncherItem("性能监测", Icons.Outlined.MonitorHeart, LuluRoute.Performance),
        LauncherItem("阅读", Icons.Outlined.AutoStories, LuluRoute.Reading),
        LauncherItem("心愿馆", Icons.Outlined.StarOutline, LuluRoute.Wishes),
        LauncherItem("考研", Icons.Outlined.School, LuluRoute.Study),
        LauncherItem("游戏", Icons.Outlined.SportsEsports, LuluRoute.Games),
        LauncherItem("设置", Icons.Outlined.Settings, LuluRoute.Settings),
    )
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val latest = conversations.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("今天", color = MilestoneMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("欢迎回来，主人", fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
                    Text("露露把今天要用的东西都收好啦。", color = MilestoneMuted)
                }
                MilestoneAvatar("露", 50)
            }
        }
        item {
            PaperCard(Modifier.clickable { onOpen(LuluRoute.ChatDetail) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MilestoneAvatar("露", 56)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(latest?.title ?: "露露", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(latest?.lastMessage.orEmpty().ifBlank { "来和露露聊聊天吧～" }, color = MilestoneMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MilestoneMuted)
                }
                Button(
                    onClick = { onOpen(LuluRoute.ChatDetail) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) { Text("继续聊天", fontWeight = FontWeight.Bold) }
            }
        }
        items(launchers.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    PaperCard(
                        modifier = Modifier.weight(1f).height(100.dp).clickable { onOpen(item.route) },
                        padding = 10.dp,
                    ) {
                        Icon(item.icon, null, tint = MilestoneMuted, modifier = Modifier.size(28.dp))
                        Text(item.title, fontSize = 12.sp)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MilestoneChat(
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
    onOpenCharacterSettings: () -> Unit,
) {
    var section by remember { mutableStateOf(ChatSection.Messages) }
    Scaffold(
        containerColor = MilestonePaper,
        topBar = { MilestoneTopBar("聊天", onBack) },
        bottomBar = {
            NavigationBar(containerColor = MilestoneCard) {
                listOf(
                    Triple(ChatSection.Messages, "消息", Icons.Outlined.ChatBubbleOutline),
                    Triple(ChatSection.Characters, "角色", Icons.Outlined.PeopleOutline),
                    Triple(ChatSection.Moments, "朋友圈", Icons.Outlined.DynamicFeed),
                    Triple(ChatSection.Me, "我的", Icons.Outlined.PersonOutline),
                ).forEach { (item, title, icon) ->
                    NavigationBarItem(
                        selected = item == section,
                        onClick = { section = item },
                        icon = { Icon(icon, null) },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                ChatSection.Messages -> ConversationRepositoryList(onOpenConversation)
                ChatSection.Characters -> CharacterHub(onOpenCharacterSettings)
                ChatSection.Moments -> CenterEmpty("朋友圈", "入口已保留，等内容规则确定后再接入。")
                ChatSection.Me -> ProfileHub()
            }
        }
    }
}

@Composable
private fun ConversationRepositoryList(onOpen: () -> Unit) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(conversations, key = { it.id }) { conversation ->
            ConversationCard(conversation, onOpen)
        }
    }
}

@Composable
private fun ConversationCard(conversation: LuluConversation, onOpen: () -> Unit) {
    PaperCard(Modifier.clickable {
        MigratedDomainStores.chat.markConversationRead(conversation.id)
        onOpen()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MilestoneAvatar("露", 52)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(conversation.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(conversation.lastMessage, color = MilestoneMuted, maxLines = 1)
            }
            if (conversation.unreadCount > 0) Badge { Text(conversation.unreadCount.toString()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepositoryChatScreen(onBack: () -> Unit) {
    val conversationId = "lulu-main"
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    var input by remember { mutableStateOf("") }
    Scaffold(
        containerColor = MilestonePaper,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MilestoneAvatar("露", 38)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("露露", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("陪着主人", color = MilestoneMuted, fontSize = 11.sp)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Call, "通话") }
                    IconButton(onClick = {}) { Icon(Icons.Outlined.MoreHoriz, "更多") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MilestonePaper),
            )
        },
        bottomBar = {
            Surface(color = MilestoneCard) {
                Row(
                    Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Mic, "语音") }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("和露露说点什么…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                    )
                    FilledIconButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                MigratedDomainStores.chat.sendUserMessage(conversationId, text)
                                input = ""
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MilestoneWheat),
                    ) { Icon(Icons.Outlined.Send, "发送", tint = MilestoneInk) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { message -> RepositoryMessageBubble(message) }
        }
    }
}

@Composable
private fun RepositoryMessageBubble(message: LuluChatMessage) {
    val user = message.sender == LuluChatMessage.Sender.User
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!user) {
            MilestoneAvatar("露", 34)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                color = if (user) MilestoneWheat else Color(0xFFFFFEFA),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MilestoneBorder),
            ) {
                Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            val time = remember(message.createdAt) {
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(message.createdAt)
            }
            Text(
                if (message.status == LuluChatMessage.Status.Failed) "$time · 发送失败" else time,
                color = if (message.status == LuluChatMessage.Status.Failed) MaterialTheme.colorScheme.error else MilestoneMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun CharacterHub(onSettings: () -> Unit) {
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val lulu = settings["lulu"]
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MilestoneAvatar("露", 58)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lulu?.displayName ?: "露露", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(lulu?.persona.orEmpty(), color = MilestoneMuted, maxLines = 2)
                    }
                    AssistChip(onClick = {}, label = { Text("使用中") })
                }
            }
        }
        item {
            PaperCard(Modifier.clickable(onClick = onSettings)) {
                Text("角色设置", fontWeight = FontWeight.Bold)
                Text("人设、主动联系、来电和夜间勿扰", color = MilestoneMuted)
            }
        }
        item { PaperCard { Text("新建角色", fontWeight = FontWeight.Bold); Text("角色创建流程下一阶段接入。", color = MilestoneMuted) } }
    }
}

@Composable
private fun ProfileHub() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MilestoneAvatar("主", 58)
                    Spacer(Modifier.width(14.dp))
                    Column { Text("主人", fontWeight = FontWeight.Bold, fontSize = 21.sp); Text("露露机的使用者", color = MilestoneMuted) }
                }
            }
        }
        items(listOf("个人资料", "成就与收藏", "账号与数据", "隐私与安全")) { title ->
            PaperCard { Row { Text(title, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = MilestoneMuted) } }
        }
    }
}

@Composable
private fun CharacterWorldBookScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val books by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    val rules by MigratedDomainStores.worldBookRules.rules.collectAsState()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(true) }
    Scaffold(containerColor = MilestonePaper, topBar = { MilestoneTopBar("世界书", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PaperCard {
                    OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text("世界设定") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    SwitchRow("默认全局应用", global) { global = it }
                    Button(
                        onClick = {
                            if (title.isNotBlank()) scope.launch {
                                LuluRepositories.worldBook.save(
                                    WorldBookEntry(
                                        id = java.util.UUID.randomUUID().toString(),
                                        title = title.trim(),
                                        content = content.trim(),
                                        globalEnabled = global,
                                        characterOverrides = emptyMap(),
                                    ),
                                )
                                title = ""; content = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存世界书") }
                }
            }
            if (books.isEmpty()) item { CenterEmpty("暂无世界书", "创建后可以单独决定露露是否使用。") }
            items(books, key = { it.id }) { book ->
                val explicit = rules.lastOrNull { it.worldBookId == book.id && it.characterId == "lulu" }
                val enabled = explicit?.enabled ?: book.globalEnabled
                PaperCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(book.title, fontWeight = FontWeight.Bold)
                            Text(book.content, color = MilestoneMuted, maxLines = 3)
                            Text(if (explicit == null) "跟随全局默认" else "露露单独设置", color = MilestoneMuted, fontSize = 12.sp)
                        }
                        Switch(checked = enabled, onCheckedChange = { MigratedDomainStores.worldBookRules.setEnabled(book.id, "lulu", it) })
                    }
                    TextButton(onClick = { scope.launch { LuluRepositories.worldBook.delete(book.id) } }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun MilestoneSettings(onBack: () -> Unit, onOpen: (LuluRoute) -> Unit) {
    val sections = listOf(
        Triple("外观", "暖纸主题、字号和动效", LuluRoute.AppearanceSettings),
        Triple("聊天", "消息、流式显示和语音", LuluRoute.ChatSettings),
        Triple("模型与 API", "服务商、模型、密钥与 Token", LuluRoute.ModelSettings),
        Triple("记忆", "总结阈值与最近消息排除", LuluRoute.Memory),
        Triple("通知与主动联系", "主动消息、来电和勿扰", LuluRoute.NotificationSettings),
        Triple("数据", "导入、导出、备份和缓存", LuluRoute.DataSettings),
        Triple("应用与权限", "版本、权限与关于", LuluRoute.PermissionSettings),
    )
    Scaffold(containerColor = MilestonePaper, topBar = { MilestoneTopBar("设置", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("露露机设置", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            items(sections) { (title, subtitle, route) ->
                PaperCard(Modifier.clickable { onOpen(route) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MilestoneMuted) }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MilestoneMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetailScreen(title: String, rows: List<Pair<String, Boolean>>, onBack: () -> Unit) {
    var values by remember(rows) { mutableStateOf(rows.map { it.second }) }
    Scaffold(containerColor = MilestonePaper, topBar = { MilestoneTopBar(title, onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows.indices.toList()) { index ->
                PaperCard { SwitchRow(rows[index].first, values[index]) { checked -> values = values.toMutableList().also { it[index] = checked } } }
            }
        }
    }
}

@Composable
private fun ModelSettingsScreen(onBack: () -> Unit) {
    var provider by remember { mutableStateOf("OpenAI 兼容接口") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Scaffold(containerColor = MilestonePaper, topBar = { MilestoneTopBar("模型与 API", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PaperCard {
                    OutlinedTextField(provider, { provider = it }, label = { Text("服务商") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("API 密钥") }, modifier = Modifier.fillMaxWidth())
                    Text("密钥持久化与加密将在数据层接入，不会写入日志。", color = MilestoneMuted, fontSize = 12.sp)
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
                }
            }
        }
    }
}

@Composable
private fun MilestonePlaceholder(route: LuluRoute, onBack: () -> Unit) {
    val title = when (route) {
        LuluRoute.Reading -> "阅读"
        LuluRoute.Wishes -> "心愿馆"
        LuluRoute.Study -> "考研"
        LuluRoute.Games -> "游戏"
        else -> "露露机"
    }
    Scaffold(containerColor = MilestonePaper, topBar = { MilestoneTopBar(title, onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CenterEmpty(title, if (route == LuluRoute.Reading) "阅读空间暂时保持为空。" else "入口已建立，后续继续迁移真实功能。")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MilestoneTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MilestonePaper),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun PaperCard(modifier: Modifier = Modifier, padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MilestoneCard),
        border = BorderStroke(1.dp, MilestoneBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = Alignment.Start,
            content = content,
        )
    }
}

@Composable
private fun CenterEmpty(title: String, text: String) {
    PaperCard(Modifier.padding(18.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(text, color = MilestoneMuted)
    }
}

@Composable
private fun MilestoneAvatar(text: String, size: Int) {
    Box(
        Modifier.size(size.dp).background(MilestoneWheat, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp) }
}
