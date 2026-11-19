package com.jiacimu.lulu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ListenTogetherPlaylistTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Test fun playlistsAreCharacterScopedAndPersistSongOrigins() {
        val roleA = "playlist-role-a"
        val roleB = "playlist-role-b"
        assertTrue(SharedListenPlaylists.create(context, roleA, "夜间散步"))
        val list = SharedListenPlaylists.read(context, roleA).single()
        val external = SharedListenEntry(title = "试播歌曲", artist = "演唱者",
            source = "external", packageName = "com.netease.cloudmusic")
        assertTrue(SharedListenPlaylists.add(context, roleA, list.id, external))
        assertTrue(SharedListenPlaylists.add(context, roleA, list.id, external))
        assertEquals(1, SharedListenPlaylists.read(context, roleA).single().tracks.size)
        assertTrue(SharedListenPlaylists.read(context, roleB).isEmpty())
        assertEquals("external", SharedListenPlaylists.read(context, roleA).single().tracks.single().source)
        assertTrue(SharedListenPlaylists.removeTrack(context, roleA, list.id, external.id))
        assertTrue(SharedListenPlaylists.read(context, roleA).single().tracks.isEmpty())
    }

    @Test fun parserKeepsPausedNeteaseSessionButDoesNotClaimAudio() {
        val paused = JSONObject().put("packageName", "com.netease.cloudmusic")
            .put("title", "正在暂停的歌").put("artist", "歌手")
            .put("playing", false).put("positionMs", 18000L).put("durationMs", 220000L)
        val snapshot = JSONObject().put("success", true).put("sessions",
            JSONArray().put(paused)).toString()
        val result = listenedMediaSession(snapshot)
        assertEquals("", result.second)
        assertEquals("正在暂停的歌", result.first?.title)
        assertEquals(false, result.first?.playing)
        assertEquals(18000L, result.first?.positionMs)
        assertEquals(220000L, result.first?.durationMs)
    }

    @Test fun invalidMetadataIsNotARealPlayer() {
        val result = listenedMediaSession("{\"success\":true,\"sessions\":[]}")
        assertNull(result.first)
        assertTrue(result.second.isNotBlank())
    }
}
