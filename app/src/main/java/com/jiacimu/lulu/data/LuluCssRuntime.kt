package com.jiacimu.lulu.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** Native Compose CSS subset: parsed as data. No JavaScript, imports, URLs or WebView. */
data class LuluCssRule(val selectors: List<String>, val values: Map<String, String>)

class LuluCssSheet private constructor(
    private val rules: List<LuluCssRule>,
    val errors: List<String>,
    val warnings: List<String>,
) {
    fun page(scope: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        rules.forEach { if (":root" in it.selectors) values.putAll(it.values) }
        rules.forEach { if ("#" + scope in it.selectors) values.putAll(it.values) }
        return values
    }

    fun element(scope: String, element: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        rules.forEach { if ("." + element in it.selectors) values.putAll(it.values) }
        rules.forEach { if ("#" + scope + " ." + element in it.selectors) values.putAll(it.values) }
        return values
    }

    fun imageToken(scope: String): String? =
        IMAGE.matchEntire(page(scope)["background-image"]?.trim().orEmpty())?.groupValues?.get(1)

    fun profile(scope: String, base: LuluAppearance): LuluAppearance {
        val values = page(scope)
        val baseColors = base.palette
        fun color(name: String, fallback: Long): Long = parseColor(values[name]) ?: fallback
        val paper = parseColor(values["--paper"] ?: values["background-color"]) ?: baseColors.paper
        val colors = baseColors.copy(
            paper = paper, card = color("--card", baseColors.card),
            cardStrong = color("--card-strong", baseColors.cardStrong),
            ink = parseColor(values["--ink"] ?: values["color"]) ?: baseColors.ink,
            muted = color("--muted", baseColors.muted),
            border = color("--border", baseColors.border),
            accent = color("--accent", baseColors.accent),
            onAccent = color("--on-accent", baseColors.onAccent),
            soft = color("--soft", baseColors.soft),
        )
        val font = when (values["font-family"]?.trim()?.lowercase()) {
            "serif", "songti" -> LuluAppearanceFont.SERIF
            "mono", "monospace" -> LuluAppearanceFont.MONO
            else -> LuluAppearanceFont.SANS
        }
        val darkness = ((paper shr 16 and 255) * 299 +
            (paper shr 8 and 255) * 587 + (paper and 255) * 114) < 128000
        return base.copy(
            palette = colors, font = font, motif = LuluAppearanceMotif.CLEAN,
            dark = values["color-scheme"]?.trim() == "dark" ||
                (values["color-scheme"] == null && darkness),
            corner = dp(values["border-radius"])?.toInt()?.coerceIn(0, 60) ?: base.corner,
        )
    }

    companion object {
        const val MAX_LENGTH = 65536
        private val COMMENTS = Regex("""/\*[\s\S]*?\*/""")
        private val BLOCK = Regex("""([^{}]+)\{([^{}]*)\}""")
        private val IMAGE = Regex(
            """url\(\s*["']?(asset://[a-f0-9-]{36}\.(?:png|jpg|webp))["']?\s*\)""",
            RegexOption.IGNORE_CASE,
        )
        private val SELECTOR = Regex("""(:root|#[A-Za-z][A-Za-z0-9]*|(?:#[A-Za-z][A-Za-z0-9]* )?\.[a-z][a-z0-9-]*)""")
        private val PROPERTIES = setOf(
            "--paper", "--card", "--card-strong", "--ink", "--muted",
            "--border", "--accent", "--on-accent", "--soft", "background-color",
            "background-image", "color", "color-scheme", "font-family",
            "border-radius", "offset-x", "offset-y", "left", "top", "padding",
        )

        fun parse(css: String): LuluCssSheet {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            if (css.length > MAX_LENGTH) errors += "CSS 不能超过 64 KB"
            val clean = COMMENTS.replace(css.take(MAX_LENGTH), "")
            if (clean.count { it == '{' } != clean.count { it == '}' }) {
                errors += "CSS 花括号不成对"
            }
            val rules = mutableListOf<LuluCssRule>()
            BLOCK.findAll(clean).take(300).forEach { block ->
                val selectors = block.groupValues[1].trim()
                    .split(',').map(String::trim).filter(String::isNotEmpty)
                if (selectors.any { !SELECTOR.matches(it) }) {
                    warnings += "尚未映射的选择器：" + selectors.joinToString()
                }
                val values = linkedMapOf<String, String>()
                block.groupValues[2].split(';').forEach { declaration ->
                    if (declaration.isBlank()) return@forEach
                    val index = declaration.indexOf(':')
                    if (index < 1) {
                        errors += "属性缺少冒号：" + declaration.trim().take(32)
                        return@forEach
                    }
                    val name = declaration.substring(0, index).trim().lowercase()
                    val value = declaration.substring(index + 1).trim()
                    if (name !in PROPERTIES) {
                        warnings += "暂不支持：" + name
                    } else if (name == "background-image" &&
                        value != "none" && IMAGE.matchEntire(value) == null) {
                        errors += "背景图片只能使用本地导入的 asset:// 路径"
                    } else if ((name.startsWith("--") || name == "color" ||
                            name == "background-color") && parseColor(value) == null) {
                        errors += "颜色格式需要 #RRGGBB 或 #RRGGBBAA：" + name
                    } else if (name in setOf("offset-x", "offset-y", "left", "top",
                            "padding", "border-radius") && dp(value) == null) {
                        errors += name + " 需要 dp 数值"
                    } else {
                        values[name] = value
                    }
                }
                if (selectors.isNotEmpty()) rules += LuluCssRule(selectors, values)
            }
            return LuluCssSheet(rules, errors.distinct(), warnings.distinct())
        }

        /** CSS hex RGB/RGBA -> Android ARGB. */
        fun parseColor(raw: String?): Long? {
            val text = raw?.trim() ?: return null
            if (!text.startsWith("#")) return null
            return when (text.length) {
                7 -> text.drop(1).toLongOrNull(16)?.let { 0xFF000000L or it }
                9 -> text.drop(1).toLongOrNull(16)?.let { (it shr 8) or ((it and 255) shl 24) }
                else -> null
            }
        }

        fun dp(raw: String?): Float? =
            raw?.trim()?.removeSuffix("dp")?.trim()?.toFloatOrNull()?.takeIf(Float::isFinite)
    }
}

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
