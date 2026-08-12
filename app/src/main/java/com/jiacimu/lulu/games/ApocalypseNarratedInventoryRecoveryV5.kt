package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Inventory is canonical only through the writer's hidden structured receipt.
 *
 * Prose stays literary and is never regex-scanned for stock. This file only validates/repairs the
 * already structured receipt when a model still tries to collapse several independently consumable
 * things into a single "组合箱/礼包/一批装备" row.
 */
internal fun apocalypseInventoryQuantityContractV5(): String = """
【具体物资与数量硬规则】
1. 只要本幕真实购买、搜集、领取、交换、捡到、搬走或收入空间任何实体物品，隐藏状态回执 acquiredItems 必须逐项完整列出本幕真正获得的全部物品；客户端只按结构化清单入库，绝不从正文反推库存。
2. 仓库只保存“最小可独立消耗/使用/计数的具体品类”。组合箱、礼包、礼盒、套装、拼盘、一批装备、若干食品、混合物资都不能作为最终库存条目；必须把内部每一种真实物品拆成独立记录，父级组合包装只写进 detail 作为来源。
3. acquiredItems 每项必须包含 id、kind、title、quantity、unit、detail、tag。title必须是一个具体物品名；quantity必须是明确整数；unit必须是实际追踪单位。
4. 食物优先使用后续真实会吃掉的单位：糖果按“颗”（若明确整包不可拆则按包）、薯片按“包”、巧克力按“块/条”、罐头按“罐”、方便面按“包”、饮料/水按“瓶/罐”、面包按“包/片”取正文实际包装。药片按片、胶囊按粒、绷带按卷、针剂按支。弹药按发/枚，枪械按支/把，盾牌按面，背心/护甲/头盔按件。
5. 如果采购的是N个相同组合箱，且每箱内部构成由本幕模型决定，则本幕就必须把每个子品类的“每箱数量×箱数=总数量”确定下来，并直接把总数量写进 acquiredItems；以后不能再保留一个“组合箱×N份”的模糊父条目。
6. 如果正文已经明确了包装数量，严格使用正文数字。例如10箱水×12瓶/箱=120瓶；4盒弹药共120发，则库存记录120发而不是4盒。不得把“份”当作万能单位。
7. 新获得的每个子品类只出现一次；不要同时在 acquiredItems 和 inventoryChanges 正向重复入账。已有物品吃掉、喝掉、使用、丢失、赠送、损坏时才用 inventoryChanges 的负 quantityDelta，并沿用具体库存名称/id。
8. 正文获得N种具体东西，acquiredItems必须有N条或更多（组合包装拆开后可以更多），一件都不能漏。没有获得新物品时返回空数组 []。
9. 不得在玩家可见正文中追加“入库清点”“库存审计”“系统补记”“数量待确认”等系统文字。
""".trimIndent()

private val apocalypseBundledInventoryWordsV5 = listOf(
    "组合箱", "组合盒", "组合包", "组合装", "组合套", "组合", "大礼包", "礼包", "礼盒",
    "套装", "套餐", "拼盘", "混合装", "混合包", "一批", "整批", "若干", "多种",
    "食品物资", "食物物资", "零食干果", "战术装备", "防暴装备", "生活用品一批", "补给物资",
)

private val apocalypseBundledInventoryJoinersV5 = listOf("及", "以及", "与", "和", "、", "/")

private fun apocalypseInventoryAssetNeedsGranularRepairV5(asset: ApocalypseV3Asset): Boolean {
    val title = asset.title.trim()
    val detail = asset.detail.trim()
    val combined = "$title $detail"
    if (apocalypseBundledInventoryWordsV5.any(combined::contains)) return true

    // A title that itself joins multiple independently countable nouns is almost always a bundled row.
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

    // "包含A、B、C" in a stock row means the model stored a container instead of its contents.
    if (
        listOf("包含", "内含", "含有", "包括").any(detail::contains) &&
        listOf('、', '，', ',').any(detail::contains)
    ) {
        return true
    }

    val unit = apocalypseStructuredInventoryUnitV5(asset)
    if (
        asset.kind in setOf(ApocalypseV3AssetKind.Food, ApocalypseV3AssetKind.Water, ApocalypseV3AssetKind.Medicine) &&
        unit == "份" &&
        listOf("箱", "礼包", "组合", "套装", "礼盒").any(combined::contains)
    ) {
        return true
    }
    return false
}

private fun apocalypseInventoryReceiptNeedsGranularRepairV5(outcome: ApocalypseSceneOutcomeV5): Boolean {
    val additions = outcome.delta.discoverAssets
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
 * It never infers stock from prose. If the structured receipt is too coarse, a tiny hidden repair call
 * rewrites only acquiredItems. The visible story remains byte-for-byte the same (apart from removing
 * the exact legacy audit paragraph from very old builds).
 */
internal suspend fun recoverApocalypseNarratedInventoryV5(
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 {
    val cleaned = outcome.copy(text = stripLegacyApocalypseInventoryAuditV5(outcome.text))
    if (!apocalypseInventoryReceiptNeedsGranularRepairV5(cleaned)) return cleaned

    val originalAssets = JSONArray().apply {
        cleaned.delta.discoverAssets.forEach { asset ->
            put(
                JSONObject()
                    .put("id", asset.id)
                    .put("kind", apocalypseStructuredInventoryKindNameV5(asset.kind))
                    .put("title", asset.title)
                    .put("quantity", asset.quantity)
                    .put("unit", apocalypseStructuredInventoryUnitV5(asset))
                    .put("detail", asset.detail)
                    .put("tag", asset.tag),
            )
        }
    }
    val facts = buildString {
        appendLine("【不展示给玩家的库存账本修复】")
        appendLine("玩家可见正文保持不变：")
        appendLine(cleaned.text.take(5_600))
        appendLine("原结构化新增物资：")
        appendLine(originalAssets.toString())
        appendLine("原动作结果：${cleaned.actionOutcome.take(500)}")
    }
    val instruction = """
        你只修复这一幕的隐藏新增物资清单，不改正文，不补剧情，不解释。只返回一个合法单行JSON对象：{"acquiredItems":[...]}。

        ${apocalypseInventoryQuantityContractV5()}

        额外要求：
        - 原清单中已经足够具体的单品必须保留，不能因为拆组合箱而漏掉。
        - 对“组合箱/礼包/礼盒/套装/一批装备”等父条目，删除父条目，把其内部每个独立品类展开成单独 acquiredItems。
        - 如果这是虚构采购且正文/原detail只列了内部品类、没有逐项数量，你现在作为同一幕的库存记账模型必须为每个子品类确定合理的明确整数数量，并把它作为本幕正史数量固定下来；不得返回“未知/若干/一份”。数量应与原箱数、包装常识和正文规模相称，不能凭空夸张。
        - quantity是实际可追踪单位总数，不是外包装数量。detail可以写“来源：风味零食组合箱6箱；每箱4包薯片，共24包”之类包装关系。
        - 同一种具体物品只返回一条，数量合并。
        - 不输出 inventoryChanges，不输出正文，不输出Markdown。
    """.trimIndent()

    val generated = LuluAiServices.gateway.generate(
        characterId = "__apocalypse_inventory_receipt_v5__",
        facts = facts,
        instruction = instruction,
        source = "末世求生V5物资细分",
        title = "末世求生 · 隐藏物资细分",
        temperature = 0.18,
        maxTokens = 1500,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
        streamResponse = false,
        readTimeoutMillis = 45_000,
    ).getOrNull() ?: throw IllegalStateException("物资清单需要细分，但模型没有返回可用的细分账本；本幕没有写入存档，请重试。")

    val repairedAssets = parseApocalypseGranularInventoryRepairV5(generated.text)
    if (repairedAssets.isEmpty() || repairedAssets.any(::apocalypseInventoryAssetNeedsGranularRepairV5)) {
        throw IllegalStateException("物资清单仍然把多种物品合并在一起；本幕没有写入存档，请重试。")
    }

    val fields = cleaned.reportedStateFields.toMutableSet().apply {
        add("acquiredItems")
        add("discoverAssets")
    }
    return cleaned.copy(
        simulationStateReported = true,
        reportedStateFields = fields,
        delta = cleaned.delta.copy(discoverAssets = repairedAssets.take(96)),
    )
}

private fun parseApocalypseGranularInventoryRepairV5(raw: String): List<ApocalypseV3Asset> = runCatching {
    val normalized = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = normalized.indexOf('{')
    val end = normalized.lastIndexOf('}')
    if (start < 0 || end <= start) return@runCatching emptyList()
    val json = JSONObject(normalized.substring(start, end + 1))
    val array = json.optJSONArray("acquiredItems") ?: return@runCatching emptyList()
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title").trim().take(80)
            val quantity = item.optInt("quantity", 0).coerceIn(1, 99_999)
            val unit = item.optString("unit").trim().take(16)
            if (title.isBlank() || quantity <= 0 || unit.isBlank()) continue
            val rawTag = item.optString("tag").trim()
            val tag = listOf(rawTag, "单位=$unit")
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("；")
                .take(100)
            add(
                ApocalypseV3Asset(
                    id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() },
                    kind = parseApocalypseStructuredInventoryKindV5(item.optString("kind")),
                    title = title,
                    detail = item.optString("detail").trim().take(420),
                    quantity = quantity,
                    tag = tag,
                ),
            )
        }
    }
        .groupBy { "${it.kind}|${it.title.lowercase().replace(Regex("\\s+"), "")}" }
        .values
        .map { same ->
            val first = same.first()
            first.copy(
                quantity = same.sumOf { it.quantity }.coerceAtMost(99_999),
                detail = same.map { it.detail }.filter(String::isNotBlank).distinct().joinToString("；").take(420),
            )
        }
        .take(96)
}.getOrDefault(emptyList())

private fun parseApocalypseStructuredInventoryKindV5(raw: String): ApocalypseV3AssetKind = when (raw.trim().lowercase()) {
    "food", "食品", "食物" -> ApocalypseV3AssetKind.Food
    "water", "drink", "饮水", "饮料" -> ApocalypseV3AssetKind.Water
    "medicine", "medical", "药品", "医疗" -> ApocalypseV3AssetKind.Medicine
    "material", "materials", "材料" -> ApocalypseV3AssetKind.Material
    "tool", "tools", "item", "工具" -> ApocalypseV3AssetKind.Tool
    "weapon", "combat", "firearm", "ammo", "armor", "战斗", "武器", "枪械", "弹药", "护甲" -> ApocalypseV3AssetKind.Weapon
    "vehicle", "载具" -> ApocalypseV3AssetKind.Vehicle
    "key", "钥匙", "权限" -> ApocalypseV3AssetKind.Key
    "document", "file", "文件" -> ApocalypseV3AssetKind.Document
    "map", "地图" -> ApocalypseV3AssetKind.Map
    "core", "晶核" -> ApocalypseV3AssetKind.Core
    else -> ApocalypseV3AssetKind.Clue
}

private fun apocalypseStructuredInventoryKindNameV5(kind: ApocalypseV3AssetKind): String = when (kind) {
    ApocalypseV3AssetKind.Food -> "food"
    ApocalypseV3AssetKind.Water -> "water"
    ApocalypseV3AssetKind.Medicine -> "medicine"
    ApocalypseV3AssetKind.Material -> "material"
    ApocalypseV3AssetKind.Tool -> "tool"
    ApocalypseV3AssetKind.Weapon -> "weapon"
    ApocalypseV3AssetKind.Vehicle -> "vehicle"
    ApocalypseV3AssetKind.Key -> "key"
    ApocalypseV3AssetKind.Document -> "document"
    ApocalypseV3AssetKind.Clue -> "clue"
    ApocalypseV3AssetKind.Map -> "map"
    ApocalypseV3AssetKind.Core -> "core"
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
            asset.title.contains("薯片") || asset.title.contains("方便面") || asset.title.contains("饼干") -> "包"
            asset.title.contains("巧克力") -> "块"
            asset.title.contains("罐头") -> "罐"
            asset.title.contains("面包") -> "包"
            else -> "份"
        }
        ApocalypseV3AssetKind.Water -> "瓶"
        ApocalypseV3AssetKind.Medicine -> when {
            asset.title.contains("片") -> "片"
            asset.title.contains("胶囊") -> "粒"
            asset.title.contains("绷带") -> "卷"
            else -> "件"
        }
        ApocalypseV3AssetKind.Weapon -> when {
            asset.title.contains("弹") -> "发"
            asset.title.contains("盾") -> "面"
            asset.title.contains("枪") -> "支"
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
