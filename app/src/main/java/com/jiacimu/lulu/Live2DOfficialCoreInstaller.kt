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
 * Installs the official hosted Cubism Core directly, without downloading the full AIRI SDK ZIP.
 *
 * This intentionally does NOT redistribute Live2D proprietary code in GitHub or the APK.
 * Callers must show the official license and obtain the user's consent before invoking it.
 */
internal object Live2DOfficialCoreInstaller {
    internal const val CORE_URL =
        "https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"
    internal const val SDK_URL =
        "https://cubism.live2d.com/sdk-web/bin/CubismSdkForWeb-5-r.3.zip"
    internal const val LICENSE_URL =
        "https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_cn.html"
    private const val EXPECTED_PATH =
        "CubismSdkForWeb-5-r.3/Core/live2dcubismcore.min.js"
    // This protects a single download from runaway data / damaged files, not model storage.
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
        try {
            // This URL is the official "Cubism Core for Web" hosting endpoint.
            // Download just the JS file, not the much larger entire SDK package.
            val connection = URL(CORE_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 20_000
                connection.readTimeout = 45_000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                check(connection.responseCode in 200..299 &&
                    connection.url.protocol.equals("https", ignoreCase = true)) {
                    "Live2D 官方 Core 服务器不可用（HTTP ${connection.responseCode}）"
                }
                val reportedLength = connection.contentLengthLong
                require(reportedLength < 0 || reportedLength <= MAX_CORE_BYTES) {
                    "官方 Core 文件大于预期，已停止下载"
                }
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(staging).use { output ->
                        val bytes = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            downloaded += count
                            check(downloaded <= MAX_CORE_BYTES) {
                                "官方 Core 文件大于预期，已停止下载"
                            }
                            output.write(bytes, 0, count)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            check(staging.length() > 1024 && staging.readText().contains("Live2DCubismCore")) {
                "官方托管 Core 内容无效，未覆盖原有文件"
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
            "已从 Live2D 官方站点安装轻量 Cubism Core，模型现在可以尝试渲染。"
        } finally {
            staging.delete()
            // If a failed install preserved the previous version, remove the stale backup.
            if (destination.isFile) backup.delete()
        }
    }
}
