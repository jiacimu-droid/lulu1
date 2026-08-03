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

private val AdvancedPage = Color.White
private val AdvancedCard = Color(0xFFFCFCFC)
private val AdvancedField = Color(0xFFF4F4F4)
private val AdvancedBorder = Color(0xFFE7E7E7)
private val AdvancedInk = Color(0xFF1D1D1F)
private val AdvancedMuted = Color(0xFF7A7A7E)
private val AdvancedAccent = Color(0xFF292929)

private const val ADVANCED_PREFS = "lulu_advanced_settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluVoiceSettingsScreen(onBack: () -> Unit) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences(ADVANCED_PREFS, Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean("tts_enabled", true)) }
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("tts_auto_speak", true)) }
    var language by remember { mutableStateOf(prefs.getString("tts_language", "zh-CN") ?: "zh-CN") }
    var rate by remember { mutableFloatStateOf(prefs.getFloat("tts_rate", 1.0f)) }
    var pitch by remember { mutableFloatStateOf(prefs.getFloat("tts_pitch", 1.0f)) }

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
            SettingsSectionCard("声音参数") {
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
                Button(
                    onClick = {
                        prefs.edit()
                            .putString("tts_language", language.trim())
                            .putFloat("tts_rate", rate)
                            .putFloat("tts_pitch", pitch)
                            .apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccent),
                ) { Text("保存语音设置") }
            }
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
    }

    AdvancedSettingsScaffold(title = "记忆设置", onBack = onBack) {
        item {
            Text(
                "这里配置记忆线路使用的专用模型，不建立模型存档，也不会改变聊天页当前模型。",
                color = AdvancedMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            SettingsSectionCard("记忆抽取模型") {
                AdvancedTextField("API 地址", extractionUrl, { extractionUrl = it }, "https://.../v1")
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("API 密钥", extractionKey, { extractionKey = it }, "sk-...", password = true)
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("模型名称", extractionModel, { extractionModel = it }, "用于提取事实、情绪与时间线")
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
                    AdvancedTextField("API 地址", embeddingUrl, { embeddingUrl = it }, "https://.../v1")
                    Spacer(Modifier.height(10.dp))
                    AdvancedTextField("API 密钥", embeddingKey, { embeddingKey = it }, "sk-...", password = true)
                    Spacer(Modifier.height(10.dp))
                    AdvancedTextField("模型名称", embeddingModel, { embeddingModel = it }, "例如 text-embedding-3-small")
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
                    AdvancedTextField("API 地址", rerankUrl, { rerankUrl = it }, "https://...")
                    Spacer(Modifier.height(10.dp))
                    AdvancedTextField("API 密钥", rerankKey, { rerankKey = it }, "sk-...", password = true)
                    Spacer(Modifier.height(10.dp))
                    AdvancedTextField("模型名称", rerankModel, { rerankModel = it }, "例如 bge-reranker-v2")
                }
            }
        }
        item {
            Button(
                onClick = ::save,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccent),
            ) { Text("保存记忆设置") }
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
                AdvancedTextField("API 地址", baseUrl, { baseUrl = it }, "https://.../v1")
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("API 密钥", apiKey, { apiKey = it }, "sk-...", password = true)
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("模型名称", model, { model = it }, "例如 flux、dall-e 或自定义模型")
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
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccent),
            ) { Text("保存生图设置") }
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
