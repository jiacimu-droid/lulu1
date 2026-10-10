package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
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
    val pack: String = "我的添加",
    val visualDescription: String = "",
    val usageHint: String = "",
)

/**
 * Persistent personal sticker shelf. The licensed built-in collection is
 * automatically cached on the device after installation; users can delete
 * or re-order every imported image, and removed default items stay removed.
 * All images are copied into private app storage so chat remains usable offline
 * after the one-time import. Already-sent images are not erased by shelf deletion.
 */
internal object StickerLibraryStore {
    private const val PREF_NAME = "lulu_sticker_library_v1"
    private const val PREF_KEY = "items"
    private const val MAX_ITEMS = 220
    private const val MAX_BYTES = 5 * 1024 * 1024
    private var appContext: Context? = null
    private val builtinScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val builtinLock = Mutex()
    private const val COMPLETED_BUILTINS = "completed_openmoji_codes_v1"
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
                val catalog = BundledCuteStickerCatalog.items.firstOrNull { it.id == id }
                if (id.isBlank() || uri.isBlank()) null else LuluSticker(
                    id, uri, json.optString("name").take(90),
                    json.optBoolean("favorite"),
                    json.optString("pack").ifBlank { "我的添加" }.take(40),
                    json.optString("visualDescription").ifBlank { catalog?.description.orEmpty() }.take(400),
                    json.optString("usageHint").ifBlank { catalog?.usage.orEmpty() }.take(160),
                )
            }.distinctBy(LuluSticker::id).take(MAX_ITEMS)
        }.getOrDefault(emptyList())
        builtinScope.launch { ensureBuiltIns(context.applicationContext) }
    }

    @Synchronized private fun update(transform: (List<LuluSticker>) -> List<LuluSticker>) {
        val context = appContext ?: return
        val next = transform(mutableItems.value).take(MAX_ITEMS)
        val json = JSONArray()
        for (sticker in next) json.put(JSONObject()
            .put("id", sticker.id).put("uri", sticker.uri)
            .put("name", sticker.name).put("favorite", sticker.favorite)
            .put("pack", sticker.pack)
            .put("visualDescription", sticker.visualDescription)
            .put("usageHint", sticker.usageHint))
        if (context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY, json.toString()).commit()) {
            mutableItems.value = next
        }
    }

    fun byId(context: Context, id: String): LuluSticker? {
        initialize(context)
        return items.value.firstOrNull { it.id == id }
    }

    /** Visual facts and possible usage remain separate for models without vision. */
    fun imageDescription(sticker: LuluSticker): String = buildString {
        append("表情包；")
        if (sticker.visualDescription.isNotBlank()) {
            append("画面：").append(sticker.visualDescription.take(400))
        } else {
            append("画面尚无识图描述")
        }
        append("；名字：").append(sticker.name)
        sticker.usageHint.takeIf(String::isNotBlank)?.let {
            append("；可表达的语气：").append(it.take(160))
        }
    }

    fun prompt(context: Context, limit: Int = 40): String {
        initialize(context)
        val available = items.value.take(limit)
        if (available.isEmpty()) return "当前表情图库为空；不得声称能发送图片表情包。可以自然使用标点、颜文字、幽默和文字。"
        return buildString {
            appendLine("【真实可发送的分组表情包｜开源素材已按类别自动标注】")
            appendLine("模型没有看到图片。每条的「实际画面」是已存好的视觉说明，「可能用法」只是社交语气建议。不能把用法误认为画面上真的发生了相应动作。")
            appendLine("仅从下列准确 stickerId 中挑选；可不选，不要按比例凑表情。依据语境与角色本人兴趣选择，不要捏造图中文字。")
            available.forEach { sticker ->
                appendLine("- stickerId=${sticker.id}；分组=${sticker.pack}；表情名=${sticker.name}；实际画面=${sticker.visualDescription.ifBlank { "未经识图，画面内容尚不确定" }}；可能用法=${sticker.usageHint.ifBlank { sticker.name }}；${if (sticker.favorite) "用户收藏" else "普通"}")
            }
            appendLine("不知道合适哪张就不发；不自行编造图片链接、库外ID或把所有表情都塞进同一轮。")
        }
    }

    fun rename(id: String, name: String, description: String? = null, usage: String? = null) = update { list ->
        list.map { sticker ->
            if (sticker.id != id) sticker else sticker.copy(
                name = name.trim().take(90).ifBlank { "自选表情" },
                visualDescription = description?.trim()?.take(400) ?: sticker.visualDescription,
                usageHint = usage?.trim()?.take(160) ?: sticker.usageHint,
            )
        }
    }

    fun toggleFavorite(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
    }

    fun move(id: String, direction: Int) = update { old ->
        val from = old.indexOfFirst { it.id == id }
        val to = from + direction.coerceIn(-1, 1)
        if (from < 0 || to !in old.indices || old[from].pack != old[to].pack) old
        else old.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun remove(id: String) = update { list ->
        // A removed pre-installed sticker must stay removed on subsequent starts.
        if (BundledCuteStickerCatalog.items.any { it.id == id }) {
            appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)?.let { prefs ->
                val complete = prefs.getStringSet(COMPLETED_BUILTINS, emptySet()).orEmpty().toMutableSet()
                complete += id
                prefs.edit().putStringSet(COMPLETED_BUILTINS, complete).commit()
            }
        }
        list.filterNot { it.id == id }
    }

    /**
     * Three themed OpenMoji sets are shipped inside the APK as SVG text assets.
     * No network or image-recognition service is necessary to reconstruct them.
     * The artwork is rasterized once and cached as private local PNG files.
     */
    private fun embeddedSvgArtwork(context: Context): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (file in listOf("cats.json", "animals.json", "moods.json")) {
            runCatching {
                val json = context.assets.open("stickers/$file")
                    .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                val entries = json.optJSONArray("items") ?: return@runCatching
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val code = entry.optString("code")
                    val svg = entry.optString("svg")
                    if (code.isNotBlank() && svg.startsWith("<svg")) result[code] = svg
                }
            }
        }
        return result
    }

    private fun renderSvgToPng(source: String, file: File): Boolean = runCatching {
        val picture = SVG.getFromString(source).renderToPicture(360, 360)
        val bitmap = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawPicture(picture)
            file.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        check(file.isFile && file.length() > 0L)
        true
    }.getOrDefault(false)

    /**
     * Import the offline-approved sets once. When an SVG is missing or cannot
     * render, only that exact allowlisted icon falls back to an HTTPS PNG.
     */
    suspend fun ensureBuiltIns(context: Context): Int = withContext(Dispatchers.IO) {
        initialize(context)
        builtinLock.withLock {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val completed = prefs.getStringSet(COMPLETED_BUILTINS, emptySet()).orEmpty().toMutableSet()
            val embedded = embeddedSvgArtwork(context)
            var count = 0
            for (source in BundledCuteStickerCatalog.items) {
                if (source.id in completed) continue
                if (items.value.any { it.id == source.id }) {
                    completed += source.id
                    continue
                }
                if (items.value.size >= MAX_ITEMS) break
                val file = File(
                    File(context.applicationContext.filesDir, "sticker-library").apply { mkdirs() },
                    "${source.id}.png",
                )
                val worked = runCatching {
                    val rendered = embedded[source.code]?.let { renderSvgToPng(it, file) } == true
                    if (!rendered) {
                        val url = URL(source.url)
                        require(url.protocol == "https" &&
                            url.host == "raw.githubusercontent.com" &&
                            url.path.startsWith("/hfg-gmuend/openmoji/master/color/72x72/"))
                        val connection = url.openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 5_000
                            connection.readTimeout = 6_000
                            connection.instanceFollowRedirects = false
                            check(connection.responseCode == 200)
                            connection.inputStream.use { input ->
                                file.outputStream().use { output ->
                                    val bytes = ByteArray(8192)
                                    var total = 0
                                    while (true) {
                                        val read = input.read(bytes)
                                        if (read < 0) break
                                        total += read
                                        check(total in 1..MAX_BYTES)
                                        output.write(bytes, 0, read)
                                    }
                                }
                            }
                            check(file.isFile && file.length() > 0L &&
                                BitmapFactory.decodeFile(file.absolutePath) != null)
                        } finally {
                            connection.disconnect()
                        }
                    }
                    val sticker = LuluSticker(source.id, Uri.fromFile(file).toString(),
                        source.name, pack = source.pack,
                        visualDescription = source.description,
                        usageHint = source.usage)
                    update { old -> if (old.any { it.id == source.id }) old else old + sticker }
                    check(items.value.any { it.id == source.id })
                }.isSuccess
                if (worked) {
                    completed += source.id
                    prefs.edit().putStringSet(COMPLETED_BUILTINS, completed.toSet()).commit()
                    count++
                } else file.delete()
            }
            count
        }
    }

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
