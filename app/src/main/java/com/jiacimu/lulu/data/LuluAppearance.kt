package com.jiacimu.lulu.data

/**
 * A reusable visual recipe. Screen-specific bindings live in [LuluAppearanceLibrary],
 * not in the recipe: changing the home screen must not retheme the user's chat.
 */
data class LuluAppearancePalette(
    val paper: Long,
    val card: Long,
    val cardStrong: Long,
    val ink: Long,
    val muted: Long,
    val border: Long,
    val accent: Long,
    val onAccent: Long,
    val soft: Long,
)

enum class LuluAppearanceMotif { CLEAN, STITCH, SLASH, INK, GOTHIC, COMIC, BOTANICAL, GLASS, STARS }
enum class LuluAppearanceFont { SANS, SERIF, MONO }

data class LuluAppearance(
    val id: String,
    val name: String,
    val description: String,
    val palette: LuluAppearancePalette,
    val motif: LuluAppearanceMotif,
    val font: LuluAppearanceFont = LuluAppearanceFont.SANS,
    val corner: Int = 18,
    val dark: Boolean = false,
    val builtIn: Boolean = true,
)

object LuluAppearanceCatalog {
    // The CSS studio starts with a neutral fallback only. Old built-in style
    // packages are retired; old private custom theme data is not erased.
    val builtIns: List<LuluAppearance> = listOf(
        LuluAppearance("paper", "原生外观", "空白画布", LuluAppearancePalette(
            0xFFFFFFFF, 0xFFFCFCFC, 0xFFF4F4F4, 0xFF1D1D1F, 0xFF737378, 0xFFE7E7E7,
            0xFF292929, 0xFFFFFFFF, 0xFFF1F1F1), LuluAppearanceMotif.CLEAN),
    )

    // Individual app bindings. No implicit global override or one-size-fits-all skin.
    val scopes = listOf(
        "Home" to "桌面", "Chat" to "聊天", "Phone" to "通话", "Pomodoro" to "学习小窗",
        "CharacterSettings" to "角色设置", "Memory" to "记忆", "Lexicon" to "生活记录",
        "WorldBook" to "世界书", "Performance" to "性能监测", "Health" to "健康",
        "Reading" to "阅读", "Scroll" to "画卷", "Theater" to "阅读",
        "Study" to "考研", "Schedule" to "日程", "Meeting" to "见面",
        "Games" to "游戏", "ApocalypseSurvival" to "末世求生",
        "Settings" to "设置", "Appearance" to "美化",
    )
}

/** Pure selection and library rules, also tested without Android dependencies. */
data class LuluAppearanceLibrary(
    val custom: List<LuluAppearance> = emptyList(),
    val selections: Map<String, String> = emptyMap(),
    val recent: List<String> = emptyList(),
) {
    val all: List<LuluAppearance> get() = LuluAppearanceCatalog.builtIns + custom
    fun find(id: String): LuluAppearance? = all.firstOrNull { it.id == id }
    fun active(scope: String): LuluAppearance =
        find(selections[scope].orEmpty()) ?: LuluAppearanceCatalog.builtIns.first()

    fun select(scope: String, id: String): LuluAppearanceLibrary {
        if (find(id) == null) return this
        return copy(
            selections = selections + (scope to id),
            recent = (listOf(id) + recent.filterNot { it == id }).take(12),
        )
    }

    fun save(appearance: LuluAppearance): LuluAppearanceLibrary {
        if (appearance.builtIn || appearance.id.isBlank() || find(appearance.id) != null) return this
        return copy(custom = custom + appearance.copy(builtIn = false))
    }

    fun remove(id: String): LuluAppearanceLibrary {
        if (selections.values.any { it == id }) return this
        if (custom.none { it.id == id }) return this
        return copy(custom = custom.filterNot { it.id == id }, recent = recent.filterNot { it == id })
    }
}
