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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "这是网页兼容性测试，不会把网页、Cookie 或账号信息交给角色。"
                    + " 这里的登录状态目前不会同步给 Termux MCP。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { browser?.loadUrl(XHS_PROBE_URL) }) { Text("小红书") }
                OutlinedButton(onClick = { browser?.loadUrl(GENERAL_PROBE_URL) }) { Text("普通网页对照") }
                OutlinedButton(onClick = { browser?.reload() }) { Text("刷新") }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XHS_PROBE_URL)))
                    }.onFailure { status = "无法打开系统浏览器：" + it.message.orEmpty().take(80) }
                }) { Text("外部浏览器") }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
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
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = WebChromeClient()
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
                                    status = "阻止非网页链接：" + target.scheme.orEmpty()
                                }
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
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
                                    "网页加载结束。请检查是否显示正常内容或登录界面。"
                                } else status
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    status = "网页加载失败：" + error?.description.orEmpty().take(100)
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
