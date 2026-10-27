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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Portrait
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
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
    // Same framing switch as video calls: waist-up portrait or full body.
    // Switching framing must not recreate the stage (calls rely on that too).
    var framingMode by remember { mutableStateOf("portrait") }
    var menuExpanded by remember { mutableStateOf(false) }
    var panelExpanded by remember { mutableStateOf(true) }
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
    }

    fun playExpression(name: String) {
        previewExpression = name
        lastPlayed = "表情 $name"
        motionRevision++
    }

    fun reset() {
        previewMotion = null
        previewExpression = null
        previewFace = null
        lastPlayed = "已复位"
        motionRevision++
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF151719)),
    ) {
        if (modelUrl != null) {
            key(characterId, modelUrl) {
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
                    onModelTouch = { region, gesture, force, durationMillis ->
                        lastTouch = "$region · $gesture · $force · ${durationMillis}ms"
                    },
                    onStatus = { phase, detail ->
                        stagePhase = phase
                        stageMessage = detail
                    },
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("尚未导入角色 Live2D 模型", color = Color.LightGray, fontSize = 13.sp)
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
        // Call-style top chrome: title, framing menu, close.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "动作表情试验场" + (modelLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
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
        // Bottom control strip over the stage.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xE6202326)).padding(12.dp),
        ) {
            Text("当前：$lastPlayed", color = Color(0xFFFAE3AE), fontSize = 12.sp)
            Text("触摸：$lastTouch", color = Color.LightGray, fontSize = 12.sp)
            Text("阶段：$stagePhase · $stageMessage", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (!panelExpanded) {
                TextButton(onClick = { panelExpanded = true }) {
                    Text("展开动作 / 表情 / 皮套", fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Text("皮套存档（一个 zip 就是一个存档，切换不删旧皮）", color = Color.White, fontSize = 13.sp)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Button(onClick = { importLauncher.launch("application/zip") }) {
                                Text("导入 zip 皮套", fontSize = 12.sp)
                            }
                            TextButton(onClick = { panelExpanded = false }) {
                                Text("收起", fontSize = 12.sp)
                            }
                        }
                    }
                    if (archiveMessage.isNotBlank()) {
                        item { Text(archiveMessage, color = Color(0xFFFAE3AE), fontSize = 12.sp) }
                    }
                    if (archives.isEmpty()) {
                        item { Text("暂无存档，导入一个 zip 就会出现在这里", color = Color.LightGray, fontSize = 12.sp) }
                    } else {
                        items(archives.size) { index ->
                            val entry = archives[index]
                            val isCurrent = entry.settings == currentSettings
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.label + if (isCurrent) "（当前）" else "",
                                    color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (!isCurrent) {
                                    TextButton(onClick = {
                                        archiveMessage = if (Live2DAssets.switchArchive(
                                                context, characterId, entry.settings,
                                            )
                                        ) "已切换到 ${entry.label}" else "切换失败"
                                    }) { Text("切换", fontSize = 12.sp) }
                                }
                                TextButton(onClick = {
                                    archiveMessage = if (Live2DAssets.deleteArchive(
                                            context, characterId, entry.settings,
                                        )
                                    ) "已删除 ${entry.label}" else "删除失败"
                                }) { Text("删除", fontSize = 12.sp) }
                            }
                        }
                    }
                    item {
                        Text("动作（点一下播整套：动作＋配表情＋神态）", color = Color.White, fontSize = 13.sp)
                    }
                    val motions = controls.motions
                    if (motions.isEmpty()) {
                        item { Text("该模型没有可用动作", color = Color.LightGray, fontSize = 12.sp) }
                    } else {
                        items(motions.chunked(3).size) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                for ((group, index) in motions.chunked(3)[row]) {
                                    val label = if (group == "LuluWolf") {
                                        Live2DMotionCatalog.entries[index]?.name ?: "$group#$index"
                                    } else {
                                        "$group#$index"
                                    }
                                    Button(
                                        onClick = { playMotion(group, index) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text(label, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    item {
                        Text("表情（只换表情）", color = Color.White, fontSize = 13.sp)
                    }
                    if (controls.expressions.isEmpty()) {
                        item { Text("该模型没有可用表情", color = Color.LightGray, fontSize = 12.sp) }
                    } else {
                        items(controls.expressions.chunked(4).size) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                for (name in controls.expressions.chunked(4)[row]) {
                                    Button(
                                        onClick = { playExpression(name) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text(name, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            TextButton(onClick = ::reset) { Text("复位", fontSize = 12.sp) }
                            TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}
