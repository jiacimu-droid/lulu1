package com.jiacimu.lulu.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

/**
 * 整机数据备份：把应用私有数据目录打成一个 zip。
 *
 * 为什么是这个形态：之前教训是备份放在 app 自己肚子里（filesDir），
 * 卸载时系统会连备份一起清掉。所以这个备份不写 app 私有空间，
 * 而是由用户在系统文件选择器里挑一个位置保存（比如下载文件夹），
 * 那个文件卸了 app 也还在。恢复时先把备份读进暂存区、整包校验
 * 通过才标记“待恢复”，下次启动、在任何数据层读文件之前，
 * 再把备份解压覆盖回数据目录并重启后生效。
 *
 * 备份范围：数据目录下除缓存（cache、code_cache）和恢复暂存区
 * 之外的一切，包括设置、聊天与时间线、各角色数据、日记与词库、
 * 模型皮套。备份里含登录密钥等私密内容，设置页会提醒用户
 * 不要把备份文件发给别人。
 *
 * 纯 File 的三个核心函数（exportZip / validateZip / restoreZip）
 * 不碰任何 Android API，单元测试用临时目录就能跑，不要
 * 再把 org.json 之类带进纯单测（之前 #5903 就是这么挂的）。
 */
object LuluDataBackup {
    const val SCHEMA_VERSION = 1
    const val MANIFEST_NAME = "lulu-backup.json"
    const val STAGING_DIR_NAME = "pending_restore"
    const val STAGING_ZIP_NAME = "backup.zip"
    const val PENDING_FLAG_NAME = "pending"
    const val RESULT_FILE_NAME = "lulu_restore_result.txt"

    private val EXCLUDED_TOP_DIRS = setOf("cache", "code_cache")

    data class ExportResult(val fileCount: Int, val totalBytes: Long)
    data class ValidatedZip(val entryCount: Int)

    sealed interface RestoreOutcome {
        data object None : RestoreOutcome
        data class Applied(val fileCount: Int) : RestoreOutcome
        data class Failed(val reason: String) : RestoreOutcome
    }

    /** 把 [dataDir] 打包写进 [out]，返回文件数与总字节数。 */
    fun exportZip(dataDir: File, out: OutputStream): ExportResult {
        var files = 0
        var bytes = 0L
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifestJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            fun walk(dir: File, rel: String) {
                val children = dir.listFiles()?.sortedBy { it.name } ?: return
                for (child in children) {
                    val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                    if (rel.isEmpty() && child.isDirectory && child.name in EXCLUDED_TOP_DIRS) continue
                    if (childRel == "files/$STAGING_DIR_NAME" ||
                        childRel.startsWith("files/$STAGING_DIR_NAME/")
                    ) continue
                    if (child.isDirectory) {
                        zip.putNextEntry(ZipEntry("$childRel/"))
                        zip.closeEntry()
                        walk(child, childRel)
                    } else if (child.isFile) {
                        zip.putNextEntry(ZipEntry(childRel))
                        FileInputStream(child).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                        files += 1
                        bytes += child.length()
                    }
                }
            }
            walk(dataDir, "")
        }
        return ExportResult(files, bytes)
    }

    /** 整包读一遍校验（CRC 不对或截断都会抛），返回条目数。 */
    fun validateZip(input: InputStream): ValidatedZip {
        // 先落到临时文件再用 ZipFile 校验：流式 ZipInputStream 读到
        // 截断处只会返回 null（当作“读完了”），截断的包也能蒙混过关，
        // 而 ZipFile 必须解析中央目录，缺尾巴会直接抛 ZipException。
        // 这是 #5905 的教训：损坏的备份绝不能被当成好的放行。
        val temp = File.createTempFile("lulu-validate-", ".zip")
        try {
            input.use { source -> FileOutputStream(temp).use { dest -> source.copyTo(dest) } }
            return validateZipFile(temp)
        } finally {
            temp.delete()
        }
    }

    fun validateZipFile(file: File): ValidatedZip {
        var entries = 0
        var schema: Int? = null
        val seenPaths = hashSetOf<String>()
        ZipFile(file).use { zip ->
            for (entry in zip.entries().toList()) {
                validateEntryPath(entry.name)
                require(seenPaths.add(entry.name)) { "备份含重复路径：${entry.name}" }
                entries += 1
                zip.getInputStream(entry).use { stream ->
                    val checksum = CRC32()
                    if (entry.name == MANIFEST_NAME) {
                        if (entry.size > 16_384L) throw IOException("备份清单异常过大")
                        val bytes = stream.readBytes()
                        checksum.update(bytes)
                        val text = bytes.toString(Charsets.UTF_8)
                        schema = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)")
                            .find(text)?.groupValues?.get(1)?.toIntOrNull()
                    } else {
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (n > 0) checksum.update(buffer, 0, n)
                        }
                    }
                    // ZipFile verifies the central directory but does not
                    // guarantee CRC validation of every getInputStream payload.
                    if (entry.crc >= 0 && checksum.value != entry.crc)
                        throw IOException("备份数据校验失败：${entry.name}")
                }
            }
        }
        // 备份必须带清单且版本一致：没有清单的 zip（哪怕条目都完好）
        // 也不是露露备份，不能放行。
        if (schema != SCHEMA_VERSION) {
            throw IOException("不是有效的露露备份：缺少清单或版本不对（schema=$schema）")
        }
        return ValidatedZip(entries)
    }

    /** A damaged archive must never start writing data before full CRC and
     * central-directory validation. The staged app import uses the file form
     * below to avoid copying an already-staged archive twice.
     */
    fun restoreZip(dataDir: File, input: InputStream): Int {
        val candidate = File.createTempFile("lulu-restore-checked-", ".zip")
        try {
            input.use { source ->
                FileOutputStream(candidate).use { dest -> source.copyTo(dest) }
            }
            return restoreVerifiedZip(dataDir, candidate)
        } finally {
            candidate.delete()
        }
    }

    private fun restoreVerifiedZip(dataDir: File, archive: File): Int {
        validateZipFile(archive)
        return FileInputStream(archive).use { extractValidatedZip(dataDir, it) }
    }

    private fun validateEntryPath(name: String) {
        // Reject traversal and Windows separators before extracting any file,
        // not just when the corrupt ZIP reaches that entry.
        require(name.isNotBlank() && !name.startsWith("/") && !name.startsWith("./") &&
            !name.contains("..") && !name.contains('\\')) {
            "备份里有非法路径：$name"
        }
    }

    /**
     * Already-validated ZIP: overlay files. Existing unmentioned files are
     * intentionally left in place, so in-place restore is NOT an exact clone.
     */
    private fun extractValidatedZip(dataDir: File, input: InputStream): Int {
        var files = 0
        val base = dataDir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name == MANIFEST_NAME) {
                    zip.closeEntry()
                    continue
                }
                validateEntryPath(name)
                if (name == "files/$STAGING_DIR_NAME" ||
                    name.startsWith("files/$STAGING_DIR_NAME/")
                ) {
                    zip.closeEntry()
                    continue
                }
                val target = File(dataDir, name)
                require(target.canonicalPath.startsWith(base)) {
                    "备份路径超出数据目录：$name"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                    files += 1
                }
                zip.closeEntry()
            }
        }
        return files
    }

    // ---- Context 薄壳：只负责取目录与暂存标记 ----

    fun dataDir(context: Context): File = File(context.applicationInfo.dataDir)

    fun stagingDir(context: Context): File = File(context.filesDir, STAGING_DIR_NAME)

    fun hasPendingRestore(context: Context): Boolean =
        File(stagingDir(context), PENDING_FLAG_NAME).exists()

    fun readRestoreResult(context: Context): String? =
        File(context.filesDir, RESULT_FILE_NAME).takeIf { it.isFile }
            ?.readText().orEmpty().ifBlank { null }

    fun clearRestoreResult(context: Context) {
        File(context.filesDir, RESULT_FILE_NAME).delete()
    }

    /** Stage the next candidate atomically. A broken second import must not
     * overwrite an earlier valid ZIP already marked for restoration.
     */
    internal fun stageZip(staging: File, input: InputStream): ValidatedZip {
        if (!staging.isDirectory && !staging.mkdirs())
            throw IOException("无法创建恢复暂存目录")
        val candidate = File.createTempFile("candidate-", ".zip", staging)
        try {
            input.use { source ->
                FileOutputStream(candidate).use { dest -> source.copyTo(dest) }
            }
            val validated = validateZipFile(candidate)
            val ready = File(staging, STAGING_ZIP_NAME)
            if (!candidate.renameTo(ready)) throw IOException("无法保存已校验的恢复包")
            val flag = File(staging, PENDING_FLAG_NAME)
            if (!flag.isFile && !flag.createNewFile()) throw IOException("无法登记待恢复状态")
            return validated
        } finally {
            candidate.delete()
        }
    }

    fun stageForRestore(context: Context, input: InputStream): ValidatedZip =
        stageZip(stagingDir(context), input)

    /**
     * 启动时、在任何数据层读文件之前调用一次。
     * 无待恢复标记时直接返回 None，不碰数据。
     */
    fun applyPendingRestoreIfNeeded(context: Context): RestoreOutcome {
        val staging = stagingDir(context)
        val flag = File(staging, PENDING_FLAG_NAME)
        val zipFile = File(staging, STAGING_ZIP_NAME)
        if (!flag.exists()) return RestoreOutcome.None
        return try {
            if (!zipFile.isFile) throw IOException("备份文件不见了")
            val count = restoreVerifiedZip(dataDir(context), zipFile)
            staging.deleteRecursively()
            writeResult(context, "成功：已恢复 $count 个文件（${Instant.now()}）。")
            RestoreOutcome.Applied(count)
        } catch (error: Exception) {
            runCatching { staging.deleteRecursively() }
            // A ZIP can be valid but the filesystem may fail mid-apply (e.g.
            // disk full). Do not claim the original data is untouched.
            writeResult(context, "失败：${error.message.orEmpty()}。若恢复已开始，部分文件可能被覆盖；请保留原 ZIP 并重试，勿依赖未完整恢复的数据。")
            RestoreOutcome.Failed(error.message.orEmpty())
        }
    }

    private fun writeResult(context: Context, text: String) {
        runCatching {
            File(context.filesDir, RESULT_FILE_NAME).writeText(text)
        }
    }

    private fun manifestJson(): String =
        "{\"schemaVersion\":$SCHEMA_VERSION,\"exportedAt\":\"${Instant.now()}\"}"
}
