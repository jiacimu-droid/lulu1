package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GoogleApiChannelTest {
    @Test fun editingAnExistingGoogleConnectionPreservesItsModelAndStoresAllKeys() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = ModelConnectionStore.create(context)
        val legacy = store.saveConfiguration(null, "我的谷歌", GOOGLE_API_BASE_URL, "fixture-1")
        val archive = store.addArchive(legacy.id, "gemini-test")
        val extraKeys = (2..12).map { "fixture-$it" }
        store.saveConfiguration(legacy.id, "Google", "https://proxy.test/v1", "fixture-1",
            googleAdditionalKeys = extraKeys, googleRotationEnabled = true)
        val restored = ModelConnectionStore.create(context)
        val connection = restored.resolveConnection(archive.id)
        assertEquals("https://proxy.test/v1", connection.baseUrl)
        assertEquals("gemini-test", connection.model)
        assertEquals(legacy.id, connection.googleRotationId)
        assertEquals(extraKeys, connection.googleAdditionalKeys)

        restored.saveConfiguration(legacy.id, "Google", connection.baseUrl, connection.apiKey,
            googleAdditionalKeys = extraKeys, googleRotationEnabled = false)
        val disabled = ModelConnectionStore.create(context).resolveConnection(archive.id)
        assertNull(disabled.googleRotationId)
        assertEquals("fixture-1", disabled.apiKey)
        assertEquals(extraKeys, disabled.googleAdditionalKeys)
    }

    @Test(timeout = 15_000) fun modelListingAndPostUseTheSameSavedPoolAndRealHttpRejections() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        val store = ModelConnectionStore.create(context)
        val gateway = CompanionModelGateway(store)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"models/gemini-test"}]}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""
                {"error":{"message":"invalid key","details":[{
                "@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val saved = store.saveConfiguration(null, "Google", server.url("/v1beta/openai/").toString(),
                "first-fixture", googleAdditionalKeys = listOf("second-fixture"), googleRotationEnabled = true)
            val archive = store.addArchive(saved.id, gateway.fetchModels(saved).getOrThrow().single())
            val result = gateway.requestOpenAiJson(store.resolveConnection(archive.id), "chat/completions",
                JSONObject().put("model", archive.model))
            assertTrue(result.getBoolean("ok"))
            val requests = (1..4).map { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            assertEquals(listOf("GET", "GET", "POST", "POST"), requests.map { it.method })
            assertEquals(listOf("models", "models", "chat/completions", "chat/completions")
                .map { "/v1beta/openai/$it" }, requests.map { it.path })
            assertEquals(listOf("first-fixture", "second-fixture", "second-fixture", "first-fixture")
                .map { "Bearer $it" }, requests.map { it.getHeader("Authorization") })
            assertEquals(4, server.requestCount)
        }
    }
}
