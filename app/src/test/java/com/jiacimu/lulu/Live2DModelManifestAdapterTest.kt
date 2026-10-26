package com.jiacimu.lulu

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
class Live2DModelManifestAdapterTest {
    @Test fun findsUnlistedWolfExpressionsWithoutOverwritingOriginalManifest() {
        val dir = Files.createTempDirectory("lulu-live2d-").toFile()
        try {
            val settings = File(dir, "小狼.model3.json")
            val original = """{"FileReferences":{"Moc":"小狼.moc3","Textures":["小狼.8192/texture_00.png"]}}"""
            settings.writeText(original)
            File(dir, "吐舌.exp3.json").writeText("""{"Type":"Live2D Expression","Parameters":[]}""")
            File(dir, "兽耳.exp3.json").writeText("""{"Type":"Live2D Expression","Parameters":[]}""")
            File(dir, "波浪.motion3.json").writeText("{}")
            val result = Live2DModelManifestAdapter.normalizedSettings(settings)
            val refs = result.getJSONObject("FileReferences")
            assertEquals(2, refs.getJSONArray("Expressions").length())
            assertEquals(1, refs.getJSONObject("Motions").getJSONArray("Lulu").length())
            assertEquals(original, settings.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun realWolfNineUnreferencedExpressionsAreVisibleWithoutReimport() {
        val dir = Files.createTempDirectory("lulu-wolf-nine-").toFile()
        try {
            val model = File(dir, "小狼.model3.json")
            val original = """{"FileReferences":{"Moc":"小狼.moc3","Textures":["小狼.8192/texture_00.png"]}}"""
            model.writeText(original)
            val names = listOf("兽耳", "吐舌", "星星眼", "流泪",
                "爱心眼", "耳机", "脸红", "脸黑", "麦")
            names.forEach { name ->
                File(dir, "$name.exp3.json").writeText(
                    """{"Type":"Live2D Expression","Parameters":[]}""")
            }
            val expressions = Live2DModelManifestAdapter.normalizedSettings(model)
                .getJSONObject("FileReferences").getJSONArray("Expressions")
            val discovered = (0 until expressions.length()).map {
                expressions.getJSONObject(it).getString("Name")
            }
            assertEquals(names.toSet(), discovered.toSet())
            assertEquals(names.size, discovered.size)
            assertEquals(original, model.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun generatedWolfMotionsKeepStableSeparateGroupAndOriginalManifest() {
        val dir = Files.createTempDirectory("lulu-wolf-motions-").toFile()
        try {
            val settings = File(dir, "小狼.model3.json")
            val original = """{"FileReferences":{"Moc":"小狼.moc3","Textures":["texture.png"],"Motions":{"Wave":[{"File":"wave.motion3.json"}]}}}"""
            settings.writeText(original)
            File(dir, "wave.motion3.json").writeText("{}")
            File(dir, "original.motion3.json").writeText("{}")
            val generated = File(dir, "__lulu_wolf_motions").apply { mkdirs() }
            File(generated, "02_tilt_right.motion3.json").writeText("{}")
            File(generated, "00_nod.motion3.json").writeText("{}")
            File(generated, "01_tilt_left.motion3.json").writeText("{}")
            val groups = Live2DModelManifestAdapter.normalizedSettings(settings)
                .getJSONObject("FileReferences").getJSONObject("Motions")
            assertEquals(1, groups.getJSONArray("Wave").length())
            assertEquals(1, groups.getJSONArray("Lulu").length())
            val wolf = groups.getJSONArray("LuluWolf")
            assertEquals(3, wolf.length())
            assertEquals("__lulu_wolf_motions/00_nod.motion3.json",
                wolf.getJSONObject(0).getString("File"))
            assertEquals("__lulu_wolf_motions/01_tilt_left.motion3.json",
                wolf.getJSONObject(1).getString("File"))
            assertEquals("__lulu_wolf_motions/02_tilt_right.motion3.json",
                wolf.getJSONObject(2).getString("File"))
            assertEquals(original, settings.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun referencedActionsAreNotDuplicatedAndNullPhysicsIsRemoved() {
        val dir = Files.createTempDirectory("lulu-live2d-").toFile()
        try {
            val settings = File(dir, "avatar.model3.json")
            settings.writeText("""{"FileReferences":{"Moc":"avatar.moc3","Textures":["texture.png"],"Physics":null,"Expressions":[{"Name":"smile","File":"smile.exp3.json"}]}}""")
            File(dir, "smile.exp3.json").writeText("{}")
            val refs = Live2DModelManifestAdapter.normalizedSettings(settings).getJSONObject("FileReferences")
            assertEquals(1, refs.getJSONArray("Expressions").length())
            assertTrue(!refs.has("Physics"))
        } finally { dir.deleteRecursively() }
    }
}
