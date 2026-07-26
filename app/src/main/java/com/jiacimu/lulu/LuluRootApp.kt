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
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.study.PostgraduateExamApp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RootPaper = Color(0xFFFFFDF7)
private val RootCard = Color(0xFFFFFBF1)
private val RootWheat = Color(0xFFF4D57D)
private val RootMuted = Color(0xFF6D7888)
private val RootInk = Color(0xFF343434)
private val RootBorder = Color(0xFFEAE0CC)

private enum class RootRoute {
    Home, Chat, ChatDetail, CharacterSettings, Memory, Lexicon, WorldBook,
    Performance, Reading, Wishes, Study, Games, Settings,
}

private data class RootLauncher(val title: String, val icon: ImageVector, val route: RootRoute)

@Composable
fun LuluRootApp() {
    var route by remember { mutableStateOf(RootRoute.Home) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = RootWheat,
            onPrimary = RootInk,
            background = RootPaper,
            surface = RootCard,
            onSurface = RootInk,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = RootPaper) {
            when (route) {
                RootRoute.Home -> RootHome { route = it }
                RootRoute.Chat -> RootChatHub(
                    onBack = { route = RootRoute.Home },
                    onOpenConversation = { route = RootRoute.ChatDetail },
                    onCharacterSettings = { route = RootRoute.CharacterSettings },
                )
                RootRoute.ChatDetail -> RootChatDetail { route = RootRoute.Chat }
                RootRoute.CharacterSettings -> CharacterSettingsScreen { route = RootRoute.Chat }
                RootRoute.Memory -> MemoryFeatureScreen { route = RootRoute.Home }
                RootRoute.Lexicon -> LexiconFeatureScreen { route = RootRoute.Home }
                RootRoute.WorldBook -> CharacterWorldBookScreen { route = RootRoute.Home }
                RootRoute.Performance -> PerformanceFeatureScreen { route = RootRoute.Home }
                RootRoute.Study -> PostgraduateExamApp { route = RootRoute.Home }
                RootRoute.Settings -> RootSettings { route = RootRoute.Home }
                RootRoute.Reading -> RootEmpty("阅读", "阅读空间先保留为空，后续再迁入阅读能力。") { route = RootRoute.Home }
                RootRoute.Wishes -> RootEmpty("心愿馆", "心愿馆将作为下一批独立模块迁移。") { route = RootRoute.Home }
                RootRoute.Games -> RootEmpty("游戏", "游戏模块将在考研 App 稳定后整体迁移。") { route = RootRoute.Home }
            }
        }
    }
}

@Composable
private fun RootHome(onOpen: (RootRoute) -> Unit) {
    val launchers = listOf(
        RootLauncher("聊天", Icons.Outlined.ChatBubbleOutline, RootRoute.Chat),
        RootLauncher("记忆", Icons.Outlined.Psychology, RootRoute.Memory),
        RootLauncher("辞海", Icons.Outlined.MenuBook, RootRoute.Lexicon),
        RootLauncher("世界书", Icons.Outlined.Public, RootRoute.WorldBook),
        RootLauncher("性能监测", Icons.Outlined.MonitorHeart, RootRoute.Performance),
        RootLauncher("阅读", Icons.Outlined.AutoStories, RootRoute.Reading),
        RootLauncher("心愿馆", Icons.Outlined.StarOutline, RootRoute.Wishes),
        RootLauncher("考研", Icons.Outlined.School, RootRoute.Study),
        RootLauncher("游戏", Icons.Outlined.SportsEsports, RootRoute.Games),
        RootLauncher("设置", Icons.Outlined.Settings, RootRoute.Settings),
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
                    Text("今天", color = RootMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("欢迎回来，主人", fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
                    Text("露露把聊天、学习和记忆都整理在这里。", color = RootMuted)
                }
                RootAvatar("露", 50)
            }
        }
        item {
            RootCardBox(Modifier.clickable { onOpen(RootRoute.ChatDetail) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RootAvatar("露", 56)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(latest?.title ?: "露露", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(latest?.lastMessage.orEmpty().ifBlank { "来和露露聊聊天吧～" }, color = RootMuted, maxLines = 2)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = RootMuted)
                }
                Button(onClick = { onOpen(RootRoute.ChatDetail) }, modifier = Modifier.fillMaxWidth()) {
                    Text("继续聊天", fontWeight = FontWeight.Bold)
                }
            }
        }
        items(launchers.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    Card(
                        modifier = Modifier.weight(1f).height(100.dp).clickable { onOpen(item.route) },
                        colors = CardDefaults.cardColors(containerColor = RootCard),
                        border = BorderStroke(1.dp, RootBorder),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(item.icon, null, tint = RootMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(item.title, fontSize = 12.sp)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RootChatHub(onBack: () -> Unit, onOpenConversation: () -> Unit, onCharacterSettings: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("消息", "角色", "朋友圈", "我的")
    Scaffold(
        containerColor = RootPaper,
        topBar = { RootTopBar("聊天", onBack) },
        bottomBar = {
            NavigationBar(containerColor = RootCard) {
                listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.PeopleOutline, Icons.Outlined.DynamicFeed, Icons.Outlined.PersonOutline).forEachIndexed { index, icon ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, null) },
                        label = { Text(labels[index]) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> RootConversationList(onOpenConversation)
                1 -> RootCharacterHub(onCharacterSettings)
                2 -> RootCenterEmpty("朋友圈", "入口已经保留，内容规则后续再确定。")
                else -> RootProfileHub()
            }
        }
    }
}

@Composable
private fun RootConversationList(onOpen: () -> Unit) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(conversations, key = { it.id }) { conversation ->
            RootCardBox(Modifier.clickable {
                MigratedDomainStores.chat.markConversationRead(conversation.id)
                onOpen()
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RootAvatar("露", 52)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conversation.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(conversation.lastMessage, color = RootMuted, maxLines = 1)
                    }
                    if (conversation.unreadCount > 0) Badge { Text(conversation.unreadCount.toString()) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootChatDetail(onBack: () -> Unit) {
    val conversationId = "lulu-main"
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    var input by remember { mutableStateOf("") }
    Scaffold(
        containerColor = RootPaper,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RootAvatar("露", 38)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("露露", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("陪着主人", color = RootMuted, fontSize = 11.sp)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Call, "通话") }
                    IconButton(onClick = {}) { Icon(Icons.Outlined.MoreHoriz, "更多") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = RootPaper),
            )
        },
        bottomBar = {
            Surface(color = RootCard) {
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
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RootWheat),
                    ) { Icon(Icons.Outlined.Send, "发送", tint = RootInk) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { message -> RootMessageBubble(message) }
        }
    }
}

@Composable
private fun RootMessageBubble(message: LuluChatMessage) {
    val fromUser = message.sender == LuluChatMessage.Sender.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser) {
            RootAvatar("露", 34)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
            Surface(
                color = if (fromUser) RootWheat else RootCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, RootBorder),
            ) {
                Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            Text(
                message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                color = RootMuted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun RootCharacterHub(onSettings: () -> Unit) {
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val lulu = settings["lulu"]
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            RootCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RootAvatar("露", 56)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lulu?.displayName ?: "露露", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(lulu?.persona.orEmpty(), color = RootMuted)
                    }
                    AssistChip(onClick = {}, label = { Text("使用中") })
                }
            }
        }
        item {
            RootCardBox(Modifier.clickable(onClick = onSettings)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = RootMuted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("角色设置", fontWeight = FontWeight.Bold)
                        Text("人设、主动联系、勿扰和主动来电", color = RootMuted, fontSize = 13.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = RootMuted)
                }
            }
        }
    }
}

@Composable
private fun RootProfileHub() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            RootCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RootAvatar("主", 58)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("主人", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("露露机的唯一主人", color = RootMuted)
                    }
                }
            }
        }
        items(listOf("个人资料", "成就与收藏", "账号与数据", "隐私与安全")) { title ->
            RootCardBox {
                Row {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.ChevronRight, null, tint = RootMuted)
                }
            }
        }
    }
}

@Composable
private fun RootSettings(onBack: () -> Unit) {
    val sections = listOf(
        "外观" to "暖纸极简、字号和桌面",
        "聊天" to "流式回复、消息显示与语音",
        "模型与 API" to "服务商、模型、密钥和 Token",
        "记忆" to "总结阈值和最近消息排除",
        "通知与主动联系" to "角色消息、来电、勿扰和时间段",
        "数据" to "导入、导出、缓存和清理",
        "应用与权限" to "版本、权限和关于",
    )
    Scaffold(containerColor = RootPaper, topBar = { RootTopBar("设置", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("露露机设置", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("所有页面均为新结构，不沿用旧版设置页面。", color = RootMuted)
            }
            items(sections) { (title, subtitle) ->
                RootCardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Settings, null, tint = RootMuted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text(subtitle, color = RootMuted, fontSize = 13.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = RootMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun RootEmpty(title: String, text: String, onBack: () -> Unit) {
    Scaffold(containerColor = RootPaper, topBar = { RootTopBar(title, onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            RootCenterEmpty(title, text)
        }
    }
}

@Composable
private fun RootCenterEmpty(title: String, text: String) {
    RootCardBox(Modifier.padding(18.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(text, color = RootMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = RootPaper),
    )
}

@Composable
private fun RootCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RootCard),
        border = BorderStroke(1.dp, RootBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
private fun RootAvatar(text: String, size: Int) {
    Box(
        modifier = Modifier.size(size.dp).background(RootWheat, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = (size / 2.5).sp)
    }
}
