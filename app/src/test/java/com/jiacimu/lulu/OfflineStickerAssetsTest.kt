package com.jiacimu.lulu

import com.caverock.androidsvg.SVG
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OfflineStickerAssetsTest {
    @Test fun missingStickerFilesCannotBeReportedAsActuallySent() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val file = java.io.File(context.cacheDir, "lulu-sticker-readability-test.png")
        file.writeBytes(byteArrayOf(137.toByte(), 80, 78, 71))
        val sticker = LuluSticker("readability-test",
            android.net.Uri.fromFile(file).toString(), "测试表情")
        try {
            assertTrue(StickerLibraryStore.canActuallySend(context, sticker))
        } finally {
            file.delete()
        }
        assertFalse(StickerLibraryStore.canActuallySend(context, sticker))
        assertFalse(StickerLibraryStore.canActuallySend(context,
            sticker.copy(uri = "file:///nonexistent/sticker.png")))
    }

    @Test fun allCuratedDescriptionsCorrespondToActualBundledSvgFiles() {
        val app = RuntimeEnvironment.getApplication()
        val ids = mutableSetOf<String>()
        for (pack in listOf("cats", "animals", "moods")) {
            val root = app.assets.open("stickers/$pack.json")
                .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            assertEquals("OpenMoji", root.optString("source"))
            assertEquals("CC BY-SA 4.0", root.optString("license"))
            val items = root.getJSONArray("items")
            for (index in 0 until items.length()) {
                val icon = items.getJSONObject(index)
                val code = icon.getString("code")
                assertTrue("Duplicate icon: $code", ids.add(code))
                val svg = icon.getString("svg")
                assertTrue("No SVG body: $code", svg.contains("<svg"))
                assertNotNull("Not renderable: $code", SVG.getFromString(svg).renderToPicture(180, 180))
            }
        }
        assertEquals(29, ids.size)
        assertEquals(ids, BundledCuteStickerCatalog.items.map(BuiltInCuteSticker::code).toSet())
    }
}
