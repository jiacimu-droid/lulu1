package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

private val Paper = Color(0xFFFFFDF7)
private val CardColor = Color(0xFFFFFBF1)
private val Wheat = Color(0xFFF4D57D)
private val Ink = Color(0xFF343434)
private val BlueGray = Color(0xFF6D7888)
private val Border = Color(0xFFEAE0CC)
private val UserBubble = Color(0xFFF4D57D)
private val CharacterBubble = Color(0xFFFFFEFA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LuluApp() }
    }
}

private enum class Screen {
    Home, Chat, ChatDetail, Memory, Lexicon, WorldBook, Monitor,
    Reading, Wishes, Study, Games, Settings,
}
private enum class ChatTab { Messages, Characters, Moments, Me }
private data class AppEntry(val title: String, val icon: ImageVector, val screen: Screen)
private data class PreviewMessage(val text: String, val fromUser: Boolean, val time: String)

@Composable
private fun LuluApp() {
    var screen by remember { mutableStateOf(Screen.Home) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Wheat,
            onPrimary = Ink,
            background = Paper,
            surface = CardColor,
            onSurface = Ink,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            when (screen) {
                Screen.Home -> HomeScreen { screen = it }
                Screen.Chat -> ChatApp(
                    onBack = { screen = Screen.Home },
                    onOpenConversation = { screen = Screen.ChatDetail },
                )
                Screen.ChatDetail -> ChatDetailScreen(onBack = { screen = Screen.Chat })
                Screen.Memory -> MemoryFeatureScreen { screen = Screen.Home }
                Screen.Lexicon -> LexiconFeatureScreen { screen = Screen.Home }
                Screen.WorldBook -> WorldBookFeatureScreen { screen = Screen.Home }
                Screen.Monitor -> PerformanceFeatureScreen { screen = Screen.Home }
                Screen.Settings -> SettingsScreen { screen = Screen.Home }
                else -> PlaceholderScreen(screen) { screen = Screen.Home }
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpen: (Screen) -> Unit) {
    val entries = listOf(
        AppEntry("聊天", Icons.Outlined.ChatBubbleOutline, Screen.Chat),
        AppEntry("记忆", Icons.Outlined.Psychology, Screen.Memory),
        AppEntry("辞海", Icons.Outlined.MenuBook, Screen.Lexicon),
        AppEntry("世界书", Icons.Outlined.Public, Screen.WorldBook),
        AppEntry("性能监测", Icons.Outlined.MonitorHeart, Screen.Monitor),
        AppEntry("阅读", Icons.Outlined.AutoStories, Screen.Reading),
        AppEntry("心愿馆", Icons.Outlined.StarOutline, Screen.Wishes),
        AppEntry("考研", Icons.Outlined.School, Screen.Study),
        AppEntry("游戏", Icons.Outlined.SportsEsports, Screen.Games),
        AppEntry("设置", Icons.Outlined.Settings, Screen.Settings),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("5月20日  星期二", color = BlueGray, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("早上好，主人 ☀", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("今天也要和露露一起好好度过呀 ♥", color = BlueGray, fontSize = 15.sp)
                }
                Avatar("露")
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(Screen.ChatDetail) },
                colors = CardDefaults.cardColors(containerColor = CardColor),
                border = BorderStroke(1.dp, Border),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar("露", 56)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("露露", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("主人，今天也来陪露露聊聊天吧～", color = BlueGray, fontSize = 14.sp)
                        }
                        Text("最近", color = BlueGray, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onOpen(Screen.ChatDetail) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Wheat),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text("继续聊天", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        items(entries.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { entry ->
                    Card(
                        modifier = Modifier.weight(1f).aspectRatio(0.82f).clickable { onOpen(entry.screen) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA)),
                        border = BorderStroke(1.dp, Border),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(entry.icon, null, tint = BlueGray, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(9.dp))
                            Text(entry.title, fontSize = 12.sp, color = Ink)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChatApp(onBack: () -> Unit, onOpenConversation: () -> Unit) {
    var tab by remember { mutableStateOf(ChatTab.Messages) }
    Scaffold(
        containerColor = Paper,
        topBar = { LuluTopBar("聊天", onBack) },
        bottomBar = {
            NavigationBar(containerColor = CardColor) {
                listOf(
                    Triple(ChatTab.Messages, "消息", Icons.Outlined.ChatBubbleOutline),
                    Triple(ChatTab.Characters, "角色", Icons.Outlined.PeopleOutline),
                    Triple(ChatTab.Moments, "朋友圈", Icons.Outlined.DynamicFeed),
                    Triple(ChatTab.Me, "我的", Icons.Outlined.PersonOutline),
                ).forEach { (item, label, icon) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ChatTab.Messages -> ConversationList(onOpenConversation)
                ChatTab.Characters -> CharacterHubScreen()
                ChatTab.Moments -> MomentsPlaceholderScreen()
                ChatTab.Me -> MyProfileScreen()
            }
        }
    }
}

@Composable
private fun ConversationList(onOpenConversation: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenConversation),
                colors = CardDefaults.cardColors(containerColor = CardColor),
                border = BorderStroke(1.dp, Border),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("露", 52)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("露露", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("今天也会一直陪着主人呀～", color = BlueGray)
                    }
                    Text("最近", color = BlueGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatDetailScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(
            listOf(
                PreviewMessage("主人，今天学习辛苦啦。", false, "16:08"),
                PreviewMessage("嗯，刚刚做完一篇阅读。", true, "16:09"),
                PreviewMessage("那先休息一小会儿，露露陪着你。", false, "16:09"),
            ),
        )
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar("露", 38)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("露露", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("陪着主人", color = BlueGray, fontSize = 11.sp)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Call, "通话") }
                    IconButton(onClick = {}) { Icon(Icons.Outlined.MoreHoriz, "更多") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Paper),
            )
        },
        bottomBar = {
            Surface(color = CardColor, tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = {}) { Icon(Icons.Outlined.Mic, "语音") }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("和露露说点什么…") },
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 4,
                    )
                    IconButton(onClick = {}) { Icon(Icons.Outlined.AddCircleOutline, "更多") }
                    FilledIconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                messages = messages + PreviewMessage(text, true, "刚刚")
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Wheat),
                    ) { Icon(Icons.Outlined.Send, "发送", tint = Ink) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("今天", color = BlueGray, fontSize = 12.sp)
                }
            }
            items(messages) { message -> MessageBubble(message) }
        }
    }
}

@Composable
private fun MessageBubble(message: PreviewMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!message.fromUser) {
            Avatar("露", 34)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start) {
            Surface(
                color = if (message.fromUser) UserBubble else CharacterBubble,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (message.fromUser) 18.dp else 5.dp,
                    bottomEnd = if (message.fromUser) 5.dp else 18.dp,
                ),
                border = BorderStroke(1.dp, Border),
            ) {
                Text(message.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ink)
            }
            Text(message.time, color = BlueGray, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val sections = listOf(
        Triple("外观", "主题、桌面、字号与聊天气泡", Icons.Outlined.Palette),
        Triple("聊天", "流式回复、消息显示与语音", Icons.Outlined.ChatBubbleOutline),
        Triple("模型与 API", "服务商、模型、密钥与 Token", Icons.Outlined.Hub),
        Triple("记忆", "总结阈值、最近消息排除与调试", Icons.Outlined.Psychology),
        Triple("通知与主动联系", "主动消息、来电、勿扰与时间段", Icons.Outlined.NotificationsNone),
        Triple("数据", "导入、导出、缓存与数据清理", Icons.Outlined.Storage),
        Triple("应用与权限", "版本、更新、权限与关于", Icons.Outlined.Info),
    )
    Scaffold(containerColor = Paper, topBar = { LuluTopBar("设置", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("露露机设置", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("重新整理后的设置中心，不沿用原版页面结构。", color = BlueGray)
            }
            items(sections) { (title, subtitle, icon) ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { },
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    border = BorderStroke(1.dp, Border),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(0xFFFFF4D5), shape = CircleShape) {
                            Icon(icon, null, tint = BlueGray, modifier = Modifier.padding(11.dp).size(24.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(subtitle, color = BlueGray, fontSize = 13.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = BlueGray)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(screen: Screen, onBack: () -> Unit) {
    val title = when (screen) {
        Screen.Reading -> "阅读"
        Screen.Wishes -> "心愿馆"
        Screen.Study -> "考研"
        Screen.Games -> "游戏"
        else -> "露露机"
    }
    Scaffold(containerColor = Paper, topBar = { LuluTopBar(title, onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(title, if (screen == Screen.Reading) "阅读空间正在整理中。" else "页面结构已经独立建立，下一阶段接入真实功能。")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LuluTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
    )
}

@Composable
private fun EmptyState(title: String, text: String) {
    Card(
        modifier = Modifier.padding(18.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(text, color = BlueGray)
        }
    }
}

@Composable
private fun Avatar(text: String, size: Int = 48) {
    Box(
        modifier = Modifier.size(size.dp).background(Wheat, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ink, fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp)
    }
}
