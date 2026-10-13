package com.jiacimu.lulu

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

/** Only user-triggered, read-only browsing. No cookies or notes are sent to the model/MCP. */
private data class XhsReaderCard(
    val id: String,
    val token: String,
    val title: String,
    val author: String,
    val source: String,
)
private enum class XhsReaderPage { Feed, Search, Note }

private const val XHS_HOME = "https://www.xiaohongshu.com/explore"

/**
 * Read-only, native rendering of the website's own initial page state.
 * A real WebView remains attached solely for loading the public page in the
 * user's existing login session. The user never clicks XHS's broken modal.
 * Missing data is treated as a failure, not a successful reading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluXhsNativeReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(XhsReaderPage.Feed) }
    var keyword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<XhsReaderCard?>(null) }
    var cards by remember { mutableStateOf<List<XhsReaderCard>>(emptyList()) }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var noteAuthor by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("正在读取网页中的真实推荐笔记…") }
    var sequence by remember { mutableIntStateOf(0) }
    var latestHttpError by remember { mutableStateOf("") }

    val originalUserAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val chromeVersion = remember(originalUserAgent) {
        Regex("Chrome/([0-9.]+)").find(originalUserAgent)?.groupValues?.get(1) ?: "120.0.0.0"
    }
    val desktopUserAgent = remember(chromeVersion) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    fun targetUrl(target: XhsReaderCard): String =
        Uri.Builder().scheme("https").authority("www.xiaohongshu.com")
            .appendPath("explore").appendPath(target.id)
            .appendQueryParameter("xsec_token", target.token)
            .appendQueryParameter("xsec_source", target.source)
            .build().toString()

    fun navigate(url: String, next: XhsReaderPage) {
        page = next
        sequence += 1
        loading = true
        message = "正在读取小红书实际网页数据…"
        latestHttpError = ""
        if (next == XhsReaderPage.Note) {
            noteTitle = ""
            noteBody = ""
            noteAuthor = ""
        } else {
            cards = emptyList()
        }
        web?.loadUrl(url)
    }

    fun backWithinReader() {
        if (page == XhsReaderPage.Note) {
            navigate(
                if (keyword.isBlank()) XHS_HOME
                else "https://www.xiaohongshu.com/search_result?keyword=" + Uri.encode(keyword) + "&source=web_explore_feed",
                if (keyword.isBlank()) XhsReaderPage.Feed else XhsReaderPage.Search,
            )
        } else onBack()
    }

    BackHandler { backWithinReader() }

    LaunchedEffect(sequence) {
        val started = sequence
        delay(23_000)
        if (sequence == started && loading) {
            loading = false
            message = if (latestHttpError.isNotBlank()) latestHttpError
                else "小红书在规定时间内未提供列表/正文数据。可能是网站限制或页面脚本未完成。"
        }
    }

    val listScript = remember {
        """
        (() => {
          try {
            const state = window.__INITIAL_STATE__ || {};
            const unwrap = o => o?._rawValue ?? o?._value ?? o?.value ?? o;
            const searching = location.pathname.includes('search');
            const data = searching ? unwrap(state.search?.feeds)
                : unwrap(state.feed?.feeds) ?? unwrap(state.homeFeed?.feeds);
            const list = Array.isArray(data) ? data
                : (Array.isArray(unwrap(data?.feeds)) ? unwrap(data.feeds) : []);
            const used = new Set(), items = [];
            function add(id, token, title, author, source) {
              id = String(id || '').trim();
              token = String(token || '').trim();
              if (!/^[a-z0-9]{12,40}$/i.test(id) || used.has(id)) return;
              used.add(id);
              items.push({
                id, token: token.slice(0, 512),
                title: String(title || '未命名笔记').slice(0, 160),
                author: String(author || '').slice(0, 90),
                source: String(source || (searching ? 'pc_search' : 'pc_feed')).slice(0, 32)
              });
            }
            for (const row of list) {
              const card = row?.noteCard ?? row?.note_card;
              if (!row || !card) continue;
              add(row.id ?? card.noteId, row.xsecToken ?? row.xsec_token,
                  card.displayTitle ?? card.title, card.user?.nickname ?? card.user?.nickName,
                  searching ? 'pc_search' : 'pc_feed');
              if (items.length >= 35) break;
            }
            // DOM fallback uses actual same-origin note URLs only.
            if (!items.length) for (const a of document.querySelectorAll('a[href]')) {
              try {
                const url = new URL(a.href);
                if (url.hostname !== location.hostname) continue;
                const match = url.pathname.match(/^\/(?:explore|discovery\/item)\/([a-z0-9]{12,40})$/i);
                if (!match) continue;
                add(match[1], url.searchParams.get('xsec_token') || '',
                    a.querySelector('[class*="title"]')?.textContent ||
                      a.getAttribute('title') || a.innerText || '',
                    '', url.searchParams.get('xsec_source') || '');
                if (items.length >= 35) break;
              } catch (_) {}
            }
            const text = (document.body?.innerText || '').slice(0, 6500);
            return JSON.stringify({
              items,
              blocked: /IP at risk|300012|安全验证|访问异常|当前页面不可访问/.test(text),
              loginRequired: /请登录后|扫码登录/.test(text)
            });
          } catch (_) { return JSON.stringify({items:[], exception:true}); }
        })()
        """.trimIndent()
    }

    fun noteScript(id: String): String {
        val quotedId = JSONObject.quote(id)
        return """
            (() => {
              try {
                const unwrap = o => o?._rawValue ?? o?._value ?? o?.value ?? o;
                const state = window.__INITIAL_STATE__ || {};
                const noteState = state.note || {};
                const map = unwrap(noteState.noteDetailMap) || {};
                const entry = unwrap(map[$quotedId]) || {};
                const note = unwrap(entry.note ?? entry.data?.note ?? {});
                const title = String(note.title ?? '').slice(0, 200);
                const body = String(note.desc ?? note.description ?? '').slice(0, 8000);
                const author = String(note.user?.nickname ?? note.user?.nickName ?? '').slice(0, 120);
                const text = (document.body?.innerText || '').slice(0, 6500);
                return JSON.stringify({
                  title, body, author,
                  blocked: /笔记详情获取失败|笔记不可访问|当前笔记暂时无法浏览|安全限制|300012|300017/.test(text),
                  hasState: !!window.__INITIAL_STATE__
                });
              } catch (_) { return JSON.stringify({body:'', title:'', exception:true}); }
            })()
        """.trimIndent()
    }

    fun inspect(loaded: WebView, expectedPage: XhsReaderPage, seq: Int, attempt: Int) {
        if (sequence != seq || web !== loaded || page != expectedPage || !loading) return
        val script = if (expectedPage == XhsReaderPage.Note) {
            noteScript(selected?.id.orEmpty())
        } else listScript
        loaded.evaluateJavascript(script) { raw ->
            if (sequence != seq || web !== loaded || page != expectedPage || !loading) return@evaluateJavascript
            val result = runCatching {
                JSONObject(JSONTokener(raw).nextValue() as String)
            }.getOrNull()
            if (expectedPage == XhsReaderPage.Note) {
                val body = result?.optString("body").orEmpty().trim()
                if (body.isNotBlank()) {
                    noteTitle = result?.optString("title").orEmpty().ifBlank { selected?.title.orEmpty() }
                    noteBody = body
                    noteAuthor = result?.optString("author").orEmpty()
                    loading = false
                    message = "已读取网页中的真实笔记正文。"
                    return@evaluateJavascript
                }
            } else {
                val values = result?.optJSONArray("items")
                val parsed = (0 until (values?.length() ?: 0)).mapNotNull { index ->
                    val item = values?.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    if (id.isBlank()) return@mapNotNull null
                    XhsReaderCard(id, item.optString("token"), item.optString("title"),
                        item.optString("author"), item.optString("source", "pc_feed"))
                }
                if (parsed.isNotEmpty()) {
                    cards = parsed
                    loading = false
                    message = "已获取 ${parsed.size} 篇真实笔记，点击标题可直接读取正文。"
                    return@evaluateJavascript
                }
            }
            if (attempt < 11) {
                loaded.postDelayed({ inspect(loaded, expectedPage, seq, attempt + 1) }, 1500L)
            } else {
                loading = false
                message = if (latestHttpError.isNotBlank()) latestHttpError
                else if (result?.optBoolean("blocked") == true) {
                    "小红书网页提示访问受限；不会伪造读取结果。"
                } else if (result?.optBoolean("loginRequired") == true) {
                    "网站提示需要登录，请回到原网页检查会话。"
                } else if (expectedPage == XhsReaderPage.Note) {
                    "网站没有提供可读取的笔记正文，可能被限制或内容尚未加载。"
                } else {
                    "页面未提供笔记列表数据。可以返回网页模式，或稍后重试。"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小红书 · 简洁阅读") },
                navigationIcon = {
                    IconButton(onClick = { backWithinReader() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Keep WebView attached for its same-process session cookies and SSR.
            // Keep a real-sized browser behind opaque native UI so the site can hydrate.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.userAgentString = desktopUserAgent
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                val target = view ?: return
                                val seq = sequence
                                val currentPage = page
                                val host = runCatching { Uri.parse(url.orEmpty()).host }.getOrNull()
                                if (host != "www.xiaohongshu.com" && host != "xiaohongshu.com") {
                                    loading = false
                                    message = "网站跳转到了其他地址，可能触发登录或访问限制。"
                                    return
                                }
                                target.postDelayed({ inspect(target, currentPage, seq, 0) }, 800L)
                            }
                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                response: android.webkit.WebResourceResponse?,
                            ) {
                                val code = response?.statusCode ?: 0
                                if (request?.isForMainFrame == true && code >= 400) {
                                    latestHttpError = "小红书网页返回 HTTP $code"
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    message = "网页连接失败：" + error?.description?.toString().orEmpty().take(100)
                                }
                            }
                        }
                        web = this
                        loadUrl(XHS_HOME)
                    }
                },
                onRelease = { view ->
                    if (web === view) web = null
                    CookieManager.getInstance().flush()
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    view.destroy()
                },
            )
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            if (page != XhsReaderPage.Note) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it.take(60) },
                        modifier = Modifier.weight(1f),
                        label = { Text("搜索真实笔记") },
                        singleLine = true,
                    )
                    Button(onClick = {
                        if (keyword.isBlank()) navigate(XHS_HOME, XhsReaderPage.Feed)
                        else navigate(
                            "https://www.xiaohongshu.com/search_result?keyword=" +
                                Uri.encode(keyword) + "&source=web_explore_feed",
                            XhsReaderPage.Search,
                        )
                    }) { Text("搜索") }
                }
                TextButton(onClick = { keyword = ""; navigate(XHS_HOME, XhsReaderPage.Feed) }) {
                    Text("查看首页推荐")
                }
            }

            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (!loading && cards.isEmpty() && noteBody.isEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (page == XhsReaderPage.Note) {
                if (noteBody.isNotBlank()) {
                    Text(noteTitle, style = MaterialTheme.typography.titleMedium)
                    if (noteAuthor.isNotBlank()) Text(noteAuthor, style = MaterialTheme.typography.labelMedium)
                }
                if (selected != null) {
                    OutlinedButton(onClick = {
                        val url = targetUrl(selected!!)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { message = "无法打开外部浏览器：" + it.message.orEmpty().take(60) }
                    }) { Text("在小红书/浏览器中查看原文") }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item { Text(noteBody, style = MaterialTheme.typography.bodyLarge) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable {
                            if (card.token.isBlank()) {
                                message = "这篇笔记缺少网站访问令牌，无法安全打开详情。"
                            } else {
                                selected = card
                                navigate(targetUrl(card), XhsReaderPage.Note)
                            }
                        }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(card.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    card.author + (if (card.token.isBlank()) " · 缺少详情令牌" else ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            }
        }
    }
}
