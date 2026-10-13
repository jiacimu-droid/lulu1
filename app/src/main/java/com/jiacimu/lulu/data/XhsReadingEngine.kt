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
import android.view.ViewGroup
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference
import kotlinx.coroutines.TimeoutCancellationException
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

internal data class XhsArticle(val id: String, val title: String, val body: String, val author: String, val provider: String, val images: List<XhsImage> = emptyList()) {
    val sourceUrl: String get() = "https://www.xiaohongshu.com/explore/$id"
}

/** One session and one parser for native reading and authorized character tools.
 * Reuse the actual login browser (including its SPA and session storage); when
 * absent, retain one attached browser for this foreground Activity. Search data
 * must belong to the requested keyword, even when rendered in an overlay.
 * Android background execution is not promised: MCP remains the optional path.
 */
internal object XhsReadingEngine {
    @Volatile private var owner: WeakReference<Activity>? = null
    private val navigation = Mutex()
    private var visibleBrowser: WeakReference<WebView>? = null
    private var retainedBrowser: WebView? = null
    private var controlledBrowser: WebView? = null
    fun attachBrowser(view: WebView) {
        if (controlledBrowser !== retainedBrowser) releaseRetainedBrowser()
        visibleBrowser = WeakReference(view)
    }
    fun detachBrowser(view: WebView) { if (visibleBrowser?.get() === view) visibleBrowser = null }
    fun controls(view: WebView?): Boolean = view != null && controlledBrowser === view
    private fun releaseRetainedBrowser() {
        retainedBrowser?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        retainedBrowser = null
    }
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("lulu_external_mcp_v1", Context.MODE_PRIVATE)
    fun lastDiagnostic(context: Context): String = listOf(
        prefs(context).getString("xiaohongshu:last-tool-attempt", "").orEmpty(),
        prefs(context).getString("xiaohongshu:last-diagnostic", "").orEmpty(),
    ).filter(String::isNotBlank).joinToString("\n")

    /**
     * Plain stage+result only. No cookies, search terms, note URLs, account
     * identifiers, session tokens or model-generated paraphrases.
     */
    fun recordToolStage(context: Context, phase: String, failure: String = "") {
        val state = JSONObject().put("stage", phase.take(24))
            .put("at", System.currentTimeMillis())
            .put("status", if (failure.isBlank()) "succeeded" else "failed")
        if (failure.isNotBlank()) {
            state.put("reason", failure
                .replace(Regex("""https?://\S+"""), "[链接]")
                .replace(Regex("""(?i)(?:xsec_token|web_session|token)=[^\s&，；]+"""), "[敏感参数]")
                .take(130))
        }
        prefs(context).edit().putString("xiaohongshu:last-tool-attempt", state.toString()).apply()
    }
    fun localEnabled(context: Context): Boolean = prefs(context).getBoolean("xiaohongshu:local", true)
    fun setLocalEnabled(context: Context, enabled: Boolean): Boolean =
        prefs(context).edit().putBoolean("xiaohongshu:local", enabled).commit()
    fun resume(activity: Activity) {
        if (retainedBrowser?.context !== activity && controlledBrowser !== retainedBrowser) releaseRetainedBrowser()
        owner = WeakReference(activity)
    }
    fun pause(activity: Activity) {
        if (owner?.get() === activity) {
            owner = null
            if (controlledBrowser !== retainedBrowser) releaseRetainedBrowser()
        }
    }
    fun available(context: Context): Boolean =
        (localEnabled(context) && owner?.get()?.let { !it.isDestroyed && !it.isFinishing } == true) ||
            LuluExternalMcp.configured(context, "xiaohongshu")

    /**
     * The phone browser and the optional authenticated MCP are two real
     * sources. A failed local page is not proof that MCP is down. Try the
     * second configured source before admitting that nothing was read.
     */
    suspend fun list(context: Context, keyword: String, onProgress: (String) -> Unit = {},
                     localOnly: Boolean = false): List<XhsCard> {
        val query = keyword.trim().take(64)
        val canUseLocal = localEnabled(context) &&
            owner?.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        val canUseMcp = !localOnly && LuluExternalMcp.configured(context, "xiaohongshu")
        if (localOnly && !canUseLocal) {
            error("内置浏览器不可用：请打开露露机并开启「使用已登录的本机网页」，再重试")
        }
        var localFailure = ""
        if (canUseLocal) {
            try {
                onProgress("正在通过已登录网页寻找笔记")
                val url = if (query.isBlank()) "https://www.xiaohongshu.com/explore" else
                    "https://www.xiaohongshu.com/search_result?keyword=${Uri.encode(query)}&source=web_explore_feed"
                val root = loadPage(context, url, if (query.isBlank()) "feed" else "search", "", query, onProgress)
                val values = root.optJSONArray("items")
                val found = (0 until (values?.length() ?: 0)).mapNotNull {
                    val row = values?.optJSONObject(it) ?: return@mapNotNull null
                    card(row, query, "webview")
                }.distinctBy { it.id }
                if (found.isNotEmpty() || !canUseMcp) return found
                localFailure = "本机网页没有返回可读笔记"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                localFailure = error.message.orEmpty().take(140).ifBlank { "本机网页读取失败" }
                if (!canUseMcp) throw IllegalStateException("本机小红书读取失败：$localFailure", error)
            }
        }
        if (!canUseMcp) {
            error(if (canUseLocal) "本机网页没有返回可读笔记" else
                "角色当前没有可用的小红书读取窗口；请打开露露机并登录网页，或连接可用的 MCP")
        }
        if (localFailure.isNotBlank()) onProgress("本机阅读未成功，正在尝试已有 MCP 连接")
        try {
            val raw = LuluExternalMcp.xhsRead(context, if (query.isBlank()) "list_feeds" else "search_feeds",
                if (query.isBlank()) JSONObject() else JSONObject().put("keyword", query))
            val root = JSONObject(raw)
            return parseMcpCards(root, query)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val mcpFailure = error.message.orEmpty().take(140).ifBlank { "MCP 未能返回笔记" }
            throw IllegalStateException(
                if (localFailure.isBlank()) "小红书 MCP 读取失败：$mcpFailure"
                else "小红书本机读取失败：$localFailure；备用 MCP 也失败：$mcpFailure",
                error,
            )
        }
    }

    /**
     * Both upstream types are real: Go MCP returns nested noteCard, whereas
     * @the-thing/xiaohongshu-mcp (our Termux adapter) returns *normalized*
     * {id,xsecToken,title,authorName}. Reject missing token later, but do
     * not discard perfectly valid normalized results here.
     */
    internal fun parseMcpCards(root: JSONObject, query: String): List<XhsCard> {
        val rows = root.optJSONArray("feeds") ?: root.optJSONObject("data")?.optJSONArray("feeds")
            ?: error("MCP 没有返回笔记列表")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val note = row.optJSONObject("noteCard") ?: row.optJSONObject("note_card") ?: row
            val user = note.optJSONObject("user")
            card(JSONObject()
                .put("id", row.optString("id").ifBlank { note.optString("noteId") })
                .put("token", row.optString("xsecToken")
                    .ifBlank { row.optString("xsec_token") }
                    .ifBlank { note.optString("xsecToken") }
                    .ifBlank { note.optString("xsec_token") })
                .put("title", note.optString("displayTitle")
                    .ifBlank { note.optString("title") })
                .put("author", row.optString("authorName")
                    .ifBlank { row.optString("author") }
                    .ifBlank { user?.optString("nickname").orEmpty() })
                .put("source", row.optString("source")), query, "mcp")
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

    suspend fun read(context: Context, target: XhsCard, onProgress: (String) -> Unit = {},
                     localOnly: Boolean = false): XhsArticle {
        require(Regex("[a-zA-Z0-9]{12,40}").matches(target.id) && target.token.isNotBlank()) {
            "笔记缺少有效 ID 或详情令牌，未标记为已读"
        }
        if (localOnly) require(target.provider == "webview") {
            "该笔记并非来自内置浏览器，不能算内置浏览器测试成功"
        }
        var actualProvider = target.provider
        val note = if (target.provider == "webview") {
            require(localEnabled(context)) { "本机阅读已关闭" }
            try {
                loadPage(context, target.url(), "note", target.id, "", onProgress)
                    .optJSONObject("note") ?: error("当前登录网页没有提供这篇笔记的正文")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (localOnly || !LuluExternalMcp.configured(context, "xiaohongshu")) throw error
                onProgress("网页笔记读取失败，正在尝试备用 MCP")
                try { readMcpNote(context, target).also { actualProvider = "mcp" } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (secondary: Exception) {
                    throw IllegalStateException("网页笔记读取失败：${error.message.orEmpty().take(110)}；" +
                        "备用 MCP 也未返回正文：${secondary.message.orEmpty().take(110)}", secondary)
                }
            }
        } else {
            readMcpNote(context, target)
        }
        val body = note.optString("body").trim().take(8000)
        val values = note.optJSONArray("images")
        val images = (0 until minOf(values?.length() ?: 0, 30)).mapNotNull {
            val row = values?.optJSONObject(it) ?: return@mapNotNull null
            val url = XhsImagePolicy.normalize(row.optString("url")) ?: return@mapNotNull null
            XhsImage(url, row.optInt("width"), row.optInt("height"))
        }.distinctBy { it.url }
        require(body.isNotBlank() || images.isNotEmpty()) { "实际笔记没有正文或图片，未标记为已读" }
        return XhsArticle(target.id, note.optString("title").ifBlank { target.title }, body,
            note.optString("author").ifBlank { target.author }, actualProvider, images)
    }

    private suspend fun readMcpNote(context: Context, target: XhsCard): JSONObject {
            val root = JSONObject(LuluExternalMcp.xhsRead(context, "get_feed_detail", JSONObject()
                .put("feed_id", target.id).put("xsec_token", target.token).put("load_all_comments", false)))
            return normalizeMcpNote(root, target)
    }

    internal fun normalizeMcpNote(root: JSONObject, target: XhsCard): JSONObject {
            val data = root.optJSONObject("data") ?: root
            val payload = data.optJSONObject("data") ?: data
            val raw = payload.optJSONObject("note") ?: payload.optJSONObject("detail")
                ?: error("MCP 正文结构缺少 note/detail")
            val actualId = raw.optString("noteId").ifBlank { raw.optString("note_id") }.ifBlank { raw.optString("id") }
            require(actualId.isBlank() || actualId == target.id) { "MCP 返回了其他笔记，未标记已读" }
            val pictures = org.json.JSONArray()
            val values = raw.optJSONArray("imageList") ?: raw.optJSONArray("image_list")
                ?: raw.optJSONArray("imageUrls")
            for (index in 0 until minOf(values?.length() ?: 0, 30)) {
                val item = values?.opt(index)
                // @the-thing returns imageUrls: ["https://..."], while
                // the Go MCP returns imageList: [{urlDefault,...}].
                if (item is String) {
                    pictures.put(JSONObject().put("url", item).put("width", 0).put("height", 0))
                    continue
                }
                val image = item as? JSONObject ?: continue
                val infos = image.optJSONArray("infoList") ?: image.optJSONArray("info_list")
                val full = (0 until (infos?.length() ?: 0)).mapNotNull { infos?.optJSONObject(it) }
                    .firstOrNull { it.optString("imageScene") == "WB_DFT" }?.optString("url").orEmpty()
                pictures.put(JSONObject().put("url", image.optString("urlDefault")
                    .ifBlank { image.optString("url_default") }.ifBlank { full }
                    .ifBlank { image.optString("urlPre") }.ifBlank { image.optString("url") })
                    .put("width", image.optInt("width")).put("height", image.optInt("height")))
            }
            return JSONObject().put("images", pictures).put("title", raw.optString("title"))
                .put("body", raw.optString("desc").ifBlank { raw.optString("description") })
                .put("author", raw.optJSONObject("user")?.optString("nickname")
                    .orEmpty().ifBlank { raw.optJSONObject("author")?.optString("nickname").orEmpty() })
    }

    private suspend fun loadPage(
        context: Context, url: String, kind: String, id: String, keyword: String,
        onProgress: (String) -> Unit,
    ): JSONObject = withContext(Dispatchers.Main.immediate) {
        var lastStage = "等待阅读窗口"
        onProgress(lastStage)
        var latestReport = JSONObject()
        try {
            // The deadline also covers waiting for another reading to release the session.
            withTimeout(if (kind == "search") 25_000L else 35_000L) {
                navigation.withLock {
                    val activity = owner?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                        ?: error("本机阅读需露露机保持前台")
                    val container = activity.findViewById<FrameLayout>(android.R.id.content)
                        ?: error("阅读窗口尚未准备好")
                    val script = withContext(Dispatchers.IO) {
                        context.assets.open("xhs-reader.js").bufferedReader().use { it.readText() }
                    }
                    val shared = visibleBrowser?.get()?.takeIf { it.isAttachedToWindow }
                    val view = shared ?: retainedBrowser ?: WebView(activity).also { created ->
                        configure(created)
                        created.isFocusable = false
                        created.isFocusableInTouchMode = false
                        container.addView(created, 0, FrameLayout.LayoutParams(-1, -1))
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                            WebViewCompat.addDocumentStartJavaScript(created, script,
                                setOf("https://www.xiaohongshu.com", "https://xiaohongshu.com"))
                        retainedBrowser = created
                    }
                    controlledBrowser = view
                    var mainError = ""
                    if (shared == null) view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(web: WebView?, request: WebResourceRequest?): Boolean {
                            if (request?.isForMainFrame != true) return false
                            return request.url.scheme != "https" || request.url.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")
                        }
                        override fun onReceivedError(web: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true && !error?.description.toString().contains("ERR_ABORTED", true))
                                mainError = "网页加载失败（${error?.errorCode}）"
                        }
                        override fun onReceivedHttpError(web: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) mainError = "网页返回 HTTP ${response?.statusCode}"
                        }
                    }
                    fun stage(value: String) {
                        if (lastStage != value) { lastStage = value; onProgress(value) }
                    }
                    var searchSubmitted = false
                    var searchStartedAt = System.currentTimeMillis()
                    var navigatedToSearchPage = false
                    try {
                        if (kind == "search") {
                            stage("在已登录网页搜索")
                            val uri = Uri.parse(view.url.orEmpty())
                            if (uri.scheme != "https" || uri.host !in setOf("www.xiaohongshu.com", "xiaohongshu.com"))
                                view.loadUrl("https://www.xiaohongshu.com/explore")
                        } else {
                            stage(if (kind == "note") "读取笔记图文" else "读取首页推荐")
                            if (view.url != url) view.loadUrl(url)
                        }
                        while (true) {
                            delay(350)
                            check(owner?.get() === activity && !activity.isDestroyed) { "已离开前台，读取停止" }
                            check(shared == null || view.isAttachedToWindow) { "阅读窗口已关闭" }
                            check(mainError.isBlank()) { mainError }
                            val host = Uri.parse(view.url.orEmpty()).host
                            if (host !in setOf("www.xiaohongshu.com", "xiaohongshu.com")) continue
                            val raw = evaluate(view, script + "\nwindow.__luluXhsReader.snapshot(${JSONObject.quote(kind)},${JSONObject.quote(id)},${JSONObject.quote(keyword)});")
                            val root = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull() ?: continue
                            latestReport = root.optJSONObject("diagnostics") ?: JSONObject()
                            latestReport.put("stage", kind).put("phase", lastStage)
                                .put("session", if (shared != null) "login-browser" else "retained-browser")
                                .put("nativeWidth", view.width).put("nativeHeight", view.height)
                                .put("ready", root.optBoolean("onPage")).put("result", root.optString("error"))
                            prefs(context).edit().putString("xiaohongshu:last-diagnostic", latestReport.toString()).apply()
                            val reason = root.optString("error")
                            check(reason != "restricted") { "小红书限制访问，请在网页登录页查看验证提示" }
                            check(!reason.startsWith("api:")) { "小红书接口失败（${reason.removePrefix("api:")}）" }
                            check(reason != "network") { "小红书数据请求连接失败" }
                            check(reason != "invalid") { "小红书返回的数据无法解析" }
                            if (root.optJSONObject("note") != null || (root.optJSONArray("items")?.length() ?: 0) > 0 || root.optBoolean("empty"))
                                return@withLock root
                            check(reason != "login") { "请先在小红书网页完成登录" }
                            if (kind == "search" && !searchSubmitted) {
                                val state = evaluate(view, """(() => {
                                    const reader = window.__luluXhsReader;
                                    const keyword = ${JSONObject.quote(keyword)};
                                    const url = new URL(location.href);
                                    if (reader.pendingSearch(keyword) ||
                                        (['/search_result','/search','/search/result'].includes(url.pathname) && url.searchParams.get('keyword') === keyword)) return 'waiting';
                                    return reader.startSearch(keyword);
                                })()""".trimIndent())
                                val action = runCatching { JSONTokener(state).nextValue() as String }.getOrNull()
                                if (action == "submitted" || action == "waiting") {
                                    searchSubmitted = true
                                    searchStartedAt = System.currentTimeMillis()
                                    stage("等待搜索结果")
                                } else if (action == "input-missing") {
                                    // Many XHS desktop builds hide the search
                                    // input behind an SPA overlay. Reuse the
                                    // SAME logged-in WebView and navigate to
                                    // its official search_result route; never
                                    // call signed internal APIs ourselves.
                                    searchSubmitted = true
                                    navigatedToSearchPage = true
                                    stage("打开网页搜索结果")
                                    view.loadUrl(url)
                                } else stage("等待网页搜索框")
                            }
                            if (kind == "search" && searchSubmitted && !navigatedToSearchPage &&
                                System.currentTimeMillis() - searchStartedAt > 7_000L) {
                                // If a synthetic click opened a stuck SPA
                                // search screen, force ONE official full page
                                // navigation. New document starts with fresh
                                // Vue state and the same logged-in cookies.
                                navigatedToSearchPage = true
                                stage("直接读取网页搜索结果")
                                view.loadUrl(url)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") JSONObject()
                    } finally {
                        controlledBrowser = null
                        CookieManager.getInstance().flush()
                        if (owner?.get() !== activity || (shared == null && visibleBrowser?.get()?.isAttachedToWindow == true)) releaseRetainedBrowser()
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            val api = latestReport.optJSONObject("api")?.optJSONObject(kind)
            val detail = if (kind == "search") {
                if (lastStage == "等待网页搜索框" || lastStage == "等待阅读窗口") "网页搜索尚未启动" else
                    if (api == null) "搜索请求未返回" else "搜索已返回但没有可读取的笔记"
            } else lastStage
            latestReport.put("result", "timeout").put("phase", lastStage)
            prefs(context).edit().putString("xiaohongshu:last-diagnostic", latestReport.toString()).apply()
            throw IllegalStateException("读取超时：$detail", error)
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
