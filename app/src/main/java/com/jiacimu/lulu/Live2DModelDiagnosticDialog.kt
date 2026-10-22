package com.jiacimu.lulu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

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
    var latestPhase by remember { mutableStateOf("未检测") }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var manualHead by remember { mutableStateOf<Live2DVisualActionBus.Head?>(null) }
    var motionRevision by remember { mutableIntStateOf(0) }
    var mouthPreview by remember { mutableStateOf(false) }
    fun record(message: String) {
        events = (events + message.take(260)).takeLast(50)
    }
    fun attemptReload() {
        session++
        manualHead = null
        mouthPreview = false
        record("重新创建独立 WebView 渲染器")
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                                onStatus = { phase, detail ->
                                    if (phase != "diagnostic") {
                                        stagePhase = phase
                                        stageMessage = detail
                                    }
                                    if (phase != latestPhase || events.lastOrNull() != "$phase · $detail") {
                                        record("$phase · $detail")
                                        latestPhase = phase
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
                Text("加载日志（最近50条）", color = Color.LightGray, fontSize = 12.sp)
                val scrollState = rememberScrollState()
                LaunchedEffect(events.size) { scrollState.scrollTo(scrollState.maxValue) }
                Column(Modifier.fillMaxWidth().height(145.dp).verticalScroll(scrollState)
                    .background(Color(0xFF202326)).padding(8.dp)) {
                    events.forEach {
                        Text(it, color = Color(0xFFE0E2E6), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
