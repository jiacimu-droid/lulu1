package com.jiacimu.lulu.data

/** Native Compose CSS subset. Rules style existing controls and local assets. */
class LuluCssSheet private constructor(
    private val rules: Map<String, Map<String, String>>,
    val errors: List<String>,
    val warnings: List<String>,
) {
    fun page(scope: String): Map<String, String> = buildMap {
        putAll(rules[":root"].orEmpty())
        LuluCssTargets.lineage(scope).forEach { putAll(rules["#$it"].orEmpty()) }
    }

    fun element(scope: String, vararg elements: String): Map<String, String> = buildMap {
        elements.forEach { putAll(rules[".$it"].orEmpty()) }
        LuluCssTargets.lineage(scope).forEach { page ->
            elements.forEach { putAll(rules["#$page .$it"].orEmpty()) }
        }
    }

    fun imageToken(scope: String): String? = assetToken(page(scope)["background-image"])

    fun profile(scope: String, base: LuluAppearance): LuluAppearance {
        val values = page(scope)
        if (values.isEmpty()) return base
        val baseColors = base.palette
        fun color(name: String, fallback: Long): Long = parseColor(values[name]) ?: fallback
        val paper = color("--paper", baseColors.paper)
        val colors = baseColors.copy(
            paper = paper, card = color("--card", baseColors.card),
            cardStrong = color("--card-strong", baseColors.cardStrong),
            ink = color("--ink", baseColors.ink),
            muted = color("--muted", baseColors.muted), border = color("--border", baseColors.border),
            accent = color("--accent", baseColors.accent),
            onAccent = color("--on-accent", baseColors.onAccent), soft = color("--soft", baseColors.soft),
        )
        val font = when (values["font-family"]?.lowercase()) {
            "serif", "songti" -> LuluAppearanceFont.SERIF
            "mono", "monospace" -> LuluAppearanceFont.MONO
            "sans-serif", "sans" -> LuluAppearanceFont.SANS
            else -> base.font
        }
        val darkness = ((paper shr 16 and 255) * 299 +
            (paper shr 8 and 255) * 587 + (paper and 255) * 114) < 128000
        return base.copy(
            palette = colors, font = font,
            dark = when (values["color-scheme"]) {
                "dark" -> true
                "light" -> false
                else -> if ("--paper" in values) darkness else base.dark
            },
            corner = dp(values["border-radius"])?.toInt()?.coerceIn(0, 60) ?: base.corner,
        )
    }

    companion object {
        const val MAX_LENGTH = 65536
        private val COMMENTS = Regex("""/\*[\s\S]*?\*/""")
        private val BLOCK = Regex("""([^{}]+)\{([^{}]*)\}""")
        private val IMAGE = Regex("""url\(\s*(?:(["'])([^"']+)\1|([^"'\s]+))\s*\)""")
        private val ASSET = Regex("""asset://[a-f0-9-]{36}\.(png|jpg|webp)""")
        private val SELECTOR = Regex("""(:root|#[A-Za-z][A-Za-z0-9]*|(?:#[A-Za-z][A-Za-z0-9]* )?\.[a-z][a-z0-9-]*)""")
        private val COLORS = setOf(
            "--paper", "--card", "--card-strong", "--ink", "--muted", "--border",
            "--accent", "--on-accent", "--soft", "background-color", "color", "border-color",
        )
        private val LENGTHS = setOf(
            "width", "height", "min-width", "min-height", "max-width", "max-height",
            "offset-x", "offset-y", "left", "top", "padding", "padding-inline", "padding-block",
            "margin", "margin-inline", "margin-block", "border-radius", "border-width",
        )
        private val PERCENT_LENGTHS = setOf("width", "height", "offset-x", "offset-y", "left", "top")
        private val KEYWORDS = mapOf(
            "background-size" to setOf("cover", "contain", "stretch"),
            "font-family" to setOf("serif", "songti", "sans", "sans-serif", "mono", "monospace"),
            "color-scheme" to setOf("light", "dark"),
            "align" to setOf("start", "center", "end", "top-start", "top-center", "top-end",
                "center-start", "center-end", "bottom-start", "bottom-center", "bottom-end"),
            "dock" to setOf("top", "bottom"),
        )

        fun parse(css: String): LuluCssSheet {
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            if (css.length > MAX_LENGTH) errors += "CSS 不能超过 64 KB"
            val clean = COMMENTS.replace(css.take(MAX_LENGTH), "")
            val rules = linkedMapOf<String, MutableMap<String, String>>()
            var end = 0
            for (block in BLOCK.findAll(clean)) {
                if (clean.substring(end, block.range.first).isNotBlank()) errors += "CSS 花括号不成对"
                end = block.range.last + 1
                val selectors = block.groupValues[1].split(',').map { it.trim().replace(Regex("\\s+"), " ") }
                val supported = selectors.filter { selector ->
                    when {
                        !SELECTOR.matches(selector) -> {
                            warnings += "暂不支持的选择器：$selector"
                            false
                        }
                        !LuluCssTargets.knowsSelector(selector) -> {
                            warnings += "目前没有对应的原生控件：$selector"
                            false
                        }
                        else -> true
                    }
                }
                for (declaration in block.groupValues[2].split(';')) {
                    if (declaration.isBlank()) continue
                    val index = declaration.indexOf(':')
                    if (index < 1) {
                        errors += "属性缺少冒号：" + declaration.trim().take(32)
                        continue
                    }
                    val name = declaration.substring(0, index).trim().lowercase()
                    val raw = declaration.substring(index + 1).trim()
                    val value = raw.takeIf { name == "background-image" } ?: raw.lowercase()
                    when {
                        name == "background-image" -> if (value != "none" && assetToken(value) == null) {
                            errors += "图片只能使用本机导入的 asset:// 路径"
                        }
                        name in COLORS -> if (parseColor(value) == null) {
                            errors += "$name 需要 #RGB、#RGBA、#RRGGBB、#RRGGBBAA 或 transparent"
                        }
                        name in LENGTHS -> if (dp(value) == null &&
                            !(name in PERCENT_LENGTHS && percent(value) != null)
                        ) {
                            errors += "$name 需要 dp 数值" + if (name in PERCENT_LENGTHS) "或百分比" else ""
                        }
                        name == "font-size" -> if (value.removeSuffix("sp").toFloatOrNull()
                                ?.let { it.isFinite() && it > 0 } != true) {
                            errors += "font-size 需要正的 sp 数值"
                        }
                        name == "z-index" -> if (value.toIntOrNull() == null) errors += "z-index 需要整数"
                        name in KEYWORDS -> if (value !in KEYWORDS.getValue(name)) {
                            errors += "$name 可用值：" + KEYWORDS.getValue(name).joinToString()
                        }
                        else -> {
                            warnings += "暂不支持的属性：$name"
                            continue
                        }
                    }
                    for (selector in supported) {
                        val key = when (name) {
                            "left" -> "offset-x"
                            "top" -> "offset-y"
                            "background-color" -> if ('.' in selector) name else "--paper"
                            "color" -> if ('.' in selector) name else "--ink"
                            else -> name
                        }
                        rules.getOrPut(selector) { linkedMapOf() }[key] = value
                    }
                }
            }
            if (clean.substring(end).isNotBlank()) errors += "CSS 需要选择器及成对的花括号"
            return LuluCssSheet(rules, errors.distinct(), warnings.distinct())
        }

        fun assetToken(raw: String?): String? {
            val match = IMAGE.matchEntire(raw?.trim().orEmpty()) ?: return null
            return match.groupValues.drop(2).firstOrNull { ASSET.matches(it) }
        }

        /** CSS hex RGB/RGBA -> Android ARGB. */
        fun parseColor(raw: String?): Long? {
            val text = raw?.trim() ?: return null
            if (text.equals("transparent", ignoreCase = true)) return 0
            if (!text.startsWith('#')) return null
            val hex = text.drop(1)
            if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
            val expanded = if (hex.length in 3..4) hex.map { "$it$it" }.joinToString("") else hex
            val number = expanded.toLongOrNull(16) ?: return null
            return when (expanded.length) {
                6 -> 0xFF000000L or number
                8 -> (number shr 8) or ((number and 255) shl 24)
                else -> null
            }
        }

        fun dp(raw: String?): Float? =
            raw?.trim()?.removeSuffix("dp")?.trim()?.toFloatOrNull()?.takeIf(Float::isFinite)

        fun percent(raw: String?): Float? =
            raw?.takeIf { it.endsWith('%') }?.dropLast(1)?.toFloatOrNull()?.takeIf(Float::isFinite)
    }
}
