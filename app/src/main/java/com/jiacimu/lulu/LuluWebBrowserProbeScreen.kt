package com.jiacimu.lulu

import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import com.jiacimu.lulu.data.XhsCard
import com.jiacimu.lulu.data.XhsReadingEngine
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

private const val XHS_PROBE_URL = "https://www.xiaohongshu.com/explore"

/** A SPA note can have no WebView history even though its detail screen is visible. */
internal fun xhsBrowserIsNoteUrl(raw: String): Boolean {
    val url = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
    if (url.scheme != "https" || url.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")) return false
    return Regex("""^/(?:explore|discovery/item)/[a-zA-Z0-9]{12,40}/?$""")
        .matches(url.path.orEmpty())
}


/** Login stays in the visible website; manual searches and note taps use the shared reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluWebBrowserProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var readerVisible by remember { mutableStateOf(false) }
    // A late SPA navigation callback can otherwise reopen the note right
    // after the user presses Back. Keep routing disabled until home finishes.
    var returningToList by remember { mutableStateOf(false) }
    var readerKeyword by remember { mutableStateOf("") }
    var readerCard by remember { mutableStateOf<XhsCard?>(null) }
    val routingScript = remember {
        context.assets.open("xhs-reader.js").bufferedReader().use { it.readText() } + "\n" +
            context.assets.open("xhs-browser-route.js").bufferedReader().use { it.readText() }
    }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("准备打开小红书桌面网页版…") }
    var progress by remember { mutableIntStateOf(0) }
    var resourceFailures by remember { mutableIntStateOf(0) }
    var apiFailures by remember { mutableIntStateOf(0) }
    var lastApiFailure by remember { mutableStateOf("") }
    var jsNetworkFailures by remember { mutableIntStateOf(0) }
    var browserDiagnostics by remember { mutableStateOf("") }
    var diagnosticsVisible by remember { mutableStateOf(false) }
    var websiteIssue by remember { mutableStateOf("") }
    val mobileAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val chromeVersion = remember(mobileAgent) {
        Regex("Chrome/([0-9.]+)").find(mobileAgent)?.groupValues?.get(1) ?: "120.0.0.0"
    }
    val desktopAgent = remember(chromeVersion) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    fun openOriginalNote() {
        val active = browser ?: return
        // User-triggered only: the complete note URL is passed directly to Android
        // ACTION_VIEW, never to the model, logs, analytics, or our MCP service.
        active.evaluateJavascript(
            """(() => window.__luluLastNoteUrl || (
                /^\/(explore|discovery\/item)\//.test(location.pathname)
                ? location.href : ""))()""".trimIndent(),
        ) { encoded ->
            val candidate = runCatching {
                JSONTokener(encoded).nextValue() as String
            }.getOrNull().orEmpty()
            val target = runCatching { Uri.parse(candidate) }.getOrNull()
            val host = target?.host.orEmpty().lowercase()
            val notePath = target?.path.orEmpty()
            if (target?.scheme != "https" ||
                host !in setOf("xiaohongshu.com", "www.xiaohongshu.com") ||
                !(notePath.startsWith("/explore/") || notePath.startsWith("/discovery/item/"))) {
                status = "当前笔记链接未能识别；暂时无法直接跳转。"
                return@evaluateJavascript
            }
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, target))
            }.onFailure {
                status = "无法打开笔记：" + it.message.orEmpty().take(70)
            }
        }
    }

    fun returnToList() {
        // XHS details are SPA overlays: WebView.canGoBack() can be false
        // even while a full-screen note is covering the feed. A real "list"
        // button must escape that overlay rather than depending on site history.
        returningToList = true
        readerVisible = false
        readerCard = null
        readerKeyword = ""
        websiteIssue = ""
        val current = browser
        if (current == null) {
            returningToList = false
            status = "已退出笔记阅读"
            return
        }
        current.stopLoading()
        // A fresh home navigation is deterministic even when the note was a
        // SPA dialog with no WebView history; don't navigate back into it.
        current.loadUrl(XHS_PROBE_URL)
        status = "正在返回笔记列表…"
    }

    fun routeToReader(target: Uri): Boolean {
        if (returningToList || readerVisible) return false
        if (target.scheme != "https" || target.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")) return false
        if (target.path in setOf("/search_result", "/search", "/search/result")) {
            readerKeyword = target.getQueryParameter("keyword").orEmpty().take(64)
            readerCard = null
            readerVisible = true
            return true
        }
        val id = Regex("/(?:explore|discovery/item)/([a-zA-Z0-9]{12,40})/?")
            .matchEntire(target.path.orEmpty())?.groupValues?.get(1) ?: return false
        readerCard = XhsCard(id, target.getQueryParameter("xsec_token").orEmpty(), "笔记", "",
            target.getQueryParameter("xsec_source").orEmpty().ifBlank { "pc_feed" })
        readerVisible = true
        return true
    }


    BackHandler(enabled = !readerVisible) {
        val current = browser
        if (current != null && xhsBrowserIsNoteUrl(current.url.orEmpty())) {
            returnToList()
        } else if (current?.canGoBack() == true) current.goBack() else onBack()
    }

    if (diagnosticsVisible && !readerVisible) {
        AlertDialog(
            onDismissRequest = { diagnosticsVisible = false },
            title = { Text("网页诊断结果") },
            text = { Text(browserDiagnostics, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { diagnosticsVisible = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticsVisible = false; returnToList() }) { Text("返回列表") }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小红书") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (readerVisible || xhsBrowserIsNoteUrl(browser?.url.orEmpty())) returnToList()
                        else onBack()
                    }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Always visible even when a website modal consumes touch/
                    // history inside WebView. This is not another WebView back.
                    TextButton(onClick = { returnToList() }) { Text("返回列表") }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Keep the browser viewport as tall as possible on a phone.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { returnToList() },
                ) { Text("首页") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { readerKeyword = ""; readerCard = null; readerVisible = true },
                ) { Text("阅读 / 搜索") }
                Button(modifier = Modifier.weight(1f), onClick = {
                    diagnosticsVisible = true
                    browserDiagnostics = "正在检查页面…"
                    val web = browser
                    if (web == null) {
                        browserDiagnostics = "浏览器尚未准备好。"
                    } else {
                        // Report categories and booleans only. Never surface raw URLs, notes, tokens, or cookies.
                        val hasWebSession = CookieManager.getInstance()
                            .getCookie(XHS_PROBE_URL).orEmpty()
                            .split(";").any { it.trimStart().startsWith("web_session=") }
                        val script = """
                            (() => {
                              const text = (document.body?.innerText || "").slice(0, 40000);
                              return JSON.stringify({
                                noteFailure: text.includes("笔记详情获取失败") || text.includes("笔记加载失败"),
                                loginPrompt: /请先登录|登录后查看|扫码登录|立即登录/.test(text),
                                verifyPrompt: /安全验证|滑块验证|异常访问|IP at risk|300012/.test(text),
                                pageReady: document.readyState,
                                currentDiagnostic: window.__luluXhsReader?.diagnostics?.() || null,
                                searchRoutingInstalled: !!window.__luluXhsRouting,
                                navigationBridge: !!window.luluXhsNavigation
                              });
                            })()
                        """.trimIndent()
                        web.evaluateJavascript(script) { raw ->
                            val flags = runCatching {
                                JSONObject(JSONTokener(raw).nextValue() as String)
                            }.getOrNull()
                            browserDiagnostics = buildString {
                                append("网页会话 Cookie：")
                                append(if (hasWebSession) "已检测到（不能单凭此确认登录）" else "未检测到（不代表账号已退出）")
                                append("\n")
                                append("小红书接口 HTTP 失败：$apiFailures")
                                if (lastApiFailure.isNotBlank()) append("；最近：$lastApiFailure")
                                append("\n其他网络加载失败：$resourceFailures；页面脚本网络报错：$jsNetworkFailures")
                                append("\n搜索和笔记点击共用原生阅读页。")
                                if (flags != null) {
                                    append("\n页面状态：")
                                    append(when {
                                        flags.optBoolean("verifyPrompt") -> "检测到安全验证提示"
                                        flags.optBoolean("noteFailure") -> "检测到笔记详情失败提示"
                                        flags.optBoolean("loginPrompt") -> "检测到登录提示"
                                        else -> "未检测到上述提示"
                                    })
                                    append("；文档：")
                                    append(flags.optString("pageReady", "未知"))
                                } else {
                                    append("\n网页脚本未返回诊断结果，可能仍未完成加载。")
                                }
                                val currentDiagnostic = flags?.optJSONObject("currentDiagnostic")
                                if (currentDiagnostic != null) {
                                    currentDiagnostic.put("stage", "current-browser")
                                        .put("nativeWidth", web.width).put("nativeHeight", web.height)
                                    append("\n当前页面诊断：\n$currentDiagnostic")
                                }
                                append("\n搜索接管脚本：${flags?.optBoolean("searchRoutingInstalled") == true}；导航桥：${flags?.optBoolean("navigationBridge") == true}")
                                if (apiFailures == 0) append("\nHTTP 无错误不等于网站业务成功；以实际读取业务码为准。")
                            }
                        }
                    }
                }) { Text("诊断") }
            }
            if (websiteIssue.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        websiteIssue,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (websiteIssue.contains("笔记详情")) {
                        TextButton(onClick = { openOriginalNote() }) { Text("外部读帖") }
                    }
                    TextButton(onClick = { returnToList() }) { Text("返回列表") }
                }
            }
            Text(
                "桌面网页版 · " + status,
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                HorizontalDivider()
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // Always identify this embedded view as a desktop browser.
                        // Set the UA before the first navigation; toggling between mobile
                        // and desktop is removed to keep the same login/web session.
                        settings.userAgentString = desktopAgent
                        // A desktop UA string alone can disagree with WebView's Android
                        // Client Hints. Align supported UA metadata with the same desktop
                        // identity; leave the existing CookieManager storage unchanged.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                            runCatching {
                                val major = chromeVersion.substringBefore(".")
                                val chromium = UserAgentMetadata.BrandVersion.Builder()
                                    .setBrand("Chromium")
                                    .setMajorVersion(major)
                                    .setFullVersion(chromeVersion)
                                    .build()
                                val chrome = UserAgentMetadata.BrandVersion.Builder()
                                    .setBrand("Google Chrome")
                                    .setMajorVersion(major)
                                    .setFullVersion(chromeVersion)
                                    .build()
                                val metadata = UserAgentMetadata.Builder()
                                    .setPlatform("Linux")
                                    .setMobile(false)
                                    .setBrandVersionList(listOf(chromium, chrome))
                                    .build()
                                WebSettingsCompat.setUserAgentMetadata(settings, metadata)
                            }
                        }
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.textZoom = 100
                        setInitialScale(0)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress.coerceIn(0, 100)
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                // Count known network errors only; never retain the console message.
                                val text = consoleMessage?.message().orEmpty()
                                if (text.contains("Failed to fetch", ignoreCase = true) ||
                                    text.contains("ERR_CONNECTION", ignoreCase = true) ||
                                    text.contains("NetworkError", ignoreCase = true)) {
                                    jsNetworkFailures = (jsNetworkFailures + 1).coerceAtMost(999)
                                }
                                return false
                            }
                        }
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            WebViewCompat.addWebMessageListener(this, "luluXhsNavigation",
                                setOf("https://www.xiaohongshu.com", "https://xiaohongshu.com")) { view, message, origin, mainFrame, _ ->
                                if (!mainFrame || browser !== view || returningToList || readerVisible ||
                                    XhsReadingEngine.controls(view) || origin.scheme != "https" ||
                                    origin.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")) return@addWebMessageListener
                                val value = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
                                    ?: return@addWebMessageListener
                                when (value.optString("kind")) {
                                    "search-start", "search-ready" -> {
                                        readerKeyword = value.optString("keyword").take(64)
                                        readerCard = null
                                        readerVisible = true
                                    }
                                    "note" -> {
                                        val id = value.optString("id")
                                        if (!Regex("[a-zA-Z0-9]{12,40}").matches(id)) return@addWebMessageListener
                                        readerCard = XhsCard(id, value.optString("token").take(512),
                                            value.optString("title").take(200).ifBlank { "笔记" },
                                            value.optString("author").take(120),
                                            value.optString("source").takeIf { it in setOf("pc_feed", "pc_search", "pc_user", "pc_share", "pc_board") } ?: "pc_feed")
                                        readerVisible = true
                                    }
                                }
                            }
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                                WebViewCompat.addDocumentStartJavaScript(this, routingScript,
                                    setOf("https://www.xiaohongshu.com", "https://xiaohongshu.com"))
                        }
                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                if (view === browser && !XhsReadingEngine.controls(view)) routeToReader(Uri.parse(url.orEmpty()))
                            }
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                if (request?.isForMainFrame != true) return false
                                val target = request.url ?: return true
                                if (!XhsReadingEngine.controls(view) && routeToReader(target)) return true
                                val host = target.host.orEmpty().lowercase()
                                val allowed = target.scheme == "https" &&
                                    (host == "xiaohongshu.com" ||
                                        host.endsWith(".xiaohongshu.com"))
                                if (allowed) return false
                                if (target.scheme == "http" || target.scheme == "https") {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, target))
                                    }.onFailure {
                                        status = "无法跳转外部浏览器：" + it.message.orEmpty().take(80)
                                    }
                                } else {
                                    status = if (target.scheme == "intent" ||
                                        target.scheme?.startsWith("xhs") == true) {
                                        "页面要求跳转小红书 App；已留在当前页，请使用阅读 / 搜索。"
                                    } else {
                                        "阻止非网页链接：" + target.scheme.orEmpty()
                                    }
                                }
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                resourceFailures = 0
                                apiFailures = 0
                                lastApiFailure = ""
                                jsNetworkFailures = 0
                                browserDiagnostics = ""
                                diagnosticsVisible = false
                                websiteIssue = ""
                                                        progress = 0
                                status = "正在加载网页…"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (browser !== view) return
                                if (returningToList) {
                                    val address = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                    if (address?.host in setOf("www.xiaohongshu.com", "xiaohongshu.com") &&
                                        address.path in setOf("/", "/explore")) {
                                        returningToList = false
                                        view?.clearHistory()
                                        status = "已返回小红书首页"
                                    } else {
                                        // Ignore stale note/search page-finished
                                        // callbacks during the return navigation.
                                        return
                                    }
                                    // Home is now loaded. Continue below to
                                    // reinstall click/search routing on WebViews
                                    // without document-start injection.
                                }
                                if (!XhsReadingEngine.controls(view) && routeToReader(Uri.parse(url.orEmpty()))) return
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                                    view?.evaluateJavascript(routingScript, null)
                                // Keep the full original link only for the user-triggered external-open button.
                                view?.evaluateJavascript(
                                    """
                                    (() => {
                                      if (window.__luluNoteCaptureReady) return;
                                      window.__luluNoteCaptureReady = true;
                                      window.__luluLastNoteUrl = '';
                                      document.addEventListener('click', event => {
                                        const node = event.target instanceof Element
                                          ? event.target : event.target?.parentElement;
                                        const link = node?.closest?.('a[href]');
                                        if (!link) return;
                                        try {
                                          const u = new URL(link.getAttribute('href'), location.href);
                                          if (u.protocol === 'https:' &&
                                              (u.hostname === 'www.xiaohongshu.com' || u.hostname === 'xiaohongshu.com') &&
                                              /^\/(explore|discovery\/item)\//.test(u.pathname)) {
                                            window.__luluLastNoteUrl = u.href;
                                          }
                                        } catch (_) {}
                                      }, true);
                                    })()
                                    """.trimIndent(),
                                    null,
                                )
                                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                val riskCode = if (uri?.path?.contains("/website-login/error") == true)
                                    uri.getQueryParameter("error_code") else null
                                status = if (riskCode != null) {
                                    "小红书限制了这个浏览器会话（错误码 $riskCode）。请勿反复尝试登录。"
                                } else if (!status.startsWith("网页加载失败") &&
                                    !status.startsWith("网页返回 HTTP")) {
                                    if (resourceFailures > 0)
                                        "网页已打开，但部分资源失败（$resourceFailures 项）；可点诊断查看。"
                                    else
                                        "主文档已加载；笔记列表等动态内容可能仍在加载。"
                                } else status
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    status = "网页加载失败：" + error?.description?.toString().orEmpty().take(100)
                                } else if (request?.url?.host.orEmpty().endsWith("xiaohongshu.com")) {
                                    // Count failures only. Never log request URLs, cookies or page content.
                                    resourceFailures = (resourceFailures + 1).coerceAtMost(999)
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                val code = errorResponse?.statusCode ?: 0
                                if (request?.isForMainFrame == true && code >= 400) {
                                    status = "网页返回 HTTP $code"
                                } else if (code >= 400 && request?.url?.host.orEmpty()
                                        .endsWith("xiaohongshu.com") &&
                                    request?.url?.path.orEmpty().contains("/api/")) {
                                    val path = request?.url?.path.orEmpty()
                                    val category = when {
                                        path.contains("search", ignoreCase = true) -> "搜索"
                                        path.contains("feed", ignoreCase = true) ||
                                            path.contains("note", ignoreCase = true) -> "笔记详情/列表"
                                        else -> "小红书数据"
                                    }
                                    apiFailures = (apiFailures + 1).coerceAtMost(999)
                                    lastApiFailure = "$category HTTP $code"
                                }
                            }
                        }
                        browser = this
                        XhsReadingEngine.attachBrowser(this)
                        loadUrl(XHS_PROBE_URL)
                    }
                },
                onRelease = { view ->
                    XhsReadingEngine.detachBrowser(view)
                    if (browser === view) browser = null
                    CookieManager.getInstance().flush()
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    view.webChromeClient = null
                    view.destroy()
                },
            )
        }
    }
    if (readerVisible) {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {
            key(readerKeyword, readerCard?.id) {
                LuluXhsNativeReaderScreen(onBack = { returnToList() },
                    initialKeyword = readerKeyword, initialCard = readerCard)
            }
        }
    }
    }
}
