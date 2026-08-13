package com.jiacimu.lulu.games

/**
 * Inventory is canonical only through the scene writer's hidden structured receipt.
 *
 * The client deliberately does not infer, split or invent inventory semantics. The writing model must
 * decide the concrete SKU list and quantities itself in the same generation that writes the scene.
 * This file is only a validator/save gate plus legacy visible-audit cleanup.
 */
internal fun apocalypseInventoryQuantityContractV5(): String = """
【模型主动拆分物资｜最高优先硬规则】
1. 你是本幕正文作者，同时也是本幕唯一的物资记账者。写正文前，先在内部完成一次“本幕获得/消耗物资盘点”，确定每一个具体SKU、准确数量和真实计量单位；然后正文与隐藏状态回执必须共同使用这一份盘点结果。客户端不会替你猜、拆、补数量。
2. 只要本幕真实购买、搜集、领取、交换、捡到、搬走或收入空间任何实体物品，隐藏回执必须主动返回 acquiredItems。正文获得多少种具体SKU，acquiredItems就逐项列多少种，一件都不能漏。
3. 仓库只保存“最小可独立消耗/使用/计数的具体SKU”。组合箱、礼包、礼盒、套装、套餐、拼盘、一批装备、若干食品、混合物资、综合补给都绝对不能作为最终库存title。父包装只能写进detail作为来源。
4. acquiredItems每项必须包含{id,kind,title,quantity,unit,detail,tag}。title只能写一个具体物品名；quantity必须是明确整数；unit必须是后续真正会扣减的单位。
5. 组合包装必须由你主动展开。例如“6箱零食组合箱，内有薯片、奶糖、巧克力、果干”，你必须在本幕就决定并固定每箱构成，再返回“香辣薯片×总包数、奶糖×总颗数、巧克力×总块数、果干×总袋数”等独立SKU；禁止返回“零食组合箱×6份”。
6. 食物优先用可直接消耗单位：糖果按颗，薯片按包，巧克力按块/条，罐头按罐，方便面/饼干按包，水和饮料按瓶/罐，面包按包/片，速冻食品按袋/盒。药片按片、胶囊按粒、绷带按卷、针剂按支。弹药按发/枚，枪械按支/把，盾牌按面，背心/护甲/头盔按件。
7. 正文明示包装数量时必须精确换算。例如10箱水×12瓶/箱=120瓶；4盒弹药共120发，则acquiredItems记录120发而不是4盒。不得把“份”当万能单位。
8. 如果玩家买的是虚构组合商品且正文此前没有给出子品数量，你作为本幕作者必须在写正文时一次性确定合理、明确、不过度夸张的包装构成，并把这些数字写进正文可见采购/清点结果与acquiredItems；以后这组数字成为正史，不能下一幕改口。
9. 同一种具体SKU本幕只返回一条，数量合并。新获得只放acquiredItems；已有库存吃掉、喝掉、使用、丢失、赠送、损坏时用inventoryChanges负quantityDelta，并沿用已有具体库存id/title。
10. 正文与回执必须一致：正文说吃了1颗奶糖，inventoryChanges就让奶糖-1；正文说吃了1包薯片，就让薯片-1。不能只减抽象“食物”而不减具体SKU。
11. 不得在玩家可见正文中写“入库清点、库存审计、系统补记、数量待确认”等系统文字。物资账单只存在隐藏回执；正文只自然写采购、清点、食用和使用行为。
12. 无论其他旧示例是否出现discoverAssets，以本规则为准：新获得物资必须完整写入acquiredItems；discoverAssets仅视为旧兼容字段，不能用它规避逐SKU拆分。
""".trimIndent()

private val apocalypseBundledInventoryWordsV5 = listOf(
    "组合箱", "组合盒", "组合包", "组合装", "组合套", "组合",
    "大礼包", "礼包", "礼盒", "套装", "套餐", "拼盘", "混合装", "混合包", "混装",
    "一批", "整批", "若干", "多种", "综合补给", "综合物资", "补给包", "补给箱",
    "食品物资", "食物物资", "零食干果", "零食组合", "早点组合", "面食组合", "速冻组合",
    "战术装备", "防暴装备", "生活用品一批", "补给物资", "调味包大礼包", "调味礼包",
)

private val apocalypseBundledInventoryJoinersV5 = listOf("及", "以及", "与", "和", "、", "/", "+")

/** A parent package row is never a legal final concrete warehouse SKU. */
internal fun apocalypseInventoryAssetNeedsGranularRepairV5(asset: ApocalypseV3Asset): Boolean {
    val title = asset.title.trim()
    val detail = asset.detail.trim()
    val combined = "$title $detail"
    if (apocalypseBundledInventoryWordsV5.any(combined::contains)) return true

    if (
        asset.kind in setOf(
            ApocalypseV3AssetKind.Food,
            ApocalypseV3AssetKind.Water,
            ApocalypseV3AssetKind.Medicine,
            ApocalypseV3AssetKind.Weapon,
            ApocalypseV3AssetKind.Tool,
            ApocalypseV3AssetKind.Material,
        ) &&
        apocalypseBundledInventoryJoinersV5.any(title::contains)
    ) {
        return true
    }

    if (
        listOf("包含", "内含", "含有", "包括", "内有", "装有").any(detail::contains) &&
        listOf('、', '，', ',').any(detail::contains)
    ) {
        return true
    }

    val unit = apocalypseStructuredInventoryUnitV5(asset)
    if (
        asset.kind in setOf(ApocalypseV3AssetKind.Food, ApocalypseV3AssetKind.Water, ApocalypseV3AssetKind.Medicine) &&
        unit == "份" &&
        listOf("箱", "盒", "礼包", "组合", "套装", "礼盒", "套餐").any(combined::contains)
    ) {
        return true
    }
    return false
}

internal fun apocalypseInventoryHasBundledRowsV5(assets: List<ApocalypseV3Asset>): Boolean =
    assets.any(::apocalypseInventoryAssetNeedsGranularRepairV5)

internal fun apocalypseInventoryReceiptNeedsGranularRepairV5(outcome: ApocalypseSceneOutcomeV5): Boolean {
    val additions = outcome.delta.discoverAssets
    if (additions.isNotEmpty() && "acquiredItems" !in outcome.reportedStateFields) return true
    if (additions.any { asset -> !Regex("(?:^|[；;])单位=[^；;]+").containsMatchIn(asset.tag) }) return true
    if (additions.any(::apocalypseInventoryAssetNeedsGranularRepairV5)) return true

    fun positiveDeltaWithoutConcrete(kind: ApocalypseV3AssetKind, delta: Int, field: String): Boolean =
        field in outcome.reportedStateFields && delta > 0 && additions.none { it.kind == kind }

    return positiveDeltaWithoutConcrete(ApocalypseV3AssetKind.Food, outcome.delta.foodDelta, "foodDelta") ||
        positiveDeltaWithoutConcrete(ApocalypseV3AssetKind.Water, outcome.delta.waterDelta, "waterDelta") ||
        positiveDeltaWithoutConcrete(ApocalypseV3AssetKind.Medicine, outcome.delta.medicineDelta, "medicineDelta") ||
        positiveDeltaWithoutConcrete(ApocalypseV3AssetKind.Material, outcome.delta.materialsDelta, "materialsDelta")
}

/**
 * Compatibility hook used by the generation pipeline.
 *
 * No second inventory model, no prose regex and no client-side semantic splitting. If the scene
 * writer did not produce an atomic receipt, the scene is rejected instead of silently saving a bad
 * warehouse row. The writing model must get it right in its own scene generation / normal rewrite.
 */
internal suspend fun recoverApocalypseNarratedInventoryV5(
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 {
    val cleaned = outcome.copy(text = stripLegacyApocalypseInventoryAuditV5(outcome.text))
    if (apocalypseInventoryReceiptNeedsGranularRepairV5(cleaned)) {
        throw IllegalStateException(
            "本幕模型没有主动返回逐SKU acquiredItems（含明确数量和单位）；这次结果没有写入存档，请重试这一幕。",
        )
    }
    return cleaned
}

private fun apocalypseStructuredInventoryUnitV5(asset: ApocalypseV3Asset): String {
    Regex("(?:^|[；;])单位=([^；;]+)")
        .find(asset.tag)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    return when (asset.kind) {
        ApocalypseV3AssetKind.Food -> when {
            asset.title.contains("糖") -> "颗"
            asset.title.contains("薯片") || asset.title.contains("方便面") || asset.title.contains("泡面") || asset.title.contains("饼干") -> "包"
            asset.title.contains("巧克力") -> "块"
            asset.title.contains("罐头") -> "罐"
            asset.title.contains("面包") || asset.title.contains("吐司") -> "包"
            asset.title.contains("水饺") || asset.title.contains("饺子") || asset.title.contains("馄饨") -> "袋"
            else -> "份"
        }
        ApocalypseV3AssetKind.Water -> "瓶"
        ApocalypseV3AssetKind.Medicine -> when {
            asset.title.contains("片") -> "片"
            asset.title.contains("胶囊") -> "粒"
            asset.title.contains("绷带") -> "卷"
            asset.title.contains("针") || asset.title.contains("注射") -> "支"
            else -> "件"
        }
        ApocalypseV3AssetKind.Weapon -> when {
            asset.title.contains("弹") -> "发"
            asset.title.contains("盾") -> "面"
            asset.title.contains("枪") -> "支"
            asset.title.contains("刀") || asset.title.contains("斧") -> "把"
            else -> "件"
        }
        ApocalypseV3AssetKind.Vehicle -> "辆"
        ApocalypseV3AssetKind.Key -> "枚"
        ApocalypseV3AssetKind.Document, ApocalypseV3AssetKind.Clue, ApocalypseV3AssetKind.Map -> "份"
        ApocalypseV3AssetKind.Core -> "枚"
        else -> "件"
    }
}

/** Remove only the exact legacy audit paragraph previously appended by old clients. */
internal fun stripLegacyApocalypseInventoryAuditV5(text: String): String {
    if (text.isBlank()) return text
    val marker = "【旁白】入库清点："
    val index = text.lastIndexOf(marker)
    if (index < 0) return text
    return text.substring(0, index).trimEnd()
}
