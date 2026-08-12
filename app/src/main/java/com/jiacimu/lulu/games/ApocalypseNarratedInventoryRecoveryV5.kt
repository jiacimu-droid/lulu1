package com.jiacimu.lulu.games

import java.util.UUID

/**
 * Hard writer contract injected into every V5 scene before prose generation.
 * Concrete stock is only useful when the prose and receipt contain countable quantities.
 */
internal fun apocalypseInventoryQuantityContractV5(): String = """
【具体物资与数量硬规则】
只要本幕真实购买、搜集、领取、交换、捡到、搬走或收入空间任何实体物品，正文必须在获得发生的位置写出“具体品名 + 明确整数数量 + 实际单位”，状态回执discoverAssets也必须逐项一一对应。禁止用“一批、若干、一些、整箱、几件、战术装备若干、食品若干”作为最终数量。
如果使用箱/盒/提等外包装，必须同时写包装数、每包装单件数和折算后的总数，例如“10箱矿泉水，每箱12瓶，共120瓶”。如果正文确实无法知道箱内数量，就只能保留为“1箱未拆封”，不能把未知数量伪装成单件数。
战斗物资必须分开计数：枪械按支/把，盾牌按面，背心/护甲/头盔按件，弹药按发/枚；“枪械及战术装备”这种合并名禁止直接入库，必须拆成实际取得的每一种物品。食物、饮水、药品也必须分别写真实可消耗单位，方便后续吃多少、喝多少、用多少就扣多少。
采购成交前如果数量尚未确定，就先在剧情里确定数量再完成付款/入库；不能先写“全部收进空间”却不给可统计数量。
""".trimIndent()

/**
 * Repairs concrete inventory rows from prose when the model receipt is incomplete or uses an
 * unexpected kind. Recovery is intentionally conservative: ordinary narrative fragments are never
 * allowed to become warehouse items merely because the sentence also contains an acquisition verb.
 */
internal fun recoverApocalypseNarratedInventoryV5(
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 {
    val source = listOf(outcome.actionOutcome, outcome.text)
        .filter(String::isNotBlank)
        .joinToString("\n")

    val normalizedReported = outcome.delta.discoverAssets.map(::normalizeNarratedAssetKindV5)
    val recovered = recoverCompletedAcquisitionRowsV5(source)
    val mergedAssets = mergeNarratedAssetsV5(normalizedReported, recovered).take(64)

    val normalizedChanges = outcome.inventoryChanges.map { change ->
        val classification = classifyNarratedInventoryV5(
            title = change.title,
            detail = change.detail,
            tag = change.tag,
            original = change.kind,
        )
        change.copy(
            kind = classification.kind,
            tag = mergeNarratedInventoryTagsV5(change.tag, classification.tag),
        )
    }.take(64)

    val reported = outcome.reportedStateFields.toMutableSet()
    if (mergedAssets != outcome.delta.discoverAssets) reported += "discoverAssets"
    if (normalizedChanges != outcome.inventoryChanges) reported += "inventoryChanges"

    val visibleText = ensureNarratedAcquisitionQuantitiesVisibleV5(
        text = outcome.text,
        additions = mergedAssets,
    )

    return outcome.copy(
        text = visibleText,
        simulationStateReported = outcome.simulationStateReported || reported.isNotEmpty(),
        reportedStateFields = reported,
        inventoryChanges = normalizedChanges,
        delta = outcome.delta.copy(discoverAssets = mergedAssets),
    )
}

private data class NarratedInventoryClassificationV5(
    val kind: ApocalypseV3AssetKind,
    val tag: String = "",
)

private data class NarratedPackageV5(
    val quantity: Int,
    val unit: String,
    val detail: String,
)

private val narratedAcquisitionWordsV5 = listOf(
    "买下", "买了", "购买", "购入", "采购", "结账", "付款后", "拿到", "拿下", "收下", "带走", "搬走",
    "搬进空间", "搬进了空间", "装进空间", "装进了空间", "塞进空间", "塞进了空间", "收入空间", "收入了空间",
    "收进空间", "收进了空间", "存进空间", "存进了空间", "放进空间", "放进了空间", "带进空间", "囤下", "入库",
)
private val narratedAcquisitionNegationsV5 = listOf(
    "没买", "没有买", "未买", "买不到", "没拿到", "未拿到", "缺货", "暂不买", "不买", "想买", "打算买",
    "准备买", "考虑买", "问价", "询价", "看看价格", "只看了", "只是看", "没有带走", "没带走", "没有收进",
)

private val narratedFoodWordsV5 = listOf(
    "罐头", "方便面", "泡面", "面饼", "饼干", "压缩粮", "压缩饼干", "大米", "米袋", "面粉", "挂面", "面条",
    "面包", "火腿", "香肠", "午餐肉", "水饺", "饺子", "馒头", "快餐", "盒饭", "点心", "糕点", "蛋糕",
    "巧克力", "糖果", "糖", "能量棒", "肉干", "冻肉", "食品", "粮食", "食用油", "薯片", "零食", "辣条",
    "坚果", "果干", "海苔", "威化", "曲奇", "果冻", "麦片", "燕麦", "泡芙",
)
private val narratedWaterWordsV5 = listOf(
    "矿泉水", "纯净水", "饮用水", "瓶装水", "桶装水", "净水", "饮料", "果汁", "汽水", "苏打水", "运动饮料",
)
private val narratedMedicineWordsV5 = listOf(
    "药品", "药物", "退烧药", "止痛药", "消炎药", "抗生素", "抗过敏", "止泻药", "胃药", "感冒药", "维生素",
    "绷带", "纱布", "创可贴", "碘伏", "医用酒精", "消毒液", "止血带", "急救包", "医疗包", "注射器", "口罩",
)
private val narratedCombatWordsV5 = listOf(
    "枪械", "枪支", "手枪", "步枪", "冲锋枪", "霰弹枪", "猎枪", "狙击枪", "机枪", "卡宾枪", "防爆弹枪", "防暴弹枪",
    "枪弹", "胶弹", "橡胶弹", "防暴弹", "弹药", "子弹", "弹丸", "弹匣", "弹夹", "砍刀", "开山刀", "折叠刀",
    "战术刀", "匕首", "军刀", "长刀", "短刀", "斧头", "战斧", "弓箭", "弓弩", "弩箭", "甩棍", "防身棍",
    "警棍", "撬棍", "盾牌", "防暴盾", "防爆盾", "防爆装备", "防暴装备", "防弹装备", "防刺装备", "防弹衣",
    "防刺服", "防暴服", "防爆服", "战术头盔", "防弹头盔", "护甲", "战术背心", "防弹背心", "战术护具",
    "战术手套", "护膝", "护肘",
)
private val narratedVehicleWordsV5 = listOf(
    "汽车", "轿车", "越野车", "面包车", "货车", "卡车", "皮卡", "摩托车", "摩托", "电动车", "自行车", "三轮车", "拖车", "房车", "快艇",
)
private val narratedMaterialWordsV5 = listOf(
    "木板", "木方", "木材", "钢板", "铁板", "钢管", "铁管", "角钢", "型钢", "钢筋", "铁丝", "钢丝", "铁钉", "钉子",
    "螺丝", "螺栓", "螺母", "水泥", "砂浆", "沙子", "砖", "玻璃", "电线", "线缆", "铜线", "胶带", "扎带", "篷布", "零件", "五金件",
)
private val narratedToolWordsV5 = listOf(
    "锤子", "铁锤", "榔头", "扳手", "螺丝刀", "改锥", "钳子", "电钻", "手电钻", "电锯", "手锯", "锯子", "工具箱",
    "卷尺", "水平尺", "测电笔", "万用表", "电烙铁", "焊机", "铁锹", "铁铲", "铲子", "镐", "剪刀", "管钳", "千斤顶",
)
private val narratedHouseholdWordsV5 = listOf(
    "卫生纸", "纸巾", "湿巾", "卫生巾", "牙刷", "牙膏", "洗面奶", "洗发", "护发", "沐浴", "香皂", "肥皂", "洗衣液",
    "洗洁精", "清洁剂", "抹布", "拖把", "扫帚", "毛巾", "浴巾", "衣架", "垃圾袋", "收纳箱", "床单", "被子", "毯子",
    "枕头", "内衣", "内裤", "袜子", "衣物", "外套", "裤子", "鞋子", "拖鞋", "餐具", "筷子", "勺子", "杯子", "饭盒",
)
private val narratedSurvivalWordsV5 = listOf(
    "背包", "登山包", "行军包", "帐篷", "天幕", "睡袋", "防潮垫", "折叠床", "手电筒", "手电", "头灯", "营灯", "指南针",
    "望远镜", "求生哨", "救生衣", "安全绳", "攀岩绳", "登山绳", "登山扣", "滤水器", "净水器", "净水片", "防毒面具", "雨衣", "雨披",
)
private val narratedEnergyWordsV5 = listOf(
    "汽油", "柴油", "煤油", "燃油", "燃料", "液化气", "燃气", "气罐", "丁烷", "丙烷", "固体燃料", "酒精燃料", "木炭",
    "煤炭", "木柴", "发电机", "太阳能板", "蓄电池", "备用电池", "干电池", "锂电池", "电池组", "储能电源", "户外电源",
)
private val narratedElectronicsWordsV5 = listOf(
    "手机", "对讲机", "无线电", "收音机", "卫星电话", "电脑", "笔记本电脑", "平板电脑", "充电宝", "移动电源", "充电器", "数据线",
    "插线板", "电源适配器", "相机", "摄像机", "无人机", "gps", "定位器", "导航仪", "耳机", "麦克风", "监控", "摄像头", "硬盘", "u盘",
)
private val narratedKeyWordsV5 = listOf("钥匙", "门禁卡", "门卡", "房卡", "通行证", "权限卡", "身份卡", "密码", "口令", "访问权限")
private val narratedDocumentWordsV5 = listOf("文件", "档案", "记录", "清单", "报告", "日志", "名单", "图纸", "施工图", "说明书", "手册", "合同", "证件", "账本", "笔记")
private val narratedClueWordsV5 = listOf("线索", "情报", "口供", "坐标", "编号", "暗号", "痕迹", "目击", "传闻")

private val narratedNumberedItemRegexV5 = Regex(
    """(\d{1,6}|[一二两三四五六七八九十百千]{1,5})\s*(箱|盒|包|袋|瓶|罐|把|根|卷|件|套|桶|提|板|支|个|份|条|双|枚|发|片|粒|台|辆|只|组|面)\s*(?:的)?\s*([^、，,；;。！？!?\n]{1,34})""",
)
private val narratedWholePackageRegexV5 = Regex(
    """整\s*(箱|盒|包|袋|桶|套|件|组)\s*(?:的)?\s*([^、，,；;。！？!?\n]{1,34})""",
)

private fun recoverCompletedAcquisitionRowsV5(text: String): List<ApocalypseV3Asset> {
    if (text.isBlank()) return emptyList()
    val recovered = mutableListOf<ApocalypseV3Asset>()
    val sentences = text.split(Regex("[。！？!?\n]+"))
        .map(String::trim)
        .filter(String::isNotBlank)

    sentences.forEach { sentence ->
        if (!isCompletedAcquisitionSentenceV5(sentence)) return@forEach

        narratedNumberedItemRegexV5.findAll(sentence).forEach { match ->
            val count = parseNarratedSmallNumberV5(match.groupValues[1]) ?: return@forEach
            val outerUnit = match.groupValues[2]
            val title = cleanNarratedInventoryCandidateV5(match.groupValues[3])
            createRecoveredNarratedAssetV5(title, count, outerUnit, sentence, quantityExplicit = true)?.let(recovered::add)
        }
        narratedWholePackageRegexV5.findAll(sentence).forEach { match ->
            val outerUnit = match.groupValues[1]
            val title = cleanNarratedInventoryCandidateV5(match.groupValues[2])
            createRecoveredNarratedAssetV5(title, 1, outerUnit, sentence, quantityExplicit = true)?.let(recovered::add)
        }

        val zone = acquisitionInventoryZoneV5(sentence)
        val listPieces = zone
            .split(Regex("[、，,；;]|以及|还有|连同|与|和|及"))
            .map(::cleanNarratedInventoryCandidateV5)
            .filter { it.length in 2..34 }
        listPieces.forEach { candidate ->
            if (recovered.any { sameNarratedInventoryTitleV5(it.title, candidate) }) return@forEach
            if (looksLikeNarrativePhraseInsteadOfItemV5(candidate)) return@forEach
            val classification = classifyNarratedInventoryV5(candidate, "", "", ApocalypseV3AssetKind.Tool)
            val known = classification.tag != "待分类"
            if (!known && !looksLikeConcreteUnknownItemV5(candidate)) return@forEach
            createRecoveredNarratedAssetV5(
                rawTitle = candidate,
                count = 1,
                outerUnit = inferNaturalUnitFromCandidateV5(candidate),
                sentence = sentence,
                quantityExplicit = false,
            )?.let(recovered::add)
        }
    }

    return mergeNarratedAssetsV5(emptyList(), recovered)
}

private fun isCompletedAcquisitionSentenceV5(sentence: String): Boolean {
    if (narratedAcquisitionNegationsV5.any(sentence::contains)) return false
    return narratedAcquisitionWordsV5.any(sentence::contains)
}

private fun acquisitionInventoryZoneV5(sentence: String): String {
    val hits = narratedAcquisitionWordsV5.mapNotNull { word ->
        sentence.indexOf(word).takeIf { it >= 0 }?.let { it to word }
    }
    if (hits.isEmpty()) return sentence
    val (index, word) = hits.minByOrNull { it.first } ?: return sentence
    val before = sentence.substring(0, index)
    val after = sentence.substring((index + word.length).coerceAtMost(sentence.length))
    return when {
        before.length >= 6 && after.length < 10 -> before
        word.contains("空间") && before.length >= 4 -> before
        else -> after
    }
}

private fun cleanNarratedInventoryCandidateV5(raw: String): String {
    var value = raw.trim()
        .replace(Regex("^(我把|我将|我又把|我再把|然后把|随后把|接着把|被我|这些|那些|全部|一并|统统|都|其中|包括|比如|像是|以及)+"), "")
        .trim()
    val actionStops = narratedAcquisitionWordsV5 + listOf("然后", "随后", "接着", "最后", "被我", "全被", "全部被")
    actionStops.mapNotNull { stop -> value.indexOf(stop).takeIf { it > 0 } }
        .minOrNull()
        ?.let { value = value.substring(0, it) }
    value = value
        .replace(Regex("^(?:一批|一套|一组|一堆|若干|几件|几样|一些)"), "")
        .replace(Regex("^(?:整箱|整盒|整包|整袋|整套|整组)(?:的)?"), "")
        .replace(Regex("(?:都|全部|一并)?(?:被)?(?:我)?(?:收进|收入|装进|塞进|放进|存进|搬进|带进)(?:了)?空间.*$"), "")
        .trim(' ', '的', '：', ':', '（', '）', '(', ')')
    return value.take(48)
}

private fun looksLikeNarrativePhraseInsteadOfItemV5(value: String): Boolean {
    if (value.length < 2) return true
    val narrative = listOf(
        "老板", "店员", "他说", "她说", "我说", "我们", "他们", "价格", "付款", "结账", "空间", "车里", "店里", "仓库里",
        "准备", "决定", "发现", "觉得", "确认", "检查", "看看", "推荐", "告诉", "之后", "然后", "随后", "终于", "已经",
        "捏在手里", "拿在手里", "握在手里", "眉开眼笑", "道了声谢", "说了声谢", "随手一挥", "便将其", "将其", "笑着",
        "说道", "说着", "接过", "递给", "转身", "点头", "看着", "望着", "走向", "走出", "收好之后",
    )
    return narrative.any(value::contains) ||
        Regex("(了|着|地|得)(声谢|起来|过去|回来|出去|进去|一下|一眼)$").containsMatchIn(value)
}

private fun looksLikeConcreteUnknownItemV5(value: String): Boolean {
    val concreteNouns = listOf(
        "设备", "装备", "用品", "工具", "器", "机", "仪", "灯", "箱", "柜", "架", "包", "袋", "瓶", "罐", "盒", "桶",
        "衣", "服", "鞋", "帽", "盔", "甲", "盾", "枪", "弹", "刀", "剑", "弓", "弩", "棍", "锤", "钳", "铲", "锯",
        "板", "管", "线", "绳", "网", "布", "膜", "胶", "卡", "钥匙", "表", "杯", "锅", "炉", "床", "椅", "桌",
        "药", "粮", "食品", "饮料", "零食", "材料", "配件", "零件", "电池", "电源", "燃料", "方舱", "模块",
    )
    return value.length <= 24 && concreteNouns.any(value::contains)
}

private fun createRecoveredNarratedAssetV5(
    rawTitle: String,
    count: Int,
    outerUnit: String,
    sentence: String,
    quantityExplicit: Boolean,
): ApocalypseV3Asset? {
    val title = cleanNarratedInventoryCandidateV5(rawTitle)
    if (title.length < 2 || looksLikeNarrativePhraseInsteadOfItemV5(title)) return null
    val classification = classifyNarratedInventoryV5(title, "", "", ApocalypseV3AssetKind.Tool)
    val packageInfo = resolveNarratedPackageV5(classification.kind, title, count, outerUnit, sentence)
    val quantityTag = if (quantityExplicit) "" else "数量待确认"
    return ApocalypseV3Asset(
        id = UUID.randomUUID().toString(),
        kind = classification.kind,
        title = title,
        detail = buildString {
            append("根据本幕已经完成的获得行为补记；${packageInfo.detail}")
            if (!quantityExplicit) append("；原正文未明确写数量，暂按1个最小占位记录，后续新生成场景必须给出明确数量")
        }.take(360),
        quantity = packageInfo.quantity.coerceIn(1, 99_999),
        tag = mergeNarratedInventoryTagsV5(
            mergeNarratedInventoryTagsV5(classification.tag, quantityTag),
            "单位=${packageInfo.unit}",
        ).take(120),
    )
}

private fun resolveNarratedPackageV5(
    kind: ApocalypseV3AssetKind,
    title: String,
    count: Int,
    outerUnit: String,
    sentence: String,
): NarratedPackageV5 {
    if (outerUnit == "箱" || outerUnit == "提") {
        val explicit = Regex("""每(?:箱|提)\s*(\d{1,6})\s*(瓶|罐|包|盒|袋|支|个|件|卷|份|片|粒|枚|发|面)""")
            .find(sentence)
        if (explicit != null) {
            val each = explicit.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val unit = explicit.groupValues[2]
            return NarratedPackageV5(count * each, unit, "原包装：${count}${outerUnit}×${each}${unit}/${outerUnit}=${count * each}${unit}")
        }
        val default = when (kind) {
            ApocalypseV3AssetKind.Water -> if (title.contains("桶装")) 1 to "桶" else 12 to "瓶"
            ApocalypseV3AssetKind.Food -> when {
                title.contains("罐头") || title.contains("罐装") -> 12 to "罐"
                title.contains("方便面") || title.contains("泡面") || title.contains("面饼") -> 12 to "包"
                title.contains("饼干") || title.contains("薯片") || title.contains("零食") || title.contains("能量棒") || title.contains("压缩粮") -> 12 to "包"
                else -> null
            }
            else -> null
        }
        if (default != null) {
            val (each, unit) = default
            return NarratedPackageV5(count * each, unit, "按标准包装折算：${count}${outerUnit}×${each}${unit}/${outerUnit}=${count * each}${unit}")
        }
        return NarratedPackageV5(count, outerUnit, "原包装：${count}${outerUnit}；正文未说明箱内单件数，因此不虚构拆箱数量")
    }
    return NarratedPackageV5(
        count,
        outerUnit.ifBlank { inferNarratedUnitV5(kind, title) },
        "原数量：$count${outerUnit.ifBlank { inferNarratedUnitV5(kind, title) }}",
    )
}

private fun inferNaturalUnitFromCandidateV5(title: String): String = when {
    title.contains("盾") -> "面"
    title.contains("子弹") || title.contains("弹药") || title.contains("枪弹") || title.contains("胶弹") -> "发"
    title.contains("枪") -> "支"
    title.contains("水") || title.contains("饮料") -> "瓶"
    title.contains("罐头") -> "罐"
    title.contains("薯片") || title.contains("零食") || title.contains("饼干") -> "包"
    title.contains("车辆") || title.contains("汽车") || title.contains("摩托") || title.contains("自行车") -> "辆"
    else -> "件"
}

private fun normalizeNarratedAssetKindV5(asset: ApocalypseV3Asset): ApocalypseV3Asset {
    val classification = classifyNarratedInventoryV5(asset.title, asset.detail, asset.tag, asset.kind)
    return asset.copy(
        kind = classification.kind,
        tag = mergeNarratedInventoryTagsV5(asset.tag, classification.tag),
    )
}

private fun classifyNarratedInventoryV5(
    title: String,
    detail: String,
    tag: String,
    original: ApocalypseV3AssetKind,
): NarratedInventoryClassificationV5 {
    val text = "$title $detail $tag".lowercase()
    fun has(words: List<String>) = words.any(text::contains)

    if (original == ApocalypseV3AssetKind.Map) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Map)
    if (original == ApocalypseV3AssetKind.Core || text.contains("晶核")) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Core)
    if (has(narratedWaterWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Water)
    if (has(narratedFoodWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Food)
    if (has(narratedMedicineWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Medicine)
    if (has(narratedCombatWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Weapon, "战斗")
    if (has(narratedVehicleWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Vehicle)
    if (has(narratedKeyWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Key)
    if (has(narratedDocumentWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Document)
    if (has(narratedClueWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Clue)
    if (has(narratedEnergyWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Tool, "能源燃料")
    if (has(narratedElectronicsWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Tool, "电子通讯")
    if (has(narratedSurvivalWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Tool, "生存装备")
    if (has(narratedHouseholdWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Tool, "生活用品")
    if (has(narratedMaterialWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Material)
    if (has(narratedToolWordsV5)) return NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Tool)

    return when (original) {
        ApocalypseV3AssetKind.Food,
        ApocalypseV3AssetKind.Water,
        ApocalypseV3AssetKind.Medicine,
        ApocalypseV3AssetKind.Material,
        ApocalypseV3AssetKind.Weapon,
        ApocalypseV3AssetKind.Vehicle,
        ApocalypseV3AssetKind.Key,
        ApocalypseV3AssetKind.Document,
        ApocalypseV3AssetKind.Map,
        ApocalypseV3AssetKind.Core -> NarratedInventoryClassificationV5(original)
        ApocalypseV3AssetKind.Clue -> {
            if (text.contains("线索") || text.contains("情报") || text.contains("坐标")) {
                NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Clue)
            } else {
                NarratedInventoryClassificationV5(ApocalypseV3AssetKind.Tool, "待分类")
            }
        }
        ApocalypseV3AssetKind.Tool -> NarratedInventoryClassificationV5(
            ApocalypseV3AssetKind.Tool,
            if (tag.isBlank()) "待分类" else "",
        )
    }
}

private fun inferNarratedUnitV5(kind: ApocalypseV3AssetKind, title: String): String = when (kind) {
    ApocalypseV3AssetKind.Water -> if (title.contains("桶装")) "桶" else "瓶"
    ApocalypseV3AssetKind.Food -> when {
        title.contains("罐头") || title.contains("罐装") -> "罐"
        title.contains("方便面") || title.contains("泡面") || title.contains("饼干") || title.contains("薯片") || title.contains("零食") -> "包"
        else -> "份"
    }
    ApocalypseV3AssetKind.Medicine -> "件"
    ApocalypseV3AssetKind.Weapon -> when {
        title.contains("盾") -> "面"
        title.contains("子弹") || title.contains("弹药") || title.contains("枪弹") || title.contains("胶弹") -> "发"
        title.contains("枪") -> "支"
        else -> "件"
    }
    ApocalypseV3AssetKind.Vehicle -> "辆"
    ApocalypseV3AssetKind.Key -> "枚"
    ApocalypseV3AssetKind.Document,
    ApocalypseV3AssetKind.Clue,
    ApocalypseV3AssetKind.Map -> "份"
    ApocalypseV3AssetKind.Core -> "枚"
    else -> "件"
}

private fun mergeNarratedAssetsV5(
    first: List<ApocalypseV3Asset>,
    second: List<ApocalypseV3Asset>,
): List<ApocalypseV3Asset> {
    val merged = first.toMutableList()
    second.forEach { incoming ->
        val index = merged.indexOfFirst { existing -> sameNarratedInventoryTitleV5(existing.title, incoming.title) }
        if (index < 0) {
            merged += incoming
        } else {
            val existing = merged[index]
            val preferIncomingKind = existing.kind == ApocalypseV3AssetKind.Clue && incoming.kind != ApocalypseV3AssetKind.Clue
            merged[index] = existing.copy(
                kind = if (preferIncomingKind) incoming.kind else existing.kind,
                quantity = maxOf(existing.quantity, incoming.quantity),
                detail = mergeNarratedInventoryDetailsV5(existing.detail, incoming.detail),
                tag = mergeNarratedInventoryTagsV5(existing.tag, incoming.tag),
            )
        }
    }
    return merged
}

private fun ensureNarratedAcquisitionQuantitiesVisibleV5(
    text: String,
    additions: List<ApocalypseV3Asset>,
): String {
    if (text.isBlank() || additions.isEmpty()) return text
    val physical = additions.filterNot {
        it.kind in setOf(
            ApocalypseV3AssetKind.Clue,
            ApocalypseV3AssetKind.Document,
            ApocalypseV3AssetKind.Map,
        )
    }
    if (physical.isEmpty()) return text

    val missing = physical.filterNot { asset -> textShowsQuantityForAssetV5(text, asset) }
    if (missing.isEmpty()) return text

    val line = missing.joinToString("、") { asset ->
        val unit = Regex("(?:^|[；;])单位=([^；;]+)")
            .find(asset.tag)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: inferNarratedUnitV5(asset.kind, asset.title)
        if (asset.tag.contains("数量待确认")) {
            "${asset.title}（原文数量未明确，暂记${asset.quantity}${unit}，待后续确认）"
        } else {
            "${asset.title}${asset.quantity}${unit}"
        }
    }
    return text.trimEnd() + "\n\n【旁白】入库清点：$line。"
}

private fun textShowsQuantityForAssetV5(text: String, asset: ApocalypseV3Asset): Boolean {
    val title = asset.title.trim()
    if (title.length < 2) return false
    var index = text.indexOf(title, ignoreCase = true)
    while (index >= 0) {
        val start = (index - 16).coerceAtLeast(0)
        val end = (index + title.length + 20).coerceAtMost(text.length)
        val window = text.substring(start, end)
        if (Regex("(?:\d{1,6}|[一二两三四五六七八九十百千]+)\s*(?:箱|盒|包|袋|瓶|罐|把|根|卷|件|套|桶|提|板|支|个|份|条|双|枚|发|片|粒|台|辆|只|组|面)").containsMatchIn(window)) {
            return true
        }
        index = text.indexOf(title, startIndex = index + title.length, ignoreCase = true)
    }
    return false
}

private fun sameNarratedInventoryTitleV5(first: String, second: String): Boolean {
    fun key(value: String) = value.lowercase()
        .replace(Regex("[\\s·•，,。；;：:（）()\\[\\]【】_-]+"), "")
        .replace("整箱", "")
        .replace("箱装", "")
        .replace("整盒", "")
        .replace("盒装", "")
        .replace("的", "")
    val a = key(first)
    val b = key(second)
    if (a.isBlank() || b.isBlank()) return false
    return a == b || (minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a)))
}

private fun mergeNarratedInventoryTagsV5(first: String, second: String): String =
    (first.split('；', ';') + second.split('；', ';'))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")
        .take(120)

private fun mergeNarratedInventoryDetailsV5(first: String, second: String): String = when {
    first.isBlank() -> second.take(360)
    second.isBlank() -> first.take(360)
    first.contains(second) -> first.take(360)
    second.contains(first) -> second.take(360)
    else -> "$first；$second".take(360)
}

private fun parseNarratedSmallNumberV5(raw: String): Int? {
    raw.toIntOrNull()?.let { return it }
    val digits = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    if (raw == "十") return 10
    if ('百' in raw || '千' in raw) return parseSimpleChineseIntegerV5(raw)
    if ('十' in raw) {
        val parts = raw.split('十', limit = 2)
        val tens = parts.firstOrNull()?.firstOrNull()?.let(digits::get) ?: 1
        val ones = parts.getOrNull(1)?.firstOrNull()?.let(digits::get) ?: 0
        return tens * 10 + ones
    }
    return raw.firstOrNull()?.let(digits::get)
}

private fun parseSimpleChineseIntegerV5(raw: String): Int? {
    val digit = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    var total = 0
    var current = 0
    raw.forEach { char ->
        when (char) {
            '千' -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
            '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
            '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
            else -> digit[char]?.let { current = it }
        }
    }
    return (total + current).takeIf { it > 0 }
}
