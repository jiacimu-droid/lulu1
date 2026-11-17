package com.jiacimu.lulu.study

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The one on-device TXT import path shared by the reading shelf and background reader. */
internal object ReadingTxtLibrary {
    private const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_BOOK_CHARS = 800_000
    private const val MAX_LIBRARY_CHARS = 12_000_000

    fun importBook(context: Context, uri: Uri): BackgroundReadingBook {
        val resolver = context.contentResolver
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名书籍.txt"
        require(fileName.endsWith(".txt", ignoreCase = true) ||
            fileName.endsWith(".md", ignoreCase = true) ||
            resolver.getType(uri) == "text/plain") { "请选择 TXT 文本文件" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length == 0) continue
                require(output.size() + length <= MAX_FILE_BYTES) {
                    "文件超过 4MB，请先拆分成几个 TXT 文件"
                }
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        } ?: error("无法打开选中的文件")
        val text = decodeText(bytes).replace("\r\n", "\n")
            .replace('\r', '\n').trim()
        require(text.isNotBlank()) { "这份 TXT 没有可阅读的正文" }
        require('\u0000' !in text) { "文件可能不是纯文本，请选择 TXT" }
        require(text.length <= MAX_BOOK_CHARS) { "单本最多 80 万字符，请分卷导入" }
        val title = fileName.substringBeforeLast('.', fileName).trim()
            .ifBlank { "未命名书籍" }

        val prefs = context.applicationContext.getSharedPreferences(
            "lulu_reading_library", Context.MODE_PRIVATE)
        synchronized(this) {
            // Read and retain every older item; never silently evict a previous book.
            val stored = JSONArray(prefs.getString("books_v1", "[]"))
            var total = text.length
            for (index in 0 until stored.length()) {
                val book = stored.getJSONObject(index)
                val previousText = book.optString("content")
                require(previousText != text) { "这本书已经导入过啦" }
                total += previousText.length
            }
            require(total <= MAX_LIBRARY_CHARS) {
                "本地书库容量已满，请先整理旧书；已有书籍不会被覆盖"
            }
            val id = "txt-" + UUID.randomUUID()
            val next = JSONArray().put(JSONObject()
                .put("id", id).put("title", title).put("content", text))
            for (index in 0 until stored.length()) next.put(stored.getJSONObject(index))
            check(prefs.edit().putString("books_v1", next.toString()).commit()) {
                "本地书籍保存失败，旧书库未被替换"
            }
            return BackgroundReadingBook(id, title, text, "用户上传 · TXT")
        }
    }

    /** Common Chinese TXT encodings: UTF-8 (with/without BOM), UTF-16 and GB18030. */
    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }
}
