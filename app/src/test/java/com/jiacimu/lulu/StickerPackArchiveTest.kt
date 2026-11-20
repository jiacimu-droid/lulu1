package com.jiacimu.lulu

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class StickerPackArchiveTest {
    @Test fun importsWholePackWithCachedFilesAndDescriptionsWithoutVision() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        bitmap.recycle()
        val zip = File(context.cacheDir, "lulu-stickers-archive-test.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("cat/hello.png"))
                output.write(png)
                output.closeEntry()
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write("""
                    {
                        "pack": "朋友整理的猫猫",
                        "items": [{
                            "file": "cat/hello.png",
                            "name": "猫猫问候",
                            "description": "一只猫咪在打招呼",
                            "usage": "见面、欢迎"
                        }]
                    }
                """.trimIndent().toByteArray())
                output.closeEntry()
            }
            val result = StickerPackArchive.import(context, Uri.fromFile(zip), "猫猫.zip")
            assertEquals("朋友整理的猫猫", result.pack)
            assertEquals(1, result.imported)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            val sticker = StickerLibraryStore.items.value.first { it.pack == result.pack && it.name == "猫猫问候" }
            assertEquals("一只猫咪在打招呼", sticker.visualDescription)
            assertTrue(StickerLibraryStore.canActuallySend(context, sticker))
            val again = StickerPackArchive.import(context, Uri.fromFile(zip), "猫猫.zip")
            assertEquals(0, again.imported)
            assertEquals(1, again.skipped)
        } finally {
            zip.delete()
        }
    }
}
