package com.jiacimu.lulu.study

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
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.launch

private enum class TheaterV2Mode { BOOKSHELF, READER, PLANNER, GENERATOR }

@Composable
internal fun StarWishTheaterContentV2(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
) {
    val context = LocalContext.current
    val customLibrary = remember { StarWishCustomTheaterLibrary.get(context) }
    var customTheaters by remember { mutableStateOf(customLibrary.all()) }
    var mode by rememberSaveable { mutableStateOf(TheaterV2Mode.BOOKSHELF) }
    var openedTitle by rememberSaveable { mutableStateOf<String?>(null) }

    val allTheaters = remember(customTheaters) {
        (customTheaters + StarWishRules.theaters).distinctBy { it.title }
    }
    val openedSeed = allTheaters.firstOrNull { it.title == openedTitle }

    when (mode) {
        TheaterV2Mode.BOOKSHELF -> TheaterBookshelfV2(
            theaters = allTheaters,
            state = state,
            fragmentCount = studyState.inventory.theaterFragments,
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
                studyStore = studyStore,
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
    }
}

@Composable
private fun TheaterBookshelfV2(
    theaters: List<StarWishTheaterSeed>,
    state: StarWishState,
    fragmentCount: Int,
    onOpen: (String) -> Unit,
    onGenerate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("剧情生成器 · 给我三套故事")
            }
        }
        item {
            Text(
                "小剧场券 $fragmentCount",
                color = StudyDesign.muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        items(theaters, key = { it.title }) { seed ->
            val chapterCount = state.theaterChapters[seed.title].orEmpty().size
            var menu by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(seed.title) },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 17.dp, top = 16.dp, bottom = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(
                            Icons.Outlined.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.padding(11.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(seed.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (chapterCount == 0) "尚未开篇" else "$chapterCount 章 · 继续阅读",
                            color = StudyDesign.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (seed !in StarWishRules.theaters) {
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
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = StudyDesign.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun TheaterReaderV2(
    seed: StarWishTheaterSeed,
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
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
    val listState = rememberLazyListState()

    LaunchedEffect(chapters.size) {
        if (chapters.isNotEmpty() && selectedIndex > chapters.lastIndex) selectedIndex = chapters.lastIndex
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = StudyDesign.paper, tonalElevation = 1.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回书架") }
                Text(seed.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { chapterMenu = true }, enabled = chapters.isNotEmpty()) {
                    Icon(Icons.Outlined.FormatListNumbered, "章节")
                }
                Box {
                    IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("剧情规划") },
                            leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                            onClick = { overflowMenu = false; onPlanner() },
                        )
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
            contentPadding = PaddingValues(horizontal = 21.dp, vertical = 20.dp),
        ) {
            if (chapters.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.size(42.dp), tint = StudyDesign.muted)
                            Spacer(Modifier.height(12.dp))
                            Text("故事还没有开始", color = StudyDesign.muted)
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
                    Text("第 ${selectedIndex + 1} / ${chapters.size} 章", color = StudyDesign.muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = influence,
                    onValueChange = { influence = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("我想影响下一章……") },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                )
                Button(
                    enabled = !generating && studyState.inventory.theaterFragments >= StarWishRules.THEATER_FRAGMENTS_PER_CHAPTER && chapters.size < StarWishRules.MAX_CHAPTERS_PER_THEATER,
                    onClick = {
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
                            ).onSuccess { reply ->
                                if (StarWishInventoryBridge.consumeTheaterFragment(studyStore)) {
                                    store.addChapter(StarWishTheaterChapter(theater = seed.title, chapter = chapterNumber, title = "第 $chapterNumber 章", content = reply.text, userInfluence = influence.trim()))
                                    selectedIndex = chapterNumber - 1
                                    influence = ""
                                    message = "第 $chapterNumber 章已生成"
                                    listState.scrollToItem(0)
                                } else message = "小剧场券不足"
                            }.onFailure { message = it.message ?: "章节生成失败" }
                            generating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (generating) "正在续写" else "续写下一章")
                }
                if (message.isNotBlank()) Text(message, color = if (message.contains("失败") || message.contains("不足")) MaterialTheme.colorScheme.error else StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (chapterMenu) {
        AlertDialog(
            onDismissRequest = { chapterMenu = false },
            title = { Text("章节导航") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
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
            },
            confirmButton = { TextButton(onClick = { chapterMenu = false }) { Text("关闭") } },
        )
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
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回阅读") }
            Column(Modifier.weight(1f)) {
                Text("剧情规划", fontWeight = FontWeight.Bold)
                Text(title, color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        OutlinedTextField(
            value = guide,
            onValueChange = { guide = it },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("世界观、总纲、明暗线、伏笔与逐章指导") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
        )
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.weight(1f)) { Text("重新生成三套") }
            Button(onClick = { onSave(guide.trim()) }, modifier = Modifier.weight(1f), enabled = guide.isNotBlank()) { Text("保存规划") }
        }
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
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Text(if (existingTitle == null) "剧情生成器" else "《$existingTitle》的剧情规划", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = direction,
                    onValueChange = { direction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("我想要的故事方向") },
                    placeholder = { Text("例如：被攻略、系统轻喜剧、恐怖加麻加辣……") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
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
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (generating) "正在构造三套故事" else "生成三套剧情规划")
                }
            }
            if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            items(candidates.size) { index ->
                val item = candidates[index]
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("方案 ${index + 1}", color = StudyDesign.muted, style = MaterialTheme.typography.labelMedium)
                                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            IconButton(onClick = { expandedIndex = if (expandedIndex == index) -1 else index }) {
                                Icon(if (expandedIndex == index) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开")
                            }
                        }
                        Text(item.hook, color = MaterialTheme.colorScheme.primary)
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
                        Button(onClick = { onApply(item) }, modifier = Modifier.fillMaxWidth()) {
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
