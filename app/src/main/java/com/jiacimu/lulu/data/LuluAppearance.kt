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
    val builtIns: List<LuluAppearance> = listOf(
        LuluAppearance("paper", "原生留白", "清爽的基础画布 · 不强制全局使用", LuluAppearancePalette(
            0xFFFFFFFF, 0xFFFCFCFC, 0xFFF4F4F4, 0xFF1D1D1F, 0xFF737378, 0xFFE7E7E7,
            0xFF292929, 0xFFFFFFFF, 0xFFF1F1F1), LuluAppearanceMotif.CLEAN),
        LuluAppearance("journal", "奶油布艺手账", "布纹 · 缝线 · 纸片与暖色拼贴", LuluAppearancePalette(
            0xFFF7EDE1, 0xFFFFF9EE, 0xFFEAD7C9, 0xFF4E3934, 0xFF866B62, 0xFFCDAFA6,
            0xFFAD675C, 0xFFFFFFFF, 0xFFF4DDD4), LuluAppearanceMotif.STITCH, LuluAppearanceFont.SERIF, 12),
        LuluAppearance("phantom", "怪盗漫画", "P5 灵感 · 不规则黑红视觉冲击", LuluAppearancePalette(
            0xFF16151A, 0xFF29262C, 0xFF413238, 0xFFFFFFFF, 0xFFCFC8CA, 0xFF73646B,
            0xFFF32D42, 0xFFFFFFFF, 0xFF3E1E28), LuluAppearanceMotif.SLASH, LuluAppearanceFont.SANS, 4, true),
        LuluAppearance("han", "云笺宋韵", "宣纸 · 淡墨 · 古典书卷的素雅", LuluAppearancePalette(
            0xFFF5F1E5, 0xFFFFFCF3, 0xFFE9E2CE, 0xFF383C33, 0xFF787969, 0xFFCECCB8,
            0xFF596B5B, 0xFFFFFFFF, 0xFFE6E8D8), LuluAppearanceMotif.INK, LuluAppearanceFont.SERIF, 6),
        LuluAppearance("gothic", "午夜蔷薇", "黑银 · 酒红 · 哥特蕾丝", LuluAppearancePalette(
            0xFF17131A, 0xFF241D28, 0xFF382932, 0xFFF4E6E8, 0xFFBBA7B4, 0xFF6D5263,
            0xFFB54E74, 0xFFFFFFFF, 0xFF38202D), LuluAppearanceMotif.GOTHIC, LuluAppearanceFont.SERIF, 10, true),
        LuluAppearance("manga", "漫画留声", "干净的二次元分镜 · 网点与描边", LuluAppearancePalette(
            0xFFFFFEF9, 0xFFFFFFFF, 0xFFF0EFE9, 0xFF191919, 0xFF666666, 0xFF272727,
            0xFF5077CD, 0xFFFFFFFF, 0xFFE8EDFF), LuluAppearanceMotif.COMIC, LuluAppearanceFont.SANS, 3),
        LuluAppearance("moss", "雨后青苔", "植物压花 · 森林与柔和纸感", LuluAppearancePalette(
            0xFFEAF0E6, 0xFFF9FBF4, 0xFFDCE5D6, 0xFF35463B, 0xFF6F8471, 0xFFB9C9B7,
            0xFF54795C, 0xFFFFFFFF, 0xFFD6E5D5), LuluAppearanceMotif.BOTANICAL, LuluAppearanceFont.SERIF, 22),
        LuluAppearance("silver", "银雾未来", "冷银 · 科技感 · 轻盈玻璃层", LuluAppearancePalette(
            0xFFE9EEF4, 0xFFFFFFFF, 0xFFD9E3EF, 0xFF263448, 0xFF6D7889, 0xFFB6C7D9,
            0xFF506C94, 0xFFFFFFFF, 0xFFD7E9F6), LuluAppearanceMotif.GLASS, LuluAppearanceFont.SANS, 24),
        LuluAppearance("dream", "星眠日记", "浅紫 · 星点 · 轻盈梦幻感", LuluAppearancePalette(
            0xFFF6F0FB, 0xFFFEFBFF, 0xFFEAE0F4, 0xFF49365F, 0xFF89759C, 0xFFD4C2E1,
            0xFF9874C7, 0xFFFFFFFF, 0xFFF0E1FC), LuluAppearanceMotif.STARS, LuluAppearanceFont.SANS, 26),
    )

    // Individual app bindings. No implicit global override or one-size-fits-all skin.
    val scopes = listOf(
        "Home" to "桌面", "Chat" to "聊天", "Phone" to "通话", "Pomodoro" to "学习小窗",
        "CharacterSettings" to "角色设置", "Memory" to "记忆", "Lexicon" to "辞海",
        "WorldBook" to "世界书", "Performance" to "性能监测", "Health" to "健康",
        "Reading" to "阅读", "Scroll" to "画卷", "Theater" to "剧场",
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
