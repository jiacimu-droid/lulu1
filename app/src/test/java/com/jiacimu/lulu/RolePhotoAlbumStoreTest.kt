package com.jiacimu.lulu

import android.graphics.Bitmap
import android.net.Uri
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RolePhotoAlbumStoreTest {
    @Test fun savedPhotoSurvivesSourceDeletionAndNeverAppearsInAnotherCharactersAlbum() {
        val context = RuntimeEnvironment.getApplication() as Context
        val first = "album-a-" + UUID.randomUUID()
        val second = "album-b-" + UUID.randomUUID()
        val original = File(context.filesDir, "album-src-${UUID.randomUUID()}.png")
        val pixels = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        try {
            original.outputStream().use {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val saved = RolePhotoAlbumStore.saveUri(context, first,
                Uri.fromFile(original).toString(), "聊天收到的照片", "今天的天空")
            assertTrue(original.delete())
            assertTrue(File(Uri.parse(saved.uri).path!!).isFile)
            assertEquals(1, RolePhotoAlbumStore.list(context, first).size)
            assertEquals("今天的天空", RolePhotoAlbumStore.list(context, first).single().caption)
            assertTrue(RolePhotoAlbumStore.list(context, second).isEmpty())
            assertTrue(RolePhotoAlbumStore.delete(context, first, saved.id))
            assertFalse(File(Uri.parse(saved.uri).path!!).exists())
            assertTrue(RolePhotoAlbumStore.list(context, first).isEmpty())
        } finally {
            pixels.recycle()
            original.delete()
        }
    }

    @Test fun arbitraryExternalFileIsNeverCopiedIntoRoleAlbum() {
        val context = RuntimeEnvironment.getApplication() as Context
        assertThrows(IllegalArgumentException::class.java) {
            RolePhotoAlbumStore.saveUri(context, "role-${UUID.randomUUID()}",
                Uri.fromFile(File("/sdcard/Download/private.png")).toString(), "外部文件")
        }
    }
}
