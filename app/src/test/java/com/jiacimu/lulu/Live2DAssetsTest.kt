package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DAssetsTest {
    @Test fun characterModelBindingNeverReadsOutsidePrivateStore() {
        val context = RuntimeEnvironment.getApplication()
        val role = "role-" + UUID.randomUUID()
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:$role", "models/../../private-secret.json").commit()
        assertNull(Live2DAssets.modelUrl(context, role))
        assertFalse(Live2DAssets.modelLabel(context, role).isNotBlank())
    }

    @Test fun exposesOnlyImportedModelControlsAndSameOriginUrl() {
        val context = RuntimeEnvironment.getApplication()
        val role = "role-" + UUID.randomUUID()
        val relative = "models/test-" + UUID.randomUUID() + "/model.model3.json"
        val file = File(Live2DAssets.base(context), relative)
        file.parentFile?.mkdirs()
        file.writeText(
            """{"FileReferences":{"Moc":"model.moc3","Textures":["texture.png"],"Expressions":[{"Name":"smile","File":"smile.exp3.json"}],"Motions":{"Idle":[{"File":"idle.motion3.json"},{"File":"idle2.motion3.json"}]}}}"""
        )
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:$role", relative).commit()
        val url = Live2DAssets.modelUrl(context, role)
        assertTrue(url.orEmpty().startsWith("https://appassets.androidplatform.net/private/models/"))
        assertEquals(listOf("smile"), Live2DAssets.controls(context, role).expressions)
        assertEquals(listOf("Idle" to 0, "Idle" to 1), Live2DAssets.controls(context, role).motions)
        file.parentFile?.deleteRecursively()
        prefs.edit().remove("model:$role").commit()
    }
    @Test fun oldWolfImportUsesOnlyRealPortraitNeverMobileTextureCache() {
        val context = RuntimeEnvironment.getApplication()
        val role = "wolf-" + UUID.randomUUID()
        val relative = "models/wolf-" + UUID.randomUUID() + "/小狼.model3.json"
        val model = File(Live2DAssets.base(context), relative)
        val folder = model.parentFile!!
        val atlasFolder = File(folder, "小狼.8192").apply { mkdirs() }
        model.writeText(
            """{"FileReferences":{"Moc":"小狼.moc3","Textures":["小狼.8192/texture.png"]}}"""
        )
        File(atlasFolder, "texture.png").writeText("texture")
        val cache = File(atlasFolder, "texture.lulu-mobile-4096.png").apply { writeText("atlas") }
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:" + role, relative)
            .putString("preview:" + role, cache.relativeTo(Live2DAssets.base(context))
                .invariantSeparatorsPath).commit()
        try {
            // The old preferences may point directly at an atlas cache.
            assertNull(Live2DAssets.previewFile(context, role))
            val portrait = File(folder, "小狼.png").apply { writeText("portrait") }
            assertEquals(portrait.canonicalPath,
                Live2DAssets.previewFile(context, role)?.canonicalPath)
        } finally {
            folder.deleteRecursively()
            prefs.edit().remove("model:" + role).remove("preview:" + role).commit()
        }
    }

}
