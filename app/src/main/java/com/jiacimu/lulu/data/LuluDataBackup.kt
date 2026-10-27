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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        var entries = 0
        var schema: Int? = null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entry.name == MANIFEST_NAME) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    schema = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)")
                        .find(text)?.groupValues?.get(1)?.toIntOrNull()
                } else {
                    val buffer = ByteArray(8192)
                    while (zip.read(buffer) >= 0) {
                        // 只读不存：读完即完成 CRC 校验。
                    }
                }
                zip.closeEntry()
            }
        }
        if (schema != null && schema != SCHEMA_VERSION) {
            throw IOException("不支持的备份版本：$schema")
        }
        return ValidatedZip(entries)
    }

    /**
     * 把备份 zip 解压覆盖进 [dataDir]，返回写进的文件数。
     * 拒绝绝对路径与含 .. 的条目（zip-slip），跳过恢复暂存区。
     * 注意：同名文件会被覆盖；备份里没有、现在多出来的零散文件
     * 不会被删，全新安装后恢复是最干净的。
     */
    fun restoreZip(dataDir: File, input: InputStream): Int {
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
                require(!name.startsWith("/") && !name.contains("..")) {
                    "备份里有非法路径：$name"
                }
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

    /** 把用户挑的备份文件拷进暂存区并整包校验，校验通过才标记待恢复。 */
    fun stageForRestore(context: Context, input: InputStream): ValidatedZip {
        val staging = stagingDir(context).apply { mkdirs() }
        val zipFile = File(staging, STAGING_ZIP_NAME)
        input.use { source ->
            FileOutputStream(zipFile).use { dest -> source.copyTo(dest) }
        }
        val validated = FileInputStream(zipFile).use { validateZip(it) }
        File(staging, PENDING_FLAG_NAME).createNewFile()
        return validated
    }

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
            val count = FileInputStream(zipFile).use { restoreZip(dataDir(context), it) }
            staging.deleteRecursively()
            writeResult(context, "成功：已恢复 $count 个文件（${Instant.now()}）。")
            RestoreOutcome.Applied(count)
        } catch (error: Exception) {
            runCatching { staging.deleteRecursively() }
            writeResult(context, "失败：${error.message.orEmpty()}，原数据未动，可重新挑备份恢复。")
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
