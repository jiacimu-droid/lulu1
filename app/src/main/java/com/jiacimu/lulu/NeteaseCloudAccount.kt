package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class NeteaseCloudProfile(val uid: String, val nickname: String)
internal data class NeteaseCloudList(
    val id: String, val name: String, val trackCount: Int,
    val liked: Boolean, val subscribed: Boolean,
)
internal data class NeteaseCloudSong(val id: String, val title: String, val artist: String)
internal data class NeteaseCloudTrackPage(
    val ids: List<String>, val tracks: List<NeteaseCloudSong>,
    val missing: Int,
)

/**
 * Read-only, same-origin NetEase access. The official page owns login and
 * Android WebView owns its cookies. No credentials or cookies are saved by
 * Lulu, printed, sent to models, or transmitted to third-party proxies.
 *
 * These read-only website endpoints aren't a contractual public SDK and may
 * change or refuse embedded login. Fail visibly; never claim account access
 * or invent missing songs from their titles.
 */
internal object NeteaseCloudAccount {
    private const val ORIGIN = "https://music.163.com"
    private val ids = Regex("^[0-9]{1,20}$")
    private const val MAX_JSON_BYTES = 6_000_000
    const val LOGIN_PAGE = "$ORIGIN/"

    fun userId(text: String): String? {
        val t = text.trim()
        if (ids.matches(t)) return t
        val match = Regex("""(?:[?&#])id=([0-9]{1,20})(?:$|[&#])""")
            .find(t)?.groupValues?.getOrNull(1)
        val uri = runCatching { Uri.parse(t) }.getOrNull()
        return match?.takeIf {
            uri?.scheme == "https" &&
                uri.host?.lowercase() in setOf("music.163.com", "y.music.163.com") &&
                uri.toString().contains("/user/home")
        }
    }

    internal fun parseAccount(root: JSONObject): NeteaseCloudProfile? {
        val account = root.optJSONObject("account")
        val profile = root.optJSONObject("profile")
        val uid = account?.optString("id").orEmpty().ifBlank {
            profile?.optString("userId").orEmpty()
        }
        if (!ids.matches(uid)) return null
        val nick = profile?.optString("nickname").orEmpty().ifBlank { "网易云用户" }
        return NeteaseCloudProfile(uid, nick)
    }

    internal fun parseLists(root: JSONObject): List<NeteaseCloudList> {
        check(root.optInt("code", 0) == 200) { "网易云没有返回歌单权限（code=${root.optInt("code")}）" }
        val items = root.optJSONArray("playlist") ?: error("歌单接口未返回 playlist 字段")
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!ids.matches(id)) return@mapNotNull null
            NeteaseCloudList(
                id = id, name = item.optString("name").ifBlank { "未命名歌单" },
                trackCount = item.optInt("trackCount").coerceAtLeast(0),
                liked = item.optInt("specialType") == 5,
                subscribed = item.optBoolean("subscribed"),
            )
        }
    }

    internal fun parseIds(root: JSONObject): List<String> {
        check(root.optInt("code", 0) == 200) { "网易云歌单详情无权访问（code=${root.optInt("code")}）" }
        val playlist = root.optJSONObject("playlist") ?: error("歌单详情缺少 playlist")
        val idsArray = playlist.optJSONArray("trackIds")
        val rawTracks = playlist.optJSONArray("tracks")
        val all = if (idsArray != null) {
            (0 until idsArray.length()).mapNotNull { i ->
                idsArray.optJSONObject(i)?.optString("id")?.takeIf(ids::matches)
            }
        } else {
            (0 until (rawTracks?.length() ?: 0)).mapNotNull { i ->
                rawTracks?.optJSONObject(i)?.optString("id")?.takeIf(ids::matches)
            }
        }
        val expected = playlist.optInt("trackCount", all.size)
        // Some playlist responses include 10 song records but thousands of
        // trackIds; silently using those first ten would truncate the library.
        check(expected <= all.size) {
            "歌单共有 $expected 首，但接口只返回 ${all.size} 个 ID；请稍后重新登录或重试"
        }
        return all.distinct()
    }

    internal fun parseSongs(root: JSONObject): List<NeteaseCloudSong> {
        check(root.optInt("code", 0) == 200) { "网易云未返回歌曲资料（code=${root.optInt("code")}）" }
        val songs = root.optJSONArray("songs") ?: error("歌曲接口没有 songs 数据")
        return (0 until songs.length()).mapNotNull { i ->
            val song = songs.optJSONObject(i) ?: return@mapNotNull null
            val id = song.optString("id").takeIf(ids::matches) ?: return@mapNotNull null
            val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists") ?: JSONArray()
            val artist = (0 until artists.length())
                .mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString(" / ").ifBlank { "未知歌手" }
            NeteaseCloudSong(id, song.optString("name").ifBlank { "未知歌曲 #$id" }, artist)
        }
    }

    /** Android CookieManager is accessed on the main thread. */
    private fun siteCookie(): String {
        val all = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
        return if (all.split(';').any { it.trim().startsWith("MUSIC_U=") }) all else ""
    }

    private fun request(path: String, cookie: String, post: String? = null): JSONObject {
        check(path.startsWith("/api/")) { "不允许访问其他站点或非只读接口" }
        val url = URL(ORIGIN + path)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false // never leak cookies via redirects
            connection.connectTimeout = 12_000
            connection.readTimeout = 16_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", "$ORIGIN/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            if (post != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { stream ->
                    stream.write(post.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            check(code == 200) { "网易云请求失败：HTTP $code" }
            val bytes = connection.inputStream.use { stream ->
                val data = java.io.ByteArrayOutputStream()
                val part = ByteArray(8192)
                while (true) {
                    val n = stream.read(part)
                    if (n < 0) break
                    check(data.size() + n <= MAX_JSON_BYTES) { "歌单响应过大，请缩小读取范围" }
                    data.write(part, 0, n)
                }
                data.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun verifyLogin(): NeteaseCloudProfile? {
        val cookie = siteCookie()
        if (cookie.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val result = runCatching { request("/api/nuser/account/get", cookie) }
                .getOrElse { request("/api/w/nuser/account/get", cookie) }
            parseAccount(result)
        }
    }

    /** Public lists work with a UID; a logged-in cookie additionally permits private lists when supported. */
    suspend fun lists(uid: String): List<NeteaseCloudList> {
        require(ids.matches(uid))
        val cookie = siteCookie()
        return withContext(Dispatchers.IO) {
            val collected = ArrayList<NeteaseCloudList>()
            var offset = 0
            repeat(20) {
                val page = request("/api/user/playlist?uid=$uid&limit=100&offset=$offset", cookie)
                val chunk = parseLists(page)
                collected.addAll(chunk)
                if (chunk.isEmpty() || !page.optBoolean("more", false)) return@withContext
                offset += chunk.size
                check(chunk.size > 0) { "歌单分页没有前进" }
            }
            collected.distinctBy { it.id }
        }
    }

    suspend fun trackIds(listId: String): List<String> {
        require(ids.matches(listId))
        val cookie = siteCookie()
        return withContext(Dispatchers.IO) {
            parseIds(request("/api/v6/playlist/detail?id=$listId", cookie))
        }
    }

    suspend fun tracks(ids: List<String>): NeteaseCloudTrackPage {
        require(ids.size <= 100 && ids.all { this.ids.matches(it) })
        if (ids.isEmpty()) return NeteaseCloudTrackPage(emptyList(), emptyList(), 0)
        val cookie = siteCookie()
        return withContext(Dispatchers.IO) {
            val payload = JSONArray().apply { ids.forEach { put(JSONObject().put("id", it)) } }
            val body = "c=" + URLEncoder.encode(payload.toString(), "UTF-8")
            val response = request("/api/v3/song/detail", cookie, body)
            val songs = parseSongs(response)
            val byId = songs.associateBy { it.id }
            val ordered = ids.mapNotNull(byId::get)
            NeteaseCloudTrackPage(ids, ordered, ids.size - ordered.size)
        }
    }
}
