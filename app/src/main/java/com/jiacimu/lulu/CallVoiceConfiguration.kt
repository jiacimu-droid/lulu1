package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import java.net.URI

internal object CallVoiceConfiguration {
    fun provider(context: Context): String = context.getSharedPreferences("lulu_advanced_settings", 0).getString("tts_provider", "system").orEmpty()
    fun usesAgent(provider: String, mode: String?): Boolean = provider == "elevenlabs" && mode == "agent"
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
        if (usesAgent(provider, p.getString("voice_call_mode", "direct"))) return null
        val key = p.getString(if (provider == "minimax") "minimax_api_key" else "eleven_api_key", "").orEmpty()
        val voice = CharacterVoicePreferenceStore.playbackVoiceId(characterId) ?: p.getString(if (provider == "minimax") "minimax_voice_id" else "eleven_voice_id", "")
        if (provider != "system" && key.isBlank()) return "${label(provider)} API Key 未填写"
        if (provider != "system" && voice.isNullOrBlank()) return "${label(provider)} Voice ID 未填写"
        return null
    }
}
