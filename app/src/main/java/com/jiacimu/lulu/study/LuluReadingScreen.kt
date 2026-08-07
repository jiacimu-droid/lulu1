package com.jiacimu.lulu.study

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

private data class ReadingBook(val id: String, val title: String, val content: String, val source: String)
private data class ReadingLine(val id: String = UUID.randomUUID().toString(), val mine: Boolean, val text: String)

@Composable
fun LuluReadingScreen(
    onBack: () -> Unit,
    initialBookTitle: String? = null,
) {
    val context = LocalContext.current
    val starStore = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val starState by starStore.state.collectAsState()
    val studyState by studyStore.state.collectAsState()
    var uploads by remember { mutableStateOf(loadReadingBooks(context)) }
    var selected by remember { mutableStateOf<ReadingBook?>(null) }
    var notice by remember { mutableStateOf("") }

    val theaterBooks = remember(starState.theaterChapters) {
        starState.theaterChapters.mapNotNull { (title, chapters) ->
            if (chapters.isEmpty()) null else ReadingBook(
                id = "theater:$title",
                title = title,
                content = chapters.sortedBy { it.chapter }.joinToString("\n\n") { chapter ->
                    "第${chapter.chapter}章 ${chapter.title}\n${chapter.content}"
                },
                source = "来自小剧场 · ${chapters.size}章",
            )
        }
    }
    val allBooks = uploads + theaterBooks

    LaunchedEffect(initialBookTitle, allBooks.map(ReadingBook::id)) {
        val title = initialBookTitle?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (selected == null) {
            selected = allBooks.firstOrNull { it.title == title }
            if (selected == null) notice = "没有找到《$title》，它可能已经被删除或改名。"
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.substringBeforeLast('.')
                    ?.ifBlank { "我的故事" }
                    ?: "我的故事"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?.trim()?.take(300_000).orEmpty()
                require(content.isNotBlank()) { "没有读取到文字内容" }
                val book = ReadingBook(UUID.randomUUID().toString(), title, content, "用户上传")
                uploads = listOf(book) + uploads
                saveReadingBooks(context, uploads)
                selected = book
            }.onFailure { notice = it.message ?: "故事导入失败，请选择 TXT 或 Markdown 文件" }
        }
    }

    Scaffold(
        containerColor = StudyDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text(if (selected == null) "一起阅读" else selected!!.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyDesign.paper),
            )
        },
    ) { padding ->
        if (selected == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                item {
                    Surface(
                        color = StudyDesign.card,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, StudyDesign.border),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Button(
                                onClick = { importer.launch(arrayOf("text/plain", "text/markdown", "application/json")) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                            ) {
                                Icon(Icons.Outlined.UploadFile, null)
                                Spacer(Modifier.width(7.dp))
                                Text("上传故事")
                            }
                        }
                    }
                }
                if (allBooks.isEmpty()) {
                    item { Text("书架还是空的。", color = StudyDesign.muted) }
                }
                items(allBooks, key = ReadingBook::id) { book ->
                    Surface(
                        onClick = { selected = book },
                        color = StudyDesign.card,
                        shape = RoundedCornerShape(19.dp),
                        border = BorderStroke(1.dp, StudyDesign.border),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoStories, null, tint = StudyDesign.ink, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.source, color = StudyDesign.muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = StudyDesign.muted)
                        }
                    }
                }
                if (notice.isNotBlank()) item { Text(notice, color = StudyDesign.error) }
            }
        } else {
            ReadingRoom(
                modifier = Modifier.fillMaxSize().padding(padding),
                book = selected!!,
                characterId = studyState.profile.selectedCharacterId,
            )
        }
    }
}

@Composable
private fun ReadingRoom(modifier: Modifier, book: ReadingBook, characterId: String) {
    val character = MigratedDomainStores.characters.get(characterId)
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var discussing by remember(false) { mutableStateOf(false) }
    var lines by remember(book.id) { mutableStateOf(listOf<ReadingLine>()) }
    var sectionExpanded by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            Surface(color = StudyDesign.card, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, StudyDesign.border)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text("正在和${character.displayName}共读 · ${book.source}", color = StudyDesign.muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { sectionExpanded = !sectionExpanded }) {
                            Icon(if (sectionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开正文")
                        }
                    }
                    if (sectionExpanded) {
                        Text(book.content, fontSize = 15.sp, lineHeight = 24.sp)
                    }
                }
            }
        }
        if (lines.isNotEmpty()) {
            item { Text("共读讨论", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            items(lines, key = ReadingLine::id) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (line.mine) StudyDesign.wheatSoft else StudyDesign.card,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, StudyDesign.border),
                        modifier = Modifier.widthIn(max = 310.dp),
                    ) { Text(line.text, Modifier.padding(12.dp), lineHeight = 21.sp) }
                }
            }
        }
        item {
            Surface(color = StudyDesign.card, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, StudyDesign.border)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        label = { Text("和${character.displayName}讨论这段故事") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    Button(
                        enabled = question.isNotBlank() && !discussing,
                        onClick = {
                            val clean = question.trim()
                            question = ""
                            lines = lines + ReadingLine(mine = true, text = clean)
                            discussing = true
                            scope.launch {
                                LuluAiServices.gateway.generate(
                                    characterId = characterId,
                                    facts = buildString {
                                        appendLine("正在共同阅读：《${book.title}》")
                                        appendLine("阅读正文：")
                                        appendLine(book.content.take(12_000))
                                        appendLine("最近讨论：")
                                        lines.takeLast(8).forEach { appendLine("${if (it.mine) "用户" else character.displayName}：${it.text}") }
                                        appendLine("用户刚说：$clean")
                                    },
                                    instruction = "和用户共同阅读并讨论当前故事。回应用户的问题、感受或推测，不续写剧情，不冒充原作者，不假装看过未提供的内容。1-4段。",
                                    source = "阅读",
                                    title = "共读讨论",
                                    maxTokens = 700,
                                ).onSuccess { reply ->
                                    lines = lines + ReadingLine(mine = false, text = reply.text)
                                    SharedExperienceTimeline.record(
                                        eventId = "reading-talk-${UUID.randomUUID()}",
                                        characterId = characterId,
                                        channel = "共同阅读《${book.title}》",
                                        speaker = "用户与${character.displayName}",
                                        content = "用户：$clean\n${character.displayName}：${reply.text}",
                                        occurredAt = Instant.now(),
                                    )
                                }.onFailure { lines = lines + ReadingLine(mine = false, text = it.message ?: "这次讨论没有成功") }
                                discussing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                    ) { Text(if (discussing) "正在想" else "一起讨论") }
                }
            }
        }
    }
}

private fun loadReadingBooks(context: Context): List<ReadingBook> = runCatching {
    val raw = context.getSharedPreferences("lulu_reading_library", Context.MODE_PRIVATE).getString("books_v1", "[]")
    val array = JSONArray(raw)
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(ReadingBook(item.optString("id"), item.optString("title"), item.optString("content"), "用户上传"))
        }
    }
}.getOrDefault(emptyList())

private fun saveReadingBooks(context: Context, books: List<ReadingBook>) {
    val array = JSONArray().apply {
        books.take(40).forEach { book ->
            put(JSONObject().put("id", book.id).put("title", book.title).put("content", book.content))
        }
    }
    context.getSharedPreferences("lulu_reading_library", Context.MODE_PRIVATE).edit().putString("books_v1", array.toString()).apply()
}
