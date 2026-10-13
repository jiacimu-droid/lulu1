package com.jiacimu.lulu.ai

import org.json.JSONArray
import org.json.JSONObject

/** Preserve ordinary text requests; attach downloaded pixels for one multimodal turn. */
internal object MultimodalContent {
    fun user(text: String, imageDataUrls: List<String>, nativeClaude: Boolean): Any {
        if (imageDataUrls.isEmpty()) return text
        require(imageDataUrls.size <= 6) { "一次最多读取六张图片" }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        imageDataUrls.forEach { data ->
            val match = Regex("^data:(image/(?:jpeg|png|webp));base64,([A-Za-z0-9+/=]+)$").matchEntire(data)
                ?: error("图片需要有效的内嵌像素数据")
            content.put(if (nativeClaude) JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", match.groupValues[1]).put("data", match.groupValues[2]))
            else JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", data).put("detail", "high")))
        }
        return content
    }
}
