package com.jiacimu.lulu.games

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val assetHost = WebViewAssetLoader.DEFAULT_DOMAIN
private const val arcadeOrigin = "https://$assetHost"
private const val arcadePage = "/assets/arcade/index.html"
private const val bridge = "LuluArcade"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ArcadeAndroidScreen(
    store: LuluGameStore,
    kind: String,
    initialParty: List<String>,
    onExit: () -> Unit,
    onClassic: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val game = remember(context, store, kind) { ArcadeNativeGame(context, store, kind, initialParty) }
    val scope = rememberCoroutineScope()
    val exit by rememberUpdatedState(onExit)
    val classic by rememberUpdatedState(onClassic)
    var web by remember { mutableStateOf<WebView?>(null) }
    var fallback by remember { mutableStateOf<String?>(null) }
    var teamOpen by remember { mutableStateOf(false) }
    var island by remember { mutableStateOf<BlockIslandState?>(null) }
    var relic by remember { mutableStateOf<RelicRun?>(null) }
    var autonomous by remember { mutableStateOf(game.islandAutonomous) }

    fun refresh() {
        web?.evaluateJavascript("window.dispatchEvent(new Event('lulu:world'))", null)
    }

    fun back() {
        if (teamOpen) teamOpen = false
        else if (web == null || fallback != null) exit()
        else web?.evaluateJavascript("window.luluArcadeBack && window.luluArcadeBack()") { result ->
            if (result == null || result == "null") exit()
        }
    }

    BackHandler { back() }
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        AlertDialog(onDismissRequest = onExit, title = { Text("需要更新 Android System WebView") },
            text = { Text("更新系统网页组件后即可使用新版画面。原有存档可以继续游玩。") },
            confirmButton = { TextButton(onClick = onClassic) { Text("打开经典界面") } },
            dismissButton = { TextButton(onClick = onExit) { Text("返回游戏馆") } })
        return
    }

    if (fallback == null) AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
                .build()
            WebView(viewContext).also { view ->
                web = view
                view.setBackgroundColor(0xFF0B111B.toInt())
                view.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (request.method == "GET" && uri.scheme == "https" && uri.host == assetHost &&
                            (uri.path?.startsWith("/assets/arcade/") == true || uri.path == "/assets/world-joystick.js")) {
                            loader.shouldInterceptRequest(uri)?.let { return it }
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest,
                        error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) fallback = "游戏画面加载失败，请重新进入或继续使用经典界面。"
                    }

                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        // Remove the dead WebView on the next composition. Android must not close Lulu.
                        fallback = "游戏画面暂时中断，进度已保存在本机。"
                        return true
                    }
                }
                WebViewCompat.addWebMessageListener(view, bridge, setOf(arcadeOrigin)) { sender, message, origin, main, reply ->
                    if (!main || origin != Uri.parse(arcadeOrigin) || sender !== web) return@addWebMessageListener
                    val raw = message.data?.takeIf { it.length <= 8_192 } ?: return@addWebMessageListener
                    val input = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                    val id = input.optInt("requestId")
                    scope.launch {
                        val response = try {
                            when (input.optString("verb")) {
                                "exit" -> { exit(); return@launch }
                                "classic" -> { classic(); return@launch }
                                "team" -> {
                                    island = if (kind == "island") game.island() else null
                                    relic = if (kind == "relic") game.relic() else null
                                    teamOpen = island?.world?.friends?.isNotEmpty() == true || relic?.allies?.isNotEmpty() == true
                                    JSONObject()
                                }
                                "invite" -> game.invite(input.optString("actor"))
                                else -> withContext(Dispatchers.IO) { game.request(input) }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            JSONObject().put("error", error.message ?: "游戏操作未完成，请重试。")
                        }
                        if (sender === web) runCatching { reply.postMessage(response.put("requestId", id).toString()) }
                    }
                }
                view.loadUrl("$arcadeOrigin$arcadePage?scene=$kind")
            }
        },
        onReset = null,
        onRelease = { view ->
            if (web === view) web = null
            view.stopLoading()
            WebViewCompat.removeWebMessageListener(view, bridge)
            view.destroy()
        },
    )

    DisposableEffect(lifecycle, web) {
        val view = web
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    view?.evaluateJavascript("window.dispatchEvent(new Event('lulu:pause'))", null)
                    view?.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fallback?.let { message ->
        AlertDialog(onDismissRequest = onExit, title = { Text("游戏画面暂时不可用") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = onClassic) { Text("打开经典界面") } },
            dismissButton = { TextButton(onClick = onExit) { Text("返回游戏馆") } })
    }

    if (teamOpen) {
        Dialog(onDismissRequest = { teamOpen = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("语音与队友", style = MaterialTheme.typography.titleMedium)
                    island?.let { current ->
                        IslandCompanionPanel(current, navigating = false, autonomous = autonomous,
                            onAutonomousChange = { enabled ->
                                autonomous = enabled
                                game.islandAutonomous = enabled
                                game.island()?.let { saved ->
                                    if (!enabled) game.saveIsland(IslandTravel.stopAll(saved))
                                    island = game.island()
                                    refresh()
                                }
                            },
                            onSave = { next -> game.saveIsland(next); island = next; refresh() },
                            onPing = { point ->
                                web?.evaluateJavascript("window.luluArcadePing?.(${point.first},${point.second})", null)
                            },
                            readIsland = { game.island() ?: current })
                    }
                    relic?.let { current ->
                        RelicCompanionPanel(current, readRun = { game.relic() ?: current },
                            onTeamPulse = { runId -> game.pulseRelic(runId)?.also { relic = it; refresh() } },
                            onChoice = { id, choice ->
                                game.relicCompanion(current.id, id, choice).also { saved ->
                                    if (saved) { relic = game.relic(); refresh() }
                                }
                            })
                    }
                    TextButton(onClick = { teamOpen = false; refresh() }) { Text("返回战场") }
                }
            }
        }
    }
}
