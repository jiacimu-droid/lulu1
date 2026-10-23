package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DeepSeekSearchIntegrationTest {
    @Test fun reusesOnlyOfficialDeepSeekChatCredentials() {
        assertTrue(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://api.deepseek.com/v1"))
        assertTrue(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://api.deepseek.com"))
        assertTrue(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://api.deepseek.com/v1/"))
        assertFalse(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://api.deepseek.com.evil.example/v1"))
        assertFalse(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://proxy.example.com/v1"))
        assertFalse(WebSearchProvider.isOfficialDeepSeekBaseUrl("http://api.deepseek.com/v1"))
        assertFalse(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://api.deepseek.com:8443/v1"))
        assertFalse(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://api.deepseek.com/v1/tools/search"))
        assertFalse(WebSearchProvider.isOfficialDeepSeekBaseUrl("https://bad@api.deepseek.com/v1"))
    }

    @Test fun searchSnippetsAreNotPresentedAsReadPages() {
        val response = JSONObject("""{"results":[
            {"title":"真实资料","url":"https://example.org/news","snippet":"找到的网址与摘要"},
            {"title":"私网","url":"https://127.0.0.1/secret","snippet":"不可访问"},
            {"title":"重复资料","url":"https://example.org/news","snippet":"重复"}
        ]}""")
        val hits = WebSearchProvider.parseDeepSeekSearch(response)
        assertEquals(1, hits.size)
        assertEquals("真实资料", hits.single().title)
        assertEquals("https://example.org/news", hits.single().url)
        assertEquals("找到的网址与摘要", hits.single().preview)
    }
}
