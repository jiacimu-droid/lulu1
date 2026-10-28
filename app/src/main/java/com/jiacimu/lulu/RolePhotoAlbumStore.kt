package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ImageSearchProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Durable private photos owned by ONE character. No second character-memory store:
 * this is only the person's phone media library, not a claim that they have seen
 * or understood every pixel. A model may save only a genuinely visible message,
 * a result from its own verified image search, or its own authorized capture.
 */
internal data class RoleAlbumPhoto(
    val id: String, val uri: String, val source: String,
    val caption: String, val sourceUrl: String, val savedAt: String,
)

internal object RolePhotoAlbumStore {
    private const val PREF = "lulu_role_photo_album_v1"
    private const val MAX_BYTES = 12L * 1024 * 1024
    private val updates = MutableStateFlow(0L)
    val revisions = updates.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun folder(context: Context): File =
        File(context.applicationContext.filesDir, "role-photo-albums").apply {
            check(isDirectory || mkdirs()) { "无法建立角色相册目录" }
        }
    private fun key(characterId: String) = "photos:$characterId"

    @Synchronized fun list(context: Context, characterId: String): List<RoleAlbumPhoto> {
        if (characterId.isBlank()) return emptyList()
        val entries = runCatching {
            JSONArray(prefs(context).getString(key(characterId), "[]"))
        }.getOrDefault(JSONArray())
        return (0 until entries.length()).mapNotNull { i ->
            val value = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = value.optString("id")
            val uri = value.optString("uri")
            if (id.isBlank() || uri.isBlank()) return@mapNotNull null
            RoleAlbumPhoto(id, uri, value.optString("source"),
                value.optString("caption"), value.optString("sourceUrl"),
                value.optString("savedAt"))
        }.asReversed()
    }

    /** Never trust a requested arbitrary local path. The caller resolves an
     * authorized chat message or screenshot, and images are stored as separate
     * bytes so deleting the source message does not wipe the album. */
    @Synchronized fun saveUri(context: Context, characterId: String, sourceUri: String,
        source: String, caption: String = "", sourceUrl: String = ""): RoleAlbumPhoto {
        require(characterId.isNotBlank()) { "没有对应的角色相册" }
        val sourcePath = Uri.parse(sourceUri)
        // Only files already held by this app or a platform content grant.
        if (sourcePath.scheme == "file") {
            val file = File(sourcePath.path ?: error("图片地址无效")).canonicalFile
            require(file.path.startsWith(context.applicationContext.filesDir.canonicalPath + File.separator)) {
                "不能保存不属于本应用的任意本地文件"
            }
        } else require(sourcePath.scheme == "content") { "相册只接受已授权的本机图片" }
        val target = File.createTempFile("photo-", ".part", folder(context))
        try {
            val input = context.contentResolver.openInputStream(sourcePath)
                ?: error("图片来源无法打开")
            input.use { stream ->
                FileOutputStream(target).use { output ->
                    var length = 0L
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val bytes = stream.read(buffer)
                        if (bytes < 0) break
                        if (bytes == 0) continue
                        length += bytes
                        check(length <= MAX_BYTES) { "图片超过12MB，未保存" }
                        output.write(buffer, 0, bytes)
                    }
                }
            }
            val header = target.inputStream().use { it.readNBytes(16) }
            val mime = ImageSearchProvider.sniff(header)
                ?: error("不是有效的 JPEG、PNG、WEBP 或 GIF 图片")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000) {
                "无法读取图片或图片尺寸异常"
            }
            val extension = when (mime) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> ".jpg"
            }
            val id = UUID.randomUUID().toString()
            val destination = File(folder(context), "$id$extension")
            check(target.renameTo(destination)) { "图片保存失败" }
            val item = RoleAlbumPhoto(id, Uri.fromFile(destination).toString(), source,
                caption.take(240), sourceUrl.take(1500), Instant.now().toString())
            try {
                val entries = JSONArray(prefs(context).getString(key(characterId), "[]"))
                entries.put(JSONObject().put("id", item.id).put("uri", item.uri)
                    .put("source", item.source).put("caption", item.caption)
                    .put("sourceUrl", item.sourceUrl).put("savedAt", item.savedAt))
                check(prefs(context).edit().putString(key(characterId), entries.toString()).commit()) {
                    "保存相册索引失败"
                }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            updates.value = updates.value + 1
            return item
        } finally { target.delete() }
    }

    fun saveChatMessage(context: Context, characterId: String, messageId: String): RoleAlbumPhoto {
        require(messageId.isNotBlank()) { "请指定真实聊天图片的消息ID" }
        val image = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { chat ->
                chat.characterId == characterId && chat.groupChat == null ||
                    chat.groupChat?.members?.any { it.characterId == characterId } == true
            }
            .flatMap { chat -> MigratedDomainStores.chat.messages(chat.id).value.asSequence() }
            .firstOrNull { it.id == messageId &&
                it.sender == LuluChatMessage.Sender.User }
            ?.let { decodeQqChatImage(it.content) }
            ?: error("本角色没有收到这张真实聊天图片")
        return saveUri(context, characterId, image.imageUri, "聊天收到的照片",
            image.caption.ifBlank { image.imageDescription }, image.sourceUrl)
    }

    fun saveSearchResult(context: Context, characterId: String,
        searchId: String, index: Int): RoleAlbumPhoto {
        val hit = ImageSearchProvider.select(context, characterId, searchId, index)
        val downloaded = ImageSearchProvider.download(context, hit)
        return saveUri(context, characterId, downloaded, "网络图片", hit.title, hit.sourceUrl)
    }

    fun saveCapture(context: Context, characterId: String, bitmap: Bitmap,
        description: String): RoleAlbumPhoto {
        require(characterId.isNotBlank()) { "截图未绑定角色" }
        val temporary = File.createTempFile("captured-", ".jpg", folder(context))
        try {
            temporary.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "截图编码失败" }
            }
            return saveUri(context, characterId, Uri.fromFile(temporary).toString(),
                "本人已授权的屏幕截图", description)
        } finally { temporary.delete() }
    }

    @Synchronized fun delete(context: Context, characterId: String, photoId: String): Boolean {
        val own = list(context, characterId).firstOrNull { it.id == photoId } ?: return false
        val file = Uri.parse(own.uri).path?.let(::File)?.canonicalFile ?: return false
        if (file.parentFile != folder(context).canonicalFile) return false
        val entries = list(context, characterId).filterNot { it.id == photoId }.asReversed()
        val saved = JSONArray()
        entries.forEach { photo ->
            saved.put(JSONObject().put("id", photo.id).put("uri", photo.uri)
                .put("source", photo.source).put("caption", photo.caption)
                .put("sourceUrl", photo.sourceUrl).put("savedAt", photo.savedAt))
        }
        check(prefs(context).edit().putString(key(characterId), saved.toString()).commit())
        file.delete()
        updates.value = updates.value + 1
        return true
    }
}
