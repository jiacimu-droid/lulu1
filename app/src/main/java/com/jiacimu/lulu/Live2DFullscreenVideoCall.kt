package com.jiacimu.lulu

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import java.io.File

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
    val cue = cues[characterId]?.takeIf { it.callId == state.callExperienceId }
    DisposableEffect(characterId, state.callExperienceId) {
        onDispose { Live2DVisualActionBus.stageClosed(characterId, state.callExperienceId) }
    }
    // Familiar face-first framing for calls; the user can pull back to a full
    // body without reloading the model or interrupting the call audio.
    var framingMode by remember(modelUrl) { mutableStateOf("portrait") }
    val cameraContext = LocalContext.current
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
    var renderRetry by remember(modelUrl) { mutableIntStateOf(0) }
    var nativeFrameReady by remember(modelUrl, renderRetry) { mutableStateOf(false) }
    var stageStatus by remember(modelUrl) { mutableStateOf("loading") }
    var stageDetail by remember(modelUrl) { mutableStateOf("正在加载模型…") }
    var videoPerformance by remember(modelUrl) { mutableStateOf("") }
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
    val hasCreatorPreview = creatorPreview?.isFile == true
    val previewSize = remember(creatorPreview?.absolutePath) {
        creatorPreviewDimensions(creatorPreview)
    }
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
                speaking = state.connected && state.speaking,
                listening = state.connected && state.listening,
                mouthLevel = mouthLevel,
                previewExpression = cue?.expression ?: previewExpression,
                previewMotion = cue?.motion ?: previewMotion,
                previewHead = cue?.head ?: manualHead,
                previewBody = cue?.body,
                previewFace = cue?.face,
                previewRevision = if (cue != null) cue.sequence.toInt() else previewRevision,
                // Both imported models and Hiyori were verified on the actual
                // device only through the native Compose presentation path.
                nativeMirrorEnabled = true,
                framingMode = framingMode,
                onStatus = { phase, detail ->
                    // A dead WebView can still emit queued console messages after
                    // onRenderProcessGone. Never recreate it without user retry.
                    if (stageStatus == "fatal" && phase != "fatal") return@Live2DCallStage
                    if (phase == "performance") {
                        // Diagnostic updates must NEVER overwrite stageStatus:
                        // the native frame is still valid and must remain visible.
                        videoPerformance = detail
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
        if (!rendered || modelUrl == null || !coreReady) {
            Box(Modifier.fillMaxSize().padding(top = 74.dp, bottom = 180.dp),
                contentAlignment = Alignment.Center) {
                Live2DCreatorPreview(
                    file = creatorPreview,
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
        // Deliberately keep this switch OUTSIDE the model renderer. It changes
        // its camera fit in-place, not the character, assets, or phone session.
        if (modelUrl != null && coreReady) {
            Row(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(start = 18.dp, top = 92.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(Color.Black.copy(alpha = .55f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("portrait" to "半身", "full" to "全身").forEach { (mode, label) ->
                    val selected = framingMode == mode
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(36.dp))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable { framingMode = mode }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 12.sp,
                            color = if (selected) Color(0xFF151719) else Color.White,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        // Small real-world diagnostic: measured native delivery, not claimed 30fps.
        // Keep it out of the call controls and let it vanish before samples exist.
        if (videoPerformance.isNotBlank() && rendered) {
            Text(videoPerformance, color = Color.White.copy(alpha = .74f),
                fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(start = 20.dp, top = 146.dp)
                    .background(Color.Black.copy(alpha = .35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp))
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
                    Live2DSelfCameraPreview(onFailure = {
                        cameraFailure = it
                        selfCameraEnabled = false
                    })
                }
                Spacer(Modifier.height(5.dp))
                Text("本机预览 · 未分享给 AI",
                    color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = .5f))
                        .padding(horizontal = 5.dp, vertical = 3.dp))
            }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                IconButton(onClick = ::toggleSelfCamera) {
                    Icon(if (selfCameraEnabled) Icons.Outlined.VideocamOff else Icons.Outlined.CameraFront,
                        if (selfCameraEnabled) "关闭我的画面" else "开启我的画面（仅本机预览）",
                        tint = Color.White)
                }
                IconButton(onClick = onChangeModel) {
                    Icon(Icons.Outlined.Portrait, "更换 Live2D 模型", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!rendered) {
                val message = when {
                    modelUrl == null -> "尚未导入 Live2D 身体，点击右上角导入模型"
                    !coreReady -> "正在准备 Live2D 官方渲染组件"
                    stageStatus == "error" || stageStatus == "fatal" ->
                        stageDetail.ifBlank { "Live2D 加载失败" }
                    else -> if (hasCreatorPreview) "完整形象预览 · 正在准备动态画面"
                            else "暂无模型预览图 · 正在加载动态画面"
                }
                Text(message, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = .53f))
                        .padding(horizontal = 12.dp, vertical = 9.dp))
                previewSize?.let { (width, height) ->
                    val direction = when {
                        height > width -> "竖向"
                        width > height -> "横向"
                        else -> "方形"
                    }
                    Text("作者预览：$direction $width × $height · 原比例",
                        color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                }
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
        }
    }
}

@Composable
private fun VideoCallButton(icon: ImageVector, label: String, onClick: () -> Unit,
    danger: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).clip(CircleShape)
            .background(if (danger) Color(0xFFE74B55) else Color.White.copy(alpha = .23f))
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = .92f), fontSize = 12.sp)
    }
}
