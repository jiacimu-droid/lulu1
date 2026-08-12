package com.jiacimu.lulu.games

import android.content.Context

private const val APOCALYPSE_INVENTORY_MANUAL_PREFS_V5 = "apocalypse_inventory_manual_v5"

/**
 * Legacy compatibility cleanup only.
 *
 * Older builds appended a visible "【旁白】入库清点：..." paragraph and attempted to reconstruct
 * missing inventory by scanning prose. That audit/recovery path is deliberately gone. Inventory now
 * comes only from the writer's structured acquiredItems/discoverAssets receipt.
 *
 * This function therefore never creates, changes, or reclassifies inventory. It only removes the old
 * visible audit paragraph from the newest save when encountered.
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
