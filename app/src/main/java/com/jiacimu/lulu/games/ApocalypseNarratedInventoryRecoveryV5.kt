package com.jiacimu.lulu.games

/**
 * Inventory must be produced by the writer's structured receipt, never reverse-engineered from prose.
 * The prose can stay literary; acquiredItems/discoverAssets is the canonical inventory ledger input.
 */
internal fun apocalypseInventoryQuantityContractV5(): String = """
【具体物资与数量硬规则】
只要本幕真实购买、搜集、领取、交换、捡到、搬走或收入空间任何实体物品，正文在自然叙事中应写清具体品名、明确整数数量与实际单位；同时状态回执 acquiredItems 必须逐项完整列出本幕真正获得的全部物品，客户端只按结构化清单入库，不会再从正文反推库存。
acquiredItems 每一项必须包含：title、kind、quantity、unit、detail；正文获得了几种物品，acquiredItems 就必须有几项，一件都不能漏。没有获得任何新物品时返回空数组 []。
禁止用“一批、若干、一些、战术装备若干、食品若干”代替最终库存数量。枪械按支/把，盾牌按面，背心/护甲/头盔按件，弹药按发/枚；必须拆成实际取得的每一种物品，不能合并成“枪械及战术装备”。
如果使用箱/盒/提等外包装，已知包装规格时必须同时给出折算后的实际可消耗数量，例如“10箱矿泉水，每箱12瓶，共120瓶”，acquiredItems.quantity=120、unit="瓶"，detail保留“10箱×12瓶/箱”。如果剧情确实不知道箱内数量，则把未拆包装本身作为库存单位，例如 quantity=1、unit="箱"，禁止虚构箱内数量。
食物、饮水、药品同样必须使用真实可消耗单位，方便后续吃多少、喝多少、使用多少就准确扣多少。
【重要】不得在可见正文末尾追加任何“入库清点”“库存审计”“系统补记”“数量待确认”之类的系统说明；库存清单只存在于隐藏状态回执中。
""".trimIndent()

/**
 * Compatibility hook kept for the generation pipeline. The old implementation attempted to infer
 * inventory from Chinese prose and even appended a visible "入库清点" audit paragraph. That fallback
 * is intentionally removed: structured acquiredItems/discoverAssets is now the only inventory source.
 */
internal fun recoverApocalypseNarratedInventoryV5(
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 = outcome.copy(
    text = stripLegacyApocalypseInventoryAuditV5(outcome.text),
)

/** Remove only the exact legacy audit paragraph previously appended by the client. */
internal fun stripLegacyApocalypseInventoryAuditV5(text: String): String {
    if (text.isBlank()) return text
    val marker = "【旁白】入库清点："
    val index = text.lastIndexOf(marker)
    if (index < 0) return text
    return text.substring(0, index).trimEnd()
}
