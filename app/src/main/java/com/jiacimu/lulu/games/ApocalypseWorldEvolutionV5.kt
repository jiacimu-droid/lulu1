package com.jiacimu.lulu.games

internal enum class ApocalypseMapConditionV5 {
    Baseline,
    Unknown,
    Stressed,
    Offline,
    Damaged,
    Destroyed,
    Blocked,
    Occupied,
    Rebuilt,
    Overgrown,
    Flooded,
    Contaminated,
    Open,
}

internal data class ApocalypseKnownMapChangeV5(
    val type: String,
    val city: String,
    val target: String,
    val condition: ApocalypseMapConditionV5,
    val label: String,
    val detail: String,
)

internal data class ApocalypseMapStatusV5(
    val condition: ApocalypseMapConditionV5,
    val label: String,
    val detail: String,
    val confirmed: Boolean,
)

internal data class ApocalypseMapEvolutionV5(
    val dayIndex: Int,
    val eraTitle: String,
    val eraDetail: String,
    val infrastructureDecay: Float,
    val ecologyPressure: Float,
    val changes: List<ApocalypseKnownMapChangeV5>,
) {
    fun placeChange(city: String, target: String): ApocalypseKnownMapChangeV5? =
        changes.lastOrNull { it.type == "PLACE" && it.city == city && it.target == target }

    fun routeChange(target: String): ApocalypseKnownMapChangeV5? =
        changes.lastOrNull { it.type == "ROUTE" && it.target == target }

    fun zoneChanges(city: String): List<ApocalypseKnownMapChangeV5> =
        changes.filter { it.type == "ZONE" && it.city == city }
}

internal fun apocalypseMapEvolutionV5(save: ApocalypseV3Save): ApocalypseMapEvolutionV5 {
    val day = save.director.dayIndex
    val era = when {
        day < 0 -> Triple("灾前城市图", "道路、公共设施和城市功能仍按正常社会运行。", 0f to 0f)
        day <= 2 -> Triple("主沉降", "城市正在快速失序；地图上的设施仍在，但实时可用性开始失真。", .18f to .05f)
        day <= 13 -> Triple("城市失序", "停电、断水、拥堵、火灾和局部封锁持续改变道路与建筑状态。", .34f to .14f)
        day <= 44 -> Triple("废墟形成", "无人维护区开始明显损坏，临时据点和污染带逐渐取代原有城市功能。", .48f to .30f)
        day <= 179 -> Triple("据点时代", "幸存者开始修复、占领和重组基础设施；赤潮生态同时侵入空置城区。", .62f to .50f)
        day <= 539 -> Triple("赤潮新生态", "道路、建筑、人类据点和异化生态形成新的长期边界，灾前地图只能作为底图。", .74f to .72f)
        else -> Triple("文明重构", "旧城市骨架仍在，但新的聚居地、生态走廊和交通网络已经覆盖其上。", .82f to .90f)
    }
    return ApocalypseMapEvolutionV5(
        dayIndex = day,
        eraTitle = era.first,
        eraDetail = era.second,
        infrastructureDecay = era.third.first,
        ecologyPressure = era.third.second,
        changes = parseKnownMapChangesV5(save.director.worldFacts),
    )
}

internal fun apocalypsePlaceMapStatusV5(
    evolution: ApocalypseMapEvolutionV5,
    city: ApocalypseWorldCityV5,
    place: ApocalypseWorldPlaceV5,
    currentLocation: String,
    discovered: Boolean,
): ApocalypseMapStatusV5 {
    evolution.placeChange(city.name, place.name)?.let { change ->
        return ApocalypseMapStatusV5(change.condition, change.label, change.detail, true)
    }
    if (evolution.dayIndex < 0) {
        return ApocalypseMapStatusV5(ApocalypseMapConditionV5.Baseline, "正常", "灾前基础设施状态。", true)
    }
    if (currentLocation.contains(place.name)) {
        return ApocalypseMapStatusV5(ApocalypseMapConditionV5.Open, "现场", "你当前就在这里；具体状态以当前场景为准。", true)
    }
    if (discovered) {
        return ApocalypseMapStatusV5(ApocalypseMapConditionV5.Stressed, "旧情报", "这里曾被确认过，但灾后状态可能继续变化。", true)
    }
    return ApocalypseMapStatusV5(ApocalypseMapConditionV5.Unknown, "未知", "灾后没有足够可靠的最新情报，地图不会替你猜测。", false)
}

internal fun apocalypseRouteMapStatusV5(
    evolution: ApocalypseMapEvolutionV5,
    roadName: String,
): ApocalypseMapStatusV5 {
    evolution.routeChange(roadName)?.let { change ->
        return ApocalypseMapStatusV5(change.condition, change.label, change.detail, true)
    }
    return if (evolution.dayIndex < 0) {
        ApocalypseMapStatusV5(ApocalypseMapConditionV5.Baseline, "正常", "灾前主要交通走廊。", true)
    } else {
        ApocalypseMapStatusV5(ApocalypseMapConditionV5.Unknown, "需侦查", "灾后通行状态未知；封路、事故、尸群和势力检查站都可能改变路线。", false)
    }
}

internal fun apocalypseMapLedgerPromptV5(save: ApocalypseV3Save): String {
    val ledger = save.director.worldFacts.filter { it.startsWith("MAP_KNOWN|") }.takeLast(64)
    return if (ledger.isEmpty()) "暂无玩家已确认的地图变化账本。" else ledger.joinToString("｜")
}

internal fun mergeApocalypseWorldFactsV5(previous: List<String>, incoming: List<String>): List<String> {
    val combined = if (incoming.isEmpty()) previous else previous + incoming
    val mapChanges = LinkedHashMap<String, String>()
    val ordinary = mutableListOf<String>()
    combined.forEach { fact ->
        if (fact.startsWith("MAP_KNOWN|")) {
            val parts = fact.split('|')
            if (parts.size >= 6) {
                val key = listOf(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "" }).joinToString("|")
                mapChanges[key] = fact.take(360)
            }
        } else if (fact.isNotBlank()) {
            ordinary += fact.take(360)
        }
    }
    return ordinary.takeLast(28) + mapChanges.values.takeLast(64)
}

private fun parseKnownMapChangesV5(facts: List<String>): List<ApocalypseKnownMapChangeV5> {
    val latest = LinkedHashMap<String, ApocalypseKnownMapChangeV5>()
    facts.forEach { fact ->
        if (!fact.startsWith("MAP_KNOWN|")) return@forEach
        val parts = fact.split('|')
        if (parts.size < 6) return@forEach
        val type = parts[1].trim().uppercase()
        val city = parts[2].trim()
        val target = parts[3].trim()
        val label = parts[4].trim().ifBlank { "变化" }
        val detail = parts.drop(5).joinToString("|").trim().take(220)
        val change = ApocalypseKnownMapChangeV5(
            type = type,
            city = city,
            target = target,
            condition = mapConditionFromLabelV5(label),
            label = label,
            detail = detail,
        )
        latest["$type|$city|$target"] = change
    }
    return latest.values.toList()
}

private fun mapConditionFromLabelV5(label: String): ApocalypseMapConditionV5 = when {
    listOf("坍塌", "倒塌", "摧毁", "毁坏", "废墟").any(label::contains) -> ApocalypseMapConditionV5.Destroyed
    listOf("受损", "损坏", "火灾", "破坏").any(label::contains) -> ApocalypseMapConditionV5.Damaged
    listOf("封锁", "断路", "中断", "坍方", "不可通行").any(label::contains) -> ApocalypseMapConditionV5.Blocked
    listOf("占领", "控制", "据点", "接管").any(label::contains) -> ApocalypseMapConditionV5.Occupied
    listOf("修复", "恢复", "重建", "重开").any(label::contains) -> ApocalypseMapConditionV5.Rebuilt
    listOf("植被", "生态", "侵入", "覆盖", "菌毯").any(label::contains) -> ApocalypseMapConditionV5.Overgrown
    listOf("淹没", "内涝", "积水", "洪水").any(label::contains) -> ApocalypseMapConditionV5.Flooded
    listOf("污染", "赤潮", "泄漏").any(label::contains) -> ApocalypseMapConditionV5.Contaminated
    listOf("停运", "断电", "失联", "关闭").any(label::contains) -> ApocalypseMapConditionV5.Offline
    listOf("畅通", "开放", "通行", "恢复通行").any(label::contains) -> ApocalypseMapConditionV5.Open
    else -> ApocalypseMapConditionV5.Stressed
}
