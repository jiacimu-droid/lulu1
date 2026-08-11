package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.CharacterSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val APOCALYPSE_ISOLATED_CHARACTER_ID = "__apocalypse_v5_isolated_world__"

private fun apocalypsePlayerSecondaryPromptV5(config: ApocalypseV3Config): String {
    val choice = apocalypsePlayerSecondaryChoiceV5(config)
    val ability = apocalypseAbilityDefinitionV5(choice)
    return if (ability.id == "none") {
        "玩家第二异能槽=未选择。"
    } else {
        "玩家第二异能=${ability.name}；稀有度=${ability.rarity.label}；潜力=${ability.potential}；分化=${choice.branch}。"
    }
}

private fun apocalypsePartyStylePromptV5(
    party: List<CharacterSettings>,
    config: ApocalypseV3Config,
    awakenedCompanionIds: List<String>,
): String = party.joinToString("\n") { character ->
    val choice = companionAbilityChoice(config, character.characterId)
    val ability = apocalypseAbilityDefinitionV5(choice)
    buildString {
        append("- characterId=${character.characterId}；游戏显示名=${character.displayName}；")
        append("风格原始资料=${character.persona.ifBlank { "暂无额外风格资料" }}；")
        when {
            ability.id == "none" -> append("异能硬状态=普通人；不得觉醒或使用任何异能")
            character.characterId in awakenedCompanionIds -> append("异能硬状态=已在保留剧情中觉醒；能力=${ability.name}/${choice.branch}/${ability.rarity.label}/${ability.potential}")
            else -> append("异能硬状态=潜在分化${ability.name}/${choice.branch}，当前尚未觉醒；在正文完成觉醒事件前绝对不能使用")
        }
    }
}

internal fun apocalypsePhaseForDayV5(dayIndex: Int): String = when {
    dayIndex <= -5 -> "秩序正常 · 隐秘准备"
    dayIndex <= -3 -> "零星异常 · 采购仍畅通"
    dayIndex < 0 -> "公开预警 · 最后准备"
    dayIndex == 0 -> "赤潮主沉降"
    dayIndex <= 3 -> "城市失序初期"
    dayIndex <= 42 -> "求生与据点"
    dayIndex <= 180 -> "势力形成期"
    else -> "赤潮新生态"
}

private fun apocalypseSocietyContractV5(dayIndex: Int): String = when {
    dayIndex <= -5 -> "灾前硬状态：城市完全正常运转，官方和公众不知道末世将至。商场、物流、网购、支付、租车和跨区交通可用。大量采购可以引起店员询问、库存或资金等现实摩擦，但禁止以防灾管制、官方限购、封城、征用或全民抢购强行阻止玩家囤货。"
    dayIndex <= -3 -> "灾前硬状态：只有零星异常和局部传闻，城市总体正常。个别缺货可以发生，但必须提供换店、预订、网购、批发市场或其他真实替代路径；禁止全城管制和全面限购。"
    dayIndex < 0 -> "灾前硬状态：公开异常逐渐增加，少数敏感品类可以排队、延迟或有限购，但商业与交通不能整体提前瘫痪；玩家仍应拥有完成最后采购、运输和据点调整的可玩空间。"
    else -> "灾后硬状态：管制、断供和交通失序只能依据当前保留剧情与真实时间逐步发生，不得把尚未发生的世界变化倒写进过去。"
}

internal fun sanitizePrematureWorldFactsV5(dayIndex: Int, facts: List<String>): List<String> {
    if (dayIndex >= 0) return facts
    val impossibleBeforeImpact = listOf("封城", "全面管制", "物资征用", "供应链崩溃", "交通全面停运", "全民抢购")
    val tooEarlyBeforeDayThree = listOf("官方限购", "城市管制", "商场停业")
    return facts.filterNot { fact ->
        impossibleBeforeImpact.any(fact::contains) ||
            (dayIndex <= -3 && tooEarlyBeforeDayThree.any(fact::contains))
    }
}

internal fun apocalypseActionLooksLikeSpeechV5(action: String): Boolean =
    listOf("说", "问", "告诉", "喊", "叫", "回答", "回复", "商量", "请求", "？", "?", "“", "”")
        .any(action::contains)

private fun apocalypsePlayerActionContractV5(action: String): String {
    val looksLikeSpeech = apocalypseActionLooksLikeSpeechV5(action)
    return buildString {
        append("玩家行动因果合同：本幕前25%必须让‘${action.take(180)}’实际发生，不得用新危机、转场、旁白概述或导演主线覆盖它；行动必须至少改变信息、关系、资源、位置、时间或风险中的一项，并在本幕可见。")
        if (looksLikeSpeech) append("这是发言/交流行动：必须出现【玩家】发言段，并让至少一名当前在场的相关人物在同一幕用语言、动作、停顿或态度直接回应其具体内容；不能答非所问，也不能无视后切走。")
    }
}

private fun apocalypseIsolationRuleV5(): String =
    "本作是与露露机主世界完全隔离的独立世界。角色资料只允许提取性格、说话方式、外貌、习惯、情绪表达和行为风格；资料中涉及原身份、职业、时代、阵营、原世界背景、与玩家或其他角色的原关系、聊天经历、共同事件、承诺、记忆、主时间线状态的内容全部视为禁用信息，不能成为本局事实。本局身份、关系和共同经历只能由当前存档内已经发生的剧情建立。"

private fun apocalypseRecentContinuityV5(save: ApocalypseV3Save): String = buildString {
    val earlierScenes = save.log.takeLast(6)
    if (earlierScenes.isNotEmpty()) {
        appendLine("近期行动—结果账本：")
        appendLine(earlierScenes.joinToString("\n"))
    }
    appendLine("第${save.scene}幕衔接尾段：")
    append(save.narration.takeLast(1_600))
}

private fun apocalypseWriterCanonPackV5(save: ApocalypseV3Save): String = buildString {
    val director = save.director
    appendLine("不可改写的近期正史=${director.worldFacts.takeLast(14).joinToString("｜").ifBlank { "暂无新增正史" }}")
    appendLine("当前明线=${director.activeThreads.takeLast(5).joinToString("｜")}")
    appendLine("人物/关系状态=${director.characterArcs.takeLast(8).joinToString("｜")}")
    appendLine(
        "当前在场角色id=" + if (director.presentCharacterStateKnown) {
            director.presentCharacterIds.joinToString("、").ifBlank { "无（玩家独处）" }
        } else {
            "以开局同行为准"
        },
    )
    appendLine("已获得关键资产=${director.assets.takeLast(20).joinToString("｜") { "${it.kind.label}:${it.title}×${it.quantity}" }.ifBlank { "无" }}")
    appendLine("已知地点=${director.locations.takeLast(16).joinToString("｜") { it.name }}")
}

/**
 * The long-form director is intentionally periodic. Ordinary dialogue and small continuations reuse
 * its latest blueprint; state-changing or structurally risky actions wake it immediately.
 */
internal fun shouldPlanApocalypseV5Beat(save: ApocalypseV3Save, action: String): Boolean {
    val nextScene = save.scene + 1
    if (
        save.director.directorRefreshNeeded || nextScene % 6 == 0 ||
        save.director.tension >= 8 || save.director.longTermPlan.size < 8
    ) return true
    if (save.director.foreshadowLedger.any { it.stage == "ripe" }) return true
    // Local purchases, searches, fights, conversations and training are persisted by the writer's
    // one-call scene receipt. Wake the expensive director only when the long blueprint itself may move.
    val structuralSignals = listOf(
        "跨市", "离开临江", "搬迁基地", "放弃基地", "全员撤离", "建立新基地",
        "招募入队", "加入队伍", "离开队伍", "逐出队伍", "背叛", "处决同伴",
        "加入势力", "拒绝势力", "摧毁设施", "炸毁", "改变长期目标", "重大决定",
        "跳过一天", "睡到明天", "等待末世", "揭开真相", "最终决定",
    )
    return structuralSignals.any(action::contains) || action.length >= 360
}

/** A zero-cost beat for scenes that do not need the director model to rewrite the long plan. */
internal fun continueApocalypseV5Beat(save: ApocalypseV3Save, action: String): ApocalypseV3Beat {
    val minutesPassed = 20
    val absoluteMinutes = save.director.clockMinutes + minutesPassed
    val nextDayIndex = (save.director.dayIndex + absoluteMinutes / 1440).coerceAtMost(9999)
    val nextDirector = save.director.copy(
        phase = apocalypsePhaseForDayV5(nextDayIndex),
        sceneGoal = "承接玩家行动“${action.take(90)}”，推进当前场景中的人物、信息或现实后果。",
        recentBeatTypes = (save.director.recentBeatTypes + "continuation").takeLast(8),
        dayIndex = nextDayIndex,
        clockMinutes = absoluteMinutes % 1440,
    )
    return ApocalypseV3Beat(
        nextDirector = nextDirector,
        beatType = "continuation",
        directive = "沿用总导演最近的长期蓝图，不重排主线；完整执行玩家行动，让当前人物关系或信息至少向前移动一步。",
        worldDelta = "世界继续按既有状态运行，没有凭空发生新的重大结构变化。",
        openingHook = "直接承接上一幕尚未完成的动作与玩家刚才的选择。",
        pressureEscalation = "从当前环境或人物目标中产生一个轻微但具体的阻力。",
        emotionalTurn = "让一名在场人物通过行动或措辞显露态度变化。",
        closingHook = "停在由本幕因果自然形成的新行动节点。",
        sceneValueShift = "停滞→向前一步",
        minutesPassed = minutesPassed,
    )
}

internal data class ApocalypsePlanResultV5(
    val beat: ApocalypseV3Beat,
    val directorApplied: Boolean,
)

internal suspend fun planApocalypseV5Beat(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
): ApocalypsePlanResultV5 {
    val director = save.director
    val nextScene = save.scene + 1
    val partyPrompt = apocalypsePartyStylePromptV5(party, config, director.awakenedCompanionIds)
    val facts = buildString {
        appendLine("互动长篇：《末世求生·赤潮纪元》")
        appendLine("【最高优先输入】玩家行动：$action")
        appendLine(apocalypseIsolationRuleV5())
        appendLine("世界模式：${config.worldMode}")
        appendLine(playerSpacePrompt(save.stats))
        appendLine(apocalypsePlayerSecondaryPromptV5(config))
        appendLine("异能人口规则：除玩家这一特殊提前觉醒者外，稳定异能从赤潮主沉降后才逐步形成；最终硬基线约8%，约92%没有稳定异能。")
        appendLine(apocalypseSocietyContractV5(director.dayIndex))
        appendLine(apocalypsePlayerActionContractV5(action))
        appendLine(apocalypseWorldGeographyPromptV5())
        appendLine(apocalypseCinematicDirectorBibleV5(save))
        appendLine("玩家已确认的地图变化账本：${apocalypseMapLedgerPromptV5(save)}")
        appendLine("时间=${apocalypseDayLabelV5(director.dayIndex)} ${apocalypseClockLabelV5(director.clockMinutes)}；天气=${director.weather}；温度=${director.temperatureC}℃")
        appendLine("玩家状态：生命${save.stats.health}/100；体力${save.stats.stamina}/100；感染${save.stats.infection}/100；士气${save.stats.morale}/100")
        appendLine("阶段=${director.phase}；地点=${director.location}；当前已读到第${save.scene}幕；本次必须规划第${nextScene}幕；威胁=${director.tension}/10")
        appendLine("资源：资金¥${save.stats.money} 食物${save.stats.food} 水${save.stats.water} 药物${save.stats.medicine} 材料${save.stats.materials} 晶核${save.stats.crystalCores}")
        appendLine("基地=${save.stats.baseName}/Lv.${save.stats.baseLevel}")
        val presentIds = if (director.presentCharacterStateKnown) director.presentCharacterIds else save.partyIds
        appendLine("当前在场角色id=${presentIds.joinToString("、").ifBlank { "无（玩家独处）" }}")
        appendLine("当前明线：${director.activeThreads.joinToString("｜")}")
        appendLine("隐藏长期线：${director.hiddenThreads.joinToString("｜")}")
        appendLine("长期剧情蓝图：${director.longTermPlan.joinToString("｜")}")
        appendLine("势力状态：${director.factionStates.joinToString("｜")}")
        appendLine("人物长期弧：${director.characterArcs.joinToString("｜")}")
        appendLine("伏笔回收计划：${director.foreshadowPlan.joinToString("｜")}")
        appendLine("已确认世界事实：${director.worldFacts.takeLast(24).joinToString("｜")}")
        appendLine("已知地点：${director.locations.joinToString("｜") { it.name }}")
        appendLine("已获得资产：${director.assets.joinToString("｜") { "${it.kind.label}:${it.title}" }}")
        appendLine("同行角色风格参考（只按隔离规则提取风格，不继承其中身份/关系/经历）：\n$partyPrompt")
        appendLine("本局仍保留的连续剧情（这是唯一可用的跨幕共同经历）：\n${apocalypseRecentContinuityV5(save)}")
    }
    val instruction = """
        你是长篇互动游戏《末世求生》的隐藏总导演。你不写正文，只维护世界、长线剧情和下一幕导演意图。只返回 JSON，不加代码块。

        【世界隔离是最高优先级】
        - 这是独立游戏世界，不是露露机主世界的延续、梦境、番外或平行记忆回放。
        - 不得调用、猜测或补全主聊天、主时间线、辞海、世界书、角色长期记忆、共同活动、承诺、原世界身份和原关系。
        - 同行角色只继承输入资料里的性格、说话方式、外貌、习惯、情绪表达与行为风格。即使风格原始资料里写了职业、身份、关系或过去经历，也必须忽略这些部分。
        - 角色在本局里的身份、关系、经历只由当前存档的世界事实、导演状态、保留剧情记录和上一幕决定。已经从存档删除的剧情不得靠推测重建。

        返回字段：phase, location, sceneGoal, beatType, tension, activeThreads, hiddenThreads, worldFacts,
        longTermPlan, factionStates, characterArcs, foreshadowPlan, characterDossiers, foreshadowLedger,
        worldDelta, directive, openingHook, pressureEscalation, emotionalTurn, closingHook, sceneValueShift,
        focusCharacterIds, presentCharacterIds:[characterId], foreshadowMoves, awakenCompanionIds:[characterId],
        moneyDelta, foodDelta, waterDelta, medicineDelta, materialsDelta, coresFound, playerAbilityXpGain, baseDelta,
        healthDelta, staminaDelta, infectionDelta, moraleDelta, minutesPassed, weather, temperatureC,
        unlockLocations:[{id,name,detail,unlocked}], discoverAssets:[{id,kind,title,detail,quantity,tag}]。
        kind只能 food|water|medicine|material|tool|weapon|vehicle|key|document|clue|map|core。

        characterDossiers是本幕新增或发生变化的角色编剧档案数组；未变化人物无需重复返回，返回的人物必须给出完整对象。每项字段为：
        {id,name,storyRole,publicGoal,privateNeed,fear,secret,contradiction,bottomLine,relationshipWeb,arcStage,lastAdvancedScene,status}。
        同行者沿用其characterId；原创长期NPC使用稳定id（如npc_luo_yan），后续不得换id或换名。只返回真正会持续出现或影响剧情的人物，最多24名。
        foreshadowLedger是本幕新增或发生变化的伏笔状态数组；未触碰伏笔无需重复返回，返回的伏笔必须给出完整对象。每项字段为：
        {id,title,hiddenTruth,visibleEvidence,surfaceMeaning,stage,plantedScene,lastTouchedScene,targetPayoffStart,targetPayoffEnd,payoffConsequence,linkedCharacterIds}。
        stage只能seeded|echoed|distorted|ripe|paid_off|abandoned；最多18条。visibleEvidence只能写玩家在保留剧情中真正见过的证据。

        导演原则：
        1. 必须提前维护数月到数年的剧情蓝图、势力状态、人物弧和伏笔回收计划，而不是每一幕临时编。
        2. 蓝图不是铁路。玩家可以拒绝、绕路、提前发现、救人或毁掉原计划；发生后承认事实并重排未来。
        3. 每幕推进一个核心戏剧动作，最多顺带推进一条暗线。2—4幕让旧细节产生新意义，6—10幕才安排真正改变局面的回收或反转。
        4. 重大反转必须有可回看的依据，禁止凭空失忆、万能组织、无缘由背叛和突然救世主。
        5. 玩家是目前唯一已确认的灾前提前觉醒者，这是长期谜团和主角特殊性。其他人的稳定异能只能在赤潮主沉降后逐步形成，最终硬基线约8%，约92%仍是普通人。末世淘汰可提高幸存者中的异能者比例，但除特殊据点外仍不应成为多数。
        6. 玩家第一异能固定为空间系高稳定共鸣；玩家可以另外拥有一个第二异能槽。两种异能都是硬设定，不能无故遗忘、替换或突然出现第三种能力。
        7. 空间系成长：Lv1不能稳定空间刃；Lv2裂隙刃雏形；Lv3闪位与稳定空间刃；Lv4空间锁/裂隙陷阱；Lv5领域。第二异能同样必须遵守它自己的分化与代价。
        8. 赤潮同时影响植物、动物、土壤、水体、气候、人类和感染者；剧情不能退化成只有丧尸。
        9. 感染者进化有时间尺度，越高阶越稀少。晶核必须真实获取，不能当自动掉落金币。
        10. 生存资源、运输、燃料、卫生、睡眠、基地维护都要有现实约束；空间异能可以显著改善搬运和保存，但不能凭空创造物资。
        11. 同行者必须有独立欲望和风险判断，不能全员围着玩家说同一种话；同行角色的异能与分化是硬设定，普通人不能突然觉醒。
        11a. 同行设置中的异能只是灾后潜在分化，不等于开局已经觉醒。灾前dayIndex<0时awakenCompanionIds必须为空，同行者绝不能使用异能。dayIndex>=0后，每幕最多让一名已配置非普通人潜能的同行者觉醒；必须把其characterId放入awakenCompanionIds，并在directive要求正文先完整演出异常征兆、失控或确认能力的过程，之后才能首次主动使用。已经在“已觉醒列表”的角色无需重复觉醒。普通人永远不能加入该列表。
        12. 东澜地区六市的相对方位、资源定位与交通距离是硬地理设定。跨市移动必须经历真实路程与风险；临江市只是开局城市，不是整个世界。
        13. 每次行动必须估算真实耗时并返回minutesPassed：简单整理5—30分钟，搜楼/战斗30—180分钟，跨区数小时，跨市通常3—10小时。时间推进后天气、照明、疲劳和风险都要跟着变化。
        14. health/stamina/infection/morale都是0—100。受伤降低health；奔跑、战斗、熬夜降低stamina；赤雨、伤口污染和感染者体液提高infection；成功、休息、关系支持可提高morale。不要无缘无故大幅波动。
        15. foodDelta/waterDelta必须包含真实消耗与搜集的净变化；长时间行动不能人人永远不喝水不吃东西。基地等级越高，休息恢复、净水、医疗和长期生产才越可靠。
        15a. moneyDelta是本幕真实发生的资金净变化，单位为元：购买、付款写负数，出售、报酬、退款写正数；没有明确交易就必须为0，绝不能为了配合物资变化凭空扣钱或加钱。余额不足时不能完成超额购买。灾前和灾变初期金钱仍有效；供应链崩溃后保留余额，但交易价值应逐步让位于物资、信用、票证和劳务。
        16. 故事必须有“电影呼吸”：连续高压2—3幕后安排低压段落，让人物做饭、疗伤、守夜、修车、谈心、争执或整理遗物；安静段也必须推进关系或信息。
        17. 每4—7幕至少让一个旧细节获得新意义；每8—14幕才允许一次真正改变局面的阶段性大场面或重大回收。不要每幕反转，也不要连续十几幕没有任何兑现。
        18. 同时维护生存层、人际/势力层、长期谜团层。单幕突出1—2层即可，第三层只留可回看的微小痕迹，避免百科全书式解释。
        19. 设计经典末世电影式场面时只借用“类型结构”而非具体作品：撤离、围困、夜间搜救、车队、桥隧、停电、暴雨、港口、山路、临时安全区等都必须由当前地理与资源状态自然触发。
        20. 重要NPC必须拥有独立于玩家的目标、恐惧、关系和底线；至少维持若干NPC彼此之间的关系线。秘密不等于背叛，死亡不等于深刻。禁止靠随机杀熟和强行黑化制造刺激。
        21. 安全区、组织和联盟可以可靠一段时间。若它们后来出问题，原因应来自资源、制度、隐瞒、误判、研究伦理或立场冲突，并提前留下证据，而不是“其实全员坏人”。
        22. 保留希望和生活感：热饭、修好的灯、重新接通的电台、生日、第一茬蔬菜、陌生人的善意都可以成为重要剧情。黑暗只有和可失去的美好并存才有重量。
        23. 长期蓝图、隐藏线、人物秘密和伏笔回收计划都是导演私密信息，绝不能在正文中直接列出、解释或提前剧透；玩家只能通过当下可观察的事件逐步发现。
        24. 地图不是静态设定图。建筑可受损、坍塌、停运、被势力占领或被重新修复；道路可封锁后再清障；空置城区可逐步被赤潮植物、水体或菌毯重塑。变化必须来自真实事件与时间，不得为了“末世感”随机毁建筑。
        25. 当且仅当某个地图变化已经会在本幕被玩家亲眼看到、可靠侦查或可信证据确认时，把它加入worldFacts，格式严格为：MAP_KNOWN|PLACE|城市名|地点名|状态|简短原因；路线用MAP_KNOWN|ROUTE|区域/城市|路线名|状态|简短原因；区域生态/势力边界用MAP_KNOWN|ZONE|城市名|区域名|状态|简短原因。新增条目必须同时写入directive，保证正文让玩家实际获得这条信息。
        26. MAP_KNOWN是地图长期账本：后续规划必须保留已有条目；同一目标发生新变化时用新的同目标条目覆盖旧状态。隐藏的远处事故、导演私密情报和未经证实的传闻绝不能标成MAP_KNOWN。地图状态既可以恶化，也可以因清障、维修、重建、夺回据点而改善。
        27. 每幕必须有可执行的戏剧节拍：openingHook在前150字内制造具体异常/欲望/问题；pressureEscalation让阻力或代价升级；emotionalTurn改变至少一段关系或玩家对局势的感受；closingHook留下迫近后果、两难信息或新的行动欲望。钩子不能靠凭空枪响、陌生电话、突然昏迷反复套模板。
        28. sceneValueShift必须写清本幕从什么情绪/关系/处境价值转向什么价值，例如“戒备→勉强信任”“希望→带代价的希望”。没有价值变化的场景视为无效场景，安静戏也必须发生微小但不可逆的改变。
        29. characterDossiers不是人物简历，而是魅力发动机。重要人物必须同时具备能力与缺口、吸引力与危险性、公开欲望与不愿承认的需求；秘密不必邪恶，矛盾不能被一幕解释完。每幕只聚焦1—2人，其他人保持自己的事务，禁止全员轮流表态。
        30. 新长期NPC登场前先给独特行动选择、声音/动作识别点和与主线无关的私人目标；不要用外貌形容词代替魅力。角色的高光必须来自他在压力下做出只有他会做的选择。
        31. 伏笔按seeded→echoed/distorted→ripe→paid_off推进。每次foreshadowMoves必须写“伏笔id:动作:本幕玩家能观察到的具体细节”。回收应同时完成答案、情绪冲击与现实后果；只口头解释真相不算回收。过了targetPayoffEnd仍不处理属于失约，除非玩家选择使其失效并标记abandoned及合理去向。
        32. 高潮不是单纯提高tension或增加敌人。本幕若为阶段高潮，必须让至少两条此前独立的线发生碰撞，迫使人物做代价明确的选择，并永久改变关系、据点、势力、地图、谜团认知或长期目标；高潮后必须安排后果戏，不能立刻刷新更大危机。
        33. 只输出本幕真正需要的一个openingHook和一个closingHook。不要每幕都用反转结尾；悬念、情感余震、承诺、倒计时、发现、艰难选择或短暂胜利都可以成为不同类型的钩子，最近使用过的节拍和情绪转折应避免重复。
        34. phase不得自由发挥，必须与输入中的真实dayIndex一致。灾前七天是按真实时间流逝的完整游玩期，不是七幕倒计时；不得为了制造紧张提前进入全面管制、封城、征用或供应链崩溃。
        35. presentCharacterIds是本幕结束时与玩家处在同一可直接交流场景中的角色硬状态。人物离开、失散、加入现场或被留在别处必须真实更新；不在场者不能凭空接话、递物或看到现场事件。
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = APOCALYPSE_ISOLATED_CHARACTER_ID,
        facts = facts,
        instruction = instruction,
        source = "末世求生V5导演",
        title = "末世求生 · 导演第${nextScene}幕",
        temperature = 0.72,
        maxTokens = 2800,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).fold(
        onSuccess = { generated ->
            parseApocalypseV5Beat(generated.text, director, save.stats.money, config, party)?.let { beat ->
                ApocalypsePlanResultV5(beat = beat, directorApplied = true)
            } ?: ApocalypsePlanResultV5(beat = fallbackApocalypseV5Beat(save), directorApplied = false)
        },
        onFailure = { ApocalypsePlanResultV5(beat = fallbackApocalypseV5Beat(save), directorApplied = false) },
    )
}

internal suspend fun writeApocalypseV5Scene(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
    beat: ApocalypseV3Beat,
    nextStats: ApocalypseV3Stats,
    usedDirector: Boolean,
): Result<ApocalypseSceneOutcomeV5> {
    val nextScene = save.scene + 1
    val promptDirector = save.director
    val presentIds = if (beat.nextDirector.presentCharacterStateKnown) {
        beat.nextDirector.presentCharacterIds
    } else {
        party.map { it.characterId }
    }
    val relevantParty = party.filter { character ->
        character.characterId in presentIds ||
            character.characterId in beat.focusCharacterIds ||
            action.contains(character.displayName, ignoreCase = true)
    }.ifEmpty { party.take(2) }
    val partyPrompt = apocalypsePartyStylePromptV5(relevantParty, config, beat.nextDirector.awakenedCompanionIds)
    val newlyAwakened = beat.nextDirector.awakenedCompanionIds - save.director.awakenedCompanionIds.toSet()
    val writerCastIds = (presentIds + beat.focusCharacterIds).toSet()
    val writerDossiers = beat.nextDirector.characterDossiers
        .filter { it.id in writerCastIds }
        .take(8)
    val facts = buildString {
        appendLine(apocalypseIsolationRuleV5())
        appendLine("【最高优先输入】玩家行动原文：$action")
        appendLine("客户端会在正文前单独显示这条玩家行动；正文不要机械复述原句，要立刻演出人物和世界对它的具体回应。")
        appendLine("行动开始时阶段：${promptDirector.phase}；地点：${promptDirector.location}；威胁：${promptDirector.tension}/10")
        appendLine(playerSpacePrompt(nextStats))
        appendLine(apocalypsePlayerSecondaryPromptV5(config))
        appendLine("异能人口规则：玩家是唯一已确认的灾前提前觉醒者；其他稳定异能在主沉降后才逐步形成，最终约8%，约92%仍是普通人。")
        appendLine(apocalypseSocietyContractV5(promptDirector.dayIndex))
        appendLine(apocalypsePlayerActionContractV5(action))
        appendLine("同行异能硬状态：已觉醒=${beat.nextDirector.awakenedCompanionIds.joinToString().ifBlank { "无" }}；本幕新觉醒=${newlyAwakened.joinToString().ifBlank { "无" }}。")
        appendLine(apocalypseWorldGeographyPromptV5())
        appendLine("行动开始时间：${apocalypseDayLabelV5(promptDirector.dayIndex)} ${apocalypseClockLabelV5(promptDirector.clockMinutes)}；天气=${promptDirector.weather} ${promptDirector.temperatureC}℃")
        if (usedDirector) {
            appendLine("导演规划的幕末时空：${apocalypseDayLabelV5(beat.nextDirector.dayIndex)} ${apocalypseClockLabelV5(beat.nextDirector.clockMinutes)}；地点=${beat.nextDirector.location}")
        }
        appendLine("行动前资源：资金¥${save.stats.money} 食${save.stats.food} 水${save.stats.water} 药${save.stats.medicine} 材料${save.stats.materials} 晶核${save.stats.crystalCores}")
        appendLine("玩家状态：生命${nextStats.health} 体力${nextStats.stamina} 感染${nextStats.infection} 士气${nextStats.morale}")
        appendLine("导演预结算后资源：资金¥${nextStats.money} 食${nextStats.food} 水${nextStats.water} 药${nextStats.medicine} 材料${nextStats.materials} 晶核${nextStats.crystalCores}；基地=${nextStats.baseName}/Lv.${nextStats.baseLevel}")
        appendLine("结算模式：${if (usedDirector) "导演已完成硬状态结算；状态回执不得再次重复增减" else "本幕跳过导演；你必须在同一次输出的状态回执中如实结算行动后果"}")
        appendLine("导演动作：${beat.beatType}；本幕目标：${beat.nextDirector.sceneGoal}")
        appendLine("世界变化：${beat.worldDelta}")
        appendLine("导演执行指令：${beat.directive}")
        appendLine("开场钩子：${beat.openingHook}")
        appendLine("压力升级：${beat.pressureEscalation}")
        appendLine("情绪转折：${beat.emotionalTurn}")
        appendLine("场景价值变化：${beat.sceneValueShift}")
        appendLine("结尾钩子：${beat.closingHook}")
        appendLine("本幕聚焦角色：${beat.focusCharacterIds.joinToString("、")}")
        appendLine("本幕伏笔动作：${beat.foreshadowMoves.joinToString("｜")}")
        appendLine("本幕角色编剧档案（只用于维持独特动机与潜台词；不得超出导演允许的信息预算）：\n${apocalypseCharacterDossiersPromptV5(writerDossiers)}")
        appendLine("当前实际在场角色id：${presentIds.joinToString("、").ifBlank { "无" }}")
        appendLine("本幕相关角色风格参考（只按隔离规则提取风格，不继承其中身份/关系/经历）：\n$partyPrompt")
        appendLine("本局硬状态摘要：\n${apocalypseWriterCanonPackV5(save)}")
        appendLine("本局仍保留的连续剧情：\n${apocalypseRecentContinuityV5(save)}")
    }
    val instruction = """
        紧接第${save.scene}幕，写第${nextScene}幕高质量中文末世互动视觉小说，约750—1200字。不要输出选项、数值面板、解释或Markdown。

        【输出协议｜状态块不会展示给玩家】
        必须严格先输出以下两个标记；标记之间只能放一个合法、单行JSON对象，正文放在第二个标记之后：
        $APOCALYPSE_SCENE_STATE_MARKER_V5
        {"actionAcknowledged":true,"actionOutcome":"行动造成的具体可见结果","continuitySummary":"供下一幕使用的短正史摘要","respondedCharacterIds":[],"directorRefreshNeeded":false,"location":"幕末真实地点","sceneGoal":"幕末仍待处理的近期目标","beatType":"关系/探索/生存等","emotionalTurn":"关系或情绪变化","worldFactsAdd":[],"characterStateAdds":[],"presentCharacterIds":[],"weather":"幕末天气","temperatureC":34,"minutesPassed":20,"moneyDelta":0,"foodDelta":0,"waterDelta":0,"medicineDelta":0,"materialsDelta":0,"coresFound":0,"playerAbilityXpGain":0,"baseDelta":0,"healthDelta":0,"staminaDelta":0,"infectionDelta":0,"moraleDelta":0,"discoverAssets":[]}
        $APOCALYPSE_SCENE_TEXT_MARKER_V5
        【旁白】正文……

        - actionOutcome必须准确说明玩家行动实际怎样发生、谁怎样回应、带来什么结果，不能写“剧情继续推进”。
        - continuitySummary用120—240字保留幕末地点、在场人物、未完成动作、关键所得/损失和关系变化，不得写后台秘密。
        - respondedCharacterIds只列本幕真正直接回应玩家发言的角色id，而且正文前半必须实际出现对应【角色:id】段。
        - presentCharacterIds只列幕末与玩家处于同一可直接交流现场的人；不在场者不能接话或看到事件。
        - ${if (usedDirector) "导演已经结算本幕；所有数值delta必须填0，location/在场人物/幕末天气仍须如实报告。" else "本幕没有导演结算；交易、搜集、消耗、受伤、移动、时间、天气和关系变化必须在此状态块真实落账，并与正文完全一致。"}
        - worldFactsAdd最多4条，只记录以后不可装作没发生的事实；characterStateAdds最多3条，只记录可持续的人际/立场变化。临时动作不要滥记。
        - directorRefreshNeeded只有在玩家行动使长期蓝图换轨、重要人物/势力结构改变或阶段目标失效时才为true；普通对话、采购、搜索、战斗和训练不需要。
        - discoverAssets格式为[{"id":"稳定或新id","kind":"food|water|medicine|material|tool|weapon|vehicle|key|document|clue|map|core","title":"名称","detail":"来源与状态","quantity":1,"tag":""}]。

        【世界隔离是最高优先级】
        - 这是独立游戏世界。不得带入露露机主聊天、主时间线、辞海、世界书、角色长期记忆、共同活动、承诺、原身份、原职业、原世界背景或原关系。
        - 同行者只继承提供资料中的性格、说话方式、外貌、习惯、情绪表达与行为风格；风格资料中的身份、关系和过去经历一律忽略。
        - 本局内的人际关系和共同经历只能由当前存档已经保留的剧情形成。已删除的剧情、后果和关系变化不得自行补回。

        【必须遵守的视觉小说格式】
        每个显示段单独一段，并且段首必须是以下三种标记之一：
        【旁白】用于环境、动作、内心、无人直接说话的叙事。
        【玩家】只用于玩家本人正在直接说话的段落。
        【角色:<characterId>】用于同行者或导演演员档案中的人物直接说话，characterId必须从同行者清单或角色编剧档案原样复制。
        同一段只能有一个当前说话人；两个人连续说话必须拆成两段。不要把标记写到句子中间。
        每段通常1—2句，目标30—70字；客户端会在不删减任何文字和标点的前提下继续细分长段落。

        文学与玩法规则：
        - 开头必须承接上一幕最后可见的地点、在场人物、姿态、未完成动作和玩家刚才的行动，不得跳时空、重复开场、总结上一幕或把已发生的事再演一次。
        - 玩家刚才的行动必须真实发生并有现实后果，不能偷换成编剧想让玩家做的事。客户端已经显示行动原文，正文从即时反应和后果开始，不必复述。
        - 玩家行动是本幕第一优先级。开头四分之一内必须回应；如果玩家在说话或提问，让被问者针对具体内容作出可辨认回应，然后才推进其他线。可以迟疑、拒绝、误解或反驳，但不能无视、答非所问或用突发事件打断。
        - 同行者严格保持允许继承的性格、语言、外貌、习惯和行为风格；本局关系只按本局已发生剧情发展。异能按设定和分化使用，普通人不能突然觉醒。
        - 玩家拥有两个异能槽：第一异能固定为空间；第二异能以硬设定为准。没有选择第二异能时绝不能临时补一个。
        - 空间异能当前等级要大胆使用但不能越级；第二异能也不能无代价无限使用。
        - 玩家是当前唯一已确认的灾前提前觉醒者。其他人不得在主沉降前使用异能；灾后稳定觉醒硬基线约8%，普通人约92%，不要写成遍地超能力。
        - 同行异能状态以“已觉醒/本幕新觉醒”清单为唯一准据。未列入已觉醒的同行者不能感知、调用或误打误撞使用其潜在异能。若列入本幕新觉醒，必须先用完整可见事件描写征兆、失控/发现、本人和旁人的反应及确认，不能第一句就熟练使用；灾前绝不允许同行觉醒。
        - 生存细节要有重量：水、保质、药物、燃料、噪音、伤口、睡眠、卫生、天气、车辆和电力会影响选择。
        - 资金余额是硬状态。涉及购买、付款、出售、报酬或退款时，正文金额必须和本幕最终状态回执的余额变化、moneyDelta一致；余额不足不能硬买。秩序崩溃后可以出现“有钱也买不到”，但不能擅自清空余额。
        - 赤潮生态要进入植物、动物、土壤、水和天气，不要只写丧尸。
        - 高潮之间允许做饭、整理物资、赶路、建设、争执、休息和关系沉淀。九死一生要靠积累。
        - 晶核、物资、线索、地图和地点必须在状态回执中同步落账，并在正文写清楚如何得到；没有获得过程不能凭空增加。
        - 东澜六市地理是硬设定；跨市行动必须写出路程、道路、燃料、天气、桥隧和中途风险，不能把不同城市当成同一街区。
        - 时间和身体状态是硬状态：当前时刻、天气、生命、体力、感染、士气必须反映在行动能力和描写里。疲劳时不能像满状态一样连续高强度战斗；高感染要出现现实后果但不能直接无判定宣判死亡。
        - 基地能力按等级逐步解锁：Lv1安全睡眠/储物，Lv2净水/医疗，Lv3供电/工坊，Lv4防御/通信，Lv5持续生产。不能让低级临时据点凭空拥有完整城市功能。
        - 让场景像电影而不是剧情摘要：用可感知的环境变化、动作、停顿、声音、光线和人物反应承载信息；避免角色站着互相讲设定。
        - 对话要有潜台词和人物差异。重要人物可以避而不答、说半句、误解、改口或在行动中表达立场，但不能为了悬念故意全员谜语人。
        - 严格执行开场钩子→压力升级→情绪转折→结尾钩子的因果节拍，但不要把这些词写进正文。开场钩子必须尽早出现；结尾钩子必须从本幕已有因果长出来。
        - 聚焦角色要通过选择、动作、措辞、隐瞒方式和对他人的态度显出魅力与矛盾，禁止用旁白直接总结“他很神秘/强大/温柔”。配角不必人人发言。
        - 伏笔动作只呈现指定的可观察细节，不得把hiddenTruth、后台档案、回收窗口或导演计划直接说出来。回收时让旧细节在行动和后果中获得新意义，不写解释大会。
        - 大场面前先建立空间和目标，大场面后必须留下实际后果；安静场景同样要有情绪、关系或选择上的推进。
        - 不要为了“刺激”频繁杀角色、背叛、抓走队友或突然出现更强怪物。真正的悬念来自已有规则下越来越难的选择。
        - 结尾停在自然可行动节点，不替玩家决定下一步。
    """.trimIndent()

    val firstRaw = LuluAiServices.gateway.generate(
        characterId = APOCALYPSE_ISOLATED_CHARACTER_ID,
        facts = facts,
        instruction = instruction,
        source = "末世求生V5正文",
        title = "末世求生 · 第${nextScene}幕",
        temperature = 0.80,
        maxTokens = 2300,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).getOrElse { return Result.failure(it) }.text.trim()
    val firstOutcome = parseApocalypseSceneOutcomeV5(firstRaw) ?: fallbackApocalypseSceneOutcomeV5(firstRaw)
    val needsRepair = apocalypseSceneOutcomeNeedsRepairV5(
        action = action,
        outcome = firstOutcome,
        party = relevantParty,
        dossiers = writerDossiers,
        presentCharacterIds = presentIds,
    )
    if (!needsRepair) return Result.success(firstOutcome)

    val repairFacts = buildString {
        appendLine(facts)
        appendLine("【上一版不合格正文｜仅供返工，不是已发生正史】")
        append(firstOutcome.text.take(5_000))
    }
    val repairInstruction = instruction + """

        【强制返工原因】上一版缺少合法状态回执、没有真正承接玩家行动，或发言没有得到在场人物回应。整幕重写，不解释返工过程：
        1. 严格输出状态标记、合法单行JSON和正文标记；actionAcknowledged必须与正文事实一致；
        2. 正文开头四分之一直接演出行动后果；若是发言，让正确的在场人物回应具体内容；
        3. 回应必须改变信息、关系、资源、位置、时间或风险至少一项，不能点头后照旧推进旧计划。
    """.trimIndent()
    return LuluAiServices.gateway.generate(
        characterId = APOCALYPSE_ISOLATED_CHARACTER_ID,
        facts = repairFacts,
        instruction = repairInstruction,
        source = "末世求生V5正文返工",
        title = "末世求生 · 第${nextScene}幕返工",
        temperature = 0.68,
        maxTokens = 2200,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).mapCatching { generated ->
        val repaired = parseApocalypseSceneOutcomeV5(generated.text)
            ?: fallbackApocalypseSceneOutcomeV5(generated.text)
        check(
            !apocalypseSceneOutcomeNeedsRepairV5(
                action = action,
                outcome = repaired,
                party = relevantParty,
                dossiers = writerDossiers,
                presentCharacterIds = presentIds,
            ),
        ) { "模型连续两次没有真正回应你的行动，这一幕没有写入存档，请重试。" }
        repaired
    }
}

private fun parseApocalypseV5Beat(
    raw: String,
    previous: ApocalypseV3Director,
    availableMoney: Int,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
): ApocalypseV3Beat? = runCatching {
    val json = JSONObject(extractApocalypseV5Json(raw))
    require(
        json.has("directive") && json.has("minutesPassed") &&
            json.has("worldDelta") && json.has("presentCharacterIds"),
    ) { "导演返回缺少本幕硬状态字段" }
    val locations = json.optJSONArray("unlockLocations").v5Objects { item ->
        ApocalypseV3Location(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name").ifBlank { "未知地点" }.take(70),
            detail = item.optString("detail").take(260),
            unlocked = item.optBoolean("unlocked", true),
        )
    }
    val assets = json.optJSONArray("discoverAssets").v5Objects { item ->
        ApocalypseV3Asset(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = parseApocalypseV5AssetKind(item.optString("kind")),
            title = item.optString("title").ifBlank { "新发现" }.take(70),
            detail = item.optString("detail").take(360),
            quantity = item.optInt("quantity", 1).coerceIn(1, 999),
            tag = item.optString("tag").take(40),
        )
    }
    val minutesPassed = json.optInt("minutesPassed", 30).coerceIn(5, 720)
    val beatType = json.optString("beatType").ifBlank { "continuation" }.take(40)
    val emotionalTurn = json.optString("emotionalTurn").take(320)
    val dossierUpdates = decodeApocalypseCharacterDossiersV5(json.optJSONArray("characterDossiers"))
    val foreshadowUpdates = decodeApocalypseForeshadowLedgerV5(json.optJSONArray("foreshadowLedger"))
    val absoluteMinutes = previous.clockMinutes + minutesPassed
    val dayAdvance = absoluteMinutes / 1440
    val nextClockMinutes = absoluteMinutes % 1440
    val nextDayIndex = (previous.dayIndex + dayAdvance).coerceAtMost(9999)
    val eligibleAwakeningIds = if (nextDayIndex < 0) {
        emptySet()
    } else {
        party.mapNotNull { character ->
            character.characterId.takeIf {
                companionAbilityChoice(config, it).abilityId != "none" && it !in previous.awakenedCompanionIds
            }
        }.toSet()
    }
    val newAwakeningIds = json.optJSONArray("awakenCompanionIds").v5Strings()
        .filter { it in eligibleAwakeningIds }
        .distinct()
        .take(1)
    val mergedDossiers = mergeApocalypseCharacterDossiersV5(previous.characterDossiers, dossierUpdates)
    val validPresentIds = (party.map { it.characterId } + mergedDossiers.map { it.id }).toSet()
    val presentCharacterIds = json.optJSONArray("presentCharacterIds").v5Strings()
        .filter(validPresentIds::contains)
        .distinct()
        .take(10)
    val next = previous.copy(
        phase = apocalypsePhaseForDayV5(nextDayIndex),
        location = json.optString("location").ifBlank { previous.location }.take(100),
        sceneGoal = json.optString("sceneGoal").ifBlank { previous.sceneGoal }.take(260),
        activeThreads = json.optJSONArray("activeThreads").v5Strings().ifEmpty { previous.activeThreads }.take(8),
        hiddenThreads = json.optJSONArray("hiddenThreads").v5Strings().ifEmpty { previous.hiddenThreads }.take(8),
        worldFacts = sanitizePrematureWorldFactsV5(
            nextDayIndex,
            mergeApocalypseWorldFactsV5(previous.worldFacts, json.optJSONArray("worldFacts").v5Strings()),
        ),
        longTermPlan = json.optJSONArray("longTermPlan").v5Strings().ifEmpty { previous.longTermPlan }.take(12),
        factionStates = json.optJSONArray("factionStates").v5Strings().ifEmpty { previous.factionStates }.take(14),
        characterArcs = json.optJSONArray("characterArcs").v5Strings().ifEmpty { previous.characterArcs }.take(14),
        foreshadowPlan = json.optJSONArray("foreshadowPlan").v5Strings().ifEmpty { previous.foreshadowPlan }.take(14),
        characterDossiers = mergedDossiers,
        foreshadowLedger = mergeApocalypseForeshadowLedgerV5(previous.foreshadowLedger, foreshadowUpdates),
        recentBeatTypes = (previous.recentBeatTypes + beatType).takeLast(8),
        recentEmotionalTurns = (previous.recentEmotionalTurns + emotionalTurn)
            .filter(String::isNotBlank)
            .takeLast(8),
        awakenedCompanionIds = (previous.awakenedCompanionIds + newAwakeningIds).distinct().take(12),
        presentCharacterIds = if (json.has("presentCharacterIds")) {
            presentCharacterIds
        } else {
            if (previous.presentCharacterStateKnown) previous.presentCharacterIds else party.map { it.characterId }
        },
        presentCharacterStateKnown = json.has("presentCharacterIds") || previous.presentCharacterStateKnown,
        directorRefreshNeeded = false,
        locations = (previous.locations + locations).distinctBy { it.id }.takeLast(36),
        assets = (previous.assets + assets).distinctBy { it.id }.takeLast(90),
        dayIndex = nextDayIndex,
        clockMinutes = nextClockMinutes,
        weather = json.optString("weather").ifBlank { previous.weather }.take(40),
        temperatureC = json.optInt("temperatureC", previous.temperatureC).coerceIn(-35, 55),
        tension = json.optInt("tension", previous.tension).coerceIn(1, 10),
    )
    ApocalypseV3Beat(
        nextDirector = next,
        beatType = beatType,
        directive = json.optString("directive").ifBlank { "让玩家行动产生现实后果。" }.take(1400),
        worldDelta = json.optString("worldDelta").take(500),
        openingHook = json.optString("openingHook").take(320),
        pressureEscalation = json.optString("pressureEscalation").take(420),
        emotionalTurn = emotionalTurn,
        closingHook = json.optString("closingHook").take(320),
        sceneValueShift = json.optString("sceneValueShift").take(180),
        focusCharacterIds = json.optJSONArray("focusCharacterIds").v5Strings().take(2),
        foreshadowMoves = json.optJSONArray("foreshadowMoves").v5Strings().take(3),
        moneyDelta = json.optInt("moneyDelta").coerceIn(-availableMoney.coerceIn(0, 50_000), 50_000),
        foodDelta = json.optInt("foodDelta").coerceIn(-4, 4),
        waterDelta = json.optInt("waterDelta").coerceIn(-4, 4),
        medicineDelta = json.optInt("medicineDelta").coerceIn(-4, 4),
        materialsDelta = json.optInt("materialsDelta").coerceIn(-4, 4),
        coresFound = json.optInt("coresFound").coerceIn(0, 4),
        playerAbilityXpGain = json.optInt("playerAbilityXpGain").coerceIn(0, 5),
        baseDelta = json.optInt("baseDelta").coerceIn(0, 1),
        healthDelta = json.optInt("healthDelta").coerceIn(-35, 20),
        staminaDelta = json.optInt("staminaDelta").coerceIn(-45, 40),
        infectionDelta = json.optInt("infectionDelta").coerceIn(-15, 30),
        moraleDelta = json.optInt("moraleDelta").coerceIn(-30, 30),
        minutesPassed = minutesPassed,
    )
}.getOrNull()

private fun fallbackApocalypseV5Beat(save: ApocalypseV3Save): ApocalypseV3Beat = ApocalypseV3Beat(
    nextDirector = save.director.copy(
        phase = apocalypsePhaseForDayV5(save.director.dayIndex),
        sceneGoal = "承认玩家刚才的自由行动，让它改变资源、关系、风险或信息，并继续沿长期世界状态自然演化。",
        tension = if (save.scene % 6 == 0) (save.director.tension + 1).coerceAtMost(7) else save.director.tension,
    ),
    beatType = "continuation",
    directive = "延续已有环境、人物和伏笔，不凭空反转；认真执行玩家行动。",
    worldDelta = "局势按照玩家行为继续变化。",
    openingHook = "让玩家行动立刻碰到一个来自现有环境或人物目标的具体阻力。",
    pressureEscalation = "让选择产生资源、时间、关系或信息上的真实代价。",
    emotionalTurn = "至少让一名在场人物因玩家的选择改变一点态度或暴露一个性格侧面。",
    closingHook = "停在由本幕因果自然产生、值得玩家回应的新局面。",
    sceneValueShift = "观望→必须表态",
)

private fun extractApocalypseV5Json(raw: String): String {
    val value = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = value.indexOf('{')
    val end = value.lastIndexOf('}')
    return if (start >= 0 && end > start) value.substring(start, end + 1) else value
}

private fun JSONArray?.v5Strings(): List<String> = buildList {
    val array = this@v5Strings ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.v5Objects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@v5Objects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}

private fun parseApocalypseV5AssetKind(raw: String): ApocalypseV3AssetKind = when (raw.lowercase()) {
    "food" -> ApocalypseV3AssetKind.Food
    "water" -> ApocalypseV3AssetKind.Water
    "medicine" -> ApocalypseV3AssetKind.Medicine
    "material" -> ApocalypseV3AssetKind.Material
    "tool", "item" -> ApocalypseV3AssetKind.Tool
    "weapon" -> ApocalypseV3AssetKind.Weapon
    "vehicle" -> ApocalypseV3AssetKind.Vehicle
    "key" -> ApocalypseV3AssetKind.Key
    "document" -> ApocalypseV3AssetKind.Document
    "map" -> ApocalypseV3AssetKind.Map
    "core" -> ApocalypseV3AssetKind.Core
    else -> ApocalypseV3AssetKind.Clue
}
