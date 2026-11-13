package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual screen pixels are owner-inspectable; this is not a model memory or a fake chat photo. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RoleScreenObservationArchiveTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun observedMainScreenIsDurableAndIsolatedPerCharacter() {
        val first = "screen-record-" + UUID.randomUUID()
        val second = "screen-record-" + UUID.randomUUID()
        val bitmap = Bitmap.createBitmap(36, 72, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        try {
            val at = Instant.parse("2026-11-13T08:15:30Z")
            val record = RolePhotoAlbumStore.recordScreenObservation(
                context, first, bitmap, "com.example.reader", at, studyWatch = false)
            assertEquals(RolePhotoAlbumStore.SCREEN_OBSERVATION, record.source)
            assertTrue(record.caption.contains("角色查看"))
            assertTrue(record.caption.contains("com.example.reader"))
            assertTrue(record.caption.contains(at.toString()))
            val saved = File(requireNotNull(Uri.parse(record.uri).path))
            assertTrue(saved.isFile && saved.length() > 0L)

            assertEquals(listOf(record.id), RolePhotoAlbumStore.list(context, first).map { it.id })
            assertTrue(RolePhotoAlbumStore.list(context, second).isEmpty())
            val chosen = RolePhotoAlbumStore.saveCapture(
                context, first, bitmap, "角色自己选择收藏的截图")
            assertNotEquals(RolePhotoAlbumStore.SCREEN_OBSERVATION, chosen.source)
            assertEquals(1, RolePhotoAlbumStore.list(context, first)
                .count { it.source == RolePhotoAlbumStore.SCREEN_OBSERVATION })
            assertTrue(RolePhotoAlbumStore.delete(context, first, record.id))
            assertFalse(saved.exists())
            assertTrue(RolePhotoAlbumStore.list(context, first).none { it.id == record.id })
            assertTrue(RolePhotoAlbumStore.list(context, first).any { it.id == chosen.id })
            assertTrue(RolePhotoAlbumStore.delete(context, first, chosen.id))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun supervisionRecordHasItsOwnLabelAndCannotLackSourceApp() {
        val role = "screen-record-" + UUID.randomUUID()
        val bitmap = Bitmap.createBitmap(28, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GRAY)
            val photo = RolePhotoAlbumStore.recordScreenObservation(
                context, role, bitmap, "com.example.studying", Instant.now(),
                studyWatch = true)
            assertTrue(photo.caption.contains("学习监督"))
            assertEquals(RolePhotoAlbumStore.SCREEN_OBSERVATION, photo.source)
            assertTrue(RolePhotoAlbumStore.delete(context, role, photo.id))
            assertThrows(IllegalArgumentException::class.java) {
                RolePhotoAlbumStore.recordScreenObservation(
                    context, role, bitmap, "", Instant.now(), studyWatch = false)
            }
            assertTrue(RolePhotoAlbumStore.list(context, role).isEmpty())
        } finally {
            bitmap.recycle()
        }
    }
}
