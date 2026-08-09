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
): String = party.joinToString("\n") { character ->
    val choice = companionAbilityChoice(config, character.characterId)
    val ability = apocalypseAbilityDefinitionV5(choice)
    buildString {
        append("- characterId=${character.characterId}；游戏显示名=${character.displayName}；")
        append("风格原始资料=${character.persona.ifBlank { "暂无额外风格资料" }}；")
        append("本局异能=${ability.name}/${choice.branch}/${ability.rarity.label}/${ability.potential}")
    }
}

private fun apocalypseIsolationRuleV5(): String =
    "本作是与露露机主世界完全隔离的独立世界。角色资料只允许提取性格、说话方式、外貌、习惯、情绪表达和行为风格；资料中涉及原身份、职业、时代、阵营、原世界背景、与玩家或其他角色的原关系、聊天经历、共同事件、承诺、记忆、主时间线状态的内容全部视为禁用信息，不能成为本局事实。本局身份、关系和共同经历只能由当前存档内已经发生的剧情建立。"

internal suspend fun planApocalypseV5Beat(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
): ApocalypseV3Beat {
    val director = save.director
    val partyPrompt = apocalypsePartyStylePromptV5(party, config)
    val facts = buildString {
        appendLine("互动长篇：《末世求生·赤潮纪元》")
        appendLine(apocalypseIsolationRuleV5())
        appendLine("世界模式：${config.worldMode}")
        appendLine(playerSpacePrompt(save.stats))
        appendLine(apocalypsePlayerSecondaryPromptV5(config))
        appendLine("异能人口规则：灾前约8%人口拥有稳定异能，约92%没有稳定异能；末世后因普通人平均死亡率更高，幸存者中的异能者比例可以逐步升高，但除特殊异能者聚居地外不要把异能者写成多数。")
        appendLine(apocalypseWorldGeographyPromptV5())
        appendLine(apocalypseCinematicDirectorBibleV5(save))
        appendLine("玩家已确认的地图变化账本：${apocalypseMapLedgerPromptV5(save)}")
        appendLine("时间=${apocalypseDayLabelV5(director.dayIndex)} ${apocalypseClockLabelV5(director.clockMinutes)}；天气=${director.weather}；温度=${director.temperatureC}℃")
        appendLine("玩家状态：生命${save.stats.health}/100；体力${save.stats.stamina}/100；感染${save.stats.infection}/100；士气${save.stats.morale}/100")
        appendLine("阶段=${director.phase}；地点=${director.location}；第${save.scene}幕；威胁=${director.tension}/10")
        appendLine("资源：食物${save.stats.food} 水${save.stats.water} 药物${save.stats.medicine} 材料${save.stats.materials} 晶核${save.stats.crystalCores}")
        appendLine("基地=${save.stats.baseName}/Lv.${save.stats.baseLevel}")
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
        if (save.log.isNotEmpty()) {
            appendLine("本局仍保留的最近剧情记录（这是唯一可用的跨幕共同经历）：\n${save.log.takeLast(8).joinToString("\n---\n")}")
        }
        appendLine("玩家行动：$action")
        appendLine("上一幕：\n${save.narration.takeLast(3000)}")
    }
    val instruction = """
        你是长篇互动游戏《末世求生》的隐藏总导演。你不写正文，只维护世界、长线剧情和下一幕导演意图。只返回 JSON，不加代码块。

        【世界隔离是最高优先级】
        - 这是独立游戏世界，不是露露机主世界的延续、梦境、番外或平行记忆回放。
        - 不得调用、猜测或补全主聊天、主时间线、辞海、世界书、角色长期记忆、共同活动、承诺、原世界身份和原关系。
        - 同行角色只继承输入资料里的性格、说话方式、外貌、习惯、情绪表达与行为风格。即使风格原始资料里写了职业、身份、关系或过去经历，也必须忽略这些部分。
        - 角色在本局里的身份、关系、经历只由当前存档的世界事实、导演状态、保留剧情记录和上一幕决定。已经从存档删除的剧情不得靠推测重建。

        返回字段：phase, location, sceneGoal, beatType, tension, activeThreads, hiddenThreads, worldFacts,
        longTermPlan, factionStates, characterArcs, foreshadowPlan, worldDelta, directive,
        foodDelta, waterDelta, medicineDelta, materialsDelta, coresFound, playerAbilityXpGain, baseDelta,
        healthDelta, staminaDelta, infectionDelta, moraleDelta, minutesPassed, weather, temperatureC,
        unlockLocations:[{id,name,detail,unlocked}], discoverAssets:[{id,kind,title,detail,quantity,tag}]。
        kind只能 food|water|medicine|material|tool|weapon|vehicle|key|document|clue|map|core。

        导演原则：
        1. 必须提前维护数月到数年的剧情蓝图、势力状态、人物弧和伏笔回收计划，而不是每一幕临时编。
        2. 蓝图不是铁路。玩家可以拒绝、绕路、提前发现、救人或毁掉原计划；发生后承认事实并重排未来。
        3. 每幕推进一个核心戏剧动作，最多顺带推进一条暗线。2—4幕让旧细节产生新意义，6—10幕才安排真正改变局面的回收或反转。
        4. 重大反转必须有可回看的依据，禁止凭空失忆、万能组织、无缘由背叛和突然救世主。
        5. 灾前稳定异能者约占8%，普通人约占92%。末世淘汰可以提高幸存者中的异能者比例，但除特殊据点外仍不应默认异能者为多数。体能/感官型占异能的大头；元素、念动力更少；空间、预知等规则型能力极其稀有。
        6. 玩家第一异能固定为空间系高稳定共鸣；玩家可以另外拥有一个第二异能槽。两种异能都是硬设定，不能无故遗忘、替换或突然出现第三种能力。
        7. 空间系成长：Lv1不能稳定空间刃；Lv2裂隙刃雏形；Lv3闪位与稳定空间刃；Lv4空间锁/裂隙陷阱；Lv5领域。第二异能同样必须遵守它自己的分化与代价。
        8. 赤潮同时影响植物、动物、土壤、水体、气候、人类和感染者；剧情不能退化成只有丧尸。
        9. 感染者进化有时间尺度，越高阶越稀少。晶核必须真实获取，不能当自动掉落金币。
        10. 生存资源、运输、燃料、卫生、睡眠、基地维护都要有现实约束；空间异能可以显著改善搬运和保存，但不能凭空创造物资。
        11. 同行者必须有独立欲望和风险判断，不能全员围着玩家说同一种话；同行角色的异能与分化是硬设定，普通人不能突然觉醒。
        12. 东澜地区六市的相对方位、资源定位与交通距离是硬地理设定。跨市移动必须经历真实路程与风险；临江市只是开局城市，不是整个世界。
        13. 每次行动必须估算真实耗时并返回minutesPassed：简单整理5—30分钟，搜楼/战斗30—180分钟，跨区数小时，跨市通常3—10小时。时间推进后天气、照明、疲劳和风险都要跟着变化。
        14. health/stamina/infection/morale都是0—100。受伤降低health；奔跑、战斗、熬夜降低stamina；赤雨、伤口污染和感染者体液提高infection；成功、休息、关系支持可提高morale。不要无缘无故大幅波动。
        15. foodDelta/waterDelta必须包含真实消耗与搜集的净变化；长时间行动不能人人永远不喝水不吃东西。基地等级越高，休息恢复、净水、医疗和长期生产才越可靠。
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
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = APOCALYPSE_ISOLATED_CHARACTER_ID,
        facts = facts,
        instruction = instruction,
        source = "末世求生V5导演",
        title = "末世求生 · 导演第${save.scene}幕",
        temperature = 0.72,
        maxTokens = 2200,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).fold(
        onSuccess = { parseApocalypseV5Beat(it.text, director) ?: fallbackApocalypseV5Beat(save) },
        onFailure = { fallbackApocalypseV5Beat(save) },
    )
}

internal suspend fun writeApocalypseV5Scene(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
    beat: ApocalypseV3Beat,
    nextStats: ApocalypseV3Stats,
): Result<String> {
    val partyPrompt = apocalypsePartyStylePromptV5(party, config)
    val facts = buildString {
        appendLine(apocalypseIsolationRuleV5())
        appendLine("玩家行动：$action")
        appendLine("阶段：${beat.nextDirector.phase}；地点：${beat.nextDirector.location}；威胁：${beat.nextDirector.tension}/10")
        appendLine(playerSpacePrompt(nextStats))
        appendLine(apocalypsePlayerSecondaryPromptV5(config))
        appendLine("异能人口规则：灾前约8%稳定觉醒，约92%普通人；灾后幸存者中的异能者比例会因淘汰效应上升，但不能泛滥。")
        appendLine(apocalypseWorldGeographyPromptV5())
        appendLine("时间：${apocalypseDayLabelV5(beat.nextDirector.dayIndex)} ${apocalypseClockLabelV5(beat.nextDirector.clockMinutes)}；天气=${beat.nextDirector.weather} ${beat.nextDirector.temperatureC}℃")
        appendLine("玩家状态：生命${nextStats.health} 体力${nextStats.stamina} 感染${nextStats.infection} 士气${nextStats.morale}")
        appendLine("资源：食${nextStats.food} 水${nextStats.water} 药${nextStats.medicine} 材料${nextStats.materials} 晶核${nextStats.crystalCores}；基地=${nextStats.baseName}/Lv.${nextStats.baseLevel}")
        appendLine("导演动作：${beat.beatType}；本幕目标：${beat.nextDirector.sceneGoal}")
        appendLine("世界变化：${beat.worldDelta}")
        appendLine("导演执行指令：${beat.directive}")
        appendLine("同行角色风格参考（只按隔离规则提取风格，不继承其中身份/关系/经历）：\n$partyPrompt")
        appendLine("当前明线：${beat.nextDirector.activeThreads.joinToString("｜")}")
        if (save.log.isNotEmpty()) {
            appendLine("本局仍保留的最近剧情记录：\n${save.log.takeLast(8).joinToString("\n---\n")}")
        }
        appendLine("上一幕：\n${save.narration.takeLast(3000)}")
    }
    val instruction = """
        写一幕高质量中文末世互动视觉小说，约750—1200字。不要输出选项、数值面板、解释、Markdown或JSON。

        【世界隔离是最高优先级】
        - 这是独立游戏世界。不得带入露露机主聊天、主时间线、辞海、世界书、角色长期记忆、共同活动、承诺、原身份、原职业、原世界背景或原关系。
        - 同行者只继承提供资料中的性格、说话方式、外貌、习惯、情绪表达与行为风格；风格资料中的身份、关系和过去经历一律忽略。
        - 本局内的人际关系和共同经历只能由当前存档已经保留的剧情形成。已删除的剧情、后果和关系变化不得自行补回。

        【必须遵守的视觉小说格式】
        每个显示段单独一段，并且段首必须是以下三种标记之一：
        【旁白】用于环境、动作、内心、无人直接说话的叙事。
        【玩家】只用于玩家本人正在直接说话的段落。
        【角色:<characterId>】用于某位同行角色正在说话的段落，characterId必须从同行者清单原样复制。
        同一段只能有一个当前说话人；两个人连续说话必须拆成两段。不要把标记写到句子中间。
        每段通常1—2句，目标30—70字；客户端会在不删减任何文字和标点的前提下继续细分长段落。

        文学与玩法规则：
        - 玩家刚才的行动必须真实发生并有现实后果，不能偷换成编剧想让玩家做的事。
        - 同行者严格保持允许继承的性格、语言、外貌、习惯和行为风格；本局关系只按本局已发生剧情发展。异能按设定和分化使用，普通人不能突然觉醒。
        - 玩家拥有两个异能槽：第一异能固定为空间；第二异能以硬设定为准。没有选择第二异能时绝不能临时补一个。
        - 空间异能当前等级要大胆使用但不能越级；第二异能也不能无代价无限使用。
        - 灾前异能者约8%，所以路人和普通幸存者默认仍应以普通人为主；可以通过末世淘汰让幸存队伍中的异能者比例逐渐提高，但不要写成遍地超能力。
        - 生存细节要有重量：水、保质、药物、燃料、噪音、伤口、睡眠、卫生、天气、车辆和电力会影响选择。
        - 赤潮生态要进入植物、动物、土壤、水和天气，不要只写丧尸。
        - 高潮之间允许做饭、整理物资、赶路、建设、争执、休息和关系沉淀。九死一生要靠积累。
        - 晶核、物资、线索、地图和地点只有导演给出时才正式获得，并写清楚如何得到。
        - 东澜六市地理是硬设定；跨市行动必须写出路程、道路、燃料、天气、桥隧和中途风险，不能把不同城市当成同一街区。
        - 时间和身体状态是硬状态：当前时刻、天气、生命、体力、感染、士气必须反映在行动能力和描写里。疲劳时不能像满状态一样连续高强度战斗；高感染要出现现实后果但不能直接无判定宣判死亡。
        - 基地能力按等级逐步解锁：Lv1安全睡眠/储物，Lv2净水/医疗，Lv3供电/工坊，Lv4防御/通信，Lv5持续生产。不能让低级临时据点凭空拥有完整城市功能。
        - 让场景像电影而不是剧情摘要：用可感知的环境变化、动作、停顿、声音、光线和人物反应承载信息；避免角色站着互相讲设定。
        - 对话要有潜台词和人物差异。重要人物可以避而不答、说半句、误解、改口或在行动中表达立场，但不能为了悬念故意全员谜语人。
        - 大场面前先建立空间和目标，大场面后必须留下实际后果；安静场景同样要有情绪、关系或选择上的推进。
        - 不要为了“刺激”频繁杀角色、背叛、抓走队友或突然出现更强怪物。真正的悬念来自已有规则下越来越难的选择。
        - 结尾停在自然可行动节点，不替玩家决定下一步。
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = APOCALYPSE_ISOLATED_CHARACTER_ID,
        facts = facts,
        instruction = instruction,
        source = "末世求生V5正文",
        title = "末世求生 · 第${save.scene}幕",
        temperature = 0.80,
        maxTokens = 2300,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).map { it.text.trim() }
}

private fun parseApocalypseV5Beat(raw: String, previous: ApocalypseV3Director): ApocalypseV3Beat? = runCatching {
    val json = JSONObject(extractApocalypseV5Json(raw))
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
    val absoluteMinutes = previous.clockMinutes + minutesPassed
    val dayAdvance = absoluteMinutes / 1440
    val nextClockMinutes = absoluteMinutes % 1440
    val next = previous.copy(
        phase = json.optString("phase").ifBlank { previous.phase }.take(80),
        location = json.optString("location").ifBlank { previous.location }.take(100),
        sceneGoal = json.optString("sceneGoal").ifBlank { previous.sceneGoal }.take(260),
        activeThreads = json.optJSONArray("activeThreads").v5Strings().ifEmpty { previous.activeThreads }.take(8),
        hiddenThreads = json.optJSONArray("hiddenThreads").v5Strings().ifEmpty { previous.hiddenThreads }.take(8),
        worldFacts = mergeApocalypseWorldFactsV5(previous.worldFacts, json.optJSONArray("worldFacts").v5Strings()),
        longTermPlan = json.optJSONArray("longTermPlan").v5Strings().ifEmpty { previous.longTermPlan }.take(12),
        factionStates = json.optJSONArray("factionStates").v5Strings().ifEmpty { previous.factionStates }.take(14),
        characterArcs = json.optJSONArray("characterArcs").v5Strings().ifEmpty { previous.characterArcs }.take(14),
        foreshadowPlan = json.optJSONArray("foreshadowPlan").v5Strings().ifEmpty { previous.foreshadowPlan }.take(14),
        locations = (previous.locations + locations).distinctBy { it.id }.takeLast(36),
        assets = (previous.assets + assets).distinctBy { it.id }.takeLast(90),
        dayIndex = (previous.dayIndex + dayAdvance).coerceAtMost(9999),
        clockMinutes = nextClockMinutes,
        weather = json.optString("weather").ifBlank { previous.weather }.take(40),
        temperatureC = json.optInt("temperatureC", previous.temperatureC).coerceIn(-35, 55),
        tension = json.optInt("tension", previous.tension).coerceIn(1, 10),
    )
    ApocalypseV3Beat(
        nextDirector = next,
        beatType = json.optString("beatType").ifBlank { "continuation" }.take(40),
        directive = json.optString("directive").ifBlank { "让玩家行动产生现实后果。" }.take(900),
        worldDelta = json.optString("worldDelta").take(500),
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
        sceneGoal = "承认玩家刚才的自由行动，让它改变资源、关系、风险或信息，并继续沿长期世界状态自然演化。",
        tension = if (save.scene % 6 == 0) (save.director.tension + 1).coerceAtMost(7) else save.director.tension,
    ),
    beatType = "continuation",
    directive = "延续已有环境、人物和伏笔，不凭空反转；认真执行玩家行动。",
    worldDelta = "局势按照玩家行为继续变化。",
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
