package com.jiacimu.lulu

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class QqChatImageMessage(val imageUri: String, val caption: String, val imageDescription: String)
private const val ChatImagePrefix = "⟪CHAT_IMAGE:"
private const val ChatImageSuffix = "⟫"
private val ChatImageRegex = Regex("⟪CHAT_IMAGE:([^⟫]+)⟫")

internal fun encodeQqChatImage(imageUri: String, caption: String = "", imageDescription: String = ""): String {
    val json = JSONObject().put("uri", imageUri.trim()).put("caption", caption.trim().take(1000)).put("description", imageDescription.trim().take(1800))
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
    return "$ChatImagePrefix$encoded$ChatImageSuffix"
}

internal fun decodeQqChatImage(content: String): QqChatImageMessage? {
    val encoded = ChatImageRegex.find(content)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
        QqChatImageMessage(json.optString("uri").trim(), json.optString("caption").trim(), json.optString("description").trim())
    }.getOrNull()?.takeIf { it.imageUri.isNotBlank() }
}

internal fun stripQqChatImageDirective(content: String): String = content.replace(ChatImageRegex, "").trim()
internal fun qqChatImageContextText(content: String): String? = decodeQqChatImage(content)?.let { image ->
    buildString {
        append("[用户发送图片] ")
        append(image.imageDescription.ifBlank { "图片内容暂无识图描述" }.take(1200))
        if (image.caption.isNotBlank()) append("；配文：${image.caption.take(500)}")
    }
}
