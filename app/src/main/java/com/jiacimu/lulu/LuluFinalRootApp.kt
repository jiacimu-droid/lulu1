package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.games.LuluGamesApp
import com.jiacimu.lulu.study.PostgraduateExamApp

private val FinalPaper = Color(0xFFFFFDF7)
private val FinalCard = Color(0xFFFFFBF1)
private val FinalWheat = Color(0xFFF4D57D)
private val FinalMuted = Color(0xFF6D7888)
private val FinalBorder = Color(0xFFEAE0CC)
private val FinalInk = Color(0xFF343434)

private enum class FinalRoute {
    Home, Chat, CharacterSettings, Memory, Lexicon, WorldBook, Performance,
    Reading, Wishes, Study, Games, Settings,
}

private data class FinalLauncher(val title: String, val icon: ImageVector, val route: FinalRoute)

@Composable
fun LuluFinalRootApp() {
    var route by remember { mutableStateOf(FinalRoute.Home) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = FinalWheat,
            onPrimary = FinalInk,
            background = FinalPaper,
            surface = FinalCard,
            onSurface = FinalInk,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = FinalPaper) {
            when (route) {
                FinalRoute.Home -> FinalHome { route = it }
                FinalRoute.Chat -> FinalChatHub(
                    onBack = { route = FinalRoute.Home },
                    onCharacterSettings = { route = FinalRoute.CharacterSettings },
                )
                FinalRoute.CharacterSettings -> CharacterSettingsScreen { route = FinalRoute.Chat }
                FinalRoute.Memory -> MemoryFeatureScreen { route = FinalRoute.Home }
                FinalRoute.Lexicon -> LexiconFeatureScreen { route = FinalRoute.Home }
                FinalRoute.WorldBook -> CharacterWorldBookScreen { route = FinalRoute.Home }
                FinalRoute.Performance -> PerformanceFeatureScreen { route = FinalRoute.Home }
                FinalRoute.Study -> PostgraduateExamApp { route = FinalRoute.Home }
                FinalRoute.Games -> LuluGamesApp { route = FinalRoute.Home }
                FinalRoute.Settings -> LuluSettingsScreen { route = FinalRoute.Home }
                FinalRoute.Reading -> FinalEmpty("阅读", "阅读空间已保留，下一整块迁移时接入书架、阅读器与笔记。") { route = FinalRoute.Home }
                FinalRoute.Wishes -> FinalEmpty("心愿馆", "心愿馆已保留，下一整块迁移时接入愿望、进度与角色回应。") { route = FinalRoute.Home }
            }
        }
    }
}

@Composable
private fun FinalHome(onOpen: (FinalRoute) -> Unit) {
    val launchers = listOf(
        FinalLauncher("聊天", Icons.Outlined.ChatBubbleOutline, FinalRoute.Chat),
        FinalLauncher("记忆", Icons.Outlined.Psychology, FinalRoute.Memory),
        FinalLauncher("辞海", Icons.Outlined.MenuBook, FinalRoute.Lexicon),
        FinalLauncher("世界书", Icons.Outlined.Public, FinalRoute.WorldBook),
        FinalLauncher("性能监测", Icons.Outlined.MonitorHeart, FinalRoute.Performance),
        FinalLauncher("阅读", Icons.Outlined.AutoStories, FinalRoute.Reading),
        FinalLauncher("心愿馆", Icons.Outlined.StarOutline, FinalRoute.Wishes),
        FinalLauncher("考研", Icons.Outlined.School, FinalRoute.Study),
        FinalLauncher("游戏", Icons.Outlined.SportsEsports, FinalRoute.Games),
        FinalLauncher("设置", Icons.Outlined.Settings, FinalRoute.Settings),
    )
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val latest = conversations.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text("欢迎回来，主人", fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
            Text("露露把聊天、学习、游戏和记忆都整理好了。", color = FinalMuted)
        }
        item {
            FinalCardBox(Modifier.clickable { onOpen(FinalRoute.Chat) }) {
                Text(latest?.title ?: "露露", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(latest?.lastMessage.orEmpty().ifBlank { "来和露露聊聊天吧～" }, color = FinalMuted)
                Button(onClick = { onOpen(FinalRoute.Chat) }, modifier = Modifier.fillMaxWidth()) { Text("继续聊天") }
            }
        }
        items(launchers.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    Card(
                        modifier = Modifier.weight(1f).height(100.dp).clickable { onOpen(item.route) },
                        colors = CardDefaults.cardColors(containerColor = FinalCard),
                        border = BorderStroke(1.dp, FinalBorder),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(item.icon, null, tint = FinalMuted, modifier = Modifier.size(28.dp))
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
private fun FinalChatHub(onBack: () -> Unit, onCharacterSettings: () -> Unit) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    Scaffold(
        containerColor = FinalPaper,
        topBar = { FinalTopBar("聊天", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FinalCardBox(Modifier.clickable(onClick = onCharacterSettings)) {
                    Text("角色设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("主动联系、来电、勿扰、世界书与角色设定。", color = FinalMuted)
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                FinalCardBox {
                    Text(conversation.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(conversation.lastMessage, color = FinalMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = FinalPaper),
    )
}

@Composable
private fun FinalEmpty(title: String, subtitle: String, onBack: () -> Unit) {
    Scaffold(containerColor = FinalPaper, topBar = { FinalTopBar(title, onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            FinalCardBox(Modifier.padding(18.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = FinalMuted)
            }
        }
    }
}

@Composable
private fun FinalCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FinalCard),
        border = BorderStroke(1.dp, FinalBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
