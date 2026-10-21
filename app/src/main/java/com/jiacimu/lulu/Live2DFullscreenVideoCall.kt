package com.jiacimu.lulu

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
    setupMessage: String,
    onSwitchToVoice: () -> Unit,
    onMinimize: () -> Unit,
    onChangeModel: () -> Unit,
    onRetryCore: () -> Unit,
    onStartCall: () -> Unit,
) {
    var stageStatus by remember(modelUrl) { mutableStateOf("loading") }
    var stageDetail by remember(modelUrl) { mutableStateOf("正在加载模型…") }
    val rendered = stageStatus == "ready"
    val hasCreatorPreview = creatorPreview?.isFile == true
    Box(Modifier.fillMaxSize().background(Color(0xFF151719))) {
        // Fill the entire portrait call screen. The complete model remains fitted, not cropped.
        if (modelUrl != null && coreReady) {
            Live2DCallStage(
                characterId = characterId,
                modelUrl = modelUrl,
                speaking = state.connected && state.speaking,
                listening = state.connected && state.listening,
                mouthLevel = mouthLevel,
                previewExpression = previewExpression,
                previewMotion = previewMotion,
                previewRevision = previewRevision,
                onStatus = { phase, detail ->
                    stageStatus = phase
                    stageDetail = detail
                },
            )
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
                IconButton(onClick = onChangeModel) {
                    Icon(Icons.Outlined.Portrait, "更换 Live2D 模型", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!rendered) {
                val message = when {
                    modelUrl == null -> "尚未导入 Live2D 身体，点击右上角导入模型"
                    !coreReady -> "正在准备 Live2D 官方渲染组件"
                    stageStatus == "error" -> stageDetail.ifBlank { "Live2D 加载失败" }
                    else -> if (hasCreatorPreview) "完整形象预览 · 正在准备动态画面"
                            else "暂无模型预览图 · 正在加载动态画面"
                }
                Text(message, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = .53f))
                        .padding(horizontal = 12.dp, vertical = 9.dp))
                if (stageStatus == "error" && hasCreatorPreview) {
                    Text("正在显示模型自带的静态形象；可继续正常打电话。",
                        color = Color.White.copy(alpha = .75f), fontSize = 11.sp)
                }
                if (!coreReady && modelUrl != null && setupMessage.contains("失败")) {
                    TextButton(onClick = onRetryCore) {
                        Text("重试安装官方渲染核心", color = Color.White, fontSize = 12.sp)
                    }
                }
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
