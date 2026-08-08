package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.CharacterSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal suspend fun planApocalypseV4Beat(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
): ApocalypseV3Beat {
    val director = save.director
    val partyPrompt = party.joinToString("\n") { character ->
        val choice = companionAbilityChoice(config, character.characterId)
        val ability = companionAbilityDefinition(choice)
        "- id=${character.characterId}；${character.displayName}；人设=${character.persona.ifBlank { "遵循既有人设" }}；能力=${ability.name}；稀有度=${ability.rarity.label}；潜力=${ability.potential}；分化=${choice.branch}"
    }
    val facts = buildString {
        appendLine("互动长篇：《末世求生·赤潮纪元》")
        appendLine("世界模式：${config.worldMode}")
        appendLine(playerSpacePrompt(save.stats))
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
        appendLine("同行者硬设定：\n$partyPrompt")
        appendLine("玩家行动：$action")
        appendLine("上一幕：\n${save.narration.takeLast(3000)}")
    }
    val instruction = """
        你是长篇互动游戏《末世求生》的隐藏总导演。你不写正文，只维护世界、长线剧情和下一幕导演意图。只返回 JSON，不加代码块。

        返回字段：phase, location, sceneGoal, beatType, tension, activeThreads, hiddenThreads, worldFacts,
        longTermPlan, factionStates, characterArcs, foreshadowPlan, worldDelta, directive,
        foodDelta, waterDelta, medicineDelta, materialsDelta, coresFound, playerAbilityXpGain, baseDelta,
        unlockLocations:[{id,name,detail,unlocked}], discoverAssets:[{id,kind,title,detail,quantity,tag}]。
        kind只能 food|water|medicine|material|tool|weapon|vehicle|key|document|clue|map|core。

        导演原则：
        1. 必须提前维护数月到数年的剧情蓝图、势力状态、人物弧和伏笔回收计划，而不是每一幕临时编。
        2. 蓝图不是铁路。玩家可以拒绝、绕路、提前发现、救人或毁掉原计划；发生后承认事实并重排未来。
        3. 每幕推进一个核心戏剧动作，最多顺带推进一条暗线。2—4幕让旧细节产生新意义，6—10幕才安排真正改变局面的回收或反转。
        4. 重大反转必须有可回看的依据，禁止凭空失忆、万能组织、无缘由背叛和突然救世主。
        5. 大多数人没有异能；常见异能以体能/感官为主。同行人的能力、稀有度、潜力和分化方向是硬设定。
        6. 玩家固定为空间系高稳定共鸣，体能偏弱，但必须具有主角级生存优势和后期攻击成长。Lv1不能稳定空间刃；Lv2裂隙刃雏形；Lv3闪位与稳定空间刃；Lv4空间锁/裂隙陷阱；Lv5领域。
        7. 赤潮同时影响植物、动物、土壤、水体、气候、人类和感染者；剧情不能退化成只有丧尸。
        8. 感染者进化有时间尺度，越高阶越稀少。晶核必须真实获取，不能当自动掉落金币。
        9. 生存资源、运输、燃料、卫生、睡眠、基地维护都要有现实约束；空间异能可以显著改善搬运和保存，但不能凭空创造物资。
        10. 同行者必须有独立欲望和风险判断，不能全员围着玩家说同一种话。
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世求生V4导演",
        title = "末世求生 · 导演第${save.scene}幕",
        temperature = 0.72,
        maxTokens = 2200,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).fold(
        onSuccess = { parseApocalypseV4Beat(it.text, director) ?: fallbackApocalypseV4Beat(save) },
        onFailure = { fallbackApocalypseV4Beat(save) },
    )
}

internal suspend fun writeApocalypseV4Scene(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
    beat: ApocalypseV3Beat,
    nextStats: ApocalypseV3Stats,
): Result<String> {
    val partyPrompt = party.joinToString("\n") { character ->
        val choice = companionAbilityChoice(config, character.characterId)
        val ability = companionAbilityDefinition(choice)
        "- characterId=${character.characterId}；名字=${character.displayName}；人设=${character.persona.ifBlank { "遵循既有人设" }}；异能=${ability.name}/${choice.branch}/${ability.rarity.label}"
    }
    val facts = buildString {
        appendLine("玩家行动：$action")
        appendLine("阶段：${beat.nextDirector.phase}；地点：${beat.nextDirector.location}；威胁：${beat.nextDirector.tension}/10")
        appendLine(playerSpacePrompt(nextStats))
        appendLine("资源：食${nextStats.food} 水${nextStats.water} 药${nextStats.medicine} 材料${nextStats.materials} 晶核${nextStats.crystalCores}；基地=${nextStats.baseName}/Lv.${nextStats.baseLevel}")
        appendLine("导演动作：${beat.beatType}；本幕目标：${beat.nextDirector.sceneGoal}")
        appendLine("世界变化：${beat.worldDelta}")
        appendLine("导演执行指令：${beat.directive}")
        appendLine("同行者：\n$partyPrompt")
        appendLine("当前明线：${beat.nextDirector.activeThreads.joinToString("｜")}")
        appendLine("上一幕：\n${save.narration.takeLast(3000)}")
    }
    val instruction = """
        写一幕高质量中文末世互动视觉小说，约750—1200字。不要输出选项、数值面板、解释、Markdown或JSON。

        【必须遵守的视觉小说格式】
        每个显示段单独一段，并且段首必须是以下三种标记之一：
        【旁白】用于环境、动作、内心、无人直接说话的叙事。
        【玩家】只用于玩家本人正在直接说话的段落。
        【角色:<characterId>】用于某位同行角色正在说话的段落，characterId必须从同行者清单原样复制。
        同一段只能有一个当前说话人；两个人连续说话必须拆成两段。不要把标记写到句子中间。
        每段通常1—3句，目标40—90字；客户端还会把超过96字的段落强制切开。

        文学与玩法规则：
        - 玩家刚才的行动必须真实发生并有现实后果，不能偷换成编剧想让玩家做的事。
        - 对话时让说话人拥有自然动作、停顿、眼神和潜台词，但不要把多个说话人的台词混进同一段。
        - 纯环境、战斗过程、玩家没有开口时用【旁白】，不要为了展示头像强迫每段都有人说话。
        - 如果玩家行动明确包含“我说/我问/我喊”等直接发言，应安排合适的【玩家】段；不要替玩家凭空说出其没有表达的重大观点。
        - 同行者严格保持露露机既有人设与关系；异能按设定和分化使用，普通人不能突然觉醒。
        - 玩家体能偏弱，主角感来自空间异能、准备、判断和逐步成长的空间攻击。当前等级能力要大胆使用，但不能越级。
        - 生存细节要有重量：水、保质、药物、燃料、噪音、伤口、睡眠、卫生、天气、车辆和电力会影响选择。
        - 赤潮生态要进入植物、动物、土壤、水和天气，不要只写丧尸。
        - 高潮之间允许做饭、整理物资、赶路、建设、争执、休息和关系沉淀。九死一生要靠积累。
        - 晶核、物资、线索、地图和地点只有导演给出时才正式获得，并写清楚如何得到。
        - 结尾停在自然可行动节点，不替玩家决定下一步。
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世求生V4正文",
        title = "末世求生 · 第${save.scene}幕",
        temperature = 0.80,
        maxTokens = 2300,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).map { it.text.trim() }
}

private fun parseApocalypseV4Beat(raw: String, previous: ApocalypseV3Director): ApocalypseV3Beat? = runCatching {
    val json = JSONObject(extractApocalypseV4Json(raw))
    val locations = json.optJSONArray("unlockLocations").v4Objects { item ->
        ApocalypseV3Location(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name").ifBlank { "未知地点" }.take(70),
            detail = item.optString("detail").take(260),
            unlocked = item.optBoolean("unlocked", true),
        )
    }
    val assets = json.optJSONArray("discoverAssets").v4Objects { item ->
        ApocalypseV3Asset(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = parseApocalypseV4AssetKind(item.optString("kind")),
            title = item.optString("title").ifBlank { "新发现" }.take(70),
            detail = item.optString("detail").take(360),
            quantity = item.optInt("quantity", 1).coerceIn(1, 999),
            tag = item.optString("tag").take(40),
        )
    }
    val next = previous.copy(
        phase = json.optString("phase").ifBlank { previous.phase }.take(80),
        location = json.optString("location").ifBlank { previous.location }.take(100),
        sceneGoal = json.optString("sceneGoal").ifBlank { previous.sceneGoal }.take(260),
        activeThreads = json.optJSONArray("activeThreads").v4Strings().ifEmpty { previous.activeThreads }.take(8),
        hiddenThreads = json.optJSONArray("hiddenThreads").v4Strings().ifEmpty { previous.hiddenThreads }.take(8),
        worldFacts = json.optJSONArray("worldFacts").v4Strings().ifEmpty { previous.worldFacts }.takeLast(28),
        longTermPlan = json.optJSONArray("longTermPlan").v4Strings().ifEmpty { previous.longTermPlan }.take(12),
        factionStates = json.optJSONArray("factionStates").v4Strings().ifEmpty { previous.factionStates }.take(14),
        characterArcs = json.optJSONArray("characterArcs").v4Strings().ifEmpty { previous.characterArcs }.take(14),
        foreshadowPlan = json.optJSONArray("foreshadowPlan").v4Strings().ifEmpty { previous.foreshadowPlan }.take(14),
        locations = (previous.locations + locations).distinctBy { it.id }.takeLast(36),
        assets = (previous.assets + assets).distinctBy { it.id }.takeLast(90),
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
    )
}.getOrNull()

private fun fallbackApocalypseV4Beat(save: ApocalypseV3Save): ApocalypseV3Beat = ApocalypseV3Beat(
    nextDirector = save.director.copy(
        sceneGoal = "承认玩家刚才的自由行动，让它改变资源、关系、风险或信息，并继续沿长期世界状态自然演化。",
        tension = if (save.scene % 6 == 0) (save.director.tension + 1).coerceAtMost(7) else save.director.tension,
    ),
    beatType = "continuation",
    directive = "延续已有环境、人物和伏笔，不凭空反转；认真执行玩家行动。",
    worldDelta = "局势按照玩家行为继续变化。",
)

private fun extractApocalypseV4Json(raw: String): String {
    val value = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = value.indexOf('{')
    val end = value.lastIndexOf('}')
    return if (start >= 0 && end > start) value.substring(start, end + 1) else value
}

private fun JSONArray?.v4Strings(): List<String> = buildList {
    val array = this@v4Strings ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.v4Objects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@v4Objects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}

private fun parseApocalypseV4AssetKind(raw: String): ApocalypseV3AssetKind = when (raw.lowercase()) {
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
