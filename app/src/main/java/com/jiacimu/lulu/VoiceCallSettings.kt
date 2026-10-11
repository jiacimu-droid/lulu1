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
    var silence by remember { mutableFloatStateOf(prefs.getInt("voice_end_silence_ms", 1700).toFloat().coerceIn(850f, 3200f)) }
    var advanced by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(prefs.getString("minimax_asr_endpoint", "").orEmpty()) }
    var sttMode by remember { mutableStateOf(CallVoiceConfiguration.normalizeSttMode(prefs.getString("call_stt_mode", "system"))) }
    LaunchedEffect(Unit) {
        // Older installs could store "auto"; keep their former phone-first behavior
        // without surprising switches to billable cloud transcription.
        if (prefs.getString("call_stt_mode", null) != sttMode)
            prefs.edit().putString("call_stt_mode", sttMode).apply()
    }
    var groqKey by remember { mutableStateOf(prefs.getString("groq_asr_key", "").orEmpty()) }
    var groqModel by remember { mutableStateOf(prefs.getString("groq_asr_model", "whisper-large-v3-turbo").orEmpty()) }
    var elevenSttModel by remember {
        mutableStateOf(CallVoiceConfiguration.elevenSttModel(prefs.getString("eleven_asr_model", null)))
    }

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
        Text("明确选择识别渠道，不自动切换。ElevenLabs 实时 Agent 使用自己的实时识别；普通电话使用这里选定的渠道。",
            style = MaterialTheme.typography.bodySmall)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("elevenlabs" to "ElevenLabs Scribe", "system" to "手机识别",
                "groq" to "Groq Whisper", "minimax" to "MiniMax").forEach { (id, title) ->
                FilterChip(selected = sttMode == id, onClick = {
                    sttMode = id
                    prefs.edit().putString("call_stt_mode", id).apply()
                }, label = { Text(title) })
            }
        }
        val activeEngine = CallVoiceConfiguration.resolveSttEngine(sttMode)
        Text("当前识别：${CallVoiceConfiguration.sttLabel(activeEngine)}",
            style = MaterialTheme.typography.bodySmall)
        if (sttMode == "groq") {
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
        if (sttMode == "elevenlabs") {
            Text("ElevenLabs 识别模型", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    CallVoiceConfiguration.SCRIBE_V2 to "Scribe v2 · 准确优先",
                    CallVoiceConfiguration.SCRIBE_V2_REALTIME to "Scribe v2 Realtime · 边说边转",
                ).forEach { (id, label) ->
                    FilterChip(selected = elevenSttModel == id, onClick = {
                        elevenSttModel = id
                        prefs.edit().putString("eleven_asr_model", id).apply()
                    }, label = { Text(label) })
                }
            }
            Text(
                if (elevenSttModel == CallVoiceConfiguration.SCRIBE_V2_REALTIME)
                    "实时版经 WebSocket 连续接收收音、更新临时字幕；只有收到最终识别结果才交给角色回复。"
                else "普通版保留现有 Scribe v2：录音分段后上传识别，适合优先对比完整度。",
                style = MaterialTheme.typography.bodySmall)
            Text("两种模型共用你的 ElevenLabs API Key（不需要 Voice ID）；均可能消耗识别积分。仅影响普通 API 电话，新选择从下一通电话生效。实时 Agent 的识别由 Agent 单独配置。",
                style = MaterialTheme.typography.bodySmall)
            var elevenKey by remember { mutableStateOf(prefs.getString("eleven_api_key", "").orEmpty()) }
            OutlinedTextField(value = elevenKey, onValueChange = {
                elevenKey = it
                prefs.edit().putString("eleven_api_key", it.trim()).apply()
            }, label = { Text("ElevenLabs API Key（与配音共用）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (sttMode == "system") {
            Text("手机系统 SpeechRecognizer 由手机语音服务收音、断句与降噪，可能联网。与 ElevenLabs 完全分离；长句完整率会受手机系统识别引擎限制。",
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
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("哄睡 · 无人回应也继续轻声聊天", style = MaterialTheme.typography.bodyMedium)
                Text("角色会主动持续轻声说话；从你最后一句真正说完的话起计时，约一小时后自动结束电话，无需起床挂断。模型和语音生成可能消耗 API 积分。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (activeEngine != "system" && !CallVoiceConfiguration.usesAgent(provider, mode)) {
            Text("语句结束停顿：${silence.toInt()} 毫秒（长句会自动延长换气等待）")
            Slider(value = silence, onValueChange = { silence = it },
                onValueChangeFinished = {
                    prefs.edit().putInt("voice_end_silence_ms", silence.toInt()).apply()
                }, valueRange = 850f..3200f)
            Text("收音灵敏度（低阈值更容易听到轻声）")
            Slider(value = threshold, onValueChange = { threshold = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat("voice_vad_threshold", threshold).apply()
                }, valueRange = 150f..1500f)
        }
    }
}
