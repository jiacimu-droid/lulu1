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
    fun savedKey(context: Context): String = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    fun isConfigured(context: Context) = savedKey(context).isNotBlank()
    fun saveKey(context: Context, key: String): Boolean {
        val cleaned = key.trim()
        require(cleaned.length <= 256 && !cleaned.contains('\n') && !cleaned.contains('\r'))
        return context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, cleaned).commit()
    }

    fun search(context: Context, query: String): List<WebSearchHit> {
        val key = savedKey(context)
        require(key.isNotBlank()) { "尚未配置 Tavily Search API Key" }
        val text = query.trim().take(180)
        require(text.length >= 2) { "请输入具体搜索词" }
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
