package com.jiacimu.lulu

import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import androidx.webkit.UserAgentMetadata
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Manual browsing and character tools use the same session, extraction and failure handling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluXhsNativeReaderScreen(
    onBack: () -> Unit, initialKeyword: String = "", initialCard: XhsCard? = null,
    initialCards: List<XhsCard> = emptyList(),
    onSearchInBrowser: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    // A real, attached WebView is embedded BELOW the native reader toolbar.
    // It shares the site's login state, but never opens Chrome or a separate
    // browser screen. This also avoids the invisible WebView timeout.
    val useEmbeddedBrowser = onSearchInBrowser == null
    val smokeRequested = remember { XhsReadingEngine.consumeVisibleReadTest(context) }
    var webReady by remember { mutableStateOf(false) }
    var webProblem by remember { mutableStateOf("") }
    var embeddedView by remember { mutableStateOf<WebView?>(null) }
    var showWebPreview by remember { mutableStateOf(smokeRequested) }
    var smokeStarted by remember { mutableStateOf(false) }
    var smokeResult by remember { mutableStateOf("") }
    var smokeRunning by remember { mutableStateOf(false) }
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
        // A visible login WebView is already available on this route.
        // Let that real page perform the search instead of spawning a
        // second, obscured WebView behind the reading screen.
        if (onSearchInBrowser != null) {
            sequence++
            loading = false
            message = "正在切回小红书网页搜索…"
            onSearchInBrowser(query.trim().take(64))
            return
        }
        visionTask?.cancel(); visionText = ""; visionLoading = false
        val current = ++sequence
        selected = null
        article = null
        cards = emptyList()
        loading = true
        message = "正在读取真实笔记列表…"
        task = scope.launch {
            try {
                val result = XhsReadingEngine.list(context, query, onProgress = { if (sequence == current) message = it })
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
                val result = XhsReadingEngine.read(context, card, onProgress = { if (sequence == current) message = it })
                if (sequence == current) { article = result; message = "" }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (sequence == current) message = failure(error)
            } finally { if (sequence == current) loading = false }
        }
    }

    fun startNativeReadTest() {
        if (smokeRunning) return
        task?.cancel()
        val current = ++sequence
        selected = null
        article = null
        cards = emptyList()
        loading = true
        smokeRunning = true
        showWebPreview = true
        message = "第 1 / 2 步：在当前内置网页搜索真实笔记…"
        task = scope.launch {
            val result = try {
                withTimeout(82_000L) {
                    val found = XhsReadingEngine.list(context, "猫咪",
                        onProgress = { if (current == sequence) message = "第 1 / 2 步 · $it" },
                        localOnly = true)
                    if (current != sequence) return@withTimeout ""
                    cards = found
                    val chosen = found.firstOrNull { it.provider == "webview" && it.token.isNotBlank() }
                        ?: error("找到 ${found.size} 篇笔记，但没有可打开的真实详情令牌")
                    message = "第 2 / 2 步：在同一内置网页读取笔记图文…"
                    val real = XhsReadingEngine.read(context, chosen,
                        onProgress = { if (current == sequence) message = "第 2 / 2 步 · $it" },
                        localOnly = true)
                    if (current != sequence) return@withTimeout ""
                    if (real.body.isBlank() && real.images.isEmpty())
                        error("帖子没有返回真实正文或图片")
                    "通过：内置网页搜索 ${found.size} 篇，读到正文 ${real.body.length} 字" +
                        "、${real.images.size} 张图片。\n未用 MCP，未写入角色记忆。"
                }
            } catch (error: TimeoutCancellationException) {
                "失败：等待内置网页搜索/正文超过 82 秒。可展开下方网页预览，" +
                    "查看实际登录或验证提示，并点「读取诊断」。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "失败：${error.message.orEmpty().take(180)}。\n未切换 MCP，也没有虚报已读。"
            }
            if (current == sequence) {
                smokeResult = result
                smokeRunning = false
                loading = false
                message = result
            }
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
    LaunchedEffect(webReady) {
        // When launched from Settings we wait for the embedded page to start
        // loading before asking it for search data. When nested over the
        // existing browser, reuse the supplied cards instead.
        if (useEmbeddedBrowser && !webReady) return@LaunchedEffect
        if (initialCard != null) readNote(initialCard)
        else if (initialCards.isNotEmpty()) {
            cards = initialCards
            loading = false
            message = "网页已经找到 ${initialCards.size} 篇真实笔记（无需重新搜索）"
        } else if (smokeRequested && !smokeStarted) {
            smokeStarted = true
            startNativeReadTest()
        } else if (onSearchInBrowser != null) {
            message = "请在小红书网页搜索，或输入关键词后点「搜索」"
        } else if (!smokeStarted) loadList(initialKeyword)
    }
    LaunchedEffect(useEmbeddedBrowser, webReady, smokeRequested) {
        if (useEmbeddedBrowser && !webReady && smokeRequested) {
            delay(18_000)
            if (!webReady && !smokeStarted) {
                smokeStarted = true
                smokeResult = "内置小红书网页没有在 18 秒内完成主文档加载。" +
                    "请检查网页预览中的网络、登录或验证提示；未调用 MCP。"
                message = smokeResult
            }
        }
    }
    DisposableEffect(Unit) { onDispose { task?.cancel(); visionTask?.cancel() } }

    if (smokeResult.isNotBlank()) AlertDialog(onDismissRequest = { smokeResult = "" },
        title = { Text("内置阅读测试结果") },
        text = { Text(smokeResult) },
        confirmButton = { TextButton(onClick = { smokeResult = "" }) { Text("知道了") } })

    if (diagnosticVisible) AlertDialog(onDismissRequest = { diagnosticVisible = false },
        title = { Text("读取诊断") },
        text = { Text(XhsReadingEngine.lastDiagnostic(context), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { diagnosticVisible = false }) { Text("关闭") } })

    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(
            title = { Text(if (selected == null) "小红书 · 列表" else "小红书 · 笔记") },
            navigationIcon = {
                IconButton(onClick = { back() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回列表")
                }
            },
            actions = {
                // This is the VISIBLE top bar while the native note covers the
                // WebView toolbar. A parent-only button was unreachable.
                TextButton(onClick = { back() }) {
                    Text(if (selected != null) "返回列表" else "退出阅读")
                }
            },
        )
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
                if (useEmbeddedBrowser) {
                    TextButton(onClick = {
                        showWebPreview = true
                        embeddedView?.loadUrl(target.url())
                        message = "已在本页内置网页区域打开原文，可随时返回简洁阅读"
                    }) { Text("在本页查看原网页") }
                }
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
            if (useEmbeddedBrowser) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("同一页面内的登录网页 · 供搜索与角色阅读复用",
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { showWebPreview = !showWebPreview }) {
                        Text(if (showWebPreview) "收起网页预览" else "查看网页状态")
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(
                        if (showWebPreview || loading || smokeRunning) 180.dp else 2.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            val chromeVersion = Regex("Chrome/([0-9.]+)").find(
                                WebSettings.getDefaultUserAgent(ctx))?.groupValues?.get(1) ?: "120.0.0.0"
                            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                                runCatching {
                                    val brands = listOf("Chromium", "Google Chrome").map { brand ->
                                        UserAgentMetadata.BrandVersion.Builder().setBrand(brand)
                                            .setMajorVersion(chromeVersion.substringBefore('.'))
                                            .setFullVersion(chromeVersion).build()
                                    }
                                    WebSettingsCompat.setUserAgentMetadata(settings,
                                        UserAgentMetadata.Builder().setPlatform("Linux").setMobile(false)
                                            .setBrandVersionList(brands).build())
                                }
                            }
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                val script = ctx.assets.open("xhs-reader.js").bufferedReader()
                                    .use { it.readText() }
                                WebViewCompat.addDocumentStartJavaScript(this, script,
                                    setOf("https://www.xiaohongshu.com", "https://xiaohongshu.com"))
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?, request: WebResourceRequest?): Boolean {
                                    val uri = request?.url ?: return true
                                    return uri.scheme != "https" ||
                                        uri.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")
                                }
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        webProblem = "内置网页加载失败：${error?.description.orEmpty()}"
                                        if (!webReady) webReady = true
                                    }
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (!webReady) webReady = true
                                }
                            }
                            embeddedView = this
                            XhsReadingEngine.attachBrowser(this)
                            loadUrl("https://www.xiaohongshu.com/explore")
                        }
                    },
                    onRelease = { view ->
                        XhsReadingEngine.detachBrowser(view)
                        if (embeddedView === view) embeddedView = null
                        view.stopLoading()
                        view.destroy()
                    },
                )
                if (webProblem.isNotBlank()) Text(webProblem, style = MaterialTheme.typography.bodySmall)
                if (!smokeRunning && !loading && !smokeRequested && cards.isEmpty() && selected == null)
                    Text("搜索将直接读取上面的内置网页，不会打开外部浏览器。",
                        style = MaterialTheme.typography.labelSmall)
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
