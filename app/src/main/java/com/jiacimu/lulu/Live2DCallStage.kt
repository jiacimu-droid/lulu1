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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// A frame is acknowledged only after Android has decoded it, preventing
// WebView and Compose from accumulating latency or multiple large JPEG buffers.
private data class Live2DBinaryFrame(val jpeg: ByteArray, val reply: JavaScriptReplyProxy)

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
    framingMode: String = "full",
    onWebViewReady: ((WebView) -> Unit)? = null,
    onStatus: (phase: String, detail: String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val statusReceiver = rememberUpdatedState(onStatus)
    val webViewReceiver = rememberUpdatedState(onWebViewReady)
    val binaryFrames = remember(context, characterId, modelUrl) {
        Channel<Live2DBinaryFrame>(Channel.CONFLATED)
    }
    val binarySupported = remember(context, characterId, modelUrl) {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
    }
    val rendererReady = remember(context, characterId, modelUrl) { mutableStateOf(false) }
    val stage = remember(context, characterId, modelUrl) {
        createLive2DWebView(
            context, characterId, modelUrl, framingMode, nativeMirrorEnabled,
            onFrame = { jpeg, reply ->
                if (binaryFrames.trySend(Live2DBinaryFrame(jpeg, reply)).isFailure) {
                    runCatching { reply.postMessage("next:900") }
                }
            },
        ) { phase, detail ->
            if (phase == "ready") rendererReady.value = true
            else if (phase == "fatal") rendererReady.value = false
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
            Live2DNativeFrameOverlay(
                stage, binaryFrames, binarySupported, rendererReady.value,
            ) { phase, detail ->
                statusReceiver.value(phase, detail)
            }
        }
    }
    LaunchedEffect(stage, nativeMirrorEnabled) {
        stage.evaluateJavascript(
            "window.__luluNativeStreamEnabled=$nativeMirrorEnabled;" +
                "window.LuluLive2D && window.LuluLive2D.setNativeStreaming($nativeMirrorEnabled)",
            null,
        )
    }
    LaunchedEffect(stage, framingMode) {
        val mode = if (framingMode == "portrait") "portrait" else "full"
        stage.evaluateJavascript(
            "window.__luluFramingMode=${JSONObject.quote(mode)};" +
                "window.LuluLive2D && window.LuluLive2D.setFramingMode(window.__luluFramingMode)",
            null,
        )
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
 * compatibility path: adaptive 520-940px at roughly 8-12fps, never sent over the network.
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
        var slowFrames = 0
        var fastFrames = 0
        var maxEdge = 860
        while (isActive) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val rawResult = try {
                suspendCancellableCoroutine<String?> { continuation ->
                    webView.evaluateJavascript(
                        "window.LuluLive2D && window.LuluLive2D.captureNativeFrame" +
                            " ? window.LuluLive2D.captureNativeFrame($maxEdge) : null",
                    ) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            } catch (_: Exception) {
                null
            }
            // JSON parsing, base64 decoding and JPEG bitmap decoding must not
            // compete with audio controls or the Compose UI on the main thread.
            val decoded = withContext(Dispatchers.Default) {
                runCatching {
                    val encoded = rawResult?.let {
                        org.json.JSONTokener(it).nextValue() as? String
                    }
                    if (encoded == null || !encoded.startsWith("data:image/jpeg;base64,") ||
                        encoded.length > 3_500_000) null
                    else {
                        val bytes = Base64.decode(encoded.substringAfter(','), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }
            if (decoded != null) {
                picture = decoded.asImageBitmap()
                consecutiveErrors = 0
                if (!reportedFirstFrame) {
                    reportedFirstFrame = true
                    receiver.value("diagnostic",
                        "原生 Compose 画面桥接已取得首帧：${decoded.width}×${decoded.height}")
                }
            } else if (rawResult != null && rawResult != "null" && rawResult != "undefined") {
                consecutiveErrors++
                if (consecutiveErrors == 3) {
                    receiver.value("error", "原生画面桥接连续解码失败；已保留最后有效帧")
                }
            }
            val costMs = android.os.SystemClock.elapsedRealtime() - startedAt
            if (decoded != null) {
                // Keep the quality budget responsive to low-end devices and long
                // calls rather than forcing fixed 960px JPEGs onto every frame.
                if (costMs > 145) {
                    slowFrames++
                    fastFrames = 0
                } else if (costMs < 65) {
                    fastFrames++
                    slowFrames = 0
                } else {
                    fastFrames = 0
                    slowFrames = 0
                }
                if (slowFrames >= 3) {
                    maxEdge = when (maxEdge) {
                        in 801..960 -> 720
                        in 681..800 -> 600
                        else -> 520
                    }
                    slowFrames = 0
                } else if (fastFrames >= 18) {
                    maxEdge = when (maxEdge) {
                        in 0..599 -> 680
                        in 600..799 -> 820
                        else -> 940
                    }
                    fastFrames = 0
                }
            }
            delay((95L - costMs).coerceAtLeast(15L))
        }
    }
    val current = picture
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = "Live2D Android 原生帧显示",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    } else {
        // A native blue test surface distinguishes a genuinely missing
        // Android overlay from an unreadable WebGL frame. This layer is only
        // used during explicit fallback, never in ordinary voice calls.
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color(0xFF15354C)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Android 原生画面通道已启动\n正在读取 Live2D 图像帧…",
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        }
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
    initialFramingMode: String,
    initialNativeMirrorEnabled: Boolean,
    onFrame: (ByteArray, JavaScriptReplyProxy) -> Unit,
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
        // Strictly same-origin and main-frame only. Unlike JavaScriptInterface,
        // AndroidX WebMessage supports binary ArrayBuffer without Base64.
        // Must register BEFORE loading the HTML page.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            runCatching {
                WebViewCompat.addWebMessageListener(
                    this, "LuluNativeFrames", setOf("https://appassets.androidplatform.net"),
                ) { _, message, sourceOrigin, isMainFrame, reply ->
                    if (isMainFrame && sourceOrigin.scheme == "https" &&
                        sourceOrigin.host == "appassets.androidplatform.net" &&
                        message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                        val jpeg = message.arrayBuffer
                        if (jpeg.size in 256..3_500_000 &&
                            (jpeg[0].toInt() and 0xff) == 0xff &&
                            (jpeg[1].toInt() and 0xff) == 0xd8) {
                            onFrame(jpeg, reply)
                        } else {
                            runCatching { reply.postMessage("next:900") }
                        }
                    }
                }
                onStatus("diagnostic", "Android WebMessage 二进制图像桥接已注册")
            }.onFailure {
                onStatus("diagnostic", "二进制图像桥接不可用，将自动使用兼容通道")
            }
        }
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
                                "window.__luluFramingMode=${JSONObject.quote(initialFramingMode)};" +
                                "window.__luluNativeStreamEnabled=$initialNativeMirrorEnabled;" +
                                "window.__luluNativeViewport={width:$width,height:$height};" +
                                "if(window.LuluLive2D){" +
                                "window.LuluLive2D.setViewport($width,$height);" +
                                "window.LuluLive2D.setFramingMode(window.__luluFramingMode);" +
                                "window.LuluLive2D.setNativeStreaming(window.__luluNativeStreamEnabled);" +
                                "window.LuluLive2D.load(window.__luluStageModelUrl)}", null)
                    }
                }
            }
        }
    }
}
