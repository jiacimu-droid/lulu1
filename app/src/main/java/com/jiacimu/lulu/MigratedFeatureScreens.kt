package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.data.InMemoryLexiconRepository
import com.jiacimu.lulu.data.InMemoryMemoryRepository
import com.jiacimu.lulu.data.InMemoryWorldBookRepository
import com.jiacimu.lulu.data.LocalPerformanceRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private val FeaturePaper = Color(0xFFFFFDF7)
private val FeatureCard = Color(0xFFFFFBF1)
private val FeatureBlueGray = Color(0xFF6D7888)
private val FeatureBorder = Color(0xFFEAE0CC)

object LuluRepositories {
    val memory = InMemoryMemoryRepository()
    val lexicon = InMemoryLexiconRepository()
    val worldBook = InMemoryWorldBookRepository()

    private var performanceInternal: LocalPerformanceRepository? = null
    val performance: LocalPerformanceRepository
        get() = checkNotNull(performanceInternal) { "LuluRepositories 尚未初始化" }

    fun initialize(context: Context) {
        if (performanceInternal != null) return
        performanceInternal = LocalPerformanceRepository(context.applicationContext)
    }
}

@Composable
fun MemoryFeatureScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val policy by LuluRepositories.memory.observePolicy("lulu").collectAsState(initial = MemoryPolicy())
    val memories by LuluRepositories.memory.observeMemories("lulu").collectAsState(initial = emptyList())
    var excluded by remember(policy.excludedRecentMessages) { mutableStateOf(policy.excludedRecentMessages.toString()) }
    var threshold by remember(policy.readableThreshold) { mutableStateOf(policy.readableThreshold.toString()) }

    Scaffold(containerColor = FeaturePaper, topBar = { FeatureTopBar("记忆", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                FeatureCardBox {
                    Text("记忆整理规则", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    OutlinedTextField(
                        value = excluded,
                        onValueChange = { excluded = it.filter(Char::isDigit) },
                        label = { Text("最近 N 条消息不读取") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { threshold = it.filter(Char::isDigit) },
                        label = { Text("可读消息达到此数量才总结") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                LuluRepositories.memory.updatePolicy(
                                    "lulu",
                                    policy.copy(
                                        excludedRecentMessages = excluded.toIntOrNull() ?: 10,
                                        readableThreshold = threshold.toIntOrNull() ?: 20,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存记忆规则") }
                }
            }
            item { Text("记忆内容", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
            if (memories.isEmpty()) {
                item { FeatureEmpty("还没有形成记忆", "事实、情绪和时间线会在后续接入旧数据迁移层后显示。") }
            } else {
                items(memories, key = { it.id }) { memory ->
                    FeatureCardBox {
                        Text(memory.kind.name, color = FeatureBlueGray, fontSize = 12.sp)
                        Text(memory.content)
                    }
                }
            }
        }
    }
}

@Composable
fun LexiconFeatureScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sections = listOf(
        LexiconSection.Life to "生活",
        LexiconSection.Concern to "挂心",
        LexiconSection.Promise to "约定",
        LexiconSection.Diary to "日记",
    )
    var selected by remember { mutableIntStateOf(0) }
    val currentSection = sections[selected].first
    val entries by LuluRepositories.lexicon.observeEntries("lulu", currentSection).collectAsState(initial = emptyList())
    var title by remember(currentSection) { mutableStateOf("") }
    var content by remember(currentSection) { mutableStateOf("") }

    Scaffold(containerColor = FeaturePaper, topBar = { FeatureTopBar("辞海", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FeatureCardBox(Modifier.padding(16.dp)) { Text("露露", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            TabRow(selectedTabIndex = selected, containerColor = FeatureCard) {
                sections.forEachIndexed { index, (_, label) ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(label) })
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    FeatureCardBox {
                        OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(content, { content = it }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                if (title.isNotBlank()) scope.launch {
                                    LuluRepositories.lexicon.save(
                                        LexiconEntry(
                                            id = UUID.randomUUID().toString(),
                                            characterId = "lulu",
                                            section = currentSection,
                                            title = title.trim(),
                                            content = content.trim(),
                                            promiseKind = if (currentSection == LexiconSection.Promise) PromiseKind.Promise else null,
                                            createdAt = Instant.now(),
                                            updatedAt = Instant.now(),
                                        ),
                                    )
                                    title = ""
                                    content = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("新增${sections[selected].second}") }
                    }
                }
                if (entries.isEmpty()) {
                    item { FeatureEmpty(sections[selected].second, "当前还没有记录。") }
                } else {
                    items(entries, key = { it.id }) { entry ->
                        FeatureCardBox {
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, fontWeight = FontWeight.Bold)
                                    Text(entry.content, color = FeatureBlueGray)
                                }
                                IconButton(onClick = { scope.launch { LuluRepositories.lexicon.delete(entry.id) } }) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除")
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
fun WorldBookFeatureScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val books by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(true) }

    Scaffold(containerColor = FeaturePaper, topBar = { FeatureTopBar("世界书", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FeatureCardBox {
                    OutlinedTextField(title, { title = it }, label = { Text("世界书标题") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text("世界设定") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("全局应用")
                        Switch(checked = global, onCheckedChange = { global = it })
                    }
                    Button(
                        onClick = {
                            if (title.isNotBlank()) scope.launch {
                                LuluRepositories.worldBook.save(
                                    WorldBookEntry(
                                        id = UUID.randomUUID().toString(),
                                        title = title.trim(),
                                        content = content.trim(),
                                        globalEnabled = global,
                                        characterOverrides = emptyMap(),
                                    ),
                                )
                                title = ""
                                content = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存世界书") }
                }
            }
            if (books.isEmpty()) {
                item { FeatureEmpty("暂无世界书", "可以先创建标题、世界设定，并决定是否全局应用。") }
            } else {
                items(books, key = { it.id }) { book ->
                    FeatureCardBox {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.Bold)
                                Text(book.content, color = FeatureBlueGray)
                                Text(if (book.globalEnabled) "全局应用" else "按角色应用", color = FeatureBlueGray, fontSize = 12.sp)
                            }
                            IconButton(onClick = { scope.launch { LuluRepositories.worldBook.delete(book.id) } }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = FeaturePaper),
    )
}

@Composable
internal fun FeatureCardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeatureCard),
        border = BorderStroke(1.dp, FeatureBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
internal fun FeatureEmpty(title: String, text: String) {
    FeatureCardBox {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(text, color = FeatureBlueGray)
    }
}
