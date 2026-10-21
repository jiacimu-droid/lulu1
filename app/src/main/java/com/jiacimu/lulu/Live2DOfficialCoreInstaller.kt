package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Fetches Cubism Core from the versioned official SDK archive used by AIRI.
 *
 * This intentionally does NOT redistribute Live2D proprietary code in GitHub or the APK.
 * Callers must show the official license and obtain the user's consent before invoking it.
 */
internal object Live2DOfficialCoreInstaller {
    internal const val SDK_URL =
        "https://cubism.live2d.com/sdk-web/bin/CubismSdkForWeb-5-r.3.zip"
    internal const val LICENSE_URL =
        "https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_cn.html"
    private const val EXPECTED_PATH =
        "CubismSdkForWeb-5-r.3/Core/live2dcubismcore.min.js"
    // This protects a single download from runaway data / damaged files, not model storage.
    private const val MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L
    private const val MAX_CORE_BYTES = 12L * 1024L * 1024L

    /**
     * Extract only the one pinned Core path. Caller handles HTTPS source, limits and license.
     * Pure ZIP parsing is tested with synthetic archives, without downloading proprietary SDK bits.
     */
    internal fun extractCoreFromArchive(input: java.io.InputStream, destination: File): Boolean {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return false
                val path = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && path == EXPECTED_PATH) {
                    var coreBytes = 0L
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            coreBytes += count
                            check(coreBytes <= MAX_CORE_BYTES) { "官方 Core 文件过大" }
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    return true
                }
                zip.closeEntry()
            }
        }
    }

    suspend fun install(context: Context): String = withContext(Dispatchers.IO) {
        val destination = Live2DAssets.coreFile(context)
        destination.parentFile?.mkdirs()
        val staging = File(destination.parentFile, "official-core-" + UUID.randomUUID() + ".tmp")
        val backup = File(destination.parentFile, "previous-core-" + UUID.randomUUID() + ".bak")
        val connection = URL(SDK_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/zip")
            require(connection.responseCode in 200..299) {
                "官方 SDK 服务器返回 HTTP ${connection.responseCode}；可稍后重试"
            }
            val size = connection.contentLengthLong
            require(size <= 0 || size <= MAX_DOWNLOAD_BYTES) {
                "官方 SDK 下载包超出当前单次下载安全预算"
            }
            var downloaded = 0L
            val boundedInput = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(): Int {
                    val result = `in`.read()
                    if (result >= 0) {
                        downloaded++
                        check(downloaded <= MAX_DOWNLOAD_BYTES) { "下载内容超过单次安全预算" }
                    }
                    return result
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val result = `in`.read(bytes, offset, length)
                    if (result > 0) {
                        downloaded += result
                        check(downloaded <= MAX_DOWNLOAD_BYTES) { "下载内容超过单次安全预算" }
                    }
                    return result
                }
            }
            val locatedCore = extractCoreFromArchive(boundedInput, staging)
            check(locatedCore) {
                "官方 SDK 压缩包中没有预期的 5-r.3 Cubism Core，下载内容可能更新"
            }
            check(staging.length() > 1024 && staging.readText().contains("Live2DCubismCore")) {
                "下载的 Core 内容无效；现有 Core 未被替换"
            }
            val hadExisting = destination.isFile
            if (hadExisting) check(destination.renameTo(backup)) {
                "无法备份之前的 Cubism Core"
            }
            try {
                check(staging.renameTo(destination)) { "官方 Core 安装失败" }
            } catch (error: Exception) {
                if (hadExisting) backup.renameTo(destination)
                throw error
            }
            backup.delete()
            Live2DAssets.notifyCoreChanged()
            "官方 Cubism Core 5-r.3 已下载并安装，本机模型现在可以尝试渲染。"
        } finally {
            connection.disconnect()
            staging.delete()
            // If a failed install preserved the previous version, remove the stale backup.
            if (destination.isFile) backup.delete()
        }
    }
}
