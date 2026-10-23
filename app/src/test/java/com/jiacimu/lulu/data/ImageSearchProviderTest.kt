package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImageSearchProviderTest {
    @Test fun serperParsesActualImageUrlsAndRejectsUnsafeMetadata() {
        val result = ImageSearchProvider.parseSerper(JSONObject("""{"images":[
            {"title":"熊猫头表情包","imageUrl":"https://img.example.com/1.png","thumbnailUrl":"https://thumb.example.com/1.png","link":"https://example.com/post"},
            {"title":"内网","imageUrl":"http://127.0.0.1/secret","link":"https://example.com/post"},
            {"title":"重复","imageUrl":"https://img.example.com/1.png","link":"https://example.com/dup"},
            {"title":"缺少来源","imageUrl":"https://img.example.com/2.png"}
        ]}"""))
        assertEquals(1, result.size)
        assertEquals("serper", result.single().provider)
        assertEquals("熊猫头表情包", result.single().title)
        assertEquals("https://example.com/post", result.single().sourceUrl)
    }
    @Test fun giphyUsesRealRenditionWithSourceCredit() {
        val result = ImageSearchProvider.parseGiphy(JSONObject("""{"data":[
            {"title":"Funny Cat","url":"https://giphy.com/gifs/cat","images":{
                "fixed_width":{"url":"https://media.giphy.com/media/abc/200w.gif"},
                "fixed_width_still":{"url":"https://media.giphy.com/media/abc/200w_s.gif"}}},
            {"title":"Missing file","url":"https://giphy.com/gifs/no","images":{}}
        ]}"""))
        assertEquals(1, result.size)
        assertEquals("giphy", result.single().provider)
        assertEquals("https://media.giphy.com/media/abc/200w.gif", result.single().imageUrl)
    }
    @Test fun searchResultsOnlyBelongToTheCharacterWhoReallySearched() {
        val context = RuntimeEnvironment.getApplication()
        val role = UUID.randomUUID().toString()
        val a = ImageSearchHit("Funny", "https://img.example.com/a.jpg",
            "https://example.com/story", "serper")
        val id = ImageSearchProvider.remember(context, role, listOf(a))
        assertEquals(a, ImageSearchProvider.select(context, role, id, 0))
        assertTrue(runCatching {
            ImageSearchProvider.select(context, role + "-other", id, 0)
        }.isFailure)
        assertTrue(runCatching {
            ImageSearchProvider.select(context, role, "fabricated", 0)
        }.isFailure)
        assertTrue(runCatching {
            ImageSearchProvider.select(context, role, id, 20)
        }.isFailure)
    }
    @Test fun verifiesFileSignatureRatherThanTrustingExtensionOrUrl() {
        assertEquals("image/png", ImageSearchProvider.sniff(byteArrayOf(-119,80,78,71,13,10,26,10,0,0,0,0)))
        assertEquals("image/gif", ImageSearchProvider.sniff("GIF89a……".toByteArray()))
        assertEquals("image/webp", ImageSearchProvider.sniff("RIFFxxxxWEBP".toByteArray()))
        assertEquals("image/jpeg", ImageSearchProvider.sniff(byteArrayOf(-1,-40,-1,0,0,0,0,0,0,0,0,0)))
        assertEquals(null, ImageSearchProvider.sniff("<html>error</html>".toByteArray()))
    }
}
