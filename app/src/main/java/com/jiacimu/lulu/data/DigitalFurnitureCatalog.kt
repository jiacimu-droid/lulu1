package com.jiacimu.lulu.data

enum class DigitalFurnitureKind {
    BED,
    SOFA,
    COFFEE_TABLE,
    TABLE,
    CHAIR,
    DESK,
    SHELF,
    CABINET,
    FLOOR_LAMP,
    TABLE_LAMP,
    RUG,
    PLANT,
    DECOR,
}

data class DigitalFurnitureStyle(
    val id: String,
    val kind: DigitalFurnitureKind,
    val displayName: String,
    val colorKey: String,
    val pattern: String = "plain",
    val keywords: List<String> = emptyList(),
)

/**
 * A finite visual furniture city for the persistent digital world.
 *
 * The model may still describe an object naturally, but the renderer always resolves that prose to
 * one of these stable sticker styles. This keeps old saves renderable and prevents arbitrary prose
 * from silently inventing a new visual asset on every generation.
 */
object DigitalFurnitureCatalog {
    val styles: List<DigitalFurnitureStyle> = listOf(
        DigitalFurnitureStyle("bed_cream", DigitalFurnitureKind.BED, "奶油软床", "cream", keywords = listOf("奶油", "米白", "奶白", "柔软")),
        DigitalFurnitureStyle("bed_stripe_blue", DigitalFurnitureKind.BED, "蓝白条纹床", "sky", "stripe", listOf("条纹", "蓝白", "浅蓝")),
        DigitalFurnitureStyle("bed_stripe_green", DigitalFurnitureKind.BED, "绿白条纹床", "sage", "stripe", listOf("绿白", "鼠尾草", "绿色条纹")),
        DigitalFurnitureStyle("bed_wood", DigitalFurnitureKind.BED, "原木床", "wood", keywords = listOf("原木", "木色", "木质")),
        DigitalFurnitureStyle("sofa_cream", DigitalFurnitureKind.SOFA, "奶油沙发", "cream", keywords = listOf("奶油", "米白", "布艺")),
        DigitalFurnitureStyle("sofa_sage", DigitalFurnitureKind.SOFA, "鼠尾草沙发", "sage", keywords = listOf("鼠尾草", "浅绿", "绿色")),
        DigitalFurnitureStyle("sofa_charcoal", DigitalFurnitureKind.SOFA, "深灰沙发", "charcoal", keywords = listOf("深灰", "炭灰", "黑灰")),
        DigitalFurnitureStyle("coffee_oak", DigitalFurnitureKind.COFFEE_TABLE, "原木茶几", "wood", keywords = listOf("原木", "木色", "圆角")),
        DigitalFurnitureStyle("coffee_white", DigitalFurnitureKind.COFFEE_TABLE, "白色茶几", "white", keywords = listOf("白色", "奶白")),
        DigitalFurnitureStyle("table_round", DigitalFurnitureKind.TABLE, "圆桌", "wood", keywords = listOf("圆桌", "圆形")),
        DigitalFurnitureStyle("table_white", DigitalFurnitureKind.TABLE, "白色餐桌", "white", keywords = listOf("白色", "餐桌")),
        DigitalFurnitureStyle("chair_wood", DigitalFurnitureKind.CHAIR, "木椅", "wood", keywords = listOf("木椅", "原木", "木质")),
        DigitalFurnitureStyle("chair_white", DigitalFurnitureKind.CHAIR, "白色椅子", "white", keywords = listOf("白色", "奶白")),
        DigitalFurnitureStyle("desk_wood", DigitalFurnitureKind.DESK, "原木书桌", "wood", keywords = listOf("书桌", "原木", "写字台")),
        DigitalFurnitureStyle("desk_white", DigitalFurnitureKind.DESK, "白色书桌", "white", keywords = listOf("白色", "书桌", "写字台")),
        DigitalFurnitureStyle("shelf_oak", DigitalFurnitureKind.SHELF, "原木书架", "wood", keywords = listOf("书架", "原木", "木色")),
        DigitalFurnitureStyle("shelf_white", DigitalFurnitureKind.SHELF, "白色书架", "white", keywords = listOf("白色", "书架")),
        DigitalFurnitureStyle("cabinet_cream", DigitalFurnitureKind.CABINET, "奶油矮柜", "cream", keywords = listOf("矮柜", "奶油", "米白")),
        DigitalFurnitureStyle("cabinet_wood", DigitalFurnitureKind.CABINET, "木质收纳柜", "wood", keywords = listOf("柜", "收纳", "木质", "原木")),
        DigitalFurnitureStyle("lamp_floor", DigitalFurnitureKind.FLOOR_LAMP, "落地灯", "warm", keywords = listOf("落地灯", "暖光", "灯")),
        DigitalFurnitureStyle("lamp_table", DigitalFurnitureKind.TABLE_LAMP, "小台灯", "warm", keywords = listOf("台灯", "床头灯", "小灯")),
        DigitalFurnitureStyle("rug_stripe", DigitalFurnitureKind.RUG, "条纹地毯", "cream", "stripe", listOf("条纹", "地毯")),
        DigitalFurnitureStyle("rug_cloud", DigitalFurnitureKind.RUG, "云朵地毯", "sky", "cloud", listOf("云朵", "云", "地毯")),
        DigitalFurnitureStyle("plant_monstera", DigitalFurnitureKind.PLANT, "龟背竹", "leaf", keywords = listOf("龟背竹", "绿植", "植物", "盆栽")),
        DigitalFurnitureStyle("decor_default", DigitalFurnitureKind.DECOR, "摆件", "cream", keywords = listOf("摆件", "装饰")),
    )

    fun resolve(item: DigitalWorldItem): DigitalFurnitureStyle {
        val haystack = listOf(item.type, item.name, item.appearance).joinToString(" ").lowercase()
        val kind = kindFor(haystack)
        val candidates = styles.filter { it.kind == kind }
        return candidates.maxByOrNull { style ->
            style.keywords.count { keyword -> keyword.lowercase() in haystack } * 10 +
                if (style.displayName.lowercase() in haystack) 8 else 0 +
                if (style.pattern != "plain" && style.pattern in haystack) 2 else 0
        } ?: styles.last()
    }

    fun promptOptions(): String = styles
        .groupBy(DigitalFurnitureStyle::kind)
        .entries
        .joinToString("；") { (kind, values) ->
            "${kind.name.lowercase()}=${values.joinToString("/") { it.displayName }}"
        }

    private fun kindFor(text: String): DigitalFurnitureKind = when {
        listOf("床", "bed").any(text::contains) -> DigitalFurnitureKind.BED
        listOf("沙发", "sofa").any(text::contains) -> DigitalFurnitureKind.SOFA
        listOf("茶几", "coffee table").any(text::contains) -> DigitalFurnitureKind.COFFEE_TABLE
        listOf("书桌", "写字台", "desk").any(text::contains) -> DigitalFurnitureKind.DESK
        listOf("书架", "置物架", "shelf").any(text::contains) -> DigitalFurnitureKind.SHELF
        listOf("柜", "cabinet").any(text::contains) -> DigitalFurnitureKind.CABINET
        listOf("落地灯", "floor lamp").any(text::contains) -> DigitalFurnitureKind.FLOOR_LAMP
        listOf("台灯", "床头灯", "table lamp").any(text::contains) -> DigitalFurnitureKind.TABLE_LAMP
        listOf("地毯", "rug").any(text::contains) -> DigitalFurnitureKind.RUG
        listOf("植物", "绿植", "盆栽", "龟背竹", "plant").any(text::contains) -> DigitalFurnitureKind.PLANT
        listOf("椅", "chair").any(text::contains) -> DigitalFurnitureKind.CHAIR
        listOf("桌", "table").any(text::contains) -> DigitalFurnitureKind.TABLE
        else -> DigitalFurnitureKind.DECOR
    }
}
