package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.data.InMemoryLexiconRepository
import com.jiacimu.lulu.data.InMemoryWorldBookRepository
import com.jiacimu.lulu.data.LocalMemoryRepository
import com.jiacimu.lulu.data.LocalPerformanceRepository
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val FeaturePaper = Color(0xFFFFFDF7)
private val FeatureCard = Color(0xFFFFFBF1)
private val FeatureBlueGray = Color(0xFF6D7888)
private val FeatureBorder = Color(0xFFEAE0CC)
private val FeatureWheat = Color(0xFFF4D57D)

object LuluRepositories {
    val memory = LocalMemoryRepository()
    val lexicon = InMemoryLexiconRepository()
    val worldBook = InMemoryWorldBookRepository()

    private var performanceInternal: LocalPerformanceRepository? = null
    val performance: LocalPerformanceRepository
        get() = checkNotNull(performanceInternal) { "LuluRepositories 尚未初始化" }

    fun initialize(context: Context) {
        if (performanceInternal != null) return
        performanceInternal = LocalPerformanceRepository(context.applicationContext)
        memory.initialize(context.applicationContext)
    }
}

private enum class MemoryPageSection(val label: String) {
    Recent("全部记忆"),
    Fact("长期事实"),
    Emotion("情绪印象"),
    Timeline("重要经历"),
    Pending("待整理"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFeatureScreen(onBack: () -> Unit) {
    val repository = LuluRepositories.memory
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var selectedCharacterId by rememberSaveable {
        mutableStateOf(characters.keys.firstOrNull() ?: "lulu")
    }
    val selectedCharacter = characters[selectedCharacterId] ?: MigratedDomainStores.characters.get(selectedCharacterId)
    val policy by repository.observePolicy(selectedCharacterId).collectAsState(initial = MemoryPolicy())
    val memories by repository.observeMemories(selectedCharacterId).collectAsState(initial = emptyList())
    val debug by repository.debugState.collectAsState()
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf(MemoryPageSection.Recent) }
    var search by rememberSaveable { mutableStateOf("") }
    var excluded by remember(policy.excludedRecentMessages) { mutableStateOf(policy.excludedRecentMessages.toString()) }
    var threshold by remember(policy.readableThreshold) { mutableStateOf(policy.readableThreshold.toString()) }
    var autoSummarize by remember(policy.autoSummarize) { mutableStateOf(policy.autoSummarize) }
    var showSettings by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntry?>(null) }
    var creatingMemory by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var maintenanceNotice by remember { mutableStateOf("") }
    val pendingEvents = repository.pendingTimelineEvents(selectedCharacterId)

    val filtered = remember(memories, section, search) {
        memories.filter { memory ->
            val sectionMatch = when (section) {
                MemoryPageSection.Recent -> true
                MemoryPageSection.Fact -> memory.kind == MemoryKind.Fact
                MemoryPageSection.Emotion -> memory.kind == MemoryKind.Emotion
                MemoryPageSection.Timeline -> memory.kind == MemoryKind.Timeline
                MemoryPageSection.Pending -> false
            }
            val query = search.trim()
            sectionMatch && (query.isBlank() || memory.content.contains(query, ignoreCase = true) || memory.source.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        containerColor = FeaturePaper,
        topBar = {
            TopAppBar(
                title = { Text("记忆", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Outlined.Tune, "记忆设置")
                    }
                    IconButton(onClick = { creatingMemory = true }) {
                        Icon(Icons.Outlined.Add, "手动添加记忆")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FeaturePaper),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                characters.values.forEach { character ->
                    FilterChip(
                        selected = selectedCharacterId == character.characterId,
                        onClick = {
                            selectedCharacterId = character.characterId
                            maintenanceNotice = ""
                        },
                        label = { Text(character.displayName) },
                        leadingIcon = {
                            Surface(shape = CircleShape, color = FeatureWheat, modifier = Modifier.size(22.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(character.displayName.take(1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                    )
                }
            }

            if (showSettings) {
                FeatureCardBox(Modifier.padding(horizontal = 16.dp)) {
                    Text("${selectedCharacter.displayName}的记忆规则", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    OutlinedTextField(
                        value = excluded,
                        onValueChange = { value -> excluded = value.filter(Char::isDigit) },
                        label = { Text("最近 N 条消息不读取") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { value -> threshold = value.filter(Char::isDigit) },
                        label = { Text("可读消息达到此数量才总结") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自动整理", fontWeight = FontWeight.SemiBold)
                            Text("聊天回复成功后检查阈值；不足时不会调用模型。", color = FeatureBlueGray, fontSize = 12.sp)
                        }
                        Switch(checked = autoSummarize, onCheckedChange = { autoSummarize = it })
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                repository.updatePolicy(
                                    selectedCharacterId,
                                    MemoryPolicy(
                                        excludedRecentMessages = excluded.toIntOrNull()?.coerceAtLeast(0) ?: 25,
                                        readableThreshold = threshold.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                                        autoSummarize = autoSummarize,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存记忆规则") }
                    OutlinedButton(
                        onClick = {
                            organizing = true
                            maintenanceNotice = ""
                            scope.launch {
                                val removed = repository.maintain(selectedCharacterId)
                                maintenanceNotice = if (removed > 0) {
                                    "已安全合并 $removed 条重复/高度重复记忆"
                                } else {
                                    "没有发现可以安全自动合并的重复记忆"
                                }
                                organizing = false
                            }
                        },
                        enabled = !organizing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (organizing) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(if (organizing) "正在维护…" else "整理已有重复记忆")
                    }
                    Text(
                        "删除记忆后，它会立即退出召回范围，并留下删除标记，自动总结不会再把同一条重新生成；如果多个角色里存在同一条错误记忆，手动删除会一起清掉等价副本。",
                        color = FeatureBlueGray,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    if (maintenanceNotice.isNotBlank()) {
                        Text(maintenanceNotice, color = FeatureBlueGray, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索记忆内容或来源") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )

            ScrollableTabRow(
                selectedTabIndex = section.ordinal,
                containerColor = FeatureCard,
                edgePadding = 8.dp,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                MemoryPageSection.entries.forEach { item ->
                    Tab(
                        selected = section == item,
                        onClick = { section = item },
                        text = { Text(item.label, maxLines = 1) },
                    )
                }
            }

            if (section != MemoryPageSection.Pending) {
                Text(
                    when (section) {
                        MemoryPageSection.Recent -> "这里是三类已整理记忆的合并视图，不会另外生成一份重复内容。"
                        MemoryPageSection.Fact -> "长期稳定的身份、偏好、边界和持续计划；不会再靠‘不是/其实/应该’等关键词机械判定。"
                        MemoryPageSection.Emotion -> "明确发生过的情绪、原因与时间，不把普通语气猜成情绪。"
                        MemoryPageSection.Timeline -> "有时间或里程碑意义的重要经历；普通聊天、无互动番茄钟和约定不放这里。"
                        MemoryPageSection.Pending -> ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = FeatureBlueGray,
                    fontSize = 12.sp,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (section == MemoryPageSection.Pending) {
                    item {
                        FeatureCardBox {
                            Text("待整理消息", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(
                                "当前约有 ${repository.pendingMessageCount(selectedCharacterId)} 条可读消息尚未进入成功批次。",
                                color = FeatureBlueGray,
                            )
                            Text("阈值：${policy.readableThreshold} 条；最近排除：${policy.excludedRecentMessages} 条", color = FeatureBlueGray, fontSize = 12.sp)
                            Text("最近排除只是暂缓，之后有新消息时会按原时间顺序进入队列，不会丢弃。私聊和该角色参与的群聊都在这里。", color = FeatureBlueGray, fontSize = 12.sp)
                            Button(
                                onClick = {
                                    organizing = true
                                    scope.launch {
                                        repository.summarizeNow(selectedCharacterId)
                                        organizing = false
                                    }
                                },
                                enabled = !organizing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (organizing || debug.extracting) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(7.dp))
                                }
                                Text(if (organizing || debug.extracting) "正在整理…" else "立即整理")
                            }
                        }
                    }
                    item {
                        FeatureCardBox {
                            Text("调试信息", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(debug.message)
                            MemoryMetricLine("可读消息", debug.readableCount.toString())
                            MemoryMetricLine("待处理", debug.pendingCount.toString())
                            MemoryMetricLine("本批数量", debug.batchCount.toString())
                            MemoryMetricLine("最近新增", debug.lastExtractedCount.toString())
                            debug.lastError?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                            Text(
                                debug.updatedAt.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter),
                                color = FeatureBlueGray,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    if (pendingEvents.isNotEmpty()) {
                        item { Text("等待队列 · 由旧到新", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                        items(pendingEvents, key = { event -> event.id }) { event ->
                            FeatureCardBox {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(event.channel, color = FeatureBlueGray, fontSize = 11.sp)
                                    Text(event.occurredAt.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter), color = FeatureBlueGray, fontSize = 11.sp)
                                }
                                Text("${event.speaker}：${event.content}", fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        FeatureEmpty(
                            title = if (search.isBlank()) "还没有${section.label}" else "没有匹配的记忆",
                            text = if (search.isBlank()) "可以手动添加，或在达到阈值后执行记忆整理。" else "换一个关键词再试试。",
                        )
                    }
                } else {
                    items(filtered, key = { memory -> memory.id }) { memory ->
                        MemoryEntryCard(
                            memory = memory,
                            onEdit = { editingMemory = memory },
                            onDelete = { scope.launch { repository.deleteEverywhereEquivalent(memory.id) } },
                            onTogglePinned = { scope.launch { repository.togglePinned(memory.id) } },
                            onToggleRecall = { scope.launch { repository.toggleRecall(memory.id) } },
                        )
                    }
                }
            }
        }
    }

    val dialogMemory = editingMemory ?: MemoryEntry(
        id = UUID.randomUUID().toString(),
        characterId = selectedCharacterId,
        content = "",
        kind = MemoryKind.Fact,
        source = "手动",
        occurredAt = null,
        createdAt = Instant.now(),
        strength = 5,
        pinned = false,
        canRecallProactively = true,
    ).takeIf { creatingMemory }

    dialogMemory?.let { memory ->
        MemoryEditorDialog(
            memory = memory,
            isNew = creatingMemory,
            onDismiss = {
                editingMemory = null
                creatingMemory = false
            },
            onSave = { saved ->
                scope.launch { repository.save(saved) }
                editingMemory = null
                creatingMemory = false
            },
            onDelete = {
                scope.launch { repository.deleteEverywhereEquivalent(memory.id) }
                editingMemory = null
                creatingMemory = false
            },
        )
    }
}

@Composable
private fun MemoryEntryCard(
    memory: MemoryEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleRecall: () -> Unit,
) {
    FeatureCardBox {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(memory.kind.memoryKindLabel(), fontSize = 11.sp) },
                    )
                    if (memory.pinned) Icon(Icons.Outlined.PushPin, "已固定", tint = FeatureBlueGray, modifier = Modifier.size(16.dp))
                    if (memory.canRecallProactively) Icon(Icons.Outlined.AutoAwesome, "可主动回忆", tint = FeatureBlueGray, modifier = Modifier.size(16.dp))
                }
                Text(memory.content, fontSize = 16.sp, lineHeight = 23.sp)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑") }
        }
        HorizontalDivider(color = FeatureBorder)
        MemoryMetricLine("来源", memory.source)
        MemoryMetricLine("强度", "${memory.strength}/10")
        memory.occurredAt?.let { occurred ->
            MemoryMetricLine("发生时间", occurred.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter))
        }
        MemoryMetricLine("创建时间", memory.createdAt.atZone(ZoneId.systemDefault()).format(MemoryDateTimeFormatter))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onTogglePinned) {
                Icon(Icons.Outlined.PushPin, null)
                Spacer(Modifier.width(4.dp))
                Text(if (memory.pinned) "取消固定" else "固定")
            }
            TextButton(onClick = onToggleRecall) {
                Icon(if (memory.canRecallProactively) Icons.Outlined.VisibilityOff else Icons.Outlined.AutoAwesome, null)
                Spacer(Modifier.width(4.dp))
                Text(if (memory.canRecallProactively) "禁止主动回忆" else "允许主动回忆")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MemoryEditorDialog(
    memory: MemoryEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (MemoryEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var content by remember(memory.id) { mutableStateOf(memory.content) }
    var source by remember(memory.id) { mutableStateOf(memory.source) }
    var kind by remember(memory.id) { mutableStateOf(memory.kind) }
    var strength by remember(memory.id) { mutableFloatStateOf(memory.strength.toFloat()) }
    var pinned by remember(memory.id) { mutableStateOf(memory.pinned) }
    var recall by remember(memory.id) { mutableStateOf(memory.canRecallProactively) }
    var occurredAtText by remember(memory.id) {
        mutableStateOf(memory.occurredAt?.atZone(ZoneId.systemDefault())?.format(MemoryInputFormatter).orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加记忆" else "编辑记忆") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("记忆内容") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryKind.entries.forEach { item ->
                        FilterChip(
                            selected = kind == item,
                            onClick = { kind = item },
                            label = { Text(item.memoryKindLabel()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("来源") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = occurredAtText,
                    onValueChange = { occurredAtText = it },
                    label = { Text("发生时间（可空）") },
                    supportingText = { Text("格式：yyyy-MM-dd HH:mm；无法解析时保留为空") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("记忆强度：${strength.toInt()}/10", fontWeight = FontWeight.SemiBold)
                Slider(value = strength, onValueChange = { strength = it }, valueRange = 1f..10f, steps = 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("固定在前面")
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("允许角色主动回忆")
                    Switch(checked = recall, onCheckedChange = { recall = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = {
                    val occurredAt = occurredAtText.trim().takeIf(String::isNotBlank)?.let { text ->
                        runCatching {
                            java.time.LocalDateTime.parse(text, MemoryInputFormatter)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                        }.getOrNull()
                    }
                    onSave(
                        memory.copy(
                            content = content.trim(),
                            kind = kind,
                            source = source.trim().ifBlank { "手动" },
                            occurredAt = occurredAt,
                            strength = strength.toInt().coerceIn(1, 10),
                            pinned = pinned,
                            canRecallProactively = recall,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun MemoryMetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = FeatureBlueGray, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.weight(1f), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun MemoryKind.memoryKindLabel(): String = when (this) {
    MemoryKind.Fact -> "长期事实"
    MemoryKind.Emotion -> "情绪印象"
    MemoryKind.Timeline -> "重要经历"
}

private val MemoryDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val MemoryInputFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
                Surface(shape = RoundedCornerShape(10.dp), color = FeatureWheat, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("露", fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(11.dp))
                Text("露露", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            TabRow(selectedTabIndex = selectedSectionIndex, containerColor = FeatureCard) {
                LexiconSections.forEachIndexed { index, (_, label) ->
                    Tab(selected = selectedSectionIndex == index, onClick = { selectedSectionIndex = index }, text = { Text(label) })
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
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                    if (entry.section == LexiconSection.Promise) {
                                        Text(entry.promiseKind.promiseKindLabel(), color = FeatureBlueGray, fontSize = 11.sp)
                                    }
                                    Text(entry.content, color = FeatureBlueGray)
                                }
                                IconButton(onClick = { editing = entry }) { Icon(Icons.Outlined.Edit, "编辑") }
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
            onDismiss = { editing = null; creating = false },
            onSave = { saved -> scope.launch { LuluRepositories.lexicon.save(saved) }; editing = null; creating = false },
            onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) }; editing = null; creating = false },
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
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("内容") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
                if (entry.section == LexiconSection.Promise) {
                    Text("约定类型", fontWeight = FontWeight.SemiBold)
                    PromiseKinds.chunked(2).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (kind, label) ->
                                FilterChip(selected = promiseKind == kind, onClick = { promiseKind = kind }, label = { Text(label) }, modifier = Modifier.weight(1f))
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
                    onSave(entry.copy(title = title.trim(), content = content.trim(), promiseKind = if (entry.section == LexiconSection.Promise) promiseKind else null, updatedAt = Instant.now()))
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun PromiseKind?.promiseKindLabel(): String = PromiseKinds.firstOrNull { (kind, _) -> kind == this }?.second ?: "承诺"

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
internal fun FeatureCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FeatureCard),
        border = BorderStroke(1.dp, FeatureBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun FeatureEmpty(title: String, text: String) {
    FeatureCardBox {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(text, color = FeatureBlueGray)
    }
}
