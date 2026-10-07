package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Model IDs and transport follow the official model/TTD API documentation. */
internal object ElevenLabsModels {
    const val DEFAULT = "eleven_v4_turbo"
    val choices = listOf(
        "Eleven v4 Turbo · 实时通话" to "eleven_v4_turbo",
        "Eleven v4 · 情感表现" to "eleven_v4",
        "Eleven v3 Conversational · 实时通话" to "eleven_v3_conversational",
        "Eleven v3 · 情感表现" to "eleven_v3",
        "Flash v2.5 · 低延迟" to "eleven_flash_v2_5",
        "Multilingual v2 · 自然稳定" to "eleven_multilingual_v2",
        "Turbo v2.5 · 旧版兼容" to "eleven_turbo_v2_5",
    )
    fun websocket(model: String) = model == "eleven_v4_turbo" || model == "eleven_v3_conversational"
    fun dialogue(model: String) = model.startsWith("eleven_v4") || model.startsWith("eleven_v3")
    fun httpPath(model: String, voice: String) = if (dialogue(model)) "/v1/text-to-dialogue/stream"
        else "/v1/text-to-speech/${URLEncoder.encode(voice, "UTF-8")}/stream"

    fun body(model: String, text: String, voice: String, stability: Float, similarity: Float): JSONObject =
        JSONObject().put("model_id", model).apply {
            if (dialogue(model)) put("inputs", JSONArray().put(JSONObject().put("text", text).put("voice_id", voice)))
            else {
                put("text", text)
                put("voice_settings", JSONObject().put("stability", stability.toDouble()).put("similarity_boost", similarity.toDouble()))
            }
        }
}
