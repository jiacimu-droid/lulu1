package com.jiacimu.lulu.games

import java.util.UUID

/**
 * Safe compatibility repair for the newest already-saved scene.
 *
 * Old builds sometimes persisted only one item even though the visible narration explicitly listed
 * a complete inventory count such as “胶弹枪4支、盾牌2面、背心13件、4盒共120发橡塑防爆弹”.
 * This parser is deliberately gated by inventory-counting context and is only used by the current
 * scene repair path, never as the primary acquisition mechanism for new scenes.
 */
internal fun recoverApocalypseExplicitInventorySnapshotV5(text: String): List<ApocalypseV3Asset> {
    if (text.isBlank() || !looksLikeInventorySnapshotSceneV5(text)) return emptyList()

    val recovered = mutableListOf<ApocalypseV3Asset>()

    // Packaging with an explicit total: “4盒共120发橡塑防爆弹” -> 120发橡塑防爆弹.
    val packagedTotal = Regex(
        """(\d{1,5})\s*(箱|盒|包|袋|提)\s*(?:共|合计|总计)\s*(\d{1,6})\s*(发|枚|瓶|罐|包|袋|支|件|片|粒)\s*([^、，,；;。！？!?\n]{2,36})""",
    )
    packagedTotal.findAll(text).forEach { match ->
        val packageCount = match.groupValues[1].toIntOrNull() ?: return@forEach
        val packageUnit = match.groupValues[2]
        val quantity = match.groupValues[3].toIntOrNull() ?: return@forEach
        val unit = match.groupValues[4]
        val title = cleanSnapshotTitleV5(match.groupValues[5])
        if (title.length < 2 || snapshotTitleLooksNarrativeV5(title)) return@forEach
        recovered += ApocalypseV3Asset(
            id = UUID.randomUUID().toString(),
            kind = inferSnapshotKindV5(title),
            title = title,
            detail = "当前幕明确清点：${packageCount}${packageUnit}共${quantity}${unit}",
            quantity = quantity.coerceIn(1, 99_999),
            tag = "单位=$unit",
        )
    }

    // Chinese prose often puts the count after the item: “民用防暴胶弹枪4支”.
    val trailingCount = Regex(
        """([^、，,；;。！？!?\n]{2,42}?)(\d{1,6})\s*(支|把|面|件|发|枚|瓶|罐|包|袋|盒|箱|卷|台|辆|套|根|个|份|片|粒)(?=[、，,；;。！？!?\n]|以及|及|和|与|$)""",
    )
    trailingCount.findAll(text).forEach { match ->
        val rawTitle = match.groupValues[1]
        val title = cleanSnapshotTitleV5(rawTitle)
        val quantity = match.groupValues[2].toIntOrNull() ?: return@forEach
        val unit = match.groupValues[3]
        if (title.length < 2 || snapshotTitleLooksNarrativeV5(title)) return@forEach
        if (recovered.any { sameSnapshotAssetV5(it.title, title) }) return@forEach
        recovered += ApocalypseV3Asset(
            id = UUID.randomUUID().toString(),
            kind = inferSnapshotKindV5(title),
            title = title,
            detail = "当前幕明确盘点数量",
            quantity = quantity.coerceIn(1, 99_999),
            tag = "单位=$unit",
        )
    }

    return recovered
        .filter { asset -> snapshotAssetLooksConcreteV5(asset.title) }
        .distinctBy { asset -> snapshotKeyV5(asset.title) }
        .take(64)
}

private fun looksLikeInventorySnapshotSceneV5(text: String): Boolean = listOf(
    "盘点", "清点", "枪柜", "武器柜", "装备柜", "库存", "物资清单", "仓库", "整理好的装备", "摆在台上", "码在台上",
).any(text::contains)

private fun cleanSnapshotTitleV5(raw: String): String {
    var value = raw.trim()
        .replace(Regex("^(?:【[^】]+】)?(?:我|玩家|佳辞)?(?:上前一步|走过去|走到[^，,。]{0,16})?[，,：:\s]*"), "")
        .trim(' ', '的', '：', ':')
    val suffixStops = listOf(
        "整齐摆在", "摆在", "放在", "码在", "排列在", "堆在", "收在", "装在", "已经", "随后", "然后",
    )
    suffixStops.mapNotNull { stop -> value.indexOf(stop).takeIf { it > 0 } }
        .minOrNull()
        ?.let { value = value.substring(0, it) }
    // Remove inventory-context lead-ins accidentally captured before the real noun.
    val leadIns = listOf("将枪柜中整理好的装备快速盘点", "枪柜中整理好的", "整理好的", "清点出", "盘点出")
    leadIns.forEach { prefix -> if (value.startsWith(prefix)) value = value.removePrefix(prefix) }
    return value.trim(' ', '的', '：', ':', '，', ',').take(64)
}

private fun snapshotTitleLooksNarrativeV5(title: String): Boolean = listOf(
    "上前一步", "快速盘点", "整齐摆在", "眉开眼笑", "道了声谢", "随手一挥", "将其", "看着", "说道", "说着", "随后", "然后",
).any(title::contains)

private fun snapshotAssetLooksConcreteV5(title: String): Boolean {
    val t = title.lowercase()
    return listOf(
        "枪", "弹", "盾", "背心", "防弹", "防爆", "防暴", "头盔", "护甲", "刀", "斧", "弓", "弩", "棍",
        "工具", "锤", "扳手", "钳", "钻", "锯", "铲", "绳", "板", "管", "线", "电池", "电源", "对讲机", "手机",
        "背包", "帐篷", "睡袋", "手电", "面具", "护目镜", "汽油", "柴油", "燃料", "发电机", "无人机", "车辆", "车",
    ).any(t::contains)
}

private fun inferSnapshotKindV5(title: String): ApocalypseV3AssetKind {
    val t = title.lowercase()
    fun has(vararg words: String) = words.any(t::contains)
    return when {
        has("枪", "弹", "盾", "防弹", "防爆", "防暴", "战术背心", "头盔", "护甲", "刀", "斧", "弓", "弩", "警棍", "甩棍") -> ApocalypseV3AssetKind.Weapon
        has("汽车", "轿车", "越野车", "货车", "卡车", "皮卡", "摩托", "电动车", "自行车", "快艇") -> ApocalypseV3AssetKind.Vehicle
        has("木板", "钢板", "钢管", "铁管", "角钢", "钢筋", "铁丝", "螺丝", "水泥", "砂浆", "电线", "线缆", "建材") -> ApocalypseV3AssetKind.Material
        else -> ApocalypseV3AssetKind.Tool
    }
}

private fun sameSnapshotAssetV5(first: String, second: String): Boolean =
    snapshotKeyV5(first) == snapshotKeyV5(second)

private fun snapshotKeyV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
    .replace("高分子", "")
    .replace("模块化", "")
    .replace("民用", "")
    .trim()
