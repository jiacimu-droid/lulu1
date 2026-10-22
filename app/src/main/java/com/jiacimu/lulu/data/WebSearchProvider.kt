package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Search results are discovery evidence, never proof that a page was read. */
internal data class WebSearchHit(val title: String, val url: String, val preview: String)

internal object WebSearchProvider {
    private const val PREF = "lulu_learning_web_search_v1"
    private const val KEY = "tavily_api_key"
    private const val DEEPSEEK_KEY = "deepseek_search_api_key"
    fun savedKey(context: Context): String = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    fun savedDeepSeekKey(context: Context): String = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(DEEPSEEK_KEY, "").orEmpty()
    fun isConfigured(context: Context) =
        savedDeepSeekKey(context).isNotBlank() || savedKey(context).isNotBlank()
    fun saveDeepSeekKey(context: Context, key: String): Boolean {
        val cleaned = key.trim()
        require(cleaned.length <= 256 && !cleaned.contains('\n') && !cleaned.contains('\r'))
        return context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(DEEPSEEK_KEY, cleaned).commit()
    }
    fun saveKey(context: Context, key: String): Boolean {
        val cleaned = key.trim()
        require(cleaned.length <= 256 && !cleaned.contains('\n') && !cleaned.contains('\r'))
        return context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, cleaned).commit()
    }

    /** Only genuine fetched page contents become source evidence, never search snippets. */
    internal fun deepSeekFetchContent(response: JSONObject): String {
        val text = response.optString("content").trim().take(8_000)
        require(text.length >= 120) { "DeepSeek Fetch 没有返回足够的真实网页正文" }
        return text
    }

    private fun deepSeekPost(key: String, endpoint: String, payload: JSONObject): JSONObject {
        val connection = URL("https://api.deepseek.com/v1/tools/$endpoint")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            require(code in 200..299) { "DeepSeek 联网工具 HTTP $code" }
            val content = connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (output.size() < 240_000) {
                    val read = input.read(buffer, 0, minOf(buffer.size, 240_000 - output.size()))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toString("UTF-8")
            }
            return JSONObject(content)
        } finally { connection.disconnect() }
    }

    /** Provider-side article extraction is a fallback when websites block direct Android readers. */
    fun extract(context: Context, articleUrl: String): String {
        LearningMaterialSource.validateUrl(articleUrl)
        val deepSeekKey = savedDeepSeekKey(context)
        if (deepSeekKey.isNotBlank()) return deepSeekFetchContent(
            deepSeekPost(deepSeekKey, "fetch", JSONObject().put("url", articleUrl))
        )
        val key = savedKey(context)
        require(key.isNotBlank()) { "尚未配置 Tavily Search API Key" }
        val connection = URL("https://api.tavily.com/extract").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer " + key)
            val urls = org.json.JSONArray().put(articleUrl)
            val payload = JSONObject().put("urls", urls).put("extract_depth", "basic")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode in 200..299) {
                "网页正文提取失败：HTTP " + connection.responseCode
            }
            val raw = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (output.size() < 120_000) {
                    val n = input.read(bytes, 0, minOf(bytes.size, 120_000 - output.size()))
                    if (n < 0) break
                    output.write(bytes, 0, n)
                }
                output.toString("UTF-8")
            }
            val results = JSONObject(raw).optJSONArray("results") ?: error("提取服务没有返回正文")
            val match = (0 until results.length()).mapNotNull(results::optJSONObject)
                .firstOrNull { it.optString("url") == articleUrl } ?: error("提取结果未匹配原网址")
            val text = match.optString("raw_content").trim().take(8_000)
            require(text.length >= 120) { "网页正文不足，不把摘要视为全文" }
            return text
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseDeepSeekSearch(root: JSONObject): List<WebSearchHit> {
        val results = root.optJSONArray("results") ?: error("DeepSeek Search 未返回结果列表")
        return (0 until minOf(results.length(), 10)).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").trim()
            if (runCatching { LearningMaterialSource.validateUrl(url) }.isFailure) return@mapNotNull null
            val title = item.optString("title").trim().take(160).ifBlank { url }
            WebSearchHit(title, url, item.optString("snippet").trim().take(500)
                .ifBlank { item.optString("summary").trim().take(500) })
        }.distinctBy { it.url }.take(5)
    }

    fun search(context: Context, query: String): List<WebSearchHit> {
        val text = query.trim().take(128)
        require(text.length >= 2) { "请输入具体搜索词" }
        val deepSeekKey = savedDeepSeekKey(context)
        if (deepSeekKey.isNotBlank()) {
            val hits = parseDeepSeekSearch(deepSeekPost(
                deepSeekKey, "search", JSONObject().put("query", text)
                    .put("count", 5).put("need_content", false),
            ))
            require(hits.isNotEmpty()) { "DeepSeek Search 没有有效 HTTPS 搜索结果" }
            return hits
        }
        val key = savedKey(context)
        require(key.isNotBlank()) { "尚未配置 DeepSeek Search 或 Tavily Search API Key" }
        val connection = URL("https://api.tavily.com/search").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 16_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer " + key)
            val payload = JSONObject().put("query", text)
                .put("search_depth", "basic").put("max_results", 5)
                .put("include_answer", false).put("include_raw_content", false)
                .put("include_images", false)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode in 200..299) {
                "搜索服务请求失败：HTTP " + connection.responseCode
            }
            val raw = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while (output.size() < 160_000) {
                    val n = input.read(bytes, 0, minOf(bytes.size, 160_000 - output.size()))
                    if (n < 0) break
                    output.write(bytes, 0, n)
                }
                output.toString("UTF-8")
            }
            val hits = JSONObject(raw).optJSONArray("results") ?: error("搜索服务没有返回结果列表")
            val result = (0 until minOf(hits.length(), 10)).mapNotNull { i ->
                val obj = hits.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url").trim()
                if (runCatching { LearningMaterialSource.validateUrl(url) }.isFailure) return@mapNotNull null
                val title = obj.optString("title").trim().take(160).ifBlank { url }
                WebSearchHit(title, url, obj.optString("content").trim().take(500))
            }.distinctBy { it.url }.take(5)
            require(result.isNotEmpty()) { "没有有效的公开 HTTPS 搜索结果" }
            return result
        } finally {
            connection.disconnect()
        }
    }
}
