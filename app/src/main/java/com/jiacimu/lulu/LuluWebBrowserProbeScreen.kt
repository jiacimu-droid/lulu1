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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

private const val XHS_PROBE_URL = "https://www.xiaohongshu.com/explore"

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
    var status by remember { mutableStateOf("准备打开小红书桌面网页版…") }
    var progress by remember { mutableIntStateOf(0) }
    var resourceFailures by remember { mutableIntStateOf(0) }
    var apiFailures by remember { mutableIntStateOf(0) }
    var lastApiFailure by remember { mutableStateOf("") }
    var jsNetworkFailures by remember { mutableIntStateOf(0) }
    var browserDiagnostics by remember { mutableStateOf("") }
    var diagnosticsVisible by remember { mutableStateOf(false) }
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

    if (diagnosticsVisible) {
        AlertDialog(
            onDismissRequest = { diagnosticsVisible = false },
            title = { Text("网页诊断结果") },
            text = { Text(browserDiagnostics, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { diagnosticsVisible = false }) { Text("关闭") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小红书网页版测试") },
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { browser?.loadUrl(XHS_PROBE_URL) },
                ) { Text("首页") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { browser?.reload() },
                ) { Text("刷新") }
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
                                pageReady: document.readyState
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
                                if (apiFailures == 0) append("\n注意：HTTP 200 也可能包含网站业务错误。")
                            }
                        }
                    }
                }) { Text("诊断") }
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
                                apiFailures = 0
                                lastApiFailure = ""
                                jsNetworkFailures = 0
                                browserDiagnostics = ""
                                diagnosticsVisible = false
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
                        loadUrl(XHS_PROBE_URL)
                    }
                },
                onRelease = { view ->
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
}
