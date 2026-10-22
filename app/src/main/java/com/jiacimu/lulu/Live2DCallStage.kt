package com.jiacimu.lulu

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.BitmapFactory
import android.util.Base64
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** An isolated visual stage; no JavaScriptInterface, cookies, external resources or model API calls. */
@Composable
internal fun Live2DCallStage(characterId: String, modelUrl: String, speaking: Boolean,
    listening: Boolean, mouthLevel: Float, modifier: Modifier = Modifier,
    previewExpression: String? = null, previewMotion: Pair<String, Int>? = null,
    previewRevision: Int = 0,
    previewHead: Live2DVisualActionBus.Head? = null,
    // Render frames in Compose, outside the WebView surface, when Android's
    // GPU compositor displays an empty WebView in spite of positive readback.
    nativeMirrorEnabled: Boolean = false,
    onWebViewReady: ((WebView) -> Unit)? = null,
    onStatus: (phase: String, detail: String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val statusReceiver = rememberUpdatedState(onStatus)
    val webViewReceiver = rememberUpdatedState(onWebViewReady)
    val stage = remember(context, characterId, modelUrl) {
        createLive2DWebView(context, characterId, modelUrl) { phase, detail ->
            statusReceiver.value(phase, detail)
        }
    }
    LaunchedEffect(stage) { webViewReceiver.value?.invoke(stage) }
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
    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                stage.loadUrl("https://appassets.androidplatform.net/assets/live2d/stage.html")
                stage
            },
            modifier = Modifier.fillMaxSize(),
            update = { view -> reportNativeLive2DViewport(view) },
        )
        if (nativeMirrorEnabled) {
            Live2DNativeFrameOverlay(stage) { phase, detail ->
                statusReceiver.value(phase, detail)
            }
        }
    }
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

/**
 * Bypasses the WebView's compositor altogether. Android receives compressed
 * frames from the *local* Pixi drawing buffer over evaluateJavascript and
 * draws them as a native Compose image on top of the WebView. This is a
 * compatibility path: capped at ~6 fps / 480 px, never sent over the network.
 */
@Composable
private fun Live2DNativeFrameOverlay(
    webView: WebView,
    onStatus: (String, String) -> Unit,
) {
    var picture by remember(webView) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    val receiver = rememberUpdatedState(onStatus)
    LaunchedEffect(webView) {
        var reportedFirstFrame = false
        var consecutiveErrors = 0
        while (isActive) {
            val encoded = try {
                suspendCancellableCoroutine<String?> { continuation ->
                    webView.evaluateJavascript(
                        "window.LuluLive2D && window.LuluLive2D.captureNativeFrame" +
                            " ? window.LuluLive2D.captureNativeFrame() : null",
                    ) { result ->
                        val decoded = runCatching {
                            org.json.JSONTokener(result).nextValue() as? String
                        }.getOrNull()
                        if (continuation.isActive) continuation.resume(decoded)
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (encoded != null && encoded.startsWith("data:image/jpeg;base64,") &&
                encoded.length < 2_000_000) {
                try {
                    val bytes = Base64.decode(encoded.substringAfter(','), Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) {
                        picture = decoded.asImageBitmap()
                        consecutiveErrors = 0
                        if (!reportedFirstFrame) {
                            reportedFirstFrame = true
                            receiver.value("diagnostic",
                                "原生 Compose 画面桥接已取得首帧：${decoded.width}×${decoded.height}")
                        }
                    } else consecutiveErrors++
                } catch (_: Exception) {
                    consecutiveErrors++
                }
            }
            if (consecutiveErrors == 3) {
                receiver.value("error", "原生画面桥接解码失败，请复制诊断日志")
            }
            delay(160)
        }
    }
    picture?.let { current ->
        Image(
            bitmap = current,
            contentDescription = "Live2D Android 原生帧显示",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
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
                view.post {
                    reportNativeLive2DViewport(view as WebView)
                    onStatus("step", "Android WebView 布局：${width}×${height}")
                }
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
                    // Keep the actual JS error category, but not the full local
                    // resource URL or model data.
                    onStatus("error", "Live2D JS: " +
                        source.lineSequence().firstOrNull().orEmpty().take(170))
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
                    val served = loader.shouldInterceptRequest(uri)
                    if (served == null) {
                        view?.post {
                            onStatus("resource", "未找到本地资源：" +
                                uri.lastPathSegment.orEmpty().take(80))
                        }
                        return WebResourceResponse("text/plain", "utf-8", 404,
                            "Not Found", emptyMap(),
                            ByteArrayInputStream(ByteArray(0)))
                    }
                    return served
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedHttpError(view: WebView?,
                request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.url.host == "appassets.androidplatform.net" &&
                    errorResponse.statusCode >= 400) {
                    onStatus("resource", "HTTP ${errorResponse.statusCode}：" +
                        request.url.lastPathSegment.orEmpty().take(80))
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?) {
                if (request?.url?.host == "appassets.androidplatform.net" && request.isForMainFrame) {
                    onStatus("error", "无法加载 Live2D 本地舞台")
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "https://appassets.androidplatform.net/assets/live2d/stage.html") {
                    onStatus("step", "Live2D 页面 HTML 加载完成；WebView " +
                        "${view?.width ?: 0}×${view?.height ?: 0}；引擎 " +
                        (WebView.getCurrentWebViewPackage()?.versionName ?: "未知"))
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
