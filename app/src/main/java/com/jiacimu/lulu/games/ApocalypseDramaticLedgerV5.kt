package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject

internal data class ApocalypseCharacterDossierV5(
    val id: String,
    val name: String,
    val storyRole: String,
    val publicGoal: String,
    val privateNeed: String,
    val fear: String,
    val secret: String,
    val contradiction: String,
    val bottomLine: String,
    val relationshipWeb: List<String>,
    val arcStage: String,
    val lastAdvancedScene: Int,
    val status: String = "active",
)

internal data class ApocalypseForeshadowV5(
    val id: String,
    val title: String,
    val hiddenTruth: String,
    val visibleEvidence: List<String>,
    val surfaceMeaning: String,
    val stage: String,
    val plantedScene: Int,
    val lastTouchedScene: Int,
    val targetPayoffStart: Int,
    val targetPayoffEnd: Int,
    val payoffConsequence: String,
    val linkedCharacterIds: List<String> = emptyList(),
)

internal fun defaultApocalypseForeshadowLedgerV5(): List<ApocalypseForeshadowV5> = listOf(
    ApocalypseForeshadowV5(
        id = "red_static_1417",
        title = "14:17红色通信雪花",
        hiddenTruth = "异常太阳活动让电离层中的赤潮载体发生全球同步共振，预警者利用这一窗口逆向注入短讯。",
        visibleEvidence = listOf("所有设备在14:17同时短暂失去信号", "红色雪花无法截图却在不同设备上同步出现"),
        surfaceMeaning = "像恶作剧、黑客攻击或运营商事故。",
        stage = "seeded",
        plantedScene = 1,
        lastTouchedScene = 1,
        targetPayoffStart = 18,
        targetPayoffEnd = 34,
        payoffConsequence = "预警来源与赤潮共振被证明有关，玩家获得预测下一次沉降窗口的方法，但仍不知道发送者是谁。",
    ),
    ApocalypseForeshadowV5(
        id = "b8_shelter",
        title = "被抹去的B8防灾层",
        hiddenTruth = "B8既保存第一批沉降样本，也连接一条未写入公开档案的旧时代撤离支线。",
        visibleEvidence = listOf("相册中凭空出现被红笔圈出的B8图纸", "公开线路只到地下四层"),
        surfaceMeaning = "它可能是一处秘密避难所，也可能是诱饵。",
        stage = "seeded",
        plantedScene = 1,
        lastTouchedScene = 1,
        targetPayoffStart = 10,
        targetPayoffEnd = 24,
        payoffConsequence = "B8改变临江市撤离路线与赤潮起源认知，并把玩家引向更大的跨市线索。",
    ),
    ApocalypseForeshadowV5(
        id = "shelter_one_warning",
        title = "不要去第一避难区",
        hiddenTruth = "第一避难区并非简单的邪恶陷阱；它的结构、人员调动与研究任务在赤潮到来后会形成致命风险。",
        visibleEvidence = listOf("预警明确点名第一避难区", "警告没有解释原因"),
        surfaceMeaning = "发送者可能在制造恐慌，或与官方存在私人冲突。",
        stage = "seeded",
        plantedScene = 1,
        lastTouchedScene = 1,
        targetPayoffStart = 8,
        targetPayoffEnd = 18,
        payoffConsequence = "玩家必须在相信官方秩序与相信不明预警之间承担真实代价；答案应由多源证据回收。",
    ),
    ApocalypseForeshadowV5(
        id = "space_early_awaken",
        title = "空间异能提前觉醒",
        hiddenTruth = "玩家对源质拥有罕见的高稳定共鸣，觉醒时间与预警事件之间存在因果联系。",
        visibleEvidence = listOf("赤潮抵达前七天就出现完整储物空间", "空间边缘已经出现尚不稳定的黑色裂线"),
        surfaceMeaning = "玩家可能只是幸运地比其他人更早觉醒。",
        stage = "seeded",
        plantedScene = 1,
        lastTouchedScene = 1,
        targetPayoffStart = 28,
        targetPayoffEnd = 52,
        payoffConsequence = "能力来源成为玩家必须主动选择如何使用的责任，而不是单纯血统揭晓或天选身份。",
    ),
    ApocalypseForeshadowV5(
        id = "infected_learning",
        title = "感染者不合常理的学习行为",
        hiddenTruth = "少数高阶感染者会借助赤潮网络共享局部经验，但这种协作受距离、天气和统御节点限制。",
        visibleEvidence = emptyList(),
        surfaceMeaning = "早期异常动作可能只是生前习惯、巧合或感官变异。",
        stage = "seeded",
        plantedScene = 0,
        lastTouchedScene = 0,
        targetPayoffStart = 22,
        targetPayoffEnd = 46,
        payoffConsequence = "旧有安全规则失效，队伍必须从单纯提高火力转向识别并破坏信息节点。",
    ),
)

internal fun encodeApocalypseCharacterDossiersV5(values: List<ApocalypseCharacterDossierV5>): JSONArray =
    JSONArray().apply {
        values.forEach { value ->
            put(
                JSONObject()
                    .put("id", value.id)
                    .put("name", value.name)
                    .put("storyRole", value.storyRole)
                    .put("publicGoal", value.publicGoal)
                    .put("privateNeed", value.privateNeed)
                    .put("fear", value.fear)
                    .put("secret", value.secret)
                    .put("contradiction", value.contradiction)
                    .put("bottomLine", value.bottomLine)
                    .put("relationshipWeb", JSONArray(value.relationshipWeb))
                    .put("arcStage", value.arcStage)
                    .put("lastAdvancedScene", value.lastAdvancedScene)
                    .put("status", value.status),
            )
        }
    }

internal fun decodeApocalypseCharacterDossiersV5(array: JSONArray?): List<ApocalypseCharacterDossierV5> =
    array.v5LedgerObjects { item ->
        val id = item.optString("id").trim()
        val name = item.optString("name").trim()
        if (id.isBlank() || name.isBlank()) return@v5LedgerObjects null
        ApocalypseCharacterDossierV5(
            id = id.take(80),
            name = name.take(40),
            storyRole = item.optString("storyRole").take(100),
            publicGoal = item.optString("publicGoal").take(220),
            privateNeed = item.optString("privateNeed").take(220),
            fear = item.optString("fear").take(220),
            secret = item.optString("secret").take(320),
            contradiction = item.optString("contradiction").take(220),
            bottomLine = item.optString("bottomLine").take(220),
            relationshipWeb = item.optJSONArray("relationshipWeb").v5LedgerStrings().take(8),
            arcStage = item.optString("arcStage").take(180),
            lastAdvancedScene = item.optInt("lastAdvancedScene", 0).coerceAtLeast(0),
            status = item.optString("status", "active").ifBlank { "active" }.take(40),
        )
    }

internal fun encodeApocalypseForeshadowLedgerV5(values: List<ApocalypseForeshadowV5>): JSONArray =
    JSONArray().apply {
        values.forEach { value ->
            put(
                JSONObject()
                    .put("id", value.id)
                    .put("title", value.title)
                    .put("hiddenTruth", value.hiddenTruth)
                    .put("visibleEvidence", JSONArray(value.visibleEvidence))
                    .put("surfaceMeaning", value.surfaceMeaning)
                    .put("stage", value.stage)
                    .put("plantedScene", value.plantedScene)
                    .put("lastTouchedScene", value.lastTouchedScene)
                    .put("targetPayoffStart", value.targetPayoffStart)
                    .put("targetPayoffEnd", value.targetPayoffEnd)
                    .put("payoffConsequence", value.payoffConsequence)
                    .put("linkedCharacterIds", JSONArray(value.linkedCharacterIds)),
            )
        }
    }

internal fun decodeApocalypseForeshadowLedgerV5(array: JSONArray?): List<ApocalypseForeshadowV5> =
    array.v5LedgerObjects { item ->
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isBlank() || title.isBlank()) return@v5LedgerObjects null
        val planted = item.optInt("plantedScene", 0).coerceAtLeast(0)
        val start = item.optInt("targetPayoffStart", planted + 6).coerceAtLeast(planted)
        ApocalypseForeshadowV5(
            id = id.take(80),
            title = title.take(80),
            hiddenTruth = item.optString("hiddenTruth").take(420),
            visibleEvidence = item.optJSONArray("visibleEvidence").v5LedgerStrings().take(8),
            surfaceMeaning = item.optString("surfaceMeaning").take(260),
            stage = normalizeApocalypseForeshadowStageV5(item.optString("stage", "seeded")),
            plantedScene = planted,
            lastTouchedScene = item.optInt("lastTouchedScene", planted).coerceAtLeast(planted),
            targetPayoffStart = start,
            targetPayoffEnd = item.optInt("targetPayoffEnd", start + 8).coerceAtLeast(start),
            payoffConsequence = item.optString("payoffConsequence").take(360),
            linkedCharacterIds = item.optJSONArray("linkedCharacterIds").v5LedgerStrings().take(8),
        )
    }

internal fun mergeApocalypseCharacterDossiersV5(
    previous: List<ApocalypseCharacterDossierV5>,
    updates: List<ApocalypseCharacterDossierV5>,
): List<ApocalypseCharacterDossierV5> {
    if (updates.isEmpty()) return previous
    val merged = LinkedHashMap<String, ApocalypseCharacterDossierV5>()
    previous.forEach { merged[it.id] = it }
    updates.forEach { update ->
        val old = merged[update.id]
        merged[update.id] = if (old == null) update else update.copy(
            name = update.name.ifBlank { old.name },
            storyRole = update.storyRole.ifBlank { old.storyRole },
            publicGoal = update.publicGoal.ifBlank { old.publicGoal },
            privateNeed = update.privateNeed.ifBlank { old.privateNeed },
            fear = update.fear.ifBlank { old.fear },
            secret = update.secret.ifBlank { old.secret },
            contradiction = update.contradiction.ifBlank { old.contradiction },
            bottomLine = update.bottomLine.ifBlank { old.bottomLine },
            relationshipWeb = update.relationshipWeb.ifEmpty { old.relationshipWeb },
            arcStage = update.arcStage.ifBlank { old.arcStage },
            lastAdvancedScene = maxOf(old.lastAdvancedScene, update.lastAdvancedScene),
            status = update.status.ifBlank { old.status },
        )
    }
    return merged.values.toList().takeLast(24)
}

internal fun mergeApocalypseForeshadowLedgerV5(
    previous: List<ApocalypseForeshadowV5>,
    updates: List<ApocalypseForeshadowV5>,
): List<ApocalypseForeshadowV5> {
    if (updates.isEmpty()) return previous
    val merged = LinkedHashMap<String, ApocalypseForeshadowV5>()
    previous.forEach { merged[it.id] = it }
    updates.forEach { update ->
        val old = merged[update.id]
        merged[update.id] = if (old == null) update else update.copy(
            title = update.title.ifBlank { old.title },
            hiddenTruth = update.hiddenTruth.ifBlank { old.hiddenTruth },
            visibleEvidence = (old.visibleEvidence + update.visibleEvidence).distinct().takeLast(8),
            surfaceMeaning = update.surfaceMeaning.ifBlank { old.surfaceMeaning },
            stage = progressedApocalypseForeshadowStageV5(old.stage, update.stage),
            plantedScene = listOf(old.plantedScene, update.plantedScene).filter { it > 0 }.minOrNull() ?: 0,
            lastTouchedScene = maxOf(old.lastTouchedScene, update.lastTouchedScene),
            payoffConsequence = update.payoffConsequence.ifBlank { old.payoffConsequence },
            linkedCharacterIds = (old.linkedCharacterIds + update.linkedCharacterIds).distinct().takeLast(8),
        )
    }
    return merged.values.toList().takeLast(18)
}

internal fun apocalypseCharacterDossiersPromptV5(values: List<ApocalypseCharacterDossierV5>): String =
    values.joinToString("\n") { value ->
        buildString {
            append("- ${value.id}/${value.name} [${value.status}] 叙事功能=${value.storyRole}；")
            append("外在目标=${value.publicGoal}；内在需求=${value.privateNeed}；恐惧=${value.fear}；")
            append("秘密=${value.secret}；矛盾魅力=${value.contradiction}；底线=${value.bottomLine}；")
            append("关系网=${value.relationshipWeb.joinToString("、")}；弧光=${value.arcStage}；上次推进=第${value.lastAdvancedScene}幕")
        }
    }.ifBlank { "尚未建立。总导演必须为本幕实际出现的长期人物建立档案。" }

internal fun apocalypseForeshadowLedgerPromptV5(values: List<ApocalypseForeshadowV5>): String =
    values.joinToString("\n") { value ->
        buildString {
            append("- ${value.id}/${value.title} [${value.stage}] 真相=${value.hiddenTruth}；")
            append("玩家已见证据=${value.visibleEvidence.joinToString("、")}；当前表面解释=${value.surfaceMeaning}；")
            append("播种=${value.plantedScene} 上次触碰=${value.lastTouchedScene} 目标回收=${value.targetPayoffStart}-${value.targetPayoffEnd}；")
            append("回收后果=${value.payoffConsequence}；关联角色=${value.linkedCharacterIds.joinToString("、")}")
        }
    }.ifBlank { "尚无伏笔。" }

private fun normalizeApocalypseForeshadowStageV5(raw: String): String = when (raw.trim().lowercase()) {
    "echoed", "distorted", "ripe", "paid_off", "abandoned" -> raw.trim().lowercase()
    else -> "seeded"
}

private fun progressedApocalypseForeshadowStageV5(previous: String, update: String): String {
    if (update == "abandoned" || previous == "abandoned") return "abandoned"
    val order = listOf("seeded", "echoed", "distorted", "ripe", "paid_off")
    return if (order.indexOf(update) >= order.indexOf(previous)) update else previous
}

private fun JSONArray?.v5LedgerStrings(): List<String> = buildList {
    val array = this@v5LedgerStrings ?: return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun <T> JSONArray?.v5LedgerObjects(mapper: (JSONObject) -> T?): List<T> = buildList {
    val array = this@v5LedgerObjects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}
