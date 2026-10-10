package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class LuluSticker(
    val id: String,
    val uri: String,
    val name: String,
    val favorite: Boolean = false,
)

/**
 * Personal sticker shelf: no unsolicited downloads and no built-in sticker pack.
 * Every image on the shelf originates from an explicit user import.
 * Files are copied into private app storage so a transient photo-picker URI
 * cannot expire after a reboot. Deleting from the shelf leaves any already
 * sent message's local image intact.
 */
internal object StickerLibraryStore {
    private const val PREF_NAME = "lulu_sticker_library_v1"
    private const val PREF_KEY = "items"
    private const val MAX_ITEMS = 220
    private const val MAX_BYTES = 5 * 1024 * 1024
    private var appContext: Context? = null
    private val mutableItems = MutableStateFlow<List<LuluSticker>>(emptyList())
    val items = mutableItems.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, "[]").orEmpty()
        mutableItems.value = runCatching {
            val values = JSONArray(raw)
            (0 until values.length()).mapNotNull { i ->
                val json = values.optJSONObject(i) ?: return@mapNotNull null
                val id = json.optString("id")
                val uri = json.optString("uri")
                if (id.isBlank() || uri.isBlank()) null else LuluSticker(
                    id, uri, json.optString("name").take(90),
                    json.optBoolean("favorite"),
                )
            }.distinctBy(LuluSticker::id).take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    @Synchronized private fun update(transform: (List<LuluSticker>) -> List<LuluSticker>) {
        val context = appContext ?: return
        val next = transform(mutableItems.value).take(MAX_ITEMS)
        val json = JSONArray()
        for (sticker in next) json.put(JSONObject()
            .put("id", sticker.id).put("uri", sticker.uri)
            .put("name", sticker.name).put("favorite", sticker.favorite))
        if (context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY, json.toString()).commit()) {
            mutableItems.value = next
        }
    }

    fun byId(context: Context, id: String): LuluSticker? {
        initialize(context)
        return items.value.firstOrNull { it.id == id }
    }

    fun prompt(context: Context, limit: Int = 24): String {
        initialize(context)
        val available = items.value.take(limit)
        if (available.isEmpty()) return "当前表情图库为空；不得声称能发送图片表情包。可以自然使用标点、颜文字、幽默和文字。"
        return buildString {
            appendLine("【真实可发送的用户自选表情包】")
            appendLine("仅从下列准确 stickerId 中挑选；可不选，不要按比例凑表情。图片表达应该像真人聊天一样按本人心情、相处关系和语境自然出现。")
            available.forEach { sticker ->
                appendLine("- stickerId=${sticker.id}；含义=${sticker.name}；${if (sticker.favorite) "用户收藏" else "普通"}")
            }
            appendLine("不知道合适哪张就不发；不自行编造图片链接、库外ID或把所有表情都塞进同一轮。")
        }
    }

    fun rename(id: String, name: String) = update { list ->
        list.map { if (it.id == id) it.copy(name = name.trim().take(90).ifBlank { "自选表情" }) else it }
    }

    fun toggleFavorite(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
    }

    fun move(id: String, direction: Int) = update { old ->
        val from = old.indexOfFirst { it.id == id }
        val to = from + direction.coerceIn(-1, 1)
        if (from < 0 || to !in old.indices) old
        else old.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun importImage(context: Context, selected: Uri, label: String = ""): Result<LuluSticker> =
        withContext(Dispatchers.IO) {
            runCatching {
                initialize(context)
                check(items.value.size < MAX_ITEMS) { "表情包数量已达上限" }
                val type = context.contentResolver.getType(selected).orEmpty().lowercase()
                require(type.startsWith("image/")) { "请选择图片、GIF 或 WebP" }
                val extension = when (type) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> error("暂不支持此图片格式：$type")
                }
                val id = UUID.randomUUID().toString()
                val folder = File(context.applicationContext.filesDir, "sticker-library").apply { mkdirs() }
                val file = File(folder, "$id.$extension")
                try {
                    context.contentResolver.openInputStream(selected)?.use { input ->
                        file.outputStream().use { output ->
                            val bytes = ByteArray(16_384)
                            var total = 0
                            while (true) {
                                val count = input.read(bytes)
                                if (count < 0) break
                                total += count
                                check(total <= MAX_BYTES) { "图片太大了，请选 5MB 以内的表情包" }
                                output.write(bytes, 0, count)
                            }
                        }
                    } ?: error("无法读取选中的图片")
                    check(file.length() > 0L) { "图片内容为空" }
                    val sticker = LuluSticker(id, Uri.fromFile(file).toString(), label.ifBlank { "自选表情" })
                    update { old -> old + sticker }
                    check(items.value.any { it.id == id }) { "保存到表情图库失败" }
                    sticker
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
            }
        }
}
