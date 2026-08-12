package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** User-facing inspector/editor for plot-only vector memory. */
@Composable
internal fun ApocalypsePlotMemoryManagerV5(
    save: ApocalypseV3Save?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { ApocalypsePlotMemoryStoreV5(context) }
    var cards by remember(save?.id) { mutableStateOf(save?.let { store.load(it.id) }.orEmpty()) }
    var editTarget by remember { mutableStateOf<ApocalypsePlotMemoryCardV5?>(null) }
    var deleteTarget by remember { mutableStateOf<ApocalypsePlotMemoryCardV5?>(null) }
    var rebuildNonce by remember { mutableIntStateOf(0) }
    var rebuilding by remember { mutableStateOf(false) }

    LaunchedEffect(save?.id, rebuildNonce) {
        val current = save ?: return@LaunchedEffect
        cards = store.load(current.id)
        if (rebuildNonce > 0) {
            rebuilding = true
            try {
                ApocalypsePlotMemoryRuntimeV5.refreshEmbeddings(context, current.id)
            } finally {
                cards = store.load(current.id)
                rebuilding = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("剧情向量记忆", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    if (save != null) {
                        IconButton(
                            enabled = !rebuilding,
                            onClick = {
                                store.invalidateEmbeddings(save.id)
                                cards = store.load(save.id)
                                rebuildNonce++
                            },
                        ) {
                            if (rebuilding) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, "重新生成全部向量")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (save == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Text("还没有末世存档。进入游戏建立存档后，这里会出现剧情专用记忆。")
            }
            return@Scaffold
        }

        val vectorized = cards.count { it.embedding.isNotEmpty() && it.embeddingDimension > 0 }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("这是剧情专用记忆，不属于任何角色", fontWeight = FontWeight.Black)
                        Text(
                            "旧剧情先压成可审阅的正史卡片，再按当前行动做语义召回。上一幕仍保留完整正文；更老内容只在相关时进入模型，避免每幕把整本历史重新塞进去。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text("共 ${cards.size} 条 · 已向量化 $vectorized 条", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        store.invalidateEmbeddings(save.id)
                        cards = store.load(save.id)
                        rebuildNonce++
                    },
                    enabled = cards.isNotEmpty() && !rebuilding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (rebuilding) "正在重建剧情向量…" else "重新生成全部剧情向量")
                }
            }

            if (cards.isEmpty()) {
                item {
                    Text(
                        "目前还没有剧情记忆卡。新生成的幕会自动写入；旧版剧情进入游戏后也会按记录补回。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            } else {
                items(cards.asReversed(), key = { it.id }) { card ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("第${card.scene}幕 · ${card.worldTime.ifBlank { "时间未记录" }}", fontWeight = FontWeight.Black)
                                    Text(
                                        buildString {
                                            append(card.location.ifBlank { "地点未记录" })
                                            append(" · 重要度 ${"%.2f".format(card.importance)}")
                                            append(if (card.embedding.isNotEmpty()) " · 向量 ${card.embeddingDimension}维" else " · 待向量化")
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { editTarget = card }) {
                                    Icon(Icons.Outlined.Edit, "编辑")
                                }
                                IconButton(onClick = { deleteTarget = card }) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除")
                                }
                            }
                            HorizontalDivider()
                            Text(card.content, fontSize = 12.sp, lineHeight = 19.sp)
                            if (card.eventTypes.isNotEmpty()) {
                                Text(
                                    card.eventTypes.joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { card ->
        ApocalypsePlotMemoryEditDialogV5(
            card = card,
            onDismiss = { editTarget = null },
            onSave = { content, worldTime, location, types, importance ->
                store.updateCard(
                    saveId = card.saveId,
                    cardId = card.id,
                    content = content,
                    worldTime = worldTime,
                    location = location,
                    eventTypes = types,
                    importance = importance,
                )
                cards = store.load(card.saveId)
                editTarget = null
                rebuildNonce++
            },
        )
    }

    deleteTarget?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除第${card.scene}幕的剧情记忆？") },
            text = { Text("只删除这条剧情召回卡，不会删除该幕正文或回滚存档。之后模型不会再从这条记忆中召回旧事。") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteCard(card.saveId, card.id)
                    cards = store.load(card.saveId)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypsePlotMemoryEditDialogV5(
    card: ApocalypsePlotMemoryCardV5,
    onDismiss: () -> Unit,
    onSave: (String, String, String, List<String>, Double) -> Unit,
) {
    var content by remember(card.id) { mutableStateOf(card.content) }
    var worldTime by remember(card.id) { mutableStateOf(card.worldTime) }
    var location by remember(card.id) { mutableStateOf(card.location) }
    var eventTypes by remember(card.id) { mutableStateOf(card.eventTypes.joinToString("、")) }
    var importance by remember(card.id) { mutableStateOf(card.importance.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑第${card.scene}幕剧情记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(900) },
                    label = { Text("正史摘要") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = worldTime,
                    onValueChange = { worldTime = it.take(80) },
                    label = { Text("剧情时间") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it.take(100) },
                    label = { Text("地点") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = eventTypes,
                    onValueChange = { eventTypes = it.take(160) },
                    label = { Text("标签（用顿号或逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("重要度 ${"%.2f".format(importance)}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Slider(value = importance, onValueChange = { importance = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            Button(
                enabled = content.isNotBlank(),
                onClick = {
                    val types = eventTypes.split(Regex("[、,，|/\\s]+"))
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    onSave(content, worldTime, location, types, importance.toDouble())
                },
            ) { Text("保存并重建向量") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
