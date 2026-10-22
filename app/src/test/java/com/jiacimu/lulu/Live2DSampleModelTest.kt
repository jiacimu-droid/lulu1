package com.jiacimu.lulu

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DSampleModelTest {
    @Test fun localSampleZipCanBeImportedWithoutTouchingCharacterBindings() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sampleRole = Live2DSampleModel.TEST_ROLE
        val ordinaryRole = "real-user-role-preserved"
        val otherBefore = Live2DAssets.modelUrl(context, ordinaryRole)
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        val file = File.createTempFile("hiyori-fixture-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                val files = mapOf(
                    "hiyori/hiyori.model3.json" to
                        """{"FileReferences":{"Moc":"hiyori.moc3","Textures":["texture.png"],"Physics":null}}""",
                    "hiyori/hiyori.moc3" to "mock-moc3-for-import-validation-only",
                    "hiyori/texture.png" to "mock-png-for-import-validation-only"
                )
                for ((name, body) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
            }
            val notice = Live2DAssets.importModel(context, sampleRole, Uri.fromFile(file))
            assertTrue(notice.contains("已导入"))
            assertNotNull(Live2DAssets.modelUrl(context, sampleRole))
            assertTrue(Live2DAssets.modelUrl(context, ordinaryRole) == otherBefore)
        } finally {
            file.delete()
            val relative = prefs.getString("model:$sampleRole", null)
            relative?.let { saved ->
                val parts = saved.split('/')
                if (parts.size >= 4) {
                    File(Live2DAssets.base(context), parts.take(3).joinToString("/"))
                        .deleteRecursively()
                }
            }
            prefs.edit().remove("model:$sampleRole").remove("label:$sampleRole")
                .remove("preview:$sampleRole").commit()
        }
    }
}
