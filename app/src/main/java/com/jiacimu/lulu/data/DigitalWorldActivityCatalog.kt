package com.jiacimu.lulu.data

/**
 * Program-owned affordances for furniture and shared locations.
 *
 * A model may choose one of these IDs, but the executable result is derived from the real item
 * and persisted by [DigitalWorldStore].
 */
internal object DigitalWorldActivityCatalog {
    fun optionsFor(item: DigitalWorldItem): List<Pair<String, String>> {
        val specific = when (DigitalFurnitureCatalog.resolve(item).kind) {
            DigitalFurnitureKind.BED -> listOf(
                "lie_down" to "躺一会儿",
                "rest" to "上床休息",
                "nap" to "开始小睡",
                "sleep" to "准备睡觉",
                "make_bed" to "整理床铺",
            )
            DigitalFurnitureKind.SOFA -> listOf(
                "sit" to "坐下",
                "curl_up" to "窝在沙发里",
                "rest" to "靠着休息",
                "nap" to "打个盹",
            )
            DigitalFurnitureKind.CHAIR -> listOf(
                "sit" to "坐下",
                "rest" to "靠着休息",
            )
            DigitalFurnitureKind.DESK -> listOf(
                "sit_at_desk" to "在桌前坐一会儿",
                "organize_surface" to "整理桌面",
            )
            DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> listOf(
                "sit_by_table" to "在桌边坐一会儿",
                "organize_surface" to "整理桌面",
            )
            DigitalFurnitureKind.SHELF -> listOf(
                "inspect_shelf" to "看看架上的陈设",
                "dust_item" to "清理表面",
            )
            DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND, DigitalFurnitureKind.BASKET -> listOf(
                "organize_storage" to "整理收纳",
                "inspect_item" to "检查一下",
            )
            DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP -> listOf(
                "adjust_lamp" to "调整灯的位置",
                "inspect_light" to "检查灯光",
            )
            DigitalFurnitureKind.RUG -> listOf(
                "sit_on_rug" to "坐到地毯上",
                "stretch" to "在地毯上舒展身体",
                "smooth_rug" to "把地毯理平",
            )
            DigitalFurnitureKind.PLANT -> listOf(
                "tend_plant" to "照料植物",
                "inspect_leaves" to "看看叶片状态",
            )
            DigitalFurnitureKind.TV -> listOf(
                "clean_screen" to "擦一擦屏幕",
                "inspect_screen" to "检查屏幕",
            )
            DigitalFurnitureKind.MIRROR -> listOf(
                "check_reflection" to "照照镜子",
                "clean_mirror" to "擦拭镜面",
            )
            DigitalFurnitureKind.WALL_ART -> listOf(
                "admire_art" to "看一会儿挂画",
                "straighten_art" to "把挂画扶正",
            )
            DigitalFurnitureKind.CLOCK -> listOf(
                "check_time" to "确认时间",
                "inspect_clock" to "检查时钟",
            )
            DigitalFurnitureKind.CUSHION -> listOf(
                "hug_cushion" to "抱着靠一会儿",
                "adjust_cushion" to "整理抱枕",
            )
            DigitalFurnitureKind.DECOR -> listOf(
                "inspect_item" to "仔细看看",
                "dust_item" to "清理表面",
            )
        }
        return (specific + listOf("inspect_item" to "查看这件物品")).distinctBy { it.first }
    }

    fun promptFor(items: List<DigitalWorldItem>): String = items.joinToString("\n") { item ->
        val options = optionsFor(item).joinToString("/") { (id, label) -> "$id=$label" }
        "- itemId=${item.id}；${item.name}；可执行 activityId=$options"
    }

    fun itemActivitySummary(
        characterName: String,
        item: DigitalWorldItem,
        activityId: String,
    ): String? {
        if (optionsFor(item).none { it.first == activityId }) return null
        val action = when (activityId) {
            "lie_down" -> "在“${item.name}”上躺了下来"
            "rest" -> "开始在“${item.name}”上休息"
            "nap" -> "在“${item.name}”上安顿好，开始打盹"
            "sleep" -> "在“${item.name}”上躺好，准备睡觉"
            "make_bed" -> "认真整理了“${item.name}”"
            "sit" -> "在“${item.name}”上坐了下来"
            "curl_up" -> "窝进了“${item.name}”里"
            "sit_at_desk" -> "在“${item.name}”前坐了下来"
            "sit_by_table" -> "在“${item.name}”旁坐了一会儿"
            "organize_surface" -> "整理了“${item.name}”的表面"
            "inspect_shelf" -> "查看了“${item.name}”上的现有陈设"
            "dust_item" -> "清理了“${item.name}”的表面"
            "organize_storage" -> "整理了“${item.name}”"
            "adjust_lamp" -> "调整了“${item.name}”的位置和朝向"
            "inspect_light" -> "检查了“${item.name}”的灯光状态"
            "sit_on_rug" -> "坐到了“${item.name}”上"
            "stretch" -> "在“${item.name}”上舒展了一会儿"
            "smooth_rug" -> "把“${item.name}”重新理平"
            "tend_plant" -> "照料了“${item.name}”"
            "inspect_leaves" -> "检查了“${item.name}”的叶片状态"
            "clean_screen" -> "擦拭了“${item.name}”的屏幕"
            "inspect_screen" -> "检查了“${item.name}”的屏幕状态"
            "check_reflection" -> "站在“${item.name}”前看了看自己"
            "clean_mirror" -> "擦拭了“${item.name}”的镜面"
            "admire_art" -> "停在“${item.name}”前看了一会儿"
            "straighten_art" -> "把“${item.name}”重新扶正"
            "check_time" -> "看了看“${item.name}”显示的时间"
            "inspect_clock" -> "检查了“${item.name}”的运行状态"
            "hug_cushion" -> "把“${item.name}”抱进怀里靠了一会儿"
            "adjust_cushion" -> "重新摆好了“${item.name}”"
            else -> "仔细查看了“${item.name}”"
        }
        return "$characterName$action；这件物品真实位于${item.position}。"
    }

    fun locationOptions(locationCode: String): List<Pair<String, String>> = when (locationCode) {
        DigitalWorldStore.CLOUD_MEADOW -> listOf(
            "cloud_walk" to "在云眠原散步",
            "cloud_sit" to "坐在感官云质上",
            "cloud_rest" to "躺在感官云质上休息",
            "cloud_feel" to "感受云质的温度与重量",
        )
        DigitalWorldStore.ARRIVAL -> listOf(
            "arrival_wait" to "在入口等待一会儿",
            "arrival_observe" to "观察世界入口",
        )
        else -> if (locationCode.startsWith("home:")) {
            listOf(
                "home_pace" to "在当前家园走动",
                "home_quiet_rest" to "在当前家园安静休息",
            )
        } else emptyList()
    }

    fun locationActivitySummary(
        characterName: String,
        locationCode: String,
        activityId: String,
        locationName: String,
    ): String? {
        if (locationOptions(locationCode).none { it.first == activityId }) return null
        val action = when (activityId) {
            "cloud_walk" -> "在云眠原的感官云质上散了一会儿步"
            "cloud_sit" -> "在云眠原的感官云质上坐了下来"
            "cloud_rest" -> "躺在云眠原的感官云质上开始休息"
            "cloud_feel" -> "停下来感受云眠原云质传来的柔软、温度与重量"
            "arrival_wait" -> "在世界入口停下来等待了一会儿"
            "arrival_observe" -> "仔细观察了一会儿世界入口的实际状态"
            "home_pace" -> "在${locationName}里慢慢走动了一圈"
            "home_quiet_rest" -> "在${locationName}里停下来安静休息"
            else -> return null
        }
        return "$characterName$action。"
    }
}
