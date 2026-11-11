package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluEditorialHeader
import com.jiacimu.lulu.design.LuluEditorialPanel
import com.jiacimu.lulu.design.LuluEditorialSectionHeading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MiniTheaterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val library = remember { MiniTheaterLibrary.get(context) }
    val allScenes by library.items.collectAsState()
    val scenes = allScenes.filter { it.characterId.isBlank() }
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<String?>(null) }
    var showPrompt by remember(opened) { mutableStateOf(false) }
    var background by remember(opened) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf(emptyList<MiniTheaterItem>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmRewrite by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            check(bytes.size() + n <= 16 * 1024 * 1024) {
                                "文件超过单次导入预算（16MB）"
                            }
                            bytes.write(buffer, 0, n)
                        }
                        bytes.toString(Charsets.UTF_8.name())
                    } ?: error("读取文件失败")
                    MiniTheaterLibrary.parse(json)
                }
            }
            parsed.onSuccess {
                candidates = it
                selected = it.mapTo(mutableSetOf()) { item -> item.id }
                if (it.isEmpty()) notice = "文件里没有找到小剧场正文"
            }.onFailure { notice = "导入失败：${it.message}" }
        }
    }
    val current = scenes.firstOrNull { it.id == opened }
    BackHandler { if (opened == null) onBack() else opened = null }
    Column(Modifier.fillMaxSize()) {
        Surface(shadowElevation = 1.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (current == null) onBack() else opened = null }) {
                    Icon(Icons.Outlined.ArrowBack, "返回")
                }
                Text(if (current == null) "单篇小剧场" else "阅读",
                    modifier = Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.SemiBold)
                if (current == null) IconButton(onClick = {
                    picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }) { Icon(Icons.Outlined.FileUpload, "多选导入") }
            }
        }
        if (current == null) {
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    LuluEditorialHeader(
                        overline = "THEATER  /  LIBRARY",
                        title = "每一篇，都是一个小世界",
                        subtitle = "${scenes.size} 个独立故事。在这里重逢、探索，或开始新的想象。",
                    )
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(query, { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        label = { Text("搜索小剧场") })
                    if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
                }
                item { LuluEditorialSectionHeading(title = "故事书架") }
                items(scenes.filter { query.isBlank() || it.title.contains(query, true) },
                    key = { it.id }) { item ->
                    OutlinedCard(onClick = {
                        opened = item.id; background = item.sceneContext; notice = ""
                    }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(when (item.status) {
                                "complete" -> "已写完 · 点击阅读"
                                "queued", "running" -> "正在写 · 可离开页面"
                                "draft" -> "正文未完 · 可续写"
                                "failed" -> "生成失败 · 可重试"
                                else -> if (item.builtIn) "内置小剧场 · 未开始" else "已收藏 · 未开始"
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("READING ROOM  /  单篇", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(current.title, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.headlineSmall)
                if (current.text.isNotBlank()) Text(
                    "正文 ${PersonalTheaterTypography.wordCount(current.text)} 字",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showPrompt = !showPrompt },
                    contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (showPrompt) "收起创作设定" else "查看创作设定")
                }
                if (showPrompt) LuluEditorialPanel {
                    SelectionContainer {
                        Text(current.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
                    }
                }
                if (current.status !in setOf("queued", "running", "complete")) {
                    OutlinedTextField(background, { background = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("前情 / 指定的梗（可不填）") })
                    Button(onClick = {
                        runCatching { MiniTheaterJobs.enqueue(context, current.id, background) }
                            .onFailure { notice = "无法生成：${it.message}" }
                    }) { Text(if (current.rewriting) "重试创作新版本"
                        else if (current.text.isBlank()) "写这一篇" else "继续写这一篇") }
                }
                if (current.status in setOf("queued", "running")) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("后台创作中，退出也会保存结果")
                }
                if (current.error.isNotBlank())
                    Text(current.error, color = MaterialTheme.colorScheme.error)
                if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
                if (current.text.isNotBlank()) {
                    HorizontalDivider()
                    Text(if (current.rewriting) "上一版正文（已保留）" else "小剧场正文",
                        fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(current.text, style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 28.sp) }
                    if (current.status == "complete") TextButton(onClick = {
                        confirmRewrite = true
                    }) { Text("重新创作") }
                }
            }
        }
    }

    if (confirmRewrite && current != null) AlertDialog(
        onDismissRequest = { confirmRewrite = false },
        title = { Text("重新创作这篇？") },
        text = { Text("会覆盖本篇已完成的正文，但不会修改原始要求。") },
        confirmButton = { TextButton(onClick = {
            confirmRewrite = false
            runCatching { MiniTheaterJobs.enqueue(context, current.id, background, rewrite = true) }
                .onFailure { notice = it.message.orEmpty() }
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { confirmRewrite = false }) { Text("取消") } },
    )
    if (candidates.isNotEmpty()) AlertDialog(
        onDismissRequest = { candidates = emptyList() },
        title = { Text("多选导入 · ${selected.size}/${candidates.size}") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Row {
                    TextButton(onClick = { selected = candidates.mapTo(mutableSetOf()) { it.id } }) {
                        Text("全选")
                    }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                }
                candidates.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (item.id in selected) selected - item.id else selected + item.id
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.id in selected, onCheckedChange = { yes ->
                            selected = if (yes) selected + item.id else selected - item.id
                        })
                        Text(item.title)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = {
            val added = library.addSelected(candidates.filter { it.id in selected })
            notice = "导入 ${added} 篇；已有的同名同正文小剧场不会重复添加"
            candidates = emptyList()
        }) { Text("导入所选") } },
        dismissButton = { TextButton(onClick = { candidates = emptyList() }) { Text("取消") } },
    )
}
