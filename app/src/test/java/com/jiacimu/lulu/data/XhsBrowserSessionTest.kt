package com.jiacimu.lulu.data

import android.app.Activity
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class XhsBrowserSessionTest {
    @Test fun noteDetailUrlIsRecognizedEvenWhenWebViewHasNoBackHistory() {
        val id = "1234567890abcdef12345678"
        assertTrue(com.jiacimu.lulu.xhsBrowserIsNoteUrl(
            "https://www.xiaohongshu.com/explore/$id?xsec_token=abc"))
        assertTrue(com.jiacimu.lulu.xhsBrowserIsNoteUrl(
            "https://www.xiaohongshu.com/discovery/item/$id"))
        assertFalse(com.jiacimu.lulu.xhsBrowserIsNoteUrl(
            "https://www.xiaohongshu.com/explore"))
        assertFalse(com.jiacimu.lulu.xhsBrowserIsNoteUrl(
            "https://untrusted.example/explore/$id"))
    }

    @Test fun characterReadFailureDiagnosisShowsActualStageButNotSessionTokens() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        XhsReadingEngine.recordToolStage(context, "笔记正文",
            "连接失败 https://www.xiaohongshu.com/explore/abcdef123456789012345678?xsec_token=secret")
        val diagnostic = XhsReadingEngine.lastDiagnostic(context)
        assertTrue(diagnostic.contains("笔记正文"))
        assertTrue(diagnostic.contains("failed"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("abcdef123456789012345678"))
        XhsReadingEngine.recordToolStage(context, "笔记图文已核验")
        assertTrue(XhsReadingEngine.lastDiagnostic(context).contains("succeeded"))
    }

    /** This fixture tests Android ownership only; production JS parsing has independent browser tests. */
    private class LoginBrowser(activity: Activity) : WebView(activity) {
        var navigations = 0
        var searches = 0
        var clientChanges = 0
        var destroyed = false
        override fun getUrl(): String = "https://www.xiaohongshu.com/explore"
        override fun loadUrl(url: String) { navigations++ }
        override fun setWebViewClient(client: WebViewClient) { clientChanges++; super.setWebViewClient(client) }
        override fun destroy() { destroyed = true; super.destroy() }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            if (script.contains("const reader = window.__luluXhsReader;")) {
                searches++
                resultCallback?.onReceiveValue(JSONObject.quote("submitted"))
            } else {
                val items = JSONArray()
                if (searches > 0) items.put(JSONObject().put("id", "1234567890abcdef12345678")
                    .put("token", "fixture-token").put("title", "搜索结果").put("author", "作者").put("source", "pc_search"))
                resultCallback?.onReceiveValue(JSONObject.quote(JSONObject().put("items", items)
                    .put("onPage", searches > 0).put("diagnostics", JSONObject()).toString()))
            }
        }
    }

    @Test fun nativeSearchKeepsTheAttachedLoginBrowserAndItsClientAlive() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val browser = LoginBrowser(activity)
        activity.setContentView(browser)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(browser.isAttachedToWindow)
        browser.clientChanges = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            XhsReadingEngine.setLocalEnabled(activity, true)
            XhsReadingEngine.resume(activity)
            XhsReadingEngine.attachBrowser(browser)
            val reading = scope.async { XhsReadingEngine.list(activity, "记者") }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!reading.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                Thread.sleep(5)
            }
            assertTrue("Session reading did not complete", reading.isCompleted)
            val result = kotlinx.coroutines.runBlocking { reading.await() }
            assertEquals("1234567890abcdef12345678", result.single().id)
            assertEquals(1, browser.searches)
            assertEquals(0, browser.navigations)
            assertEquals(0, browser.clientChanges)
            assertFalse(browser.destroyed)
            assertFalse(XhsReadingEngine.controls(browser))
        } finally {
            scope.cancel()
            XhsReadingEngine.detachBrowser(browser)
            XhsReadingEngine.pause(activity)
            browser.destroy()
            controller.pause().stop().destroy()
        }
    }
}
