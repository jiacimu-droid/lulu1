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

    @Test fun termuxNormalizedFeedIsNotDiscardedForMissingNoteCard() {
        val id = "1234567890abcdef12345678"
        val normalized = JSONObject().put("feeds", JSONArray().put(JSONObject()
            .put("id", id).put("xsecToken", "xsec-token-real")
            .put("title", "一篇真实可读笔记").put("authorName", "作者甲")))
        val cards = XhsReadingEngine.parseMcpCards(normalized, "猫咪")
        assertEquals(1, cards.size)
        assertEquals(id, cards.single().id)
        assertEquals("xsec-token-real", cards.single().token)
        assertEquals("一篇真实可读笔记", cards.single().title)
        assertEquals("作者甲", cards.single().author)
        assertEquals("mcp", cards.single().provider)

        val legacy = JSONObject().put("feeds", JSONArray().put(JSONObject()
            .put("id", id).put("xsecToken", "legacy-token")
            .put("noteCard", JSONObject().put("displayTitle", "旧版结构")
                .put("user", JSONObject().put("nickname", "作者乙")))))
        assertEquals("旧版结构", XhsReadingEngine.parseMcpCards(legacy, "").single().title)
        assertEquals("作者乙", XhsReadingEngine.parseMcpCards(legacy, "").single().author)
    }

    @Test fun termuxImageUrlArrayAndTraditionalImageObjectBothSurviveDetailParsing() {
        val target = XhsCard("1234567890abcdef12345678", "valid-token", "标题", "作者", "pc_feed", "mcp")
        val raw = JSONObject().put("data", JSONObject().put("note", JSONObject()
            .put("id", target.id).put("title", "详情")
            .put("desc", "真正获取的文字正文")
            .put("imageList", JSONArray()
                .put("https://sns-img-bd.xhscdn.com/image-one")
                .put(JSONObject().put("urlDefault", "https://sns-img-bd.xhscdn.com/image-two")))))
        val parsed = XhsReadingEngine.normalizeMcpNote(raw, target)
        assertEquals("真正获取的文字正文", parsed.getString("body"))
        val images = parsed.getJSONArray("images")
        assertEquals(2, images.length())
        assertEquals("https://sns-img-bd.xhscdn.com/image-one",
            images.getJSONObject(0).getString("url"))
        assertEquals("https://sns-img-bd.xhscdn.com/image-two",
            images.getJSONObject(1).getString("url"))
    }

    @Test fun anotherPostIdCannotBeSilentlyMarkedAsTheRequestedArticle() {
        val target = XhsCard("1234567890abcdef12345678", "token", "标题", "作者", "pc_feed", "mcp")
        val wrong = JSONObject().put("data", JSONObject().put("note",
            JSONObject().put("id", "aaaa567890abcdef12345678").put("desc", "别人的帖子")))
        assertThrows(IllegalArgumentException::class.java) {
            XhsReadingEngine.normalizeMcpNote(wrong, target)
        }
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
