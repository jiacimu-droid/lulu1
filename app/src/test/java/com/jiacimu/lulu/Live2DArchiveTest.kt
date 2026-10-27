package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DArchiveTest {
    @Test fun decodeUNameRestoresChineseNames() {
        assertEquals("小狼.moc3", Live2DAssets.decodeUName("#U5c0f#U72fc.moc3"))
        assertEquals("小狼.8192", Live2DAssets.decodeUName("#U5c0f#U72fc.8192"))
        assertEquals("plain.png", Live2DAssets.decodeUName("plain.png"))
        assertEquals("#U12.png", Live2DAssets.decodeUName("#U12.png"))
    }

    @Test fun listArchivesOnlyReturnsExistingModels() {
        val context = RuntimeEnvironment.getApplication()
        val role = "archive-role-test"
        val relative = "models/archive-test/x/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile?.mkdirs()
        settings.writeText("{}")
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit()
            .putString("model:$role", relative)
            .putString("label:$role", "小狼")
            .putString(
                "archives:$role",
                """[{"settings":"$relative","label":"小狼"},{"settings":"models/nope/x/b.model3.json","label":"gone"}]""",
            )
            .commit()
        try {
            val archives = Live2DAssets.listArchives(context, role)
            assertEquals(1, archives.size)
            assertEquals(relative, archives.single().settings)
            assertEquals("小狼", archives.single().label)
            val modeled = Live2DAssets.listModeledCharacters(context)
            assertTrue(modeled.any { it.first == role && it.second == "小狼" })
        } finally {
            settings.parentFile?.deleteRecursively()
            prefs.edit()
                .remove("model:$role").remove("label:$role").remove("archives:$role")
                .commit()
        }
    }
}
