package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluCssScaffold
import com.jiacimu.lulu.design.LuluCssTopAppBar
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
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
private val ElevenLabsSpeechModels = ElevenLabsModels.choices
private val MossSpeechModels = listOf(
    "1.5 Flash · 推荐" to MossSpeech.DEFAULT_MODEL,
    "1.0 Pro · 高保真" to "moss-tts-1.0-pro",
    "1.5 Flash · 2026-06-26" to "moss-tts-1.5-flash-2026-06-26",
    "1.0 Pro · 2026-02-07" to "moss-tts-1.0-pro-2026-02-07",
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
    var autoSpeak by remember { mutableStateOf(prefs.getBoolean("tts_auto_speak", false)) }
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
    var mossKey by remember { mutableStateOf(prefs.getString("moss_api_key", "").orEmpty()) }
    var mossModel by remember { mutableStateOf(prefs.getString("moss_tts_model", MossSpeech.DEFAULT_MODEL).orEmpty().ifBlank { MossSpeech.DEFAULT_MODEL }) }
    var mossVoice by remember { mutableStateOf(prefs.getString("moss_voice_id", "").orEmpty()) }
    var elevenKey by remember { mutableStateOf(prefs.getString("eleven_api_key", "").orEmpty()) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == "eleven_api_key") {
                val stored = changed.getString("eleven_api_key", "").orEmpty()
                if (elevenKey != stored) elevenKey = stored
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var elevenVoice by remember { mutableStateOf(prefs.getString("eleven_voice_id", "").orEmpty()) }
    var elevenModel by remember { mutableStateOf(prefs.getString("eleven_tts_model", ElevenLabsModels.DEFAULT).orEmpty().trim().ifBlank { ElevenLabsModels.DEFAULT }) }
    var elevenStability by remember { mutableFloatStateOf(prefs.getFloat("eleven_stability", .5f)) }
    var elevenSimilarity by remember { mutableFloatStateOf(prefs.getFloat("eleven_similarity", .75f)) }
    var testingVoice by remember { mutableStateOf(false) }
    var voiceNotice by remember { mutableStateOf("") }
    // Read and write the same per-character voice keys used by live calls.
    CharacterVoicePreferenceStore.initialize(context)
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var selectedVoiceRole by remember { mutableStateOf("lulu") }
    var musicEnabled by remember { mutableStateOf(prefs.getBoolean("eleven_music_enabled", false)) }
    var musicUseRoleVoice by remember { mutableStateOf(prefs.getBoolean("eleven_music_use_role_voice", false)) }
    var musicModel by remember { mutableStateOf(prefs.getString("eleven_music_model", ElevenLabsMusic.DEFAULT_MODEL).orEmpty().ifBlank { ElevenLabsMusic.DEFAULT_MODEL }) }
    var musicPrompt by remember { mutableStateOf("一首温柔的中文原创小夜曲，轻柔钢琴伴奏，清澈的年轻人声，唱出温暖和陪伴。") }
    var directSingingLyrics by remember {
        mutableStateOf("晚风轻轻吹过窗台，\n我把想念唱给你听。\n你抬头时，我就在这里。")
    }
    var directSingingBusy by remember { mutableStateOf(false) }
    var directSingingNotice by remember { mutableStateOf("") }
    var musicSeconds by remember { mutableFloatStateOf(30f) }
    var generatingMusic by remember { mutableStateOf(false) }
    var musicNotice by remember { mutableStateOf("") }
    var musicSaved by remember(selectedVoiceRole) {
        mutableStateOf(ElevenLabsMusic.latestSong(context, selectedVoiceRole) != null)
    }
    var singingEngine by remember {
        mutableStateOf(ElevenLabsSingingSvc.engine(context))
    }
    var singingAdvancedOpen by remember { mutableStateOf(false) }
    var singingServiceUrl by remember {
        mutableStateOf(prefs.getString(ElevenLabsSingingSvc.KEY_ENDPOINT, "").orEmpty())
    }
    var singingServiceToken by remember {
        mutableStateOf(prefs.getString(ElevenLabsSingingSvc.KEY_TOKEN, "").orEmpty())
    }
    var singingVoiceStyle by remember(selectedVoiceRole) {
        mutableStateOf(ElevenLabsRoleSongPipeline.voiceStyle(context, selectedVoiceRole))
    }
    var musicAdvancedOpen by remember { mutableStateOf(false) }
    var musicFinetune by remember(selectedVoiceRole) {
        mutableStateOf(ElevenLabsMusic.finetuneId(context, selectedVoiceRole))
    }
    val voiceRoleOptions = (listOf("主角" to "lulu") +
        characters.entries.map { (id, character) ->
            character.displayName.ifBlank { id } to id
        }).distinctBy { it.second }
    var sleepVoiceId by remember(selectedVoiceRole) {
        mutableStateOf(CharacterVoicePreferenceStore.sleepVoiceId(selectedVoiceRole))
    }
    var sleepVoiceEnabled by remember(selectedVoiceRole) {
        mutableStateOf(CharacterVoicePreferenceStore.isSleepVoiceEnabled(selectedVoiceRole))
    }
    var phoneOptionsOpen by remember { mutableStateOf(false) }
    var providerAdvancedOpen by remember { mutableStateOf(false) }
    var cloudTasksOpen by remember { mutableStateOf(false) }

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
            .putString("moss_api_key", mossKey.trim())
            .putString("moss_tts_model", mossModel)
            .putString("moss_voice_id", mossVoice.trim())
            .putString("eleven_voice_id", elevenVoice.trim())
            .putString("eleven_tts_model", elevenModel.trim())
            .putFloat("eleven_stability", elevenStability)
            .putFloat("eleven_similarity", elevenSimilarity)
            .putBoolean("eleven_music_enabled", musicEnabled)
            .putBoolean("eleven_music_use_role_voice", musicUseRoleVoice)
            .putString(ElevenLabsSingingSvc.KEY_ENGINE, singingEngine)
            .putString(ElevenLabsSingingSvc.KEY_ENDPOINT, singingServiceUrl.trim())
            .putString(ElevenLabsSingingSvc.KEY_TOKEN, singingServiceToken.trim())
            .putString("eleven_music_model", musicModel)
            .apply()
    }

    LaunchedEffect(
        provider, language, rate, pitch, minimaxEndpoint, minimaxApiKey, minimaxGroupId,
        minimaxModel, minimaxVoiceId, minimaxLanguage, minimaxSpeed, minimaxVolume, minimaxPitch,
        elevenVoice, elevenModel, elevenStability, elevenSimilarity,
        mossKey, mossModel, mossVoice,
        musicEnabled, musicModel, musicUseRoleVoice,
        singingEngine, singingServiceUrl, singingServiceToken,
    ) { saveVoiceSettings() }

    AdvancedSettingsScaffold(cssPage = "VoiceSettings", title = "语音设置", onBack = onBack) {
        item {
            SettingsSectionCard("语音服务") {
                Text("选择电话与语音回复的供应商", color = AdvancedMuted, fontSize = 13.sp)
                AdvancedChoiceField("语音服务商", provider, listOf(
                    "系统语音 · 离线" to "system",
                    "MiniMax" to "minimax",
                    "ElevenLabs" to "elevenlabs",
                    "MOSS" to "moss",
                )) {
                    provider = it
                    providerAdvancedOpen = false
                    voiceNotice = ""
                    prefs.edit().putString("tts_provider", it).putString("voice_call_mode", "direct").apply()
                }
            }
        }
        item {
            SettingsSectionCard("电话线路") {
                TextButton(onClick = { phoneOptionsOpen = !phoneOptionsOpen }) {
                    Text(if (phoneOptionsOpen) "收起电话线路" else "电话线路与实时通话 · 展开")
                }
                if (phoneOptionsOpen) VoiceCallSettings(provider)
            }
        }
        item {
            SettingsSwitchCard(
                title = "启用 TTS",
                subtitle = "关闭后停止当前语音与电话，手动朗读也不可用。",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean("tts_enabled", it).apply()
                    if (!it) {
                        // End an active direct call as well as its playback when TTS is turned off.
                        LuluVoiceCallSession.endCall()
                    }
                },
            )
        }
        item {
            SettingsSwitchCard(
                title = "自动朗读",
                subtitle = "默认关闭。开启后聊天新消息才可能自动朗读。",
                checked = autoSpeak,
                enabled = enabled,
                onCheckedChange = {
                    autoSpeak = it
                    prefs.edit().putBoolean("tts_auto_speak", it).apply()
                },
            )
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
            SettingsSectionCard("MiniMax · 声音") {
                AdvancedTextField("API Key", minimaxApiKey, { minimaxApiKey = it },
                    "填写 MiniMax API Key", password = true)
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("Group ID", minimaxGroupId, { minimaxGroupId = it },
                    "国内账号填写 Group ID")
                Spacer(Modifier.height(10.dp))
                AdvancedChoiceField("语音模型", minimaxModel,
                    MiniMaxSpeechModels.map { it to it }, { minimaxModel = it })
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("默认 Voice ID", minimaxVoiceId, { minimaxVoiceId = it }, "填写声线 ID")
                TextButton(onClick = { providerAdvancedOpen = !providerAdvancedOpen }) {
                    Text(if (providerAdvancedOpen) "收起高级参数" else "更多参数 · 线路 / 语速 / 音调")
                }
                if (providerAdvancedOpen) {
                    AdvancedChoiceField("接口线路", minimaxEndpoint, MiniMaxEndpoints,
                        { minimaxEndpoint = it })
                    Spacer(Modifier.height(10.dp))
                    AdvancedChoiceField("语言增强", minimaxLanguage,
                        MiniMaxLanguages.map { it to it }, { minimaxLanguage = it })
                    Text("语速  ${"%.2f".format(minimaxSpeed)}", color = AdvancedInk)
                    Slider(value = minimaxSpeed, onValueChange = { minimaxSpeed = it },
                        valueRange = 0.5f..2f, enabled = enabled)
                    Text("音量  ${"%.2f".format(minimaxVolume)}", color = AdvancedInk)
                    Slider(value = minimaxVolume, onValueChange = { minimaxVolume = it },
                        valueRange = 0.1f..10f, enabled = enabled)
                    Text("音调  ${minimaxPitch.toInt()}", color = AdvancedInk)
                    Slider(value = minimaxPitch, onValueChange = { minimaxPitch = it },
                        valueRange = -12f..12f, steps = 23, enabled = enabled)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        saveVoiceSettings()
                        testingVoice = true
                        voiceNotice = "正在连接 MiniMax 并生成试听…"
                        scope.launch {
                            speechEngine.previewMiniMax("你好，我是露露。这个声音听起来还合适吗？")
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
        if (provider == "moss") item {
            SettingsSectionCard("MOSS · 配音") {
                AdvancedChoiceField("语音模型", mossModel, MossSpeechModels) { mossModel = it }
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("API Key", mossKey, { mossKey = it }, "填写 MOSS API Key", password = true)
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("默认 Voice ID", mossVoice, { mossVoice = it }, "从 MOSS Create 复制音色 ID")
                Text("官方接口 · " + MossSpeech.ENDPOINT, color = AdvancedMuted, fontSize = 12.sp)
                OutlinedButton(
                    onClick = {
                        saveVoiceSettings()
                        testingVoice = true
                        voiceNotice = "正在连接 MOSS 并试听…"
                        scope.launch {
                            speechEngine.previewMoss("你好，我是露露。这个声音听起来还合适吗？")
                                .onSuccess { voiceNotice = "MOSS 已返回音频，正在播放试听。" }
                                .onFailure { voiceNotice = it.message ?: "试听失败，请检查密钥或音色。" }
                            testingVoice = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !testingVoice && mossKey.isNotBlank() && mossVoice.isNotBlank(),
                ) { Text(if (testingVoice) "正在试听" else "试听当前声音") }
                if (voiceNotice.isNotBlank()) Text(voiceNotice, color = AdvancedMuted, fontSize = 12.sp)
            }
        }
        if (provider == "elevenlabs") item {
            SettingsSectionCard("ElevenLabs · 配音") {
                AdvancedTextField("API Key", elevenKey, {
                    elevenKey = it
                    prefs.edit().putString("eleven_api_key", it.trim()).apply()
                }, "配音、Scribe 和 Eleven Music 共用", password = true)
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("默认 Voice ID", elevenVoice, { elevenVoice = it }, "填写选择的声线 ID")
                Spacer(Modifier.height(10.dp))
                AdvancedChoiceField(
                    label = "语音模型",
                    value = elevenModel,
                    options = if (ElevenLabsSpeechModels.any { it.second == elevenModel }) ElevenLabsSpeechModels
                        else listOf("已保存的模型" to elevenModel) + ElevenLabsSpeechModels,
                    onSelected = { elevenModel = it; prefs.edit().putString("eleven_tts_model", it).apply() },
                )
                if (!ElevenLabsModels.dialogue(elevenModel)) {
                    TextButton(onClick = { providerAdvancedOpen = !providerAdvancedOpen }) {
                        Text(if (providerAdvancedOpen) "收起高级参数" else "更多参数 · 稳定性 / 相似度")
                    }
                    if (providerAdvancedOpen) {
                        Text("稳定性 ${"%.2f".format(elevenStability)}", color = AdvancedInk)
                        Slider(value = elevenStability, onValueChange = { elevenStability = it },
                            valueRange = 0f..1f, enabled = enabled)
                        Text("声线相似度 ${"%.2f".format(elevenSimilarity)}", color = AdvancedInk)
                        Slider(value = elevenSimilarity, onValueChange = { elevenSimilarity = it },
                            valueRange = 0f..1f, enabled = enabled)
                    }
                }
                OutlinedButton(onClick = {
                    saveVoiceSettings(); testingVoice = true; voiceNotice = "正在连接 ElevenLabs 并试听…"
                    scope.launch {
                        speechEngine.previewElevenLabs("你好，我是露露。这个声音听起来还合适吗？")
                            .onSuccess { voiceNotice = "ElevenLabs 已返回音频并播放完成" }
                            .onFailure { voiceNotice = it.message.orEmpty() }
                        testingVoice = false
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = enabled && !testingVoice && elevenKey.isNotBlank() && elevenVoice.isNotBlank()) {
                    Text(if (testingVoice) "正在试听" else "试听当前声音")
                }
                if (voiceNotice.isNotBlank()) Text(voiceNotice, color = AdvancedMuted, fontSize = 12.sp)
            }
        }
        item {
            SettingsSectionCard("角色直接试唱 · ElevenLabs v4") {
                Text("直接用角色自己已有的 ElevenLabs Voice ID 试唱，音频在露露机内生成、播放和保存；" +
                    "不用网页、不用 GPU、不把陌生歌手的声音硬换过来。" +
                    "这是无伴奏短句实验，不代表已经支持指定旋律的完整歌曲。",
                    color = AdvancedMuted, fontSize = 12.sp)
                AdvancedChoiceField("试唱角色", selectedVoiceRole, voiceRoleOptions) {
                    selectedVoiceRole = it
                }
                val directVoiceId = ElevenLabsRoleSongPipeline.roleVoiceId(context, selectedVoiceRole)
                if (directVoiceId == null) {
                    Text("此角色还没有可用的 ElevenLabs Voice ID，请先选好角色原本的音色。",
                        color = AdvancedMuted, fontSize = 12.sp)
                }
                AdvancedTextField("让角色唱的歌词", directSingingLyrics,
                    { directSingingLyrics = it },
                    "写2～240字原创歌词；模型会即兴决定旋律", minLines = 3)
                Text("使用 Eleven v4 的实验性唱歌指令。单次请求消耗 Text to Speech 字符额度，" +
                    "不会发起 Eleven Music 生成，也不会偷偷调用普通变声。",
                    color = AdvancedMuted, fontSize = 12.sp)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !directSingingBusy && elevenKey.isNotBlank() &&
                        directVoiceId != null && directSingingLyrics.trim().length in 2..240,
                    onClick = {
                        saveVoiceSettings()
                        directSingingBusy = true
                        directSingingNotice = "正用角色本人音色直接试唱…"
                        val role = selectedVoiceRole
                        val lyrics = directSingingLyrics
                        scope.launch {
                            runCatching {
                                val file = ElevenLabsDirectSinging.generate(context, role, lyrics)
                                val played = withContext(Dispatchers.IO) {
                                    ElevenLabsMusic.play(ElevenLabsMusic.Song(
                                        file, ElevenLabsDirectSinging.MODEL, false, "",
                                        usedRoleVoice = true))
                                }
                                if (played) "正在播放角色本人的短句试唱；是否唱准请以实际听感为准。"
                                else "试唱已保存，但无法开始播放。"
                            }.onSuccess { directSingingNotice = it }
                                .onFailure { directSingingNotice = it.message ?: "角色直接试唱失败" }
                            directSingingBusy = false
                        }
                    },
                ) {
                    if (directSingingBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (directSingingBusy) "正在试唱" else "用角色本人声音直接唱几句（消耗语音额度）")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !directSingingBusy &&
                        ElevenLabsDirectSinging.latest(context, selectedVoiceRole) != null,
                    onClick = {
                        val role = selectedVoiceRole
                        scope.launch {
                            runCatching {
                                val saved = ElevenLabsDirectSinging.latest(context, role)
                                    ?: error("还没有保存的直接试唱")
                                withContext(Dispatchers.IO) {
                                    ElevenLabsMusic.play(ElevenLabsMusic.Song(
                                        saved, ElevenLabsDirectSinging.MODEL, false, "",
                                        usedRoleVoice = true))
                                }
                            }.onSuccess { directSingingNotice = "正在播放本地试唱，不会再次扣费。" }
                                .onFailure { directSingingNotice = it.message ?: "试唱重播失败" }
                        }
                    },
                ) { Text("重听上次试唱（免费）") }
                if (directSingingNotice.isNotBlank())
                    Text(directSingingNotice, color = AdvancedMuted, fontSize = 12.sp)
            }
        }
        item {
            SettingsSectionCard("角色唱歌 · Eleven Music") {
                Text("独立音乐生成，不改变聊天和电话的 TTS 声音。生成原创歌曲会消耗 Eleven Music 额度。",
                    color = AdvancedMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许角色主动唱歌", modifier = Modifier.weight(1f), color = AdvancedInk, fontSize = 14.sp)
                    Switch(checked = musicEnabled, onCheckedChange = { musicEnabled = it })
                }
                AdvancedChoiceField("音乐模型", musicModel,
                    listOf("Music v2.5（推荐）" to "music_v2_5", "Music v2" to "music_v2"),
                    { musicModel = it })
                Spacer(Modifier.height(10.dp))
                AdvancedChoiceField("选择角色", selectedVoiceRole, voiceRoleOptions) {
                    selectedVoiceRole = it
                }
                val roleVoiceId = ElevenLabsRoleSongPipeline.roleVoiceId(context, selectedVoiceRole)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("使用角色已有 ElevenLabs 音色唱歌", modifier = Modifier.weight(1f),
                        color = AdvancedInk, fontSize = 14.sp)
                    Switch(checked = musicUseRoleVoice, onCheckedChange = { musicUseRoleVoice = it })
                }
                Text(
                    if (musicUseRoleVoice) {
                        if (roleVoiceId == null) "尚未找到该角色的 ElevenLabs Voice ID，请在角色声音设置中选择已有音色。"
                        else "音乐生成→人声分离→角色变声→本地混音；三种接口分别计费。"
                    } else "关闭时使用音乐模型生成的人声，不保证像角色本人。",
                    color = AdvancedMuted, fontSize = 12.sp)
                val readyForRoleSong = singingEngine == ElevenLabsSingingSvc.ENGINE_LEGACY ||
                    (ElevenLabsSingingSvc.endpointIsSafe(singingServiceUrl) &&
                        singingServiceToken.isNotBlank())
                if (musicUseRoleVoice) {
                    Text("只用 ElevenLabs 会员即可继续唱歌；已有 API Key 和角色音色自动复用。" +
                        "这套普通变声不能保证唱歌不跑调，先用原版/角色版对比试听。",
                        color = AdvancedMuted, fontSize = 12.sp)
                    TextButton(onClick = { singingAdvancedOpen = !singingAdvancedOpen }) {
                        Text(if (singingAdvancedOpen) "收起专业歌声实验设置" else
                            "专业歌声转换（高级可选，不需要就别设置）")
                    }
                    if (singingAdvancedOpen) {
                        AdvancedChoiceField("歌声转换引擎", singingEngine,
                            listOf(
                                "ElevenLabs（现有会员可用）" to ElevenLabsSingingSvc.ENGINE_LEGACY,
                                "自建 Seed-VC（需额外 GPU，不推荐当前使用）" to ElevenLabsSingingSvc.ENGINE_SVC,
                            ),
                            { singingEngine = it },
                        )
                    }
                    if (singingAdvancedOpen && singingEngine == ElevenLabsSingingSvc.ENGINE_SVC) {
                        Text("保留原唱旋律、节奏与音高曲线；首次自动用角色现有 ElevenLabs Voice ID 生成短参考音频。" +
                            "需要独立 GPU 服务，未配置绝不偷偷调用旧变声或扣音乐生成费。",
                            color = AdvancedMuted, fontSize = 12.sp)
                        AdvancedTextField("歌声转换服务 HTTPS 地址", singingServiceUrl,
                            { singingServiceUrl = it },
                            "https://你的GPU域名/v1/singing/convert")
                        AdvancedTextField("歌声转换服务访问密钥（一次填写）", singingServiceToken,
                            { singingServiceToken = it },
                            "部署服务时设置的 SVC_API_TOKEN", password = true)
                        if (!readyForRoleSong) {
                            Text("尚未接通歌声 GPU 服务：请按仓库 tools/singing-svc/README.md 部署并配置。" +
                                "目前不可生成角色原声；仍可关闭角色音色开关试听 Eleven Music 原唱。",
                                color = AdvancedMuted, fontSize = 12.sp)
                        } else {
                            Text("已填写服务地址与密钥，实际可用性需在首次 30 秒试听时验证。" +
                                "首次音色参考会少量消耗 ElevenLabs TTS 额度。",
                                color = AdvancedMuted, fontSize = 12.sp)
                        }
                    } else if (singingAdvancedOpen && singingEngine == ElevenLabsSingingSvc.ENGINE_LEGACY) {
                        Text("Speech-to-Speech 是说话变声，可能产生跑调、压嗓和水牛音；" +
                            "仅供对比实验，不再作为推荐的歌声转换方式。",
                            color = AdvancedMuted, fontSize = 12.sp)
                        AdvancedChoiceField(
                            "旧版变声实验参数",
                            singingVoiceStyle,
                            listOf("原始转换" to ElevenLabsRoleSongPipeline.NATURAL,
                                "更灵动（实验）" to ElevenLabsRoleSongPipeline.EXPRESSIVE),
                            {
                                singingVoiceStyle = it
                                prefs.edit()
                                    .putString("eleven_music_voice_style:" + selectedVoiceRole.trim(), it)
                                    .apply()
                            },
                        )
                    }
                }
                TextButton(onClick = { musicAdvancedOpen = !musicAdvancedOpen }) {
                    Text(if (musicAdvancedOpen) "收起 Music Finetune" else "Music Finetune（可选，非修音）")
                }
                if (musicAdvancedOpen) {
                    AdvancedTextField("Music Finetune ID", musicFinetune, {
                        musicFinetune = it
                        prefs.edit().putString("eleven_music_finetune:" + selectedVoiceRole, it.trim()).apply()
                    }, "与普通 ElevenLabs Voice ID 不同")
                    Text("用于训练和固定曲风、人声风格；不能自动修正跑调。没有单独训练过就留空。",
                        color = AdvancedMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("试听歌曲描述", musicPrompt, { musicPrompt = it },
                    "想要的中文歌词主题、曲风与唱法")
                Text("时长 · " + musicSeconds.toInt() + " 秒", color = AdvancedInk, fontSize = 13.sp)
                Slider(value = musicSeconds, onValueChange = { musicSeconds = it },
                    valueRange = 10f..180f, enabled = musicEnabled && !generatingMusic)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = musicEnabled && !generatingMusic && elevenKey.isNotBlank() &&
                        musicPrompt.isNotBlank() && (!musicUseRoleVoice ||
                            (roleVoiceId != null && readyForRoleSong)),
                    onClick = {
                        saveVoiceSettings()
                        generatingMusic = true
                        musicNotice = if (musicUseRoleVoice) "正在生成并转换为角色音色，成功后自动试听…" else "正在生成原创歌曲，完成后自动试听…"
                        val requestedRole = selectedVoiceRole
                        val requestedPrompt = musicPrompt
                        val requestedSeconds = musicSeconds.toInt()
                        scope.launch {
                            runCatching {
                                val song = ElevenLabsMusic.generate(
                                    context, requestedRole, requestedPrompt, requestedSeconds)
                                musicSaved = true
                                val played = withContext(Dispatchers.IO) { ElevenLabsMusic.play(song) }
                                if (played) {
                                    if (song.usedRoleVoice) "角色音色歌曲已保存，正在播放。"
                                    else "原创歌曲已保存并开始播放；当前使用音乐模型人声。"
                                } else "歌曲已保存，但本机未能开始播放。"
                            }.onSuccess { musicNotice = it }
                                .onFailure {
                                    musicSaved = ElevenLabsMusic.latestSong(context, requestedRole) != null
                                    musicNotice = it.message ?: "Eleven Music 生成或播放失败"
                                }
                            generatingMusic = false
                        }
                    },
                ) {
                    if (generatingMusic) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (generatingMusic) "歌曲处理中" else if (musicUseRoleVoice) "生成并试听角色原声（消耗额度）" else "生成并试听原创歌曲（消耗额度）")
                }
                Text("对比同一首歌 · 本地播放，不消耗额度", color = AdvancedMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = musicSaved && !generatingMusic &&
                            ElevenLabsMusic.latestOriginalSong(context, selectedVoiceRole) != null,
                        onClick = {
                            val role = selectedVoiceRole
                            scope.launch {
                                runCatching {
                                    val original = ElevenLabsMusic.latestOriginalSong(context, role)
                                        ?: error("没有找到本首歌曲的音乐模型原版")
                                    withContext(Dispatchers.IO) { ElevenLabsMusic.play(original) }
                                }.onSuccess { musicNotice = "正在播放音乐模型原版，用来检查生成旋律是否跑调；无额外费用。" }
                                    .onFailure { musicNotice = it.message ?: "原版试听失败" }
                            }
                        },
                    ) { Text("听生成原版") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = musicSaved && !generatingMusic &&
                            ElevenLabsMusic.latestRoleVoiceSong(context, selectedVoiceRole) != null,
                        onClick = {
                            val role = selectedVoiceRole
                            scope.launch {
                                runCatching {
                                    val converted = ElevenLabsMusic.latestRoleVoiceSong(context, role)
                                        ?: error("这首歌还没有当前角色的换声版本")
                                    withContext(Dispatchers.IO) { ElevenLabsMusic.play(converted) }
                                }.onSuccess { musicNotice = "正在播放同一首歌的角色音色版；无额外费用。" }
                                    .onFailure { musicNotice = it.message ?: "角色版试听失败" }
                            }
                        },
                    ) { Text("听角色版") }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        ElevenLabsMusic.stop()
                        musicNotice = "已停止播放"
                    },
                ) { Text("停止歌曲") }
                if (musicUseRoleVoice && musicSaved) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !generatingMusic && roleVoiceId != null && readyForRoleSong,
                        onClick = {
                            saveVoiceSettings()
                            generatingMusic = true
                            musicNotice = "复用已保存歌曲换声；分轨与变声仍可能计费，不会重新生成音乐…"
                            val role = selectedVoiceRole
                            scope.launch {
                                runCatching {
                                    val song = ElevenLabsMusic.retryLatestRoleVoice(context, role)
                                    withContext(Dispatchers.IO) { ElevenLabsMusic.play(song) }
                                    "已复用原曲并开始播放角色歌声，无须重新生成音乐或重复分轨。"
                                }.onSuccess { musicNotice = it }
                                    .onFailure { musicNotice = it.message ?: "换声失败，原曲仍在本地保存" }
                                generatingMusic = false
                            }
                        },
                    ) { Text("只重试换声（不重新生成歌曲）") }
                }
                if (musicNotice.isNotBlank()) Text(musicNotice, color = AdvancedMuted, fontSize = 12.sp)
                Text("现成热门歌曲翻唱不属于此生成接口；请使用原创或确有授权的歌词与素材。",
                    color = AdvancedMuted, fontSize = 12.sp)
            }
        }
        if (provider == "elevenlabs") item {
            SettingsSectionCard("哄睡声线 · 按角色") {
                AdvancedChoiceField("选择角色", selectedVoiceRole, voiceRoleOptions) {
                    selectedVoiceRole = it
                }
                Spacer(Modifier.height(10.dp))
                AdvancedTextField("哄睡 Voice ID", sleepVoiceId, {
                    sleepVoiceId = it
                    CharacterVoicePreferenceStore.setSleepVoiceId(selectedVoiceRole, it)
                }, "该角色哄睡时用的 ElevenLabs Voice ID")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("哄睡时切换这条声线", modifier = Modifier.weight(1f),
                        color = AdvancedInk, fontSize = 14.sp)
                    Switch(
                        checked = sleepVoiceEnabled,
                        enabled = sleepVoiceId.isNotBlank(),
                        onCheckedChange = {
                            sleepVoiceEnabled = it
                            CharacterVoicePreferenceStore.setSleepVoiceEnabled(selectedVoiceRole, it)
                        },
                    )
                }
                Text("只修改所选角色；不启用时继续用平时声线。",
                    color = AdvancedMuted, fontSize = 12.sp)
            }
        }
        item {
            SettingsSectionCard("其他") {
                TextButton(onClick = { cloudTasksOpen = !cloudTasksOpen }) {
                    Text(if (cloudTasksOpen) "收起云端任务" else "云端任务（可选）")
                }
                if (cloudTasksOpen) CloudConnectionSettings()
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

    LaunchedEffect(
        vectorEnabled, rerankEnabled, extractionUrl, extractionKey, extractionModel,
        embeddingUrl, embeddingKey, embeddingModel, rerankUrl, rerankKey, rerankModel,
    ) { save() }

    AdvancedSettingsScaffold(cssPage = "MemorySettings", title = "记忆设置", onBack = onBack) {
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
                subtitle = "可增强语义搜索，但每次新查询及新增记忆的向量索引都可能额外请求 API；关闭后仍保留本地记忆与关联召回",
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
                subtitle = "仅对需要比较的向量候选额外调用重排服务；静默后台感知不单独付费重排。优先省钱可关闭",
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
    LaunchedEffect(enabled, baseUrl, apiKey, model, size, negativePrompt) {
        prefs.edit()
            .putBoolean("image_enabled", enabled)
            .putString("image_url", baseUrl.trim())
            .putString("image_key", apiKey.trim())
            .putString("image_model", model.trim())
            .putString("image_size", size.trim())
            .putString("image_negative_prompt", negativePrompt.trim())
            .apply()
    }

    AdvancedSettingsScaffold(cssPage = "ImageSettings", title = "生图设置", onBack = onBack) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSettingsScaffold(
    cssPage: String,
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LuluCssScaffold(
        cssPage = cssPage,
        containerColor = AdvancedPage,
        topBar = {
            LuluCssTopAppBar(
                title = { Text(title, color = AdvancedInk, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = AdvancedInk) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AdvancedPage),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
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
        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
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
