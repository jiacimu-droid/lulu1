package com.jiacimu.lulu.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class XhsImage(val url: String, val width: Int = 0, val height: Int = 0)

internal object XhsImagePolicy {
    fun normalize(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in setOf("https", "http") || uri.userInfo != null || uri.port !in setOf(-1, 443, 80) ||
            !(host == "xhscdn.com" || host.endsWith(".xhscdn.com") ||
                host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com"))) return null
        // Official note images often carry http URLs; use the same CDN path over HTTPS.
        if (uri.scheme == "http") "https://" + host +
            (if (uri.port == -1 || uri.port == 80) "" else ":${uri.port}") + uri.rawPath.orEmpty() +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "") else raw.trim()
    }.getOrNull()
}

/** Share the downloaded pixels between display and vision without exporting browser cookies. */
internal object XhsImageLoader {
    suspend fun bitmap(url: String): Bitmap = withContext(Dispatchers.IO) {
        var target = XhsImagePolicy.normalize(url) ?: error("不是可读取的小红书图片地址")
        var bytes: ByteArray? = null
        for (hop in 0..3) {
            currentCoroutineContext().ensureActive()
            val connection = URL(target).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
                val status = connection.responseCode
                if (status in 300..399) {
                    val next = URI(target).resolve(connection.getHeaderField("Location").orEmpty()).toString()
                    target = XhsImagePolicy.normalize(next) ?: error("图片跳转地址不受支持")
                    continue
                }
                check(status in 200..299) { "图片加载失败（HTTP $status）" }
                check(connection.contentLengthLong <= 8_000_000) { "图片过大" }
                bytes = connection.inputStream.use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 8_000_000) { "图片过大" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
                break
            } finally { connection.disconnect() }
        }
        val data = bytes ?: error("图片跳转次数过多")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "返回的内容不是有效图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片解码失败")
    }

    suspend fun dataUrl(url: String): String {
        val bitmap = bitmap(url)
        return try {
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
