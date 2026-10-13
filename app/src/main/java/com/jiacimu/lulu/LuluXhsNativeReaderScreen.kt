package com.jiacimu.lulu

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.jiacimu.lulu.data.XhsImage
import com.jiacimu.lulu.data.XhsImageLoader
import com.jiacimu.lulu.ai.VisionModelService
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.XhsArticle
import com.jiacimu.lulu.data.XhsCard
import com.jiacimu.lulu.data.XhsReadingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/** Manual browsing and character tools use the same session, extraction and failure handling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluXhsNativeReaderScreen(
    onBack: () -> Unit, initialKeyword: String = "", initialCard: XhsCard? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var keyword by remember { mutableStateOf(initialKeyword) }
    var cards by remember { mutableStateOf<List<XhsCard>>(emptyList()) }
    var selected by remember { mutableStateOf<XhsCard?>(null) }
    var article by remember { mutableStateOf<XhsArticle?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var task by remember { mutableStateOf<Job?>(null) }
    var visionTask by remember { mutableStateOf<Job?>(null) }
    var visionText by remember { mutableStateOf("") }
    var visionLoading by remember { mutableStateOf(false) }
    var diagnosticVisible by remember { mutableStateOf(false) }
    var sequence by remember { mutableIntStateOf(0) }

    fun failure(error: Exception): String = if (error is TimeoutCancellationException)
        "等待网页数据超时，请在登录网页检查会话或稍后重试。"
        else error.message.orEmpty().take(180).ifBlank { "读取失败，未新增阅读记录。" }

    fun loadList(query: String) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        task?.cancel()
        visionTask?.cancel(); visionText = ""; visionLoading = false
        val current = ++sequence
        selected = null
        article = null
        cards = emptyList()
        loading = true
        message = "正在读取真实笔记列表…"
        task = scope.launch {
            try {
                val result = XhsReadingEngine.list(context, query) { if (sequence == current) message = it }
                if (sequence == current) {
                    cards = result
                    message = if (result.isEmpty()) "没有找到相关笔记。" else "已获取 ${result.size} 篇笔记"
                    listState.scrollToItem(0)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (sequence == current) message = failure(error)
            } finally { if (sequence == current) loading = false }
        }
    }

    fun readNote(card: XhsCard) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        if (card.token.isBlank()) {
            selected = card
            message = "这篇笔记没有详情令牌，无法读取正文。"
            return
        }
        task?.cancel()
        visionTask?.cancel(); visionText = ""; visionLoading = false
        val current = ++sequence
        selected = card
        article = null
        loading = true
        message = "正在读取笔记正文…"
        task = scope.launch {
            try {
                val result = XhsReadingEngine.read(context, card) { if (sequence == current) message = it }
                if (sequence == current) { article = result; message = "" }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (sequence == current) message = failure(error)
            } finally { if (sequence == current) loading = false }
        }
    }

    fun back() {
        task?.cancel(); visionTask?.cancel()
        sequence++
        if (selected != null) {
            if (initialCard != null && cards.isEmpty()) { onBack(); return }
            selected = null
            article = null
            loading = false
            message = if (cards.isEmpty()) "暂无列表" else "已获取 ${cards.size} 篇笔记"
        } else onBack()
    }

    BackHandler { back() }
    LaunchedEffect(Unit) {
        if (initialCard != null) readNote(initialCard) else loadList(initialKeyword)
    }
    DisposableEffect(Unit) { onDispose { task?.cancel(); visionTask?.cancel() } }

    if (diagnosticVisible) AlertDialog(onDismissRequest = { diagnosticVisible = false },
        title = { Text("读取诊断") },
        text = { Text(XhsReadingEngine.lastDiagnostic(context), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { diagnosticVisible = false }) { Text("关闭") } })

    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text("小红书 · 阅读") }, navigationIcon = {
            IconButton(onClick = { back() }) { Icon(Icons.Outlined.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val target = selected
            if (target == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(keyword, { keyword = it.take(64) }, modifier = Modifier.weight(1f),
                        label = { Text("搜索笔记") }, singleLine = true)
                    Button(onClick = { loadList(keyword) }) { Text("搜索") }
                }
                TextButton(onClick = { keyword = ""; loadList("") }) { Text("首页推荐") }
            } else {
                Text(article?.title ?: target.title, style = MaterialTheme.typography.titleMedium)
                val author = article?.author ?: target.author
                if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url()))) }
                        .onFailure { message = "无法打开原文" }
                }) { Text("查看原文") }
                val content = article
                if (content?.images?.isNotEmpty() == true) {
                    TextButton(enabled = !visionLoading, onClick = {
                        val current = sequence
                        visionLoading = true
                        visionTask = scope.launch {
                            val result = VisionModelService.describeXhsImages(context, content.images.map { it.url }, content.body)
                            if (sequence == current) {
                                visionText = result.getOrNull().orEmpty()
                                message = result.exceptionOrNull()?.message.orEmpty()
                                visionLoading = false
                            }
                        }
                    }) { Text(if (visionLoading) "正在识图…" else "识别图片（前${minOf(6, content.images.size)}张）") }
                }
                if (!loading && article == null) TextButton(onClick = { readNote(target) }) { Text("重试读取") }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            if (!loading && message.contains("超时")) TextButton(onClick = { diagnosticVisible = true }) { Text("读取诊断") }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = {
                    task?.cancel(); sequence++; loading = false; message = "已停止读取"
                }) { Text("停止") }
            }
            if (target != null) {
                LazyColumn(Modifier.weight(1f)) {
                    item { Text(article?.body.orEmpty(), style = MaterialTheme.typography.bodyLarge) }
                    items(article?.images.orEmpty(), key = { it.url }) { photo -> XhsNoteImage(photo) }
                    if (visionText.isNotBlank()) item {
                        Text("图片理解（模型识别可能有误）", style = MaterialTheme.typography.titleSmall)
                        Text(visionText, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(cards, key = { it.id }) { card ->
                        ElevatedCard(Modifier.fillMaxWidth().clickable { readNote(card) }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(card.title, style = MaterialTheme.typography.bodyLarge)
                                Text(card.author + if (card.token.isBlank()) " · 无详情令牌" else "",
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XhsNoteImage(photo: XhsImage) {
    var failure by remember(photo.url) { mutableStateOf("") }
    var retry by remember(photo.url) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(null, photo.url, retry) {
        value = null
        failure = ""
        runCatching { XhsImageLoader.bitmap(photo.url) }
            .onFailure { if (it is CancellationException) throw it }.fold({ value = it }, { failure = it.message.orEmpty() })
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = "笔记图片",
            modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.Fit)
    } else if (failure.isNotBlank()) {
        TextButton(onClick = { retry++ }) { Text("图片加载失败 · 重试") }
    } else {
        Box(Modifier.fillMaxWidth().height(180.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}
