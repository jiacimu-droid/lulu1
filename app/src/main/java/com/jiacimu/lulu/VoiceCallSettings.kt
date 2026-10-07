package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceCallSettings(provider: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE) }
    var mode by remember(provider) { mutableStateOf(prefs.getString("voice_call_mode", "direct").orEmpty()) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat("voice_vad_threshold", 350f)) }
    var advanced by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(prefs.getString("minimax_asr_endpoint", "").orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("电话使用 ${CallVoiceConfiguration.label(provider)}", style = MaterialTheme.typography.titleMedium)
        if (provider == "elevenlabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode != "agent", { mode = "direct"; prefs.edit().putString("voice_call_mode", mode).apply() }, label = { Text("账号直连") })
                FilterChip(mode == "agent", { mode = "agent"; prefs.edit().putString("voice_call_mode", mode).apply() }, label = { Text("Agent 高级通话") })
            }
            Text(if (mode == "agent") "需要部署角色服务并配置 ElevenLabs Agent；使用其通话会话。" else "使用同一 ElevenLabs Key 识别和发声，角色回复使用电话模型；无需部署服务。")
        } else if (provider == "minimax") {
            Text("同一 MiniMax Key 用于语音识别和发声。停顿后转写，再由电话模型回复；无需部署服务。")
            TextButton({ advanced = !advanced }) { Text("识别接口设置") }
            if (advanced) OutlinedTextField(endpoint, { endpoint = it; prefs.edit().putString("minimax_asr_endpoint", it).apply() },
                label = { Text("识别接口（留空跟随 MiniMax 区域）") }, modifier = Modifier.fillMaxWidth())
        } else Text("使用手机系统识别和发声；手机必须安装可用的系统语音识别服务。")
        if (provider == "minimax") {
            Text("收音灵敏度（较低阈值更容易识别轻声）")
            Slider(threshold, { threshold = it }, onValueChangeFinished = { prefs.edit().putFloat("voice_vad_threshold", threshold).apply() }, valueRange = 150f..1500f)
        }
    }
}
