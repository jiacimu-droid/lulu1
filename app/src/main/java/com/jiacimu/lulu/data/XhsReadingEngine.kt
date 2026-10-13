package com.jiacimu.lulu.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

internal data class XhsCard(
    val id: String, val token: String, val title: String, val author: String,
    val source: String, val provider: String = "webview",
) {
    fun url(): String = Uri.Builder().scheme("https").authority("www.xiaohongshu.com")
        .appendPath("explore").appendPath(id).appendQueryParameter("xsec_token", token)
        .appendQueryParameter("xsec_source", source).build().toString()
}

internal data class XhsArticle(val id: String, val title: String, val body: String, val author: String, val provider: String) {
    val sourceUrl: String get() = "https://www.xiaohongshu.com/explore/$id"
}

/** One session and one parser for native reading and authorized character tools.
 * A fresh, full-sized attached WebView per navigation prevents old SPA responses
 * from becoming the next search. Its default CookieManager reuses the login view.
 * Android background execution is not promised: MCP remains the optional path.
 */
internal object XhsReadingEngine {
    @Volatile private var owner: WeakReference<Activity>? = null
    private val navigation = Mutex()
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("lulu_external_mcp_v1", Context.MODE_PRIVATE)
    fun lastDiagnostic(context: Context): String = prefs(context).getString("xiaohongshu:last-diagnostic", "").orEmpty()
    fun localEnabled(context: Context): Boolean = prefs(context).getBoolean("xiaohongshu:local", true)
    fun setLocalEnabled(context: Context, enabled: Boolean): Boolean =
        prefs(context).edit().putBoolean("xiaohongshu:local", enabled).commit()
    fun resume(activity: Activity) { owner = WeakReference(activity) }
    fun pause(activity: Activity) { if (owner?.get() === activity) owner = null }
    fun available(context: Context): Boolean =
        (localEnabled(context) && owner?.get()?.let { !it.isDestroyed && !it.isFinishing } == true) ||
            LuluExternalMcp.configured(context, "xiaohongshu")

    suspend fun list(context: Context, keyword: String): List<XhsCard> {
        val query = keyword.trim().take(64)
        if (localEnabled(context) && owner?.get() != null) {
            val url = if (query.isBlank()) "https://www.xiaohongshu.com/explore" else
                "https://www.xiaohongshu.com/search_result?keyword=${Uri.encode(query)}&source=web_explore_feed"
            val root = loadPage(context, url, if (query.isBlank()) "feed" else "search", "", query)
            val values = root.optJSONArray("items")
            return (0 until (values?.length() ?: 0)).mapNotNull {
                val row = values?.optJSONObject(it) ?: return@mapNotNull null
                card(row, query, "webview")
            }.distinctBy { it.id }
        }
        require(LuluExternalMcp.configured(context, "xiaohongshu")) { "请打开露露机使用本机阅读，或配置可用的 MCP" }
        val raw = LuluExternalMcp.xhsRead(context, if (query.isBlank()) "list_feeds" else "search_feeds",
            if (query.isBlank()) JSONObject() else JSONObject().put("keyword", query))
        val root = JSONObject(raw)
        val rows = root.optJSONArray("feeds") ?: root.optJSONObject("data")?.optJSONArray("feeds")
            ?: error("MCP 没有返回笔记列表")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val note = row.optJSONObject("noteCard") ?: row.optJSONObject("note_card") ?: return@mapNotNull null
            card(JSONObject().put("id", row.optString("id"))
                .put("token", row.optString("xsecToken").ifBlank { row.optString("xsec_token") })
                .put("title", note.optString("displayTitle").ifBlank { note.optString("title") })
                .put("author", note.optJSONObject("user")?.optString("nickname").orEmpty()), query, "mcp")
        }.distinctBy { it.id }
    }

    private fun card(row: JSONObject, query: String, provider: String): XhsCard? {
        val id = row.optString("id")
        if (!Regex("[a-zA-Z0-9]{12,40}").matches(id)) return null
        return XhsCard(id, row.optString("token").take(512),
            row.optString("title").take(200).ifBlank { "未命名笔记" }, row.optString("author").take(120),
            row.optString("source").takeIf { it in setOf("pc_feed", "pc_search", "pc_user", "pc_share", "pc_board") }
                ?: if (query.isBlank()) "pc_feed" else "pc_search", provider)
    }

    suspend fun read(context: Context, target: XhsCard): XhsArticle {
        require(Regex("[a-zA-Z0-9]{12,40}").matches(target.id) && target.token.isNotBlank()) {
            "笔记缺少有效 ID 或详情令牌，未标记为已读"
        }
        val note = if (target.provider == "webview") {
            require(localEnabled(context)) { "本机阅读已关闭" }
            loadPage(context, target.url(), "note", target.id, "").optJSONObject("note")
        } else {
            val root = JSONObject(LuluExternalMcp.xhsRead(context, "get_feed_detail", JSONObject()
                .put("feed_id", target.id).put("xsec_token", target.token).put("load_all_comments", false)))
            val data = root.optJSONObject("data") ?: root
            val payload = data.optJSONObject("data") ?: data
            val raw = payload.optJSONObject("note") ?: error("MCP 正文结构缺少 note")
            val actualId = raw.optString("noteId").ifBlank { raw.optString("note_id") }.ifBlank { raw.optString("id") }
            require(actualId.isBlank() || actualId == target.id) { "MCP 返回了其他笔记，未标记已读" }
            JSONObject().put("title", raw.optString("title")).put("body", raw.optString("desc"))
                .put("author", raw.optJSONObject("user")?.optString("nickname").orEmpty())
        } ?: error("网站没有提供这篇笔记的正文")
        val body = note.optString("body").trim().take(8000)
        require(body.isNotBlank()) { "实际正文为空，未标记为已读" }
        return XhsArticle(target.id, note.optString("title").ifBlank { target.title }, body,
            note.optString("author").ifBlank { target.author }, target.provider)
    }

    private suspend fun loadPage(context: Context, url: String, kind: String, id: String, keyword: String): JSONObject =
        withContext(Dispatchers.Main.immediate) {
            navigation.withLock {
                val activity = owner?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: error("本机阅读需要露露机保持打开；后台可另配 MCP")
                val container = activity.findViewById<FrameLayout>(android.R.id.content)
                    ?: error("阅读窗口尚未准备好")
                val script = withContext(Dispatchers.IO) { context.assets.open("xhs-reader.js").bufferedReader().use { it.readText() } }
                val view = WebView(activity)
                var mainError = ""
                try {
                    configure(view)
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    // Behind the opaque Compose surface, with a real viewport. Not GONE/1dp.
                    container.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(web: WebView?, request: WebResourceRequest?): Boolean {
                            if (request?.isForMainFrame != true) return false
                            val uri = request.url
                            return uri.scheme != "https" || uri.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")
                        }
                        override fun onReceivedError(web: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true && request.url.toString() == url &&
                                !error?.description.toString().contains("ERR_ABORTED", true)) {
                                mainError = "网页加载失败（${error?.errorCode}）"
                            }
                        }
                        override fun onReceivedHttpError(web: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) mainError = "网页返回 HTTP ${response?.statusCode}"
                        }
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(view, script,
                            setOf("https://www.xiaohongshu.com", "https://xiaohongshu.com"))
                    }
                    prefs(context).edit().putString("xiaohongshu:last-diagnostic", "阶段=$kind；等候网页数据").apply()
                    view.loadUrl(url)
                    withTimeout(35_000) {
                        while (true) {
                            delay(700)
                            check(owner?.get() === activity && !activity.isDestroyed) { "露露机已离开前台，读取已停止；未新增阅读记录" }
                            check(mainError.isBlank()) { mainError }
                            val host = Uri.parse(view.url.orEmpty()).host
                            if (host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")) continue
                            // Fallback for older WebViews; repeated evaluation is idempotent.
                            val js = script + "\nwindow.__luluXhsReader.snapshot(${JSONObject.quote(kind)},${JSONObject.quote(id)},${JSONObject.quote(keyword)});"
                            val raw = evaluate(view, js)
                            val root = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull() ?: continue
                            val report = root.optJSONObject("diagnostics") ?: JSONObject()
                            report.put("stage", kind).put("nativeWidth", view.width).put("nativeHeight", view.height)
                                .put("ready", root.optBoolean("onPage")).put("result", root.optString("error"))
                            val reportText = report.toString()
                            if (lastDiagnostic(context) != reportText)
                                prefs(context).edit().putString("xiaohongshu:last-diagnostic", reportText).apply()
                            val reason = root.optString("error")
                            check(reason != "restricted") {
                                val api = report.optJSONObject("api")?.optJSONObject(kind)
                                "小红书拒绝了本次访问（HTTP ${api?.optInt("http") ?: 0}，业务码 ${api?.optString("code").orEmpty().ifBlank { "网页限制提示" }}），未新增阅读记录"
                            }
                            check(!reason.startsWith("api:")) { "小红书数据接口失败（${reason.removePrefix("api:")}），未新增阅读记录" }
                            check(reason != "network") { "小红书数据请求未能连接，请检查网页网络" }
                            if (root.optJSONObject("note") != null || (root.optJSONArray("items")?.length() ?: 0) > 0)
                                return@withTimeout root
                            check(reason != "login") { "请先在小红书登录网页完成登录" }
                            if (root.optBoolean("empty")) return@withTimeout root
                        }
                        @Suppress("UNREACHABLE_CODE")
                        JSONObject()
                    }
                } finally {
                    view.stopLoading()
                    container.removeView(view)
                    view.webViewClient = WebViewClient()
                    CookieManager.getInstance().flush()
                    view.destroy()
                }
            }
        }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it ?: "null") }
    }

    private fun configure(view: WebView) {
        val version = Regex("Chrome/([0-9.]+)").find(WebSettings.getDefaultUserAgent(view.context))
            ?.groupValues?.get(1) ?: "120.0.0.0"
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) runCatching {
            val brands = listOf("Chromium", "Google Chrome").map { brand ->
                UserAgentMetadata.BrandVersion.Builder().setBrand(brand)
                    .setMajorVersion(version.substringBefore('.')).setFullVersion(version).build()
            }
            WebSettingsCompat.setUserAgentMetadata(view.settings, UserAgentMetadata.Builder()
                .setPlatform("Linux").setMobile(false).setBrandVersionList(brands).build())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    }
}
