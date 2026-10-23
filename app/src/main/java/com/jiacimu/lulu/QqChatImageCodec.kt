package com.jiacimu.lulu

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class QqChatImageMessage(
    val imageUri: String, val caption: String, val imageDescription: String,
    val sticker: Boolean = false,
    val sourceUrl: String = "",
    val sourceProvider: String = "",
)
private const val ChatImagePrefix = "⟪CHAT_IMAGE:"
private const val ChatImageSuffix = "⟫"
private val ChatImageRegex = Regex("⟪CHAT_IMAGE:([^⟫]+)⟫")

internal fun encodeQqChatImage(
    imageUri: String, caption: String = "", imageDescription: String = "", sticker: Boolean = false,
    sourceUrl: String = "", sourceProvider: String = "",
): String {
    val json = JSONObject().put("uri", imageUri.trim()).put("caption", caption.trim().take(1000))
        .put("description", imageDescription.trim().take(1800)).put("sticker", sticker)
        .put("sourceUrl", sourceUrl.trim().take(2048))
        .put("sourceProvider", sourceProvider.trim().take(20))
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
    return "$ChatImagePrefix$encoded$ChatImageSuffix"
}

internal fun decodeQqChatImage(content: String): QqChatImageMessage? {
    val encoded = ChatImageRegex.find(content)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
        QqChatImageMessage(json.optString("uri").trim(), json.optString("caption").trim(),
            json.optString("description").trim(), json.optBoolean("sticker", false),
            json.optString("sourceUrl").trim(), json.optString("sourceProvider").trim())
    }.getOrNull()?.takeIf { it.imageUri.isNotBlank() }
}

/**
 * Display-only layout data. Historical image-search replies stored attribution
 * as the final caption line; recover that line without changing archived messages.
 */
internal data class QqChatImageDisplay(
    val caption: String,
    val sourceUrl: String,
    val sourceLabel: String,
)

private val LegacyImageAttribution = Regex(
    """(?:^|\n)(图片来源|Powered By GIPHY)\s*·\s*(https://[^\s]+)\s*$"""
)

internal fun qqChatImageDisplay(image: QqChatImageMessage): QqChatImageDisplay {
    val legacy = if (image.sourceUrl.isBlank()) LegacyImageAttribution.find(image.caption) else null
    val caption = if (legacy != null) image.caption.removeRange(legacy.range).trim()
        else image.caption.trim()
    val candidate = image.sourceUrl.trim().ifBlank { legacy?.groupValues?.getOrNull(2).orEmpty() }
    val source = runCatching {
        val uri = java.net.URI(candidate)
        if (uri.scheme?.equals("https", ignoreCase = true) == true &&
            !uri.host.isNullOrBlank() && uri.userInfo == null) candidate else ""
    }.getOrDefault("")
    val giphy = image.sourceProvider.equals("giphy", ignoreCase = true) ||
        legacy?.groupValues?.getOrNull(1) == "Powered By GIPHY"
    return QqChatImageDisplay(caption, source,
        if (giphy) "Powered By GIPHY" else "图片来源")
}

internal fun stripQqChatImageDirective(content: String): String = content.replace(ChatImageRegex, "").trim()
internal fun qqChatImageContextText(content: String): String? = decodeQqChatImage(content)?.let { image ->
    buildString {
        append(if (image.sticker) "[发送表情包] " else "[发送图片] ")
        append(image.imageDescription.ifBlank { if (image.sticker) "未标记表情含义" else "图片内容暂无识图描述" }.take(1200))
        if (image.caption.isNotBlank()) append("；配文：${image.caption.take(500)}")
    }
}
