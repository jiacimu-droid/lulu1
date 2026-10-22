package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.PixelCopy
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch

/**
 * Count genuinely visible pixels from the Android dialog WINDOW, rather than
 * just gl.readPixels or an offscreen DOM canvas. PixelCopy captures the actual
 * compositor output in the same rectangle the user is looking at.
 * Null means unavailable / capture failed, NOT proof that the screen is black.
 */
private suspend fun presentedLive2DColorPixels(window: Window?, view: WebView?): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        window == null || view == null || !view.isAttachedToWindow ||
        view.width < 64 || view.height < 64) return null
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val rectangle = Rect(location[0], location[1],
        location[0] + view.width, location[1] + view.height)
    if (rectangle.left < 0 || rectangle.top < 0) return null
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window, rectangle, bitmap, { result ->
                var pixels: Int? = null
                if (result == PixelCopy.SUCCESS) {
                    var found = 0
                    // Ignore the edges: they may include controls or a border.
                    // A dark, empty WebView background never satisfies this.
                    for (y in 14 until 82) for (x in 14 until 82) {
                        val value = bitmap.getPixel(x, y)
                        val r = AndroidColor.red(value)
                        val g = AndroidColor.green(value)
                        val b = AndroidColor.blue(value)
                        val highest = maxOf(r, g, b)
                        val lowest = minOf(r, g, b)
                        if (highest > 90 || highest - lowest > 26) found++
                    }
                    pixels = found
                }
                bitmap.recycle()
                if (continuation.isActive) continuation.resume(pixels)
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

/**
 * Independent renderer-only diagnostic. Neither LuluVoiceCallSession nor AI
 * nor TTS is initialized. Can compare AIRI Hiyori against user's saved role.
 */
@Composable
internal fun Live2DModelDiagnosticDialog(characterId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Live2DAssets.revisions.collectAsState()
    val savedRole = remember(characterId, revision) { Live2DAssets.modelUrl(context, characterId) }
    val sampleRole = remember(revision) {
        Live2DAssets.modelUrl(context, Live2DSampleModel.TEST_ROLE)
    }
    val coreReady = remember(revision) { Live2DAssets.hasCore(context) }
    var useSample by remember { mutableStateOf(false) }
    val roleId = if (useSample) Live2DSampleModel.TEST_ROLE else characterId
    val modelUrl = if (useSample) sampleRole else savedRole
    var installing by remember { mutableStateOf(false) }
    var session by remember(roleId, modelUrl) { mutableIntStateOf(0) }
    var stagePhase by remember(roleId, modelUrl, session) { mutableStateOf("idle") }
    var stageMessage by remember(roleId, modelUrl, session) { mutableStateOf("尚未开始动态渲染") }
    var lastStatusRecord by remember { mutableStateOf("") }
    var logsExpanded by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var manualHead by remember { mutableStateOf<Live2DVisualActionBus.Head?>(null) }
    var motionRevision by remember { mutableIntStateOf(0) }
    var mouthPreview by remember { mutableStateOf(false) }
    var diagnosticView by remember(roleId, modelUrl, session) { mutableStateOf<WebView?>(null) }
    var mirrorMode by remember(roleId, modelUrl, session) { mutableStateOf(false) }
    var nativeMirrorMode by remember(roleId, modelUrl, session) { mutableStateOf(false) }
    fun record(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        events = (events + "$timestamp · ${message.take(260)}").takeLast(50)
    }
    fun attemptReload() {
        session++
        manualHead = null
        mouthPreview = false
        record("重新创建独立 WebView 渲染器")
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Compose Dialog uses its OWN Android Window. Walk the parent chain,
        // rather than accidentally capturing the Activity behind the dialog.
        var dialogParent: android.view.View? = LocalView.current
        while (dialogParent != null && dialogParent !is DialogWindowProvider) {
            dialogParent = dialogParent.parent as? android.view.View
        }
        val dialogWindow = (dialogParent as? DialogWindowProvider)?.window
        LaunchedEffect(stagePhase, nativeMirrorMode, session) {
            if (stagePhase == "native_loading" && nativeMirrorMode) {
                delay(6_000)
                if (stagePhase == "native_loading") {
                    stagePhase = "error"
                    stageMessage = "Android 原生画面已启动，但未收到 Live2D 帧；" +
                        "如蓝色底图可见，说明 WebView 帧导出存在问题"
                    record("原生兼容通道等待6秒仍未收到模型帧")
                }
            }
        }
        LaunchedEffect(stagePhase, diagnosticView, dialogWindow, session) {
            if (diagnosticView == null) return@LaunchedEffect
            if (stagePhase == "native_verifying") {
                delay(600)
                val copied = presentedLive2DColorPixels(dialogWindow, diagnosticView)
                record("Android 原生 Compose 画面 PixelCopy：${copied ?: "无法采样"}")
                stagePhase = when {
                    copied == null -> "unverified"
                    copied >= 32 -> "ready"
                    else -> "error"
                }
                stageMessage = when (stagePhase) {
                    "ready" -> "原生 Compose 图片通道已通过 Android 屏幕采样（低帧率兼容模式）"
                    "unverified" -> "原生图像已解码，Android 窗口采样暂不可用"
                    else -> "原生图片也无法显示：需进一步排查安卓窗口层叠"
                }
                return@LaunchedEffect
            }
            if (stagePhase != "verifying") return@LaunchedEffect
            delay(450)
            val first = presentedLive2DColorPixels(dialogWindow, diagnosticView)
            record("Android 屏幕 PixelCopy（中部可见像素）：${first ?: "无法采样"}" +
                if (mirrorMode) " · 图片通道" else " · WebGL 直显")
            if (first != null && first >= 32) {
                stagePhase = "ready"
                stageMessage = if (mirrorMode) "手机画面可见（图片兼容通道约 5fps）"
                    else "Android 屏幕已确认显示人物"
            } else if (first == null) {
                stagePhase = "unverified"
                stageMessage = "手机合成画面采样不可用，不能认定黑屏已修复；可手动尝试图片通道"
            } else if (!mirrorMode) {
                record("Canvas 有内容但屏幕仍黑，自动切换到 HTML 图片兼容通道")
                diagnosticView?.evaluateJavascript(
                    "window.LuluLive2D && window.LuluLive2D.setPresentationMode(true)", null)
                mirrorMode = true
                delay(850)
                val second = presentedLive2DColorPixels(dialogWindow, diagnosticView)
                record("图片兼容通道 PixelCopy：${second ?: "无法采样"}")
                if (second != null && second >= 32) {
                    stagePhase = "ready"
                    stageMessage = "图片兼容通道可见（约 5fps，仅用于定位设备问题）"
                } else {
                    // Both failed (or PixelCopy is unavailable). The next
                    // experiment must BYPASS WebView entirely rather than
                    // draw another HTML element within the same black view.
                    record("WebView 直显/HTML 图片均未确认可见，启动原生 Compose 帧覆盖")
                    nativeMirrorMode = true
                    stagePhase = "native_loading"
                    stageMessage = "改为 Android Compose 原生图片输出，正在取得首帧"
                }
            } else {
                stagePhase = "error"
                stageMessage = "图片通道未显示到 Android 屏幕；请复制诊断日志"
            }
        }
        Surface(color = Color(0xFF121416), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live2D 动态检测", color = Color.White, fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                }
                Text("独立测试 · 不拨号 · 不调用角色 API · 不修改角色记忆",
                    color = Color.LightGray, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { useSample = false; attemptReload() },
                        enabled = savedRole != null,
                        modifier = Modifier.weight(1f)) {
                        Text("已导入模型", fontSize = 12.sp,
                            color = if (!useSample) Color.White else Color.LightGray)
                    }
                    OutlinedButton(
                        onClick = { useSample = true; attemptReload() },
                        modifier = Modifier.weight(1f)) {
                        Text("AIRI 示例 Hiyori", fontSize = 12.sp,
                            color = if (useSample) Color.White else Color.LightGray)
                    }
                }
                if (!coreReady) {
                    Text("① 缺少 Cubism Core。请先在视频页面安装官方 Core。",
                        color = Color(0xFFFFC28D), fontSize = 12.sp)
                }
                if (useSample && sampleRole == null) {
                    Text("示例模型来自 AIRI 使用的公开分发地址。首次测试才下载，不增加 APK 大小；仅用于本机诊断，请遵守示例模型授权。",
                        color = Color.LightGray, fontSize = 11.sp, lineHeight = 16.sp)
                    Button(enabled = !installing, onClick = {
                        installing = true
                        record("开始获取 AIRI Hiyori 示例 ZIP")
                        scope.launch {
                            try {
                                val message = Live2DSampleModel.install(context) { progress ->
                                    scope.launch { record(progress) }
                                }
                                record("示例导入成功：$message")
                                useSample = true
                            } catch (error: Exception) {
                                record("示例获取/导入失败：" + (error.message ?: "未知错误"))
                            } finally {
                                installing = false
                            }
                        }
                    }) { Text(if (installing) "正在下载安装示例…" else "自动下载安装 Hiyori 示例模型") }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF25282C)),
                    contentAlignment = Alignment.Center) {
                    if (modelUrl != null && coreReady) {
                        key(roleId, modelUrl, session) {
                            Live2DCallStage(
                                characterId = roleId,
                                modelUrl = modelUrl,
                                speaking = mouthPreview,
                                listening = false,
                                mouthLevel = if (mouthPreview) .9f else 0f,
                                previewHead = manualHead,
                                previewRevision = motionRevision,
                                nativeMirrorEnabled = nativeMirrorMode,
                                onWebViewReady = { view ->
                                    diagnosticView = view
                                    record("Native WebView 硬件加速=${view.isHardwareAccelerated}" +
                                        "；渲染引擎=" +
                                        (WebView.getCurrentWebViewPackage()?.versionName ?: "未知"))
                                },
                                onStatus = { phase, detail ->
                                    if (nativeMirrorMode && phase == "diagnostic" &&
                                        detail.startsWith("原生 Compose 画面桥接已取得首帧")) {
                                        stagePhase = "native_verifying"
                                        stageMessage = "已取得原生人物帧，验证是否合成到 Android 屏幕"
                                    } else if (phase == "ready") {
                                        // JavaScript readPixels and drawImage prove only that the
                                        // canvas has pixels. PixelCopy proves Android displays them.
                                        stagePhase = "verifying"
                                        stageMessage = "Canvas 有像素；正在检查手机屏幕实际画面"
                                    } else if (phase !in setOf(
                                            "diagnostic", "step", "resource", "parameters") &&
                                        stagePhase !in setOf("native_loading", "native_verifying")) {
                                        stagePhase = phase
                                        stageMessage = detail
                                    }
                                    val entry = "$phase · $detail"
                                    if (entry != lastStatusRecord) {
                                        record(entry)
                                        lastStatusRecord = entry
                                    }
                                },
                            )
                        }
                    } else {
                        Text(if (useSample) "请下载示例模型并确认 Core 已安装"
                            else "尚未导入角色 Live2D 模型",
                            color = Color.LightGray, fontSize = 13.sp)
                    }
                }
                Text("当前阶段：$stagePhase · $stageMessage", color = if (stagePhase == "error" ||
                    stagePhase == "fatal") Color(0xFFFFB09D) else Color.White,
                    fontSize = 12.sp, lineHeight = 17.sp)
                // Always visible even when the scrollable history is clipped by a
                // small phone or Android navigation bar. The last event is NOT
                // dependent on the LazyColumn scroll offset.
                Text("最新日志：${events.lastOrNull() ?: "暂无记录"}",
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF30343A))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    color = Color(0xFFFAE3AE), fontSize = 11.sp,
                    lineHeight = 15.sp, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextButton(onClick = ::attemptReload, enabled = modelUrl != null && coreReady) {
                        Text("重新测试", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        manualHead = Live2DVisualActionBus.Head(yaw = -18f)
                        motionRevision++
                        record("人工测试头部左转 ParamAngleX=-18")
                    }, enabled = stagePhase == "ready") { Text("左转", fontSize = 12.sp) }
                    TextButton(onClick = {
                        manualHead = Live2DVisualActionBus.Head(yaw = 18f)
                        motionRevision++
                        record("人工测试头部右转 ParamAngleX=18")
                    }, enabled = stagePhase == "ready") { Text("右转", fontSize = 12.sp) }
                    TextButton(onClick = {
                        mouthPreview = !mouthPreview
                        record("人工测试口型：" + if (mouthPreview) "张嘴" else "闭嘴")
                    }, enabled = stagePhase == "ready") {
                        Text(if (mouthPreview) "闭嘴" else "张嘴", fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        mirrorMode = !mirrorMode
                        diagnosticView?.evaluateJavascript(
                            "window.LuluLive2D && window.LuluLive2D.setPresentationMode(${mirrorMode})",
                            null)
                        stagePhase = "verifying"
                        stageMessage = "手动切换显示通道；重新采样手机屏幕"
                    }, enabled = diagnosticView != null) {
                        Text(if (mirrorMode) "改用 WebGL 直显" else "试试图片兼容通道", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        if (!nativeMirrorMode) {
                            record("手动启用原生 Compose 图片桥接")
                            nativeMirrorMode = true
                            stagePhase = "native_loading"
                            stageMessage = "等待原生 Compose 画面首帧"
                        } else {
                            record("手动停用原生 Compose 图片桥接")
                            nativeMirrorMode = false
                            stagePhase = "verifying"
                            stageMessage = "切回 WebView 并重新验证"
                        }
                    }, enabled = diagnosticView != null) {
                        Text(if (nativeMirrorMode) "关闭原生画面" else "试试原生画面",
                            fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("加载日志（最近50条）", color = Color.LightGray, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { logsExpanded = !logsExpanded }) {
                        Text(if (logsExpanded) "收起日志" else "展开日志", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("Live2D 诊断", (
                                "模型：" + if (useSample) "AIRI Hiyori" else "当前角色" ) +
                                "\n阶段：$stagePhase · $stageMessage\n" +
                                events.joinToString("\n")))
                    }) { Text("复制诊断", fontSize = 12.sp) }
                }
                val logListState = rememberLazyListState()
                LaunchedEffect(events.lastOrNull(), logsExpanded) {
                    if (events.isNotEmpty()) logListState.scrollToItem(events.lastIndex)
                }
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.fillMaxWidth().height(if (logsExpanded) 250.dp else 112.dp)
                        .background(Color(0xFF202326)).padding(8.dp),
                ) {
                    itemsIndexed(events) { _, entry ->
                        Text(entry, color = Color(0xFFE0E2E6), fontSize = 11.sp,
                            lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}
