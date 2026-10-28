package com.jiacimu.lulu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Portrait
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Standalone motion/expression playground for one imported model.
 *
 * No video call, no AI reply, no TTS: the user taps a motion and watches
 * the same stage play it, so feedback can name the exact motion ("23
 * 惊讶一震太快了") instead of describing a call they just made. Tapping a
 * motion plays its combined performance (motion + catalog expression +
 * catalog face); tapping an expression only swaps the expression, so
 * combinations can be tried freely. Touching the model itself still runs
 * the normal touch reflex and is logged here instead of answered.
 *
 * The same content also hosts skin archives: one imported zip is one
 * archive, and switching archives only rebinds the character's model,
 * never deletes the others until the user removes one.
 *
 * The page mirrors the fullscreen video call page: the stage fills the
 * screen, the framing menu (waist-up / full-body) is the same one calls
 * use, and the motion/expression/archive controls live in a collapsible
 * bottom panel over the stage instead of a list squeezed under it.
 * Importing a model zip works from here too, even when nothing has been
 * imported yet.
 */
@Composable
internal fun Live2DPreviewPlaygroundDialog(characterId: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Live2DPreviewPlaygroundContent(characterId, onClose = onDismiss)
    }
}

@Composable
internal fun Live2DPreviewPlaygroundContent(characterId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Live2DAssets.revisions.collectAsState()
    val modelUrl = remember(characterId, revision) { Live2DAssets.modelUrl(context, characterId) }
    val modelLabel = remember(characterId, revision) { Live2DAssets.modelLabel(context, characterId) }
    val controls = remember(characterId, revision) { Live2DAssets.controls(context, characterId) }
    val archives = remember(characterId, revision) { Live2DAssets.listArchives(context, characterId) }
    val currentSettings = remember(characterId, revision) {
        Live2DAssets.currentModelSettings(context, characterId)
    }
    var previewMotion by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var previewExpression by remember { mutableStateOf<String?>(null) }
    var previewFace by remember { mutableStateOf<Live2DVisualActionBus.Face?>(null) }
    var motionRevision by remember { mutableIntStateOf(0) }
    var lastPlayed by remember { mutableStateOf("尚未播放") }
    var lastTouch by remember { mutableStateOf("尚未触摸") }
    var stagePhase by remember { mutableStateOf("idle") }
    var stageMessage by remember { mutableStateOf("尚未开始动态渲染") }
    var archiveMessage by remember { mutableStateOf("") }
    var pendingDelete by remember(characterId) { mutableStateOf<Live2DAssets.ArchiveEntry?>(null) }
    // Same framing switch as video calls: waist-up portrait or full body.
    // Switching framing must not recreate the stage (calls rely on that too).
    var framingMode by remember { mutableStateOf("portrait") }
    var menuExpanded by remember { mutableStateOf(false) }
    val coreInstalled = remember(revision) { Live2DAssets.hasCore(context) }
    // The portrait has priority: the controls start as a compact dock.
    // A catalog opens temporarily, then closes as soon as an action is chosen.
    var panelExpanded by remember(characterId) { mutableStateOf(false) }
    var panelSection by remember(characterId) { mutableStateOf("motions") }
    var interactionMode by remember(modelUrl) { mutableStateOf("hand") }
    var interactionForce by remember(modelUrl) { mutableStateOf(.25f) }
    var touchCalibrationMode by remember(modelUrl) { mutableStateOf(false) }
    var touchRegion by remember(modelUrl) { mutableStateOf("cheek-left") }
    var touchRegionMenuOpen by remember(modelUrl) { mutableStateOf(false) }
    val touchPrefs = remember(context) {
        context.getSharedPreferences("lulu_live2d_touch_zones_v1", android.content.Context.MODE_PRIVATE)
    }
    var touchRadius by remember(modelUrl, touchRegion) {
        val saved = runCatching {
            JSONObject(touchPrefs.getString(modelUrl.orEmpty(), "{}") ?: "{}")
                .optJSONObject(touchRegion)?.optDouble("rx")?.toFloat()
        }.getOrNull()?.takeIf { it.isFinite() && it >= Live2DTouchZones.minimumRadius }
        mutableStateOf(saved ?: Live2DTouchZones.defaultRadius(touchRegion))
    }
    var touchSizeRevision by remember(modelUrl) { mutableIntStateOf(0) }
    var touchCalibrationReset by remember(modelUrl) { mutableIntStateOf(0) }
    var confirmOfficialCore by remember { mutableStateOf(false) }
    var coreLicenseChecked by remember { mutableStateOf(false) }
    val coreLicensePrefs = remember(context) {
        context.getSharedPreferences("lulu_live2d_core_license_v1", android.content.Context.MODE_PRIVATE)
    }
    var coreBusy by remember { mutableStateOf(false) }
    var coreMessage by remember { mutableStateOf("") }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                archiveMessage = try {
                    Live2DAssets.importModel(context, characterId, uri)
                } catch (error: Exception) {
                    "导入失败：${error.message}"
                }
            }
        }
    }

    val importCoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            coreBusy = true
            coreMessage = try {
                Live2DAssets.importOfficialCore(context, uri)
            } catch (error: Exception) {
                "Core 导入失败：${error.message.orEmpty().take(150)}"
            } finally {
                coreBusy = false
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这份 Live2D 皮套？") },
            text = {
                Text("即将删除「" + entry.label + "」。这份模型的互动校准也将删除；其他上传版本、校准和角色资料不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    archiveMessage = if (Live2DAssets.deleteArchive(context, characterId, entry.settings))
                        "已删除 " + entry.label else "删除失败，请检查该模型是否仍在使用"
                    pendingDelete = null
                    previewMotion = null
                    previewExpression = null
                    previewFace = null
                    motionRevision++
                }) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("保留模型") }
            },
        )
    }
    if (confirmOfficialCore) {
        AlertDialog(
            onDismissRequest = { if (!coreBusy) confirmOfficialCore = false },
            title = { Text("安装 Live2D 官方 Cubism Core") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("皮套 ZIP 不包含官方渲染核心。首次安装前需要阅读许可；Core 仅下载到本机，不打包进 APK。")
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(Live2DOfficialCoreInstaller.LICENSE_URL)))
                        }.onFailure { coreMessage = "无法打开官方许可页面" }
                    }) { Text("阅读 Live2D 官方许可") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = coreLicenseChecked,
                            onCheckedChange = { coreLicenseChecked = it })
                        Text("我已阅读并同意官方许可")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = coreLicenseChecked && !coreBusy, onClick = {
                    if (!coreLicensePrefs.edit().putBoolean("license_accepted", true).commit()) {
                        coreMessage = "无法保存许可确认，安装未开始"
                    } else {
                        confirmOfficialCore = false
                        coreBusy = true
                        scope.launch {
                            coreMessage = try {
                                Live2DOfficialCoreInstaller.install(context)
                            } catch (error: Exception) {
                                "官方 Core 安装失败：${error.message.orEmpty().take(150)}，也可以手动导入 Core JS"
                            } finally { coreBusy = false }
                        }
                    }
                }) { Text("确认并安装") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOfficialCore = false }) { Text("取消") }
            },
        )
    }

    fun playMotion(group: String, index: Int) {
        previewMotion = group to index
        val entry = if (group == "LuluWolf") Live2DMotionCatalog.entries[index] else null
        previewExpression = entry?.expression?.let {
            Live2DVisualActionBus.resolveExpression(it, controls.expressions)
        }
        previewFace = entry?.face?.takeIf { it.isNotEmpty() }?.let { Live2DVisualActionBus.Face(it) }
        val label = entry?.name ?: "$group#$index"
        lastPlayed = "动作 $label" +
            (entry?.expression?.let { "＋$it" } ?: "") +
            (entry?.faceNote?.let { "＋$it" } ?: "")
        motionRevision++
        panelExpanded = false
    }

    fun playExpression(name: String) {
        previewExpression = name
        lastPlayed = "表情 $name"
        motionRevision++
        panelExpanded = false
    }

    fun reset() {
        previewMotion = null
        previewExpression = null
        previewFace = null
        lastPlayed = "已复位"
        motionRevision++
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFFE6EDF3)),
    ) {
        if (modelUrl != null && coreInstalled) {
            // Never start the WebView model loader before the licensed Cubism Core is installed.
            // A missing Core is an actionable setup state, not a blank rendering crash.
            // A new Core installation must recreate this WebView, not merely recompose controls.
            key(characterId, modelUrl, revision) {
                Live2DCallStage(
                    characterId = characterId,
                    modelUrl = modelUrl,
                    speaking = false,
                    listening = false,
                    mouthLevel = 0f,
                    previewExpression = previewExpression,
                    previewMotion = previewMotion,
                    previewFace = previewFace,
                    previewRevision = motionRevision,
                    previewImmediate = true,
                    framingMode = framingMode,
                    interactionMode = interactionMode,
                    interactionForce = interactionForce,
                    calibrationMode = touchCalibrationMode,
                    calibrationRegion = touchRegion,
                    calibrationRadius = touchRadius,
                    calibrationSizeRevision = touchSizeRevision,
                    calibrationResetRevision = touchCalibrationReset,
                    onModelTouch = { region, gesture, force, durationMillis ->
                        lastTouch = "$region · $gesture · $force · ${durationMillis}ms"
                    },
                    onStatus = { phase, detail ->
                        stagePhase = phase
                        stageMessage = detail
                    },
                )
            }
        } else if (modelUrl == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("尚未导入角色 Live2D 模型", color = Color(0xFF31445A), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { importLauncher.launch("application/zip") }) {
                    Text("导入 zip 皮套", fontSize = 12.sp)
                }
                if (archiveMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(archiveMessage, color = Color(0xFFFAE3AE), fontSize = 12.sp)
                }
            }
        }
        if (modelUrl != null && !coreInstalled) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("皮套已导入，缺少 Cubism Core", color = Color(0xFF27384B), fontSize = 15.sp)
                Text("Core 是官方渲染引擎，不包含在皮套 ZIP 中。", color = Color(0xFF52677C), fontSize = 12.sp)
                Button(enabled = !coreBusy, onClick = { confirmOfficialCore = true }) {
                    Text(if (coreBusy) "正在安装…" else "安装官方 Core")
                }
                OutlinedButton(enabled = !coreBusy, onClick = { importCoreLauncher.launch("*/*") }) {
                    Text("从文件导入 Core JS")
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(Live2DOfficialCoreInstaller.LICENSE_URL)))
                    }.onFailure { coreMessage = "无法打开许可页面，请在浏览器访问 Live2D 官方许可协议" }
                }) { Text("查看官方许可") }
            }
        }
        if (coreMessage.isNotBlank()) {
            Text(
                coreMessage,
                color = Color(0xFF31445A),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(top = 275.dp, start = 22.dp, end = 22.dp),
            )
        }
        // Call-style top chrome: title, framing menu, close.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Live2D" + (modelLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, "试验场设置", tint = Color.White)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (framingMode == "portrait") "✓ 半身近景" else "半身近景") },
                        onClick = {
                            framingMode = "portrait"
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Outlined.Face, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (framingMode == "full") "✓ 全身镜头" else "全身镜头") },
                        onClick = {
                            framingMode = "full"
                            menuExpanded = false
                        },
                        leadingIcon = { Icon(Icons.Outlined.Portrait, null) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (panelExpanded) "收起控制条" else "展开控制条") },
                        onClick = {
                            panelExpanded = !panelExpanded
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("复位") },
                        onClick = {
                            reset()
                            menuExpanded = false
                        },
                    )
                }
            }
            TextButton(onClick = onClose) { Text("关闭", color = Color.White, fontSize = 13.sp) }
        }
        // Preview first: only two small rows are persistent. The motion catalog
        // never reserves half the display and collapses after playback starts.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xE91E2937)).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                listOf("hand" to "抚摸", "pat" to "扇脸", "kiss" to "亲吻").forEach { (id, label) ->
                    TextButton(onClick = { interactionMode = id }) {
                        Text((if (interactionMode == id) "● " else "") + label,
                            color = if (interactionMode == id) Color(0xFFB8E6FF) else Color.White,
                            fontSize = 12.sp)
                    }
                }
                Text("│", color = Color.LightGray)
                listOf(.25f to "轻", .9f to "重").forEach { (force, label) ->
                    TextButton(onClick = { interactionForce = force }) {
                        Text((if (interactionForce == force) "●" else "") + label,
                            color = if (interactionForce == force) Color(0xFFB8E6FF) else Color.White,
                            fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                listOf("motions" to "动作", "expressions" to "表情", "archives" to "皮套").forEach { (id, label) ->
                    TextButton(onClick = {
                        panelExpanded = if (panelSection == id) !panelExpanded else true
                        panelSection = id
                    }) {
                        Text(if (panelExpanded && panelSection == id) "收起$label" else label,
                            color = Color.White, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    touchCalibrationMode = !touchCalibrationMode
                    if (touchCalibrationMode) panelExpanded = false
                }) {
                    Text(if (touchCalibrationMode) "完成校准" else "校准部位",
                        color = if (touchCalibrationMode) Color(0xFFB8E6FF) else Color.White,
                        fontSize = 12.sp)
                }
                if (!touchCalibrationMode) {
                    TextButton(onClick = ::reset) { Text("复位", color = Color.White, fontSize = 12.sp) }
                }
            }
            if (touchCalibrationMode) {
                Text("在人物对应部位轻点，标记该区域中心；调节范围后自动保存",
                    color = Color(0xFFDDE8F2), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { touchRegionMenuOpen = true }) {
                            Text((Live2DTouchZones.label(touchRegion) ?: "脸颊") + " ▾",
                                color = Color.White, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = touchRegionMenuOpen,
                            onDismissRequest = { touchRegionMenuOpen = false }) {
                            Live2DTouchZones.labels.filterKeys {
                                it !in setOf("head", "arm")
                            }.forEach { (id, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    touchRegion = id
                                    touchRegionMenuOpen = false
                                })
                            }
                        }
                    }
                    Slider(value = touchRadius, onValueChange = {
                        touchRadius = it
                        touchSizeRevision++
                    }, valueRange = Live2DTouchZones.minimumRadius.. .30f,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        touchCalibrationReset++
                        touchRadius = Live2DTouchZones.defaultRadius(touchRegion)
                    }) { Text("重置", color = Color.White, fontSize = 12.sp) }
                }
            }
            if (panelExpanded && !touchCalibrationMode) {
                Text("当前：$lastPlayed · 触摸：$lastTouch",
                    color = Color(0xFFE7F0F6), fontSize = 11.sp, maxLines = 1)
                LazyColumn(
                    Modifier.fillMaxWidth().height(158.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (panelSection) {
                        "archives" -> {
                            item {
                                Button(onClick = { importLauncher.launch("application/zip") }) {
                                    Text("导入 ZIP 皮套", fontSize = 12.sp)
                                }
                            }
                            if (archiveMessage.isNotBlank()) item {
                                Text(archiveMessage, color = Color(0xFFFAE3AE), fontSize = 12.sp)
                            }
                            if (archives.isEmpty()) item {
                                Text("还没有皮套存档", color = Color.White, fontSize = 12.sp)
                            }
                            items(archives.size) { index ->
                                val entry = archives[index]
                                val isCurrent = entry.settings == currentSettings
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.label + if (isCurrent) "（当前）" else "",
                                            color = Color.White, fontSize = 12.sp)
                                        Text("上传版本 · " + entry.settings.split('/').getOrNull(2)
                                            .orEmpty().take(7),
                                            color = Color(0xFFC5D0DE), fontSize = 10.sp)
                                    }
                                    if (!isCurrent) TextButton(onClick = {
                                        archiveMessage = if (Live2DAssets.switchArchive(
                                                context, characterId, entry.settings,
                                            )
                                        ) "已切换到 ${entry.label}" else "切换失败"
                                        if (archiveMessage.startsWith("已切换")) panelExpanded = false
                                    }) { Text("切换", fontSize = 12.sp) }
                                    TextButton(onClick = {
                                        pendingDelete = entry
                                    }) { Text("删除", fontSize = 12.sp) }
                                }
                            }
                        }
                        "expressions" -> {
                            if (controls.expressions.isEmpty()) item {
                                Text("该皮套没有表情", color = Color.White, fontSize = 12.sp)
                            }
                            items(controls.expressions.chunked(3).size) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    for (name in controls.expressions.chunked(3)[row]) {
                                        Button(onClick = { playExpression(name) },
                                            modifier = Modifier.weight(1f)) {
                                            Text(name, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            if (controls.motions.isEmpty()) item {
                                Text("该皮套没有动作", color = Color.White, fontSize = 12.sp)
                            }
                            items(controls.motions.chunked(3).size) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    for ((group, index) in controls.motions.chunked(3)[row]) {
                                        val label = if (group == "LuluWolf") {
                                            Live2DMotionCatalog.entries[index]?.name ?: "$group#$index"
                                        } else "$group#$index"
                                        Button(onClick = { playMotion(group, index) },
                                            modifier = Modifier.weight(1f)) {
                                            Text(label, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
