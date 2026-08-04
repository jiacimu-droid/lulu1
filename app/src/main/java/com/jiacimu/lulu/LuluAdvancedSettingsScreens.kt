package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val AdvancedPage = Color.White
private val AdvancedCard = Color(0xFFFCFCFC)
private val AdvancedField = Color(0xFFF4F4F4)
private val AdvancedBorder = Color(0xFFE7E7E7)
private val AdvancedInk = Color(0xFF1D1D1F)
private val AdvancedMuted = Color(0xFF7A7A7E)
private val AdvancedAccent = Color(0xFF292929)

private const val ADVANCED_PREFS = "lulu_advanced_settings"

private val MiniMaxEndpoints = listOf(
    "国内新版线路（推荐）" to "https://api.minimaxi.com/v1/t2a_v2",
    "国内兼容线路" to "https://api.minimax.chat/v1/t2a_v2",
    "国际线路" to "https://api.minimax.io/v1/t2a_v2",
    "国际低延迟线路" to "https://api-uw.minimax.io/v1/t2a_v2",
)
private val MiniMaxSpeechModels = listOf(
    "speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
    "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo",
)
private val MiniMaxLanguages = listOf(
    "auto", "Chinese", "Chinese,Yue", "English", "Japanese", "Korean", "French", "German",
    "Spanish", "Portuguese", "Russian", "Arabic", "Italian", "Turkish", "Dutch", "Ukrainian",
    "Vietnamese", "Indonesian", "Thai", "Polish", "Romanian", "Greek", "Czech", "Finnish",
    "Hindi", "Bulgarian", "Danish", "Hebrew", "Malay", "Persian", "Slovak", "Swedish",
    "Croatian", "Filipino", "Hungarian", "Norwegian", "Slovenian", "Catalan", "Nynorsk",
    "Tamil", "Afrikaans",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluVoiceSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences(ADVANCED_PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val speechEngine = remember { LuluSpeechEngine(context) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("tts_enabled", true)) }
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("tts_auto_speak", true)) }
    var provider by remember { mutableStateOf(prefs.getString("tts_provider", "system") ?: "system") }
    var language by remember { mutableStateOf(prefs.getString("tts_language", "zh-CN") ?: "zh-CN") }
    var rate by remember { mutableFloatStateOf(prefs.getFloat("tts_rate", 1.0f)) }
    var pitch by remember { mutableFloatStateOf(prefs.getFloat("tts_pitch", 1.0f)) }
    var minimaxEndpoint by remember { mutableStateOf(prefs.getString("minimax_endpoint", LuluSpeechEngine.DEFAULT_MINIMAX_ENDPOINT) ?: "") }
    var minimaxApiKey by remember { mutableStateOf(prefs.getString("minimax_api_key", "") ?: "") }
    var minimaxGroupId by remember { mutableStateOf(prefs.getString("minimax_group_id", "") ?: "") }
    var minimaxModel by remember { mutableStateOf(prefs.getString("minimax_model", "speech-2.8-turbo") ?: "speech-2.8-turbo") }
    var minimaxVoiceId by remember { mutableStateOf(prefs.getString("minimax_voice_id", "") ?: "") }
    var minimaxLanguage by remember { mutableStateOf(prefs.getString("minimax_language_boost", "auto") ?: "auto") }
    var minimaxSpeed by remember { mutableFloatStateOf(prefs.getFloat("minimax_speed", 1f)) }
    var minimaxVolume by remember { mutableFloatStateOf(prefs.getFloat("minimax_volume", 1f)) }
    var minimaxPitch by remember { mutableFloatStateOf(prefs.getInt("minimax_pitch", 0).toFloat()) }
    var testingVoice by remember { mutableStateOf(false) }
    var voiceNotice by remember { mutableStateOf("") }

    DisposableEffect(speechEngine) {
        onDispose { speechEngine.shutdown() }
    }

    fun saveVoiceSettings() {
        prefs.edit()
            .putString("tts_provider", provider)
            .putString("tts_language", language.trim())
            .putFloat("tts_rate", rate)
            .putFloat("tts_pitch", pitch)
            .putString("minimax_endpoint", minimaxEndpoint.trim())
            .putString("minimax_api_key", minimaxApiKey.trim())
            .putString("minimax_group_id", minimaxGroupId.trim())
            .putString("minimax_model", minimaxModel.trim())
            .putString("minimax_voice_id", minimaxVoiceId.trim())
            .putString("minimax_language_boost", minimaxLanguage.trim().ifBlank { "auto" })
            .putFloat("minimax_speed", minimaxSpeed)
            .putFloat("minimax_volume", minimaxVolume)
            .putInt("minimax_pitch", minimaxPitch.toInt())
            .apply()
    }

    AdvancedSettingsScaffold(title = "语音设置", onBack = onBack) {
        item {
            SettingsSwitchCard(
                title = "启用 TTS",
                subtitle = "角色回复时允许使用系统语音朗读",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean("tts_enabled", it).apply()
                },
            )
        }
        item {
            SettingsSwitchCard(
                title = "自动朗读",
                subtitle = "电话和支持语音的场景自动播放角色回复",
                checked = autoSpeak,
                enabled = enabled,
                onCheckedChange = {
                    autoSpeak = it
                    prefs.edit().putBoolean("tts_auto_speak", it).apply()
                },
            )
        }
        item {
            SettingsSectionCard("语音服务") {
                Text("选择朗读角色回复的声音来源", color = AdvancedMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == "system",
                        onClick = { provider = "system"; prefs.edit().putString("tts_provider", provider).apply() },
                        label = { Text("系统语音") },
                    )
                    FilterChip(
                        selected = provider == "minimax",
                        onClick = { provider = "minimax"; prefs.edit().putString("tts_provider", provider).apply() },
                        label = { Text("MiniMax") },
                    )
                }
            }
        }
        if (provider == "system") item {
            SettingsSectionCard("系统声音参数") {
                AdvancedTextField("语言代码", language, { language = it }, "例如 zh-CN")
                Spacer(Modifier.height(18.dp))
                Text("语速  ${"%.2f".format(rate)}", color = AdvancedInk, fontWeight = FontWeight.Medium)
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { prefs.edit().putFloat("tts_rate", rate).apply() },
                    valueRange = 0.5f..1.5f,
                    enabled = enabled,
                )
                Text("音调  ${"%.2f".format(pitch)}", color = AdvancedInk, fontWeight = FontWeight.Medium)
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    onValueChangeFinished = { prefs.edit().putFloat("tts_pitch", pitch).apply() },
                    valueRange = 0.5f..1.5f,
                    enabled = enabled,
                )
            }
        }
        if (provider == "minimax") item {
            SettingsSectionCard("MiniMax 接口") {
                AdvancedChoiceField(
                    label = "接口线路",
                    value = minimaxEndpoint,
                    options = MiniMaxEndpoints,
                    onSelected = { minimaxEndpoint = it },
                )
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("API Key", minimaxApiKey, { minimaxApiKey = it }, "填写 MiniMax API Key")
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("Group ID", minimaxGroupId, { minimaxGroupId = it }, "国内账号请填写 Group ID")
                Spacer(Modifier.height(10.dp))
                AdvancedChoiceField(
                    label = "语音模型",
                    value = minimaxModel,
                    options = MiniMaxSpeechModels.map { it to it },
                    onSelected = { minimaxModel = it },
                )
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("Voice ID", minimaxVoiceId, { minimaxVoiceId = it }, "填写系统音色或克隆音色 ID")
                Spacer(Modifier.height(10.dp))
                AdvancedChoiceField(
                    label = "语言增强",
                    value = minimaxLanguage,
                    options = MiniMaxLanguages.map { it to it },
                    onSelected = { minimaxLanguage = it },
                )
                Spacer(Modifier.height(18.dp))
                Text("语速  ${"%.2f".format(minimaxSpeed)}", color = AdvancedInk, fontWeight = FontWeight.Medium)
                Slider(value = minimaxSpeed, onValueChange = { minimaxSpeed = it }, valueRange = 0.5f..2f, enabled = enabled)
                Text("音量  ${"%.2f".format(minimaxVolume)}", color = AdvancedInk, fontWeight = FontWeight.Medium)
                Slider(value = minimaxVolume, onValueChange = { minimaxVolume = it }, valueRange = 0.1f..10f, enabled = enabled)
                Text("音调  ${minimaxPitch.toInt()}", color = AdvancedInk, fontWeight = FontWeight.Medium)
                Slider(value = minimaxPitch, onValueChange = { minimaxPitch = it }, valueRange = -12f..12f, steps = 23, enabled = enabled)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        saveVoiceSettings()
                        testingVoice = true
                        voiceNotice = "正在连接 MiniMax 并生成试听…"
                        scope.launch {
                            speechEngine.previewMiniMax("主人你好，我是露露。这个声音听起来还合适吗？")
                                .onSuccess { voiceNotice = "试听成功，当前 MiniMax 配置已经接通。" }
                                .onFailure { error -> voiceNotice = error.message ?: "试听失败，请检查接口配置。" }
                            testingVoice = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !testingVoice && minimaxApiKey.isNotBlank() && minimaxVoiceId.isNotBlank(),
                ) {
                    if (testingVoice) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testingVoice) "正在生成试听" else "试听当前声音")
                }
                if (voiceNotice.isNotBlank()) {
                    Text(voiceNotice, color = AdvancedMuted, fontSize = 12.sp)
                }
            }
        }
        item {
            Button(
                onClick = ::saveVoiceSettings,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccent, contentColor = Color.White),
            ) { Text("保存语音设置") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluMemorySettingsScreen(onBack: () -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences(ADVANCED_PREFS, Context.MODE_PRIVATE)
    var vectorEnabled by remember { mutableStateOf(prefs.getBoolean("memory_vector_enabled", false)) }
    var rerankEnabled by remember { mutableStateOf(prefs.getBoolean("memory_rerank_enabled", false)) }
    var extractionUrl by remember { mutableStateOf(prefs.getString("memory_extract_url", "") ?: "") }
    var extractionKey by remember { mutableStateOf(prefs.getString("memory_extract_key", "") ?: "") }
    var extractionModel by remember { mutableStateOf(prefs.getString("memory_extract_model", "") ?: "") }
    var embeddingUrl by remember { mutableStateOf(prefs.getString("memory_embedding_url", "") ?: "") }
    var embeddingKey by remember { mutableStateOf(prefs.getString("memory_embedding_key", "") ?: "") }
    var embeddingModel by remember { mutableStateOf(prefs.getString("memory_embedding_model", "") ?: "") }
    var rerankUrl by remember { mutableStateOf(prefs.getString("memory_rerank_url", "") ?: "") }
    var rerankKey by remember { mutableStateOf(prefs.getString("memory_rerank_key", "") ?: "") }
    var rerankModel by remember { mutableStateOf(prefs.getString("memory_rerank_model", "") ?: "") }
    var saveNotice by remember { mutableStateOf("") }

    fun save() {
        prefs.edit()
            .putBoolean("memory_vector_enabled", vectorEnabled)
            .putBoolean("memory_rerank_enabled", rerankEnabled)
            .putString("memory_extract_url", extractionUrl.trim())
            .putString("memory_extract_key", extractionKey.trim())
            .putString("memory_extract_model", extractionModel.trim())
            .putString("memory_embedding_url", embeddingUrl.trim())
            .putString("memory_embedding_key", embeddingKey.trim())
            .putString("memory_embedding_model", embeddingModel.trim())
            .putString("memory_rerank_url", rerankUrl.trim())
            .putString("memory_rerank_key", rerankKey.trim())
            .putString("memory_rerank_model", rerankModel.trim())
            .apply()
        saveNotice = buildString {
            append("已保存")
            append("\n记忆抽取：${extractionModel.ifBlank { "沿用当前聊天模型" }}")
            append("\nEmbedding：${if (!vectorEnabled) "未启用" else embeddingModel.ifBlank { "未选择模型，将使用本地召回" }}")
            append("\nRerank：${if (!rerankEnabled) "未启用" else rerankModel.ifBlank { "未选择模型，将跳过重排" }}")
        }
    }

    AdvancedSettingsScaffold(title = "记忆设置", onBack = onBack) {
        item {
            SettingsSectionCard("记忆抽取模型") {
                SavedConfigurationModelPicker(
                    pickerKey = "memory-extraction",
                    currentBaseUrl = extractionUrl,
                    currentModel = extractionModel,
                    onConfigurationSelected = { extractionUrl = it.baseUrl; extractionKey = it.apiKey },
                    onModelSelected = { extractionModel = it },
                )
            }
        }
        item {
            SettingsSwitchCard(
                title = "向量记忆",
                subtitle = "使用 Embedding 做语义检索",
                checked = vectorEnabled,
                onCheckedChange = { vectorEnabled = it },
            )
        }
        if (vectorEnabled) {
            item {
                SettingsSectionCard("Embedding 模型") {
                    SavedConfigurationModelPicker(
                        pickerKey = "memory-embedding",
                        currentBaseUrl = embeddingUrl,
                        currentModel = embeddingModel,
                        onConfigurationSelected = { embeddingUrl = it.baseUrl; embeddingKey = it.apiKey },
                        onModelSelected = { embeddingModel = it },
                    )
                }
            }
        }
        item {
            SettingsSwitchCard(
                title = "Rerank 重排",
                subtitle = "对向量召回结果进行二次相关性排序",
                checked = rerankEnabled,
                onCheckedChange = { rerankEnabled = it },
            )
        }
        if (rerankEnabled) {
            item {
                SettingsSectionCard("Rerank 模型") {
                    SavedConfigurationModelPicker(
                        pickerKey = "memory-rerank",
                        currentBaseUrl = rerankUrl,
                        currentModel = rerankModel,
                        onConfigurationSelected = { rerankUrl = it.baseUrl; rerankKey = it.apiKey },
                        onModelSelected = { rerankModel = it },
                    )
                }
            }
        }
        item {
            Button(
                onClick = ::save,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccent, contentColor = Color.White),
            ) { Text("保存记忆设置") }
        }
        if (saveNotice.isNotBlank()) item {
            Surface(color = Color(0xFFF1F1F1), shape = RoundedCornerShape(14.dp)) {
                Text(saveNotice, Modifier.fillMaxWidth().padding(13.dp), color = AdvancedInk)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluImageSettingsScreen(onBack: () -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences(ADVANCED_PREFS, Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean("image_enabled", false)) }
    var baseUrl by remember { mutableStateOf(prefs.getString("image_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("image_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("image_model", "") ?: "") }
    var size by remember { mutableStateOf(prefs.getString("image_size", "1024x1024") ?: "1024x1024") }
    var negativePrompt by remember { mutableStateOf(prefs.getString("image_negative_prompt", "") ?: "") }
    var saveNotice by remember { mutableStateOf("") }

    AdvancedSettingsScaffold(title = "生图设置", onBack = onBack) {
        item {
            SettingsSwitchCard(
                title = "启用生图",
                subtitle = "允许角色和功能页调用独立的图片生成接口",
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }
        item {
            SettingsSectionCard("生图接口") {
                SavedConfigurationModelPicker(
                    pickerKey = "image-generation",
                    currentBaseUrl = baseUrl,
                    currentModel = model,
                    onConfigurationSelected = { baseUrl = it.baseUrl; apiKey = it.apiKey },
                    onModelSelected = { model = it },
                )
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("默认尺寸", size, { size = it }, "例如 1024x1024")
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("默认负面提示词", negativePrompt, { negativePrompt = it }, "可留空", minLines = 3)
            }
        }
        item {
            Button(
                onClick = {
                    prefs.edit()
                        .putBoolean("image_enabled", enabled)
                        .putString("image_url", baseUrl.trim())
                        .putString("image_key", apiKey.trim())
                        .putString("image_model", model.trim())
                        .putString("image_size", size.trim())
                        .putString("image_negative_prompt", negativePrompt.trim())
                        .apply()
                    saveNotice = if (model.isBlank()) "已保存；尚未选择生图模型" else "已保存并选择：$model"
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccent, contentColor = Color.White),
            ) { Text("保存生图设置") }
        }
        if (saveNotice.isNotBlank()) item {
            Surface(color = Color(0xFFF1F1F1), shape = RoundedCornerShape(14.dp)) {
                Text(saveNotice, Modifier.fillMaxWidth().padding(13.dp), color = AdvancedInk)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        containerColor = AdvancedPage,
        topBar = {
            TopAppBar(
                title = { Text(title, color = AdvancedInk, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = AdvancedInk) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AdvancedPage),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AdvancedCard,
        border = BorderStroke(1.dp, AdvancedBorder),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = AdvancedInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AdvancedCard,
        border = BorderStroke(1.dp, AdvancedBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (enabled) AdvancedInk else AdvancedMuted, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = AdvancedMuted, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = AdvancedAccent),
            )
        }
    }
}

@Composable
private fun AdvancedTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = AdvancedMuted) },
        minLines = minLines,
        maxLines = if (minLines > 1) 6 else 1,
        singleLine = minLines == 1,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AdvancedField,
            unfocusedContainerColor = AdvancedField,
            focusedBorderColor = AdvancedInk,
            unfocusedBorderColor = AdvancedBorder,
            focusedTextColor = AdvancedInk,
            unfocusedTextColor = AdvancedInk,
        ),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun AdvancedChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == value }?.first ?: value
    Column {
        Text(label, color = AdvancedMuted, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Text(selectedLabel, Modifier.weight(1f), color = AdvancedInk)
                Text("▾", color = AdvancedMuted)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (title, savedValue) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(title, fontWeight = if (savedValue == value) FontWeight.Bold else FontWeight.Normal)
                                if (title != savedValue) Text(savedValue, color = AdvancedMuted, fontSize = 11.sp)
                            }
                        },
                        onClick = { onSelected(savedValue); expanded = false },
                    )
                }
            }
        }
    }
}
