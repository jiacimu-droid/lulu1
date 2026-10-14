package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/** One user-shared note. Verified content is distinct from an unvisited external link. */
internal data class QqXhsSharedNote(
    val title: String,
    val url: String,
    val author: String = "",
    val coverUrl: String = "",
    val body: String = "",
    val visualReading: String = "",
    val comments: List<String> = emptyList(),
    val verified: Boolean = false,
    val shareText: String = "",
)

private val xhsDirective = Regex("""⟪XHS_NOTE:([A-Za-z0-9_-]+)⟫""")
private val shareUrlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private fun officialXhsUrl(value: String): String? = runCatching {
    val url = value.trim().trimEnd('。', '，', ',', ')', '】', ';')
    val uri = URI(url)
    val host = uri.host?.lowercase()
    if (uri.scheme?.lowercase() != "https" || uri.userInfo != null ||
        uri.port !in setOf(-1, 443) ||
        host !in setOf("xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com", "www.xhslink.com")
    ) null else url
}.getOrNull()

internal fun xhsUrlInText(text: String): String? =
    shareUrlRegex.findAll(text).mapNotNull { officialXhsUrl(it.value) }.firstOrNull()

/** Android's share sheet generally provides only a URL and title, not readable note pixels. */
internal fun externalXhsSharedNote(sharedText: String): QqXhsSharedNote? {
    val link = xhsUrlInText(sharedText) ?: return null
    val beforeLink = sharedText.substringBefore(link).trim()
    val title = beforeLink.lineSequence().map { it.trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("分享给你一篇小红书笔记") }
        ?.replace(Regex("""^(?:分享给你|发现一个|我在小红书发现了|复制打开小红书看看)\s*[:：]?\s*"""), "")
        ?.trim()?.take(100).orEmpty().ifBlank { "分享的小红书笔记" }
    return QqXhsSharedNote(title, link, shareText = sharedText.take(3_000))
}

internal fun encodeQqXhsSharedNote(note: QqXhsSharedNote): String {
    val url = officialXhsUrl(note.url) ?: error("不是有效的小红书分享链接")
    val data = JSONObject()
        .put("v", 1).put("title", note.title.trim().take(160))
        .put("url", url).put("author", note.author.trim().take(100))
        .put("cover", note.coverUrl.trim().take(2000))
        .put("body", note.body.trim().take(8_000))
        .put("visual", note.visualReading.trim().take(6_000))
        .put("verified", note.verified)
        .put("shareText", note.shareText.trim().take(3_000))
        .put("comments", JSONArray().apply { note.comments.take(8).forEach { put(it.take(320)) } })
    val token = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(data.toString().toByteArray(StandardCharsets.UTF_8))
    return "[小红书分享] ${note.title.take(100)}\n⟪XHS_NOTE:$token⟫"
}

private fun decodeLegacyXhsShare(content: String): QqXhsSharedNote? {
    if (!content.startsWith("分享给你一篇小红书笔记：")) return null
    val rest = content.removePrefix("分享给你一篇小红书笔记：").trim()
    val url = xhsUrlInText(rest) ?: return null
    if (!rest.startsWith("标题：")) return externalXhsSharedNote(rest)
    val title = rest.lineSequence().firstOrNull()?.removePrefix("标题：").orEmpty()
    val author = rest.lineSequence().firstOrNull { it.startsWith("作者：") }
        ?.removePrefix("作者：").orEmpty()
    val body = rest.substringAfter("帖子原文：\n", "")
        .substringBefore("\n图片理解（", "")
        .substringBefore("\n实际加载到的评论摘录", "")
        .take(8_000)
    val visual = rest.substringAfter("图片理解（模型识别，未必准确，最多前6张）：\n", "")
        .substringBefore("\n实际加载到的评论摘录", "").take(6_000)
    return QqXhsSharedNote(title.ifBlank { "分享的小红书笔记" }, url,
        author = author, body = body, visualReading = visual,
        verified = body.isNotBlank() || visual.isNotBlank(), shareText = "")
}

internal fun decodeQqXhsSharedNote(content: String): QqXhsSharedNote? {
    val token = xhsDirective.find(content)?.groupValues?.getOrNull(1)
        ?: return decodeLegacyXhsShare(content)
    return runCatching {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8))
        val url = officialXhsUrl(json.optString("url")) ?: return null
        val comments = json.optJSONArray("comments")
        QqXhsSharedNote(
            title = json.optString("title").take(160).ifBlank { "分享的小红书笔记" },
            url = url,
            author = json.optString("author").take(100),
            coverUrl = json.optString("cover").take(2000),
            body = json.optString("body").take(8_000),
            visualReading = json.optString("visual").take(6_000),
            comments = (0 until (comments?.length() ?: 0).coerceAtMost(8))
                .map { comments!!.optString(it).take(320) }.filter(String::isNotBlank),
            verified = json.optBoolean("verified", false),
            shareText = json.optString("shareText").take(3_000),
        )
    }.getOrNull()
}

/** Give the character meaningful text, never a base64 marker or fake vision. */
internal fun qqXhsContextText(content: String): String? =
    decodeQqXhsSharedNote(content)?.let { note ->
        buildString {
            append("[用户分享小红书笔记] 标题：${note.title}；链接：${note.url}")
            if (note.author.isNotBlank()) append("；作者：${note.author}")
            if (note.verified) {
                if (note.body.isNotBlank()) append("\n真实提取的正文：${note.body.take(4_000)}")
                if (note.visualReading.isNotBlank()) append("\n图片理解（模型识别）：${note.visualReading.take(2_500)}")
                if (note.comments.isNotEmpty()) append("\n已加载的评论：${note.comments.take(6).joinToString("；").take(900)}")
                if (note.visualReading.isBlank() && note.coverUrl.isNotBlank()) append("\n存在封面或图片，但没有可确认的图片理解")
            } else {
                append("\n目前只有用户分享的标题和链接，尚未实际读取网页正文、图片或评论；不能声称已经看过")
                if (note.shareText.isNotBlank()) append("\n原分享附言：${note.shareText.take(400)}")
            }
        }
    }
