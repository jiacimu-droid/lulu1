package com.jiacimu.lulu.data

internal data class DigitalWorldPublicPlace(
    val code: String,
    val label: String,
    val subtitle: String,
    val purpose: String,
)

/**
 * Shared places are program-owned. Built-ins always exist; discovered places only become visible
 * after DigitalWorldExpansionStore has committed them to persistent world state.
 */
internal object DigitalWorldPublicPlaces {
    const val GAME_HALL = "shared:game_hall"
    const val READING_LOUNGE = "shared:reading_lounge"
    const val CAFE = "shared:cafe"
    const val COURTYARD = "shared:courtyard"

    private val builtIns: List<DigitalWorldPublicPlace> = listOf(
        DigitalWorldPublicPlace(
            GAME_HALL,
            "游戏馆",
            "一起玩 · 围观 · 休息",
            "承接露露机里真实存在的小游戏，让相约玩游戏变成世界中的共同活动。",
        ),
        DigitalWorldPublicPlace(
            READING_LOUNGE,
            "阅读馆",
            "阅读 · 窗边 · 安静陪伴",
            "承接阅读 App 和小剧场，让角色可以真的在这里读过同一段内容。",
        ),
        DigitalWorldPublicPlace(
            CAFE,
            "浮光咖啡角",
            "坐坐 · 聊天 · 偶遇",
            "低压力社交空间，适合短聊天、偶遇其他角色和一起发呆。",
        ),
        DigitalWorldPublicPlace(
            COURTYARD,
            "共生庭院",
            "散步 · 天气 · 日常事件",
            "开放式公共空间，承担散步、季节、天气和轻量随机生活事件。",
        ),
    )

    val all: List<DigitalWorldPublicPlace>
        get() = builtIns + DigitalWorldExpansionStore.places().map { place ->
            DigitalWorldPublicPlace(
                code = place.code,
                label = place.label,
                subtitle = place.subtitle,
                purpose = place.purpose,
            )
        }

    fun label(code: String): String? = all.firstOrNull { it.code == code }?.label
    fun contains(code: String): Boolean = all.any { it.code == code }
    fun isBuiltIn(code: String): Boolean = builtIns.any { it.code == code }
}
