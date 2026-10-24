package com.jiacimu.lulu

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidPixelColor
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.jiacimu.lulu.ai.VisionModelService
import java.io.File
import org.json.JSONObject

/**
 * Sample the ACTUAL Android window composition after the JPEG overlay is
 * removed. Offscreen WebGL pixels alone cannot prove the image is visible on
 * devices where the WebView compositor draws black. Unknown is a failure:
 * the proven JPEG bridge remains available.
 */
private suspend fun probePresentedWebGL(context: Context, webView: WebView?): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        webView == null || !webView.isAttachedToWindow ||
        webView.width < 64 || webView.height < 64) return null
    var current: Context? = context
    for (attempt in 0 until 12) {
        if (current is Activity) break
        current = (current as? ContextWrapper)?.baseContext
    }
    val window = (current as? Activity)?.window ?: return null
    val location = IntArray(2)
    webView.getLocationInWindow(location)
    val right = (location[0] + webView.width).coerceAtMost(window.decorView.width)
    val bottom = (location[1] + webView.height).coerceAtMost(window.decorView.height)
    if (location[0] < 0 || location[1] < 0 ||
        right - location[0] < 64 || bottom - location[1] < 64) return null
    val rect = Rect(location[0], location[1], right, bottom)
    val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window, rect, image, { result ->
                var colored: Int? = null
                if (result == PixelCopy.SUCCESS) {
                    var nonBackground = 0
                    // Middle of the character, avoiding the video toolbar.
                    for (y in 25 until 66) for (x in 21 until 75) {
                        val pixel = image.getPixel(x, y)
                        val red = AndroidPixelColor.red(pixel)
                        val green = AndroidPixelColor.green(pixel)
                        val blue = AndroidPixelColor.blue(pixel)
                        if (maxOf(red, green, blue) > 90 ||
                            maxOf(red, green, blue) - minOf(red, green, blue) > 28)
                            nonBackground++
                    }
                    colored = nonBackground
                }
                image.recycle()
                if (continuation.isActive) continuation.resume(colored)
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            image.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

/**
 * True portrait full-screen virtual video call; existing audio-only UI remains untouched.
 * A creator portrait stays visible while WebGL is loading or unavailable.
 */
@Composable
internal fun Live2DFullscreenVideoCall(
    characterId: String,
    characterName: String,
    state: LuluVoiceCallState,
    modelUrl: String?,
    creatorPreview: File?,
    coreReady: Boolean,
    avatarUri: String?,
    mouthLevel: Float,
    previewExpression: String?,
    previewMotion: Pair<String, Int>?,
    previewRevision: Int,
    manualHead: Live2DVisualActionBus.Head?,
    availableAnimations: Live2DModelControls,
    onPreviewExpression: (String) -> Unit,
    onPreviewMotion: (Pair<String, Int>) -> Unit,
    onPreviewHead: (Live2DVisualActionBus.Head) -> Unit,
    setupMessage: String,
    onSwitchToVoice: () -> Unit,
    onMinimize: () -> Unit,
    onChangeModel: () -> Unit,
    onRetryCore: () -> Unit,
    onStartCall: () -> Unit,
) {
    val cues by Live2DVisualActionBus.cues.collectAsState()
    val avatarPresentations by AvatarController.states.collectAsState()
    val avatarPresentation = avatarPresentations[characterId]
    val cue = cues[characterId]?.takeIf { it.callId == state.callExperienceId }
    DisposableEffect(characterId, state.callExperienceId) {
        onDispose { Live2DVisualActionBus.stageClosed(characterId, state.callExperienceId) }
    }
    // Familiar face-first framing for calls; the user can pull back to a full
    // body without reloading the model or interrupting the call audio.
    var framingMode by remember(modelUrl) { mutableStateOf("portrait") }
    var videoMenuExpanded by remember(modelUrl) { mutableStateOf(false) }
    var expressionPickerExpanded by remember(modelUrl) { mutableStateOf(false) }
    // These only tune the JPEG bridge's resolution budget; they do not
    // change model assets or interrupt the call/audio session.
    var qualityMode by remember(modelUrl) { mutableStateOf("smooth") }
    var interactionMode by remember(modelUrl) { mutableStateOf("hand") }
    var interactionMenuOpen by remember(modelUrl) { mutableStateOf(false) }
    var interactionForce by remember(modelUrl) { mutableFloatStateOf(.55f) }
    var forceMenuOpen by remember(modelUrl) { mutableStateOf(false) }
    var touchCalibrationMode by remember(modelUrl) { mutableStateOf(false) }
    var touchRegionMenuOpen by remember(modelUrl) { mutableStateOf(false) }
    var touchRegion by remember(modelUrl) { mutableStateOf("cheek-left") }
    val touchCalibrationContext = LocalContext.current
    val touchCalibrationPrefs = remember(touchCalibrationContext) {
        touchCalibrationContext.getSharedPreferences("lulu_live2d_touch_zones_v1",
            android.content.Context.MODE_PRIVATE)
    }
    var touchRadius by remember(modelUrl, touchRegion) {
        val savedRadius = runCatching {
            JSONObject(touchCalibrationPrefs.getString(modelUrl, "{}") ?: "{}")
                .optJSONObject(touchRegion)?.optDouble("rx")?.toFloat()
        }.getOrNull()?.takeIf { it.isFinite() && it >= Live2DTouchZones.minimumRadius }
        mutableFloatStateOf(savedRadius ?: Live2DTouchZones.defaultRadius(touchRegion))
    }
    var touchSizeRevision by remember(modelUrl) { mutableIntStateOf(0) }
    var touchCalibrationReset by remember(modelUrl) { mutableIntStateOf(0) }
    val cameraContext = LocalContext.current
    val cameraVisionScope = rememberCoroutineScope()
    var cameraShared by remember(characterId, state.callExperienceId) { mutableStateOf(false) }
    var cameraCaptureRequest by remember(characterId, state.callExperienceId) { mutableIntStateOf(0) }
    var cameraAnalyzing by remember(characterId, state.callExperienceId) { mutableStateOf(false) }
    var cameraShareStatus by remember(characterId, state.callExperienceId) { mutableStateOf("") }
    var cameraVisionJob by remember(characterId, state.callExperienceId) { mutableStateOf<Job?>(null) }
    DisposableEffect(characterId, state.callExperienceId) {
        onDispose {
            cameraVisionJob?.cancel()
            Live2DCallVisionState.disable(characterId, state.callExperienceId)
        }
    }
    var selfCameraEnabled by remember { mutableStateOf(false) }
    var cameraFailure by remember { mutableStateOf("") }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        selfCameraEnabled = granted
        if (!granted) cameraFailure = "未授予相机权限，前置摄像头保持关闭"
    }
    fun toggleSelfCamera() {
        if (selfCameraEnabled) {
            cameraShared = false
            cameraVisionJob?.cancel()
            Live2DCallVisionState.disable(characterId, state.callExperienceId)
            selfCameraEnabled = false
            cameraFailure = ""
        } else if (ContextCompat.checkSelfPermission(cameraContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            cameraFailure = ""
            selfCameraEnabled = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    // Sharing starts with one immediate photo, then refreshes at most every
    // 45 seconds. Each real vision request is billable; the default is OFF.
    // Unmounting the video call cancels this loop and revokes observations.
    LaunchedEffect(cameraShared, selfCameraEnabled, state.connected,
        state.callExperienceId) {
        if (cameraShared && selfCameraEnabled && state.connected &&
            state.callExperienceId.isNotBlank()) {
            Live2DCallVisionState.enable(characterId, state.callExperienceId)
            while (true) {
                if (!cameraAnalyzing) cameraCaptureRequest++
                delay(45_000L)
            }
        } else {
            Live2DCallVisionState.disable(characterId, state.callExperienceId)
        }
    }
    var touchFeedback by remember(characterId, modelUrl) { mutableStateOf("") }
    LaunchedEffect(touchFeedback) {
        if (touchFeedback.isNotBlank()) {
            delay(1_250L)
            touchFeedback = ""
        }
    }
    var renderRetry by remember(modelUrl) { mutableIntStateOf(0) }
    var expressionStage by remember(modelUrl, renderRetry) {
        mutableStateOf<android.webkit.WebView?>(null)
    }
    var nativeFrameReady by remember(modelUrl, renderRetry) { mutableStateOf(false) }
    // Record only a genuinely visible Android frame during the connected call.
    // Rendering may finish BEFORE dialing; the connected-state key handles it.
    LaunchedEffect(nativeFrameReady, state.connected, state.callExperienceId, characterId) {
        if (nativeFrameReady && state.connected && state.callExperienceId.isNotBlank()) {
            com.jiacimu.lulu.data.InteractionSignalBridge.recordVideoSelected(
                characterId, state.callExperienceId)
            com.jiacimu.lulu.data.InteractionSignalBridge.recordVideoRendered(
                characterId, state.callExperienceId)
        }
    }
    var stageStatus by remember(modelUrl) { mutableStateOf("loading") }
    var stageDetail by remember(modelUrl) { mutableStateOf("正在加载模型…") }
    var videoPerformance by remember(modelUrl) { mutableStateOf("") }
    var rendererPerformance by remember(modelUrl) { mutableStateOf("") }
    // WebView direct output is opt-in until it has proved visible on this
    // particular device/model. A successful probe enables retry on later calls.
    val displayPrefs = remember(cameraContext) {
        cameraContext.getSharedPreferences("lulu_live2d_display_v1", Context.MODE_PRIVATE)
    }
    var directWebGL by remember(modelUrl, renderRetry) { mutableStateOf(false) }
    var directVerified by remember(modelUrl, renderRetry) { mutableStateOf(false) }
    var autoDirectAttempted by remember(modelUrl, renderRetry) { mutableStateOf(false) }
    LaunchedEffect(modelUrl, nativeFrameReady, renderRetry) {
        if (modelUrl != null && nativeFrameReady && !autoDirectAttempted) {
            autoDirectAttempted = true
            if (displayPrefs.getBoolean("direct:$modelUrl", false)) directWebGL = true
        }
    }
    LaunchedEffect(directWebGL, nativeFrameReady, expressionStage, renderRetry) {
        if (!directWebGL || !nativeFrameReady || expressionStage == null) return@LaunchedEffect
        directVerified = false
        // Wait for Android to compose a new WebView frame after removing the
        // native overlay. A model-loaded JS signal alone is NOT enough.
        delay(700)
        val first = withTimeoutOrNull(2_000L) {
            probePresentedWebGL(cameraContext, expressionStage)
        }
        if (!directWebGL) return@LaunchedEffect
        if (first != null && first >= 100) {
            directVerified = true
            // The last decoded-JPEG FPS is now historical, not a direct
            // display metric; never show it as live direct-display speed.
            videoPerformance = "直显已验证"
            if (modelUrl != null) displayPrefs.edit()
                .putBoolean("direct:$modelUrl", true).apply()
        } else {
            // Fail closed: keep the existing video/audio session and restore
            // the proven JPEG/Bitmap fallback without reloading the model.
            directWebGL = false
            directVerified = false
            if (modelUrl != null) displayPrefs.edit()
                .remove("direct:$modelUrl").apply()
            videoPerformance = "直显未验证，已恢复兼容画面"
        }
    }
    var reportedAngles by remember(modelUrl) { mutableStateOf("") }
    // Video can load before the call is dialed. Once Android creates the real
    // call ID, attach the already-ready renderer to that session, not the empty ID.
    LaunchedEffect(stageStatus, state.callExperienceId, reportedAngles) {
        if (stageStatus == "ready" && state.callExperienceId.isNotBlank()) {
            Live2DVisualActionBus.stageReady(characterId, state.callExperienceId)
            Live2DVisualActionBus.stageParameters(characterId, state.callExperienceId, reportedAngles)
        }
    }
    // An oversized texture or broken JS bundle must not leave a silent gray canvas forever.
    // Late successful render may still recover and replace this static preview.
    LaunchedEffect(modelUrl, coreReady, renderRetry) {
        if (modelUrl != null && coreReady) {
            delay(25_000)
            if (stageStatus != "ready" && stageStatus != "error" &&
                stageStatus != "fatal") {
                stageStatus = "error"
                stageDetail = "动态模型加载超过25秒，请检查贴图大小或 Cubism 兼容性"
                Live2DVisualActionBus.stageClosed(characterId, state.callExperienceId)
            }
        }
    }
    val rendered = stageStatus == "ready"
    // Keep the SAME video chrome through WebGL startup. Wait for the
    // real Android frame, then dissolve the portrait into it.
    val previewOpacity by animateFloatAsState(
        targetValue = if (rendered && nativeFrameReady) 0f else 1f,
        label = "video-first-frame-dissolve",
    )
    val hasCreatorPreview = creatorPreview?.isFile == true
    Box(Modifier.fillMaxSize().background(Color(0xFF151719))) {
        // The stage can switch between waist-up and full-body framing without
        // destroying its native WebView or touching the existing audio session.
        // A terminated GPU renderer must be removed from the hierarchy before
        // creating another WebView. Never keep a dead native view on screen.
        if (modelUrl != null && coreReady && stageStatus != "fatal") {
            key(modelUrl, renderRetry) {
            Live2DCallStage(
                characterId = characterId,
                modelUrl = modelUrl,
                callId = state.callExperienceId,
                speaking = state.connected && (state.speaking || avatarPresentation?.speaking == true),
                listening = state.connected && state.listening,
                mouthLevel = mouthLevel,
                hasPcmSamples = avatarPresentation?.hasPcmSamples == true,
                previewExpression = cue?.expression ?: previewExpression,
                previewMotion = cue?.motion ?: previewMotion,
                previewHead = cue?.head ?: manualHead,
                previewBody = cue?.body,
                previewFace = cue?.face,
                previewBeats = cue?.beats.orEmpty(),
                previewRevision = if (cue != null) cue.sequence.toInt() else previewRevision,
                previewImmediate = cue?.immediate ?: true,
                // Both imported models and Hiyori were verified on the actual
                // device only through the native Compose presentation path.
                nativeMirrorEnabled = !directWebGL,
                framingMode = framingMode,
                qualityMode = qualityMode,
                interactionMode = interactionMode,
                interactionForce = interactionForce,
                calibrationMode = touchCalibrationMode,
                calibrationRegion = touchRegion,
                calibrationRadius = touchRadius,
                calibrationSizeRevision = touchSizeRevision,
                calibrationResetRevision = touchCalibrationReset,
                onWebViewReady = { expressionStage = it },
                onModelTouch = { region, gesture, force, durationMillis ->
                    // The JS renderer only reports real, model-hit touches.
                    // Do not record a digital-body interaction before Android
                    // has presented an actual frame in this connected call.
                    if (nativeFrameReady && state.connected &&
                        state.callExperienceId.isNotBlank()) {
                        com.jiacimu.lulu.data.InteractionSignalBridge.recordVideoRendered(
                            characterId, state.callExperienceId)
                        if (LuluVoiceCallSession.onLive2DTouch(characterId,
                                state.callExperienceId, region, gesture, force, durationMillis)) {
                            val label = Live2DTouchZones.label(region) ?: "身体"
                            touchFeedback = when (gesture) {
                                "kiss" -> "亲亲 · $label"
                                "slap" -> "扇了一下 · $label"
                                "slap-left" -> "向左扇了一下 · $label"
                                "slap-right" -> "向右扇了一下 · $label"
                                "stroke-left" -> "轻轻向左抚摸 · $label"
                                "stroke-right" -> "轻轻向右抚摸 · $label"
                                "stroke-up" -> "向上抚摸 · $label"
                                "stroke-down" -> "向下抚摸 · $label"
                                "stroke" -> "摸到了 · $label"
                                "hold" -> "碰到了 · $label"
                                else -> "戳到了 · $label"
                            }
                        }
                    }
                },
                onStatus = { phase, detail ->
                    // A dead WebView can still emit queued console messages after
                    // onRenderProcessGone. Never recreate it without user retry.
                    if (stageStatus == "fatal" && phase != "fatal") return@Live2DCallStage
                    if (phase == "performance") {
                        // GPU render cadence and Android delivered cadence are
                        // separate measurements, not one misleading FPS.
                        if (detail.startsWith("WebGL")) rendererPerformance = detail
                        else videoPerformance = detail
                    } else if (phase == "parameters") {
                        reportedAngles = detail
                        Live2DVisualActionBus.stageParameters(characterId,
                            state.callExperienceId, detail)
                    } else if (phase == "diagnostic" &&
                        detail.startsWith("原生 Compose 画面桥接已取得首帧")) {
                        // A JS 'ready' event validates offscreen pixels only.
                        // The actual Native Compose image exists at this point.
                        nativeFrameReady = true
                        stageStatus = "ready"
                        stageDetail = detail
                        Live2DVisualActionBus.stageReady(characterId, state.callExperienceId)
                    } else if (phase == "diagnostic" || phase == "step" || phase == "resource") {
                        // Diagnostics are not model readiness. In particular, a
                        // successful GPU capability probe must never hide the fallback.
                        stageDetail = detail
                    } else if (phase == "ready") {
                        // Do not remove the creator portrait while this device
                        // is still waiting for its FIRST native-frame callback.
                        if (!nativeFrameReady) {
                            stageStatus = "loading"
                            stageDetail = "模型已绘制，正在转为 Android 原生画面…"
                        }
                    } else if (nativeFrameReady && phase == "loading") {
                        // A delayed WebView log must not conceal a live native image.
                        stageDetail = detail
                    } else {
                        stageStatus = phase
                        stageDetail = detail
                        if (phase == "error" || phase == "fatal") {
                            nativeFrameReady = false
                            Live2DVisualActionBus.stageClosed(characterId, state.callExperienceId)
                        }
                    }
                },
            )
            }
        }
        // Static full-body preview is a model asset, never the separated texture atlas.
        // Hide it as soon as Live2D actually reports a successful model render.
        if (previewOpacity > .005f || modelUrl == null || !coreReady) {
            Box(Modifier.fillMaxSize().graphicsLayer(alpha = previewOpacity),
                contentAlignment = Alignment.Center) {
                Live2DCreatorPreview(
                    file = creatorPreview,
                    portraitFraming = framingMode == "portrait",
                    modifier = Modifier.fillMaxSize(),
                    fallback = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LuluProfileAvatar(imageUri = avatarUri, fallback = characterName.take(1),
                                size = 220)
                        }
                    },
                )
            }
        }
        // Opt-in on-device-only front-camera picture-in-picture. No frame ever leaves
        // this view or enters the model unless a separate vision-sharing flow is built.
        if (selfCameraEnabled) {
            Column(
                modifier = Modifier.align(Alignment.TopEnd)
                    .statusBarsPadding().padding(top = 82.dp, end = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(width = 112.dp, height = 154.dp)
                    .clip(RoundedCornerShape(17.dp)).background(Color(0xFF33363B))) {
                    Live2DSelfCameraPreview(
                        onFailure = {
                            cameraFailure = it
                            cameraShared = false
                            cameraVisionJob?.cancel()
                            Live2DCallVisionState.disable(characterId, state.callExperienceId)
                            selfCameraEnabled = false
                        },
                        captureRequest = cameraCaptureRequest.toLong(),
                        onFrameCaptured = { bitmap ->
                            if (!cameraShared || !state.connected ||
                                !Live2DCallVisionState.isEnabled(
                                    characterId, state.callExperienceId) ||
                                cameraAnalyzing) {
                                bitmap.recycle()
                            } else {
                                cameraAnalyzing = true
                                cameraShareStatus = "识图中…"
                                cameraVisionJob = cameraVisionScope.launch {
                                    try {
                                        val result = VisionModelService.describeCameraFrame(
                                            cameraContext, bitmap)
                                        if (result.isSuccess) {
                                            val accepted = Live2DCallVisionState.accept(
                                                characterId, state.callExperienceId,
                                                result.getOrThrow())
                                            if (accepted) cameraShareStatus =
                                                "已看见 · 下一轮回复可用"
                                        } else if (cameraShared) {
                                            cameraShareStatus = "看图失败：" +
                                                result.exceptionOrNull()?.message.orEmpty().take(85)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } finally {
                                        bitmap.recycle()
                                        cameraAnalyzing = false
                                    }
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(if (cameraShared) "已同意共享 · 每45秒取帧"
                    else "本机预览 · 未分享给AI",
                    color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = .5f))
                        .padding(horizontal = 5.dp, vertical = 3.dp))
                TextButton(
                    onClick = {
                        if (cameraShared) {
                            cameraShared = false
                            cameraVisionJob?.cancel()
                            Live2DCallVisionState.disable(
                                characterId, state.callExperienceId)
                            cameraShareStatus = "已停止共享"
                        } else if (!state.connected || state.callExperienceId.isBlank()) {
                            cameraShareStatus = "通话接通后才能分享"
                        } else if (!VisionModelService.canDescribeCamera(cameraContext)) {
                            cameraShareStatus = "请先在设置中配置支持图片的识图模型"
                        } else {
                            cameraShared = true
                            cameraShareStatus = "共享已开启，正在取第一帧"
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(if (cameraShared) "停止共享" else "共享给${characterName}",
                        color = if (cameraShared) Color(0xFFFFC5C5) else Color.White,
                        fontSize = 11.sp)
                }
                if (cameraShared) {
                    TextButton(
                        onClick = { if (!cameraAnalyzing) cameraCaptureRequest++ },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("现在看一眼", color = Color.White, fontSize = 10.sp) }
                }
                if (cameraShareStatus.isNotBlank()) {
                    Text(cameraShareStatus, color = Color.White,
                        fontSize = 10.sp, maxLines = 3,
                        modifier = Modifier.background(Color.Black.copy(alpha = .5f))
                            .padding(horizontal = 5.dp, vertical = 3.dp))
                }
            }
        }
        if (touchFeedback.isNotBlank()) {
            Text(touchFeedback,
                modifier = Modifier.align(Alignment.Center)
                    .offset(y = 98.dp)
                    .background(Color.Black.copy(alpha = .53f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                color = Color.White, fontSize = 13.sp)
        }
        if (expressionPickerExpanded) {
            AlertDialog(
                onDismissRequest = { expressionPickerExpanded = false },
                title = { Text("模型表情 · ${availableAnimations.expressions.size}") },
                text = {
                    Column(
                        Modifier.heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        availableAnimations.expressions.forEach { name ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    expressionPickerExpanded = false
                                    // An audition is an explicit immediate visual
                                    // action, not a new character-model decision.
                                    // It must not be hidden behind an older live2d
                                    // cue's previewRevision on this connected call.
                                    val stage = expressionStage
                                    if (nativeFrameReady && stage != null) {
                                        stage.evaluateJavascript(
                                            "window.LuluLive2D && window.LuluLive2D.expression(" +
                                                org.json.JSONObject.quote(name) + ", true)",
                                            null,
                                        )
                                    } else {
                                        onPreviewExpression(name)
                                    }
                                },
                            ) {
                                Text(name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { expressionPickerExpanded = false }) {
                        Text("关闭")
                    }
                },
            )
        }
        // A compact gradient over the avatar preserves the video-call feel.
        Box(Modifier.fillMaxWidth().height(162.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(295.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black.copy(alpha = .32f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "缩小通话", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(characterName, fontSize = 21.sp, color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                    Text(when (state.phase) {
                        CallPhase.Connected -> "%02d:%02d".format(state.elapsedSeconds / 60,
                            state.elapsedSeconds % 60)
                        CallPhase.Dialing -> "正在呼叫…"
                        CallPhase.Ready -> "准备拨打"
                        CallPhase.Ended -> "通话已结束"
                        else -> "视频通话"
                    }, fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { videoMenuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, "视频通话设置", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = videoMenuExpanded,
                        onDismissRequest = { videoMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (framingMode == "portrait") "✓ 半身近景" else "半身近景") },
                            onClick = {
                                framingMode = "portrait"
                                videoMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Outlined.Face, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (framingMode == "full") "✓ 全身镜头" else "全身镜头") },
                            onClick = {
                                framingMode = "full"
                                videoMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Outlined.Portrait, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (qualityMode == "quality")
                                "✓ 高清优先 · 点击切流畅" else "✓ 流畅优先 · 点击切高清") },
                            onClick = {
                                qualityMode = if (qualityMode == "quality") "smooth" else "quality"
                                videoMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Outlined.HighQuality, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (directVerified) "✓ WebGL 直显"
                                else if (directWebGL) "验证直显中…"
                                else "试用 WebGL 直显") },
                            enabled = nativeFrameReady,
                            onClick = {
                                directWebGL = !directWebGL
                                if (!directWebGL) {
                                    directVerified = false
                                    if (modelUrl != null) displayPrefs.edit()
                                        .remove("direct:$modelUrl").apply()
                                }
                                videoMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (selfCameraEnabled) "关闭我的摄像头" else "打开我的摄像头") },
                            onClick = {
                                videoMenuExpanded = false
                                toggleSelfCamera()
                            },
                            leadingIcon = { Icon(Icons.Outlined.CameraFront, null) },
                        )
                        if (availableAnimations.expressions.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("模型表情 · ${availableAnimations.expressions.size}") },
                                onClick = {
                                    videoMenuExpanded = false
                                    expressionPickerExpanded = true
                                },
                                leadingIcon = { Icon(Icons.Outlined.Face, null) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("更换 Live2D 模型") },
                            onClick = {
                                videoMenuExpanded = false
                                onChangeModel()
                            },
                            leadingIcon = { Icon(Icons.Outlined.Portrait, null) },
                        )
                        if (rendererPerformance.isNotBlank() || videoPerformance.isNotBlank()) {
                            HorizontalDivider()
                            if (rendererPerformance.isNotBlank()) {
                                Text(rendererPerformance, fontSize = 11.sp,
                                    color = Color.DarkGray,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                            }
                            if (videoPerformance.isNotBlank()) {
                                Text(videoPerformance, fontSize = 11.sp,
                                    color = Color.DarkGray,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Same clean call chrome before and after the first native frame.
            // Renderer progress belongs in the diagnostics screen, not over the
            // author's portrait on every call resume/re-entry.
            if (!rendered && (modelUrl == null || stageStatus == "error" ||
                    stageStatus == "fatal" || (!coreReady && setupMessage.contains("失败")))) {
                val message = when {
                    modelUrl == null -> "尚未导入 Live2D 模型，右上角可更换"
                    !coreReady -> "Live2D 渲染核心暂不可用"
                    else -> stageDetail.ifBlank { "动态画面加载失败" }
                }
                Text(message, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = .5f))
                        .padding(horizontal = 12.dp, vertical = 9.dp))
            }
            if (!rendered) {
                if ((stageStatus == "error" || stageStatus == "fatal") &&
                    modelUrl != null && coreReady) {
                    TextButton(onClick = {
                        Live2DVisualActionBus.stageClosed(characterId, state.callExperienceId)
                        stageStatus = "loading"
                        stageDetail = "正在重新创建 Live2D 图形画面…"
                        reportedAngles = ""
                        nativeFrameReady = false
                        renderRetry++
                    }) {
                        Text("重新载入动态人物", color = Color.White, fontSize = 12.sp)
                    }
                }
                if ((stageStatus == "error" || stageStatus == "fatal") && hasCreatorPreview) {
                    Text("静态形象仍可用，电话不中断。",
                        color = Color.White.copy(alpha = .75f), fontSize = 11.sp)
                }
                if (!coreReady && modelUrl != null && setupMessage.contains("失败")) {
                    TextButton(onClick = onRetryCore) {
                        Text("重试安装官方渲染核心", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (cameraFailure.isNotBlank()) {
                Text(cameraFailure, fontSize = 12.sp, color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
            }
            val liveWords = state.playingTranscript.ifBlank {
                state.partialTranscript.ifBlank { state.statusMessage }
            }
            if (liveWords.isNotBlank() && state.connected) {
                Text(liveWords.takeLast(180), fontSize = 15.sp,
                    lineHeight = 23.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            } else Spacer(Modifier.height(18.dp))
            if (modelUrl != null && state.connected) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black.copy(alpha = .48f))
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { interactionMenuOpen = true }) {
                            Text(
                                when (interactionMode) {
                                    "pat" -> "手掌 · 扇"
                                    "kiss" -> "嘴唇 · 亲吻"
                                    else -> "手掌 · 抚摸"
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = interactionMenuOpen,
                            onDismissRequest = { interactionMenuOpen = false },
                        ) {
                            listOf(
                                "hand" to "手掌 · 抚摸",
                                "pat" to "手掌 · 扇",
                                "kiss" to "嘴唇 · 亲吻",
                            ).forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(if (interactionMode == id) "✓ $label" else label) },
                                    onClick = {
                                        interactionMode = id
                                        interactionMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(modifier = Modifier.width(48.dp),
                            onClick = { forceMenuOpen = true }) {
                            Text(if (interactionForce < .4f) "轻" else
                                if (interactionForce > .75f) "重" else "中",
                                color = Color.White, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = forceMenuOpen,
                            onDismissRequest = { forceMenuOpen = false }) {
                            listOf(.25f to "轻", .55f to "中", .9f to "重").forEach { (force, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    interactionForce = force
                                    forceMenuOpen = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { touchCalibrationMode = !touchCalibrationMode }) {
                        Text(if (touchCalibrationMode) "完成校准" else "触摸校准",
                            color = Color.White, fontSize = 12.sp)
                    }
                }
                if (touchCalibrationMode) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(top = 5.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.Black.copy(alpha = .6f))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            TextButton(onClick = { touchRegionMenuOpen = true }) {
                                Text(Live2DTouchZones.label(touchRegion) ?: "脸颊",
                                    color = Color.White, fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = touchRegionMenuOpen,
                                onDismissRequest = { touchRegionMenuOpen = false },
                            ) {
                                Live2DTouchZones.labels.filterKeys {
                                    it !in setOf("head", "arm")
                                }.forEach { (id, label) ->
                                    DropdownMenuItem(text = {
                                        Text(if (touchRegion == id) "✓ $label" else label)
                                    }, onClick = {
                                        touchRegion = id
                                        touchRegionMenuOpen = false
                                    })
                                }
                            }
                        }
                        Slider(
                            value = touchRadius,
                            onValueChange = { touchRadius = it; touchSizeRevision++ },
                            valueRange = Live2DTouchZones.minimumRadius.. .30f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        TextButton(onClick = {
                            touchCalibrationReset++
                            touchRadius = Live2DTouchZones.defaultRadius(touchRegion)
                        }) {
                            Text("重置", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                VideoCallButton(Icons.Outlined.VideocamOff, "切为语音", onSwitchToVoice)
                if (state.connected) {
                    VideoCallButton(if (state.speakerEnabled) Icons.Outlined.VolumeUp
                        else Icons.Outlined.PhoneInTalk,
                        "声音输出", LuluVoiceCallSession::toggleSpeaker)
                    VideoCallButton(if (state.microphoneMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        if (state.microphoneMuted) "打开麦克风" else "关闭麦克风",
                        LuluVoiceCallSession::toggleMicrophone)
                    VideoCallButton(Icons.Outlined.CallEnd, "挂断", LuluVoiceCallSession::endCall, danger = true)
                } else if (state.phase == CallPhase.Ready) {
                    VideoCallButton(Icons.Outlined.Call, "拨打", onStartCall)
                } else {
                    VideoCallButton(Icons.Outlined.CallEnd, "取消", LuluVoiceCallSession::cancelDial,
                        danger = true)
                }
            }
            // Raise phone controls above the Android gesture bar so the
            // character remains visible and buttons are easier to reach.
            Spacer(Modifier.height(52.dp))
        }
    }
}

@Composable
private fun VideoCallButton(icon: ImageVector, label: String, onClick: () -> Unit,
    danger: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape)
            .background(if (danger) Color(0xFFD94352) else Color(0xFF272C33).copy(alpha = .88f))
            .border(1.dp, Color.White.copy(alpha = if (danger) .12f else .24f), CircleShape)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = .88f), fontSize = 11.sp)
    }
}
