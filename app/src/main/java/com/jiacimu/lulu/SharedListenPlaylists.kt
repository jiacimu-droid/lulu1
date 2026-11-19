package com.jiacimu.lulu

import android.content.Context
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
                                .put("uri", t.uri).put("packageName", t.packageName))
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
                    it.source == entry.source && it.uri == entry.uri &&
                        it.packageName == entry.packageName && it.title == entry.title && it.artist == entry.artist
                }
                if (duplicate) playlist else playlist.copy(tracks = playlist.tracks + entry)
            }
        }
        return save(context, characterId, updated)
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
