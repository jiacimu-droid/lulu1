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
    NIGHTSTAND,
    FLOOR_LAMP,
    TABLE_LAMP,
    RUG,
    PLANT,
    TV,
    MIRROR,
    WALL_ART,
    CLOCK,
    CUSHION,
    BASKET,
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
 * Finite visual furniture city for the persistent digital world.
 * Natural model prose is resolved into one stable 2D sticker style, so old saves stay renderable.
 */
object DigitalFurnitureCatalog {
    val styles: List<DigitalFurnitureStyle> = listOf(
        DigitalFurnitureStyle("bed_cream", DigitalFurnitureKind.BED, "奶油软床", "cream", keywords = listOf("奶油", "米白", "奶白", "柔软")),
        DigitalFurnitureStyle("bed_stripe_blue", DigitalFurnitureKind.BED, "蓝白条纹床", "sky", "stripe", listOf("条纹", "蓝白", "浅蓝")),
        DigitalFurnitureStyle("bed_stripe_green", DigitalFurnitureKind.BED, "绿白条纹床", "sage", "stripe", listOf("绿白", "鼠尾草", "绿色条纹")),
        DigitalFurnitureStyle("bed_wood", DigitalFurnitureKind.BED, "原木床", "wood", keywords = listOf("原木", "木色", "木质")),
        DigitalFurnitureStyle("bed_rose", DigitalFurnitureKind.BED, "雾粉软床", "rose", keywords = listOf("粉", "雾粉", "玫瑰", "暖粉")),
        DigitalFurnitureStyle("bed_check", DigitalFurnitureKind.BED, "奶咖格纹床", "latte", "check", listOf("格纹", "格子", "奶咖", "咖色")),
        DigitalFurnitureStyle("bed_navy", DigitalFurnitureKind.BED, "深蓝木框床", "navy", keywords = listOf("深蓝", "藏蓝", "海军蓝")),

        DigitalFurnitureStyle("sofa_cream", DigitalFurnitureKind.SOFA, "奶油沙发", "cream", keywords = listOf("奶油", "米白", "布艺")),
        DigitalFurnitureStyle("sofa_sage", DigitalFurnitureKind.SOFA, "鼠尾草沙发", "sage", keywords = listOf("鼠尾草", "浅绿", "绿色")),
        DigitalFurnitureStyle("sofa_charcoal", DigitalFurnitureKind.SOFA, "深灰沙发", "charcoal", keywords = listOf("深灰", "炭灰", "黑灰")),
        DigitalFurnitureStyle("sofa_sky", DigitalFurnitureKind.SOFA, "雾蓝沙发", "sky", keywords = listOf("雾蓝", "浅蓝", "蓝色")),
        DigitalFurnitureStyle("sofa_rose", DigitalFurnitureKind.SOFA, "豆沙双人沙发", "rose", keywords = listOf("豆沙", "粉", "玫瑰")),
        DigitalFurnitureStyle("sofa_latte", DigitalFurnitureKind.SOFA, "焦糖皮沙发", "latte", keywords = listOf("焦糖", "棕", "皮质", "咖色")),

        DigitalFurnitureStyle("coffee_oak", DigitalFurnitureKind.COFFEE_TABLE, "原木茶几", "wood", keywords = listOf("原木", "木色", "圆角")),
        DigitalFurnitureStyle("coffee_white", DigitalFurnitureKind.COFFEE_TABLE, "白色茶几", "white", keywords = listOf("白色", "奶白")),
        DigitalFurnitureStyle("coffee_glass", DigitalFurnitureKind.COFFEE_TABLE, "玻璃茶几", "glass", keywords = listOf("玻璃", "透明", "通透")),
        DigitalFurnitureStyle("coffee_black", DigitalFurnitureKind.COFFEE_TABLE, "黑框茶几", "charcoal", keywords = listOf("黑框", "黑色", "极简")),

        DigitalFurnitureStyle("table_round", DigitalFurnitureKind.TABLE, "原木圆桌", "wood", keywords = listOf("圆桌", "圆形", "原木")),
        DigitalFurnitureStyle("table_white", DigitalFurnitureKind.TABLE, "白色餐桌", "white", keywords = listOf("白色", "餐桌")),
        DigitalFurnitureStyle("table_dark", DigitalFurnitureKind.TABLE, "深胡桃餐桌", "walnut", keywords = listOf("胡桃", "深木", "深棕")),

        DigitalFurnitureStyle("chair_wood", DigitalFurnitureKind.CHAIR, "木椅", "wood", keywords = listOf("木椅", "原木", "木质")),
        DigitalFurnitureStyle("chair_white", DigitalFurnitureKind.CHAIR, "白色椅子", "white", keywords = listOf("白色", "奶白")),
        DigitalFurnitureStyle("chair_sage", DigitalFurnitureKind.CHAIR, "浅绿软椅", "sage", keywords = listOf("浅绿", "软椅", "鼠尾草")),
        DigitalFurnitureStyle("chair_black", DigitalFurnitureKind.CHAIR, "黑框餐椅", "charcoal", keywords = listOf("黑框", "黑色", "餐椅")),

        DigitalFurnitureStyle("desk_wood", DigitalFurnitureKind.DESK, "原木书桌", "wood", keywords = listOf("书桌", "原木", "写字台")),
        DigitalFurnitureStyle("desk_white", DigitalFurnitureKind.DESK, "白色书桌", "white", keywords = listOf("白色", "书桌", "写字台")),
        DigitalFurnitureStyle("desk_dark", DigitalFurnitureKind.DESK, "黑胡桃书桌", "walnut", keywords = listOf("胡桃", "深色", "书桌")),

        DigitalFurnitureStyle("shelf_oak", DigitalFurnitureKind.SHELF, "原木书架", "wood", keywords = listOf("书架", "原木", "木色")),
        DigitalFurnitureStyle("shelf_white", DigitalFurnitureKind.SHELF, "白色书架", "white", keywords = listOf("白色", "书架")),
        DigitalFurnitureStyle("shelf_black", DigitalFurnitureKind.SHELF, "黑框开放书架", "charcoal", keywords = listOf("黑框", "开放", "书架")),

        DigitalFurnitureStyle("cabinet_cream", DigitalFurnitureKind.CABINET, "奶油矮柜", "cream", keywords = listOf("矮柜", "奶油", "米白")),
        DigitalFurnitureStyle("cabinet_wood", DigitalFurnitureKind.CABINET, "木质收纳柜", "wood", keywords = listOf("柜", "收纳", "木质", "原木")),
        DigitalFurnitureStyle("cabinet_sage", DigitalFurnitureKind.CABINET, "鼠尾草边柜", "sage", keywords = listOf("鼠尾草", "绿", "边柜")),

        DigitalFurnitureStyle("nightstand_wood", DigitalFurnitureKind.NIGHTSTAND, "原木床头柜", "wood", keywords = listOf("床头柜", "小柜", "原木")),
        DigitalFurnitureStyle("nightstand_cream", DigitalFurnitureKind.NIGHTSTAND, "奶油床头柜", "cream", keywords = listOf("床头柜", "奶油", "米白")),
        DigitalFurnitureStyle("nightstand_black", DigitalFurnitureKind.NIGHTSTAND, "黑色小边几", "charcoal", keywords = listOf("边几", "黑色", "小桌")),

        DigitalFurnitureStyle("lamp_floor", DigitalFurnitureKind.FLOOR_LAMP, "纸罩落地灯", "warm", keywords = listOf("落地灯", "纸罩", "暖光")),
        DigitalFurnitureStyle("lamp_arc", DigitalFurnitureKind.FLOOR_LAMP, "弧形落地灯", "charcoal", keywords = listOf("弧形", "弯臂", "落地灯")),
        DigitalFurnitureStyle("lamp_firefly", DigitalFurnitureKind.FLOOR_LAMP, "流萤落地灯", "warm", "sparkle", listOf("流萤", "萤火", "星点", "暖光")),
        DigitalFurnitureStyle("lamp_table", DigitalFurnitureKind.TABLE_LAMP, "布罩小台灯", "warm", keywords = listOf("台灯", "床头灯", "布罩")),
        DigitalFurnitureStyle("lamp_mushroom", DigitalFurnitureKind.TABLE_LAMP, "蘑菇小灯", "rose", keywords = listOf("蘑菇", "圆顶", "小灯")),
        DigitalFurnitureStyle("lamp_glass", DigitalFurnitureKind.TABLE_LAMP, "玻璃球台灯", "glass", keywords = listOf("玻璃球", "透明", "台灯")),

        DigitalFurnitureStyle("rug_stripe", DigitalFurnitureKind.RUG, "奶油条纹地毯", "cream", "stripe", listOf("条纹", "地毯")),
        DigitalFurnitureStyle("rug_cloud", DigitalFurnitureKind.RUG, "云朵地毯", "sky", "cloud", listOf("云朵", "云", "地毯")),
        DigitalFurnitureStyle("rug_checker", DigitalFurnitureKind.RUG, "奶咖棋盘地毯", "latte", "check", listOf("棋盘", "格子", "奶咖", "地毯")),
        DigitalFurnitureStyle("rug_sage", DigitalFurnitureKind.RUG, "鼠尾草圆毯", "sage", "round", listOf("圆毯", "浅绿", "鼠尾草")),
        DigitalFurnitureStyle("rug_charcoal", DigitalFurnitureKind.RUG, "黑白线条地毯", "charcoal", "stripe", listOf("黑白", "线条", "极简", "地毯")),

        DigitalFurnitureStyle("plant_monstera", DigitalFurnitureKind.PLANT, "龟背竹", "leaf", keywords = listOf("龟背竹", "绿植", "植物", "盆栽")),
        DigitalFurnitureStyle("plant_olive", DigitalFurnitureKind.PLANT, "小橄榄树", "olive", keywords = listOf("橄榄", "小树", "绿植")),
        DigitalFurnitureStyle("plant_cactus", DigitalFurnitureKind.PLANT, "圆滚仙人掌", "cactus", keywords = listOf("仙人掌", "多肉", "植物")),

        DigitalFurnitureStyle("tv_black", DigitalFurnitureKind.TV, "黑色薄屏电视", "charcoal", keywords = listOf("电视", "屏幕", "薄屏", "显示器")),
        DigitalFurnitureStyle("tv_cream", DigitalFurnitureKind.TV, "奶白复古电视", "cream", keywords = listOf("电视", "复古", "奶白")),
        DigitalFurnitureStyle("mirror_arch", DigitalFurnitureKind.MIRROR, "拱形落地镜", "glass", keywords = listOf("镜", "拱形", "落地镜")),
        DigitalFurnitureStyle("mirror_round", DigitalFurnitureKind.MIRROR, "圆形墙镜", "glass", "round", listOf("圆镜", "圆形", "墙镜")),
        DigitalFurnitureStyle("art_mono", DigitalFurnitureKind.WALL_ART, "黑白线稿画", "charcoal", keywords = listOf("挂画", "线稿", "黑白", "画框")),
        DigitalFurnitureStyle("art_landscape", DigitalFurnitureKind.WALL_ART, "雾色风景画", "sky", keywords = listOf("风景", "挂画", "画框", "雾色")),
        DigitalFurnitureStyle("clock_round", DigitalFurnitureKind.CLOCK, "圆形挂钟", "white", "round", listOf("挂钟", "时钟", "圆钟")),
        DigitalFurnitureStyle("clock_black", DigitalFurnitureKind.CLOCK, "黑框时钟", "charcoal", "round", listOf("黑框", "时钟", "挂钟")),
        DigitalFurnitureStyle("cushion_cream", DigitalFurnitureKind.CUSHION, "奶油抱枕", "cream", keywords = listOf("抱枕", "靠枕", "奶油")),
        DigitalFurnitureStyle("cushion_stripe", DigitalFurnitureKind.CUSHION, "蓝白条纹抱枕", "sky", "stripe", listOf("抱枕", "条纹", "蓝白")),
        DigitalFurnitureStyle("basket_rattan", DigitalFurnitureKind.BASKET, "藤编收纳篮", "rattan", keywords = listOf("藤编", "篮", "收纳筐")),
        DigitalFurnitureStyle("basket_cream", DigitalFurnitureKind.BASKET, "布艺收纳篮", "cream", keywords = listOf("布艺", "篮", "收纳")),

        DigitalFurnitureStyle("decor_default", DigitalFurnitureKind.DECOR, "小摆件", "cream", keywords = listOf("摆件", "装饰")),
        DigitalFurnitureStyle("decor_glass", DigitalFurnitureKind.DECOR, "玻璃花瓶", "glass", keywords = listOf("花瓶", "玻璃", "透明")),
        DigitalFurnitureStyle("decor_black", DigitalFurnitureKind.DECOR, "黑白雕塑摆件", "charcoal", keywords = listOf("雕塑", "黑白", "摆件")),
    )

    fun resolve(item: DigitalWorldItem): DigitalFurnitureStyle {
        val haystack = listOf(item.type, item.name, item.appearance).joinToString(" ").lowercase()
        val kind = kindFor(haystack)
        val candidates = styles.filter { it.kind == kind }
        return candidates.maxByOrNull { style ->
            val keywordScore = style.keywords.count { keyword -> keyword.lowercase() in haystack } * 10
            val nameScore = if (style.displayName.lowercase() in haystack) 8 else 0
            val patternScore = if (style.pattern != "plain" && style.pattern in haystack) 2 else 0
            keywordScore + nameScore + patternScore
        } ?: styles.last()
    }

    fun promptOptions(): String = styles
        .groupBy(DigitalFurnitureStyle::kind)
        .entries
        .joinToString("；") { (kind, values) ->
            "${kind.name.lowercase()}=${values.joinToString("/") { it.displayName }}"
        }

    fun kindLabel(kind: DigitalFurnitureKind): String = when (kind) {
        DigitalFurnitureKind.BED -> "床"
        DigitalFurnitureKind.SOFA -> "沙发"
        DigitalFurnitureKind.COFFEE_TABLE -> "茶几"
        DigitalFurnitureKind.TABLE -> "桌子"
        DigitalFurnitureKind.CHAIR -> "椅子"
        DigitalFurnitureKind.DESK -> "书桌"
        DigitalFurnitureKind.SHELF -> "书架"
        DigitalFurnitureKind.CABINET -> "柜子"
        DigitalFurnitureKind.NIGHTSTAND -> "床头柜"
        DigitalFurnitureKind.FLOOR_LAMP -> "落地灯"
        DigitalFurnitureKind.TABLE_LAMP -> "台灯"
        DigitalFurnitureKind.RUG -> "地毯"
        DigitalFurnitureKind.PLANT -> "绿植"
        DigitalFurnitureKind.TV -> "电视"
        DigitalFurnitureKind.MIRROR -> "镜子"
        DigitalFurnitureKind.WALL_ART -> "挂画"
        DigitalFurnitureKind.CLOCK -> "时钟"
        DigitalFurnitureKind.CUSHION -> "抱枕"
        DigitalFurnitureKind.BASKET -> "收纳"
        DigitalFurnitureKind.DECOR -> "摆件"
    }

    private fun kindFor(text: String): DigitalFurnitureKind = when {
        listOf("床头柜", "nightstand", "边几").any(text::contains) -> DigitalFurnitureKind.NIGHTSTAND
        listOf("床", "bed").any(text::contains) -> DigitalFurnitureKind.BED
        listOf("沙发", "sofa").any(text::contains) -> DigitalFurnitureKind.SOFA
        listOf("茶几", "coffee table").any(text::contains) -> DigitalFurnitureKind.COFFEE_TABLE
        listOf("书桌", "写字台", "desk").any(text::contains) -> DigitalFurnitureKind.DESK
        listOf("书架", "置物架", "shelf").any(text::contains) -> DigitalFurnitureKind.SHELF
        listOf("柜", "cabinet").any(text::contains) -> DigitalFurnitureKind.CABINET
        listOf("落地灯", "floor lamp", "流萤").any(text::contains) -> DigitalFurnitureKind.FLOOR_LAMP
        listOf("台灯", "床头灯", "table lamp", "小灯").any(text::contains) -> DigitalFurnitureKind.TABLE_LAMP
        listOf("地毯", "rug", "圆毯").any(text::contains) -> DigitalFurnitureKind.RUG
        listOf("植物", "绿植", "盆栽", "龟背竹", "橄榄树", "仙人掌", "plant").any(text::contains) -> DigitalFurnitureKind.PLANT
        listOf("电视", "显示器", "屏幕", "tv").any(text::contains) -> DigitalFurnitureKind.TV
        listOf("镜", "mirror").any(text::contains) -> DigitalFurnitureKind.MIRROR
        listOf("挂画", "画框", "装饰画", "wall art").any(text::contains) -> DigitalFurnitureKind.WALL_ART
        listOf("挂钟", "时钟", "clock").any(text::contains) -> DigitalFurnitureKind.CLOCK
        listOf("抱枕", "靠枕", "cushion").any(text::contains) -> DigitalFurnitureKind.CUSHION
        listOf("收纳篮", "收纳筐", "藤编篮", "basket").any(text::contains) -> DigitalFurnitureKind.BASKET
        listOf("椅", "chair").any(text::contains) -> DigitalFurnitureKind.CHAIR
        listOf("桌", "table").any(text::contains) -> DigitalFurnitureKind.TABLE
        else -> DigitalFurnitureKind.DECOR
    }
}
