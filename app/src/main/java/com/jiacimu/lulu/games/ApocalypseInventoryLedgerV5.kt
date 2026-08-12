package com.jiacimu.lulu.games

import java.util.UUID

/**
 * Reconciles the story receipt with the concrete warehouse ledger without another model call.
 *
 * Rules:
 * - concrete named items win over rough aggregate counters;
 * - completed bulk purchases are converted to consumable units;
 * - merely intending to buy something never creates stock;
 * - explicitly narrated consumption of an existing named item can fill a missing negative change;
 * - long-running saves retain a larger concrete item ledger instead of silently forgetting rows.
 */
internal fun reconcileApocalypseInventoryOutcomeV5(
    save: ApocalypseV3Save,
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 {
    val visibleResult = listOf(outcome.actionOutcome, outcome.text)
        .filter(String::isNotBlank)
        .joinToString("\n")

    val normalizedReported = outcome.delta.discoverAssets.map(::normalizeApocalypseBulkAssetV5)
    val recovered = recoverApocalypseCompletedAcquisitionsV5(visibleResult)
    val additions = mergeApocalypseSceneAdditionsV5(normalizedReported, recovered).take(40)

    val reportedChanges = outcome.inventoryChanges
        .map { change ->
            change.copy(
                title = cleanApocalypseInventoryTitleV5(change.title).ifBlank { change.title },
                quantityDelta = change.quantityDelta.coerceIn(-99_999, 99_999),
                tag = ensureApocalypseUnitTagV5(
                    change.tag,
                    inferApocalypseInventoryUnitV5(change.kind, change.title),
                ),
            )
        }
        // Some models emit the same acquisition in both discoverAssets and inventoryChanges.
        // Acquisition belongs to discoverAssets; positive duplicate changes would double count it.
        .filterNot { change ->
            change.quantityDelta > 0 && additions.any { addition ->
                sameApocalypseInventoryIdentityV5(
                    addition.kind,
                    addition.title,
                    change.kind,
                    change.title,
                )
            }
        }

    val recoveredConsumption = recoverApocalypseNamedConsumptionV5(
        visibleText = outcome.text,
        existing = save.director.assets,
        alreadyReported = reportedChanges,
    )
    val changes = mergeApocalypseInventoryChangesV5(reportedChanges + recoveredConsumption).take(40)

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

    fun ownConcreteDelta(
        kind: ApocalypseV3AssetKind,
        deltaField: String,
        afterField: String,
    ): Int? {
        val value = concreteDelta[kind] ?: return null
        // A concrete item ledger is more precise than a model saying “water +10” after buying
        // ten cases. Remove the rough After field so applyApocalypseSceneOutcomeV5 must use the
        // concrete item-derived delta.
        fields.remove(afterField)
        fields += deltaField
        return value
    }

    val food = ownConcreteDelta(ApocalypseV3AssetKind.Food, "foodDelta", "foodAfter")
    val water = ownConcreteDelta(ApocalypseV3AssetKind.Water, "waterDelta", "waterAfter")
    val medicine = ownConcreteDelta(ApocalypseV3AssetKind.Medicine, "medicineDelta", "medicineAfter")
    val materials = ownConcreteDelta(ApocalypseV3AssetKind.Material, "materialsDelta", "materialsAfter")
    val cores = ownConcreteDelta(ApocalypseV3AssetKind.Core, "coresFound", "coresAfter")

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
 * The lower V5 merge still has a legacy 90-row retention window. Rebuild the current scene's
 * inventory from the full previous ledger plus this scene's concrete changes, then retain 240 rows.
 */
internal fun preserveApocalypseInventoryLedgerV5(
    save: ApocalypseV3Save,
    outcome: ApocalypseSceneOutcomeV5,
    beat: ApocalypseV3Beat,
): ApocalypseV3Beat {
    val rebuilt = mergeApocalypseInventoryLedgerV5(
        previous = save.director.assets,
        additions = outcome.delta.discoverAssets,
        changes = outcome.inventoryChanges,
    ).toMutableList()

    // If the director produced a legitimate special asset that is not represented by the writer
    // receipt, preserve it without adding its quantity twice.
    beat.nextDirector.assets.forEach { candidate ->
        val index = rebuilt.indexOfFirst { existing ->
            existing.id == candidate.id ||
                sameApocalypseInventoryIdentityV5(
                    existing.kind,
                    existing.title,
                    candidate.kind,
                    candidate.title,
                )
        }
        if (index < 0) {
            rebuilt += candidate
        } else {
            val existing = rebuilt[index]
            rebuilt[index] = existing.copy(
                detail = candidate.detail.ifBlank { existing.detail },
                tag = mergeApocalypseInventoryTagsV5(existing.tag, candidate.tag),
            )
        }
    }

    return beat.copy(
        nextDirector = beat.nextDirector.copy(
            assets = rebuilt.takeLast(240),
        ),
    )
}

private data class ApocalypseRecoveredPackageV5(
    val quantity: Int,
    val unit: String,
    val packageNote: String = "",
)

private val apocalypseAcquisitionWordsV5 = listOf(
    "买", "购买", "买下", "购入", "采购", "结账", "付款", "拿到", "拿下", "收下",
    "带走", "搬走", "搬进", "装进", "塞进", "收入空间", "收进空间", "囤下",
)
private val apocalypseAcquisitionNegationsV5 = listOf(
    "没买", "没有买", "未买", "买不到", "没拿到", "未拿到", "缺货", "暂不买", "不买",
    "想买", "打算买", "准备买", "问价", "询价", "看看价格",
)
private val apocalypseConsumptionWordsV5 = listOf(
    "吃了", "吃掉", "吃下", "喝了", "喝掉", "饮用", "服用", "用了", "用掉", "消耗",
    "拆开吃", "开了一罐", "开了一瓶",
)
private val apocalypseConsumptionNegationsV5 = listOf(
    "没吃", "没有吃", "不吃", "没喝", "没有喝", "不喝", "没用", "没有用", "不用",
    "舍不得吃", "暂时不吃", "只是看看", "拿出来看看",
)

private val apocalypseQuantityItemRegexV5 = Regex(
    """(\d{1,5}|[一二两三四五六七八九十]{1,3})\s*(箱|盒|包|袋|瓶|罐|把|根|卷|件|套|桶|提|板|支|个|份|条|双|枚|片|粒)\s*([\p{L}\p{N}·（）()_-]{1,24})""",
)

private fun recoverApocalypseCompletedAcquisitionsV5(text: String): List<ApocalypseV3Asset> {
    if (text.isBlank()) return emptyList()
    return buildList {
        apocalypseQuantityItemRegexV5.findAll(text).forEach { match ->
            if (!hasApocalypseAcquisitionContextV5(text, match.range.first)) return@forEach
            val count = parseApocalypseSmallNumberV5(match.groupValues[1]) ?: return@forEach
            val outerUnit = match.groupValues[2]
            val title = cleanApocalypseInventoryTitleV5(match.groupValues[3])
            if (title.length < 2) return@forEach
            val kind = inferApocalypseInventoryKindV5(title) ?: return@forEach
            val windowStart = (match.range.first - 70).coerceAtLeast(0)
            val windowEnd = (match.range.last + 90).coerceAtMost(text.length)
            val packageInfo = resolveApocalypsePackageV5(
                count = count,
                outerUnit = outerUnit,
                title = title,
                kind = kind,
                window = text.substring(windowStart, windowEnd),
            )
            add(
                ApocalypseV3Asset(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    title = title,
                    detail = buildString {
                        append("本幕已实际取得")
                        if (packageInfo.packageNote.isNotBlank()) append("；${packageInfo.packageNote}")
                    },
                    quantity = packageInfo.quantity.coerceIn(1, 99_999),
                    tag = ensureApocalypseUnitTagV5(
                        if (isApocalypseHouseholdTitleV5(title)) "生活用品" else "",
                        packageInfo.unit,
                    ),
                ),
            )
        }
    }
}

private fun hasApocalypseAcquisitionContextV5(text: String, index: Int): Boolean {
    val prefix = text.substring((index - 110).coerceAtLeast(0), index)
    val clauseStart = listOf(
        prefix.lastIndexOf('。'),
        prefix.lastIndexOf('！'),
        prefix.lastIndexOf('？'),
        prefix.lastIndexOf('\n'),
    ).maxOrNull() ?: -1
    val clause = prefix.substring((clauseStart + 1).coerceAtLeast(0))
    if (apocalypseAcquisitionNegationsV5.any(clause::contains)) return false
    return apocalypseAcquisitionWordsV5.any(clause::contains)
}

private fun normalizeApocalypseBulkAssetV5(asset: ApocalypseV3Asset): ApocalypseV3Asset {
    // Only parse package mathematics from this asset's own structured metadata. The full scene may
    // contain several unrelated cases; applying the first “10箱×12瓶” found in the scene to every
    // asset would corrupt the warehouse.
    val source = "${asset.title} ${asset.detail} ${asset.tag}"
    val explicit = extractApocalypseExplicitPackageV5(source)
    val unit = explicit?.unit ?: inferApocalypseInventoryUnitV5(asset.kind, asset.title)
    val quantity = when {
        explicit != null && explicit.quantity > asset.quantity -> explicit.quantity
        else -> asset.quantity.coerceIn(1, 99_999)
    }
    val packageNote = explicit?.packageNote.orEmpty()
    return asset.copy(
        title = cleanApocalypseInventoryTitleV5(asset.title).ifBlank { asset.title },
        quantity = quantity,
        detail = when {
            packageNote.isBlank() -> asset.detail
            asset.detail.contains(packageNote) -> asset.detail
            asset.detail.isBlank() -> packageNote
            else -> "${asset.detail}；$packageNote"
        }.take(360),
        tag = ensureApocalypseUnitTagV5(
            if (isApocalypseHouseholdTitleV5(asset.title) && !asset.tag.contains("生活用品")) {
                listOf(asset.tag, "生活用品").filter(String::isNotBlank).joinToString("；")
            } else {
                asset.tag
            },
            unit,
        ).take(80),
    )
}

private fun extractApocalypseExplicitPackageV5(source: String): ApocalypseRecoveredPackageV5? {
    val direct = Regex(
        """(\d{1,5})\s*箱[^。；，,\n]{0,28}?(?:每箱\s*)?(\d{1,5})\s*(瓶|罐|包|盒|袋|支|个|件|卷|桶|份|片|粒)""",
    ).find(source)
    if (direct != null) {
        val boxes = direct.groupValues[1].toIntOrNull() ?: return null
        val each = direct.groupValues[2].toIntOrNull() ?: return null
        val unit = direct.groupValues[3]
        return ApocalypseRecoveredPackageV5(
            quantity = (boxes * each).coerceAtMost(99_999),
            unit = unit,
            packageNote = "${boxes}箱×${each}${unit}/箱=${boxes * each}${unit}",
        )
    }

    val perBox = Regex(
        """每箱\s*(\d{1,5})\s*(瓶|罐|包|盒|袋|支|个|件|卷|桶|份|片|粒)""",
    ).find(source)
    val boxCount = Regex("""(\d{1,5})\s*箱""").find(source)
    if (perBox != null && boxCount != null) {
        val boxes = boxCount.groupValues[1].toIntOrNull() ?: return null
        val each = perBox.groupValues[1].toIntOrNull() ?: return null
        val unit = perBox.groupValues[2]
        return ApocalypseRecoveredPackageV5(
            quantity = (boxes * each).coerceAtMost(99_999),
            unit = unit,
            packageNote = "${boxes}箱×${each}${unit}/箱=${boxes * each}${unit}",
        )
    }
    return null
}

private fun resolveApocalypsePackageV5(
    count: Int,
    outerUnit: String,
    title: String,
    kind: ApocalypseV3AssetKind,
    window: String,
): ApocalypseRecoveredPackageV5 {
    if (outerUnit != "箱" && outerUnit != "提") {
        return ApocalypseRecoveredPackageV5(count, outerUnit)
    }

    val explicit = extractApocalypseExplicitPackageV5("$count$outerUnit$title $window")
    if (explicit != null) return explicit

    val default = defaultApocalypsePackageSizeV5(kind, title)
    if (default != null) {
        val (each, unit) = default
        return ApocalypseRecoveredPackageV5(
            quantity = (count * each).coerceAtMost(99_999),
            unit = unit,
            packageNote = "按本作标准包装折算：${count}${outerUnit}×${each}${unit}/${outerUnit}=${count * each}${unit}",
        )
    }
    return ApocalypseRecoveredPackageV5(count, outerUnit)
}

private fun defaultApocalypsePackageSizeV5(
    kind: ApocalypseV3AssetKind,
    title: String,
): Pair<Int, String>? = when (kind) {
    ApocalypseV3AssetKind.Water -> when {
        title.contains("桶装") -> 1 to "桶"
        else -> 12 to "瓶"
    }
    ApocalypseV3AssetKind.Food -> when {
        title.contains("罐头") || title.contains("罐装") -> 12 to "罐"
        title.contains("方便面") || title.contains("泡面") || title.contains("面饼") -> 12 to "包"
        title.contains("饼干") || title.contains("能量棒") || title.contains("压缩粮") -> 12 to "包"
        title.contains("盒饭") || title.contains("快餐") -> 12 to "盒"
        else -> null
    }
    else -> null
}

private fun recoverApocalypseNamedConsumptionV5(
    visibleText: String,
    existing: List<ApocalypseV3Asset>,
    alreadyReported: List<ApocalypseInventoryChangeV5>,
): List<ApocalypseInventoryChangeV5> {
    if (visibleText.isBlank()) return emptyList()
    return buildList {
        existing
            .filter {
                it.quantity > 0 &&
                    it.kind in setOf(
                        ApocalypseV3AssetKind.Food,
                        ApocalypseV3AssetKind.Water,
                        ApocalypseV3AssetKind.Medicine,
                    )
            }
            .forEach { asset ->
                if (alreadyReported.any { change ->
                        change.quantityDelta < 0 && (
                            (change.id.isNotBlank() && change.id == asset.id) ||
                                sameApocalypseInventoryIdentityV5(
                                    change.kind,
                                    change.title,
                                    asset.kind,
                                    asset.title,
                                )
                            )
                    }
                ) return@forEach

                val names = apocalypseInventorySearchNamesV5(asset.title)
                val matchedRanges = mutableSetOf<Int>()
                var consumed = 0
                names.forEach { name ->
                    var from = 0
                    while (from < visibleText.length) {
                        val index = visibleText.indexOf(name, startIndex = from, ignoreCase = true)
                        if (index < 0) break
                        if (matchedRanges.add(index)) {
                            val start = (index - 28).coerceAtLeast(0)
                            val end = (index + name.length + 34).coerceAtMost(visibleText.length)
                            val window = visibleText.substring(start, end)
                            val hasConsume = apocalypseConsumptionWordsV5.any(window::contains)
                            val negated = apocalypseConsumptionNegationsV5.any(window::contains)
                            if (hasConsume && !negated) {
                                consumed += inferApocalypseConsumptionCountV5(
                                    text = visibleText,
                                    titleIndex = index,
                                    titleLength = name.length,
                                )
                            }
                        }
                        from = index + name.length
                    }
                }
                val amount = consumed.coerceIn(0, asset.quantity)
                if (amount > 0) {
                    add(
                        ApocalypseInventoryChangeV5(
                            id = asset.id,
                            kind = asset.kind,
                            title = asset.title,
                            quantityDelta = -amount,
                            detail = "根据本幕正文明确食用/饮用/使用补记",
                            tag = asset.tag,
                        ),
                    )
                }
            }
    }
}

private fun inferApocalypseConsumptionCountV5(
    text: String,
    titleIndex: Int,
    titleLength: Int,
): Int {
    val before = text.substring((titleIndex - 18).coerceAtLeast(0), titleIndex)
    val after = text.substring(
        (titleIndex + titleLength).coerceAtMost(text.length),
        (titleIndex + titleLength + 18).coerceAtMost(text.length),
    )
    val numberBefore = Regex(
        """(\d{1,4}|[一二两三四五六七八九十]{1,3})\s*(?:瓶|罐|包|盒|袋|份|支|片|粒|个)?\s*$""",
    )
    numberBefore.find(before)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::parseApocalypseSmallNumberV5)
        ?.let { return it.coerceAtLeast(1) }

    val numberAfter = Regex(
        """^[^。；，,！？?]{0,8}?(\d{1,4}|[一二两三四五六七八九十]{1,3})\s*(?:瓶|罐|包|盒|袋|份|支|片|粒|个)""",
    )
    numberAfter.find(after)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::parseApocalypseSmallNumberV5)
        ?.let { return it.coerceAtLeast(1) }

    return 1
}

private fun mergeApocalypseSceneAdditionsV5(
    reported: List<ApocalypseV3Asset>,
    recovered: List<ApocalypseV3Asset>,
): List<ApocalypseV3Asset> {
    val merged = reported.toMutableList()
    recovered.forEach { item ->
        val index = merged.indexOfFirst { existing ->
            sameApocalypseInventoryIdentityV5(
                existing.kind,
                existing.title,
                item.kind,
                item.title,
            )
        }
        if (index < 0) {
            merged += item
        } else {
            val existing = merged[index]
            merged[index] = existing.copy(
                // Same event recovered from prose and receipt must not be added twice. Prefer the
                // more specific/larger package expansion.
                quantity = maxOf(existing.quantity, item.quantity).coerceAtMost(99_999),
                detail = mergeApocalypseInventoryDetailsV5(existing.detail, item.detail),
                tag = mergeApocalypseInventoryTagsV5(existing.tag, item.tag),
            )
        }
    }
    return merged
}

private fun mergeApocalypseInventoryChangesV5(
    source: List<ApocalypseInventoryChangeV5>,
): List<ApocalypseInventoryChangeV5> {
    val merged = mutableListOf<ApocalypseInventoryChangeV5>()
    source.forEach { incoming ->
        val index = merged.indexOfFirst { existing ->
            (incoming.id.isNotBlank() && existing.id == incoming.id) ||
                sameApocalypseInventoryIdentityV5(
                    existing.kind,
                    existing.title,
                    incoming.kind,
                    incoming.title,
                )
        }
        if (index < 0) {
            merged += incoming
        } else {
            val existing = merged[index]
            // If the same omission was independently recovered from the prose, do not double it.
            // Different explicitly reported changes are uncommon within one scene and the writer is
            // already expected to aggregate them.
            merged[index] = existing.copy(
                quantityDelta = if (existing.quantityDelta == incoming.quantityDelta) {
                    existing.quantityDelta
                } else {
                    existing.quantityDelta + incoming.quantityDelta
                }.coerceIn(-99_999, 99_999),
                detail = mergeApocalypseInventoryDetailsV5(existing.detail, incoming.detail),
                tag = mergeApocalypseInventoryTagsV5(existing.tag, incoming.tag),
            )
        }
    }
    return merged
}

private fun mergeApocalypseInventoryLedgerV5(
    previous: List<ApocalypseV3Asset>,
    additions: List<ApocalypseV3Asset>,
    changes: List<ApocalypseInventoryChangeV5>,
): List<ApocalypseV3Asset> {
    val merged = previous.toMutableList()

    additions.forEach { addition ->
        val index = merged.indexOfFirst { existing ->
            existing.id == addition.id ||
                sameApocalypseInventoryIdentityV5(
                    existing.kind,
                    existing.title,
                    addition.kind,
                    addition.title,
                )
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
                detail = mergeApocalypseInventoryDetailsV5(existing.detail, addition.detail),
                quantity = if (replaceOnly) {
                    maxOf(existing.quantity, addition.quantity)
                } else {
                    (existing.quantity + addition.quantity).coerceAtMost(99_999)
                },
                tag = mergeApocalypseInventoryTagsV5(existing.tag, addition.tag),
            )
        }
    }

    changes.forEach { change ->
        val index = merged.indexOfFirst { existing ->
            (change.id.isNotBlank() && existing.id == change.id) ||
                sameApocalypseInventoryIdentityV5(
                    existing.kind,
                    existing.title,
                    change.kind,
                    change.title,
                )
        }
        if (index < 0) {
            if (change.quantityDelta > 0) {
                merged += ApocalypseV3Asset(
                    id = change.id.ifBlank { UUID.randomUUID().toString() },
                    kind = change.kind,
                    title = change.title.ifBlank { "新增${change.kind.label}" },
                    detail = change.detail,
                    quantity = change.quantityDelta.coerceAtMost(99_999),
                    tag = change.tag,
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
                    detail = mergeApocalypseInventoryDetailsV5(existing.detail, change.detail),
                    tag = mergeApocalypseInventoryTagsV5(existing.tag, change.tag),
                )
            }
        }
    }

    return merged.takeLast(240)
}

private fun sameApocalypseInventoryIdentityV5(
    firstKind: ApocalypseV3AssetKind,
    firstTitle: String,
    secondKind: ApocalypseV3AssetKind,
    secondTitle: String,
): Boolean {
    if (firstKind != secondKind) return false
    val first = normalizeApocalypseInventoryTitleKeyV5(firstTitle)
    val second = normalizeApocalypseInventoryTitleKeyV5(secondTitle)
    if (first.isBlank() || second.isBlank()) return false
    if (first == second) return true
    if (minOf(first.length, second.length) >= 3 && (first.contains(second) || second.contains(first))) return true

    val anchors = when (firstKind) {
        ApocalypseV3AssetKind.Water -> listOf("矿泉水", "纯净水", "饮用水", "瓶装水")
        ApocalypseV3AssetKind.Food -> listOf("方便面", "泡面", "罐头", "饼干", "压缩粮", "快餐")
        else -> emptyList()
    }
    return anchors.any { anchor -> first.contains(anchor) && second.contains(anchor) }
}

private fun cleanApocalypseInventoryTitleV5(raw: String): String {
    var value = raw.trim().trim('，', ',', '。', '；', ';', '、', '：', ':', '！', '!', '？', '?')
    val stops = listOf(
        "以及", "并且", "然后", "随后", "接着", "放进", "装进", "塞进", "收入", "收进",
        "带走", "搬走", "结账", "付款", "一共", "共计", "总共", "直接放", "直接装", "和",
    )
    stops.mapNotNull { stop -> value.indexOf(stop).takeIf { it > 0 } }
        .minOrNull()
        ?.let { value = value.substring(0, it) }
    return value.trim().take(48)
}

private fun normalizeApocalypseInventoryTitleKeyV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
    .replace("整箱", "")
    .replace("箱装", "")
    .replace("盒装", "")
    .replace("袋装", "")
    .replace("瓶装", "")
    .replace("罐装", "")
    .replace("即食", "")
    .trim()

private fun apocalypseInventorySearchNamesV5(title: String): List<String> {
    val clean = cleanApocalypseInventoryTitleV5(title)
    val stripped = clean
        .replace("瓶装", "")
        .replace("罐装", "")
        .replace("即食", "")
        .replace("速冻", "")
        .trim()
    return listOf(clean, stripped).filter { it.length >= 2 }.distinct()
}

private fun inferApocalypseInventoryKindV5(title: String): ApocalypseV3AssetKind? {
    val value = title.lowercase()
    fun has(vararg words: String) = words.any(value::contains)
    return when {
        has("矿泉水", "纯净水", "饮用水", "瓶装水", "桶装水", "饮料", "果汁", "汽水") -> ApocalypseV3AssetKind.Water
        has("药", "绷带", "碘伏", "酒精", "消毒", "创可贴", "止血", "纱布", "退烧", "抗生素") -> ApocalypseV3AssetKind.Medicine
        has("罐头", "方便面", "泡面", "饼干", "压缩粮", "压缩饼干", "米", "面粉", "面包", "火腿", "香肠", "水饺", "馒头", "快餐", "点心", "巧克力", "能量棒", "肉干") -> ApocalypseV3AssetKind.Food
        has("砍刀", "开山刀", "折叠刀", "匕首", "斧头", "长刀", "弓", "弩", "枪", "弹药", "子弹", "防身棍", "甩棍", "撬棍") -> ApocalypseV3AssetKind.Weapon
        has("汽车", "面包车", "货车", "摩托", "电动车", "自行车") -> ApocalypseV3AssetKind.Vehicle
        isApocalypseHouseholdTitleV5(value) -> ApocalypseV3AssetKind.Tool
        has("木板", "钢板", "钢管", "铁丝", "铁钉", "钉子", "螺丝", "水泥", "砂浆", "电线", "线缆", "胶合板", "角钢", "型钢", "玻璃", "建材") -> ApocalypseV3AssetKind.Material
        has("锤", "扳手", "螺丝刀", "钳", "电钻", "手电钻", "锯", "工具箱", "绳", "梯子", "铁锹", "铲", "镐", "胶带", "测电笔") -> ApocalypseV3AssetKind.Tool
        has("晶核") -> ApocalypseV3AssetKind.Core
        else -> null
    }
}

private fun isApocalypseHouseholdTitleV5(title: String): Boolean {
    val value = title.lowercase()
    return listOf(
        "卫生纸", "厕纸", "卷纸", "抽纸", "纸巾", "湿巾", "卫生巾", "牙刷", "牙膏", "牙线", "漱口",
        "洗面奶", "洗发", "护发", "沐浴", "香皂", "肥皂", "洗衣液", "洗衣粉", "洗洁精", "清洁剂", "抹布",
        "百洁布", "海绵", "拖把", "扫帚", "毛巾", "浴巾", "脸盆", "衣架", "垃圾袋", "保鲜膜", "收纳",
        "床单", "被套", "被子", "毯子", "枕头", "枕套", "睡袋", "内衣", "内裤", "袜子", "衣物", "外套",
        "裤子", "鞋子", "拖鞋", "雨衣", "雨伞", "餐具", "筷子", "勺子", "叉子", "杯子", "饭盒", "餐盒",
        "梳子", "镜子", "剃须", "指甲剪", "棉签", "暖宝宝", "蚊香", "驱蚊",
    ).any(value::contains)
}

private fun inferApocalypseInventoryUnitV5(
    kind: ApocalypseV3AssetKind,
    title: String,
): String = when (kind) {
    ApocalypseV3AssetKind.Water -> if (title.contains("桶装")) "桶" else "瓶"
    ApocalypseV3AssetKind.Food -> when {
        title.contains("罐头") || title.contains("罐装") -> "罐"
        title.contains("方便面") || title.contains("泡面") || title.contains("饼干") || title.contains("能量棒") -> "包"
        title.contains("盒") || title.contains("快餐") -> "盒"
        else -> "份"
    }
    ApocalypseV3AssetKind.Medicine -> when {
        title.contains("片") -> "片"
        title.contains("胶囊") -> "粒"
        title.contains("绷带") -> "卷"
        else -> "件"
    }
    ApocalypseV3AssetKind.Weapon -> "件"
    ApocalypseV3AssetKind.Vehicle -> "辆"
    ApocalypseV3AssetKind.Key -> "枚"
    ApocalypseV3AssetKind.Document,
    ApocalypseV3AssetKind.Clue,
    ApocalypseV3AssetKind.Map -> "份"
    ApocalypseV3AssetKind.Core -> "枚"
    else -> "件"
}

private fun ensureApocalypseUnitTagV5(tag: String, unit: String): String {
    if (unit.isBlank() || tag.contains("单位=")) return tag
    return listOf(tag.trim('；', ';', ' '), "单位=$unit")
        .filter(String::isNotBlank)
        .joinToString("；")
}

private fun mergeApocalypseInventoryTagsV5(first: String, second: String): String =
    (first.split('；', ';') + second.split('；', ';'))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")
        .take(80)

private fun mergeApocalypseInventoryDetailsV5(first: String, second: String): String = when {
    first.isBlank() -> second.take(360)
    second.isBlank() -> first.take(360)
    first.contains(second) -> first.take(360)
    second.contains(first) -> second.take(360)
    else -> "$first；$second".take(360)
}

private fun parseApocalypseSmallNumberV5(raw: String): Int? {
    raw.toIntOrNull()?.let { return it }
    val digits = mapOf(
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
    )
    if (raw == "十") return 10
    if ('十' in raw) {
        val parts = raw.split('十', limit = 2)
        val tens = parts.firstOrNull()?.firstOrNull()?.let(digits::get) ?: 1
        val ones = parts.getOrNull(1)?.firstOrNull()?.let(digits::get) ?: 0
        return tens * 10 + ones
    }
    return raw.firstOrNull()?.let(digits::get)
}
