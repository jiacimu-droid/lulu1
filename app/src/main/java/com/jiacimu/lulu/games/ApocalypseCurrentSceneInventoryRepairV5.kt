package com.jiacimu.lulu.games

/**
 * Safe repair for the newest already-saved scene. It only restores non-consumable concrete items
 * that the current narration explicitly says were acquired, so opening a newer APK can recover a
 * shield/weapon/tool that was omitted by the old receipt parser without replaying the scene.
 *
 * Consumables are intentionally excluded here because the same scene may also have eaten/drunk some
 * of them. Future scenes use the full reconciliation path, which can account for both acquisition and
 * consumption in one receipt.
 */
internal fun repairApocalypseCurrentSceneInventoryV5(save: ApocalypseV3Save): ApocalypseV3Save {
    if (save.narration.isBlank()) return save
    val recovered = recoverApocalypseNarratedInventoryV5(
        ApocalypseSceneOutcomeV5(text = save.narration),
    ).delta.discoverAssets.filter { asset ->
        asset.kind !in setOf(
            ApocalypseV3AssetKind.Food,
            ApocalypseV3AssetKind.Water,
            ApocalypseV3AssetKind.Medicine,
            ApocalypseV3AssetKind.Core,
            ApocalypseV3AssetKind.Map,
        )
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

private fun apocalypseRepairAssetKeyV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
    .replace("整箱", "")
    .replace("箱装", "")
    .replace("整盒", "")
    .replace("盒装", "")
    .replace("的", "")
    .trim()
