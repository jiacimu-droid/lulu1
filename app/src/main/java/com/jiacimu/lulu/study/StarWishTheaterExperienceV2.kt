package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TheaterV2Mode { BOOKSHELF, READER, PLANNER, GENERATOR, ARCHIVE }

@Composable
internal fun StarWishTheaterContentV2(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val customLibrary = remember { StarWishCustomTheaterLibrary.get(context) }
    val archiveStore = remember { StarWishTheaterArchiveStore.create(context) }
    val archives by archiveStore.archives.collectAsState()
    var customTheaters by remember { mutableStateOf(customLibrary.all()) }
    var mode by rememberSaveable { mutableStateOf(TheaterV2Mode.BOOKSHELF) }
    var openedTitle by rememberSaveable { mutableStateOf<String?>(null) }

    val allTheaters = remember(customTheaters) {
        (customTheaters + StarWishRules.theaters).distinctBy { it.title }
    }
    val openedSeed = allTheaters.firstOrNull { it.title == openedTitle }

    BackHandler {
        mode = when (mode) {
            TheaterV2Mode.PLANNER, TheaterV2Mode.GENERATOR -> if (openedSeed == null) TheaterV2Mode.BOOKSHELF else TheaterV2Mode.READER
            TheaterV2Mode.READER -> TheaterV2Mode.BOOKSHELF
            TheaterV2Mode.ARCHIVE -> TheaterV2Mode.BOOKSHELF
            TheaterV2Mode.BOOKSHELF -> {
                onExit()
                TheaterV2Mode.BOOKSHELF
            }
        }
    }

    when (mode) {
        TheaterV2Mode.BOOKSHELF -> TheaterBookshelfV2(
            theaters = allTheaters,
            state = state,
            onBack = onExit,
            onArchive = { mode = TheaterV2Mode.ARCHIVE },
            onOpen = {
                openedTitle = it
                mode = TheaterV2Mode.READER
            },
            onGenerate = { mode = TheaterV2Mode.GENERATOR },
            onDelete = { title ->
                customLibrary.delete(title)
                customTheaters = customLibrary.all()
                store.deleteTheater(title)
            },
        )
        TheaterV2Mode.READER -> if (openedSeed != null) {
            TheaterReaderV2(
                seed = openedSeed,
                state = state,
                studyState = studyState,
                store = store,
                onBack = { mode = TheaterV2Mode.BOOKSHELF },
                onPlanner = { mode = TheaterV2Mode.PLANNER },
                onRegenerate = { mode = TheaterV2Mode.GENERATOR },
            )
        } else {
            mode = TheaterV2Mode.BOOKSHELF
        }
        TheaterV2Mode.PLANNER -> if (openedSeed != null) {
            TheaterPlannerV2(
                title = openedSeed.title,
                initialGuide = state.theaterGuides[openedSeed.title].orEmpty().ifBlank { openedSeed.prompt },
                onBack = { mode = TheaterV2Mode.READER },
                onSave = {
                    store.setGuide(openedSeed.title, it)
                    mode = TheaterV2Mode.READER
                },
                onRegenerate = { mode = TheaterV2Mode.GENERATOR },
            )
        } else {
            mode = TheaterV2Mode.BOOKSHELF
        }
        TheaterV2Mode.GENERATOR -> TheaterPlotGeneratorV2(
            characterId = studyState.profile.selectedCharacterId,
            existingTitle = openedSeed?.title,
            existingGuide = openedSeed?.let { state.theaterGuides[it.title].orEmpty().ifBlank { it.prompt } },
            onBack = { mode = if (openedSeed == null) TheaterV2Mode.BOOKSHELF else TheaterV2Mode.READER },
            onApply = { candidate ->
                if (openedSeed == null) {
                    val seed = StarWishTheaterSeed(candidate.title.trim().ifBlank { "未命名小剧场" }, candidate.worldview.ifBlank { candidate.overview })
                    customLibrary.add(seed)
                    customTheaters = customLibrary.all()
                    store.setGuide(seed.title, candidate.detailedGuide())
                    openedTitle = seed.title
                } else {
                    store.setGuide(openedSeed.title, candidate.detailedGuide())
                }
                mode = TheaterV2Mode.READER
            },
        )
        TheaterV2Mode.ARCHIVE -> TheaterArchiveV2(
            theaters = allTheaters,
            state = state,
            archives = archives,
            onBack = { mode = TheaterV2Mode.BOOKSHELF },
            onSave = { theater ->
                archiveStore.save(
                    theater = theater.title,
                    guide = state.theaterGuides[theater.title].orEmpty().ifBlank { theater.prompt },
                    chapters = state.theaterChapters[theater.title].orEmpty(),
                )
            },
            onRestore = { archive ->
                if (StarWishRules.theaters.none { it.title == archive.theater } && customTheaters.none { it.title == archive.theater }) {
                    customLibrary.add(StarWishTheaterSeed(archive.theater, archive.guide))
                    customTheaters = customLibrary.all()
                }
                store.restoreTheater(archive.theater, archive.guide, archive.chapters)
            },
            onDelete = archiveStore::delete,
        )
    }
}

@Composable
private fun TheaterBookshelfV2(
    theaters: List<StarWishTheaterSeed>,
    state: StarWishState,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onOpen: (String) -> Unit,
    onGenerate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回心愿馆") }
                Text("小剧场", modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onArchive) {
                    Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("存档")
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("故事书架", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("${theaters.size} 个故事", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = onGenerate, shape = RoundedCornerShape(15.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("新故事")
                    }
                }
            }
            items(theaters, key = { it.title }) { seed ->
            val chapters = state.theaterChapters[seed.title].orEmpty()
            val chapterCount = chapters.size
            val custom = seed !in StarWishRules.theaters
            var menu by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(seed.title) },
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(width = 52.dp, height = 68.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(seed.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            if (chapterCount == 0) "尚未开篇" else "$chapterCount 章 · ${chapters.last().title}",
                            color = StudyDesign.muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(
                            if (custom) "自定义" else "内置故事",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (custom) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("删除本书", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onDelete(seed.title) },
                                )
                            }
                        }
                    } else {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = StudyDesign.muted, modifier = Modifier.padding(end = 10.dp))
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TheaterArchiveV2(
    theaters: List<StarWishTheaterSeed>,
    state: StarWishState,
    archives: List<StarWishTheaterArchive>,
    onBack: () -> Unit,
    onSave: (StarWishTheaterSeed) -> Unit,
    onRestore: (StarWishTheaterArchive) -> Unit,
    onDelete: (String) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var restoreCandidate by remember { mutableStateOf<StarWishTheaterArchive?>(null) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回书架") }
                Column(Modifier.weight(1f)) {
                    Text("小剧场存档", fontWeight = FontWeight.Bold)
                    Text("独立保存，不读取聊天记录", color = StudyDesign.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("保存当前版本", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(theaters, key = { "save-${it.title}" }) { theater ->
                val chapterCount = state.theaterChapters[theater.title].orEmpty().size
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(theater.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$chapterCount 章", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton(
                            onClick = {
                                onSave(theater)
                                message = "已保存《${theater.title}》当前版本"
                            },
                        ) { Text("保存") }
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text("历史存档", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (message.isNotBlank()) Text(message, color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
            }
            if (archives.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("还没有存档", modifier = Modifier.fillMaxWidth().padding(24.dp), color = StudyDesign.muted)
                    }
                }
            } else {
                items(archives, key = StarWishTheaterArchive::id) { archive ->
                    val currentCount = state.theaterChapters[archive.theater].orEmpty().size
                    val difference = currentCount - archive.chapters.size
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(archive.theater, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                Instant.ofEpochMilli(archive.savedAtMillis).atZone(ZoneId.systemDefault()).format(TheaterArchiveDateFormatter),
                                color = StudyDesign.muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                when {
                                    difference > 0 -> "存档 ${archive.chapters.size} 章 · 当前版本多 $difference 章"
                                    difference < 0 -> "存档 ${archive.chapters.size} 章 · 当前版本少 ${-difference} 章"
                                    else -> "存档 ${archive.chapters.size} 章 · 与当前章节数相同"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onDelete(archive.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { restoreCandidate = archive }) { Text("恢复此版本") }
                            }
                        }
                    }
                }
            }
        }
    }

    restoreCandidate?.let { archive ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("恢复这个存档？") },
            text = { Text("《${archive.theater}》当前的剧情规划和章节将替换为存档中的版本。") },
            dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    onRestore(archive)
                    restoreCandidate = null
                    message = "已恢复《${archive.theater}》"
                }) { Text("恢复") }
            },
        )
    }
}

private val TheaterArchiveDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TheaterReaderV2(
    seed: StarWishTheaterSeed,
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    onBack: () -> Unit,
    onPlanner: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val chapters = state.theaterChapters[seed.title].orEmpty()
    var selectedIndex by rememberSaveable(seed.title) { mutableIntStateOf((chapters.size - 1).coerceAtLeast(0)) }
    var influence by rememberSaveable(seed.title) { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var chapterMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var composerExpanded by rememberSaveable(seed.title) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(chapters.size) {
        if (chapters.isNotEmpty() && selectedIndex > chapters.lastIndex) selectedIndex = chapters.lastIndex
    }

    fun generateNextChapter() {
        if (generating || chapters.size >= StarWishRules.MAX_CHAPTERS_PER_THEATER) return
        generating = true
        message = ""
        scope.launch {
            val guide = state.theaterGuides[seed.title].orEmpty().ifBlank { seed.prompt }
            val chapterNumber = chapters.size + 1
            val recentChapters = chapters.takeLast(3).joinToString("\n\n") { "${it.title}\n${it.content.takeLast(2200)}" }
            val lastAnchor = chapters.lastOrNull()?.content?.takeLast(1400).orEmpty()
            LuluAiServices.gateway.generate(
                characterId = studyState.profile.selectedCharacterId,
                facts = buildString {
                    appendLine("剧场：《${seed.title}》")
                    appendLine("详细剧情规划：\n$guide")
                    if (recentChapters.isNotBlank()) appendLine("最近章节：\n$recentChapters")
                    if (lastAnchor.isNotBlank()) appendLine("上一章结尾连续性锚点：\n$lastAnchor")
                    if (influence.isNotBlank()) appendLine("用户对下一章的最高优先级影响：${influence.trim()}")
                },
                instruction = """
                    续写第 $chapterNumber 章完整中文小说正文，约1800-3200字，只输出正文。
                    用户影响优先级最高；剧情规划是伏笔地图和导航，不是铁轨。冲突时应延迟、改道或拆分原计划，不能无视用户选择。
                    新章必须发生在上一章最后一句之后，禁止重演已完成的到达、对白、决定、拥抱、战斗、发现或其他动作。
                    写作前在内部确认人物位置、距离、动作状态、情绪、已知信息和未回收伏笔，但不要输出检查过程。
                    使用具体意象、五感、空间关系、动作余韵、心理变化、潜台词和留白。不要流水账，不要空泛宣布情绪，不要为了唯美堆砌辞藻。
                    每章至少推进明线、暗线、关系线中的两条，并在结尾留下自然钩子。
                """.trimIndent(),
                source = "心愿馆",
                title = "${seed.title} · 第${chapterNumber}章",
                temperature = 0.82,
                maxTokens = 4400,
                contextMode = CompanionContextMode.Isolated,
            ).onSuccess { reply ->
                store.addChapter(
                    StarWishTheaterChapter(theater = seed.title, chapter = chapterNumber, title = "第 $chapterNumber 章", content = reply.text, userInfluence = influence.trim()),
                )
                selectedIndex = chapterNumber - 1
                influence = ""
                composerExpanded = false
                message = "第 $chapterNumber 章已生成"
                listState.scrollToItem(0)
            }.onFailure { message = it.message ?: "章节生成失败" }
            generating = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回书架") }
                Column(Modifier.weight(1f)) {
                    Text(seed.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (chapters.isEmpty()) "尚未开篇" else "第 ${selectedIndex + 1} / ${chapters.size} 章",
                        color = StudyDesign.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = { chapterMenu = true }, enabled = chapters.isNotEmpty()) {
                    Icon(Icons.Outlined.FormatListNumbered, "章节")
                }
                IconButton(onClick = onPlanner) {
                    Icon(Icons.Outlined.EditNote, "剧情规划")
                }
                Box {
                    IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("重新生成剧情规划") },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                            onClick = { overflowMenu = false; onRegenerate() },
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        ) {
            if (chapters.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.padding(18.dp).size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("从第一章开始", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("可以直接生成，也可以先调整剧情规划", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                val chapter = chapters[selectedIndex.coerceIn(0, chapters.lastIndex)]
                item {
                    Text(chapter.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(Modifier.height(19.dp))
                    Text(chapter.content, fontSize = 17.sp, lineHeight = 31.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f))
                    Spacer(Modifier.height(32.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { selectedIndex -= 1; scope.launch { listState.scrollToItem(0) } },
                            enabled = selectedIndex > 0,
                        ) { Icon(Icons.Outlined.ChevronLeft, null); Text("上一章") }
                        Text("${selectedIndex + 1} / ${chapters.size}", modifier = Modifier.weight(1f), color = StudyDesign.muted, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(
                            onClick = { selectedIndex += 1; scope.launch { listState.scrollToItem(0) } },
                            enabled = selectedIndex < chapters.lastIndex,
                        ) { Text("下一章"); Icon(Icons.Outlined.ChevronRight, null) }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 5.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 9.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (composerExpanded) {
                    OutlinedTextField(
                        value = influence,
                        onValueChange = { influence = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("写下你希望下一章发生的事……") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { composerExpanded = !composerExpanded }, modifier = Modifier.weight(1f)) {
                        Icon(if (composerExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (composerExpanded) "收起" else if (influence.isBlank()) "影响下一章" else "已写剧情要求")
                    }
                    Button(
                        enabled = !generating && chapters.size < StarWishRules.MAX_CHAPTERS_PER_THEATER,
                        onClick = ::generateNextChapter,
                        modifier = Modifier.weight(1.25f),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        if (generating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (generating) "正在续写" else if (chapters.isEmpty()) "生成第一章" else "续写第 ${chapters.size + 1} 章")
                    }
                }
                if (message.isNotBlank()) Text(message, color = if (message.contains("失败") || message.contains("不足")) MaterialTheme.colorScheme.error else StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (chapterMenu) {
        ModalBottomSheet(
            onDismissRequest = { chapterMenu = false },
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                Text("章节", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(chapters, key = { it.id }) { chapter ->
                        val index = chapters.indexOf(chapter)
                        ListItem(
                            headlineContent = { Text(chapter.title) },
                            trailingContent = { if (index == selectedIndex) Icon(Icons.Outlined.Check, null) },
                            modifier = Modifier.clickable {
                                selectedIndex = index
                                chapterMenu = false
                                scope.launch { listState.scrollToItem(0) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TheaterPlannerV2(
    title: String,
    initialGuide: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    var guide by rememberSaveable(title) { mutableStateOf(initialGuide) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = guide != initialGuide
    fun attemptBack() {
        if (dirty) confirmDiscard = true else onBack()
    }
    BackHandler(onBack = ::attemptBack)

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::attemptBack) { Icon(Icons.Outlined.ArrowBack, "返回阅读") }
                Column(Modifier.weight(1f)) {
                    Text("剧情规划", fontWeight = FontWeight.Bold)
                    Text(title, color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onRegenerate) {
                    Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新生成")
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("故事地图", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("世界观、主线、暗线、关系变化与伏笔都在这里维护", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = guide,
                onValueChange = { guide = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                placeholder = { Text("写下故事总纲与逐章方向") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                shape = RoundedCornerShape(18.dp),
            )
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Button(
                onClick = { onSave(guide.trim()) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp),
                enabled = guide.isNotBlank() && dirty,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (dirty) "保存剧情规划" else "已保存")
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回阅读页后，这次编辑不会保留。") },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("放弃修改") } },
        )
    }
}

@Composable
private fun TheaterPlotGeneratorV2(
    characterId: String,
    existingTitle: String?,
    existingGuide: String?,
    onBack: () -> Unit,
    onApply: (StarWishPlotCandidate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var direction by rememberSaveable { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<StarWishPlotCandidate>>(emptyList()) }
    var expandedIndex by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !generating) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Column(Modifier.weight(1f)) {
                    Text(if (existingTitle == null) "创建小剧场" else "重新规划", fontWeight = FontWeight.Bold)
                    existingTitle?.let { Text(it, color = StudyDesign.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text("你想看什么故事？", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("留空也可以，让角色自由构思", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = direction,
                            onValueChange = { direction = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("例如：系统轻喜剧、悬疑、慢热关系……") },
                            minLines = 3,
                            maxLines = 7,
                            shape = RoundedCornerShape(16.dp),
                        )
                        Button(
                            onClick = {
                                generating = true
                                error = ""
                                scope.launch {
                                    StarWishPlotPlanner.generate(characterId, existingTitle, existingGuide, direction.trim())
                                        .onSuccess { candidates = it; expandedIndex = 0 }
                                        .onFailure { error = it.message ?: "剧情规划生成失败" }
                                    generating = false
                                }
                            },
                            enabled = !generating,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(15.dp),
                        ) {
                            if (generating) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (generating) "正在构思三套方案" else if (candidates.isEmpty()) "生成三套剧情方案" else "重新生成三套方案")
                        }
                    }
                }
            }
            if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            if (candidates.isNotEmpty()) item { Text("选择一套剧情", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(candidates.size) { index ->
                val item = candidates[index]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (expandedIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("方案 ${index + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            IconButton(onClick = { expandedIndex = if (expandedIndex == index) -1 else index }) {
                                Icon(if (expandedIndex == index) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开")
                            }
                        }
                        Text(item.hook, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
                        if (expandedIndex == index) {
                            PlotSection("世界观", item.worldview)
                            PlotSection("故事总纲", item.overview)
                            PlotSection("关系主线", item.relationshipCore)
                            PlotSection("明线", item.mainLine)
                            PlotSection("暗线", item.hiddenLine)
                            PlotSection("伏笔系统", item.foreshadowing)
                            PlotSection("情绪曲线", item.emotionalArc)
                            PlotSection("文风执行", item.proseStyle)
                            item.chapters.forEachIndexed { chapterIndex, chapter -> PlotSection("第${chapterIndex + 1}章", chapter) }
                        }
                        Button(onClick = { onApply(item) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Text(if (existingTitle == null) "选择这套并加入书架" else "应用这套剧情规划")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlotSection(title: String, content: String) {
    if (content.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Text(content, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
    }
}
