package com.jiacimu.lulu

import android.content.Context
import android.graphics.Color as AndroidColor
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
    previewRevision: Int = 0) {
    val context = LocalContext.current
    val stage = remember(context, characterId, modelUrl) { createLive2DWebView(context, modelUrl) }
    DisposableEffect(stage) {
        onDispose {
            stage.stopLoading()
            stage.loadUrl("about:blank")
            stage.destroy()
        }
    }
    AndroidView(factory = { stage }, modifier = modifier.fillMaxSize())
    LaunchedEffect(stage, previewRevision) {
        if (previewRevision > 0) {
            val script = when {
                previewExpression != null ->
                    "window.LuluLive2D && window.LuluLive2D.expression(${JSONObject.quote(previewExpression)})"
                previewMotion != null ->
                    "window.LuluLive2D && window.LuluLive2D.motion(${JSONObject.quote(previewMotion.first)}, ${previewMotion.second})"
                else -> null
            }
            if (script != null) stage.evaluateJavascript(script, null)
        }
    }
    SideEffect {
        val state = JSONObject().put("speaking", speaking).put("listening", listening)
            .put("mouth", if (speaking) mouthLevel.coerceIn(0f, 1f).toDouble() else 0.0)
        stage.evaluateJavascript(
            "window.__luluStageState = ${state}; window.LuluLive2D && window.LuluLive2D.update(window.__luluStageState)", null)
    }
}

private fun createLive2DWebView(context: Context, modelUrl: String): WebView {
    val loader = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/private/", WebViewAssetLoader.InternalStoragePathHandler(context, Live2DAssets.base(context)))
        .build()
    return WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadsImagesAutomatically = true
            blockNetworkLoads = true
            setSupportMultipleWindows(false)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") {
                    return loader.shouldInterceptRequest(uri)
                        ?: WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "https://appassets.androidplatform.net/assets/live2d/stage.html") {
                    view?.evaluateJavascript(
                        "window.__luluStageModelUrl = ${JSONObject.quote(modelUrl)};" +
                            "if (window.LuluLive2D) window.LuluLive2D.load(window.__luluStageModelUrl)", null)
                }
            }
        }
        loadUrl("https://appassets.androidplatform.net/assets/live2d/stage.html")
    }
}
