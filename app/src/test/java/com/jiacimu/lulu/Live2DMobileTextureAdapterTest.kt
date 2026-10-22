package com.jiacimu.lulu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DMobileTextureAdapterTest {
    @Test fun downscalesOnlyTheDisplayAtlasAndPreservesOriginalAndModelManifest() {
        val dir = Files.createTempDirectory("lulu-2d-texture-").toFile()
        try {
            val atlas = File(dir, "texture.png")
            val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.MAGENTA)
            atlas.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val original = atlas.readBytes()
            val model = File(dir, "role.model3.json")
            val settings = """{"FileReferences":{"Moc":"role.moc3","Textures":["texture.png"]}}"""
            model.writeText(settings)
            val updated = Live2DMobileTextureAdapter.prepare(model, JSONObject(settings), maxSide = 128)
            val path = updated.getJSONObject("FileReferences").getJSONArray("Textures").getString(0)
            assertTrue(path.contains("lulu-mobile-128"))
            val output = File(dir, path)
            assertTrue(output.isFile)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.path, opts)
            assertEquals(128, opts.outWidth)
            assertEquals(128, opts.outHeight)
            assertTrue(original.contentEquals(atlas.readBytes()))
            assertEquals(settings, model.readText())
            // A second request uses the cached variant instead of recomputing it.
            val previousWrite = output.lastModified()
            Live2DMobileTextureAdapter.prepare(model, JSONObject(settings), maxSide = 128)
            assertEquals(previousWrite, output.lastModified())
        } finally {
            dir.deleteRecursively()
        }
    }
}
