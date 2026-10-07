package com.jiacimu.lulu.study

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LuluReadingScreen(onBack: () -> Unit, initialBookTitle: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val star by StarWishStores.main.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val reflections by ReadingReflectionStore.records.collectAsState()
    var libraryVersion by remember { mutableIntStateOf(0) }
    val books = remember(star.theaterChapters, libraryVersion) { ReadingBackgroundBridge.books(context) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var characterId by remember { mutableStateOf(PostgraduateExamStores.main.state.value.profile.selectedCharacterId) }
    var notice by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    val targetId = initialBookTitle?.takeIf { it.startsWith("reading-record:") }?.removePrefix("reading-record:")
    val target = targetId?.let(ReadingReflectionStore::get)
    val listState = rememberLazyListState()
    val selected = books.firstOrNull { it.id == selectedId }

    LaunchedEffect(books, initialBookTitle) {
        ReadingReflectionStore.migrateLegacyHistory(context)
        val record = targetId?.let(ReadingReflectionStore::get)
        if (selectedId == null && initialBookTitle != null) {
            val book = if (record != null) books.firstOrNull { it.id == record.bookId }
                else books.firstOrNull { it.title == initialBookTitle }
            selectedId = book?.id
            if (record != null) characterId = record.characterId
            if (book == null) notice = "没有找到对应书籍，原文可能已被删除。"
        }
    }
    LaunchedEffect(selectedId, target?.id) {
        if (selected != null && target?.bookId == selected.id) {
            val index = readingSections(selected).indexOfFirst { target.startOffset in it.start until it.end }
            if (index >= 0) listState.scrollToItem(index + 1)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }?.substringBeforeLast('.') ?: "未命名书籍"
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            require(text.isNotBlank()) { "书籍正文为空" }
            require(text.length <= 300_000) { "单本书籍最多30万字，请分卷上传" }
            val prefs = context.getSharedPreferences("lulu_reading_library", 0)
            val old = JSONArray(prefs.getString("books_v1", "[]"))
            val id = UUID.randomUUID().toString()
            val next = JSONArray().put(JSONObject().put("id", id).put("title", title).put("content", text))
            for (i in 0 until minOf(old.length(), 39)) next.put(old.getJSONObject(i))
            check(prefs.edit().putString("books_v1", next.toString()).commit())
            libraryVersion++
            selectedId = id
        }.onFailure { notice = it.message.orEmpty() }
    }
    Scaffold(containerColor = StudyDesign.paper, topBar = {
        TopAppBar(title = { Text(selected?.title ?: "阅读", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = { if (selectedId != null) selectedId = null else onBack() }) {
                Icon(Icons.Outlined.ArrowBack, "返回")
            } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == null) {
                item { Button(onClick = { importer.launch(arrayOf("text/plain", "text/markdown")) }) {
                    Icon(Icons.Outlined.UploadFile, null); Text("上传书籍")
                } }
                if (books.isEmpty()) item { Text("书架还是空的。") }
                items(books, key = { it.id }) { book ->
                    Surface(onClick = { selectedId = book.id }, color = StudyDesign.card) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(book.title, fontWeight = FontWeight.Bold)
                            Text(book.source, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        characters.values.forEach { character -> FilterChip(selected = characterId == character.characterId,
                            onClick = { characterId = character.characterId }, label = { Text(character.displayName) }) }
                    }
                    Text(ReadingBackgroundBridge.progressLabel(context, characterId, selected), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !reading && characterId in characters && ReadingBackgroundBridge.availableBooks(context, characterId).any { it.id == selected.id },
                        onClick = {
                            reading = true
                            scope.launch {
                                val result = CompanionActionRuntime.execute(context, characterId, "read_book", JSONObject().put("readingBookId", selected.id))
                                notice = if (result.success) "已保存阅读感想" else result.summary
                                reading = false
                            }
                        }) { Text(if (reading) "正在阅读…" else "让角色继续阅读") }
                }
                val revision = readingRevision(selected)
                if (target?.bookId == selected.id && target.revision != revision) item {
                    Text("原文已更新 · 原版本阅读感想", fontWeight = FontWeight.Bold)
                    Text(target.reflection)
                }
                val bookRecords = reflections.filter { it.bookId == selected.id && it.characterId == characterId && it.revision == revision }
                items(readingSections(selected), key = { "${selected.id}:${it.start}" }) { section ->
                    var expanded by remember(selected.id, section.start) { mutableStateOf(false) }
                    val notes = bookRecords.filter { it.startOffset in section.start until section.end }
                    Surface(color = StudyDesign.card) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(section.title, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起正文" else "展开正文") }
                            if (expanded) Text(selected.content.substring(section.start, section.end))
                            notes.sortedBy { it.occurredAt }.forEach { note ->
                                HorizontalDivider()
                                Text("${characters[note.characterId]?.displayName ?: "角色"}的感想 · ${note.startOffset}—${note.endOffset}",
                                    fontWeight = FontWeight.Medium)
                                if (note.id == target?.id) Text("本次阅读", style = MaterialTheme.typography.labelSmall)
                                Text(note.reflection)
                            }
                            if (notes.isEmpty()) Text("尚无阅读感想", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (notice.isNotBlank()) item { Text(notice) }
        }
    }
}
