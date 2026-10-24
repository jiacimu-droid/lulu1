package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import java.net.URI

internal object CallVoiceConfiguration {
    fun provider(context: Context): String = context.getSharedPreferences("lulu_advanced_settings", 0).getString("tts_provider", "system").orEmpty()
    /** The retired Android SpeechRecognizer route is replaced by on-device whisper.cpp.
     * Old installs which selected "system" are migrated transparently to local inference. */
    fun normalizeSttMode(mode: String?): String = when (mode) {
        "system", "local" -> "local"
        "groq", "minimax", "elevenlabs" -> mode
        else -> "local"
    }

    const val SCRIBE_V2 = "scribe_v2"
    const val SCRIBE_V2_REALTIME = "scribe_v2_realtime"
    internal enum class ElevenInputPath { SEGMENTED_UPLOAD, CONTINUOUS_REALTIME, OTHER }
    fun elevenInputPath(sttEngine: String, model: String?): ElevenInputPath =
        if (sttEngine != "elevenlabs") ElevenInputPath.OTHER
        else if (elevenSttModel(model) == SCRIBE_V2_REALTIME) ElevenInputPath.CONTINUOUS_REALTIME
        else ElevenInputPath.SEGMENTED_UPLOAD

    /** Existing installs keep Scribe v2; invalid/obsolete preferences cannot select arbitrary APIs. */
    fun elevenSttModel(raw: String?): String =
        if (raw == SCRIBE_V2_REALTIME) SCRIBE_V2_REALTIME else SCRIBE_V2

    /** An active utterance or unfinished cloud transcript must keep its original decoder. */
    fun canSwitchSttNow(
        capturingSpeech: Boolean,
        userSpeaking: Boolean,
        inputHasPendingUtterance: Boolean,
        recognitionPendingUntil: Long,
        now: Long,
    ): Boolean = !capturingSpeech && !userSpeaking && !inputHasPendingUtterance &&
        recognitionPendingUntil <= now

    /** STT is independent from character TTS. Never silently switch paid ASR providers. */
    fun resolveSttEngine(mode: String): String = normalizeSttMode(mode)
    fun sttEngine(context: Context): String {
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        return resolveSttEngine(p.getString("call_stt_mode", "local").orEmpty())
    }
    fun sttLabel(engine: String): String = when (engine) {
        "groq" -> "Groq Whisper"
        "local" -> "本地 Whisper（完全离线）"
        "minimax" -> "MiniMax 识别"
        "elevenlabs" -> "ElevenLabs Scribe"
        else -> "尚未配置"
    }
    fun label(provider: String): String = when (provider) { "minimax" -> "MiniMax"; "elevenlabs" -> "ElevenLabs"; else -> "系统语音" }
    fun miniAsrEndpoint(ttsEndpoint: String): String {
        val uri = URI(ttsEndpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "MiniMax 接口必须是 HTTPS 地址" }
        return URI("https", null, uri.host, uri.port, "/v1/speech_to_text", null, null).toString()
    }
    /** Checks the selected capture route without rejecting a healthy live call for unrelated TTS preferences. */
    fun sttPreflight(context: Context): String? {
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        when (sttEngine(context)) {
            "local" -> {
                LocalWhisperTranscriber.runtimeError()?.let { return it }
                if (LocalWhisperModels.installedFile(context, LocalWhisperModels.selected(context)) == null)
                    return "请先到语音设置下载所选 Whisper 本地模型（Tiny 或 Base），不会产生语音识别 API 费用"
            }
            "groq" -> if (p.getString("groq_asr_key", "").orEmpty().isBlank())
                return "请到语音设置填写 Groq API Key（识别语音，与 ElevenLabs 音色无关）"
            "minimax" -> if (p.getString("minimax_api_key", "").orEmpty().isBlank())
                return "请配置 MiniMax 识别 API Key，或选择 Groq Whisper"
            "elevenlabs" -> if (p.getString("eleven_api_key", "").orEmpty().isBlank())
                return "请在 ElevenLabs 语音服务设置中配置 API Key，Scribe 识别会使用你的积分"
            else -> return "请选择一种语音转文字线路"
        }
        return null
    }

    fun preflight(context: Context, characterId: String): String? {
        val p = context.getSharedPreferences("lulu_advanced_settings", 0)
        if (!p.getBoolean("tts_enabled", true)) return "语音服务已关闭，请在语音设置启用"
        sttPreflight(context)?.let { return it }
        val provider = provider(context)
        val key = p.getString(if (provider == "minimax") "minimax_api_key" else "eleven_api_key", "").orEmpty()
        val voice = CharacterVoicePreferenceStore.playbackVoiceId(characterId) ?: p.getString(if (provider == "minimax") "minimax_voice_id" else "eleven_voice_id", "")
        if (provider != "system" && key.isBlank()) return "${label(provider)} API Key 未填写"
        if (provider != "system" && voice.isNullOrBlank()) return "${label(provider)} Voice ID 未填写"
        return null
    }
}
