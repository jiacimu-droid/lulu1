package com.jiacimu.lulu.games

import android.content.Context

private const val APOCALYPSE_INVENTORY_MANUAL_PREFS_V5 = "apocalypse_inventory_manual_v5"

/**
 * Safe repair for the newest already-saved scene. It only restores non-consumable concrete items
 * that the current narration explicitly says were acquired, so opening a newer APK can recover a
 * shield/weapon/tool that was omitted by the old receipt parser without replaying the scene.
 *
 * Manual deletions are remembered separately. Once the player deletes a bogus recovered item, the
 * current-scene repair must not immediately resurrect the same bad row on the next sheet open.
 */
internal fun repairApocalypseCurrentSceneInventoryV5(
    context: Context,
    save: ApocalypseV3Save,
): ApocalypseV3Save {
    if (save.narration.isBlank()) return save
    val deletedKeys = apocalypseDeletedInventoryKeysV5(context, save.id)
    val recovered = recoverApocalypseNarratedInventoryV5(
        ApocalypseSceneOutcomeV5(text = save.narration),
    ).delta.discoverAssets.filter { asset ->
        asset.kind !in setOf(
            ApocalypseV3AssetKind.Food,
            ApocalypseV3AssetKind.Water,
            ApocalypseV3AssetKind.Medicine,
            ApocalypseV3AssetKind.Core,
            ApocalypseV3AssetKind.Map,
        ) && apocalypseRepairAssetKeyV5(asset.title) !in deletedKeys
    }
    if (recovered.isEmpty()) return save

    val merged = save.director.assets.toMutableList()
    var changed = false
    recovered.forEach { candidate ->
        val exists = merged.any { existing ->
            apocalypseRepairAssetKeyV5(existing.title) == apocalypseRepairAssetKeyV5(candidate.title)
        }
        if (!exists) {
            merged += candidate
            changed = true
        }
    }
    if (!changed) return save
    return save.copy(
        director = save.director.copy(assets = merged.takeLast(280)),
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

private fun apocalypseDeletedInventoryKeysV5(context: Context, saveId: String): Set<String> =
    context.applicationContext
        .getSharedPreferences(APOCALYPSE_INVENTORY_MANUAL_PREFS_V5, Context.MODE_PRIVATE)
        .getStringSet("deleted_$saveId", emptySet())
        ?.toSet()
        .orEmpty()

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
