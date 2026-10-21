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
