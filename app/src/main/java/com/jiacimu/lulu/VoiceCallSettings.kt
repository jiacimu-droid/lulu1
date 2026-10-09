package com.jiacimu.lulu

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var sttMode by remember { mutableStateOf(prefs.getString("call_stt_mode", "auto").orEmpty()) }
    var groqKey by remember { mutableStateOf(prefs.getString("groq_asr_key", "").orEmpty()) }
    var groqModel by remember { mutableStateOf(prefs.getString("groq_asr_model", "whisper-large-v3-turbo").orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("通话声音：${CallVoiceConfiguration.label(provider)}", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("来电 / 呼叫铃声", style = MaterialTheme.typography.bodyMedium)
                Text("接通、拒绝或挂断后停止铃声。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = ringing, onCheckedChange = { enabled ->
                ringing = enabled
                prefs.edit().putBoolean("voice_call_ringtone_enabled", enabled).apply()
                if (!enabled) LuluCallRingtone.stopAll()
            })
        }
        HorizontalDivider()
        Text("你的声音如何转成文字", style = MaterialTheme.typography.titleSmall)
        Text("单独选择识别渠道，不影响角色的 Voice ID 或语音供应商；自动模式优先沿用已配置的 MiniMax。",
            style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "自动", "system" to "手机识别",
                "groq" to "Groq Whisper", "minimax" to "MiniMax").forEach { (id, title) ->
                FilterChip(selected = sttMode == id, onClick = {
                    sttMode = id
                    prefs.edit().putString("call_stt_mode", id).apply()
                }, label = { Text(title) })
            }
        }
        val activeEngine = CallVoiceConfiguration.resolveSttEngine(sttMode,
            SpeechRecognizer.isRecognitionAvailable(context), groqKey.isNotBlank(), provider,
            minimaxConfigured = prefs.getString("minimax_api_key", "").orEmpty().isNotBlank())
        Text("当前识别：${CallVoiceConfiguration.sttLabel(activeEngine)}",
            style = MaterialTheme.typography.bodySmall)
        if (sttMode == "auto" || sttMode == "groq") {
            OutlinedTextField(value = groqKey, onValueChange = {
                groqKey = it
                prefs.edit().putString("groq_asr_key", it.trim()).apply()
            }, label = { Text("Groq API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("whisper-large-v3-turbo" to "Turbo · 更省",
                    "whisper-large-v3" to "V3 · 更准").forEach { (id, label) ->
                    FilterChip(groqModel == id, {
                        groqModel = id
                        prefs.edit().putString("groq_asr_model", id).apply()
                    }, label = { Text(label) })
                }
            }
            Text("Groq 提供有限免费层；用量超过免费限额后的扣费规则取决于你的账号。只有使用 Groq 时才向它上传录音片段。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (sttMode == "system" && !SpeechRecognizer.isRecognitionAvailable(context))
            Text("系统识别不可用，请改选 Groq 并填写 Key。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (sttMode == "minimax") {
            Text("沿用 MiniMax API Key，仅作为语音识别；角色声音可以来自 ElevenLabs。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text("MiniMax 识别接口") }
            if (advanced) OutlinedTextField(endpoint, {
                endpoint = it
                prefs.edit().putString("minimax_asr_endpoint", it).apply()
            }, label = { Text("自定义接口（可留空）") }, modifier = Modifier.fillMaxWidth())
        }
        if (provider == "elevenlabs") {
            HorizontalDivider()
            Text(if (mode == "agent") "当前使用 Agent 实时通话"
                else "普通 API · 露露机电话模型 + ElevenLabs Voice",
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "收起高级通话模式" else "Agent 模式（高级）")
            }
            if (advanced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode != "agent", {
                        mode = "direct"
                        prefs.edit().putString("voice_call_mode", mode).apply()
                    }, label = { Text("普通 API") })
                    FilterChip(mode == "agent", {
                        mode = "agent"
                        prefs.edit().putString("voice_call_mode", mode).apply()
                    }, label = { Text("实时 Agent") })
                }
                Text("Agent 管理自己的实时收音和识别，独立计费。普通 API 才使用上面的语音识别渠道。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (activeEngine != "system" && mode != "agent") {
            Text("停顿多久开始识别：${silence.toInt()} 毫秒")
            Slider(value = silence, onValueChange = { silence = it },
                onValueChangeFinished = {
                    prefs.edit().putInt("voice_end_silence_ms", silence.toInt()).apply()
                }, valueRange = 300f..1500f)
            Text("收音灵敏度（低阈值更容易听到轻声）")
            Slider(value = threshold, onValueChange = { threshold = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat("voice_vad_threshold", threshold).apply()
                }, valueRange = 150f..1500f)
        }
    }
}
