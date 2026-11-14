package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
internal fun VoiceCallSettings(provider: String) {
    val context = LocalContext.current
    val downloadScope = rememberCoroutineScope()
    var selectedLocalModel by remember { mutableStateOf(LocalWhisperModels.selected(context)) }
    var downloadInProgress by remember { mutableStateOf<LocalWhisperModels.Model?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadNotice by remember { mutableStateOf("") }
    var modelFilesRevision by remember { mutableIntStateOf(0) }
    val prefs = remember { context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE) }
    // Old Agent preference is retired. Existing installs use normal phone calls.
    LaunchedEffect(provider) { if (prefs.getString("voice_call_mode", "direct") != "direct")
        prefs.edit().putString("voice_call_mode", "direct").apply() }
    var ringing by remember { mutableStateOf(prefs.getBoolean("voice_call_ringtone_enabled", true)) }
    var acousticCuesEnabled by remember {
        mutableStateOf(prefs.getBoolean("call_acoustic_cues_enabled", true))
    }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat("voice_vad_threshold", 195f)) }
    var silence by remember { mutableFloatStateOf(prefs.getInt("voice_end_silence_ms", 2_500).toFloat().coerceIn(1_450f, 4_500f)) }
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("本地声音线索", style = MaterialTheme.typography.bodyMedium)
                Text("通话中分析音高、录音电平与停顿；不判断情绪、不保存录音、不额外调用 API。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = acousticCuesEnabled, onCheckedChange = { enabled ->
                acousticCuesEnabled = enabled
                prefs.edit().putBoolean("call_acoustic_cues_enabled", enabled).apply()
            })
        }
        Text("你的声音如何转成文字", style = MaterialTheme.typography.titleSmall)
        SttSettingsDropdown(
            label = "识别服务",
            selectedId = sttMode,
            options = listOf("local" to "本地 Whisper · 离线", "elevenlabs" to "ElevenLabs Scribe",
                "groq" to "Groq Whisper", "minimax" to "MiniMax"),
            onSelect = { id ->
                sttMode = id
                prefs.edit().putString("call_stt_mode", id).apply()
            },
        )
        val activeEngine = CallVoiceConfiguration.resolveSttEngine(sttMode)
        if (sttMode == "groq") {
            OutlinedTextField(value = groqKey, onValueChange = {
                groqKey = it
                prefs.edit().putString("groq_asr_key", it.trim()).apply()
            }, label = { Text("Groq API Key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            SttSettingsDropdown(
                label = "Groq 识别模型",
                selectedId = groqModel,
                options = listOf("whisper-large-v3-turbo" to "Whisper Large v3 Turbo",
                    "whisper-large-v3" to "Whisper Large v3"),
                onSelect = { id ->
                    groqModel = id
                    prefs.edit().putString("groq_asr_model", id).apply()
                },
            )
            Text("Groq 提供有限免费层；用量超过免费限额后的扣费规则取决于你的账号。只有使用 Groq 时才向它上传录音片段。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (sttMode == "elevenlabs") {
            SttSettingsDropdown(
                label = "ElevenLabs 识别模型",
                selectedId = elevenSttModel,
                options = CallVoiceConfiguration.elevenSttOptions,
                onSelect = { id ->
                    elevenSttModel = id
                    prefs.edit().putString("eleven_asr_model", id).apply()
                },
            )
            Text(
                when (elevenSttModel) {
                    CallVoiceConfiguration.SCRIBE_V3_LIVE ->
                        "新版实时识别 · 边说边显示字幕，并尝试判断你是否真正说完。"
                    CallVoiceConfiguration.SCRIBE_V2_REALTIME ->
                        "实时识别 · 边说边显示字幕，按停顿识别发言。"
                    else -> "普通录音识别 · 自动检测停顿后上传语音片段，不是实时字幕。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("官方 API 标价：普通 v2 / v3 均 $0.20/小时；实时 v2 / v3 Live 均 $0.22/小时。额外功能与账户优惠另计。",
                style = MaterialTheme.typography.bodySmall)
            Text("使用已配置的 ElevenLabs API Key，无需 Voice ID；正在识别的语音不会被中途改到另一个模型。",
                style = MaterialTheme.typography.bodySmall)
            var elevenKey by remember { mutableStateOf(prefs.getString("eleven_api_key", "").orEmpty()) }
            OutlinedTextField(value = elevenKey, onValueChange = {
                elevenKey = it
                prefs.edit().putString("eleven_api_key", it.trim()).apply()
            }, label = { Text("ElevenLabs API Key（与配音共用）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (sttMode == "local") {
            Text("完全离线识别，不上传你的录音，不扣除 ElevenLabs 识别额度。模型只需下载一次。",
                style = MaterialTheme.typography.bodySmall)
            SttSettingsDropdown(
                label = "本地 Whisper 模型",
                selectedId = selectedLocalModel.id,
                options = LocalWhisperModels.Model.entries.map { it.id to "${it.title} · ${it.sizeLabel}" },
                onSelect = { id ->
                    selectedLocalModel = LocalWhisperModels.Model.from(id)
                    prefs.edit().putString("local_whisper_model", selectedLocalModel.id).apply()
                },
            )
            LocalWhisperModels.Model.entries.forEach { model ->
                val installed = remember(modelFilesRevision, model) {
                    LocalWhisperModels.installedFile(context, model) != null
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${model.sizeLabel} · ${if (installed) "已下载" else "未下载"}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (installed) {
                        TextButton(onClick = {
                            if (downloadInProgress == null) {
                                if (LocalWhisperModels.remove(context, model)) {
                                    modelFilesRevision++
                                    downloadNotice = "${model.title} 已删除"
                                } else downloadNotice = "删除失败，请检查手机存储"
                            }
                        }, enabled = downloadInProgress == null) { Text("删除") }
                    } else {
                        TextButton(
                            enabled = downloadInProgress == null,
                            onClick = {
                                downloadInProgress = model
                                downloadProgress = 0f
                                downloadNotice = "正在下载 ${model.title}"
                                downloadScope.launch {
                                    try {
                                        LocalWhisperModels.download(context, model) { done, total ->
                                            withContext(Dispatchers.Main) {
                                                downloadProgress = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                            }
                                        }
                                        modelFilesRevision++
                                        downloadNotice = "${model.title} 已下载，可以离线通话"
                                    } catch (cancel: CancellationException) {
                                        downloadNotice = "下载已中止，可以重试"
                                        throw cancel
                                    } catch (error: Exception) {
                                        downloadNotice = "下载失败：${error.message ?: "请检查网络和存储空间"}"
                                    } finally {
                                        downloadInProgress = null
                                    }
                                }
                            }
                        ) { Text(if (downloadInProgress == model) "下载中" else "下载") }
                    }
                }
            }
            if (downloadInProgress != null) {
                if (downloadProgress > 0f) LinearProgressIndicator(
                    progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (downloadNotice.isNotBlank()) Text(downloadNotice,
                style = MaterialTheme.typography.bodySmall)
            Text("选择的模型会在下一通电话生效；模型未下载时不会暗中改用付费识别。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (sttMode == "minimax") {
            Text("沿用 MiniMax API Key，仅作为语音识别；角色声音可以来自 ElevenLabs。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = !advanced }) { Text("MiniMax 识别接口") }
            if (advanced) OutlinedTextField(endpoint, {
                endpoint = it
                prefs.edit().putString("minimax_asr_endpoint", it).apply()
            }, label = { Text("自定义接口（可留空）") }, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("哄睡 · 无人回应也继续轻声聊天", style = MaterialTheme.typography.bodyMedium)
                Text("角色会主动持续轻声说话；从你最后一句真正说完的话起计时，约30分钟未收到你的新发言后，在当前语音播完时自动结束电话，无需起床挂断。模型和语音生成可能消耗 API 积分。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (activeEngine != "system") {
            Text("思考停顿宽容：${silence.toInt()} 毫秒（说话断续时可调高，停下后的回复也会稍晚）")
            Slider(value = silence, onValueChange = { silence = it },
                onValueChangeFinished = {
                    prefs.edit().putInt("voice_end_silence_ms", silence.toInt()).apply()
                }, valueRange = 1_450f..4_500f)
            if (sttMode != "elevenlabs" || !CallVoiceConfiguration.isElevenRealtime(elevenSttModel)) {
            Text("本地分段收音灵敏度（普通 Scribe 也使用；调低可能更容易拾取轻声，但也可能捕获噪声）")
            Slider(value = threshold, onValueChange = { threshold = it },
                onValueChangeFinished = {
                    prefs.edit().putFloat("voice_vad_threshold", threshold).apply()
                }, valueRange = 80f..1200f)
            }
        }
    }
}

/** One quiet selector replaces scrolling chips while retaining explicit, persisted choices. */
@Composable
private fun SttSettingsDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == selectedId }?.second
        ?: options.firstOrNull()?.second.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(selectedName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("⌄", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { expanded = false; onSelect(id) },
                        trailingIcon = if (id == selectedId) {
                            { Text("✓", color = MaterialTheme.colorScheme.primary) }
                        } else null,
                    )
                }
            }
        }
    }
}
