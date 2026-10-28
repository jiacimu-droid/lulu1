package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun sharedModelAssignedAcrossCharactersKeepsOneFileAndCalibrationUntilLastDelete() {
        val context=RuntimeEnvironment.getApplication()
        val origin="origin-"+java.util.UUID.randomUUID()
        val guest="guest-"+java.util.UUID.randomUUID()
        val relative="models/shared-"+origin+"/imported/avatar.model3.json"
        val file=File(Live2DAssets.base(context),relative)
        file.parentFile?.mkdirs()
        file.writeText("{}")
        val prefs=context.getSharedPreferences("lulu_live2d_models_v1",0)
        val touch=context.getSharedPreferences("lulu_live2d_touch_zones_v1",0)
        val url="https://appassets.androidplatform.net/private/"+relative
        prefs.edit().putString("model:$origin",relative)
            .putString("label:$origin","已校准的模型")
            .putString("archives:$origin",
                """[{"settings":"$relative","label":"已校准的模型"}]""").commit()
        touch.edit().putString(url,"custom-hit-zones").commit()
        try {
            assertTrue(Live2DAssets.listAvailableModels(context).any{it.settings==relative})
            assertTrue(Live2DAssets.useExistingModel(context,guest,relative))
            assertEquals(url,Live2DAssets.modelUrl(context,guest))
            assertEquals("custom-hit-zones",touch.getString(url,null))
            assertTrue(Live2DAssets.deleteArchive(context,origin,relative))
            assertTrue(file.isFile)
            assertEquals(url,Live2DAssets.modelUrl(context,guest))
            assertEquals("custom-hit-zones",touch.getString(url,null))
            assertTrue(Live2DAssets.deleteArchive(context,guest,relative))
            assertEquals(null,Live2DAssets.modelUrl(context,guest))
            assertTrue(!file.exists())
            assertEquals(null,touch.getString(url,null))
        } finally {
            file.parentFile?.deleteRecursively()
            for(id in listOf(origin,guest))prefs.edit().remove("model:$id")
                .remove("label:$id").remove("archives:$id").remove("preview:$id").commit()
            touch.edit().remove(url).commit()
        }
    }

    @Test fun modeledCharactersNeverIncludeTheLibraryHost() {
        val context=RuntimeEnvironment.getApplication()
        val relative="models/library-host/x/avatar.model3.json"
        val file=File(Live2DAssets.base(context),relative)
        file.parentFile?.mkdirs()
        file.writeText("{}")
        val prefs=context.getSharedPreferences("lulu_live2d_models_v1",0)
        prefs.edit().putString("model:"+Live2DAssets.LIBRARY_OWNER_ID,relative)
            .putString("label:"+Live2DAssets.LIBRARY_OWNER_ID,"小狼").commit()
        try {
            assertTrue(Live2DAssets.listModeledCharacters(context)
                .none { it.first==Live2DAssets.LIBRARY_OWNER_ID })
            assertTrue(Live2DAssets.listAvailableModels(context)
                .any { it.settings==relative })
        } finally {
            file.parentFile?.deleteRecursively()
            prefs.edit().remove("model:"+Live2DAssets.LIBRARY_OWNER_ID)
                .remove("label:"+Live2DAssets.LIBRARY_OWNER_ID).commit()
        }
    }

    @Test fun deletingFromLibraryUnbindsEveryCharacterAndDeletesTheFile() {
        val context=RuntimeEnvironment.getApplication()
        val suffix=java.util.UUID.randomUUID().toString()
        val roleA="lib-a-$suffix"
        val roleB="lib-b-$suffix"
        val relative="models/library-delete/$suffix/avatar.model3.json"
        val file=File(Live2DAssets.base(context),relative)
        file.parentFile?.mkdirs()
        file.writeText("{}")
        val prefs=context.getSharedPreferences("lulu_live2d_models_v1",0)
        val archivesJson="""[{"settings":"$relative","label":"库模型"}]"""
        val editor=prefs.edit()
        for(id in listOf(roleA,roleB,Live2DAssets.LIBRARY_OWNER_ID)) {
            editor.putString("model:$id",relative).putString("label:$id","库模型")
                .putString("archives:$id",archivesJson)
        }
        editor.commit()
        try {
            assertEquals(1,Live2DAssets.listAvailableModels(context)
                .count { it.settings==relative })
            assertTrue(Live2DAssets.deleteFromLibrary(context,relative))
            assertTrue(Live2DAssets.listAvailableModels(context)
                .none { it.settings==relative })
            assertEquals(null,Live2DAssets.modelUrl(context,roleA))
            assertEquals(null,Live2DAssets.modelUrl(context,roleB))
            assertTrue(!file.exists())
        } finally {
            file.parentFile?.deleteRecursively()
            val cleanup=prefs.edit()
            for(id in listOf(roleA,roleB,Live2DAssets.LIBRARY_OWNER_ID)) {
                cleanup.remove("model:$id").remove("label:$id")
                    .remove("archives:$id").remove("preview:$id")
            }
            cleanup.commit()
        }
    }

    @Test fun libraryModelHealthReportsCountsWolfEligibilityAndCalibration() {
        val context = RuntimeEnvironment.getApplication()
        val relative = "models/health-test-" + java.util.UUID.randomUUID() + "/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile?.mkdirs()
        settings.writeText(
            """{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],""" +
                """"DisplayInfo":"avatar.cdi3.json",""" +
                """"Expressions":[{"Name":"smile","File":"s.exp3.json"},{"Name":"流泪","File":"t.exp3.json"}],""" +
                """"Motions":{"Tap":[{"File":"a.motion3.json"},{"File":"b.motion3.json"}]}}}"""
        )
        File(settings.parentFile, "avatar.cdi3.json").writeText(
            """{"Parameters":[{"Id":"ParamCheekPuff"},{"Id":"ParamJawOpen"},""" +
                """{"Id":"ParamAngleZ2"},{"Id":"body_gensui_z"},""" +
                """{"Id":"ParamMouthPuckerWiden"},{"Id":"ParamBrowLForm"},{"Id":"ParamAngleX"}]}"""
        )
        val zones = context.getSharedPreferences("lulu_live2d_touch_zones_v1", 0)
        val zoneKey = settings.toURI().toURL().toString()
        try {
            val health = Live2DAssets.libraryModelHealth(context, settings)
            assertEquals(2, health.expressions)
            assertEquals(2, health.motions)
            assertTrue(health.wolfPackEligible)
            assertFalse(health.touchCalibrated)
            zones.edit().putString(zoneKey, "{}").commit()
            assertTrue(Live2DAssets.libraryModelHealth(context, settings).touchCalibrated)
            // A missing settings file reports an empty health, never a crash.
            val missing = Live2DAssets.libraryModelHealth(context,
                File(Live2DAssets.base(context), "models/nope/x.model3.json"))
            assertEquals(0, missing.expressions)
            assertFalse(missing.wolfPackEligible)
        } finally {
            zones.edit().remove(zoneKey).commit()
            settings.parentFile?.deleteRecursively()
        }
    }
}
