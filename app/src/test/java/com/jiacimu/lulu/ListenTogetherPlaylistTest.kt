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

    @Test fun exactNeteaseSongLinkNeverFallsBackToCurrentQueue() {
        for (link in listOf(
            "https://music.163.com/song?id=2059330001",
            "分享某歌手的单曲《这首歌》: https://music.163.com/song?id=2059330001&userid=23 (来自网易云音乐)",
            "https://music.163.com/#/song?id=2059330001",
            "https://y.music.163.com/m/song?id=2059330001",
            "orpheus://song/2059330001/?autoplay=1",
            "2059330001",
        )) assertEquals("2059330001", NeteaseSongLink.songId(link))
        assertNull(NeteaseSongLink.songId("https://music.163.com/playlist?id=2059330001"))
        assertNull(NeteaseSongLink.songId("https://other.example.com/song?id=2059330001"))
        assertNull(NeteaseSongLink.songId("一首只有歌名的旧收藏"))
        assertEquals("orpheus://song/2059330001/?autoplay=1",
            NeteaseSongLink.directUri("2059330001").toString())
        val legacy = SharedListenEntry(title = "旧收藏", artist = "不知道歌号",
            source = "external", packageName = NeteaseSongLink.packageName)
        assertTrue(NeteaseSongLink.open(context, legacy).contains("没有歌曲 ID"))
    }

    @Test fun legacyBookmarkCanBeBoundToAnExactTrackWithoutLosingItsTitle() {
        val role = "bound-music-role"
        assertTrue(SharedListenPlaylists.create(context, role, "我喜欢的"))
        val list = SharedListenPlaylists.read(context, role).single()
        val legacy = SharedListenEntry(title = "收藏时显示的歌", artist = "歌手",
            source = "external", packageName = NeteaseSongLink.packageName)
        assertTrue(SharedListenPlaylists.add(context, role, list.id, legacy))
        assertTrue(SharedListenPlaylists.bindSongId(context, role, list.id, legacy.id, "2059330001"))
        val saved = SharedListenPlaylists.read(context, role).single().tracks.single()
        assertEquals("收藏时显示的歌", saved.title)
        assertEquals("2059330001", saved.songId)
        assertFalse(SharedListenPlaylists.bindSongId(context, role, list.id, legacy.id, "not-an-id"))
    }

    @Test fun parserKeepsPausedNeteaseSessionButDoesNotClaimAudio() {
        val paused = JSONObject().put("packageName", "com.netease.cloudmusic")
            .put("title", "正在暂停的歌").put("artist", "歌手")
            .put("playing", false).put("positionMs", 18000L).put("durationMs", 220000L)
        val snapshot = JSONObject().put("success", true).put("sessions",
            JSONArray().put(paused)).toString()
        // A paused controller can resume playback but is not listening evidence.
        val actualPlayback = listenedMediaSession(snapshot)
        assertNull(actualPlayback.first)
        assertEquals("已找到播放器，但当前没有报告正在播放的歌曲",
            actualPlayback.second)
        val controllable = controllableMediaSession(snapshot)
        assertEquals("", controllable.second)
        assertEquals("正在暂停的歌", controllable.first?.title)
        assertEquals(false, controllable.first?.playing)
        assertEquals(18000L, controllable.first?.positionMs)
        assertEquals(220000L, controllable.first?.durationMs)
    }

    @Test fun invalidMetadataIsNotARealPlayer() {
        val result = listenedMediaSession("{\"success\":true,\"sessions\":[]}")
        assertNull(result.first)
        assertTrue(result.second.isNotBlank())
    }
}
