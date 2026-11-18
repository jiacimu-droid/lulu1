package com.jiacimu.lulu.system

import java.net.URI
import java.util.Locale

/** Local parsing only. A message preview never visits the shared page. */
internal object LuluSharedWebLinks {
    const val XHS_PACKAGE = "com.xingin.xhs"
    val xhsShortHosts = setOf("xhslink.com", "www.xhslink.com", "xhslink.cn", "www.xhslink.cn", "shortlink.rnote.com")
    val xhsNoteHosts = setOf("xiaohongshu.com", "www.xiaohongshu.com", "www.rednote.com")

    private val links = Regex("""\[([^\]\r\n]+)]\((https?://[^\s<>"')]+)\)|(https?://[^\s<>"'。，；、！？）】《》]+)""",
        RegexOption.IGNORE_CASE)
    data class Link(val url: String, val title: String = "")
    data class Message(val text: String, val links: List<Link>)

    fun uri(value: String): URI? {
        if (value.length > 2048) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return uri
    }

    fun xhsUrl(value: String): String? {
        val candidate = uri(value) ?: return null
        if (candidate.host.lowercase(Locale.ROOT) !in xhsShortHosts + xhsNoteHosts) return null
        val https = candidate.scheme.equals("https", ignoreCase = true)
        if (https && candidate.port in setOf(-1, 443)) return value
        if (!https && candidate.port == -1 && candidate.host.lowercase(Locale.ROOT) in xhsShortHosts)
            return "https" + value.substring(value.indexOf(':'))
        return null
    }

    fun project(text: String): Message {
        if (!text.contains("http", ignoreCase = true)) return Message(text, emptyList())
        val cards = linkedMapOf<String, Link>()
        val remaining = StringBuilder()
        var end = 0
        for (match in links.findAll(text)) {
            val markdown = match.groups[2] != null
            val raw = if (markdown) match.groupValues[2] else match.groupValues[3].trimEnd('.', ',', ';', '!', '?', ']', '}')
                .let { url ->
                    var last = url.length
                    val unmatched = (url.count { it == ')' } - url.count { it == '(' }).coerceAtLeast(0)
                    repeat(unmatched) { if (last > 0 && url[last - 1] == ')') last-- }
                    url.substring(0, last)
                }
            if (uri(raw) == null) continue
            val url = xhsUrl(raw) ?: raw
            cards.putIfAbsent(url, Link(url, match.groupValues[1].take(120)))
            remaining.append(text, end, match.range.first)
            end = if (markdown) match.range.last + 1 else match.range.first + raw.length
        }
        remaining.append(text, end, text.length)
        val caption = remaining.toString().trim()
        val textWithoutLinks = if (cards.isNotEmpty() && caption.matches(Regex("[\\s\\p{P}]*"))) "" else caption
        return Message(textWithoutLinks, cards.values.toList())
    }
}
