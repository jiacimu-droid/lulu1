package com.jiacimu.lulu.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

/**
 * Serper = public web image discovery; GIPHY = GIF and sticker discovery.
 * Search snippets and titles are metadata, never independent visual inspection.
 * Only verified downloaded bytes become an actual image message.
 */
internal data class ImageSearchHit(
    val title: String, val imageUrl: String, val sourceUrl: String,
    val provider: String, val thumbnailUrl: String = "",
)

internal object ImageSearchProvider {
    private const val PREF = "lulu_image_search_v1"
    private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun serperKey(context: Context) = prefs(context).getString("serper_key", "").orEmpty()
    fun giphyKey(context: Context) = prefs(context).getString("giphy_key", "").orEmpty()
    fun configured(context: Context, provider: String) = when (provider) {
        "serper" -> serperKey(context).isNotBlank()
        "giphy" -> giphyKey(context).isNotBlank()
        else -> false
    }
    fun saveKeys(context: Context, serper: String, giphy: String): Boolean {
        val a = serper.trim()
        val b = giphy.trim()
        require(listOf(a, b).all { it.length <= 256 && '\n' !in it && '\r' !in it })
        return prefs(context).edit().putString("serper_key", a)
            .putString("giphy_key", b).commit()
    }

    private fun publicHttps(value: String): URI {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", true) && uri.host != null &&
            uri.userInfo == null && uri.port in setOf(-1, 443)) { "图片必须是公开 HTTPS 链接" }
        val host = uri.host.lowercase()
        require(host != "localhost" && !host.endsWith(".local") &&
            !host.contains(':') && !host.matches(Regex("[0-9.]+"))) { "不能访问内网图片" }
        // Validate each DNS result again immediately before the connection.
        val ips = InetAddress.getAllByName(uri.host)
        require(ips.isNotEmpty() && ips.none {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                it.isSiteLocalAddress || it.isMulticastAddress
        }) { "图片服务器指向内网" }
        return uri
    }
    internal fun parseSerper(root: JSONObject): List<ImageSearchHit> {
        val images = root.optJSONArray("images") ?: error("Serper 没有返回 images")
        return (0 until minOf(images.length(), 15)).mapNotNull { i ->
            val item = images.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("imageUrl").trim()
            val source = item.optString("link").ifBlank { item.optString("source") }.trim()
            if (!looksPublicHttps(url) || !looksPublicHttps(source)) return@mapNotNull null
            ImageSearchHit(item.optString("title").take(150).ifBlank { "搜索图片" },
                url, source, "serper", item.optString("thumbnailUrl").trim())
        }.distinctBy { it.imageUrl }
    }
    internal fun parseGiphy(root: JSONObject): List<ImageSearchHit> {
        val images = root.optJSONArray("data") ?: error("GIPHY 没有返回 data")
        return (0 until minOf(images.length(), 15)).mapNotNull { i ->
            val item = images.optJSONObject(i) ?: return@mapNotNull null
            val formats = item.optJSONObject("images") ?: return@mapNotNull null
            val imageUrl = formats.optJSONObject("fixed_width")?.optString("url")
                .orEmpty().ifBlank { formats.optJSONObject("downsized")?.optString("url").orEmpty() }
            val source = item.optString("url")
            if (!looksPublicHttps(imageUrl) || !looksPublicHttps(source)) return@mapNotNull null
            ImageSearchHit(item.optString("title").take(150).ifBlank { "GIPHY GIF" },
                imageUrl, source, "giphy",
                formats.optJSONObject("fixed_width_still")?.optString("url").orEmpty())
        }.distinctBy { it.imageUrl }
    }
    /** Metadata filter only; full DNS validation is performed on actual downloads. */
    private fun looksPublicHttps(value: String) = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) && uri.host != null &&
            uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.host != "localhost" && !uri.host.endsWith(".local", true) &&
            !uri.host.contains(':') && !uri.host.matches(Regex("[0-9.]+"))
    }.getOrDefault(false)

    private fun fetchJson(connection: HttpURLConnection): JSONObject = try {
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        val status = connection.responseCode
        require(status in 200..299) { "图片搜索 HTTP $status（检查密钥或剩余额度）" }
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (output.size() < 250_000) {
                val read = input.read(buf, 0, minOf(buf.size, 250_000 - output.size()))
                if (read < 0) break
                output.write(buf, 0, read)
            }
            output.toString("UTF-8")
        }
        JSONObject(bytes)
    } finally { connection.disconnect() }

    fun search(context: Context, provider: String, query: String, kind: String = "gif"): List<ImageSearchHit> {
        val q = query.trim()
        require(q.length in 2..100) { "请填写具体搜图关键词" }
        val route = if (provider == "auto") {
            if (configured(context, "serper")) "serper" else "giphy"
        } else provider
        require(configured(context, route)) { "请先在『联网与阅读』配置 ${route.uppercase()} API Key" }
        val hits = when (route) {
            "serper" -> {
                val connection = URL("https://google.serper.dev/images").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("X-API-KEY", serperKey(context))
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(JSONObject().put("q", q).put("num", 10).toString()
                        .toByteArray(Charsets.UTF_8))
                }
                parseSerper(fetchJson(connection))
            }
            "giphy" -> {
                // GIPHY requires searches from the client and exact query text.
                val encoded = URLEncoder.encode(q.take(50), "UTF-8")
                val key = URLEncoder.encode(giphyKey(context), "UTF-8")
                require(kind in setOf("gif", "sticker")) { "GIPHY 只支持 gif 或 sticker" }
                val endpoint = if (kind == "sticker") "stickers" else "gifs"
                val connection = URL("https://api.giphy.com/v1/$endpoint/search?" +
                    "api_key=$key&q=$encoded&limit=10&rating=pg-13")
                    .openConnection() as HttpURLConnection
                parseGiphy(fetchJson(connection))
            }
            else -> error("仅支持 serper、giphy 或 auto")
        }
        require(hits.isNotEmpty()) { "没有找到可用的 HTTPS 图片，请更换关键词" }
        return hits
    }

    /** Search provenance is per-character and persists through process restarts. */
    fun remember(context: Context, characterId: String, hits: List<ImageSearchHit>): String {
        require(characterId.isNotBlank() && hits.isNotEmpty())
        val searchId = UUID.randomUUID().toString()
        val array = JSONArray()
        hits.forEach { hit ->
            array.put(JSONObject().put("title", hit.title).put("imageUrl", hit.imageUrl)
                .put("sourceUrl", hit.sourceUrl).put("provider", hit.provider)
                .put("thumbnailUrl", hit.thumbnailUrl))
        }
        check(prefs(context).edit().putString("result:$characterId",
            JSONObject().put("searchId", searchId).put("items", array).toString()).commit()) {
            "搜图候选保存失败"
        }
        return searchId
    }
    fun select(context: Context, characterId: String, searchId: String, index: Int): ImageSearchHit {
        val saved = JSONObject(prefs(context).getString("result:$characterId", null)
            ?: error("没有本角色实际搜索的图片候选，请先搜索"))
        require(searchId.isNotBlank() && searchId == saved.optString("searchId")) {
            "搜索结果已更新，请选择新的 searchId"
        }
        val item = saved.optJSONArray("items")?.optJSONObject(index)
            ?: error("候选图片编号不存在")
        return ImageSearchHit(item.optString("title"), item.optString("imageUrl"),
            item.optString("sourceUrl"), item.optString("provider"), item.optString("thumbnailUrl"))
    }

    /**
     * Never use remote user-controlled URLs as local filenames or permissions.
     * Leave uploaded assets in the same durable, app-private file model used by
     * QQ image messages. Validate type and signature, no HTML/SVG or executables.
     */
    fun download(context: Context, hit: ImageSearchHit): String {
        var uri = publicHttps(hit.imageUrl)
        var file: File? = null
        try {
            for (redirect in 0..3) {
                uri = publicHttps(uri.toString())
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 18_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp")
                    connection.setRequestProperty("User-Agent", "LuluImageSearch/1.0")
                    if (connection.responseCode in 300..399) {
                        require(redirect < 3) { "图片重定向过多" }
                        uri = uri.resolve(connection.getHeaderField("Location")
                            ?: error("图片跳转缺少地址"))
                        continue
                    }
                    require(connection.responseCode == 200) {
                        "图片下载失败 HTTP ${connection.responseCode}"
                    }
                    val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
                    require(mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) {
                        "来源返回的不是支持的图片文件"
                    }
                    val dir = File(context.applicationContext.filesDir, "character-web-images")
                    check(dir.isDirectory || dir.mkdirs()) { "无法建立图片存储" }
                    val temp = File.createTempFile("online-image-", ".part", dir)
                    file = temp
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buf = ByteArray(16 * 1024)
                            var size = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                size += read
                                require(size <= MAX_IMAGE_BYTES) { "图片超过10MB，不下载" }
                                output.write(buf, 0, read)
                            }
                        }
                    }
                    val bytes = temp.inputStream().use { input ->
                        val header = ByteArray(16)
                        val n = input.read(header)
                        if (n <= 0) ByteArray(0) else header.copyOf(n)
                    }
                    val actual = sniff(bytes)
                    require(actual != null && actual == mime) { "图片格式与内容不符" }
                    val dimensions = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(temp.absolutePath, dimensions)
                    require(dimensions.outWidth > 0 && dimensions.outHeight > 0) {
                        "图片无法正常解码，不发送损坏的附件"
                    }
                    val ext = when (actual) {
                        "image/gif" -> "gif"
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        else -> "jpg"
                    }
                    val dest = File(dir, "online-${UUID.randomUUID()}.$ext")
                    check(temp.renameTo(dest)) { "图片文件保存失败" }
                    file = null
                    return Uri.fromFile(dest).toString()
                } finally { connection.disconnect() }
            }
            error("无法取得图片")
        } finally { file?.delete() }
    }
    internal fun sniff(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val u = bytes.map { it.toInt() and 0xff }
        if (u[0] == 0xff && u[1] == 0xd8 && u[2] == 0xff) return "image/jpeg"
        if (u.take(8) == listOf(137,80,78,71,13,10,26,10)) return "image/png"
        if (String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) return "image/gif"
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
        return null
    }
}
