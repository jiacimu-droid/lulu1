package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.ai.LuluMusicSensoryRuntime
import kotlinx.coroutines.launch

/** An explicit, per-role audio share. Never quietly records system output. */
@Composable
internal fun CharacterMusicSensingSettings(characterId: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_music_sensory_v1", Context.MODE_PRIVATE) }
    var baseUrl by remember { mutableStateOf(prefs.getString("base_url", "").orEmpty()) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "").orEmpty()) }
    var model by remember { mutableStateOf(prefs.getString("model", "").orEmpty()) }
    var mode by remember { mutableStateOf(prefs.getString("mode", LuluMusicSensoryRuntime.MODE_OPENAI).orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(characterId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(baseUrl, apiKey, model, mode) {
        prefs.edit().putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("api_key", apiKey.trim())
            .putString("model", model.trim())
            .putString("mode", mode).apply()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            status = "正在用选定的多模态模型理解实际音频…"
            scope.launch {
                val result = LuluMusicSensoryRuntime.senseSelected(context, characterId, uri)
                status = result.fold(
                    onSuccess = { it },
                    onFailure = { "未能理解音频：" + it.message.orEmpty() })
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("分享音乐给角色听", style = MaterialTheme.typography.titleMedium)
        Text("听觉模型：" + model.ifBlank { "未配置" },
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起听觉模型线路" else "配置独立听觉模型")
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == LuluMusicSensoryRuntime.MODE_OPENAI,
                    onClick = { mode = LuluMusicSensoryRuntime.MODE_OPENAI },
                    label = { Text("兼容 audio_url") })
                FilterChip(
                    selected = mode == LuluMusicSensoryRuntime.MODE_GEMINI,
                    onClick = { mode = LuluMusicSensoryRuntime.MODE_GEMINI },
                    label = { Text("Gemini 原生") })
            }
            SavedConfigurationModelPicker(
                pickerKey = "music-sense",
                currentBaseUrl = baseUrl,
                currentModel = model,
                onConfigurationSelected = { baseUrl = it.baseUrl; apiKey = it.apiKey },
                onModelSelected = { model = it },
            )
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { picker.launch(arrayOf("audio/*")) },
        ) { Text(if (busy) "正在分析音频…" else "选择音频 · 分享给此角色") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
