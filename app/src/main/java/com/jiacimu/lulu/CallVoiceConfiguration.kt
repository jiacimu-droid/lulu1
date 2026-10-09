package com.jiacimu.lulu

import android.content.Context
import android.speech.SpeechRecognizer
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import java.net.URI

internal object CallVoiceConfiguration {
    fun provider(context: Context): String = context.getSharedPreferences("lulu_advanced_settings", 0).getString("tts_provider", "system").orEmpty()
    fun usesAgent(provider: String, mode: String?): Boolean = provider == "elevenlabs" && mode == "agent"
    /** Speech-to-text is independent of the provider that speaks for the character. */
    fun resolveSttEngine(mode: String, androidAvailable: Boolean, groqConfigured: Boolean,
        ttsProvider: String, minimaxConfigured: Boolean = ttsProvider == "minimax"): String = when (mode) {
        "system" -> "system"
        "groq" -> "groq"
        "minimax" -> "minimax"
        else -> when {
            minimaxConfigured -> "minimax" // preserve an already working ASR account
            androidAvailable -> "system" // free or OS-managed service, not necessarily offline
            groqConfigured -> "groq"
            else -> "unavailable"
        }
    }
    fun sttEngine(context: Context): String {
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        return resolveSttEngine(p.getString("call_stt_mode", "auto").orEmpty(),
            available, p.getString("groq_asr_key", "").orEmpty().isNotBlank(), provider(context),
            minimaxConfigured = p.getString("minimax_api_key", "").orEmpty().isNotBlank())
    }
    fun sttLabel(engine: String): String = when (engine) {
        "groq" -> "Groq Whisper"
        "system" -> "手机系统识别"
        "minimax" -> "MiniMax 识别"
        else -> "尚未配置"
    }
    fun label(provider: String): String = when (provider) { "minimax" -> "MiniMax"; "elevenlabs" -> "ElevenLabs"; else -> "系统语音" }
    fun miniAsrEndpoint(ttsEndpoint: String): String {
        val uri = URI(ttsEndpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "MiniMax 接口必须是 HTTPS 地址" }
        return URI("https", null, uri.host, uri.port, "/v1/speech_to_text", null, null).toString()
    }
    fun preflight(context: Context, characterId: String): String? {
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        if (!p.getBoolean("tts_enabled", true)) return "语音服务已关闭，请在语音设置启用"
        val provider = provider(context)
        val mode = p.getString("voice_call_mode", "direct")
        if (usesAgent(provider, mode)) return null
        val stt = sttEngine(context)
        when (stt) {
            "system" -> if (!SpeechRecognizer.isRecognitionAvailable(context))
                return "手机没有可用语音识别，请在语音设置中选择 Groq Whisper 并填写 API Key"
            "groq" -> if (p.getString("groq_asr_key", "").orEmpty().isBlank())
                return "请到语音设置填写 Groq API Key（识别语音，与 ElevenLabs 音色无关）"
            "minimax" -> if (p.getString("minimax_api_key", "").orEmpty().isBlank())
                return "请配置 MiniMax 识别 API Key，或选择 Groq Whisper"
            else -> return "手机未提供系统语音识别；请在语音设置里选择并配置 Groq Whisper"
        }
        val key = p.getString(if (provider == "minimax") "minimax_api_key" else "eleven_api_key", "").orEmpty()
        val voice = CharacterVoicePreferenceStore.playbackVoiceId(characterId) ?: p.getString(if (provider == "minimax") "minimax_voice_id" else "eleven_voice_id", "")
        if (provider != "system" && key.isBlank()) return "${label(provider)} API Key 未填写"
        if (provider != "system" && voice.isNullOrBlank()) return "${label(provider)} Voice ID 未填写"
        return null
    }
}
