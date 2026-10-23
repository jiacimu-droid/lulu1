package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

    Scaffold(
        containerColor = LuluColors.Paper,
        snackbarHost = { SnackbarHost(notices) },
        topBar = {
            TopAppBar(
                title = { Text("世界书", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(
                        enabled = !importBusy,
                        onClick = {
                            importRole = selectedCharacterId
                            importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                    ) {
                        Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入 JSON")
                    }
                    IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新建世界书") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                WorldV2Card {
                    Text("拼接与覆盖规则", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("世界书按列表从上到下拼接。全局开启时默认作用于所有角色；角色级的单独开启或关闭优先于全局。", color = LuluColors.Muted)
                    Text("发送时会完整拼接角色设定与已启用世界书；可用上下文长度以实际模型接口为准。", color = LuluColors.Muted, fontSize = 12.sp)
                }
            }
            item {
                Text("查看角色覆盖", fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    characters.values.forEach { character ->
                        FilterChip(
                            selected = selectedCharacterId == character.characterId,
                            onClick = { selectedCharacterId = character.characterId },
                            label = { Text(character.displayName) },
                            leadingIcon = {
                                Surface(shape = CircleShape, color = LuluColors.Wheat, contentColor = LuluColors.OnWheat, modifier = Modifier.size(22.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Text(character.displayName.take(1), fontSize = 10.sp) }
                                }
                            },
                        )
                    }
                }
            }
            if (books.isEmpty()) {
                item {
                    WorldV2Card {
                        Text("还没有世界书", fontWeight = FontWeight.Bold)
                        Text("点击右上角加号添加时代、历史、社会常态或交际规则。", color = LuluColors.Muted)
                    }
                }
            } else {
                itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                    val explicit = book.characterOverrides[selectedCharacterId]
                    WorldV2Card {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(book.content, color = LuluColors.Muted, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (explicit) {
                                        true -> "${selectedCharacter.displayName}：单独开启"
                                        false -> "${selectedCharacter.displayName}：单独关闭"
                                        null -> "${selectedCharacter.displayName}：跟随全局"
                                    },
                                    color = LuluColors.Muted,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = { editingBook = book }) { Icon(Icons.Outlined.Edit, "编辑") }
                            IconButton(onClick = { scope.launch { repository.delete(book.id) } }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider(color = LuluColors.Border)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("全局应用", fontWeight = FontWeight.SemiBold)
                                Text(if (book.globalEnabled) "默认加入所有角色" else "默认不加入，只接受角色级开启", color = LuluColors.Muted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = book.globalEnabled,
                                onCheckedChange = { enabled -> scope.launch { repository.setGlobalEnabled(book.id, enabled) } },
                            )
                        }
                        Text("${selectedCharacter.displayName}的覆盖", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WorldV2Choice("跟随全局", explicit == null, Modifier.weight(1f)) {
                                scope.launch { repository.setCharacterOverride(book.id, selectedCharacterId, null) }
                            }
                            WorldV2Choice("单独开启", explicit == true, Modifier.weight(1f)) {
                                scope.launch { repository.setCharacterOverride(book.id, selectedCharacterId, true) }
                            }
                            WorldV2Choice("单独关闭", explicit == false, Modifier.weight(1f)) {
                                scope.launch { repository.setCharacterOverride(book.id, selectedCharacterId, false) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("拼接顺序 ${index + 1}", color = LuluColors.Muted, fontSize = 12.sp)
                            Row {
                                IconButton(enabled = index > 0, onClick = { scope.launch { repository.move(book.id, -1) } }) {
                                    Icon(Icons.Outlined.ArrowUpward, "上移")
                                }
                                IconButton(enabled = index < books.lastIndex, onClick = { scope.launch { repository.move(book.id, 1) } }) {
                                    Icon(Icons.Outlined.ArrowDownward, "下移")
                                }
                            }
                        }
                    }
                }
            }
        }
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
        id = UUID.randomUUID().toString(),
        title = "",
        content = "",
        globalEnabled = true,
        characterOverrides = emptyMap(),
    ).takeIf { creating }
    dialogBook?.let { book ->
        WorldBookEditorV2(
            book = book,
            isNew = creating,
            onDismiss = { editingBook = null; creating = false },
            onSave = { saved ->
                scope.launch { repository.save(saved) }
                editingBook = null
                creating = false
            },
            onDelete = {
                scope.launch { repository.delete(book.id) }
                editingBook = null
                creating = false
            },
        )
    }
}

@Composable
private fun WorldBookEditorV2(
    book: WorldBookEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var content by remember(book.id) { mutableStateOf(book.content) }
    var globalEnabled by remember(book.id) { mutableStateOf(book.globalEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加世界书" else "编辑世界书") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("世界设定") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("全局应用")
                    Switch(checked = globalEnabled, onCheckedChange = { globalEnabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = { onSave(book.copy(title = title.trim(), content = content.trim(), globalEnabled = globalEnabled)) },
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
