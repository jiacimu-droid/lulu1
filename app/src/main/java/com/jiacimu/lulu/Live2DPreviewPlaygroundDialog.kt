package com.jiacimu.lulu

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
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
 */
@Composable
internal fun Live2DPreviewPlaygroundDialog(characterId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val revision by Live2DAssets.revisions.collectAsState()
    val modelUrl = remember(characterId, revision) { Live2DAssets.modelUrl(context, characterId) }
    val modelLabel = remember(characterId, revision) { Live2DAssets.modelLabel(context, characterId) }
    val controls = remember(characterId, revision) { Live2DAssets.controls(context, characterId) }
    var previewMotion by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var previewExpression by remember { mutableStateOf<String?>(null) }
    var previewFace by remember { mutableStateOf<Live2DVisualActionBus.Face?>(null) }
    var motionRevision by remember { mutableIntStateOf(0) }
    var lastPlayed by remember { mutableStateOf("尚未播放") }
    var lastTouch by remember { mutableStateOf("尚未触摸") }
    var stagePhase by remember { mutableStateOf("idle") }
    var stageMessage by remember { mutableStateOf("尚未开始动态渲染") }

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF202326)).padding(12.dp),
        ) {
            Text(
                "动作表情试验场" + (modelLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                color = Color.White, fontSize = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF25282C)),
                contentAlignment = Alignment.Center,
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
                    Text("尚未导入角色 Live2D 模型", color = Color.LightGray, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("当前：$lastPlayed", color = Color(0xFFFAE3AE), fontSize = 12.sp)
            Text("触摸：$lastTouch", color = Color.LightGray, fontSize = 12.sp)
            Text("阶段：$stagePhase · $stageMessage", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.fillMaxWidth().height(280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
                        TextButton(onClick = onDismiss) { Text("关闭", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
