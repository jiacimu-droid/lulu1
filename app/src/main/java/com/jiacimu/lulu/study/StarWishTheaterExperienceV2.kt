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
import com.jiacimu.lulu.ScopedModelArchiveIconButton
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.launch

private enum class TheaterV2Mode { BOOKSHELF, READER, PLANNER, GENERATOR }

@Composable
internal fun StarWishTheaterContentV2(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val customLibrary = remember { StarWishCustomTheaterLibrary.get(context) }
    val generationManager = remember { StarWishTheaterGenerationManager.get(context) }
    val generationTasks by generationManager.tasks.collectAsState()
    var customTheaters by remember { mutableStateOf(customLibrary.all()) }
    var mode by rememberSaveable { mutableStateOf(TheaterV2Mode.BOOKSHELF) }
    var openedTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTheaterTitle by remember { mutableStateOf<String?>(null) }

    val allTheaters = remember(customTheaters) {
        (customTheaters + StarWishRules.theaters).distinctBy { it.title }
    }
    val openedSeed = allTheaters.firstOrNull { it.title == openedTitle }

    BackHandler {
        mode = when (mode) {
            TheaterV2Mode.PLANNER, TheaterV2Mode.GENERATOR -> if (openedSeed == null) TheaterV2Mode.BOOKSHELF else TheaterV2Mode.READER
            TheaterV2Mode.READER -> TheaterV2Mode.BOOKSHELF
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
            generationTasks = generationTasks,
            onBack = onExit,
            onOpen = {
                openedTitle = it
                mode = TheaterV2Mode.READER
            },
            onGenerate = { mode = TheaterV2Mode.GENERATOR },
            onDelete = { title -> deleteTheaterTitle = title },
        )
        TheaterV2Mode.READER -> if (openedSeed != null) {
            TheaterReaderV2(
                seed = openedSeed,
                state = state,
                store = store,
                generationManager = generationManager,
                task = generationTasks[openedSeed.title],
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
                initialGuide = starWishGuideWithoutLegacyPlans(state.theaterGuides[openedSeed.title].orEmpty().ifBlank { openedSeed.prompt }),
                initialPlans = state.theaterPlans[openedSeed.title].orEmpty().ifEmpty {
                    starWishPlansFromLegacyGuide(state.theaterGuides[openedSeed.title].orEmpty())
                },
                onBack = { mode = TheaterV2Mode.READER },
                onSave = { guide, plans ->
                    store.setStoryPlan(openedSeed.title, guide, plans)
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
                    val baseTitle = candidate.title.trim().ifBlank { "未命名小剧场" }
                    val usedTitles = allTheaters.mapTo(mutableSetOf()) { it.title }
                    var uniqueTitle = baseTitle
                    var suffix = 2
                    while (uniqueTitle in usedTitles) uniqueTitle = "$baseTitle（${suffix++}）"
                    val seed = StarWishTheaterSeed(uniqueTitle, candidate.worldview.ifBlank { candidate.overview })
                    customLibrary.add(seed)
                    customTheaters = customLibrary.all()
                    store.setStoryPlan(seed.title, candidate.storyGuide(), candidate.chapterPlans())
                    openedTitle = seed.title
                } else {
                    store.setStoryPlan(openedSeed.title, candidate.storyGuide(), candidate.chapterPlans())
                }
                mode = TheaterV2Mode.READER
            },
        )
    }

    deleteTheaterTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { deleteTheaterTitle = null },
            title = { Text("删除《$title》？") },
            text = { Text("书架中的剧情地图、章节正文和连续性记录都会删除，且无法恢复。") },
            dismissButton = { TextButton(onClick = { deleteTheaterTitle = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    generationManager.cancel(title)
                    customLibrary.delete(title)
                    customTheaters = customLibrary.all()
                    store.deleteTheater(title)
                    deleteTheaterTitle = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun TheaterBookshelfV2(
    theaters: List<StarWishTheaterSeed>,
    state: StarWishState,
    generationTasks: Map<String, StarWishTheaterTask>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onGenerate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回心愿馆") }
                Text("小剧场", modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                ScopedModelArchiveIconButton(
                    scope = ScopedModelSelections.THEATER,
                    title = "小剧场模型",
                    subtitle = "只用于小剧场规划与续写，不会改变聊天、跑团或末世求生的模型。",
                    contentDescription = "选择小剧场模型",
                    tint = MaterialTheme.colorScheme.onSurface,
                    showLabel = true,
                )
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
            val task = generationTasks[seed.title]
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
                            when {
                                task?.active == true -> "第 ${task.chapterNumber} 章生成中 · 退出页面仍会继续"
                                task?.status == StarWishTheaterTaskStatus.FAILED -> "生成失败 · ${task.message}"
                                chapterCount == 0 -> "尚未开篇"
                                else -> "$chapterCount 章 · ${chapters.last().title}"
                            },
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TheaterReaderV2(
    seed: StarWishTheaterSeed,
    state: StarWishState,
    store: StarWishStore,
    generationManager: StarWishTheaterGenerationManager,
    task: StarWishTheaterTask?,
    onBack: () -> Unit,
    onPlanner: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val chapters = state.theaterChapters[seed.title].orEmpty()
    var selectedIndex by rememberSaveable(seed.title) { mutableIntStateOf((chapters.size - 1).coerceAtLeast(0)) }
    var influence by rememberSaveable(seed.title) { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var chapterMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var composerExpanded by rememberSaveable(seed.title) { mutableStateOf(false) }
    var editChapter by remember { mutableStateOf<StarWishTheaterChapter?>(null) }
    var confirmDeleteFrom by remember { mutableStateOf<StarWishTheaterChapter?>(null) }
    val listState = rememberLazyListState()
    val generating = task?.active == true
    val selectedChapter = chapters.getOrNull(selectedIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)))

    LaunchedEffect(chapters.size) {
        if (chapters.isNotEmpty() && selectedIndex > chapters.lastIndex) selectedIndex = chapters.lastIndex
    }

    LaunchedEffect(task?.status, chapters.size) {
        if (task?.status == StarWishTheaterTaskStatus.SUCCEEDED && chapters.isNotEmpty()) {
            selectedIndex = chapters.lastIndex
            listState.scrollToItem(0)
        }
    }

    fun generateNextChapter() {
        if (generating || chapters.size >= StarWishRules.MAX_CHAPTERS_PER_THEATER) return
        message = ""
        if (state.theaterGuides[seed.title].isNullOrBlank()) {
            store.setStoryPlan(seed.title, seed.prompt, state.theaterPlans[seed.title].orEmpty())
        }
        generationManager.enqueue(seed.title, influence)
            .onSuccess {
                influence = ""
                composerExpanded = false
                message = "已加入后台生成；退出页面也会继续"
            }
            .onFailure { message = it.message ?: "无法开始生成" }
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
                IconButton(
                    onClick = { confirmDeleteFrom = selectedChapter },
                    enabled = selectedChapter != null && !generating,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, "删除本章", tint = MaterialTheme.colorScheme.error)
                }
                Box {
                    IconButton(onClick = { overflowMenu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                        if (selectedChapter != null) {
                            DropdownMenuItem(
                                text = { Text("编辑本章") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { overflowMenu = false; editChapter = selectedChapter },
                                enabled = !generating,
                            )
                            HorizontalDivider()
                        }
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
                val statusMessage = when {
                    task?.active == true || task?.status == StarWishTheaterTaskStatus.FAILED -> task.message
                    message.isNotBlank() -> message
                    else -> task?.message.orEmpty()
                }
                if (statusMessage.isNotBlank()) Text(
                    statusMessage,
                    color = if (task?.status == StarWishTheaterTaskStatus.FAILED || statusMessage.contains("失败") || statusMessage.contains("不足")) MaterialTheme.colorScheme.error else StudyDesign.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
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

    editChapter?.let { chapter ->
        TheaterChapterEditDialog(
            chapter = chapter,
            onDismiss = { editChapter = null },
            onSave = { title, content ->
                store.updateChapter(seed.title, chapter.id, title, content)
                editChapter = null
                message = "本章已保存；后续生成会使用修改后的正文"
            },
        )
    }

    confirmDeleteFrom?.let { chapter ->
        TheaterDestructiveDialog(
            title = "删除第 ${chapter.chapter} 章及后续？",
            body = "删除后不能恢复。为保证剧情连续，第 ${chapter.chapter} 章及其后所有章节都会一起删除。之后可以点击生成重新写。",
            confirmLabel = "删除",
            onDismiss = { confirmDeleteFrom = null },
            onConfirm = {
                store.deleteChaptersFrom(seed.title, chapter.chapter)
                selectedIndex = (chapter.chapter - 2).coerceAtLeast(0)
                confirmDeleteFrom = null
                message = "已删除第 ${chapter.chapter} 章及后续；可以手动生成新章节"
            },
        )
    }
}

@Composable
private fun TheaterChapterEditDialog(
    chapter: StarWishTheaterChapter,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember(chapter.id) { mutableStateOf(chapter.title) }
    var content by remember(chapter.id) { mutableStateOf(chapter.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑第 ${chapter.chapter} 章") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("章节标题") })
                OutlinedTextField(
                    content,
                    { content = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 460.dp),
                    label = { Text("正文") },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), content.trim()) }, enabled = title.isNotBlank() && content.isNotBlank()) {
                Text("保存")
            }
        },
    )
}

@Composable
private fun TheaterDestructiveDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
    )
}

@Composable
private fun TheaterPlannerV2(
    title: String,
    initialGuide: String,
    initialPlans: List<StarWishChapterPlan>,
    onBack: () -> Unit,
    onSave: (String, List<StarWishChapterPlan>) -> Unit,
    onRegenerate: () -> Unit,
) {
    var guide by rememberSaveable(title) { mutableStateOf(initialGuide) }
    var plans by remember(title, initialPlans) { mutableStateOf(initialPlans) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = guide != initialGuide || plans != initialPlans
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
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("故事地图", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("总纲管全局，章节地图可以随时向后增加，不受最初六章限制", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(
                    value = guide,
                    onValueChange = { guide = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    label = { Text("世界观、总纲、明暗线、关系与伏笔") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("逐章规划", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${plans.size} 章规划 · 已写完也可以继续新增", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(
                        onClick = {
                            val number = plans.size + 1
                            plans = plans + StarWishChapterPlan(number = number, title = "第 $number 章", outline = "")
                        },
                    ) { Icon(Icons.Outlined.Add, null); Text("加一章") }
                }
            }
            items(plans, key = StarWishChapterPlan::id) { plan ->
                val index = plans.indexOfFirst { it.id == plan.id }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${index + 1} 章", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = {
                                plans = plans.filterNot { it.id == plan.id }.mapIndexed { planIndex, item -> item.copy(number = planIndex + 1) }
                            }) { Icon(Icons.Outlined.DeleteOutline, "删除章节规划", tint = MaterialTheme.colorScheme.error) }
                        }
                        OutlinedTextField(
                            value = plan.title,
                            onValueChange = { value -> plans = plans.map { if (it.id == plan.id) it.copy(title = value) else it } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("章节标题") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = plan.outline,
                            onValueChange = { value -> plans = plans.map { if (it.id == plan.id) it.copy(outline = value) else it } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("本章事件、人物选择、关系变化、伏笔与结尾钩子") },
                            minLines = 4,
                            maxLines = 10,
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        val start = plans.size + 1
                        plans = plans + (start until start + 3).map { number ->
                            StarWishChapterPlan(number = number, title = "第 $number 章", outline = "待规划")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Outlined.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("一次追加三章") }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Button(
                onClick = { onSave(guide.trim(), plans) },
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
                            Text("留空也可以，让模型自由构思", color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
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
