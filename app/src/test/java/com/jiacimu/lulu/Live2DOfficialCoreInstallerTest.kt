package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DOfficialCoreInstallerTest {
    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val data = ByteArrayOutputStream()
        ZipOutputStream(data).use { zip ->
            files.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return data.toByteArray()
    }

    @Test fun findsOnlyTheVersionPinnedOfficialCoreEntry() {
        val temp = File.createTempFile("live2d-test-", ".js")
        try {
            val contents = "var Live2DCubismCore = {}; // test fixture, no proprietary SDK"
            val bytes = archive(
                "not-official/Core/live2dcubismcore.min.js" to "wrong",
                "CubismSdkForWeb-5-r.3/Core/live2dcubismcore.min.js" to contents,
                "CubismSdkForWeb-5-r.3/Framework/index.ts" to "ignored"
            )
            assertTrue(Live2DOfficialCoreInstaller.extractCoreFromArchive(ByteArrayInputStream(bytes), temp))
            assertEquals(contents, temp.readText())
        } finally {
            temp.delete()
        }
    }

    @Test fun missingCoreDoesNotInventASuccessfulInstall() {
        val temp = File.createTempFile("live2d-test-", ".js")
        try {
            val bytes = archive("CubismSdkForWeb-5-r.3/Framework/index.ts" to "not a Core")
            assertFalse(Live2DOfficialCoreInstaller.extractCoreFromArchive(ByteArrayInputStream(bytes), temp))
            assertEquals(0L, temp.length())
        } finally {
            temp.delete()
        }
    }
}
