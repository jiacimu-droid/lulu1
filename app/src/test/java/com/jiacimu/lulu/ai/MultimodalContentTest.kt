package com.jiacimu.lulu.ai

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MultimodalContentTest {
    private val pixels = "data:image/jpeg;base64,YWJj"

    @Test fun ordinaryTextCallsKeepTheirOriginalShape() {
        assertEquals("原文", MultimodalContent.user("原文", emptyList(), false))
        assertEquals("原文", MultimodalContent.user("原文", emptyList(), true))
    }

    @Test fun compatibleApiReceivesTextAndActualPixelsTogether() {
        val content = MultimodalContent.user("正文及角色理解任务", listOf(pixels, pixels), false) as JSONArray
        assertEquals(3, content.length())
        assertEquals("正文及角色理解任务", content.getJSONObject(0).getString("text"))
        assertEquals(pixels, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("image_url", content.getJSONObject(2).getString("type"))
    }

    @Test fun nativeClaudeReceivesBase64SourceAndTextInTheSameMessage() {
        val content = MultimodalContent.user("正文", listOf(pixels), true) as JSONArray
        val source = content.getJSONObject(1).getJSONObject("source")
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals("YWJj", source.getString("data"))
        assertEquals("正文", content.getJSONObject(0).getString("text"))
    }

    @Test fun imageLinksAloneAndOversizedBatchesAreRejected() {
        assertTrue(runCatching { MultimodalContent.user("正文", listOf("https://sns-img.xhscdn.com/a"), false) }.isFailure)
        assertTrue(runCatching { MultimodalContent.user("正文", List(7) { pixels }, true) }.isFailure)
    }

    @Test fun dedicatedVisionModelIsOptionalAndDoesNotReplaceChatSelection() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_advanced_settings", 0)
        val keys = listOf(VisionModelService.KEY_BASE_URL, VisionModelService.KEY_API_KEY, VisionModelService.KEY_MODEL)
        val old = keys.associateWith { prefs.getString(it, null) }
        try {
            val clear = prefs.edit(); keys.forEach { clear.remove(it) }; clear.commit()
            assertNull(VisionModelService.xhsConnectionOverride(context))
            prefs.edit().putString(VisionModelService.KEY_BASE_URL, "https://example.test/v1")
                .putString(VisionModelService.KEY_API_KEY, "test").putString(VisionModelService.KEY_MODEL, "visual").commit()
            assertEquals(ModelConnection("https://example.test/v1", "test", "visual"), VisionModelService.xhsConnectionOverride(context))
        } finally {
            val restore = prefs.edit(); old.forEach { (key, value) -> restore.putString(key, value) }; restore.commit()
        }
    }
}
