package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor

/**
 * The only API-level model choice. The archive list below is pure storage;
 * every character, resident and app page owns its own visible override.
 */
@Composable
internal fun OptionalModelRolesSettings() {
    val store = LuluAiServices.connectionStore
    val library by store.library.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val fallbackId = library.archiveIdFor(ModelUsage.Sleep)
    val fallback = library.archives.firstOrNull { it.id == fallbackId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("默认兜底模型", style = MaterialTheme.typography.titleMedium)
                Text(fallback?.let(store::archiveLabel) ?: "尚未选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { choosing = true; error = "" }) { Text("更换") }
        }
        Text("哄睡和未单独指定模型的功能使用它；角色聊天、居民与游戏等独立选择不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }

    if (choosing) {
        ModelArchivePickerSheet(
            title = "默认兜底模型",
            subtitle = "只有在这里主动更换才会改变兜底；新增模型存档不会自动切换。",
            selectedArchiveId = fallbackId,
            onSelect = { id ->
                runCatching { store.selectArchive(id) }
                    .onSuccess { error = ""; choosing = false }
                    .onFailure { error = it.message ?: "模型选择失败"; choosing = false }
            },
            onDismiss = { choosing = false },
            accent = Color(0xFF292929),
            background = Color.White,
            ink = Color(0xFF1D1D1F),
            muted = Color(0xFF7A7A7E),
            border = Color(0xFFE7E7E7),
        )
    }
}
