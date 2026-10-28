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

    @Test fun switchingArchivesPreservesPerModelTouchCalibrationAndRemovingDuplicateDoesNotEraseSelected() {
        val context = RuntimeEnvironment.getApplication()
        val role = "archive-selection-" + java.util.UUID.randomUUID()
        val root = Live2DAssets.base(context)
        val a = "models/archive-test/${role}-a/avatar.model3.json"
        val b = "models/archive-test/${role}-b/avatar.model3.json"
        for (relative in listOf(a,b)) File(root,relative).apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1",0)
        val touchPrefs = context.getSharedPreferences("lulu_live2d_touch_zones_v1",0)
        val urlA = "https://appassets.androidplatform.net/private/" + a
        val urlB = "https://appassets.androidplatform.net/private/" + b
        prefs.edit().putString("model:$role",b).putString("label:$role","小狼")
            .putString("archives:$role",
                """[{"settings":"$a","label":"小狼"},{"settings":"$b","label":"小狼"}]""").commit()
        touchPrefs.edit().putString(urlA,"calibrated").putString(urlB,"untouched").commit()
        try {
            assertEquals(2,Live2DAssets.listArchives(context,role).size)
            assertTrue(Live2DAssets.switchArchive(context,role,a))
            assertEquals(a,Live2DAssets.currentModelSettings(context,role))
            assertEquals(urlA,Live2DAssets.modelUrl(context,role))
            assertEquals("calibrated",touchPrefs.getString(urlA,null))
            assertTrue(Live2DAssets.deleteArchive(context,role,b))
            assertEquals(a,Live2DAssets.currentModelSettings(context,role))
            assertEquals("calibrated",touchPrefs.getString(urlA,null))
            assertEquals(null,touchPrefs.getString(urlB,null))
            assertTrue(File(root,a).isFile)
            assertTrue(!File(root,b).exists())
            assertEquals(1,Live2DAssets.listArchives(context,role).size)
        } finally {
            File(root,"models/archive-test/${role}-a").deleteRecursively()
            File(root,"models/archive-test/${role}-b").deleteRecursively()
            prefs.edit().remove("model:$role").remove("label:$role")
                .remove("archives:$role").remove("preview:$role").commit()
            touchPrefs.edit().remove(urlA).remove(urlB).commit()
        }
    }

    @Test fun legacyActiveModelAppearsInPickerWithoutReimport() {
        val context=RuntimeEnvironment.getApplication()
        val role="archive-legacy-"+java.util.UUID.randomUUID()
        val settings="models/archive-legacy/$role/avatar.model3.json"
        val file=File(Live2DAssets.base(context),settings)
        file.parentFile?.mkdirs()
        file.writeText("{}")
        val prefs=context.getSharedPreferences("lulu_live2d_models_v1",0)
        prefs.edit().putString("model:$role",settings)
            .putString("label:$role","已校准").remove("archives:$role").commit()
        try {
            assertEquals(settings,Live2DAssets.listArchives(context,role).single().settings)
            assertTrue(Live2DAssets.switchArchive(context,role,settings))
        } finally {
            file.parentFile?.deleteRecursively()
            prefs.edit().remove("model:$role").remove("label:$role")
                .remove("archives:$role").commit()
        }
    }
}
