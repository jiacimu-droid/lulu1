package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import java.util.UUID

private val PublicWorldPaper = Color(0xFFFFFDF7)
private val PublicWorldCard = Color(0xFFFFFBF1)
private val PublicWorldBorder = Color(0xFFEAE0CC)
private val PublicWorldMuted = Color(0xFF6D7888)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterWorldBookScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val books by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    val rules by MigratedDomainStores.worldBookRules.rules.collectAsState()
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = PublicWorldPaper,
        topBar = {
            TopAppBar(
                title = { Text("世界书", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
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
                    Text("新建世界书", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text("世界设定") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("全局默认应用", fontWeight = FontWeight.SemiBold)
                            Text("角色单独设置会覆盖这里", color = PublicWorldMuted, fontSize = 12.sp)
                        }
                        Switch(checked = global, onCheckedChange = { global = it })
                    }
                    Button(
                        onClick = {
                            if (title.isNotBlank()) scope.launch {
                                LuluRepositories.worldBook.save(
                                    WorldBookEntry(
                                        id = UUID.randomUUID().toString(),
                                        title = title.trim(),
                                        content = content.trim(),
                                        globalEnabled = global,
                                        characterOverrides = emptyMap(),
                                    ),
                                )
                                title = ""
                                content = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存世界书") }
                }
            }

            if (books.isEmpty()) {
                item {
                    PublicWorldCardBox {
                        Text("还没有世界书", fontWeight = FontWeight.Bold)
                        Text("创建后可以为露露单独开启或关闭。", color = PublicWorldMuted)
                    }
                }
            } else {
                items(books, key = { it.id }) { book ->
                    val explicitRule = rules.lastOrNull { it.worldBookId == book.id && it.characterId == "lulu" }
                    val enabledForLulu = explicitRule?.enabled ?: book.globalEnabled
                    PublicWorldCardBox {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(book.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(book.content, color = PublicWorldMuted)
                                Text(
                                    if (explicitRule == null) "露露：跟随全局默认" else "露露：单独设置",
                                    color = PublicWorldMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = { scope.launch { LuluRepositories.worldBook.delete(book.id) } }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("对露露应用", fontWeight = FontWeight.SemiBold)
                                Text(if (enabledForLulu) "聊天时会注入这本世界书" else "不会注入露露的聊天", color = PublicWorldMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = enabledForLulu,
                                onCheckedChange = { MigratedDomainStores.worldBookRules.setEnabled(book.id, "lulu", it) },
                            )
                        }
                    }
                }
            }
        }
    }
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
