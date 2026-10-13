package com.jiacimu.lulu

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val XHS_PROBE_URL = "https://www.xiaohongshu.com/explore"
private const val GENERAL_PROBE_URL = "https://example.com"

/**
 * A visible, user-operated Android WebView compatibility probe.
 *
 * This is intentionally NOT an MCP bridge: no JS interfaces, DOM extraction,
 * cookie export, remote debugging, automated login, or AI tool access.
 * First confirm that the website permits WebView before designing a
 * permissioned read-only browser integration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluWebBrowserProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("准备打开小红书网页…") }
    var progress by remember { mutableIntStateOf(0) }
    var resourceFailures by remember { mutableIntStateOf(0) }
    var desktopMode by remember { mutableStateOf(false) }
    val mobileAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val chromeVersion = remember(mobileAgent) {
        Regex("Chrome/([0-9.]+)").find(mobileAgent)?.groupValues?.get(1) ?: "120.0.0.0"
    }
    val desktopAgent = remember(chromeVersion) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    BackHandler {
        val current = browser
        if (current?.canGoBack() == true) current.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内置浏览器测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回 MCP 设置")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Keep the browser viewport as tall as possible on a phone.
            Text(
                "仅验证网页显示；Cookie 不共享给角色或 Termux。",
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = { browser?.loadUrl(XHS_PROBE_URL) }) { Text("小红书") }
                TextButton(onClick = {
                    desktopMode = !desktopMode
                    browser?.let { view ->
                        view.settings.userAgentString = if (desktopMode) desktopAgent else mobileAgent
                        view.settings.useWideViewPort = desktopMode
                        view.settings.loadWithOverviewMode = desktopMode
                        view.setInitialScale(0)
                        status = if (desktopMode) "切换桌面网页版中…" else "切换手机版中…"
                        view.loadUrl(XHS_PROBE_URL)
                    }
                }) { Text(if (desktopMode) "切回手机版" else "桌面网页版") }
                TextButton(onClick = { browser?.zoomOut() }) { Text("缩小 −") }
                TextButton(onClick = { browser?.zoomIn() }) { Text("放大 +") }
                TextButton(onClick = {
                    browser?.let { it.setInitialScale(0); it.reload() }
                }) { Text("重载适配") }
                TextButton(onClick = { browser?.loadUrl(GENERAL_PROBE_URL) }) { Text("对照网页") }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XHS_PROBE_URL)))
                    }.onFailure { status = "无法打开系统浏览器：" + it.message.orEmpty().take(80) }
                }) { Text("外部打开") }
            }
            Text(
                (if (desktopMode) "桌面 · " else "手机 · ") + status,
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
                        settings.useWideViewPort = false
                        settings.loadWithOverviewMode = false
                        settings.textZoom = 100
                        setInitialScale(0)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress.coerceIn(0, 100)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                if (request?.isForMainFrame != true) return false
                                val target = request.url ?: return true
                                val host = target.host.orEmpty().lowercase()
                                val allowed = target.scheme == "https" &&
                                    (host == "xiaohongshu.com" ||
                                        host.endsWith(".xiaohongshu.com") ||
                                        host == "example.com")
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
                                        "页面要求跳转小红书 App；已留在测试页。此网页不等于可供角色阅读的网页版。"
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
                                progress = 0
                                status = "正在加载网页…"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                val riskCode = if (uri?.path?.contains("/website-login/error") == true)
                                    uri.getQueryParameter("error_code") else null
                                status = if (riskCode != null) {
                                    "小红书限制了这个浏览器会话（错误码 $riskCode）。请勿反复尝试登录。"
                                } else if (!status.startsWith("网页加载失败") &&
                                    !status.startsWith("网页返回 HTTP")) {
                                    if (resourceFailures > 0)
                                        "网页主文档已加载，部分资源失败（$resourceFailures 项）；可关闭 VPN 后重载适配。"
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
                                if (request?.isForMainFrame == true &&
                                    (errorResponse?.statusCode ?: 0) >= 400) {
                                    status = "网页返回 HTTP " + errorResponse?.statusCode
                                }
                            }
                        }
                        browser = this
                        loadUrl(XHS_PROBE_URL)
                    }
                },
                onRelease = { view ->
                    if (browser === view) browser = null
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    view.webChromeClient = null
                    view.destroy()
                },
            )
        }
    }
}
