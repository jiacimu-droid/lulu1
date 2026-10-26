package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.ai.JevDirections
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections

@Composable
internal fun OptionalModelRolesSettings() {
    val context = LocalContext.current
    val library by LuluAiServices.connectionStore.library.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var extended by remember { mutableStateOf(JevDirections.longContext(context)) }
    val selections = remember(library, revision) {
        listOf(ScopedModelSelections.EXPRESSION, ScopedModelSelections.JEV).associateWith {
            ScopedModelSelections.selectedArchiveId(it, library)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型分工", style = MaterialTheme.typography.titleMedium)
        listOf(ScopedModelSelections.EXPRESSION to "文字表达", ScopedModelSelections.JEV to "多方向参考 · Jev").forEach { (role, label) ->
            val archive = library.archives.firstOrNull { it.id == selections[role] }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    Text(archive?.let(LuluAiServices.connectionStore::archiveLabel)
                        ?: if (role == ScopedModelSelections.EXPRESSION) "跟随本轮思考模型" else "未启用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { choosing = role; notice = "" }) { Text("选择") }
                if (archive != null) TextButton(onClick = {
                    ScopedModelSelections.clear(role); revision++
                }) { Text(if (role == ScopedModelSelections.EXPRESSION) "跟随" else "关闭") }
            }
        }
        Text("文字表达仅替换已有润色阶段。Jev仅在角色主动选择时单独调用并计费，概率不会自动变成行动。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selections[ScopedModelSelections.JEV] != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Jev 长上下文")
                Text("默认单题32k；开启后262k，可能更慢且准确率下降。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = extended, onCheckedChange = { extended = it; JevDirections.setLongContext(context, it) })
        }
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error)
    }
    choosing?.let { role ->
        ModelArchivePickerSheet(
            title = if (role == ScopedModelSelections.EXPRESSION) "文字表达模型" else "Jev 思路参考模型",
            subtitle = if (role == ScopedModelSelections.EXPRESSION) "只负责已有表达阶段，思考与行动决策仍用原模型。"
                else "先保存TypeSafe配置并获取Jev型号。没有配置时不会调用。",
            selectedArchiveId = selections[role],
            onSelect = { id ->
                val model = library.archives.first { it.id == id }.model
                val jev = model.startsWith("jev-")
                if ((role == ScopedModelSelections.JEV) != jev) {
                    notice = if (role == ScopedModelSelections.JEV) "请选择jev-开头的Jev型号" else "Jev不是文字生成模型，请选择可聊天的型号"
                } else {
                    ScopedModelSelections.select(role, id); revision++; notice = ""
                }
                choosing = null
            }, onDismiss = { choosing = null },
            accent = Color(0xFF292929), background = Color.White,
            ink = Color(0xFF1D1D1F), muted = Color(0xFF7A7A7E), border = Color(0xFFE7E7E7),
        )
    }
}
