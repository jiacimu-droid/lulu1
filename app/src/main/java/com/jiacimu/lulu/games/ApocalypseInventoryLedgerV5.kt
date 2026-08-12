package com.jiacimu.lulu.games

import java.util.UUID

/**
 * Canonical V5 inventory reconciliation.
 *
 * There is deliberately NO prose scanning here. The writer's hidden acquiredItems/discoverAssets and
 * inventoryChanges receipt is the only stock source. This prevents literary sentences from becoming
 * fake inventory and makes every later consumption an exact change to a concrete item row.
 */
internal fun reconcileApocalypseInventoryOutcomeV5(
    save: ApocalypseV3Save,
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 {
    val additions = mergeApocalypseStructuredAdditionsV5(
        outcome.delta.discoverAssets.map(::normalizeApocalypseStructuredAssetV5),
    ).take(96)

    val changes = mergeApocalypseStructuredChangesV5(
        outcome.inventoryChanges.map { change ->
            change.copy(
                title = change.title.trim().take(80),
                quantityDelta = change.quantityDelta.coerceIn(-99_999, 99_999),
                detail = change.detail.trim().take(420),
                tag = ensureApocalypseStructuredUnitTagV5(
                    change.tag,
                    inferApocalypseStructuredUnitV5(change.kind, change.title),
                ).take(100),
            )
        }.filter { it.quantityDelta != 0 && (it.id.isNotBlank() || it.title.isNotBlank()) },
    ).take(96)

    val concreteDelta = mutableMapOf<ApocalypseV3AssetKind, Int>()
    additions.forEach { asset ->
        concreteDelta[asset.kind] = (concreteDelta[asset.kind] ?: 0) + asset.quantity
    }
    changes.forEach { change ->
        concreteDelta[change.kind] = (concreteDelta[change.kind] ?: 0) + change.quantityDelta
    }

    val fields = outcome.reportedStateFields.toMutableSet()
    if (additions.isNotEmpty()) fields += "discoverAssets"
    if (changes.isNotEmpty()) fields += "inventoryChanges"

    fun concreteOwnsAggregate(
        kind: ApocalypseV3AssetKind,
        deltaField: String,
        afterField: String,
    ): Int? {
        val value = concreteDelta[kind] ?: return null
        fields.remove(afterField)
        fields += deltaField
        return value
    }

    val food = concreteOwnsAggregate(ApocalypseV3AssetKind.Food, "foodDelta", "foodAfter")
    val water = concreteOwnsAggregate(ApocalypseV3AssetKind.Water, "waterDelta", "waterAfter")
    val medicine = concreteOwnsAggregate(ApocalypseV3AssetKind.Medicine, "medicineDelta", "medicineAfter")
    val materials = concreteOwnsAggregate(ApocalypseV3AssetKind.Material, "materialsDelta", "materialsAfter")
    val cores = concreteOwnsAggregate(ApocalypseV3AssetKind.Core, "coresFound", "coresAfter")

    return outcome.copy(
        simulationStateReported = outcome.simulationStateReported || fields.isNotEmpty(),
        reportedStateFields = fields,
        inventoryChanges = changes,
        delta = outcome.delta.copy(
            discoverAssets = additions,
            foodDelta = food ?: outcome.delta.foodDelta,
            waterDelta = water ?: outcome.delta.waterDelta,
            medicineDelta = medicine ?: outcome.delta.medicineDelta,
            materialsDelta = materials ?: outcome.delta.materialsDelta,
            coresFound = cores ?: outcome.delta.coresFound,
            foodAfter = if (food != null) null else outcome.delta.foodAfter,
            waterAfter = if (water != null) null else outcome.delta.waterAfter,
            medicineAfter = if (medicine != null) null else outcome.delta.medicineAfter,
            materialsAfter = if (materials != null) null else outcome.delta.materialsAfter,
            coresAfter = if (cores != null) null else outcome.delta.coresAfter,
        ),
    )
}

/**
 * Rebuild the persistent warehouse from the previous concrete ledger plus this scene's structured
 * receipt. The long-form director may contribute non-consumable clue/document/map/key records, but it
 * cannot silently create food, equipment, ammo or supplies outside acquiredItems.
 */
internal fun preserveApocalypseInventoryLedgerV5(
    save: ApocalypseV3Save,
    outcome: ApocalypseSceneOutcomeV5,
    beat: ApocalypseV3Beat,
): ApocalypseV3Beat {
    val rebuilt = mergeApocalypseStructuredLedgerV5(
        previous = save.director.assets,
        additions = outcome.delta.discoverAssets,
        changes = outcome.inventoryChanges,
    ).toMutableList()

    val directorOnlyKinds = setOf(
        ApocalypseV3AssetKind.Key,
        ApocalypseV3AssetKind.Document,
        ApocalypseV3AssetKind.Clue,
        ApocalypseV3AssetKind.Map,
    )
    beat.nextDirector.assets
        .filter { it.kind in directorOnlyKinds }
        .forEach { candidate ->
            val index = rebuilt.indexOfFirst { existing ->
                existing.id == candidate.id || sameApocalypseStructuredIdentityV5(existing, candidate)
            }
            if (index < 0) {
                rebuilt += normalizeApocalypseStructuredAssetV5(candidate)
            } else {
                val existing = rebuilt[index]
                rebuilt[index] = existing.copy(
                    detail = mergeApocalypseStructuredDetailsV5(existing.detail, candidate.detail),
                    tag = mergeApocalypseStructuredTagsV5(existing.tag, candidate.tag),
                    quantity = maxOf(existing.quantity, candidate.quantity).coerceAtMost(99_999),
                )
            }
        }

    return beat.copy(
        nextDirector = beat.nextDirector.copy(
            assets = rebuilt.takeLast(320),
        ),
    )
}

private fun normalizeApocalypseStructuredAssetV5(asset: ApocalypseV3Asset): ApocalypseV3Asset {
    val title = asset.title.trim().ifBlank { "未命名物资" }.take(80)
    return asset.copy(
        title = title,
        detail = asset.detail.trim().take(420),
        quantity = asset.quantity.coerceIn(1, 99_999),
        tag = ensureApocalypseStructuredUnitTagV5(
            asset.tag.trim(),
            inferApocalypseStructuredUnitV5(asset.kind, title),
        ).take(100),
    )
}

private fun mergeApocalypseStructuredAdditionsV5(
    source: List<ApocalypseV3Asset>,
): List<ApocalypseV3Asset> {
    val merged = mutableListOf<ApocalypseV3Asset>()
    source.forEach { incoming ->
        val index = merged.indexOfFirst { existing -> sameApocalypseStructuredIdentityV5(existing, incoming) }
        if (index < 0) {
            merged += incoming
        } else {
            val existing = merged[index]
            merged[index] = existing.copy(
                quantity = (existing.quantity + incoming.quantity).coerceAtMost(99_999),
                detail = mergeApocalypseStructuredDetailsV5(existing.detail, incoming.detail),
                tag = mergeApocalypseStructuredTagsV5(existing.tag, incoming.tag),
            )
        }
    }
    return merged
}

private fun mergeApocalypseStructuredChangesV5(
    source: List<ApocalypseInventoryChangeV5>,
): List<ApocalypseInventoryChangeV5> {
    val merged = mutableListOf<ApocalypseInventoryChangeV5>()
    source.forEach { incoming ->
        val index = merged.indexOfFirst { existing ->
            (incoming.id.isNotBlank() && existing.id.isNotBlank() && incoming.id == existing.id) ||
                sameApocalypseStructuredIdentityV5(
                    kindA = existing.kind,
                    titleA = existing.title,
                    kindB = incoming.kind,
                    titleB = incoming.title,
                )
        }
        if (index < 0) {
            merged += incoming
        } else {
            val existing = merged[index]
            merged[index] = existing.copy(
                quantityDelta = (existing.quantityDelta + incoming.quantityDelta).coerceIn(-99_999, 99_999),
                detail = mergeApocalypseStructuredDetailsV5(existing.detail, incoming.detail),
                tag = mergeApocalypseStructuredTagsV5(existing.tag, incoming.tag),
            )
        }
    }
    return merged.filter { it.quantityDelta != 0 }
}

private fun mergeApocalypseStructuredLedgerV5(
    previous: List<ApocalypseV3Asset>,
    additions: List<ApocalypseV3Asset>,
    changes: List<ApocalypseInventoryChangeV5>,
): List<ApocalypseV3Asset> {
    val merged = previous.map(::normalizeApocalypseStructuredAssetV5).toMutableList()

    additions.map(::normalizeApocalypseStructuredAssetV5).forEach { addition ->
        val index = merged.indexOfFirst { existing ->
            existing.id == addition.id || sameApocalypseStructuredIdentityV5(existing, addition)
        }
        if (index < 0) {
            merged += addition
        } else {
            val existing = merged[index]
            val replaceOnly = addition.kind in setOf(
                ApocalypseV3AssetKind.Key,
                ApocalypseV3AssetKind.Document,
                ApocalypseV3AssetKind.Clue,
                ApocalypseV3AssetKind.Map,
            )
            merged[index] = existing.copy(
                quantity = if (replaceOnly) {
                    maxOf(existing.quantity, addition.quantity)
                } else {
                    (existing.quantity + addition.quantity).coerceAtMost(99_999)
                },
                detail = mergeApocalypseStructuredDetailsV5(existing.detail, addition.detail),
                tag = mergeApocalypseStructuredTagsV5(existing.tag, addition.tag),
            )
        }
    }

    changes.forEach { change ->
        val index = merged.indexOfFirst { existing ->
            (change.id.isNotBlank() && existing.id == change.id) ||
                sameApocalypseStructuredIdentityV5(
                    kindA = existing.kind,
                    titleA = existing.title,
                    kindB = change.kind,
                    titleB = change.title,
                )
        }
        if (index < 0) {
            if (change.quantityDelta > 0) {
                merged += ApocalypseV3Asset(
                    id = change.id.ifBlank { UUID.randomUUID().toString() },
                    kind = change.kind,
                    title = change.title.ifBlank { "新增${change.kind.label}" }.take(80),
                    detail = change.detail.take(420),
                    quantity = change.quantityDelta.coerceAtMost(99_999),
                    tag = ensureApocalypseStructuredUnitTagV5(
                        change.tag,
                        inferApocalypseStructuredUnitV5(change.kind, change.title),
                    ).take(100),
                )
            }
        } else {
            val existing = merged[index]
            val nextQuantity = existing.quantity + change.quantityDelta
            if (nextQuantity <= 0) {
                merged.removeAt(index)
            } else {
                merged[index] = existing.copy(
                    quantity = nextQuantity.coerceAtMost(99_999),
                    detail = mergeApocalypseStructuredDetailsV5(existing.detail, change.detail),
                    tag = mergeApocalypseStructuredTagsV5(existing.tag, change.tag),
                )
            }
        }
    }

    return merged.takeLast(320)
}

private fun sameApocalypseStructuredIdentityV5(a: ApocalypseV3Asset, b: ApocalypseV3Asset): Boolean =
    sameApocalypseStructuredIdentityV5(a.kind, a.title, b.kind, b.title)

private fun sameApocalypseStructuredIdentityV5(
    kindA: ApocalypseV3AssetKind,
    titleA: String,
    kindB: ApocalypseV3AssetKind,
    titleB: String,
): Boolean {
    if (kindA != kindB) return false
    val a = normalizeApocalypseStructuredTitleV5(titleA)
    val b = normalizeApocalypseStructuredTitleV5(titleB)
    return a.isNotBlank() && a == b
}

private fun normalizeApocalypseStructuredTitleV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
    .trim()

private fun ensureApocalypseStructuredUnitTagV5(tag: String, unit: String): String {
    val clean = tag.trim('；', ';', ' ')
    if (unit.isBlank() || Regex("(?:^|[；;])单位=").containsMatchIn(clean)) return clean
    return listOf(clean, "单位=$unit").filter(String::isNotBlank).joinToString("；")
}

private fun inferApocalypseStructuredUnitV5(kind: ApocalypseV3AssetKind, title: String): String = when (kind) {
    ApocalypseV3AssetKind.Food -> when {
        title.contains("糖") -> "颗"
        title.contains("薯片") || title.contains("方便面") || title.contains("泡面") || title.contains("饼干") -> "包"
        title.contains("巧克力") -> "块"
        title.contains("罐头") || title.contains("罐装") -> "罐"
        title.contains("面包") || title.contains("吐司") -> "包"
        title.contains("盒饭") || title.contains("快餐") -> "盒"
        else -> "份"
    }
    ApocalypseV3AssetKind.Water -> when {
        title.contains("桶装") -> "桶"
        title.contains("罐") -> "罐"
        else -> "瓶"
    }
    ApocalypseV3AssetKind.Medicine -> when {
        title.contains("胶囊") -> "粒"
        title.contains("药片") || title.endsWith("片") -> "片"
        title.contains("绷带") -> "卷"
        title.contains("针") || title.contains("注射") -> "支"
        else -> "件"
    }
    ApocalypseV3AssetKind.Weapon -> when {
        title.contains("子弹") || title.contains("弹药") || title.contains("胶弹") -> "发"
        title.contains("手雷") || title.contains("榴弹") -> "枚"
        title.contains("盾") -> "面"
        title.contains("枪") -> "支"
        title.contains("刀") || title.contains("斧") -> "把"
        else -> "件"
    }
    ApocalypseV3AssetKind.Vehicle -> "辆"
    ApocalypseV3AssetKind.Key -> "枚"
    ApocalypseV3AssetKind.Document, ApocalypseV3AssetKind.Clue, ApocalypseV3AssetKind.Map -> "份"
    ApocalypseV3AssetKind.Core -> "枚"
    else -> "件"
}

private fun mergeApocalypseStructuredTagsV5(first: String, second: String): String =
    (first.split('；', ';') + second.split('；', ';'))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")
        .take(100)

private fun mergeApocalypseStructuredDetailsV5(first: String, second: String): String = when {
    first.isBlank() -> second.take(420)
    second.isBlank() -> first.take(420)
    first.contains(second) -> first.take(420)
    second.contains(first) -> second.take(420)
    else -> "$first；$second".take(420)
}
