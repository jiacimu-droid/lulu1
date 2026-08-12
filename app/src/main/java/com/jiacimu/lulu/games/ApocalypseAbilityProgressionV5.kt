package com.jiacimu.lulu.games

import android.content.Context

/**
 * Hard rules for the player's fixed space ability and the crystal-core economy.
 *
 * `playerAbilityXp` is a legacy field name. In V5 it stores stable resonance points earned by
 * deliberately absorbing usable crystal cores; it is not generic combat/training XP.
 */
internal fun apocalypseAbilityProgressRuleV5(stats: ApocalypseV3Stats, dayIndex: Int): String = buildString {
    val cost = apocalypseCoreCostPerResonancePointV5(stats.playerAbilityLevel)
    append("【晶核与空间异能成长硬规则】当前空间Lv.${stats.playerAbilityLevel}，可用晶核等价量=${stats.crystalCores}，")
    append("本级稳定共鸣=${stats.playerAbilityXp}/${abilityXpThresholdV3(stats.playerAbilityLevel)}。")
    append("晶核栏记录的是可安全利用的标准晶核等价量，不等于击杀数：绝大多数普通感染者没有完整可用晶核，常见的是无核、污染碎核或很多碎片才能整理出1个标准等价量；越高阶个体才越可能留下完整高质量晶核。")
    append("击杀不会自动入账。只有正文明确发生取核、筛选、回收、交易或获得样本，才能增加晶核；普通尸群即使数量很多也通常只有0—1个标准等价量，不能按一只丧尸一颗晶核结算。")
    append("playerAbilityXpGain不是普通经验。只有玩家明确选择吸收/炼化晶核强化空间异能，并真实扣除已有晶核时才可增加；训练、战斗、频繁使用异能、情绪高潮都只能提升熟练度，绝不能自行加等级共鸣。")
    if (stats.playerAbilityLevel < 5) {
        append("当前等级每获得1点稳定共鸣至少消耗${cost}个可用晶核等价量；本幕最多完成当前一级的突破，不能连续跳级。")
    } else {
        append("空间异能已达当前体系Lv.5，不再通过普通晶核继续涨等级。")
    }
    append("真正升级必须在正文中可见演出：玩家要明确感到容量、边界、距离感或新能力发生质变，并明确知道自己从旧等级突破到新等级；绝不能只改左上角状态栏。")
    if (dayIndex < 0) {
        append("当前仍在赤潮主沉降前：尚不存在可合法获取并吸收的源晶核，因此晶核增加、共鸣增长和空间升级一律禁止；已经保留的既有等级可以继续使用，但共鸣不能增长。")
    }
}

internal fun apocalypseActionRequestsCoreAbsorptionV5(action: String): Boolean {
    val text = action.trim()
    if (!text.contains("晶核") && !text.contains("源晶")) return false
    return listOf("吸收", "炼化", "强化", "升级", "突破", "修炼", "共鸣", "吞", "用晶核", "使用晶核")
        .any(text::contains)
}

/**
 * One resonance point is intentionally expensive. The level thresholds are small legacy integers,
 * so the real long-term pacing lives in the number of usable core equivalents required per point.
 */
internal fun apocalypseCoreCostPerResonancePointV5(level: Int): Int = when (level.coerceIn(1, 5)) {
    1 -> 4
    2 -> 8
    3 -> 14
    4 -> 24
    else -> Int.MAX_VALUE
}

/** Hard per-scene ceiling, not a guaranteed drop. Ordinary fights should still usually award zero. */
private fun apocalypseCoreGainCapV5(dayIndex: Int): Int = when {
    dayIndex < 0 -> 0
    dayIndex <= 13 -> 1
    dayIndex <= 59 -> 2
    dayIndex <= 179 -> 3
    else -> 5
}

private fun apocalypseCoreAcquisitionIsVisibleV5(action: String, beat: ApocalypseV3Beat): Boolean {
    val text = buildString {
        append(action)
        append(' ')
        append(beat.worldDelta)
        append(' ')
        append(beat.directive)
        append(' ')
        beat.nextDirector.assets.takeLast(8).forEach { append(it.title).append(' ').append(it.detail).append(' ') }
    }
    return listOf("晶核", "源晶", "碎核", "结晶", "取核", "搜尸", "剖开", "样本", "回收核心", "收集核心")
        .any(text::contains)
}

private fun sanitizeApocalypseCoreAssetsV5(
    save: ApocalypseV3Save,
    assets: List<ApocalypseV3Asset>,
    allowedPositiveCoreGain: Int,
): List<ApocalypseV3Asset> {
    val existingIds = save.director.assets.mapTo(mutableSetOf()) { it.id }
    var remainingNewCoreUnits = allowedPositiveCoreGain.coerceAtLeast(0)
    return buildList {
        assets.forEach { asset ->
            if (asset.kind != ApocalypseV3AssetKind.Core || asset.id in existingIds) {
                add(asset)
                return@forEach
            }
            if (remainingNewCoreUnits <= 0) return@forEach
            val quantity = asset.quantity.coerceAtMost(remainingNewCoreUnits).coerceAtLeast(1)
            add(asset.copy(quantity = quantity))
            remainingNewCoreUnits -= quantity
        }
    }
}

private fun apocalypsePlayerFirstPersonAndInventoryReceiptRuleV5(): String = """
【玩家第一人称与物资回执硬规则】
1. 玩家本人在正文中的动作、感受、判断、观察和内心一律使用第一人称“我”。禁止旁白用玩家名字、昵称、“玩家”“她/他”来叙述玩家本人，例如必须写“我把装备收进空间”，不能写“佳辞把装备收进空间”。其他角色在直接对话中可以自然称呼玩家姓名。
2. 只要本幕实际获得任何实体物资，状态回执必须生成完整获得清单。新协议优先字段为acquiredItems；同时兼容discoverAssets。正文实际获得N种具体物品，acquiredItems就必须有N条，一条都不能漏，不能只挑其中一件。
3. acquiredItems每条必须包含{id,kind,title,quantity,unit,detail,tag}。title只能是具体物品名，禁止“枪械及战术装备”“若干物资”这种合并名。quantity必须是明确整数，unit必须是实际单位（支/把/面/件/发/枚/瓶/罐/包/盒/袋/卷/台/辆等）。
4. 同一采购里的枪、盾牌、背心、弹药必须拆成四条独立记录。例如正文获得“民用防暴胶弹枪4支、高分子防爆盾牌2面、模块化战术防弹背心13件、橡塑防爆弹120发”，回执必须逐项完整列出4条，数量分别为4、2、13、120。
5. 箱/盒/提等包装如果正文知道内部数量，必须折算到可消耗单位并在detail保留包装关系；不知道箱内数量时保留“1箱未拆封”，绝不能虚构发数/瓶数。
6. 已有物品的消耗、丢失、赠送、损坏用inventoryChanges负quantityDelta；新获得物品不要同时在inventoryChanges再加一次，避免重复入账。
""".trimIndent()

/**
 * Sanitize both director and writer beats. This is the final authority even when a compatible model
 * returns over-generous drops or generic XP.
 */
internal fun sanitizeApocalypseAbilityProgressionV5(
    save: ApocalypseV3Save,
    action: String,
    beat: ApocalypseV3Beat,
): ApocalypseV3Beat {
    val rawCoreDelta = beat.coresFound
    val rawXpGain = beat.playerAbilityXpGain.coerceAtLeast(0)
    val wantsAbsorption = apocalypseActionRequestsCoreAbsorptionV5(action)
    val dayIndex = save.director.dayIndex

    val sanitizedCoreDelta: Int
    val validatedXpGain: Int

    if (wantsAbsorption && dayIndex >= 0 && save.stats.playerAbilityLevel < 5) {
        val level = save.stats.playerAbilityLevel
        val costPerPoint = apocalypseCoreCostPerResonancePointV5(level)
        val threshold = abilityXpThresholdV3(level)
        val pointsUntilBreakthrough = (threshold - save.stats.playerAbilityXp).coerceAtLeast(1)
        val maxSpendForThisLevel = pointsUntilBreakthrough * costPerPoint
        val explicitSpend = (-rawCoreDelta).coerceAtLeast(0)
        val impliedSpend = rawXpGain * costPerPoint
        val actionWantsMax = listOf("全部", "所有", "尽可能", "直接升级", "突破").any(action::contains)
        val desiredSpend = when {
            explicitSpend > 0 -> explicitSpend
            impliedSpend > 0 -> impliedSpend
            actionWantsMax -> maxSpendForThisLevel
            else -> costPerPoint
        }
        val availableSpend = desiredSpend
            .coerceAtMost(save.stats.crystalCores)
            .coerceAtMost(maxSpendForThisLevel)
        val points = (availableSpend / costPerPoint).coerceIn(0, pointsUntilBreakthrough)
        val actualSpend = points * costPerPoint
        sanitizedCoreDelta = -actualSpend
        validatedXpGain = points
    } else {
        validatedXpGain = 0
        sanitizedCoreDelta = when {
            rawCoreDelta < 0 -> -(-rawCoreDelta).coerceAtMost(save.stats.crystalCores)
            rawCoreDelta > 0 && dayIndex < 0 -> 0
            rawCoreDelta > 0 && !apocalypseCoreAcquisitionIsVisibleV5(action, beat) -> 0
            rawCoreDelta > 0 -> rawCoreDelta.coerceAtMost(apocalypseCoreGainCapV5(dayIndex))
            else -> 0
        }
    }

    val abilityRule = apocalypseAbilityProgressRuleV5(save.stats, dayIndex)
    val inventoryRule = apocalypseInventoryQuantityContractV5()
    val povAndReceiptRule = apocalypsePlayerFirstPersonAndInventoryReceiptRuleV5()
    val directiveParts = buildList {
        beat.directive.trim().takeIf(String::isNotBlank)?.let(::add)
        if (!beat.directive.contains("【晶核与空间异能成长硬规则】")) add(abilityRule)
        if (!beat.directive.contains("【具体物资与数量硬规则】")) add(inventoryRule)
        if (!beat.directive.contains("【玩家第一人称与物资回执硬规则】")) add(povAndReceiptRule)
    }
    val directive = directiveParts.joinToString("\n")

    val illegalUpgrade = validatedXpGain == 0 && !wantsAbsorption
    val cleanedFacts = if (!illegalUpgrade) {
        beat.nextDirector.worldFacts
    } else {
        beat.nextDirector.worldFacts.filterNot { fact ->
            fact !in save.director.worldFacts && apocalypseLooksLikeSpaceUpgradeClaimV5(fact)
        }
    }
    val cleanedAssets = sanitizeApocalypseCoreAssetsV5(
        save = save,
        assets = beat.nextDirector.assets,
        allowedPositiveCoreGain = sanitizedCoreDelta.coerceAtLeast(0),
    )

    return beat.copy(
        nextDirector = beat.nextDirector.copy(
            worldFacts = cleanedFacts,
            assets = cleanedAssets,
        ),
        directive = directive,
        coresFound = sanitizedCoreDelta,
        playerAbilityXpGain = validatedXpGain,
    )
}

/**
 * Hard runtime correction: before main impact no new crystal resonance can be earned. Preserve an
 * already visible Lv.2+ level, but reject stray pre-impact progress and impossible pre-impact cores.
 */
internal fun sanitizeApocalypseLoadedAbilityStateV5(save: ApocalypseV3Save): ApocalypseV3Save {
    if (save.director.dayIndex >= 0) return save
    if (save.stats.playerAbilityXp == 0 && save.stats.crystalCores == 0) return save
    return save.copy(
        stats = save.stats.copy(
            crystalCores = 0,
            playerAbilityXp = 0,
        ),
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * One-time migration for the save that lived through the old generic-XP implementation. It resets
 * the current resonance bar exactly once while preserving the already-visible level. After this flag
 * is written, legitimate post-impact crystal absorption can build resonance normally.
 */
internal fun migrateApocalypseLegacyResonanceOnceV5(
    context: Context,
    save: ApocalypseV3Save,
): ApocalypseV3Save {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences("apocalypse_ability_migrations_v5", Context.MODE_PRIVATE)
    val key = "resonance_reset_v2_${save.id}"
    val hardSanitized = sanitizeApocalypseLoadedAbilityStateV5(save)
    if (prefs.getBoolean(key, false)) return hardSanitized

    val migrated = if (hardSanitized.stats.playerAbilityXp == 0) {
        hardSanitized
    } else {
        hardSanitized.copy(
            stats = hardSanitized.stats.copy(playerAbilityXp = 0),
            updatedAt = System.currentTimeMillis(),
        )
    }
    prefs.edit().putBoolean(key, true).apply()
    return migrated
}

internal fun ensureApocalypseAbilityUpgradeNarrationV5(
    text: String,
    before: ApocalypseV3Stats,
    after: ApocalypseV3Stats,
): String {
    if (after.playerAbilityLevel <= before.playerAbilityLevel) return text.trim()
    val level = after.playerAbilityLevel
    val alreadyExplicit = text.contains("Lv.$level") &&
        listOf("升级", "突破", "晋升", "变强", "质变").any(text::contains)
    if (alreadyExplicit) return text.trim()

    val capacity = playerSpaceCapacityM3(level)
    val attack = playerSpaceAttack(level)
    val consumed = (before.crystalCores - after.crystalCores).coerceAtLeast(0)
    val notice = buildString {
        append("【旁白】")
        append("被你主动吸收的${consumed}个可用晶核等价量逐渐沉进意识深处，原本稳定的空间边界忽然向外舒展。")
        append("那不是一瞬间的错觉：你能清楚分辨出容量、距离感和空间纹理都发生了质变。")
        append("\n\n【旁白】你的空间异能从 Lv.${before.playerAbilityLevel} 正式突破到 Lv.$level。")
        append("稳定容量提升到约${capacity}m³；新的能力边界是：$attack。")
        append("这次变强有明确的晶核来源，也会从这一刻开始成为后续剧情的正史。")
    }
    return text.trimEnd() + "\n\n" + notice
}

internal fun withApocalypseAbilityUpgradeCanonV5(
    outcome: ApocalypseSceneOutcomeV5,
    before: ApocalypseV3Stats,
    after: ApocalypseV3Stats,
    visibleText: String,
): ApocalypseSceneOutcomeV5 {
    if (after.playerAbilityLevel <= before.playerAbilityLevel) return outcome.copy(text = visibleText)
    val summary = "玩家主动吸收真实持有的晶核，使空间异能从Lv.${before.playerAbilityLevel}突破到Lv.${after.playerAbilityLevel}。"
    val continuity = listOf(outcome.continuitySummary.trim(), summary)
        .filter(String::isNotBlank)
        .joinToString("｜")
        .take(360)
    val actionOutcome = listOf(outcome.actionOutcome.trim(), summary)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")
        .take(240)
    return outcome.copy(
        text = visibleText,
        continuitySummary = continuity,
        actionOutcome = actionOutcome,
    )
}

private fun apocalypseLooksLikeSpaceUpgradeClaimV5(text: String): Boolean {
    if (!text.contains("空间")) return false
    return listOf(
        "升级", "突破", "晋升", "Lv.2", "Lv2", "Lv.3", "Lv3", "Lv.4", "Lv4", "Lv.5", "Lv5",
        "裂隙刃", "闪位", "空间锁", "空间领域",
    ).any(text::contains)
}

/**
 * Story deletion is causal, not merely visual. The visible save/history/plot cards are restored by
 * the normal rollback path; this removes private future-only caches so a deleted clue cannot survive
 * in the backstage director and later be reintroduced as a "surprise".
 */
internal fun clearApocalypseFutureCachesForRollbackV5(context: Context, saveId: String) {
    if (saveId.isBlank()) return
    context.applicationContext
        .getSharedPreferences("apocalypse_living_world_v5", Context.MODE_PRIVATE)
        .edit()
        .remove("living_$saveId")
        .apply()
}
