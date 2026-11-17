package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CompanionActionRuntime
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

private object ReadingInk {
    val paper = Color(0xFFFAF9F6)
    val ink = Color(0xFF252523)
    val subtle = Color(0xFF77736D)
    val line = Color(0xFFE7E4DE)
    val accent = Color(0xFF74614F)
    val soft = Color(0xFFF1EEE8)
}

internal data class ReadingPassage(
    val title: String,
    val start: Int,
    val end: Int,
    val content: String,
    val firstInChapter: Boolean,
)

/** Break large TXT novels into bounded Compose text blocks, preserving every character. */
internal fun readingPassages(book: BackgroundReadingBook, maxLength: Int = 1800): List<ReadingPassage> {
    if (book.content.isEmpty()) return emptyList()
    val limit = maxLength.coerceAtLeast(200)
    return buildList {
        readingSections(book).forEach { section ->
            var start = section.start
            var first = true
            while (start < section.end) {
                val upper = minOf(start + limit, section.end)
                val lastNewline = if (upper < section.end) book.content.lastIndexOf('\n', upper - 1) else -1
                val end = if (lastNewline >= start + limit / 2) lastNewline + 1 else upper
                add(ReadingPassage(section.title, start, end,
                    book.content.substring(start, end), first))
                first = false
                start = end
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LuluReadingScreen(
    onBack: () -> Unit,
    initialBookTitle: String? = null,
    onOpenStoryStudio: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val star by StarWishStores.main.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val reflections by ReadingReflectionStore.records.collectAsState()
    val miniLibrary = remember(context) { MiniTheaterLibrary.get(context) }
    val miniStories by miniLibrary.items.collectAsState()
    var libraryVersion by remember { mutableIntStateOf(0) }
    val books = remember(star.theaterChapters, miniStories, libraryVersion) {
        ReadingBackgroundBridge.userBooks(context)
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var characterId by rememberSaveable {
        mutableStateOf(characters.values.firstOrNull()?.characterId.orEmpty())
    }
    var showCompanion by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val prefs = remember(context) { context.getSharedPreferences("lulu_reader_ui_v2", 0) }
    var fontSize by rememberSaveable {
        mutableIntStateOf(prefs.getInt("font_size", 18).coerceIn(15, 26))
    }
    LaunchedEffect(fontSize) { prefs.edit().putInt("font_size", fontSize).apply() }

    val targetId = initialBookTitle?.takeIf { it.startsWith("reading-record:") }
        ?.removePrefix("reading-record:")
    val target = targetId?.let(ReadingReflectionStore::get)
    val selected = books.firstOrNull { it.id == selectedId }
    val passages = remember(selected?.id, selected?.content) {
        selected?.let { readingPassages(it) }.orEmpty()
    }
    val listState = rememberLazyListState()

    LaunchedEffect(books, initialBookTitle) {
        ReadingReflectionStore.migrateLegacyHistory(context)
        if (selectedId == null && initialBookTitle != null) {
            val record = targetId?.let(ReadingReflectionStore::get)
            val title = record?.bookTitle ?: initialBookTitle
            val book = books.firstOrNull { it.id == record?.bookId }
                ?: books.firstOrNull { it.title == title }
                ?: books.firstOrNull { title.contains(it.title) || it.title.contains(title) }
            selectedId = book?.id
            if (record != null) characterId = record.characterId
            if (book == null) notice = "没有找到对应书籍，原文可能已被删除。"
        }
    }
    LaunchedEffect(selected?.id, target?.id) {
        if (selected == null) {
            listState.scrollToItem(0)
        } else {
            val targetIndex = target?.takeIf { it.bookId == selected.id }
                ?.let { record -> passages.indexOfFirst { record.startOffset in it.start until it.end } }
                ?.takeIf { it >= 0 }?.plus(1)
            val storedIndex = prefs.getInt("position:${selected.id}", 0)
            listState.scrollToItem((targetIndex ?: storedIndex)
                .coerceIn(0, passages.size))
            // Restore before observing, otherwise an initial index=0 write may
            // erase the previously saved position as the screen is composed.
            snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
                prefs.edit().putInt("position:${selected.id}", index).apply()
            }
        }
    }
    BackHandler(enabled = selected != null) {
        selectedId = null
        showCompanion = false
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            notice = ""
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ReadingTxtLibrary.importBook(context, uri) }
                }
                importing = false
                result.onSuccess { book ->
                    libraryVersion++
                    selectedId = book.id
                }.onFailure { notice = "导入失败：${it.message ?: "请检查文件内容"}" }
            }
        }
    }
    Scaffold(containerColor = ReadingInk.paper, topBar = {
        TopAppBar(
            title = {
                Text(if (selected == null) "阅读" else "正在阅读",
                    fontWeight = FontWeight.Medium, fontSize = 17.sp,
                    color = ReadingInk.ink)
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (selected == null) onBack() else {
                        selectedId = null
                        showCompanion = false
                    }
                }) { Icon(Icons.Outlined.ArrowBack, "返回", tint = ReadingInk.ink) }
            },
            actions = {
                if (selected == null) {
                    TextButton(enabled = !importing, onClick = {
                        importer.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                    }) {
                        Icon(Icons.Outlined.UploadFile, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (importing) "导入中" else "导入 TXT")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ReadingInk.paper),
        )
    }) { inset ->
        if (selected == null) {
            LazyColumn(Modifier.fillMaxSize().padding(inset),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 25.dp, bottom = 50.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item {
                    Text("THE READING ROOM", fontSize = 10.sp,
                        letterSpacing = 2.sp, color = ReadingInk.accent)
                    Spacer(Modifier.height(15.dp))
                    Text("在文字里，住一会儿", fontSize = 28.sp,
                        lineHeight = 37.sp, fontFamily = FontFamily.Serif,
                        color = ReadingInk.ink)
                    Spacer(Modifier.height(13.dp))
                    Text("一个安静的书架，留给喜欢的故事。",
                        color = ReadingInk.subtle, fontSize = 13.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(34.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("我的书架", fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, color = ReadingInk.ink)
                        Spacer(Modifier.weight(1f))
                        Text("${books.size} 本", fontSize = 12.sp, color = ReadingInk.subtle)
                    }
                    Spacer(Modifier.height(9.dp))
                    HorizontalDivider(color = ReadingInk.line)
                }
                if (books.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 47.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("你的第一本书，从这里开始", color = ReadingInk.ink,
                            fontSize = 16.sp, fontFamily = FontFamily.Serif)
                        Text("支持从手机文件中导入 .txt 小说或文章",
                            color = ReadingInk.subtle, fontSize = 12.sp)
                        OutlinedButton(enabled = !importing, onClick = {
                            importer.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                        }) { Text("选择 TXT 文件") }
                    }
                }
                items(books, key = { it.id }) { book ->
                    Surface(onClick = {
                        selectedId = book.id
                        notice = ""
                    }, color = Color.Transparent) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(width = 48.dp, height = 68.dp)
                                    .background(ReadingInk.soft, RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("书", fontSize = 23.sp, color = ReadingInk.accent,
                                    fontFamily = FontFamily.Serif)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(book.title, fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium, color = ReadingInk.ink,
                                    maxLines = 2, lineHeight = 24.sp)
                                Text("${book.source}  ·  ${book.content.length} 字",
                                    color = ReadingInk.subtle, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.ChevronRight, null, tint = ReadingInk.subtle,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(color = ReadingInk.line)
                }
                if (onOpenStoryStudio != null) item {
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = onOpenStoryStudio) {
                        Text("打开原有故事创作与存档",
                            color = ReadingInk.subtle, fontSize = 12.sp)
                    }
                }
                if (notice.isNotBlank()) item {
                    Text(notice, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
        } else {
            val at = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
            val currentOffset = passages.getOrNull(at)?.start ?: 0
            val percent = if (selected.content.isEmpty()) 0f
                else currentOffset.toFloat() / selected.content.length
            Column(Modifier.fillMaxSize().padding(inset)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 13.dp, top = 4.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("阅读进度 ${(percent * 100).toInt()}%",
                        fontSize = 11.sp, color = ReadingInk.subtle,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showCompanion = true }) { Text("角色共读", fontSize = 12.sp) }
                    TextButton(enabled = fontSize > 15, onClick = { fontSize-- },
                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("A−", fontSize = 16.sp, color = ReadingInk.ink)
                    }
                    TextButton(enabled = fontSize < 26, onClick = { fontSize++ },
                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("A+", fontSize = 16.sp, color = ReadingInk.ink)
                    }
                }
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth()
                    .height(2.dp), color = ReadingInk.accent,
                    trackColor = ReadingInk.line)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 30.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "book-opening") {
                        Text("READING  /  ${selected.source.uppercase()}",
                            fontSize = 10.sp, letterSpacing = 1.4.sp, color = ReadingInk.accent)
                        Spacer(Modifier.height(19.dp))
                        Text(selected.title, fontSize = 30.sp, lineHeight = 42.sp,
                            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif,
                            color = ReadingInk.ink)
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = ReadingInk.line)
                        Spacer(Modifier.height(23.dp))
                    }
                    items(passages, key = { "${selected.id}:${it.start}" }) { passage ->
                        if (passage.firstInChapter && passage.title != selected.title) {
                            Text(passage.title, fontSize = (fontSize + 3).sp,
                                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                                color = ReadingInk.ink, lineHeight = (fontSize + 12).sp,
                                modifier = Modifier.padding(top = 22.dp, bottom = 15.dp))
                        }
                        val text = if (passage.firstInChapter && passage.title != selected.title)
                            passage.content.trimStart().removePrefix(passage.title).trimStart()
                            else passage.content.trim()
                        if (text.isNotBlank()) SelectionContainer {
                            Text(text, fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.8f).sp,
                                color = ReadingInk.ink,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp))
                        }
                    }
                    item(key = "book-ending") {
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = ReadingInk.line)
                        Text("—  全文完  —", modifier = Modifier.fillMaxWidth()
                            .padding(top = 25.dp), color = ReadingInk.subtle,
                            fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if (showCompanion && selected != null) {
        val currentRevision = remember(selected.id, selected.content) { readingRevision(selected) }
        val bookRecords = reflections.filter {
            it.bookId == selected.id && it.characterId == characterId && it.revision == currentRevision
        }
        AlertDialog(onDismissRequest = { showCompanion = false },
            title = { Text("和角色一起读") },
            text = {
                Column(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        characters.values.forEach { character ->
                            FilterChip(selected = characterId == character.characterId,
                                onClick = { characterId = character.characterId },
                                label = { Text(character.displayName) })
                        }
                    }
                    Text(ReadingBackgroundBridge.progressLabel(context, characterId, selected),
                        style = MaterialTheme.typography.bodySmall)
                    val last = bookRecords.maxByOrNull { it.occurredAt }
                    if (last != null) {
                        Text("上次读到 · ${last.chapterTitle}", fontWeight = FontWeight.SemiBold)
                        Text((last.moments.firstOrNull()?.reaction ?: last.reflection)
                            .take(240).ifBlank { "读过这段，暂时没有留下感想。" })
                    } else {
                        Text("这本书还没有角色阅读记录。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (notice.isNotBlank()) Text(notice,
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled = !reading && characterId in characters &&
                    ReadingBackgroundBridge.nextSlice(context, characterId, selected.id) != null,
                    onClick = {
                        reading = true
                        scope.launch {
                            val result = runCatching {
                                CompanionActionRuntime.execute(context, characterId,
                                    "read_book", JSONObject().put("readingBookId", selected.id))
                            }
                            notice = result.fold(
                                onSuccess = { if (it.success) "角色已读到下一段" else it.summary },
                                onFailure = { "阅读未完成，请稍后再试" })
                            reading = false
                            showCompanion = false
                        }
                    }) { Text(if (reading) "正在阅读…" else "让角色继续读") }
            },
            dismissButton = {
                TextButton(onClick = { showCompanion = false }) { Text("关闭") }
            },
        )
    }
}
