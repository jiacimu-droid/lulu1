package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class QqForwardedChatEntry(
    val sender: String,
    val content: String,
    val timeLabel: String,
)

internal data class QqForwardedChatBundle(
    val title: String,
    val entries: List<QqForwardedChatEntry>,
)

private const val ForwardDirectivePrefix = "⟪FORWARD_CARD:"
private const val ForwardDirectiveSuffix = "⟫"
private val ForwardDirectiveRegex = Regex("⟪FORWARD_CARD:([^⟫]+)⟫")

internal fun encodeQqForwardedChat(bundle: QqForwardedChatBundle): String {
    val json = JSONObject()
        .put("title", bundle.title.trim().ifBlank { "聊天记录" })
        .put(
            "entries",
            JSONArray().apply {
                bundle.entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("sender", entry.sender)
                            .put("content", entry.content)
                            .put("time", entry.timeLabel),
                    )
                }
            },
        )
    val encoded = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
    return "[合并转发] ${bundle.entries.size} 条聊天记录\n$ForwardDirectivePrefix$encoded$ForwardDirectiveSuffix"
}

internal fun decodeQqForwardedChat(content: String): QqForwardedChatBundle? {
    val encoded = ForwardDirectiveRegex.find(content)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val raw = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        val json = JSONObject(raw)
        val array = json.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val body = item.optString("content").trim()
                if (body.isBlank()) continue
                add(
                    QqForwardedChatEntry(
                        sender = item.optString("sender").trim().ifBlank { "聊天成员" },
                        content = body,
                        timeLabel = item.optString("time").trim(),
                    ),
                )
            }
        }
        QqForwardedChatBundle(
            title = json.optString("title").trim().ifBlank { "聊天记录" },
            entries = entries,
        )
    }.getOrNull()?.takeIf { it.entries.isNotEmpty() }
}

internal fun stripQqForwardDirective(content: String): String =
    content.replace(ForwardDirectiveRegex, "").trim()

internal fun qqForwardContextText(content: String): String {
    val bundle = decodeQqForwardedChat(content) ?: return stripQqForwardDirective(content)
    val preview = bundle.entries.take(6).joinToString("；") { entry ->
        "${entry.sender}：${entry.content.take(160)}"
    }
    return "[合并转发 ${bundle.entries.size} 条] $preview"
}
