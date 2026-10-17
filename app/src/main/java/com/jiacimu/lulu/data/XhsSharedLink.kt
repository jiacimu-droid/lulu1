package com.jiacimu.lulu.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A user-shared exact note, NOT a keyword search or an invented list hit. */
internal data class XhsSharedLink(
    val id: String,
    val token: String,
    val source: String,
    val url: String,
)

internal object XhsSharedLinkResolver {
    private val allowedHosts = setOf(
        "www.xiaohongshu.com", "xiaohongshu.com", "www.xhslink.com", "xhslink.com",
    )
    private val notePath = Regex("""^/(?:explore|discovery/item|search_result)/([a-zA-Z0-9]{12,40})/?$""")

    private fun safeUri(raw: String): URI {
        val candidate = raw.trim().trimEnd('。', '，', ',', ')', '】', ';')
        require(candidate.length in 15..2048) { "小红书链接格式不正确" }
        val uri = URI(candidate)
        require(uri.scheme?.lowercase() == "https" && uri.userInfo == null &&
            uri.port in setOf(-1, 443) && uri.host?.lowercase() in allowedHosts) {
            "只能读取 HTTPS 小红书官方链接"
        }
        return uri
    }

    private fun query(uri: URI, key: String): String = uri.rawQuery.orEmpty().split('&')
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")?.let {
            runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault("")
        }.orEmpty()

    fun parseDirect(raw: String): XhsSharedLink? {
        val uri = safeUri(raw)
        if (uri.host?.lowercase() !in setOf("xiaohongshu.com", "www.xiaohongshu.com")) return null
        val id = notePath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
        val source = query(uri, "xsec_source").takeIf {
            it in setOf("pc_feed", "pc_search", "pc_user", "pc_share", "pc_board")
        } ?: "pc_share"
        return XhsSharedLink(id, query(uri, "xsec_token").take(512), source, uri.toASCIIString())
    }

    /** Bounded redirect resolution. Do not send cookies or follow third-party URLs. */
    suspend fun resolve(raw: String): XhsSharedLink = withContext(Dispatchers.IO) {
        var candidate = safeUri(raw).toASCIIString()
        repeat(5) { hop ->
            parseDirect(candidate)?.let { return@withContext it }
            val uri = safeUri(candidate)
            if (uri.host?.lowercase() !in setOf("xhslink.com", "www.xhslink.com"))
                error("分享链接没有指向具体的小红书笔记")
            if (hop == 4) error("分享短链跳转次数过多")
            val connection = URL(candidate).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Range", "bytes=0-0")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val status = connection.responseCode
                require(status in 300..399) {
                    "小红书短链未提供可解析的笔记跳转（HTTP " + status + "）"
                }
                val location = connection.getHeaderField("Location")
                    ?.takeIf(String::isNotBlank) ?: error("小红书短链缺少跳转地址")
                candidate = safeUri(uri.resolve(location).toString()).toASCIIString()
            } finally {
                connection.disconnect()
            }
        }
        error("分享链接无法解析")
    }
}
