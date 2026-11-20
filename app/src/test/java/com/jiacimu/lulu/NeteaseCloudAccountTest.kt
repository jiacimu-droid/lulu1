package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NeteaseCloudAccountTest {
    @Test fun accountMustComeFromRealProfileNotLoginPageOrCookiePresence() {
        assertNull(NeteaseCloudAccount.parseAccount(JSONObject().put("code", 301)))
        assertNull(NeteaseCloudAccount.parseAccount(JSONObject().put("account", JSONObject())))
        val loggedIn = JSONObject().put("account", JSONObject().put("id", 987654321L))
            .put("profile", JSONObject().put("nickname", "真实登录者").put("userId", 987654321L))
        assertEquals("987654321", NeteaseCloudAccount.parseAccount(loggedIn)?.uid)
        assertEquals("真实登录者", NeteaseCloudAccount.parseAccount(loggedIn)?.nickname)
    }

    @Test fun cloudListsMarkLikedAndSubscribedWithoutReplacingOwnerData() {
        val payload = JSONObject().put("code", 200).put("playlist", JSONArray()
            .put(JSONObject().put("id", 111L).put("name", "我喜欢的音乐")
                .put("specialType", 5).put("trackCount", 501))
            .put(JSONObject().put("id", 222L).put("name", "收藏的一张歌单")
                .put("subscribed", true).put("trackCount", 30)))
        val result = NeteaseCloudAccount.parseLists(payload)
        assertEquals(2, result.size)
        assertTrue(result[0].liked)
        assertFalse(result[0].subscribed)
        assertTrue(result[1].subscribed)
        assertFalse(result[1].liked)
        assertEquals(501, result[0].trackCount)
        assertEquals("222", result[1].id)
    }

    @Test fun fullTrackIdsAreUsedInsteadOfTruncatedTenTrackSample() {
        val playlist = JSONObject()
            .put("trackCount", 215)
            .put("tracks", JSONArray().apply {
                repeat(10) { put(JSONObject().put("id", it + 1)) }
            })
            .put("trackIds", JSONArray().apply {
                repeat(215) { put(JSONObject().put("id", it + 1)) }
            })
        val result = NeteaseCloudAccount.parseIds(
            JSONObject().put("code", 200).put("playlist", playlist))
        assertEquals(215, result.size)
        assertEquals("215", result.last())
        playlist.remove("trackIds")
        assertThrows(IllegalStateException::class.java) {
            NeteaseCloudAccount.parseIds(
                JSONObject().put("code", 200).put("playlist", playlist))
        }
    }

    @Test fun songMetadataPreservesExactlySelectedTrackIds() {
        val root = JSONObject().put("code", 200).put("songs", JSONArray().put(
            JSONObject().put("id", 185000001L).put("name", "第一首")
                .put("ar", JSONArray().put(JSONObject().put("name", "甲"))
                    .put(JSONObject().put("name", "乙")))))
        val result = NeteaseCloudAccount.parseSongs(root)
        assertEquals("185000001", result.single().id)
        assertEquals("第一首", result.single().title)
        assertEquals("甲 / 乙", result.single().artist)
    }

    @Test fun userIdIsValidatedAndNeverAcceptedFromAnotherDomain() {
        assertEquals("1234567", NeteaseCloudAccount.userId("1234567"))
        assertEquals("1234567", NeteaseCloudAccount.userId(
            "https://music.163.com/#/user/home?id=1234567"))
        assertNull(NeteaseCloudAccount.userId("https://evil.example/user/home?id=1234567"))
        assertNull(NeteaseCloudAccount.userId("https://music.163.com/song?id=1234567"))
        assertNull(NeteaseCloudAccount.userId("12;DROP TABLE"))
    }
}
