package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.jiacimu.lulu.ai.VisionModelService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QqChatPhotoAttachmentStoreTest {
    @Test fun copiedPickerPhotoRemainsRealPixelsAfterSourceIsGone() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val source = File.createTempFile("picker-source-", ".png", context.cacheDir)
        val picture = Bitmap.createBitmap(10, 8, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(source).use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val persistentUri = QqChatPhotoAttachmentStore.import(context, Uri.fromFile(source))
            assertTrue(persistentUri.startsWith("file://"))
            assertTrue(source.delete())
            val pixels = VisionModelService.prepareChatImageDataUrl(context, persistentUri).getOrThrow()
            assertTrue(pixels.startsWith("data:image/jpeg;base64,"))
            assertTrue(pixels.length > 50)
        } finally {
            picture.recycle()
            source.delete()
        }
    }

    @Test fun missingPickerSourceMustNeverBecomeFakeReadyImage() {
        val context = RuntimeEnvironment.getApplication() as Context
        val missing = Uri.fromFile(File(context.cacheDir, "not-a-real-image-source.png"))
        assertTrue(runCatching { QqChatPhotoAttachmentStore.import(context, missing) }.isFailure)
    }
    @Test fun deletingCopiedPhotoCannotDeleteArbitraryGalleryFiles() {
        val context = RuntimeEnvironment.getApplication() as Context
        val gallery = File.createTempFile("external-gallery-", ".png", context.cacheDir)
        val original = Uri.fromFile(gallery).toString()
        assertFalse(QqChatPhotoAttachmentStore.deleteIfOwned(context, original))
        assertTrue(gallery.exists())
        val kept = QqChatPhotoAttachmentStore.import(context, Uri.fromFile(gallery))
        assertTrue(QqChatPhotoAttachmentStore.deleteIfOwned(context, kept))
        assertFalse(File(Uri.parse(kept).path!!).exists())
        gallery.delete()
    }

}
