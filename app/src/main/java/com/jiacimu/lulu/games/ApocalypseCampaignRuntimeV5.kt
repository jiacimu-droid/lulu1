package com.jiacimu.lulu.games

internal enum class ApocalypseActionWeightV5 {
    Routine,
    Meaningful,
    Critical,
}

internal data class ApocalypseActionPacingV5(
    val weight: ApocalypseActionWeightV5,
    val recommendedMinutes: Int,
    val wantsLongNarration: Boolean,
    val reason: String,
)

internal data class ApocalypseStoryGraphNodeV5(
    val id: String,
    val title: String,
    val phase: String,
    val meaning: String,
    val triggerWindow: String,
    val criticalCharacterIds: List<String> = emptyList(),
    val prerequisiteNodeIds: List<String> = emptyList(),
    val alternateChannels: List<String> = emptyList(),
)

internal data class ApocalypseArcObligationV5(
    val characterId: String,
    val title: String,
    val unresolvedNeed: String,
    val acceptablePayoffs: List<String>,
    val canBeTransferredAfterDeathOrDeparture: Boolean = true,
)

/**
 * The campaign spine is not a railroad. These nodes preserve what the story is about while letting
 * the player reach, miss or transform them through different people, places and systems.
 */
internal fun defaultApocalypseStoryGraphV5(): List<ApocalypseStoryGraphNodeV5> = listOf(
    ApocalypseStoryGraphNodeV5(
        id = "warning_window",
        title = "七日预警与准备窗口",
        phase = "灾前",
        meaning = "玩家拥有信息差和提前觉醒优势，如何使用这七天会真实改变主沉降后的生存基础。",
        triggerWindow = "灾变前7日至灾变前1日",
        alternateChannels = listOf("采购与物流", "据点选择", "人际准备", "调查预警", "完全低调囤货"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "first_impact",
        title = "赤潮主沉降",
        phase = "主沉降",
        meaning = "城市秩序从还能运转转向分区失效；灾前准备必须在这一阶段产生可感知差异。",
        triggerWindow = "灾变第1日至第3日",
        prerequisiteNodeIds = listOf("warning_window"),
        alternateChannels = listOf("留守据点", "主动撤离", "救人", "抢救基础设施", "绕开人群"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "shelter_b8_truth",
        title = "第一避难区与B8真相",
        phase = "灾后早期",
        meaning = "回答最早的预警为何成立，同时改变玩家对旧秩序和灾变来源的判断。",
        triggerWindow = "灾变后数日至数周",
        prerequisiteNodeIds = listOf("first_impact"),
        alternateChannels = listOf("亲自进入B8", "重要NPC带回资料", "监听通信", "接触幸存者", "事后从废墟取得证据"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "stable_base",
        title = "从藏身处到可持续基地",
        phase = "灾后数周",
        meaning = "生存开始从个人资源转向水、电、医疗、生产、分工和对外关系。",
        triggerWindow = "灾后1至8周",
        prerequisiteNodeIds = listOf("first_impact"),
        alternateChannels = listOf("白榆气象观测站", "自建据点", "加入现有聚居地", "多据点网络"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "infected_learning",
        title = "感染者开始学习",
        phase = "灾后中期",
        meaning = "旧有战斗和安全规则逐步失效，敌人从单纯危险变成会利用环境的信息网络。",
        triggerWindow = "灾后数周至数月",
        prerequisiteNodeIds = listOf("first_impact"),
        alternateChannels = listOf("战斗遭遇", "雷达与监控", "其他幸存者报告", "野外痕迹", "研究样本"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "human_orders",
        title = "不同人类秩序竞争",
        phase = "灾后中后期",
        meaning = "玩家开始决定什么样的组织、规则与资源分配方式值得支持。",
        triggerWindow = "灾后2个月以后",
        prerequisiteNodeIds = listOf("stable_base"),
        alternateChannels = listOf("贸易网络", "联盟", "冲突", "自治", "救援体系", "技术交换"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "red_tide_mechanism",
        title = "赤潮机制与预警来源",
        phase = "后期",
        meaning = "长期谜团回收必须建立在前期证据、角色经历和世界状态之上，而不是临时反转。",
        triggerWindow = "灾后半年至一年半以后",
        prerequisiteNodeIds = listOf("shelter_b8_truth", "infected_learning"),
        alternateChannels = listOf("科研路线", "生态路线", "通信路线", "高阶感染网络", "旧时代资料"),
    ),
    ApocalypseStoryGraphNodeV5(
        id = "new_order_ending",
        title = "新秩序",
        phase = "终局",
        meaning = "结局由基地治理、角色命运、势力关系、真相公开方式和玩家长期选择共同长出来。",
        triggerWindow = "长期终局",
        prerequisiteNodeIds = listOf("human_orders", "red_tide_mechanism"),
        alternateChannels = listOf("修复旧文明", "地方自治网络", "技术共同体", "生态共存", "异能者新秩序", "混合路线"),
    ),
)

/**
 * Important characters should change over time, but the game must protect dramatic meaning rather
 * than force a specific actor to survive until a scripted scene.
 */
internal fun buildApocalypseArcObligationsV5(save: ApocalypseV3Save): List<ApocalypseArcObligationV5> =
    save.director.characterDossiers
        .filter { it.importance == "companion" || it.importance == "important" || it.importance == "core" }
        .map { dossier ->
            ApocalypseArcObligationV5(
                characterId = dossier.id,
                title = "${apocalypseDossierDisplayNameV5(dossier)}的未完成弧线",
                unresolvedNeed = listOf(
                    dossier.privateNeed.takeIf(String::isNotBlank),
                    dossier.fear.takeIf(String::isNotBlank)?.let { "面对：$it" },
                    dossier.contradiction.takeIf(String::isNotBlank)?.let { "矛盾：$it" },
                ).filterNotNull().joinToString("；").ifBlank { "需要通过连续事件形成稳定立场和变化" },
                acceptablePayoffs = listOf(
                    "通过玩家与该角色共同经历的事件改变关系或选择",
                    "角色在离屏行动中作出有因果依据的决定，再由可靠渠道让玩家知道",
                    "角色拒绝、离队或走向不同阵营，使原有剧情意义以另一种结果成立",
                    "角色死亡或永久失联时，将其未完成的信息与影响转移为遗留物、关系网或他人行动，而不是复活硬演原桥段",
                ),
            )
        }

internal fun classifyApocalypseActionPacingV5(
    save: ApocalypseV3Save,
    action: String,
): ApocalypseActionPacingV5 {
    val text = action.trim()
    val criticalWords = listOf(
        "救", "杀", "战斗", "攻击", "开枪", "撤离", "逃跑", "背叛", "表白", "分手", "死亡", "感染",
        "加入", "离开队伍", "B8", "避难区", "真相", "觉醒", "基地迁移", "谈判", "宣战",
    )
    if (criticalWords.any(text::contains)) {
        return ApocalypseActionPacingV5(
            weight = ApocalypseActionWeightV5.Critical,
            recommendedMinutes = 30,
            wantsLongNarration = true,
            reason = "会改变人物命运、阵营、重大风险或长期剧情节点",
        )
    }

    val routineWords = listOf(
        "买", "结账", "采购", "补货", "整理", "收纳", "吃饭", "喝水", "洗澡", "做饭", "加油", "充电",
        "简单检查", "简单维修", "睡觉", "休息", "搬运", "取货", "上厕所", "清点",
    )
    if (routineWords.any(text::contains)) {
        val minutes = when {
            listOf("睡觉", "休息").any(text::contains) -> 360
            listOf("采购", "买", "结账", "取货").any(text::contains) -> 35
            listOf("整理", "收纳", "搬运", "维修").any(text::contains) -> 45
            else -> 20
        }
        return ApocalypseActionPacingV5(
            weight = ApocalypseActionWeightV5.Routine,
            recommendedMinutes = minutes,
            wantsLongNarration = false,
            reason = "优先由游戏系统直接结算；只有出现人物冲突、特殊发现或剧情触发时才升级成长剧情幕",
        )
    }

    val explorationWords = listOf("探索", "调查", "侦察", "前往", "进入", "搜索", "查看", "联系", "拜访", "交易")
    if (explorationWords.any(text::contains)) {
        return ApocalypseActionPacingV5(
            weight = ApocalypseActionWeightV5.Meaningful,
            recommendedMinutes = if (save.director.dayIndex < 0) 45 else 60,
            wantsLongNarration = true,
            reason = "会产生地点、人物或世界状态变化，但不必自动升级成主线高潮",
        )
    }

    return ApocalypseActionPacingV5(
        weight = ApocalypseActionWeightV5.Meaningful,
        recommendedMinutes = 30,
        wantsLongNarration = true,
        reason = "普通自由行动，保留AI演出但避免每次都拖成超长章节",
    )
}

internal fun apocalypseTimeCompressionPolicyV5(save: ApocalypseV3Save): String = when {
    save.director.dayIndex < 0 -> "灾前七天属于高细节阶段，但购物、结账、整理、搬运等日常动作应由系统快速结算；每个游戏日保留约3—7个真正值得长篇演出的事件即可。"
    save.director.dayIndex <= 2 -> "主沉降72小时保持高细节，危险、关系和路线选择可以逐段演出；纯重复搜刮和机械维护不要每次生成完整章节。"
    save.director.dayIndex <= 42 -> "灾后数周进入中等压缩：日常经营可按半天或一天批量推进，重要人物事件、基地变化、探索发现和高风险行动再展开。"
    save.director.dayIndex <= 180 -> "灾后数月进入周尺度推进：允许一口气结算数日常规生产、训练、值守和交易，让AI重点写真正改变局势的事件。"
    else -> "长期阶段以周/月为时间单位。只有人物弧线、势力转折、重大探索、灾害和终局节点需要完整长篇演出，避免为了跑完大纲制造数百个无意义小幕。"
}

internal fun apocalypseCampaignRuntimePromptV5(save: ApocalypseV3Save): String = buildString {
    appendLine("【高自由度游戏运行规则｜世界先于剧情】")
    appendLine("剧情大纲是故事图，不是铁路。必须保留长期主题、重要谜团和人物弧线，但具体发生地点、参与人物、顺序、渠道和结果可以被玩家永久改变。")
    appendLine("重要NPC和同行者使用稳定身份与持续状态；他们离屏也继续生活和行动。角色死亡、离队、失联后不得为了原定桥段强行复活或瞬移，未完成剧情意义应通过遗留物、关系网、其他知情者或永久错过来转移。")
    appendLine("交易、库存、时间、资源、基地设施、地图探索等能够由游戏系统结算的内容，系统结果是权威事实。AI不得重新报价、重新抽库存、重复扣钱或为了戏剧性推翻已经结算的结果。")
    appendLine("${apocalypseTimeCompressionPolicyV5(save)}")
    appendLine("一天不等于固定几十幕；一幕也不固定等于20或30分钟。时间取决于行动本身。日常操作可以短结算，重大事件才展开成长篇。")
    appendLine("故事节点按世界时间、角色状态、证据和玩家行动触发。玩家长期不碰某条主线时，世界和NPC可以自己推进、失败、过期或改变入口，但不能用巧合把同一桥段重新塞回玩家面前。")
}
