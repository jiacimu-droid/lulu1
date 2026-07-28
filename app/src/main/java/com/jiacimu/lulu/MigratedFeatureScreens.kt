package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.PromiseKind
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
private val FeatureWheat = Color(0xFFF4D57D)

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
                        onValueChange = { value -> excluded = value.filter(Char::isDigit) },
                        label = { Text("最近 N 条消息不读取") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { value -> threshold = value.filter(Char::isDigit) },
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
                item { FeatureEmpty("还没有形成记忆", "事实、情绪和时间线会在记忆迁移阶段接入。") }
            } else {
                items(memories, key = { memory -> memory.id }) { memory ->
                    FeatureCardBox {
                        Text(memory.kind.name, color = FeatureBlueGray, fontSize = 12.sp)
                        Text(memory.content)
                    }
                }
            }
        }
    }
}

private val LexiconSections = listOf(
    LexiconSection.Life to "生活",
    LexiconSection.Concern to "挂心",
    LexiconSection.Promise to "约定",
    LexiconSection.Diary to "日记",
)

private val PromiseKinds = listOf(
    PromiseKind.Promise to "承诺",
    PromiseKind.Responsibility to "责任",
    PromiseKind.Reminder to "提醒",
    PromiseKind.LongTermSupervision to "长期监督",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiconFeatureScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedSectionIndex by remember { mutableIntStateOf(0) }
    val currentSection = LexiconSections[selectedSectionIndex].first
    val entries by LuluRepositories.lexicon
        .observeEntries("lulu", currentSection)
        .collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<LexiconEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = FeaturePaper,
        topBar = {
            TopAppBar(
                title = { Text("辞海", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新增条目") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FeaturePaper),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = FeatureWheat, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("露", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Text("露露", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            TabRow(selectedTabIndex = selectedSectionIndex, containerColor = FeatureCard) {
                LexiconSections.forEachIndexed { index, (_, label) ->
                    Tab(
                        selected = selectedSectionIndex == index,
                        onClick = { selectedSectionIndex = index },
                        text = { Text(label) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entries.isEmpty()) {
                    item { FeatureEmpty(LexiconSections[selectedSectionIndex].second, "当前还没有记录。") }
                } else {
                    items(entries, key = { entry -> entry.id }) { entry ->
                        FeatureCardBox {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                    if (entry.section == LexiconSection.Promise) {
                                        Text(
                                            entry.promiseKind.promiseKindLabel(),
                                            color = FeatureBlueGray,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Text(entry.content, color = FeatureBlueGray)
                                }
                                IconButton(onClick = { editing = entry }) {
                                    Icon(Icons.Outlined.Edit, "编辑")
                                }
                                IconButton(onClick = {
                                    scope.launch { LuluRepositories.lexicon.delete(entry.id) }
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogEntry = editing ?: LexiconEntry(
        id = UUID.randomUUID().toString(),
        characterId = "lulu",
        section = currentSection,
        title = "",
        content = "",
        promiseKind = if (currentSection == LexiconSection.Promise) PromiseKind.Promise else null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    ).takeIf { creating }

    dialogEntry?.let { entry ->
        LexiconEditorDialog(
            entry = entry,
            isNew = creating,
            onDismiss = {
                editing = null
                creating = false
            },
            onSave = { saved ->
                scope.launch { LuluRepositories.lexicon.save(saved) }
                editing = null
                creating = false
            },
            onDelete = {
                scope.launch { LuluRepositories.lexicon.delete(entry.id) }
                editing = null
                creating = false
            },
        )
    }
}

@Composable
private fun LexiconEditorDialog(
    entry: LexiconEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (LexiconEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var promiseKind by remember(entry.id) { mutableStateOf(entry.promiseKind ?: PromiseKind.Promise) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增条目" else "编辑条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (entry.section == LexiconSection.Promise) {
                    Text("约定类型", fontWeight = FontWeight.SemiBold)
                    PromiseKinds.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { (kind, label) ->
                                FilterChip(
                                    selected = promiseKind == kind,
                                    onClick = { promiseKind = kind },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    onSave(
                        entry.copy(
                            title = title.trim(),
                            content = content.trim(),
                            promiseKind = if (entry.section == LexiconSection.Promise) promiseKind else null,
                            updatedAt = Instant.now(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun PromiseKind?.promiseKindLabel(): String = PromiseKinds
    .firstOrNull { (kind, _) -> kind == this }
    ?.second
    ?: "承诺"

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
internal fun FeatureCardBox(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeatureCard),
        border = BorderStroke(1.dp, FeatureBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun FeatureEmpty(title: String, text: String) {
    FeatureCardBox {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(text, color = FeatureBlueGray)
    }
}
