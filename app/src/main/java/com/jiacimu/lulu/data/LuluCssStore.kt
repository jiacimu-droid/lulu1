package com.jiacimu.lulu.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** The stylesheet and imported assets never leave app-private storage unless exported. */
object LuluCssStore {
    private const val PREFS = "lulu_css_studio_v1"
    private const val KEY = "style"
    private val lock = Any()
    private val mutable = MutableStateFlow("")
    val css = mutable.asStateFlow()
    private var context: Context? = null

    fun initialize(app: Context) {
        synchronized(lock) {
            if (context != null) return
            context = app.applicationContext
            mutable.value = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "")?.take(LuluCssSheet.MAX_LENGTH).orEmpty()
        }
    }

    fun save(source: String): Boolean {
        if (LuluCssSheet.parse(source).errors.isNotEmpty()) return false
        synchronized(lock) {
            val app = context ?: return false
            val ok = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, source).commit()
            if (ok) mutable.value = source
            return ok
        }
    }

    fun importImage(app: Context, uri: Uri): String {
        val extension = when (app.contentResolver.getType(uri)?.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("请选择 PNG、JPG 或 WebP")
        }
        val folder = File(app.filesDir, "lulu_css_assets")
        check(folder.isDirectory || folder.mkdirs()) { "无法创建素材目录" }
        val name = UUID.randomUUID().toString() + "." + extension
        val file = File(folder, name)
        try {
            val stream = app.contentResolver.openInputStream(uri) ?: error("无法读取图片")
            stream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 8L * 1024 * 1024) { "单张图片不能超过 8 MB" }
                        output.write(buffer, 0, n)
                    }
                    require(total > 0) { "图片为空" }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) {
                "图片无法解码或尺寸过大"
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return "asset://" + name
    }

    fun assetFile(token: String?): File? {
        val name = token?.removePrefix("asset://") ?: return null
        if (!name.matches(Regex("""[a-f0-9-]{36}\.(png|jpg|webp)"""))) return null
        return context?.let { File(File(it.filesDir, "lulu_css_assets"), name) }
            ?.takeIf { it.isFile }
    }
}
