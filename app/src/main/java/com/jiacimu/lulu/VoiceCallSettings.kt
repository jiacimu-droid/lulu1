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
    var ringing by remember { mutableStateOf(prefs.getBoolean("voice_call_ringtone_enabled", true)) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat("voice_vad_threshold", 350f)) }
    var silence by remember { mutableFloatStateOf(prefs.getInt("voice_end_silence_ms", 500).toFloat()) }
    var advanced by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(prefs.getString("minimax_asr_endpoint", "").orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("电话使用 ${CallVoiceConfiguration.label(provider)}", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("来电 / 呼叫铃声", style = MaterialTheme.typography.bodyMedium)
                Text("拨出等待接通或角色主动来电时播放手机本地铃声；接通、拒绝或挂断后停止。不消耗语音模型额度。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = ringing,
                onCheckedChange = { value ->
                    ringing = value
                    prefs.edit().putBoolean("voice_call_ringtone_enabled", value).apply()
                    if (!value) LuluCallRingtone.stopAll()
                },
            )
        }
        if (provider == "elevenlabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode != "agent", { mode = "direct"; prefs.edit().putString("voice_call_mode", mode).apply() }, label = { Text("账号直连") })
                FilterChip(mode == "agent", { mode = "agent"; prefs.edit().putString("voice_call_mode", mode).apply() }, label = { Text("Agent 高级通话") })
            }
            if (mode == "agent") {
                Text("Agent 高级通话由 ElevenLabs 处理实时收音与识别，会继续产生该服务的语音用量。想省识别额度请切换「账号直连」。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text("你说的话由手机本地识别；ElevenLabs 仅为角色合成声音。不会调用 ElevenLabs Scribe 语音转文字接口。",
                    style = MaterialTheme.typography.bodySmall)
                CallVoiceConfiguration.onDeviceSttError(context)?.let { problem ->
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        } else if (provider == "minimax") {
            Text("同一 MiniMax Key 用于语音识别和发声。停顿后转写，再由电话模型回复；无需部署服务。")
            TextButton({ advanced = !advanced }) { Text("识别接口设置") }
            if (advanced) OutlinedTextField(endpoint, { endpoint = it; prefs.edit().putString("minimax_asr_endpoint", it).apply() },
                label = { Text("识别接口（留空跟随 MiniMax 区域）") }, modifier = Modifier.fillMaxWidth())
        } else Text("使用手机系统识别与发声；普通系统识别可能由厂商联网提供，且不使用 ElevenLabs 额度。")
        if (provider == "minimax") {
            Text("停顿多久开始回复：${silence.toInt()} 毫秒")
            Slider(silence, { silence = it }, onValueChangeFinished = { prefs.edit().putInt("voice_end_silence_ms", silence.toInt()).apply() }, valueRange = 300f..1500f)
            Text("较短响应更快，较长适合说话中经常停顿。", style = MaterialTheme.typography.bodySmall)
            Text("收音灵敏度（较低阈值更容易识别轻声）")
            Slider(threshold, { threshold = it }, onValueChangeFinished = { prefs.edit().putFloat("voice_vad_threshold", threshold).apply() }, valueRange = 150f..1500f)
        }
    }
}
