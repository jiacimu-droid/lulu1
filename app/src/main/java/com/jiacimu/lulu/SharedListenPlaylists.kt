package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Saved shared listening lists. Keep each character's music-room library separate.
 * An external MediaSession entry is a bookmark, never a fabricated playable URL.
 */
internal data class SharedListenEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val source: String,
    val uri: String = "",
    val packageName: String = "",
    /** Exact third-party track identifier, never inferred from title/artist. */
    val songId: String = "",
)

internal data class SharedListenPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<SharedListenEntry> = emptyList(),
)

internal object SharedListenPlaylists {
    private const val PREF = "lulu_listen_playlists_v1"
    private fun key(characterId: String) = "role:$characterId"

    fun read(context: Context, characterId: String): List<SharedListenPlaylist> {
        if (characterId.isBlank()) return emptyList()
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(key(characterId), "[]").orEmpty()
        return runCatching {
            val items = JSONArray(raw)
            (0 until items.length()).mapNotNull { i ->
                val obj = items.optJSONObject(i) ?: return@mapNotNull null
                val tracks = obj.optJSONArray("tracks") ?: JSONArray()
                SharedListenPlaylist(
                    id = obj.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null,
                    name = obj.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null,
                    tracks = (0 until tracks.length()).mapNotNull { j ->
                        val item = tracks.optJSONObject(j) ?: return@mapNotNull null
                        val title = item.optString("title").takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        SharedListenEntry(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = title,
                            artist = item.optString("artist"),
                            source = item.optString("source"),
                            uri = item.optString("uri"),
                            packageName = item.optString("packageName"),
                            songId = item.optString("songId"),
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, characterId: String, playlists: List<SharedListenPlaylist>): Boolean {
        val json = JSONArray().apply {
            playlists.forEach { playlist ->
                put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                    .put("tracks", JSONArray().apply {
                        playlist.tracks.forEach { t ->
                            put(JSONObject().put("id", t.id).put("title", t.title)
                                .put("artist", t.artist).put("source", t.source)
                                .put("uri", t.uri).put("packageName", t.packageName)
                                .put("songId", t.songId))
                        }
                    }))
            }
        }
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(key(characterId), json.toString()).commit()
    }

    fun create(context: Context, characterId: String, name: String): Boolean {
        val trimmed = name.trim().take(48)
        if (trimmed.isBlank() || characterId.isBlank()) return false
        val lists = read(context, characterId)
        if (lists.any { it.name == trimmed }) return false
        return save(context, characterId, lists + SharedListenPlaylist(name = trimmed))
    }

    fun add(context: Context, characterId: String, playlistId: String, entry: SharedListenEntry): Boolean {
        val current = read(context, characterId)
        if (current.none { it.id == playlistId }) return false
        val updated = current.map { playlist ->
            if (playlist.id != playlistId) playlist else {
                val duplicate = playlist.tracks.any {
                    (entry.songId.isNotBlank() && it.songId == entry.songId &&
                        it.packageName == entry.packageName) ||
                        (it.source == entry.source && it.uri == entry.uri &&
                            it.packageName == entry.packageName && it.title == entry.title && it.artist == entry.artist)
                }
                if (duplicate) playlist else playlist.copy(tracks = playlist.tracks + entry)
            }
        }
        return save(context, characterId, updated)
    }

    /** Bind an old MediaSession bookmark to the exact owner-provided song link. */
    fun bindSongId(context: Context, characterId: String, playlistId: String,
        entryId: String, songId: String): Boolean {
        if (NeteaseSongLink.directUri(songId) == null) return false
        val lists = read(context, characterId)
        if (lists.none { list -> list.id == playlistId && list.tracks.any { it.id == entryId } })
            return false
        return save(context, characterId, lists.map { list ->
            if (list.id != playlistId) list else list.copy(tracks = list.tracks.map { track ->
                if (track.id != entryId) track else track.copy(
                    source = "external", songId = songId,
                    uri = "https://music.163.com/song?id=$songId",
                    packageName = NeteaseSongLink.packageName)
            })
        })
    }

    fun removeTrack(context: Context, characterId: String, playlistId: String, entryId: String): Boolean {
        val current = read(context, characterId)
        return save(context, characterId, current.map {
            if (it.id == playlistId) it.copy(tracks = it.tracks.filterNot { track -> track.id == entryId })
            else it
        })
    }

    fun delete(context: Context, characterId: String, playlistId: String): Boolean =
        save(context, characterId, read(context, characterId).filterNot { it.id == playlistId })
}

/**
 * Exact-song navigation only. MediaSession title/artist provide NO stable
 * NetEase song ID. Never fall back to launching the app's last played track
 * when the user tapped an individual bookmark.
 */
internal object NeteaseSongLink {
    const val packageName = "com.netease.cloudmusic"
    private val digits = Regex("^[0-9]{1,20}$")
    private val queryId = Regex("(?:[?&#])id=([0-9]{1,20})(?:$|[&#])")

    fun songId(text: String): String? {
        val trimmed = text.trim()
        if (digits.matches(trimmed)) return trimmed
        // Android's Share copies descriptions before the URL; extract only
        // a verified NetEase URL instead of treating the first word as a link.
        val candidate = Regex(
            """(?:orpheus://song/|https?://(?:y\.)?music\.163\.com/)[^\s<>）)]+""",
            RegexOption.IGNORE_CASE,
        ).find(trimmed)?.value?.trimEnd('。', '，', '.', ',')
            ?: return null
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (uri.scheme == "orpheus" && uri.host == "song") {
            return uri.pathSegments.firstOrNull()?.takeIf(digits::matches)
        }
        if (uri.scheme !in listOf("https", "http") ||
            uri.host?.lowercase() !in setOf("music.163.com", "y.music.163.com")) return null
        if (!uri.toString().contains("/song", ignoreCase = true)) return null
        return queryId.find(candidate)?.groupValues?.getOrNull(1)
            ?: uri.pathSegments.lastOrNull()?.takeIf(digits::matches)
    }

    fun directUri(id: String): Uri? =
        id.takeIf(digits::matches)?.let { Uri.parse("orpheus://song/$it/?autoplay=1") }

    fun open(context: Context, entry: SharedListenEntry): String {
        val id = entry.songId.takeIf(digits::matches)
            ?: songId(entry.uri)
            ?: return "这条旧收藏没有歌曲 ID，无法准确播放。请在音乐页粘贴该歌曲的网易云分享链接后重新收藏。"
        val appLink = directUri(id) ?: return "歌曲 ID 无效，未打开其他歌曲"
        val app = Intent(Intent.ACTION_VIEW, appLink).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(app) }.isSuccess) {
            return "已跳转网易云单曲 $id；实际播放须由网易云媒体会话确认"
        }
        // A verified public song page is preferable to launching some other
        // song from the application's existing playback queue.
        val web = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://music.163.com/song?id=$id")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(web) }.isSuccess)
            "已打开指定歌曲页面；请在网易云确认播放"
        else "未找到能打开该歌曲的应用；没有替你播放其他歌曲"
    }
}
