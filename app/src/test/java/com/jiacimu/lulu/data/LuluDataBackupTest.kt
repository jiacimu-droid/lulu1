package com.jiacimu.lulu.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 数据备份引擎的纯本地测试：只用临时目录与 java.util.zip，
 * 不碰 Android API，不用 org.json，不用 Robolectric。
 */
class LuluDataBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fakeDataDir(): File {
        val root = temp.newFolder("data")
        File(root, "shared_prefs").mkdirs()
        File(root, "shared_prefs/a.xml").writeText("<prefs/>")
        File(root, "files").mkdirs()
        File(root, "files/notes.txt").writeText("diary")
        File(root, "databases").mkdirs()
        File(root, "databases/d.db").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "cache").mkdirs()
        File(root, "cache/junk").writeText("junk")
        File(root, "files/pending_restore").mkdirs()
        File(root, "files/pending_restore/old.zip").writeText("stale")
        return root
    }

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    @Test
    fun exportIncludesDataSkipsCacheAndStaging() {
        val out = ByteArrayOutputStream()
        val result = LuluDataBackup.exportZip(fakeDataDir(), out)
        val names = zipNames(out.toByteArray())
        assertTrue(names.contains("lulu-backup.json"))
        assertTrue(names.contains("shared_prefs/a.xml"))
        assertTrue(names.contains("files/notes.txt"))
        assertTrue(names.contains("databases/d.db"))
        assertFalse(names.any { it.startsWith("cache/") })
        assertFalse(names.any { it.startsWith("files/pending_restore/") })
        assertEquals(3, result.fileCount)
    }

    @Test
    fun restoreRoundTrip() {
        val bytes = ByteArrayOutputStream()
            .also { LuluDataBackup.exportZip(fakeDataDir(), it) }.toByteArray()
        val target = temp.newFolder("restored")
        val count = LuluDataBackup.restoreZip(target, ByteArrayInputStream(bytes))
        assertEquals("diary", File(target, "files/notes.txt").readText())
        assertTrue(File(target, "shared_prefs/a.xml").isFile)
        assertEquals(3, count)
    }

    @Test
    fun truncatedZipFailsValidation() {
        val full = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("a.txt"))
                zip.write("hello".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val cut = full.copyOf(full.size / 2)
        try {
            LuluDataBackup.validateZip(ByteArrayInputStream(cut))
            fail("截断的 zip 必须校验失败")
        } catch (expected: IOException) {
            // 截断必然在读条目或校验 CRC 时抛 IOException。
        }
    }

    @Test
    fun failedSecondImportCannotReplaceAnEarlierValidStagedBackup() {
        val good = ByteArrayOutputStream()
            .also { LuluDataBackup.exportZip(fakeDataDir(), it) }.toByteArray()
        val staging = temp.newFolder("pending")
        val first = LuluDataBackup.stageZip(staging, ByteArrayInputStream(good))
        assertTrue(first.entryCount > 0)
        val ready = File(staging, LuluDataBackup.STAGING_ZIP_NAME)
        val marker = File(staging, LuluDataBackup.PENDING_FLAG_NAME)
        assertTrue(marker.isFile)
        assertTrue(ready.readBytes().contentEquals(good))

        try {
            LuluDataBackup.stageZip(staging, ByteArrayInputStream(good.copyOf(good.size / 2)))
            fail("a second truncated candidate must not replace the good staged ZIP")
        } catch (expected: IOException) {
            // The first complete backup is still the pending restore.
        }
        assertTrue(marker.isFile)
        assertTrue(ready.readBytes().contentEquals(good))
    }

    @Test
    fun preflightRejectsMalformedZipBeforeAnyExistingDataIsOverwritten() {
        val target = temp.newFolder("existing")
        File(target, "shared_prefs").mkdirs()
        val original = File(target, "shared_prefs/identity.xml")
        original.writeText("keep-original-role")

        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry(LuluDataBackup.MANIFEST_NAME))
            zip.write("""{"schemaVersion":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("shared_prefs/identity.xml"))
            zip.write("overwritten-role".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../outside"))
            zip.write("should-not-extract".toByteArray())
            zip.closeEntry()
        }

        try {
            LuluDataBackup.restoreZip(target, ByteArrayInputStream(archive.toByteArray()))
            fail("a later unsafe entry must be rejected before writing the earlier valid entry")
        } catch (expected: IllegalArgumentException) {
            // Preflight covers all entries before any restore write.
        }
        assertEquals("keep-original-role", original.readText())
    }

    @Test
    fun truncatedBackupWithAnExistingTargetNeverStartsRestore() {
        val valid = ByteArrayOutputStream()
            .also { LuluDataBackup.exportZip(fakeDataDir(), it) }.toByteArray()
        val target = temp.newFolder("existing-truncated")
        File(target, "files").mkdirs()
        val existing = File(target, "files/notes.txt")
        existing.writeText("newer-live-notes")
        try {
            LuluDataBackup.restoreZip(target, ByteArrayInputStream(valid.copyOf(valid.size / 2)))
            fail("a ZIP with a missing central directory must be rejected")
        } catch (expected: IOException) {
            // A partially streamed archive is never allowed to overwrite notes.
        }
        assertEquals("newer-live-notes", existing.readText())
    }

    @Test
    fun zipSlipEntryIsRejected() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        try {
            LuluDataBackup.restoreZip(temp.newFolder("victim"), ByteArrayInputStream(bytes.toByteArray()))
            fail("含 .. 的条目必须被拒绝")
        } catch (expected: IllegalArgumentException) {
            // 非法路径必须在写盘前拦住。
        }
    }
}
