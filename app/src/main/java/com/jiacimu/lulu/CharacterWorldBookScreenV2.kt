package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.WorldBookJsonImporter
import com.jiacimu.lulu.data.WorldBookFolder
import com.jiacimu.lulu.data.groupWorldBookEntries
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterWorldBookScreenV2(
    initialCharacterId: String,
    onBack: () -> Unit,
) {
    val repository = LuluRepositories.worldBook
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notices = remember { SnackbarHostState() }
    var importBusy by remember { mutableStateOf(false) }
    var importRole by remember { mutableStateOf(initialCharacterId) }
    var importPreview by remember { mutableStateOf<WorldBookJsonImporter.Preview?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importBusy) scope.launch {
            importBusy = true
            try {
                val preview = withContext(Dispatchers.IO) {
                    val json = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        // Only one import is held in RAM for JSON parsing; this does
                        // not cap accumulated world-book history in the repository.
                        val maxFileBytes = 16 * 1024 * 1024
                        while (true) {
                            val bytes = input.read(buffer)
                            if (bytes == -1) break
                            if (bytes == 0) continue
                            require(output.size() + bytes <= maxFileBytes) {
                                "单个 JSON 超过 16MB，请拆分文件导入"
                            }
                            output.write(buffer, 0, bytes)
                        }
                        output.toString(Charsets.UTF_8.name())
                    } ?: error("无法读取选中的文件")
                    WorldBookJsonImporter.parse(json, importRole)
                }
                importPreview = preview
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                notices.showSnackbar(failure.message ?: "导入失败")
            } finally {
                importBusy = false
            }
        }
    }
    val books by repository.observeWorldBooks().collectAsState(initial = emptyList())
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var selectedCharacterId by rememberSaveable(initialCharacterId, characters.keys) {
        mutableStateOf(initialCharacterId.takeIf { it in characters } ?: characters.keys.firstOrNull() ?: "lulu")
    }
    val selectedCharacter = characters[selectedCharacterId] ?: MigratedDomainStores.characters.get(selectedCharacterId)
    var editingBook by remember { mutableStateOf<WorldBookEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var draftBookId by remember { mutableStateOf(UUID.randomUUID().toString()) }

    val folders by repository.observeFolders().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }
    var expandedBookId by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showFolderEditor by remember { mutableStateOf(false) }
    var folderBeingEdited by remember { mutableStateOf<WorldBookFolder?>(null) }
    var folderDraft by remember { mutableStateOf("") }
    var createForSelection by remember { mutableStateOf(false) }
    var deleteFolderConfirm by remember { mutableStateOf<WorldBookFolder?>(null) }
    val normalizedQuery = query.trim()
    val groups = remember(books, folders, normalizedQuery) {
        groupWorldBookEntries(books, folders, normalizedQuery)
    }
    val enabledCount = books.count { it.characterOverrides[selectedCharacterId] ?: it.globalEnabled }

    fun openFolderEditor(folder: WorldBookFolder? = null, forSelection: Boolean = false) {
        folderBeingEdited = folder
        folderDraft = folder?.name.orEmpty()
        createForSelection = forSelection
        showFolderEditor = true
    }

    fun removeBooks(ids: Set<String>) {
        scope.launch {
            try {
                val deleted = repository.deleteEntries(ids)
                if (deleted.entries.isEmpty()) return@launch
                selectedIds = selectedIds - ids
                if (expandedBookId?.let { it in ids } == true) expandedBookId = null
                if (editingBook?.id?.let { it in ids } == true) editingBook = null
                val label = if (deleted.entries.size == 1) "已删除《${deleted.entries.single().title}》"
                    else "已删除 ${deleted.entries.size} 条世界书"
                val action = notices.showSnackbar(
                    message = label, actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Long,
                )
                if (action == SnackbarResult.ActionPerformed) {
                    repository.restoreDeletedEntries(deleted)
                }
            } catch (failure: Exception) {
                notices.showSnackbar(failure.message ?: "删除失败")
            }
        }
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        snackbarHost = { SnackbarHost(notices) },
        topBar = {
            TopAppBar(
                title = { Text("世界书", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(
                        enabled = !importBusy,
                        onClick = {
                            importRole = selectedCharacterId
                            importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                    ) { Icon(Icons.Outlined.UploadFile, "导入 JSON") }
                    IconButton(onClick = { openFolderEditor() }) { Icon(Icons.Outlined.CreateNewFolder, "新建分类") }
                    IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新建世界书") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("全部设定", fontWeight = FontWeight.Bold, fontSize = 23.sp)
                        Text("${books.size} 条 · ${folders.size} 个分类 · ${selectedCharacter.displayName}启用 ${enabledCount} 条",
                            color = LuluColors.Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        selectionMode = !selectionMode
                        selectedIds = emptySet()
                    }) { Text(if (selectionMode) "完成" else "批量整理") }
                }
            }
            item(key = "characters") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    characters.values.forEach { character ->
                        FilterChip(
                            selected = selectedCharacterId == character.characterId,
                            onClick = { selectedCharacterId = character.characterId },
                            label = { Text(character.displayName) },
                        )
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索分类、标题或正文") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, "清除搜索")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selectionMode) {
                item(key = "selection") {
                    Surface(
                        color = LuluColors.Card,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, LuluColors.Border),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("已选 ${selectedIds.size} 条", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            TextButton(
                                onClick = {
                                    val visibleIds = groups.flatMap { it.third }.map { it.id }.toSet()
                                    selectedIds = if (visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds })
                                        selectedIds - visibleIds else selectedIds + visibleIds
                                },
                            ) { Text("全选结果") }
                            TextButton(onClick = { showMoveDialog = true }, enabled = selectedIds.isNotEmpty()) {
                                Text("移动")
                            }
                            TextButton(onClick = { showDeleteSelectedDialog = true },
                                enabled = selectedIds.isNotEmpty()) {
                                Text("删除", color = if (selectedIds.isNotEmpty())
                                    MaterialTheme.colorScheme.error else LuluColors.Muted)
                            }
                        }
                    }
                }
            }
            if (books.isEmpty() && folders.isEmpty()) {
                item(key = "empty") {
                    WorldV2Card {
                        Text("从第一本世界书开始", fontWeight = FontWeight.SemiBold)
                        Text("右上角可新建分类、添加世界书，或导入 JSON。", color = LuluColors.Muted)
                    }
                }
            } else if (groups.isEmpty()) {
                item(key = "noResults") {
                    WorldV2Card {
                        Text("没有找到相关世界书", fontWeight = FontWeight.SemiBold)
                        Text("换个关键词试试", color = LuluColors.Muted)
                    }
                }
            }
            groups.forEach { (folderId, folderName, matchedBooks) ->
                val groupKey = folderId ?: "__uncategorized__"
                val expanded = normalizedQuery.isNotBlank() || groupKey in expandedFolders
                item(key = "folder_$groupKey") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = LuluColors.Card,
                        border = BorderStroke(1.dp, LuluColors.Border),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { expandedFolders = if (expanded) expandedFolders - groupKey else expandedFolders + groupKey }
                                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                                null, tint = LuluColors.Ink, modifier = Modifier.size(23.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folderName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("${matchedBooks.size} 条", fontSize = 12.sp, color = LuluColors.Muted)
                            }
                            if (folderId != null) {
                                IconButton(onClick = {
                                    folders.firstOrNull { it.id == folderId }?.let { openFolderEditor(it) }
                                }) { Icon(Icons.Outlined.MoreHoriz, "管理分类") }
                            }
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                                tint = LuluColors.Muted)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                if (expanded) {
                    items(matchedBooks, key = { "book_${it.id}" }) { book ->
                        val actual = book.characterOverrides[selectedCharacterId] ?: book.globalEnabled
                        val explicit = book.characterOverrides[selectedCharacterId]
                        val originalIndex = books.indexOfFirst { it.id == book.id }
                        WorldV2Card {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = book.id in selectedIds,
                                        onCheckedChange = { picked ->
                                            selectedIds = if (picked) selectedIds + book.id else selectedIds - book.id
                                        },
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f).clickable {
                                        if (selectionMode) {
                                            selectedIds = if (book.id in selectedIds) selectedIds - book.id else selectedIds + book.id
                                        } else expandedBookId = if (expandedBookId == book.id) null else book.id
                                    },
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(book.content.replace('\n', ' '), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = LuluColors.Muted)
                                }
                                if (!selectionMode) {
                                    Switch(checked = actual, onCheckedChange = { enabled ->
                                        scope.launch {
                                            repository.setCharacterOverride(
                                                book.id, selectedCharacterId,
                                                enabled.takeIf { it != book.globalEnabled },
                                            )
                                        }
                                    }, modifier = Modifier.height(40.dp))
                                    IconButton(onClick = {
                                        expandedBookId = if (expandedBookId == book.id) null else book.id
                                    }) { Icon(Icons.Outlined.Tune, "详细设置") }
                                    IconButton(onClick = { removeBooks(setOf(book.id)) }) {
                                        Icon(Icons.Outlined.DeleteOutline, "删除此条世界书",
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (!selectionMode && expandedBookId == book.id) {
                                HorizontalDivider(color = LuluColors.Border)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("全局默认", modifier = Modifier.weight(1f), color = LuluColors.Muted)
                                    Switch(
                                        checked = book.globalEnabled,
                                        onCheckedChange = { scope.launch { repository.setGlobalEnabled(book.id, it) } },
                                    )
                                }
                                Text("${selectedCharacter.displayName}的设置", fontSize = 12.sp, color = LuluColors.Muted)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    WorldV2Choice("跟随全局", explicit == null, Modifier.weight(1f)) {
                                        scope.launch { repository.setCharacterOverride(book.id, selectedCharacterId, null) }
                                    }
                                    WorldV2Choice("开启", explicit == true, Modifier.weight(1f)) {
                                        scope.launch { repository.setCharacterOverride(book.id, selectedCharacterId, true) }
                                    }
                                    WorldV2Choice("关闭", explicit == false, Modifier.weight(1f)) {
                                        scope.launch { repository.setCharacterOverride(book.id, selectedCharacterId, false) }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("原始顺序 ${originalIndex + 1}", fontSize = 12.sp, color = LuluColors.Muted,
                                        modifier = Modifier.weight(1f))
                                    IconButton(enabled = originalIndex > 0, onClick = {
                                        scope.launch { repository.move(book.id, -1) }
                                    }) { Icon(Icons.Outlined.ArrowUpward, "原始顺序上移") }
                                    IconButton(enabled = originalIndex < books.lastIndex, onClick = {
                                        scope.launch { repository.move(book.id, 1) }
                                    }) { Icon(Icons.Outlined.ArrowDownward, "原始顺序下移") }
                                    IconButton(onClick = { editingBook = book }) { Icon(Icons.Outlined.Edit, "编辑世界书") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFolderEditor) {
        AlertDialog(
            onDismissRequest = { showFolderEditor = false },
            title = { Text(if (folderBeingEdited == null) "新建分类" else "编辑分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = folderDraft,
                        onValueChange = { folderDraft = it.take(60) },
                        label = { Text("分类名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    if (folderBeingEdited != null) {
                        Text("删除分类后，里面的世界书会回到“未分类”，内容和开关不变。",
                            color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(enabled = folderDraft.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            val id = repository.saveFolder(folderDraft, folderBeingEdited?.id)
                            if (createForSelection) {
                                repository.moveEntriesToFolder(selectedIds, id)
                                selectedIds = emptySet()
                                selectionMode = false
                                showMoveDialog = false
                            }
                            expandedFolders = expandedFolders + id
                            showFolderEditor = false
                        } catch (failure: Exception) {
                            notices.showSnackbar(failure.message ?: "分类保存失败")
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    folderBeingEdited?.let { folder ->
                        TextButton(onClick = {
                            deleteFolderConfirm = folder
                            showFolderEditor = false
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showFolderEditor = false }) { Text("取消") }
                }
            },
        )
    }

    deleteFolderConfirm?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderConfirm = null },
            title = { Text("删除“${folder.name}”？") },
            text = { Text("世界书会移到未分类，不会删除内容或改变开关。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            repository.deleteFolder(folder.id)
                            deleteFolderConfirm = null
                        } catch (failure: Exception) {
                            notices.showSnackbar(failure.message ?: "删除分类失败")
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFolderConfirm = null }) { Text("取消") } },
        )
    }

    if (showDeleteSelectedDialog) {
        val selectedBooks = books.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("删除 ${selectedBooks.size} 条世界书？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("所选条目将删除，操作后可在底部点“撤销”。")
                    Text(selectedBooks.take(4).joinToString("、") { it.title } +
                        if (selectedBooks.size > 4) " 等 ${selectedBooks.size} 条" else "",
                        fontSize = 12.sp, color = LuluColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedBooks.mapTo(mutableSetOf()) { it.id }
                    showDeleteSelectedDialog = false
                    selectionMode = false
                    removeBooks(ids)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("取消") } },
        )
    }

    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("移动 ${selectedIds.size} 条世界书") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    (listOf<WorldBookFolder?>(null) + folders).forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    try {
                                        repository.moveEntriesToFolder(selectedIds, folder?.id)
                                        selectedIds = emptySet()
                                        selectionMode = false
                                        showMoveDialog = false
                                    } catch (failure: Exception) {
                                        notices.showSnackbar(failure.message ?: "移动失败")
                                    }
                                }
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = LuluColors.Muted)
                            Spacer(Modifier.width(12.dp))
                            Text(folder?.name ?: "未分类", modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showMoveDialog = false
                    openFolderEditor(forSelection = true)
                }) {
                    Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建分类并移动")
                }
            },
            dismissButton = { TextButton(onClick = { showMoveDialog = false }) { Text("取消") } },
        )
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!importBusy) importPreview = null },
            title = { Text("导入世界书") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可导入 ${preview.entries.size} 条")
                    if (preview.skipped > 0) Text("无效条目 ${preview.skipped} 条已跳过", color = LuluColors.Muted)
                    if (preview.foreignFormat) {
                        Text("仅导入所选角色；关键词型 ${preview.conditionalEntries} 条默认关闭。", color = LuluColors.Muted)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importBusy,
                    onClick = {
                        importBusy = true
                        scope.launch {
                            try {
                                val outcome = withContext(Dispatchers.IO) {
                                    repository.importEntries(preview.entries)
                                }
                                importPreview = null
                                notices.showSnackbar("已导入 ${outcome.imported} 条，跳过重复 ${outcome.duplicates} 条")
                            } catch (failure: Exception) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                notices.showSnackbar(failure.message ?: "世界书保存失败")
                            } finally {
                                importBusy = false
                            }
                        }
                    },
                ) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(enabled = !importBusy, onClick = { importPreview = null }) { Text("取消") }
            },
        )
    }

    val dialogBook = editingBook ?: WorldBookEntry(
        id = draftBookId,
        title = "",
        content = "",
        globalEnabled = true,
        characterOverrides = emptyMap(),
    ).takeIf { creating }
    dialogBook?.let { book ->
        WorldBookEditorV2(
            book = book,
            folders = folders,
            isNew = creating,
            onDismiss = { editingBook = null; creating = false },
            onSave = { saved ->
                scope.launch {
                    try {
                        repository.save(saved)
                        editingBook = null
                        creating = false
                        draftBookId = UUID.randomUUID().toString()
                    } catch (failure: Exception) {
                        notices.showSnackbar(failure.message ?: "世界书保存失败")
                    }
                }
            },
            onDelete = {
                editingBook = null
                creating = false
                removeBooks(setOf(book.id))
            },
        )
    }
}

@Composable
private fun WorldBookEditorV2(
    book: WorldBookEntry,
    folders: List<WorldBookFolder>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var content by remember(book.id) { mutableStateOf(book.content) }
    var globalEnabled by remember(book.id) { mutableStateOf(book.globalEnabled) }
    var folderId by remember(book.id) { mutableStateOf(book.folderId) }
    var showFolderChoices by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加世界书" else "编辑世界书") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("世界设定") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                Box {
                    OutlinedButton(onClick = { showFolderChoices = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(folders.firstOrNull { it.id == folderId }?.name ?: "未分类")
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = showFolderChoices, onDismissRequest = { showFolderChoices = false }) {
                        (listOf<WorldBookFolder?>(null) + folders).forEach { folder ->
                            DropdownMenuItem(text = { Text(folder?.name ?: "未分类") }, onClick = {
                                folderId = folder?.id
                                showFolderChoices = false
                            })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("全局应用")
                    Switch(checked = globalEnabled, onCheckedChange = { globalEnabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = { onSave(book.copy(title = title.trim(), content = content.trim(), globalEnabled = globalEnabled, folderId = folderId)) },
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

@Composable
private fun WorldV2Choice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text, maxLines = 1, fontSize = 11.sp) }, modifier = modifier)
}

@Composable
private fun WorldV2Card(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
