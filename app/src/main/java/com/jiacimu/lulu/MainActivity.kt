package com.jiacimu.lulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
private val Card = Color(0xFFFFFBF1)
private val Wheat = Color(0xFFF4D57D)
private val Ink = Color(0xFF343434)
private val BlueGray = Color(0xFF6D7888)
private val Border = Color(0xFFEAE0CC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LuluApp() }
    }
}

private enum class Screen { Home, Chat, Memory, Lexicon, WorldBook, Monitor, Reading, Wishes, Study, Games, Settings }
private enum class ChatTab { Messages, Characters, Moments, Me }

@Composable
private fun LuluApp() {
    var screen by remember { mutableStateOf(Screen.Home) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Wheat,
            onPrimary = Ink,
            background = Paper,
            surface = Card,
            onSurface = Ink
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            when (screen) {
                Screen.Home -> HomeScreen(onOpen = { screen = it })
                Screen.Chat -> ChatApp(onBack = { screen = Screen.Home })
                Screen.Monitor -> MonitorScreen(onBack = { screen = Screen.Home })
                Screen.Lexicon -> LexiconScreen(onBack = { screen = Screen.Home })
                else -> PlaceholderScreen(screen, onBack = { screen = Screen.Home })
            }
        }
    }
}

private data class AppEntry(val title: String, val icon: ImageVector, val screen: Screen)

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
        AppEntry("设置", Icons.Outlined.Settings, Screen.Settings)
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
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
                modifier = Modifier.fillMaxWidth().clickable { onOpen(Screen.Chat) },
                colors = CardDefaults.cardColors(containerColor = Card),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar("露", 56)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("露露", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                AssistChip(onClick = {}, label = { Text("最近聊天", fontSize = 11.sp) })
                            }
                            Text("主人，今天的早餐想吃点什么呢？\n我可以陪你一起想哦～", color = BlueGray, fontSize = 14.sp)
                        }
                        Text("08:36", color = BlueGray, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onOpen(Screen.Chat) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Wheat),
                        shape = RoundedCornerShape(18.dp)
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
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
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
private fun ChatApp(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(ChatTab.Messages) }
    Scaffold(
        containerColor = Paper,
        topBar = { LuluTopBar("聊天", onBack) },
        bottomBar = {
            NavigationBar(containerColor = Card) {
                listOf(
                    Triple(ChatTab.Messages, "消息", Icons.Outlined.ChatBubbleOutline),
                    Triple(ChatTab.Characters, "角色", Icons.Outlined.PeopleOutline),
                    Triple(ChatTab.Moments, "朋友圈", Icons.Outlined.DynamicFeed),
                    Triple(ChatTab.Me, "我的", Icons.Outlined.PersonOutline)
                ).forEach { (item, label, icon) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, null) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ChatTab.Messages -> ConversationList()
                ChatTab.Characters -> SimpleList("角色", listOf("露露 · 当前角色", "新建角色", "角色设置与世界书"))
                ChatTab.Moments -> EmptyState("朋友圈", "入口已经保留，功能等待主人之后确定。")
                ChatTab.Me -> SimpleList("我的", listOf("个人资料", "账号与数据", "成就与收藏"))
            }
        }
    }
}

@Composable
private fun ConversationList() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Card), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("露", 52)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("露露", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("今天也会一直陪着主人呀～", color = BlueGray)
                    }
                    Text("08:36", color = BlueGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MonitorScreen(onBack: () -> Unit) {
    val tabs = listOf("报错日志", "缓存", "控制台", "时长监测")
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(containerColor = Paper, topBar = { LuluTopBar("性能监测", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = selected, containerColor = Card, edgePadding = 12.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
                }
            }
            when (selected) {
                0 -> SimpleList("报错日志", listOf("暂无新的崩溃记录", "后续接入筛选、复制与清空"))
                1 -> SimpleList("缓存", listOf("图片缓存", "模型缓存", "临时文件"))
                2 -> SimpleList("控制台", listOf("输入 Token：待接入", "输出 Token：待接入", "模型调用记录"))
                else -> SimpleList("时长监测", listOf("今日学习：0 分钟", "今日聊天：0 分钟", "数据将继续提供给角色读取"))
            }
        }
    }
}

@Composable
private fun LexiconScreen(onBack: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = listOf("生活", "挂心", "约定", "日记")
    Scaffold(containerColor = Paper, topBar = { LuluTopBar("辞海", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Card), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("露", 44)
                    Spacer(Modifier.width(12.dp))
                    Text("露露", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null, tint = BlueGray)
                }
            }
            TabRow(selectedTabIndex = selected, containerColor = Card) {
                tabs.forEachIndexed { index, title -> Tab(selected == index, { selected = index }, text = { Text(title) }) }
            }
            EmptyState(tabs[selected], if (selected == 2) "承诺、责任、提醒和长期监督会统一在这里呈现。" else "这里会展示露露记录下来的${tabs[selected]}内容。")
        }
    }
}

@Composable
private fun PlaceholderScreen(screen: Screen, onBack: () -> Unit) {
    val title = when (screen) {
        Screen.Memory -> "记忆"
        Screen.WorldBook -> "世界书"
        Screen.Reading -> "阅读"
        Screen.Wishes -> "心愿馆"
        Screen.Study -> "考研"
        Screen.Games -> "游戏"
        Screen.Settings -> "设置"
        else -> "露露机"
    }
    Scaffold(containerColor = Paper, topBar = { LuluTopBar(title, onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(title, if (screen == Screen.Reading) "阅读空间正在整理中。" else "页面结构已独立建立，下一阶段接入真实功能。")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LuluTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
    )
}

@Composable
private fun SimpleList(title: String, lines: List<String>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        items(lines) { line ->
            Card(colors = CardDefaults.cardColors(containerColor = Card), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Text(line, Modifier.fillMaxWidth().padding(16.dp), color = Ink)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Card(
        modifier = Modifier.padding(18.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shape = RoundedCornerShape(22.dp)
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
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Ink, fontWeight = FontWeight.Bold, fontSize = (size / 2.4).sp)
    }
}
