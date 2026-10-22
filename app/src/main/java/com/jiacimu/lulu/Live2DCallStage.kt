package com.jiacimu.lulu

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** An isolated visual stage; no JavaScriptInterface, cookies, external resources or model API calls. */
@Composable
internal fun Live2DCallStage(characterId: String, modelUrl: String, speaking: Boolean,
    listening: Boolean, mouthLevel: Float, modifier: Modifier = Modifier,
    previewExpression: String? = null, previewMotion: Pair<String, Int>? = null,
    previewRevision: Int = 0,
    previewHead: Live2DVisualActionBus.Head? = null,
    onStatus: (phase: String, detail: String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val statusReceiver = rememberUpdatedState(onStatus)
    val stage = remember(context, characterId, modelUrl) {
        createLive2DWebView(context, characterId, modelUrl) { phase, detail ->
            statusReceiver.value(phase, detail)
        }
    }
    DisposableEffect(stage) {
        onDispose {
            runCatching { stage.stopLoading() }
            runCatching { stage.loadUrl("about:blank") }
            runCatching { stage.destroy() }
        }
    }
    // AndroidView can be composed before Android has measured or attached this
    // WebView. Start loading only once it enters the actual view hierarchy,
    // then report the real pixel dimensions after onLayout.
    AndroidView(
        factory = {
            stage.loadUrl("https://appassets.androidplatform.net/assets/live2d/stage.html")
            stage
        },
        modifier = modifier.fillMaxSize(),
        update = { view -> reportNativeLive2DViewport(view) },
    )
    LaunchedEffect(stage, previewRevision) {
        if (previewRevision > 0) {
            val commands = mutableListOf<String>()
            previewExpression?.let {
                commands += "window.LuluLive2D && window.LuluLive2D.expression(${JSONObject.quote(it)})"
            }
            previewMotion?.let {
                commands += "window.LuluLive2D && window.LuluLive2D.motion(${JSONObject.quote(it.first)}, ${it.second})"
            }
            previewHead?.let { head ->
                val values = JSONObject()
                head.yaw?.let { values.put("yaw", it.toDouble()) }
                head.pitch?.let { values.put("pitch", it.toDouble()) }
                head.roll?.let { values.put("roll", it.toDouble()) }
                commands += "window.LuluLive2D && window.LuluLive2D.head($values)"
            }
            commands.forEach { stage.evaluateJavascript(it, null) }
        }
    }
    SideEffect {
        val state = JSONObject().put("speaking", speaking).put("listening", listening)
            .put("mouth", if (speaking) mouthLevel.coerceIn(0f, 1f).toDouble() else 0.0)
        stage.evaluateJavascript(
            "window.__luluStageState = ${state}; window.LuluLive2D && window.LuluLive2D.update(window.__luluStageState)", null)
    }
}

// Frequent Compose audio-level updates must not re-run canvas layout JS.
// Keep only weak references so disposed video WebViews are not retained.
private val reportedLive2DSizes = java.util.WeakHashMap<WebView, Pair<Int, Int>>()

private fun reportNativeLive2DViewport(view: WebView) {
    if (view.width < 20 || view.height < 20) return
    val size = view.width to view.height
    if (reportedLive2DSizes[view] == size) return
    reportedLive2DSizes[view] = size
    runCatching {
        view.evaluateJavascript(
            "window.__luluNativeViewport={width:${size.first},height:${size.second}};" +
                "if(window.LuluLive2D)window.LuluLive2D.setViewport(${size.first},${size.second})",
            null)
    }
}

private fun createLive2DWebView(context: Context, characterId: String, modelUrl: String,
    onStatus: (String, String) -> Unit): WebView {
    val loader = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/private/", WebViewAssetLoader.InternalStoragePathHandler(context, Live2DAssets.base(context)))
        .build()
    return WebView(context).apply {
        // A fully opaque video window prevents chat content bleeding through a
        // transparent hardware WebView surface when Android redraws its layer.
        setBackgroundColor(AndroidColor.rgb(21, 23, 25))
        addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width >= 20 && height >= 20 &&
                (width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                // Dispatch only after WebView has actually committed its layout.
                view.post { reportNativeLive2DViewport(view as WebView) }
            }
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadsImagesAutomatically = true
            // Requests with the virtual HTTPS origin must reach WebViewAssetLoader.
            // All non-local requests are denied in shouldInterceptRequest below.
            blockNetworkLoads = false
            setSupportMultipleWindows(false)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                val source = message?.message().orEmpty()
                val marker = "__LULU_LIVE2D__"
                if (source.startsWith(marker)) {
                    runCatching {
                        val event = JSONObject(source.substring(marker.length))
                        onStatus(event.optString("type", "loading"),
                            event.optString("detail").take(220))
                    }
                    return true
                }
                if (message?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    // Avoid exposing local file paths or application internals to the call UI.
                    onStatus("error", "模型渲染脚本发生错误；请检查模型或 SDK 兼容性")
                    return true
                }
                return super.onConsoleMessage(message)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Very large atlas textures can kill Android's WebView GPU process.
                // Contain the crash and let Compose retain the static preview/call.
                onStatus("fatal", if (detail?.didCrash() == true)
                    "Live2D 图形进程崩溃，可能与8192贴图或手机显存有关；已退回静态画面"
                    else "Live2D 图形进程被系统回收；已退回静态画面")
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || uri.host != "appassets.androidplatform.net"
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") {
                    // AIRI discovers model expressions missing from FileReferences. Expose
                    // the amended manifest at exactly the current model URL, never on disk.
                    if (android.net.Uri.decode(uri.toString()) ==
                        android.net.Uri.decode(modelUrl)) {
                        Live2DAssets.rendererManifest(context, characterId)?.let { json ->
                            return WebResourceResponse("application/json", "utf-8",
                                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
                        }
                    }
                    return loader.shouldInterceptRequest(uri)
                        ?: WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?) {
                if (request?.url?.host == "appassets.androidplatform.net" && request.isForMainFrame) {
                    onStatus("error", "无法加载 Live2D 本地舞台")
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "https://appassets.androidplatform.net/assets/live2d/stage.html") {
                    view?.let { current ->
                        val width = current.width
                        val height = current.height
                        current.evaluateJavascript(
                            "window.__luluStageModelUrl = ${JSONObject.quote(modelUrl)};" +
                                "window.__luluNativeViewport={width:$width,height:$height};" +
                                "if(window.LuluLive2D){" +
                                "window.LuluLive2D.setViewport($width,$height);" +
                                "window.LuluLive2D.load(window.__luluStageModelUrl)}", null)
                    }
                }
            }
        }
    }
}
