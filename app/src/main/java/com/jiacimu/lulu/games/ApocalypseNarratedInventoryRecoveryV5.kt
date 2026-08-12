package com.jiacimu.lulu.games

import java.util.UUID

/**
 * Hard contract injected into every V5 scene before the prose writer runs.
 * Concrete stock must be countable, otherwise later consumption cannot be deterministic.
 */
internal fun apocalypseInventoryQuantityContractV5(): String = """
【具体物资与数量硬规则】
只要本幕真实购买、搜集、领取、交换、捡到、搬走或收入空间任何实体物品，正文必须在获得发生的位置写出“具体品名 + 明确整数数量 + 实际单位”，状态回执discoverAssets也必须逐项一一对应。禁止用“一批、若干、一些、整箱、几件、战术装备若干、食品若干”作为最终数量。
如果使用箱/盒/提等外包装，必须同时写包装数、每包装单件数和折算后的总数，例如“10箱矿泉水，每箱12瓶，共120瓶”。如果正文确实不知道箱内数量，只能保留为“1箱未拆封”，不能虚构单件数。
战斗物资必须分开计数：枪械按支/把，盾牌按面，背心/护甲/头盔按件，弹药按发/枚；禁止把“枪械及战术装备”当成一个库存名，必须拆成实际取得的每一种物品。
食物、饮水、药品也必须分别写真实可消耗单位，方便以后吃多少、喝多少、用多少就扣多少。采购成交前如果数量尚未确定，就先确定数量再完成付款和入库。
""".trimIndent()

internal fun recoverApocalypseNarratedInventoryV5(
    outcome: ApocalypseSceneOutcomeV5,
): ApocalypseSceneOutcomeV5 {
    val source = listOf(outcome.actionOutcome, outcome.text)
        .filter(String::isNotBlank)
        .joinToString("\n")

    val reportedAssets = outcome.delta.discoverAssets
        .map(::normalizeRecoveredAssetV5)
        .filterNot(::isNarrativeGarbageAssetV5)
    val proseAssets = recoverConcreteAssetsFromAcquisitionProseV5(source)
    val additions = mergeRecoveredAssetsV5(reportedAssets, proseAssets).take(64)

    val changes = outcome.inventoryChanges
        .map { change ->
            val c = classifyRecoveredInventoryV5(change.title, change.detail, change.tag, change.kind)
            change.copy(
                kind = c.kind,
                tag = mergeRecoveredTagsV5(change.tag, c.tag),
            )
        }
        .filterNot { change -> isNarrativeGarbageTitleV5(change.title) }
        .take(64)

    val fields = outcome.reportedStateFields.toMutableSet()
    if (additions != outcome.delta.discoverAssets) fields += "discoverAssets"
    if (changes != outcome.inventoryChanges) fields += "inventoryChanges"

    return outcome.copy(
        text = ensureAcquisitionQuantitiesVisibleV5(outcome.text, additions),
        simulationStateReported = outcome.simulationStateReported || fields.isNotEmpty(),
        reportedStateFields = fields,
        inventoryChanges = changes,
        delta = outcome.delta.copy(discoverAssets = additions),
    )
}

private data class RecoveredClassificationV5(
    val kind: ApocalypseV3AssetKind,
    val tag: String = "",
)

private val acquisitionWordsV5 = listOf(
    "买下", "买了", "购买", "购入", "采购", "结账", "付款后", "拿到", "拿下", "收下", "领取", "交换到", "换到",
    "带走", "搬走", "捡到", "搜到", "找到", "搬进空间", "搬进了空间", "装进空间", "装进了空间", "塞进空间", "塞进了空间",
    "收入空间", "收入了空间", "收进空间", "收进了空间", "存进空间", "存进了空间", "放进空间", "放进了空间", "带进空间", "囤下", "入库",
)
private val acquisitionNegationsV5 = listOf(
    "没买", "没有买", "未买", "买不到", "没拿到", "未拿到", "没找到", "没搜到", "缺货", "暂不买", "不买", "想买",
    "打算买", "准备买", "考虑买", "问价", "询价", "看看价格", "只看了", "只是看", "没有带走", "没带走", "没有收进",
)

private val foodWordsV5 = listOf(
    "罐头", "方便面", "泡面", "面饼", "饼干", "压缩粮", "压缩饼干", "大米", "米袋", "面粉", "挂面", "面条", "面包",
    "火腿", "香肠", "午餐肉", "水饺", "饺子", "馒头", "快餐", "盒饭", "点心", "糕点", "蛋糕", "巧克力", "糖果",
    "能量棒", "肉干", "冻肉", "食品", "粮食", "食用油", "薯片", "零食", "辣条", "坚果", "果干", "海苔", "威化",
    "曲奇", "果冻", "麦片", "燕麦", "泡芙",
)
private val waterWordsV5 = listOf("矿泉水", "纯净水", "饮用水", "瓶装水", "桶装水", "净水", "饮料", "果汁", "汽水", "苏打水", "运动饮料")
private val medicineWordsV5 = listOf(
    "药品", "药物", "退烧药", "止痛药", "消炎药", "抗生素", "抗过敏", "止泻药", "胃药", "感冒药", "维生素", "绷带",
    "纱布", "创可贴", "碘伏", "医用酒精", "消毒液", "止血带", "急救包", "医疗包", "注射器", "口罩",
)
private val combatWordsV5 = listOf(
    "手枪", "步枪", "冲锋枪", "霰弹枪", "猎枪", "狙击枪", "机枪", "卡宾枪", "防爆弹枪", "防暴弹枪", "胶弹枪",
    "枪弹", "胶弹", "橡胶弹", "防暴弹", "弹药", "子弹", "弹丸", "弹匣", "弹夹", "砍刀", "开山刀", "折叠刀", "猎刀",
    "战术刀", "匕首", "军刀", "长刀", "短刀", "斧头", "战斧", "弓箭", "弓弩", "弩箭", "甩棍", "防身棍", "警棍",
    "盾牌", "防暴盾", "防爆盾", "防弹衣", "防刺服", "防暴服", "防爆服", "战术头盔", "防弹头盔", "护甲", "战术背心",
    "防弹背心", "战术护具", "战术手套", "护膝", "护肘",
)
private val householdWordsV5 = listOf(
    "卫生纸", "纸巾", "湿巾", "卫生巾", "牙刷", "牙膏", "洗面奶", "洗发", "护发", "沐浴", "香皂", "肥皂", "洗衣液",
    "洗洁精", "清洁剂", "抹布", "拖把", "扫帚", "毛巾", "浴巾", "衣架", "垃圾袋", "收纳箱", "床单", "被子", "毯子",
    "枕头", "内衣", "内裤", "袜子", "衣物", "外套", "裤子", "鞋子", "拖鞋", "餐具", "筷子", "勺子", "杯子", "饭盒",
)
private val survivalWordsV5 = listOf(
    "背包", "登山包", "行军包", "帐篷", "天幕", "睡袋", "防潮垫", "折叠床", "手电筒", "头灯", "营灯", "指南针", "望远镜",
    "求生哨", "救生衣", "安全绳", "攀岩绳", "登山绳", "登山扣", "滤水器", "净水器", "净水片", "防毒面具", "雨衣", "雨披",
)
private val energyWordsV5 = listOf(
    "汽油", "柴油", "煤油", "燃油", "燃料", "液化气", "燃气", "气罐", "丁烷", "丙烷", "固体燃料", "酒精燃料", "木炭", "煤炭",
    "木柴", "发电机", "太阳能板", "蓄电池", "备用电池", "干电池", "锂电池", "电池组", "储能电源", "户外电源",
)
private val electronicsWordsV5 = listOf(
    "手机", "对讲机", "无线电", "收音机", "卫星电话", "电脑", "笔记本电脑", "平板电脑", "充电宝", "移动电源", "充电器", "数据线",
    "插线板", "电源适配器", "相机", "摄像机", "无人机", "gps", "定位器", "导航仪", "耳机", "麦克风", "监控", "摄像头", "硬盘", "u盘",
)
private val materialWordsV5 = listOf(
    "木板", "木方", "木材", "钢板", "铁板", "钢管", "铁管", "角钢", "型钢", "钢筋", "铁丝", "钢丝", "铁钉", "钉子", "螺丝",
    "螺栓", "螺母", "水泥", "砂浆", "沙子", "砖", "玻璃", "电线", "线缆", "铜线", "胶带", "扎带", "篷布", "零件", "五金件",
)
private val toolWordsV5 = listOf(
    "工兵铲", "锤子", "铁锤", "榔头", "扳手", "螺丝刀", "改锥", "钳子", "电钻", "手电钻", "电锯", "手锯", "锯子", "工具箱",
    "卷尺", "水平尺", "测电笔", "万用表", "电烙铁", "焊机", "铁锹", "铁铲", "铲子", "镐", "剪刀", "管钳", "千斤顶",
)
private val vehicleWordsV5 = listOf("汽车", "轿车", "越野车", "面包车", "货车", "卡车", "皮卡", "摩托车", "摩托", "电动车", "自行车", "三轮车", "拖车", "房车", "快艇")
private val keyWordsV5 = listOf("钥匙", "门禁卡", "门卡", "房卡", "通行证", "权限卡", "身份卡", "密码", "口令", "访问权限")
private val documentWordsV5 = listOf("文件", "档案", "清单", "报告", "日志", "名单", "图纸", "施工图", "说明书", "手册", "合同", "证件", "账本", "笔记")
private val clueWordsV5 = listOf("线索", "情报", "口供", "坐标", "编号", "暗号", "痕迹", "目击", "传闻")

private val numberedItemRegexV5 = Regex(
    """(\d{1,6}|[一二两三四五六七八九十百千]{1,5})\s*(箱|盒|包|袋|瓶|罐|把|根|卷|件|套|桶|提|板|支|个|份|条|双|枚|发|片|粒|台|辆|只|组|面)\s*(?:的)?\s*(.{1,36})""",
)
private val wholePackageRegexV5 = Regex("""整\s*(箱|盒|包|袋|桶|套|件|组)\s*(?:的)?\s*(.{1,36})""")

private fun recoverConcreteAssetsFromAcquisitionProseV5(text: String): List<ApocalypseV3Asset> {
    if (text.isBlank()) return emptyList()
    val recovered = mutableListOf<ApocalypseV3Asset>()
    text.split(Regex("[。！？!?\n]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { sentence ->
            if (!isCompletedAcquisitionSentenceV5(sentence)) return@forEach

            val pieces = sentence
                .split(Regex("[、，,；;]|以及|还有|连同|与|和|及"))
                .map(::cleanRecoveredCandidateV5)
                .filter { it.length in 2..42 }

            pieces.forEach { piece ->
                if (isNarrativeGarbageTitleV5(piece)) return@forEach
                val explicit = parseExplicitQuantityV5(piece)
                val c = classifyRecoveredInventoryV5(piece, "", "", ApocalypseV3AssetKind.Tool)
                val recognized = c.tag != "待分类"
                if (!recognized && explicit == null) return@forEach
                if (!recognized && !looksLikeConcreteUnknownItemV5(piece)) return@forEach
                if (isUmbrellaInventoryNameV5(piece)) return@forEach

                val (quantity, unit, explicitQuantity) = explicit ?: Triple(1, inferUnitV5(c.kind, piece), false)
                recovered += ApocalypseV3Asset(
                    id = UUID.randomUUID().toString(),
                    kind = c.kind,
                    title = removeLeadingQuantityV5(piece).ifBlank { piece }.take(64),
                    detail = buildString {
                        append("根据本幕已经完成的获得行为补记")
                        if (!explicitQuantity) append("；原正文未写明确数量，暂按1个最小占位记录")
                    },
                    quantity = quantity.coerceIn(1, 99_999),
                    tag = mergeRecoveredTagsV5(
                        mergeRecoveredTagsV5(c.tag, if (explicitQuantity) "" else "数量待确认"),
                        "单位=$unit",
                    ),
                )
            }
        }
    return mergeRecoveredAssetsV5(emptyList(), recovered)
}

private fun isCompletedAcquisitionSentenceV5(sentence: String): Boolean {
    if (acquisitionNegationsV5.any(sentence::contains)) return false
    return acquisitionWordsV5.any(sentence::contains)
}

private fun cleanRecoveredCandidateV5(raw: String): String {
    var value = raw.trim()
        .replace(Regex("^(我把|我将|我又把|我再把|然后把|随后把|接着把|被我|这些|那些|全部|一并|统统|都|其中|包括)+"), "")
        .trim()
    acquisitionWordsV5.mapNotNull { stop -> value.indexOf(stop).takeIf { it > 0 } }
        .minOrNull()
        ?.let { value = value.substring(0, it) }
    value = value
        .replace(Regex("(?:都|全部|一并)?(?:被)?(?:我)?(?:收进|收入|装进|塞进|放进|存进|搬进|带进)(?:了)?空间.*$"), "")
        .trim(' ', '的', '：', ':', '（', '）', '(', ')')
    return value.take(72)
}

private fun parseExplicitQuantityV5(piece: String): Triple<Int, String, Boolean>? {
    numberedItemRegexV5.find(piece)?.let { match ->
        val number = parseRecoveredNumberV5(match.groupValues[1]) ?: return@let
        return Triple(number.coerceAtLeast(1), match.groupValues[2], true)
    }
    wholePackageRegexV5.find(piece)?.let { match ->
        return Triple(1, match.groupValues[1], true)
    }
    return null
}

private fun removeLeadingQuantityV5(raw: String): String = raw
    .replace(
        Regex("""^(?:\d{1,6}|[一二两三四五六七八九十百千]{1,5})\s*(?:箱|盒|包|袋|瓶|罐|把|根|卷|件|套|桶|提|板|支|个|份|条|双|枚|发|片|粒|台|辆|只|组|面)\s*(?:的)?\s*"""),
        "",
    )
    .replace(Regex("""^整\s*(?:箱|盒|包|袋|桶|套|件|组)\s*(?:的)?\s*"""), "")
    .trim()

private fun normalizeRecoveredAssetV5(asset: ApocalypseV3Asset): ApocalypseV3Asset {
    val c = classifyRecoveredInventoryV5(asset.title, asset.detail, asset.tag, asset.kind)
    return asset.copy(
        kind = c.kind,
        tag = mergeRecoveredTagsV5(asset.tag, c.tag),
    )
}

private fun classifyRecoveredInventoryV5(
    title: String,
    detail: String,
    tag: String,
    original: ApocalypseV3AssetKind,
): RecoveredClassificationV5 {
    val text = "$title $detail $tag".lowercase()
    fun has(words: List<String>) = words.any(text::contains)

    if (original == ApocalypseV3AssetKind.Map) return RecoveredClassificationV5(ApocalypseV3AssetKind.Map)
    if (original == ApocalypseV3AssetKind.Core || text.contains("晶核")) return RecoveredClassificationV5(ApocalypseV3AssetKind.Core)
    if (has(waterWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Water)
    if (has(foodWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Food)
    if (has(medicineWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Medicine)
    if (has(combatWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Weapon, "战斗")
    if (has(vehicleWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Vehicle)
    if (has(keyWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Key)
    if (has(documentWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Document)
    if (has(clueWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Clue)
    if (has(energyWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Tool, "能源燃料")
    if (has(electronicsWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Tool, "电子通讯")
    if (has(survivalWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Tool, "生存装备")
    if (has(householdWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Tool, "生活用品")
    if (has(materialWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Material)
    if (has(toolWordsV5)) return RecoveredClassificationV5(ApocalypseV3AssetKind.Tool, "工具")

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
        ApocalypseV3AssetKind.Core -> RecoveredClassificationV5(original)
        ApocalypseV3AssetKind.Clue -> if (text.contains("线索") || text.contains("情报") || text.contains("坐标")) {
            RecoveredClassificationV5(ApocalypseV3AssetKind.Clue)
        } else {
            RecoveredClassificationV5(ApocalypseV3AssetKind.Tool, "待分类")
        }
        ApocalypseV3AssetKind.Tool -> RecoveredClassificationV5(
            ApocalypseV3AssetKind.Tool,
            if (tag.split('；', ';').any { it.trim() in setOf("战斗", "生活用品", "生存装备", "能源燃料", "电子通讯", "工具") }) "" else "待分类",
        )
    }
}

private fun isNarrativeGarbageAssetV5(asset: ApocalypseV3Asset): Boolean =
    isNarrativeGarbageTitleV5(asset.title) && asset.tag.contains("待分类")

private fun isNarrativeGarbageTitleV5(value: String): Boolean {
    val text = value.trim()
    if (text.length < 2) return true
    return listOf(
        "捏在手里", "拿在手里", "握在手里", "眉开眼笑", "道了声谢", "说了声谢", "随手一挥", "便将其", "将其",
        "说道", "说着", "笑着", "接过", "递给", "转身", "点头", "看着", "望着", "走向", "走出", "走进", "终于", "已经",
        "老板", "店员", "我说", "他说", "她说", "我们", "他们", "付款", "结账", "空间里", "车里", "店里", "仓库里",
    ).any(text::contains)
}

private fun looksLikeConcreteUnknownItemV5(value: String): Boolean = listOf(
    "设备", "装备", "用品", "工具", "器", "机", "仪", "灯", "箱", "柜", "架", "包", "袋", "瓶", "罐", "盒", "桶",
    "衣", "服", "鞋", "帽", "盔", "甲", "盾", "枪", "弹", "刀", "剑", "弓", "弩", "棍", "锤", "钳", "铲", "锯",
    "板", "管", "线", "绳", "网", "布", "膜", "胶", "卡", "钥匙", "表", "杯", "锅", "炉", "床", "椅", "桌",
    "药", "粮", "食品", "饮料", "零食", "材料", "配件", "零件", "电池", "电源", "燃料", "模块",
).any(value::contains)

private fun isUmbrellaInventoryNameV5(value: String): Boolean {
    val key = removeLeadingQuantityV5(value).replace("的", "").trim()
    return key in setOf(
        "物资", "装备", "战术装备", "防暴装备", "防爆装备", "枪械", "枪支", "武器", "弹药装备", "食品", "食物", "工具", "材料", "生活用品",
    )
}

private fun inferUnitV5(kind: ApocalypseV3AssetKind, title: String): String = when (kind) {
    ApocalypseV3AssetKind.Water -> if (title.contains("桶装")) "桶" else "瓶"
    ApocalypseV3AssetKind.Food -> when {
        title.contains("罐头") || title.contains("罐装") -> "罐"
        title.contains("方便面") || title.contains("泡面") || title.contains("饼干") || title.contains("薯片") || title.contains("零食") -> "包"
        else -> "份"
    }
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

private fun mergeRecoveredAssetsV5(
    first: List<ApocalypseV3Asset>,
    second: List<ApocalypseV3Asset>,
): List<ApocalypseV3Asset> {
    val merged = first.toMutableList()
    second.forEach { incoming ->
        val index = merged.indexOfFirst { existing -> sameRecoveredTitleV5(existing.title, incoming.title) }
        if (index < 0) {
            merged += incoming
        } else {
            val existing = merged[index]
            val preferIncomingKind = existing.kind == ApocalypseV3AssetKind.Clue && incoming.kind != ApocalypseV3AssetKind.Clue
            merged[index] = existing.copy(
                kind = if (preferIncomingKind) incoming.kind else existing.kind,
                quantity = maxOf(existing.quantity, incoming.quantity),
                detail = mergeRecoveredDetailV5(existing.detail, incoming.detail),
                tag = mergeRecoveredTagsV5(existing.tag, incoming.tag),
            )
        }
    }
    return merged
}

private fun sameRecoveredTitleV5(first: String, second: String): Boolean {
    fun key(value: String) = removeLeadingQuantityV5(value)
        .lowercase()
        .replace(Regex("""[\s·•，,。；;：:（）()\[\]【】_-]+"""), "")
        .replace("整箱", "")
        .replace("箱装", "")
        .replace("整盒", "")
        .replace("盒装", "")
        .replace("的", "")
    val a = key(first)
    val b = key(second)
    if (a.isBlank() || b.isBlank()) return false
    return a == b || (minOf(a.length, b.length) >= 4 && (a.contains(b) || b.contains(a)))
}

private fun ensureAcquisitionQuantitiesVisibleV5(
    text: String,
    additions: List<ApocalypseV3Asset>,
): String {
    if (text.isBlank() || additions.isEmpty()) return text
    val physical = additions.filterNot { it.kind in setOf(ApocalypseV3AssetKind.Clue, ApocalypseV3AssetKind.Document, ApocalypseV3AssetKind.Map) }
    if (physical.isEmpty()) return text
    val missing = physical.filterNot { textShowsQuantityForAssetV5(text, it) }
    if (missing.isEmpty()) return text

    val audit = missing.joinToString("、") { asset ->
        val unit = Regex("""(?:^|[；;])单位=([^；;]+)""")
            .find(asset.tag)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: inferUnitV5(asset.kind, asset.title)
        if (asset.tag.contains("数量待确认")) {
            "${asset.title}（原文数量未明确，暂记${asset.quantity}${unit}，待后续确认）"
        } else {
            "${asset.title}${asset.quantity}${unit}"
        }
    }
    return text.trimEnd() + "\n\n【旁白】入库清点：$audit。"
}

private fun textShowsQuantityForAssetV5(text: String, asset: ApocalypseV3Asset): Boolean {
    val title = removeLeadingQuantityV5(asset.title).trim()
    if (title.length < 2) return false
    var index = text.indexOf(title, ignoreCase = true)
    val quantityPattern = Regex(
        """(?:\d{1,6}|[一二两三四五六七八九十百千]+)\s*(?:箱|盒|包|袋|瓶|罐|把|根|卷|件|套|桶|提|板|支|个|份|条|双|枚|发|片|粒|台|辆|只|组|面)""",
    )
    while (index >= 0) {
        val start = (index - 18).coerceAtLeast(0)
        val end = (index + title.length + 22).coerceAtMost(text.length)
        if (quantityPattern.containsMatchIn(text.substring(start, end))) return true
        index = text.indexOf(title, startIndex = index + title.length, ignoreCase = true)
    }
    return false
}

private fun mergeRecoveredTagsV5(first: String, second: String): String =
    (first.split('；', ';') + second.split('；', ';'))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("；")
        .take(120)

private fun mergeRecoveredDetailV5(first: String, second: String): String = when {
    first.isBlank() -> second.take(360)
    second.isBlank() -> first.take(360)
    first.contains(second) -> first.take(360)
    second.contains(first) -> second.take(360)
    else -> "$first；$second".take(360)
}

private fun parseRecoveredNumberV5(raw: String): Int? {
    raw.toIntOrNull()?.let { return it }
    val digits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    if (raw == "十") return 10
    var total = 0
    var current = 0
    raw.forEach { char ->
        when (char) {
            '千' -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
            '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
            '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
            else -> digits[char]?.let { current = it }
        }
    }
    return (total + current).takeIf { it > 0 }
}
