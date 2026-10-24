package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListenTogetherRoomTest {
    @Test fun noPermissionDoesNotPretendToHearMusic() {
        val (track, error) = listenedMediaSession(
            JSONObject().put("success", false).put("error", "需要通知使用权").toString())
        assertNull(track)
        assertTrue(error.contains("通知使用权"))
    }

    @Test fun onlyThePlayingMediaSessionIsShared() {
        val source = JSONObject().put("success", true)
            .put("sessions", JSONArray()
                .put(JSONObject().put("title", "暂停的歌").put("playing", false))
                .put(JSONObject().put("title", "雨中行走").put("artist", "某歌手")
                    .put("packageName", "test.music").put("playing", true)))
        val (track, error) = listenedMediaSession(source.toString())
        assertEquals("", error)
        assertNotNull(track)
        assertEquals("雨中行走", track?.title)
        assertEquals("某歌手", track?.artist)
    }

    @Test fun startingSharedLocalPlaybackRequestsAnalysisOnce() {
        val clip = "content://user-selected/audio/1"
        assertTrue(shouldAutoAnalyzeSharedAudio(clip, "", false))
        assertFalse(shouldAutoAnalyzeSharedAudio(clip, clip, false))
        assertFalse(shouldAutoAnalyzeSharedAudio(clip, "", true))
        assertFalse(shouldAutoAnalyzeSharedAudio("", "", false))
        // A different shared file gets its own analysis, without forcing repeats on resume.
        assertTrue(shouldAutoAnalyzeSharedAudio("content://user-selected/audio/2", clip, false))
    }

    @Test fun noPlayingSessionIsNotProofOfListening() {
        val source = JSONObject().put("success", true)
            .put("sessions", JSONArray()
                .put(JSONObject().put("title", "只暂停").put("playing", false)))
        val (track, error) = listenedMediaSession(source.toString())
        assertNull(track)
        assertTrue(error.isNotBlank())
    }
}
