package com.jiacimu.lulu.games

import kotlin.math.roundToInt

internal enum class ApocalypseTradeModeV5(val label: String) {
    Retail("正常零售"),
    Mixed("货币 / 物资混合"),
    Barter("以物易物"),
}

internal data class ApocalypseTradeOfferV5(
    val id: String,
    val title: String,
    val detail: String,
    val unitLabel: String,
    val stock: Int,
    val moneyCost: Int = 0,
    val materialsCost: Int = 0,
    val coresCost: Int = 0,
    val foodGain: Int = 0,
    val waterGain: Int = 0,
    val medicineGain: Int = 0,
    val materialsGain: Int = 0,
    val minutesPerUnit: Int = 2,
)

internal data class ApocalypseTradeMarketV5(
    val id: String,
    val location: String,
    val sellerName: String,
    val sellerDetail: String,
    val mode: ApocalypseTradeModeV5,
    val offers: List<ApocalypseTradeOfferV5>,
)

internal data class ApocalypseTradeLineV5(
    val offerId: String,
    val quantity: Int,
)

internal data class ApocalypseTradeQuoteV5(
    val lines: List<ApocalypseTradeLineV5>,
    val moneyCost: Int,
    val materialsCost: Int,
    val coresCost: Int,
    val foodGain: Int,
    val waterGain: Int,
    val medicineGain: Int,
    val materialsGain: Int,
    val minutesPassed: Int,
    val valid: Boolean,
    val reason: String = "",
)

internal data class ApocalypseTradeResolutionV5(
    val save: ApocalypseV3Save,
    val receipt: String,
    val storyAction: String,
)

private fun tradeScarcityMultiplierV5(save: ApocalypseV3Save): Float = when {
    save.director.dayIndex < -2 -> 1f
    save.director.dayIndex < 0 -> 1.12f
    save.director.dayIndex <= 2 -> 1.35f
    save.director.dayIndex <= 14 -> 1.65f
    else -> 2f
}

private fun retailOfferV5(
    save: ApocalypseV3Save,
    id: String,
    title: String,
    detail: String,
    unitLabel: String,
    stock: Int,
    basePrice: Int,
    food: Int = 0,
    water: Int = 0,
    medicine: Int = 0,
    materials: Int = 0,
): ApocalypseTradeOfferV5 {
    val price = (basePrice * tradeScarcityMultiplierV5(save)).roundToInt().coerceAtLeast(1)
    return ApocalypseTradeOfferV5(
        id = id,
        title = title,
        detail = detail,
        unitLabel = unitLabel,
        stock = stock,
        moneyCost = price,
        foodGain = food,
        waterGain = water,
        medicineGain = medicine,
        materialsGain = materials,
    )
}

private fun barterOfferV5(
    id: String,
    title: String,
    detail: String,
    unitLabel: String,
    stock: Int,
    materialsCost: Int = 0,
    coresCost: Int = 0,
    food: Int = 0,
    water: Int = 0,
    medicine: Int = 0,
    materials: Int = 0,
): ApocalypseTradeOfferV5 = ApocalypseTradeOfferV5(
    id = id,
    title = title,
    detail = detail,
    unitLabel = unitLabel,
    stock = stock,
    materialsCost = materialsCost,
    coresCost = coresCost,
    foodGain = food,
    waterGain = water,
    medicineGain = medicine,
    materialsGain = materials,
    minutesPerUnit = 4,
)

/**
 * Deterministic market state. AI may roleplay the seller, but it never invents the price, stock or
 * settlement result. This lets trading behave like a game system instead of prose accounting.
 */
internal fun buildApocalypseTradeMarketV5(
    save: ApocalypseV3Save,
    location: String = save.director.location,
): ApocalypseTradeMarketV5? {
    val text = location.lowercase()
    val retailPlace = listOf("市场", "商场", "超市", "便利店", "商店", "卖场", "物流园", "仓库").any(text::contains)
    val postTradePlace = retailPlace || listOf("基地", "营地", "聚居", "据点", "观测站", "气象站").any(text::contains)
    val preImpact = save.director.dayIndex < 0
    if (preImpact && !retailPlace) return null
    if (!preImpact && !postTradePlace) return null

    val daySeed = (save.id.hashCode().toLong() * 31L + save.director.dayIndex * 131L + location.hashCode()).let { kotlin.math.abs(it) }
    fun stock(base: Int, spread: Int): Int = base + (daySeed % spread.coerceAtLeast(1)).toInt()

    return if (preImpact) {
        ApocalypseTradeMarketV5(
            id = "retail:${location.hashCode()}:${save.director.dayIndex}",
            location = location,
            sellerName = when {
                "物流" in location || "仓库" in location -> "仓储批发窗口"
                "市场" in location -> "市场商户"
                else -> "零售收银台"
            },
            sellerDetail = "价格和库存由世界状态结算；AI只负责店员反应、排队、缺货与现实流程。",
            mode = ApocalypseTradeModeV5.Retail,
            offers = listOf(
                retailOfferV5(save, "water_case", "瓶装水整箱", "适合囤货的整箱饮用水。", "箱", stock(8, 8), 32, water = 8),
                retailOfferV5(save, "food_case", "耐储食品组合", "罐头、压缩饼干、即食主食等耐储食品。", "箱", stock(7, 7), 58, food = 8),
                retailOfferV5(save, "first_aid", "基础医药包", "消毒、止血、退热与常用药的基础组合。", "套", stock(3, 4), 86, medicine = 3),
                retailOfferV5(save, "repair_kit", "五金维修包", "胶带、紧固件、手工具与常用耗材。", "套", stock(4, 5), 72, materials = 4),
            ),
        )
    } else {
        val earlyCollapse = save.director.dayIndex <= 10
        ApocalypseTradeMarketV5(
            id = "barter:${location.hashCode()}:${save.director.dayIndex / 2}",
            location = location,
            sellerName = if ("观测站" in location || "气象站" in location) "临时补给交换点" else "幸存者交易摊",
            sellerDetail = "灾后交易不再由模型随口报数。库存、交换条件和实际扣除都由系统确定。",
            mode = if (earlyCollapse) ApocalypseTradeModeV5.Mixed else ApocalypseTradeModeV5.Barter,
            offers = buildList {
                if (earlyCollapse) {
                    add(ApocalypseTradeOfferV5("post_water_cash", "封装饮水", "来源可追溯的封装饮水。", "份", stock(4, 5), moneyCost = 45, waterGain = 3, minutesPerUnit = 3))
                    add(ApocalypseTradeOfferV5("post_food_cash", "封装口粮", "未开封的耐储食物。", "份", stock(4, 5), moneyCost = 70, foodGain = 3, minutesPerUnit = 3))
                }
                add(barterOfferV5("barter_water", "净化饮水", "经过基础过滤和煮沸处理的饮水。", "桶", stock(3, 4), materialsCost = 2, water = 5))
                add(barterOfferV5("barter_food", "混合口粮", "能撑数餐的干粮与罐头组合。", "包", stock(3, 4), materialsCost = 2, food = 5))
                add(barterOfferV5("barter_medicine", "紧缺药品包", "消毒、抗感染、止痛等更难获得的药品。", "包", stock(1, 3), materialsCost = 4, medicine = 3))
                add(barterOfferV5("barter_parts", "维修零件箱", "电工、车辆和基地维修都可能用上的通用件。", "箱", stock(2, 4), coresCost = 1, materials = 8))
            },
        )
    }
}

internal fun quoteApocalypseTradeV5(
    save: ApocalypseV3Save,
    market: ApocalypseTradeMarketV5,
    requested: Map<String, Int>,
): ApocalypseTradeQuoteV5 {
    val lines = requested.entries
        .mapNotNull { (id, rawQuantity) ->
            val offer = market.offers.firstOrNull { it.id == id } ?: return@mapNotNull null
            val quantity = rawQuantity.coerceIn(0, offer.stock)
            if (quantity <= 0) null else ApocalypseTradeLineV5(id, quantity)
        }

    var money = 0
    var materialsCost = 0
    var cores = 0
    var food = 0
    var water = 0
    var medicine = 0
    var materialsGain = 0
    var minutes = if (lines.isEmpty()) 0 else 6

    lines.forEach { line ->
        val offer = market.offers.first { it.id == line.offerId }
        money += offer.moneyCost * line.quantity
        materialsCost += offer.materialsCost * line.quantity
        cores += offer.coresCost * line.quantity
        food += offer.foodGain * line.quantity
        water += offer.waterGain * line.quantity
        medicine += offer.medicineGain * line.quantity
        materialsGain += offer.materialsGain * line.quantity
        minutes += offer.minutesPerUnit * line.quantity
    }

    val reason = when {
        lines.isEmpty() -> "还没有选择交易物品"
        save.stats.money < money -> "资金不足"
        save.stats.materials < materialsCost -> "可用于交换的材料不足"
        save.stats.crystalCores < cores -> "可用于交换的晶核不足"
        else -> ""
    }
    return ApocalypseTradeQuoteV5(
        lines = lines,
        moneyCost = money,
        materialsCost = materialsCost,
        coresCost = cores,
        foodGain = food,
        waterGain = water,
        medicineGain = medicine,
        materialsGain = materialsGain,
        minutesPassed = minutes.coerceIn(0, 90),
        valid = reason.isBlank(),
        reason = reason,
    )
}

internal fun resolveApocalypseTradeV5(
    save: ApocalypseV3Save,
    market: ApocalypseTradeMarketV5,
    requested: Map<String, Int>,
): ApocalypseTradeResolutionV5? {
    val quote = quoteApocalypseTradeV5(save, market, requested)
    if (!quote.valid || quote.lines.isEmpty()) return null

    val absolute = save.director.clockMinutes + quote.minutesPassed
    val nextDay = save.director.dayIndex + absolute / 1440
    val nextClock = ((absolute % 1440) + 1440) % 1440
    val nextStats = save.stats.copy(
        money = (save.stats.money - quote.moneyCost).coerceAtLeast(0),
        food = (save.stats.food + quote.foodGain).coerceIn(0, 999),
        water = (save.stats.water + quote.waterGain).coerceIn(0, 999),
        medicine = (save.stats.medicine + quote.medicineGain).coerceIn(0, 999),
        materials = (save.stats.materials - quote.materialsCost + quote.materialsGain).coerceIn(0, 999),
        crystalCores = (save.stats.crystalCores - quote.coresCost).coerceAtLeast(0),
    )
    val bought = quote.lines.joinToString("、") { line ->
        val offer = market.offers.first { it.id == line.offerId }
        "${offer.title}×${line.quantity}${offer.unitLabel}"
    }
    val costs = buildList {
        if (quote.moneyCost > 0) add("¥${quote.moneyCost}")
        if (quote.materialsCost > 0) add("材料${quote.materialsCost}")
        if (quote.coresCost > 0) add("晶核${quote.coresCost}")
    }.joinToString(" + ").ifBlank { "无" }
    val receipt = "在${market.location}完成交易：$bought；支付$costs；耗时${quote.minutesPassed}分钟。"
    val next = save.copy(
        stats = nextStats,
        director = save.director.copy(dayIndex = nextDay, clockMinutes = nextClock),
        updatedAt = System.currentTimeMillis(),
    )
    return ApocalypseTradeResolutionV5(
        save = next,
        receipt = receipt,
        storyAction = "我已经在${market.location}通过实际交易界面完成购买：$bought。交易金额、库存变化和时间已经由游戏系统结算，后续剧情只需要承认这笔既成事实，不要重新报价或重复扣除。",
    )
}
