package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * A photo-picker grant is not guaranteed to outlive a background worker or
 * process restart. Keep original bytes in app-private durable storage BEFORE
 * adding an image message to the shared chat timeline.
 */
internal object QqChatPhotoAttachmentStore {
    private const val MAX_BYTES = 24L * 1024L * 1024L

    fun import(context: Context, picked: Uri): String {
        val app = context.applicationContext
        val folder = File(app.filesDir, "qq-user-photos").apply {
            check(isDirectory || mkdirs()) { "无法创建聊天图片存储目录" }
        }
        val extension = when (app.contentResolver.getType(picked)?.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val destination = File.createTempFile("chat-photo-", extension, folder)
        return try {
            val input = app.contentResolver.openInputStream(picked)
                ?: error("图片来源无法打开")
            input.use { source ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                        check(count <= MAX_BYTES) { "图片超过24MB，请选择较小的文件" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(destination.length() > 0L) { "图片内容为空" }
            Uri.fromFile(destination).toString()
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }
    /** Never delete arbitrary file:// paths or external gallery content. */
    fun deleteIfOwned(context: Context, imageUri: String): Boolean {
        val uri = runCatching { Uri.parse(imageUri) }.getOrNull() ?: return false
        if (uri.scheme != "file") return false
        val file = uri.path?.let(::File) ?: return false
        val folder = File(context.applicationContext.filesDir, "qq-user-photos")
        return runCatching {
            if (file.canonicalFile.parentFile != folder.canonicalFile) return false
            file.delete()
        }.getOrDefault(false)
    }

}
