package com.jiacimu.lulu.data

import android.text.Html
import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal data class LearningMaterial(val title: String, val url: String, val text: String, val kind: String)

/** Bounded real materials; search snippets alone never become learned knowledge. */
internal object LearningMaterialSource {
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    fun validateUrl(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.host != null &&
            uri.port in setOf(-1, 443)) { "材料需要公开HTTPS网址" }
        val host = uri.host.lowercase()
        require(host != "localhost" && !host.endsWith(".local") && !host.contains(':') &&
            !host.matches(Regex("[0-9.]+"))) { "不能把本机或内网地址作为学习资料" }
        return uri
    }
    private fun get(url: String): String {
        var target = url
        repeat(4) {
            val uri = validateUrl(target)
            val addresses = java.net.InetAddress.getAllByName(uri.host)
            require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress ||
                it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) {
                "材料网址指向内网"
            }
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 12_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "LuluLearning/1.0 (Android companion; source-linked reading)")
                setRequestProperty("Accept", "text/html,application/json,text/plain")
            }
            try {
                if (connection.responseCode in 300..399) {
                    target = uri.resolve(connection.getHeaderField("Location")
                        ?: error("材料跳转缺少地址")).toString()
                } else {
                    require(connection.responseCode in 200..299) { "材料请求失败：HTTP ${connection.responseCode}" }
                    val type = connection.contentType.orEmpty().lowercase()
                    require(type.contains("text/") || type.contains("json")) { "暂不支持这类材料格式，请使用网页或文本" }
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() < 300_000) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 300_000 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val charset = Regex("charset=([^; ]+)").find(type)?.groupValues?.get(1)
                    return String(bytes, runCatching { java.nio.charset.Charset.forName(charset ?: "UTF-8") }
                        .getOrDefault(Charsets.UTF_8))
                }
            } finally { connection.disconnect() }
        }
        error("材料跳转次数过多")
    }
    fun retrieve(query: String, suppliedUrl: String, context: Context? = null): LearningMaterial {
        if (suppliedUrl.isNotBlank()) {
            val url = validateUrl(suppliedUrl).toString()
            val raw = get(url)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(raw)?.groupValues?.get(1)
                ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                ?.take(180) ?: url
            val stripped = raw.replace(Regex("(?is)<(script|style|nav|footer)[^>]*>.*?</\\1>"), " ")
            val text = Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().take(8_000)
            require(text.length >= 120) { "网页没有可读取的正文，可能需要登录或动态加载" }
            return LearningMaterial(title, url, text, "外部网页节选，来源观点待核实")
        }
        // Prefer real general search when the user has configured Tavily.
        // A result snippet NEVER counts as reading: fetch an actual public page.
        if (context != null && WebSearchProvider.isConfigured(context)) {
            val hits = WebSearchProvider.search(context, query)
            val actual = hits.take(4).firstNotNullOfOrNull { hit ->
                runCatching {
                    retrieve(query, hit.url, null).copy(kind = "通用搜索命中的网页正文节选，需核实")
                }.getOrNull()
            }
            if (actual != null) return actual
            error("通用搜索已有结果，但网页正文均无法实际读取；请换关键词或提供可访问的网址")
        }
        // Without a configured provider, use a concrete encyclopedia rather than pretend search.
        require(query.trim().length >= 2) { "需要具体搜索词或真实材料网址" }
        val base = "https://zh.wikipedia.org/w/api.php"
        val search = JSONObject(get("$base?action=query&list=search&srsearch=${encoded(query.take(120))}&srlimit=3&format=json"))
        val hit = search.optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)
            ?: error("百科搜索没有找到材料，可换搜索词、提供网页或读内部书籍")
        val title = hit.getString("title")
        val root = JSONObject(get("$base?action=query&prop=extracts&explaintext=1&exchars=8000&titles=${encoded(title)}&format=json"))
        val pages = root.getJSONObject("query").getJSONObject("pages")
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull()
            ?: error("搜索结果没有正文")
        val text = page.optString("extract").trim().take(8_000)
        require(text.length >= 120) { "搜索结果正文不足，不将标题当作已学知识" }
        return LearningMaterial(title, "https://zh.wikipedia.org/wiki/${encoded(title).replace("+", "%20")}",
            text, "百科正文节选，不是权威定论")
    }
}
