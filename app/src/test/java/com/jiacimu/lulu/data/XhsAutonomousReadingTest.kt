package com.jiacimu.lulu.data

import android.app.Activity
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class XhsAutonomousReadingTest {
    // Protocol fixture only: real page extraction/submission has JS regressions.
    // Enforce Android's real thread rule, which ShadowWebView doesn't enforce.
    private class Page(activity: Activity) : WebView(activity) {
        private val noteId = "1234567890abcdef12345678"
        private var page = "https://www.xiaohongshu.com/explore"
        var submittedSearches = 0
        var openedNotes = 0
        override fun getSettings(): WebSettings {
            check(Looper.myLooper() == Looper.getMainLooper()) { "WebView read off UI thread" }
            return super.getSettings()
        }
        override fun getUrl(): String = page
        override fun loadUrl(url: String) { page = url; openedNotes++ }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val result = if (script.contains("?.startSearch(")) {
                submittedSearches++
                page = XhsReadingEngine.searchUrl("记者")
                "submitted"
            } else {
                val snapshot = JSONObject().put("onPage", true).put("diagnostics", JSONObject())
                if (page.contains("/explore/$noteId")) snapshot.put("note", JSONObject()
                    .put("id", noteId).put("title", "记者如何核对信息").put("author", "测试作者")
                    .put("body", "作者建议核对消息来源，并把亲历事实与未经证实的推测分开记录。"))
                else snapshot.put("items", JSONArray().put(JSONObject().put("id", noteId)
                    .put("token", "").put("title", "记者如何核对信息").put("source", "pc_search")))
                snapshot.toString()
            }
            resultCallback?.onReceiveValue(JSONObject.quote(result))
        }
    }

    @Test fun autonomousIoToolSearchesOpensAndLearnsWithoutAUserSharedPost() {
        MemoryTestEnvironment.release()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val browser = Page(activity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = Executors.newSingleThreadExecutor()
        val server = MockWebServer()
        try {
            LuluRepositories.initialize(activity)
            SharedExperienceTimeline.initialize(activity)
            LuluAiServices.initialize(activity)
            CharacterLearningLedger.initialize(activity)
            val role = MigratedDomainStores.characters.create("自主阅读回归", "喜欢核实信息").characterId
            val answer = """{"thought":"原来还要分清事实和猜测。","learning":{"topic":"记者","understanding":"核对消息来源，区分亲历事实与待验证猜测","uncertainty":"一篇外部笔记还需交叉核实"}}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                    JSONObject().put("content", answer)).put("finish_reason", "stop"))).toString()))
            server.start()
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "test", server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            activity.setContentView(browser)
            shadowOf(Looper.getMainLooper()).idle()
            XhsReadingEngine.setLocalEnabled(activity, true)
            XhsReadingEngine.setMcpFallbackEnabled(activity, false)
            XhsReadingEngine.attachBrowser(browser)
            XhsReadingEngine.resume(activity)
            assertTrue(worker.submit<Boolean> { XhsReadingEngine.available(activity) }.get(5, TimeUnit.SECONDS))

            val reading = scope.async {
                JSONObject(ToolRouter.execute(activity, role, "read_xiaohongshu",
                    JSONObject().put("keyword", "记者"), requestId = "autonomous-xhs-test"))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!reading.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                Thread.sleep(5)
            }
            assertTrue("IO reading must finish using the same foreground browser", reading.isCompleted)
            val result = runBlocking { reading.await() }
            assertTrue(result.toString(), result.optBoolean("success"))
            assertEquals(1, browser.submittedSearches)
            assertEquals(1, browser.openedNotes)
            val evidenceId = result.getString("evidenceId")
            val event = SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).single()
            assertEquals("xiaohongshu-webview", event.source)
            assertEquals("search", JSONObject(event.content).getString("discoveryMode"))
            assertTrue(CharacterLearningLedger.hasInsight(role, evidenceId))
            CharacterLearningAgenda.initialize(activity)
            val goal = CharacterLearningAgenda.start(role, JSONObject().put("topic", "记者")
                .put("question", "如何核实信息的可信程度").put("sourceEvidenceId", evidenceId), Instant.now())
            val attached = runBlocking { AutonomousLearningRuntime.execute(activity, role,
                JSONObject().put("op", "attach").put("goalId", goal.getString("id"))
                    .put("sourceEvidenceId", evidenceId)) }
            assertTrue(attached.summary, attached.success)
            assertEquals("practice", CharacterLearningAgenda.get(role, goal.getString("id"))!!.optString("status"))
            assertEquals("Reading and attaching reuse one actual understanding call", 1, server.requestCount)
            XhsReadingEngine.pause(activity)
            assertFalse(worker.submit<Boolean> { XhsReadingEngine.available(activity) }.get(5, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
            worker.shutdownNow()
            XhsReadingEngine.detachBrowser(browser)
            XhsReadingEngine.pause(activity)
            browser.destroy()
            controller.pause().stop().destroy()
            server.shutdown()
            MemoryTestEnvironment.release()
        }
    }
}
