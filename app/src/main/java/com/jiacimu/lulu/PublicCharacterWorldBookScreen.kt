package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.jiacimu.lulu.core.WorldBookEntry
import kotlinx.coroutines.launch
import java.util.UUID

private val PublicWorldPaper = Color(0xFFFFFDF7)
private val PublicWorldCard = Color(0xFFFFFBF1)
private val PublicWorldBorder = Color(0xFFEAE0CC)
private val PublicWorldMuted = Color(0xFF6D7888)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterWorldBookScreen(onBack: () -> Unit) {
    val repository = LuluRepositories.worldBook
    val scope = rememberCoroutineScope()
    val books by repository.observeWorldBooks().collectAsState(initial = emptyList())
    var editingBook by remember { mutableStateOf<WorldBookEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = PublicWorldPaper,
        topBar = {
            TopAppBar(
                title = { Text("世界书", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, "新建世界书")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PublicWorldPaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PublicWorldCardBox {
                    Text("拼接规则", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "世界书按当前列表从上到下拼接。全局开启时对所有角色生效；关闭后由角色设置单独决定。",
                        color = PublicWorldMuted,
                    )
                    Text(
                        "固定人设与世界书合计超过聊天安全预算时会明确报错，不会静默裁剪。",
                        color = PublicWorldMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            if (books.isEmpty()) {
                item {
                    PublicWorldCardBox {
                        Text("还没有世界书", fontWeight = FontWeight.Bold)
                        Text("点击右上角加号添加时代、历史、社会常态或交际规则。", color = PublicWorldMuted)
                    }
                }
            } else {
                itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                    val explicit = book.characterOverrides["lulu"]
                    PublicWorldCardBox {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(
                                    book.content,
                                    color = PublicWorldMuted,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    when (explicit) {
                                        true -> "露露：单独开启"
                                        false -> "露露：单独关闭"
                                        null -> "露露：跟随全局"
                                    },
                                    color = PublicWorldMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = { editingBook = book }) {
                                Icon(Icons.Outlined.Edit, "编辑")
                            }
                            IconButton(onClick = { scope.launch { repository.delete(book.id) } }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除")
                            }
                        }

                        HorizontalDivider(color = PublicWorldBorder)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("全局应用", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (book.globalEnabled) "自动加入所有角色生成" else "仅由角色单独选择",
                                    color = PublicWorldMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = book.globalEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { repository.setGlobalEnabled(book.id, enabled) }
                                },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("顺序 ${index + 1}", color = PublicWorldMuted, fontSize = 12.sp)
                            Row {
                                IconButton(
                                    enabled = index > 0,
                                    onClick = { scope.launch { repository.move(book.id, -1) } },
                                ) { Icon(Icons.Outlined.ArrowUpward, "上移") }
                                IconButton(
                                    enabled = index < books.lastIndex,
                                    onClick = { scope.launch { repository.move(book.id, 1) } },
                                ) { Icon(Icons.Outlined.ArrowDownward, "下移") }
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogBook = editingBook ?: WorldBookEntry(
        id = UUID.randomUUID().toString(),
        title = "",
        content = "",
        globalEnabled = true,
        characterOverrides = emptyMap(),
    ).takeIf { creating }

    dialogBook?.let { book ->
        WorldBookEditorDialog(
            book = book,
            isNew = creating,
            onDismiss = {
                editingBook = null
                creating = false
            },
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
private fun WorldBookEditorDialog(
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("世界设定") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("全局应用")
                    Switch(checked = globalEnabled, onCheckedChange = { globalEnabled = it })
                }
                Text(
                    if (globalEnabled) {
                        "这段设定会自动加入所有角色相关生成。"
                    } else {
                        "关闭后，只有在角色设置中单独开启时才会加入。"
                    },
                    color = PublicWorldMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    onSave(
                        book.copy(
                            title = title.trim(),
                            content = content.trim(),
                            globalEnabled = globalEnabled,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PublicWorldCardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PublicWorldCard),
        border = BorderStroke(1.dp, PublicWorldBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}
