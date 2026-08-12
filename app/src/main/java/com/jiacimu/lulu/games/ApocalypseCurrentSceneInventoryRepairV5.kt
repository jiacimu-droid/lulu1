package com.jiacimu.lulu.games

import android.content.Context

private const val APOCALYPSE_INVENTORY_MANUAL_PREFS_V5 = "apocalypse_inventory_manual_v5"

/**
 * Legacy compatibility cleanup only.
 *
 * Older builds appended a visible "【旁白】入库清点：..." paragraph and attempted to reconstruct
 * missing inventory by scanning prose. That audit/recovery path is deliberately gone. Inventory now
 * comes only from the writer's structured acquiredItems/discoverAssets receipt.
 */
internal fun repairApocalypseCurrentSceneInventoryV5(
    context: Context,
    save: ApocalypseV3Save,
): ApocalypseV3Save {
    val cleanedNarration = stripLegacyApocalypseInventoryAuditV5(save.narration)
    if (cleanedNarration == save.narration) return save
    return save.copy(
        narration = cleanedNarration,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * One-time migration for old concrete rows that were saved as parent packages such as
 * "零食组合箱×6" or "速冻早点组合箱×8".
 *
 * This is NOT prose auditing: it reads only the already persisted asset title/quantity/detail/tag,
 * asks the same hidden structured-ledger repairer to expand them into concrete SKUs, removes the
 * parent rows, and keeps every unrelated inventory row untouched.
 */
internal suspend fun migrateApocalypseBundledInventoryV5(
    save: ApocalypseV3Save,
): ApocalypseV3Save {
    val bundled = save.director.assets.filter(::apocalypseInventoryAssetNeedsGranularRepairV5)
    if (bundled.isEmpty()) return save

    val fakeOutcome = ApocalypseSceneOutcomeV5(
        text = "",
        actionOutcome = "仅拆分已有库存父包装，不改变剧情。",
        receiptParsed = true,
        simulationStateReported = true,
        reportedStateFields = setOf("acquiredItems", "discoverAssets"),
        delta = ApocalypseSceneDeltaV5(discoverAssets = bundled),
    )
    val repaired = recoverApocalypseNarratedInventoryV5(fakeOutcome).delta.discoverAssets
    if (repaired.isEmpty() || repaired.any(::apocalypseInventoryAssetNeedsGranularRepairV5)) return save

    val bundledIds = bundled.mapTo(mutableSetOf()) { it.id }
    val nextAssets = save.director.assets.filterNot { it.id in bundledIds }.toMutableList()
    repaired.forEach { child ->
        val index = nextAssets.indexOfFirst { existing ->
            existing.kind == child.kind &&
                normalizeApocalypseMigrationTitleV5(existing.title) == normalizeApocalypseMigrationTitleV5(child.title)
        }
        if (index < 0) {
            nextAssets += child
        } else {
            val existing = nextAssets[index]
            nextAssets[index] = existing.copy(
                quantity = (existing.quantity + child.quantity).coerceAtMost(99_999),
                detail = mergeApocalypseMigrationDetailV5(existing.detail, child.detail),
                tag = mergeApocalypseMigrationTagV5(existing.tag, child.tag),
            )
        }
    }

    fun sumKind(source: List<ApocalypseV3Asset>, kind: ApocalypseV3AssetKind): Int =
        source.filter { it.kind == kind }.sumOf { it.quantity }

    val foodDelta = sumKind(repaired, ApocalypseV3AssetKind.Food) - sumKind(bundled, ApocalypseV3AssetKind.Food)
    val waterDelta = sumKind(repaired, ApocalypseV3AssetKind.Water) - sumKind(bundled, ApocalypseV3AssetKind.Water)
    val medicineDelta = sumKind(repaired, ApocalypseV3AssetKind.Medicine) - sumKind(bundled, ApocalypseV3AssetKind.Medicine)
    val materialsDelta = sumKind(repaired, ApocalypseV3AssetKind.Material) - sumKind(bundled, ApocalypseV3AssetKind.Material)
    val coreDelta = sumKind(repaired, ApocalypseV3AssetKind.Core) - sumKind(bundled, ApocalypseV3AssetKind.Core)

    val nextStats = save.stats.copy(
        food = (save.stats.food + foodDelta).coerceIn(0, 99_999),
        water = (save.stats.water + waterDelta).coerceIn(0, 99_999),
        medicine = (save.stats.medicine + medicineDelta).coerceIn(0, 99_999),
        materials = (save.stats.materials + materialsDelta).coerceIn(0, 99_999),
        crystalCores = (save.stats.crystalCores + coreDelta).coerceIn(0, 99_999),
    )

    return save.copy(
        director = save.director.copy(assets = nextAssets.takeLast(320)),
        stats = nextStats,
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * Manual inventory deletion is data correction, not an in-story action. Remove the exact row and
 * adjust only aggregate counters whose persisted kind really owns a counter. Misclassified Tool/
 * Clue rows therefore cannot accidentally subtract food just because their title happens to be food.
 */
internal fun deleteApocalypseInventoryAssetV5(
    context: Context,
    save: ApocalypseV3Save,
    asset: ApocalypseV3Asset,
): ApocalypseV3Save {
    val nextAssets = save.director.assets.filterNot { it.id == asset.id }
    if (nextAssets.size == save.director.assets.size) return save

    val quantity = asset.quantity.coerceAtLeast(0)
    val nextStats = when (asset.kind) {
        ApocalypseV3AssetKind.Food -> save.stats.copy(food = (save.stats.food - quantity).coerceAtLeast(0))
        ApocalypseV3AssetKind.Water -> save.stats.copy(water = (save.stats.water - quantity).coerceAtLeast(0))
        ApocalypseV3AssetKind.Medicine -> save.stats.copy(medicine = (save.stats.medicine - quantity).coerceAtLeast(0))
        ApocalypseV3AssetKind.Material -> save.stats.copy(materials = (save.stats.materials - quantity).coerceAtLeast(0))
        ApocalypseV3AssetKind.Core -> save.stats.copy(crystalCores = (save.stats.crystalCores - quantity).coerceAtLeast(0))
        else -> save.stats
    }

    apocalypseRememberDeletedInventoryKeyV5(context, save.id, apocalypseRepairAssetKeyV5(asset.title))
    return save.copy(
        director = save.director.copy(assets = nextAssets),
        stats = nextStats,
        updatedAt = System.currentTimeMillis(),
    )
}

private fun normalizeApocalypseMigrationTitleV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
    .trim()

private fun mergeApocalypseMigrationDetailV5(first: String, second: String): String = when {
    first.isBlank() -> second.take(420)
    second.isBlank() -> first.take(420)
    first.contains(second) -> first.take(420)
    second.contains(first) -> second.take(420)
    else -> "$first；$second".take(420)
}

private fun mergeApocalypseMigrationTagV5(first: String, second: String): String =
    (first.split('；', ';') + second.split('；', ';'))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")
        .take(100)

private fun apocalypseRememberDeletedInventoryKeyV5(context: Context, saveId: String, key: String) {
    if (saveId.isBlank() || key.isBlank()) return
    val prefs = context.applicationContext
        .getSharedPreferences(APOCALYPSE_INVENTORY_MANUAL_PREFS_V5, Context.MODE_PRIVATE)
    val next = prefs.getStringSet("deleted_$saveId", emptySet()).orEmpty().toMutableSet().apply { add(key) }
    prefs.edit().putStringSet("deleted_$saveId", next).apply()
}

private fun apocalypseRepairAssetKeyV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
    .replace("整箱", "")
    .replace("箱装", "")
    .replace("整盒", "")
    .replace("盒装", "")
    .replace("的", "")
    .trim()
