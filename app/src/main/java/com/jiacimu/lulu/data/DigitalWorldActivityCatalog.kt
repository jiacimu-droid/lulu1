package com.jiacimu.lulu.data

/**
 * Program-owned affordances for furniture and shared locations.
 *
 * A model may choose one of these IDs, but the executable result is derived from the real item
 * and persisted by [DigitalWorldStore]. The foreground explorer uses the same catalog so player
 * and autonomous characters live by one set of world rules.
 */
internal object DigitalWorldActivityCatalog {
    fun optionsFor(item: DigitalWorldItem): List<Pair<String, String>> {
        val specific = when (DigitalFurnitureCatalog.resolve(item).kind) {
            DigitalFurnitureKind.BED -> listOf(
                "sit_on_bed" to "坐到床边",
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
                "read_at_desk" to "坐下读点东西",
                "organize_surface" to "整理桌面",
            )
            DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> listOf(
                "sit_by_table" to "在桌边坐一会儿",
                "have_snack" to "吃点东西",
                "organize_surface" to "整理桌面",
            )
            DigitalFurnitureKind.SHELF -> listOf(
                "inspect_shelf" to "看看架上的陈设",
                "browse_shelf" to "随手翻翻架上的东西",
                "dust_item" to "清理表面",
            )
            DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND, DigitalFurnitureKind.BASKET -> listOf(
                "organize_storage" to "整理收纳",
                "inspect_item" to "检查一下",
            )
            DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP -> listOf(
                "toggle_lamp" to "开关灯",
                "adjust_lamp" to "调整灯的位置",
                "inspect_light" to "检查灯光",
            )
            DigitalFurnitureKind.RUG -> listOf(
                "sit_on_rug" to "坐到地毯上",
                "stretch" to "在地毯上舒展身体",
                "lie_on_rug" to "在地毯上躺一会儿",
                "smooth_rug" to "把地毯理平",
            )
            DigitalFurnitureKind.PLANT -> listOf(
                "tend_plant" to "照料植物",
                "water_plant" to "给植物浇水",
                "inspect_leaves" to "看看叶片状态",
            )
            DigitalFurnitureKind.TV -> listOf(
                "turn_on_tv" to "打开电视",
                "clean_screen" to "擦一擦屏幕",
            )
            DigitalFurnitureKind.MIRROR -> listOf(
                "check_reflection" to "照照镜子",
                "fix_appearance" to "对着镜子整理一下自己",
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
                "play_with_decor" to "摆弄一下",
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
            "sit_on_bed" -> "在“${item.name}”床边坐了下来"
            "lie_down" -> "在“${item.name}”上躺了下来"
            "rest" -> "开始在“${item.name}”上休息"
            "nap" -> "在“${item.name}”上安顿好，开始打盹"
            "sleep" -> "在“${item.name}”上躺好，准备睡觉"
            "make_bed" -> "认真整理了“${item.name}”"
            "sit" -> "在“${item.name}”上坐了下来"
            "curl_up" -> "窝进了“${item.name}”里"
            "watch_tv_from_sofa" -> "窝在“${item.name}”里看了一会儿电视"
            "sit_at_desk" -> "在“${item.name}”前坐了下来"
            "read_at_desk" -> "在“${item.name}”前坐下读了一会儿东西"
            "sit_by_table" -> "在“${item.name}”旁坐了一会儿"
            "have_snack" -> "在“${item.name}”旁吃了点东西"
            "play_table_game" -> "在“${item.name}”上摆开了一局桌面游戏"
            "organize_surface" -> "整理了“${item.name}”的表面"
            "inspect_shelf" -> "查看了“${item.name}”上的现有陈设"
            "browse_shelf" -> "查看了“${item.name}”，没有凭空增加架上的物品"
            "dust_item" -> "清理了“${item.name}”的表面"
            "organize_storage" -> "整理了“${item.name}”"
            "toggle_lamp" -> "伸手调整了“${item.name}”的开关"
            "adjust_lamp" -> "调整了“${item.name}”的位置和朝向"
            "inspect_light" -> "检查了“${item.name}”的灯光状态"
            "sit_on_rug" -> "坐到了“${item.name}”上"
            "stretch" -> "在“${item.name}”上舒展了一会儿"
            "lie_on_rug" -> "在“${item.name}”上躺了一会儿"
            "smooth_rug" -> "把“${item.name}”重新理平"
            "tend_plant" -> "照料了“${item.name}”"
            "water_plant" -> "给“${item.name}”补了水"
            "inspect_leaves" -> "检查了“${item.name}”的叶片状态"
            "turn_on_tv" -> "打开了“${item.name}”"
            "watch_tv" -> "在“${item.name}”前看了一会儿节目"
            "change_channel" -> "给“${item.name}”换了个节目"
            "clean_screen" -> "擦拭了“${item.name}”的屏幕"
            "check_reflection" -> "站在“${item.name}”前看了看自己"
            "fix_appearance" -> "对着“${item.name}”整理了一下自己"
            "clean_mirror" -> "擦拭了“${item.name}”的镜面"
            "admire_art" -> "停在“${item.name}”前看了一会儿"
            "straighten_art" -> "把“${item.name}”重新扶正"
            "check_time" -> "看了看“${item.name}”显示的时间"
            "inspect_clock" -> "检查了“${item.name}”的运行状态"
            "hug_cushion" -> "把“${item.name}”抱进怀里靠了一会儿"
            "adjust_cushion" -> "重新摆好了“${item.name}”"
            "play_with_decor" -> "随手摆弄了一会儿“${item.name}”"
            else -> "仔细查看了“${item.name}”"
        }
        return "$characterName$action。"
    }

    fun locationOptions(locationCode: String): List<Pair<String, String>> = when (locationCode) {
        DigitalWorldStore.CLOUD_MEADOW -> listOf(
            "cloud_walk" to "沿云面慢慢散步",
            "cloud_sit" to "坐到感官云质上",
            "cloud_rest" to "躺下来休息",
            "cloud_feel" to "感受云质的温度",
            "cloud_edge" to "走到云缘看看远处",
        )
        DigitalWorldPublicPlaces.GAME_HALL -> listOf(
            "browse_games" to "逛逛机台",
            "choose_arcade" to "挑一台想玩的机器",
            "check_scoreboard" to "看看成绩榜",
            "sit_arcade" to "去休息区坐坐",
            "arcade_linger" to "在灯光和音效里待一会儿",
        )
        DigitalWorldPublicPlaces.READING_LOUNGE -> listOf(
            "browse_reading" to "慢慢翻阅读架",
            "quiet_read" to "找安静位置阅读",
            "window_read" to "坐到窗边阅读",
            "reading_wander" to "在书架间随便逛逛",
            "reading_rest" to "在软座上安静坐会儿",
        )
        DigitalWorldPublicPlaces.CAFE -> listOf(
            "order_drink" to "领取一杯温水",
            "order_snack" to "领取一份原味饼干",
            "cafe_sit" to "找个座位坐下",
            "window_cafe" to "去靠窗的位置",
            "people_watch" to "看看周围",
            "slow_drink" to "慢慢喝一会儿",
            "cafe_linger" to "在店里放空一会儿",
        )
        DigitalWorldPublicPlaces.COURTYARD -> listOf(
            "courtyard_walk" to "沿庭院慢慢散步",
            "courtyard_sit" to "去长椅坐坐",
            "watch_sky" to "抬头看看天色",
            "water_edge" to "走到水边停一会儿",
            "garden_pause" to "在植物旁边待一会儿",
            "courtyard_linger" to "随便走走，不赶时间",
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
        if (activityId.startsWith("reality_")) {
            val valid = activityId.startsWith("reality_explore:") ||
                activityId.startsWith("reality_follow:") ||
                activityId.startsWith("reality_unfollow:") ||
                activityId.startsWith("reality_open:")
            if (!valid || activityId.substringAfter(':', "").isBlank()) return null
            return "${characterName}打开了露露机通往现实世界的信息窗口。"
        }
        if (locationOptions(locationCode).none { it.first == activityId }) return null
        val action = when (activityId) {
            "cloud_walk" -> "沿着云眠原的感官云面慢慢散了一会儿步"
            "cloud_sit" -> "在云眠原的感官云质上坐了下来"
            "cloud_rest" -> "躺在云眠原的感官云质上开始休息"
            "cloud_feel" -> "停下来感受云眠原云质传来的柔软、温度与重量"
            "cloud_edge" -> "走到云眠原边缘，停下来望了一会儿远处"
            "browse_games" -> "在游戏馆的机台之间慢慢逛了一圈"
            "choose_arcade" -> "在游戏馆几台机子前来回看了看，挑着自己现在最想玩的"
            "watch_game" -> "在游戏馆里停下来围观了一会儿别人的游戏"
            "check_scoreboard" -> "走到游戏馆的成绩榜前看了一会儿今天的记录"
            "sit_arcade" -> "在游戏馆休息区坐了一会儿"
            "arcade_linger" -> "没急着开始游戏，只是在游戏馆的灯光和音效里待了一会儿"
            "browse_reading" -> "走到阅读馆的书架前，准备查看实际书目"
            "quiet_read" -> "在阅读馆找了个安静的位置坐下阅读"
            "window_read" -> "在阅读馆靠窗的位置坐下读了一会儿"
            "reading_notes" -> "在阅读馆停下来整理了一下刚才读到的内容和自己的想法"
            "reading_wander" -> "沿着阅读馆的书架开始慢慢走动"
            "reading_rest" -> "在阅读馆的软座上坐了一会儿，让自己安静下来"
            "order_drink" -> "在浮光咖啡角领取了一杯温水"
            "order_snack" -> "在浮光咖啡角领取了一份原味饼干"
            "cafe_sit" -> "在浮光咖啡角找了个舒服的位置坐下"
            "window_cafe" -> "换到浮光咖啡角靠窗的位置坐下，看着外面的光景"
            "people_watch" -> "在浮光咖啡角看了看周围，没有据此假定有人在场"
            "slow_drink" -> "坐在浮光咖啡角慢慢喝着东西，没有急着离开"
            "cafe_linger" -> "在浮光咖啡角放空了一会儿，只听着周围细碎的声音"
            "courtyard_walk" -> "沿着共生庭院的步道慢慢散了一会儿步"
            "courtyard_sit" -> "在共生庭院的长椅上坐下来休息"
            "watch_sky" -> "在共生庭院停下来抬头看了一会儿天色"
            "water_edge" -> "走到共生庭院的水边停了一会儿"
            "garden_pause" -> "在共生庭院的植物旁边慢慢停下脚步"
            "courtyard_linger" -> "没有明确目的地在共生庭院随便走了一会儿"
            "arrival_wait" -> "在世界入口停下来等待了一会儿"
            "arrival_observe" -> "仔细观察了一会儿世界入口的实际状态"
            "home_pace" -> "在${locationName}里慢慢走动了一圈"
            "home_quiet_rest" -> "在${locationName}里停下来安静休息"
            else -> return null
        }
        return "$characterName$action。"
    }
}
