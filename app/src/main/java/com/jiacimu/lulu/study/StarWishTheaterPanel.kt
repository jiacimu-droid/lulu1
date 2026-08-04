package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.launch

@Composable
internal fun StarWishTheaterContent(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
) {
    var openedTheater by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openedTheater != null) { openedTheater = null }

    if (openedTheater == null) {
        TheaterBookshelf(
            state = state,
            onOpen = { openedTheater = it },
        )
    } else {
        TheaterReader(
            theaterTitle = openedTheater!!,
            state = state,
            studyState = studyState,
            store = store,
            studyStore = studyStore,
            onBack = { openedTheater = null },
        )
    }
}

@Composable
private fun TheaterBookshelf(
    state: StarWishState,
    onOpen: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(StarWishRules.theaters, key = { it.title }) { seed ->
            val chapterCount = state.theaterChapters[seed.title].orEmpty().size
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(seed.title) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            seed.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (chapterCount == 0) "尚未开始" else "已读到第 $chapterCount 章",
                            color = StudyDesign.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "打开",
                        tint = StudyDesign.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun TheaterReader(
    theaterTitle: String,
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val seed = StarWishRules.theaters.first { it.title == theaterTitle }
    val chapters = state.theaterChapters[theaterTitle].orEmpty()
    var selectedChapterIndex by rememberSaveable(theaterTitle) {
        mutableIntStateOf((chapters.size - 1).coerceAtLeast(0))
    }
    var influence by rememberSaveable(theaterTitle) { mutableStateOf("") }
    var guide by remember(theaterTitle, state.theaterGuides) {
        mutableStateOf(state.theaterGuides[theaterTitle] ?: seed.prompt)
    }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showChapterDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(chapters.size) {
        if (chapters.isNotEmpty() && selectedChapterIndex > chapters.lastIndex) {
            selectedChapterIndex = chapters.lastIndex
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = StudyDesign.paper, tonalElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回书架")
                }
                Text(
                    theaterTitle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("剧情规划") },
                            leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                            onClick = {
                                menuExpanded = false
                                showGuideDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("章节导航") },
                            leadingIcon = { Icon(Icons.Outlined.FormatListNumbered, null) },
                            enabled = chapters.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                showChapterDialog = true
                            },
                        )
                        if (chapters.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("清空本书", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteSweep,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showClearDialog = true
                                },
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        ) {
            if (chapters.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(bottom = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "故事还没有开始",
                            color = StudyDesign.muted,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                val chapter = chapters[selectedChapterIndex.coerceIn(0, chapters.lastIndex)]
                item {
                    Text(
                        chapter.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        chapter.content,
                        fontSize = 17.sp,
                        lineHeight = 30.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "第 ${selectedChapterIndex + 1} / ${chapters.size} 章",
                        modifier = Modifier.fillMaxWidth(),
                        color = StudyDesign.muted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = influence,
                    onValueChange = { influence = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("我想影响下一章……") },
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                )
                Button(
                    enabled = !generating &&
                        studyState.inventory.theaterFragments >= StarWishRules.THEATER_FRAGMENTS_PER_CHAPTER &&
                        chapters.size < StarWishRules.MAX_CHAPTERS_PER_THEATER,
                    onClick = {
                        generating = true
                        message = ""
                        scope.launch {
                            val history = chapters.joinToString("\n\n") { chapter ->
                                "${chapter.title}\n${chapter.content}"
                            }
                            val chapterNumber = chapters.size + 1
                            LuluAiServices.gateway.generate(
                                characterId = studyState.profile.selectedCharacterId,
                                facts = buildString {
                                    appendLine("剧场：${seed.title}")
                                    appendLine("核心设定：$guide")
                                    if (history.isNotBlank()) {
                                        appendLine("已发生章节：")
                                        appendLine(history)
                                    }
                                    if (influence.isNotBlank()) {
                                        appendLine("主人对下一章的影响：$influence")
                                    }
                                },
                                instruction = "续写第 $chapterNumber 章完整中文故事，正文约 1800-3000 字。保持人物、时间线和因果连续；主人影响必须自然进入剧情；不要写提纲、解释或系统提示。",
                                source = "星愿馆",
                                title = "${seed.title} · 第${chapterNumber}章",
                                temperature = 0.9,
                                maxTokens = 4200,
                            ).onSuccess { reply ->
                                if (StarWishInventoryBridge.consumeTheaterFragment(studyStore)) {
                                    store.addChapter(
                                        StarWishTheaterChapter(
                                            theater = theaterTitle,
                                            chapter = chapterNumber,
                                            title = "第 $chapterNumber 章",
                                            content = reply.text,
                                            userInfluence = influence.trim(),
                                        ),
                                    )
                                    selectedChapterIndex = chapterNumber - 1
                                    influence = ""
                                    message = "第 $chapterNumber 章已生成"
                                } else {
                                    message = "小剧场券不足"
                                }
                            }.onFailure { error ->
                                message = error.message ?: "章节生成失败"
                            }
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
                if (message.isNotBlank()) {
                    Text(
                        message,
                        color = if (message.contains("失败") || message.contains("不足")) MaterialTheme.colorScheme.error else StudyDesign.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("剧情规划") },
            text = {
                OutlinedTextField(
                    value = guide,
                    onValueChange = { guide = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("故事设定与章节方向") },
                    minLines = 8,
                    maxLines = 14,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.setGuide(theaterTitle, guide)
                    showGuideDialog = false
                    message = "剧情规划已保存"
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showGuideDialog = false }) { Text("取消") }
            },
        )
    }

    if (showChapterDialog) {
        AlertDialog(
            onDismissRequest = { showChapterDialog = false },
            title = { Text("章节导航") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(chapters, key = { it.id }) { chapter ->
                        val index = chapters.indexOf(chapter)
                        ListItem(
                            headlineContent = { Text(chapter.title) },
                            trailingContent = {
                                if (index == selectedChapterIndex) {
                                    Icon(Icons.Outlined.Check, contentDescription = null)
                                }
                            },
                            modifier = Modifier.clickable {
                                selectedChapterIndex = index
                                showChapterDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapterDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空本书？") },
            text = { Text("已生成的全部章节会被删除，这个操作不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteTheater(theaterTitle)
                    selectedChapterIndex = 0
                    showClearDialog = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}
