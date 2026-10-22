package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AIRI's public Hiyori sample URL from apps/stage-pocket/vite.config.ts.
 * Download is opt-in and ONLY for a separate no-call diagnostic role.
 * It is not bundled or automatically bound to the user's companions.
 */
internal object Live2DSampleModel {
    const val TEST_ROLE = "_lulu_live2d_diagnostic_hiyori_"
    const val SOURCE_URL = "https://dist.ayaka.moe/live2d-models/hiyori_free_zh.zip"
    private const val MAX_BYTES = 125L * 1024L * 1024L

    suspend fun install(context: Context, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val staging = File.createTempFile("lulu-hiyori-", ".zip", context.cacheDir)
            try {
                onProgress("下载：连接 AIRI 使用的示例模型地址")
                val conn = URL(SOURCE_URL).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 50_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("Accept", "application/zip")
                    val responseCode = conn.responseCode
                    check(responseCode in 200..299 && conn.url.protocol.equals("https", true)) {
                        "示例模型下载失败：HTTP $responseCode"
                    }
                    val expected = conn.contentLengthLong
                    require(expected <= 0 || expected <= MAX_BYTES) { "示例 ZIP 超出单次下载限制" }
                    var bytesReceived = 0L
                    var reportedProgress = -1
                    conn.inputStream.use { input ->
                        staging.outputStream().use { output ->
                            val bytes = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(bytes)
                                if (n < 0) break
                                bytesReceived += n
                                check(bytesReceived <= MAX_BYTES) { "示例 ZIP 超出单次下载限制" }
                                output.write(bytes, 0, n)
                                val progress = if (expected > 0) (bytesReceived * 100 / expected).toInt() else
                                    (bytesReceived / (1024 * 1024)).toInt()
                                if (progress != reportedProgress && progress % 10 == 0) {
                                    reportedProgress = progress
                                    onProgress(if (expected > 0) "下载：$progress%"
                                        else "下载：${bytesReceived / (1024 * 1024)} MiB")
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                check(staging.length() > 1000) { "示例模型下载不完整" }
                staging.inputStream().use {
                    check(it.read() == 0x50 && it.read() == 0x4b) {
                        "示例模型服务器未返回 ZIP 文件"
                    }
                }
                onProgress("校验：解压并检查 model3.json、moc3、纹理")
                Live2DAssets.importModel(context, TEST_ROLE, Uri.fromFile(staging))
            } finally {
                staging.delete()
            }
        }
}
