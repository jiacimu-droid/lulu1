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
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.ResidentDailyDecision

@Composable
internal fun OptionalModelRolesSettings() {
    val context = LocalContext.current
    val library by LuluAiServices.connectionStore.library.collectAsState()
    remember(context) { ScopedModelSelections.initialize(context.applicationContext); Unit }
    val scopeRevision by ScopedModelSelections.changes.collectAsState()
    var choosing by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var extended by remember { mutableStateOf(JevDirections.longContext(context)) }
    val roles = listOf(
        Triple(ScopedModelSelections.RESIDENT_DAILY, "居民日常决策", "未选择（需要配置低价模型）"),
        Triple(ScopedModelSelections.RESIDENT_SOCIAL, "居民聊天与社交", "跟随居民日常模型"),
        Triple(ScopedModelSelections.WORLD_SOCIAL, "数字世界·人物相遇", "默认跟随居民低价模型，未配置时普通角色沿用自己的聊天模型"),
        Triple(ScopedModelSelections.WORLD_SCENE, "数字世界·场景创意", "优先跟随世界社交／居民模型"),
        Triple(ScopedModelSelections.WORLD_LIFE, "生活小剧场·文学正文", "角色用自己的聊天模型，居民用居民社交模型"),
        Triple(ScopedModelSelections.MEETING, "数字世界·与主人见面", "跟随公共聊天模型"),
        Triple(ScopedModelSelections.THEATER, "单篇／星愿馆剧场", "跟随游戏模型"),
        Triple(ScopedModelSelections.APOCALYPSE, "末世求生", "跟随游戏模型"),
        Triple(ScopedModelSelections.EXPRESSION, "文字表达", "跟随本轮思考模型"),
        Triple(ScopedModelSelections.JEV, "多方向参考 · Jev", "未启用"),
    )
    val selections = remember(library, scopeRevision) {
        roles.associate { (role, _, _) ->
            role to ScopedModelSelections.selectedArchiveId(role, library)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型分工", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("默认聊天模型")
                val selected = library.archives.firstOrNull { it.id == library.archiveIdFor(ModelUsage.Chat) }
                Text(selected?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { choosing = "chat"; notice = "" }) { Text("选择") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("默认游戏模型")
                val selected = library.archives.firstOrNull { it.id == library.archiveIdFor(ModelUsage.Game) }
                Text(selected?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { choosing = "game"; notice = "" }) { Text("选择") }
        }
        Text("单个角色的私聊模型仍可在角色聊天页单独选择，互不影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        roles.forEach { (role, label, fallbackLabel) ->
            val archive = library.archives.firstOrNull { it.id == selections[role] }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    Text(archive?.let(LuluAiServices.connectionStore::archiveLabel) ?: fallbackLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { choosing = role; notice = "" }) { Text("选择") }
                if (archive != null) TextButton(onClick = { ScopedModelSelections.clear(role) }) {
                    Text(if (role == ScopedModelSelections.JEV) "关闭" else "跟随")
                }
            }
        }
        Text("居民主动社交默认使用居民日常模型；世界场景创意不代替角色决定，生活文学模型只负责整理已发生的事。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Sleep model: its own library slot, not a scoped preference. Picking
        // one makes sleep-mode call turns use it; leaving it unset follows
        // the chat model, and exiting sleep returns to the call model.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val sleepArchive = library.sleepArchiveId?.let { id -> library.archives.firstOrNull { it.id == id } }
            Column(Modifier.weight(1f)) {
                Text("哄睡模型")
                Text(sleepArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "跟随聊天模型",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { choosing = "sleep"; notice = "" }) { Text("选择") }
            if (sleepArchive != null) TextButton(onClick = {
                LuluAiServices.connectionStore.clearSleepArchive()
            }) { Text("跟随") }
        }
        Text("哄睡模型只在通话进入哄睡时使用，退出哄睡自动回普通通话模型；不选就跟随聊天模型。",
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
        val isSleep = role == "sleep"
        val isChat = role == "chat"
        val isGame = role == "game"
        val isJev = role == ScopedModelSelections.JEV
        val currentId = when {
            isSleep -> library.sleepArchiveId
            isChat -> library.archiveIdFor(ModelUsage.Chat)
            isGame -> library.archiveIdFor(ModelUsage.Game)
            else -> selections[role]
        }
        val selectedName = when {
            isSleep -> "哄睡模型"
            isChat -> "默认聊天模型"
            isGame -> "默认游戏模型"
            else -> roles.firstOrNull { it.first == role }?.second ?: "模型"
        }
        ModelArchivePickerSheet(
            title = selectedName,
            subtitle = when {
                isSleep -> "只在通话进入哄睡时使用。"
                isJev -> "请选择已保存的 jev- 型号；仅在角色主动使用时调用。"
                role == ScopedModelSelections.WORLD_SCENE ->
                    "为真实相遇提供创意机会，不生成已发生的对话或世界事件。"
                role == ScopedModelSelections.WORLD_LIFE ->
                    "只把已保存的角色经历写成文学正文，不能改变世界事实。"
                else -> "更改这个功能的模型，不影响其他功能或其他角色的私聊模型。"
            },
            selectedArchiveId = currentId,
            onSelect = { id ->
                val model = library.archives.first { it.id == id }.model
                val jev = model.startsWith("jev-")
                if (isJev != jev) {
                    notice = if (isJev) "请选择 jev- 开头的型号" else "Jev不能生成普通文字，请选择聊天模型"
                } else {
                    when {
                        isSleep -> LuluAiServices.connectionStore.selectArchive(id, ModelUsage.Sleep)
                        isChat -> LuluAiServices.connectionStore.selectArchive(id, ModelUsage.Chat)
                        isGame -> LuluAiServices.connectionStore.selectArchive(id, ModelUsage.Game)
                        else -> {
                            ScopedModelSelections.select(role, id)
                            if (role == ScopedModelSelections.RESIDENT_DAILY)
                                ResidentDailyDecision.schedule(context.applicationContext)
                        }
                    }
                    notice = ""
                }
                choosing = null
            },
            onDismiss = { choosing = null },
            accent = Color(0xFF292929), background = Color.White,
            ink = Color(0xFF1D1D1F), muted = Color(0xFF7A7A7E), border = Color(0xFFE7E7E7),
        )
    }
}
