package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.CharacterSettings
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
    /** Dynamic save-state. These fields belong to this game world, not the companion's main memory. */
    val currentLocation: String = "",
    val physicalState: String = "",
    val emotionalState: String = "",
    val knowledge: List<String> = emptyList(),
    val carriedItems: List<String> = emptyList(),
    val offscreenIntent: String = "",
    val lastSeenScene: Int = 0,
)

internal data class ApocalypseCharacterStatePatchV5(
    val id: String,
    val currentLocation: String? = null,
    val physicalState: String? = null,
    val emotionalState: String? = null,
    val knowledgeAdds: List<String> = emptyList(),
    val carriedItems: List<String>? = null,
    val relationshipChanges: List<String> = emptyList(),
    val offscreenIntent: String? = null,
    val status: String? = null,
    val lastSeenScene: Int? = null,
)

internal data class ApocalypseStoryThreadV5(
    val id: String,
    val title: String,
    val visibility: String,
    val currentState: String,
    val nextPressure: String,
    val status: String = "active",
    val lastTouchedScene: Int = 0,
    val linkedCharacterIds: List<String> = emptyList(),
    val linkedForeshadowIds: List<String> = emptyList(),
)

internal data class ApocalypseForeshadowPatchV5(
    val id: String,
    val stage: String? = null,
    val evidenceAdds: List<String> = emptyList(),
    val surfaceMeaning: String? = null,
    val linkedCharacterIds: List<String> = emptyList(),
    val lastTouchedScene: Int? = null,
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

internal fun defaultApocalypseStoryThreadsV5(): List<ApocalypseStoryThreadV5> = listOf(
    ApocalypseStoryThreadV5(
        id = "countdown_preparation",
        title = "七日倒计时与生存准备",
        visibility = "main",
        currentState = "玩家刚收到七日预警，城市秩序仍正常，准备窗口完整开放。",
        nextPressure = "在不暴露异常优势的前提下完成第一批采购、运输与据点判断。",
        lastTouchedScene = 1,
    ),
    ApocalypseStoryThreadV5(
        id = "b8_warning",
        title = "B8与第一避难区警告",
        visibility = "hidden",
        currentState = "玩家只持有B8图纸残片和一句来源不明的避难区警告。",
        nextPressure = "让现实中的独立细节逐步验证或质疑预警，暂不揭晓真相。",
        lastTouchedScene = 1,
        linkedForeshadowIds = listOf("b8_shelter", "shelter_one_warning"),
    ),
    ApocalypseStoryThreadV5(
        id = "early_space_awakening",
        title = "空间异能提前觉醒",
        visibility = "hidden",
        currentState = "玩家在主沉降前七天觉醒空间系，是目前唯一确认的灾前觉醒者。",
        nextPressure = "先通过能力边界和黑色裂线留下疑问，不急于解释来源。",
        lastTouchedScene = 1,
        linkedForeshadowIds = listOf("space_early_awaken", "red_static_1417"),
    ),
)

internal fun ensureApocalypsePartyDossiersV5(
    previous: List<ApocalypseCharacterDossierV5>,
    party: List<CharacterSettings>,
    location: String,
    scene: Int,
): List<ApocalypseCharacterDossierV5> {
    val partyById = party.associateBy { it.characterId }
    val migrated = previous.map { dossier ->
        val character = partyById[dossier.id] ?: return@map dossier
        dossier.copy(
            name = dossier.name.ifBlank { character.displayName },
            currentLocation = dossier.currentLocation.ifBlank { "最后位置待确认" },
            physicalState = dossier.physicalState.ifBlank { "当前身体状态待确认" },
            emotionalState = dossier.emotionalState.ifBlank { "当前持续情绪待确认" },
            offscreenIntent = dossier.offscreenIntent.ifBlank { "按自身性格观察局势并形成下一步判断" },
            lastSeenScene = dossier.lastSeenScene.takeIf { it > 0 } ?: dossier.lastAdvancedScene,
        )
    }
    val knownIds = migrated.mapTo(mutableSetOf()) { it.id }
    val seeded = party.mapNotNull { character ->
        if (!knownIds.add(character.characterId)) return@mapNotNull null
        ApocalypseCharacterDossierV5(
            id = character.characterId,
            name = character.displayName,
            storyRole = "开局同行者；具体专业、立场与本局关系必须由剧情建立",
            publicGoal = "在七日预警与即将到来的赤潮中活下去，并形成自己的判断",
            privateNeed = "尚未在本局显露",
            fear = "尚未在本局显露",
            secret = "尚未在本局建立；不得从露露机主世界资料搬运秘密",
            contradiction = "等待通过选择、行动习惯与压力反应逐步显露",
            bottomLine = "尚未在本局通过事件确认",
            relationshipWeb = emptyList(),
            arcStage = "开端：尚未对七日预警作出稳定立场",
            lastAdvancedScene = scene,
            currentLocation = location,
            physicalState = "未见明确伤病",
            emotionalState = "对异常消息的真实态度尚待本局剧情表现",
            offscreenIntent = "留意局势并按自身性格形成下一步判断",
            lastSeenScene = scene,
        )
    }
    return (migrated + seeded).takeLast(64)
}

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
                    .put("status", value.status)
                    .put("currentLocation", value.currentLocation)
                    .put("physicalState", value.physicalState)
                    .put("emotionalState", value.emotionalState)
                    .put("knowledge", JSONArray(value.knowledge))
                    .put("carriedItems", JSONArray(value.carriedItems))
                    .put("offscreenIntent", value.offscreenIntent)
                    .put("lastSeenScene", value.lastSeenScene),
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
            currentLocation = item.optString("currentLocation").take(100),
            physicalState = item.optString("physicalState").take(220),
            emotionalState = item.optString("emotionalState").take(220),
            knowledge = item.optJSONArray("knowledge").v5LedgerStrings().distinct().takeLast(12),
            carriedItems = item.optJSONArray("carriedItems").v5LedgerStrings().distinct().takeLast(12),
            offscreenIntent = item.optString("offscreenIntent").take(260),
            lastSeenScene = item.optInt("lastSeenScene", item.optInt("lastAdvancedScene", 0)).coerceAtLeast(0),
        )
    }

internal fun encodeApocalypseStoryThreadsV5(values: List<ApocalypseStoryThreadV5>): JSONArray =
    JSONArray().apply {
        values.forEach { value ->
            put(
                JSONObject()
                    .put("id", value.id)
                    .put("title", value.title)
                    .put("visibility", value.visibility)
                    .put("currentState", value.currentState)
                    .put("nextPressure", value.nextPressure)
                    .put("status", value.status)
                    .put("lastTouchedScene", value.lastTouchedScene)
                    .put("linkedCharacterIds", JSONArray(value.linkedCharacterIds))
                    .put("linkedForeshadowIds", JSONArray(value.linkedForeshadowIds)),
            )
        }
    }

internal fun decodeApocalypseStoryThreadsV5(array: JSONArray?): List<ApocalypseStoryThreadV5> =
    array.v5LedgerObjects { item ->
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isBlank() || title.isBlank()) return@v5LedgerObjects null
        if (listOf("visibility", "currentState", "nextPressure", "status", "lastTouchedScene").any { !item.has(it) }) {
            return@v5LedgerObjects null
        }
        ApocalypseStoryThreadV5(
            id = id.take(80),
            title = title.take(100),
            visibility = normalizeApocalypseThreadVisibilityV5(item.optString("visibility", "main")),
            currentState = item.optString("currentState").take(360),
            nextPressure = item.optString("nextPressure").take(320),
            status = normalizeApocalypseThreadStatusV5(item.optString("status", "active")),
            lastTouchedScene = item.optInt("lastTouchedScene", 0).coerceAtLeast(0),
            linkedCharacterIds = item.optJSONArray("linkedCharacterIds").v5LedgerStrings().distinct().take(8),
            linkedForeshadowIds = item.optJSONArray("linkedForeshadowIds").v5LedgerStrings().distinct().take(8),
        )
    }

internal fun decodeApocalypseCharacterStatePatchesV5(array: JSONArray?): List<ApocalypseCharacterStatePatchV5> =
    array.v5LedgerObjects { item ->
        val id = item.optString("id").trim()
        if (id.isBlank()) return@v5LedgerObjects null
        ApocalypseCharacterStatePatchV5(
            id = id.take(80),
            currentLocation = item.optionalLedgerStringV5("currentLocation", 100),
            physicalState = item.optionalLedgerStringV5("physicalState", 220),
            emotionalState = item.optionalLedgerStringV5("emotionalState", 220),
            knowledgeAdds = item.optJSONArray("knowledgeAdds").v5LedgerStrings().distinct().take(6),
            carriedItems = item.optJSONArray("carriedItems")?.v5LedgerStrings()?.distinct()?.take(12),
            relationshipChanges = item.optJSONArray("relationshipChanges").v5LedgerStrings().distinct().take(4),
            offscreenIntent = item.optionalLedgerStringV5("offscreenIntent", 260),
            status = item.optionalLedgerStringV5("status", 40),
            lastSeenScene = item.optInt("lastSeenScene").takeIf { item.has("lastSeenScene") }?.coerceAtLeast(0),
        )
    }

internal fun decodeApocalypseForeshadowPatchesV5(array: JSONArray?): List<ApocalypseForeshadowPatchV5> =
    array.v5LedgerObjects { item ->
        val id = item.optString("id").trim()
        if (id.isBlank()) return@v5LedgerObjects null
        ApocalypseForeshadowPatchV5(
            id = id.take(80),
            stage = item.optionalLedgerStringV5("stage", 20)?.let(::normalizeApocalypseForeshadowStageV5),
            evidenceAdds = item.optJSONArray("evidenceAdds").v5LedgerStrings().distinct().take(4),
            surfaceMeaning = item.optionalLedgerStringV5("surfaceMeaning", 260),
            linkedCharacterIds = item.optJSONArray("linkedCharacterIds").v5LedgerStrings().distinct().take(8),
            lastTouchedScene = item.optInt("lastTouchedScene").takeIf { item.has("lastTouchedScene") }?.coerceAtLeast(0),
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
            currentLocation = update.currentLocation.ifBlank { old.currentLocation },
            physicalState = update.physicalState.ifBlank { old.physicalState },
            emotionalState = update.emotionalState.ifBlank { old.emotionalState },
            knowledge = (old.knowledge + update.knowledge).distinct().takeLast(12),
            carriedItems = update.carriedItems.ifEmpty { old.carriedItems },
            offscreenIntent = update.offscreenIntent.ifBlank { old.offscreenIntent },
            lastSeenScene = maxOf(old.lastSeenScene, update.lastSeenScene),
        )
    }
    return merged.values.toList().takeLast(64)
}

internal fun mergeApocalypseCharacterStatePatchesV5(
    previous: List<ApocalypseCharacterDossierV5>,
    patches: List<ApocalypseCharacterStatePatchV5>,
    scene: Int,
): List<ApocalypseCharacterDossierV5> {
    if (patches.isEmpty()) return previous
    val patchesById = patches.associateBy { it.id }
    return previous.map { old ->
        val patch = patchesById[old.id] ?: return@map old
        old.copy(
            currentLocation = patch.currentLocation ?: old.currentLocation,
            physicalState = patch.physicalState ?: old.physicalState,
            emotionalState = patch.emotionalState ?: old.emotionalState,
            knowledge = (old.knowledge + patch.knowledgeAdds).distinct().takeLast(12),
            carriedItems = patch.carriedItems ?: old.carriedItems,
            relationshipWeb = (old.relationshipWeb + patch.relationshipChanges).distinct().takeLast(10),
            offscreenIntent = patch.offscreenIntent ?: old.offscreenIntent,
            status = patch.status?.ifBlank { old.status } ?: old.status,
            lastSeenScene = patch.lastSeenScene ?: scene,
        )
    }
}

internal fun mergeApocalypseStoryThreadsV5(
    previous: List<ApocalypseStoryThreadV5>,
    updates: List<ApocalypseStoryThreadV5>,
): List<ApocalypseStoryThreadV5> {
    if (updates.isEmpty()) return previous
    val merged = LinkedHashMap<String, ApocalypseStoryThreadV5>()
    previous.forEach { merged[it.id] = it }
    updates.forEach { update ->
        val old = merged[update.id]
        merged[update.id] = if (old == null) update else update.copy(
            title = update.title.ifBlank { old.title },
            visibility = update.visibility.ifBlank { old.visibility },
            currentState = update.currentState.ifBlank { old.currentState },
            nextPressure = update.nextPressure.ifBlank { old.nextPressure },
            status = update.status.ifBlank { old.status },
            lastTouchedScene = maxOf(old.lastTouchedScene, update.lastTouchedScene),
            linkedCharacterIds = (old.linkedCharacterIds + update.linkedCharacterIds).distinct().takeLast(8),
            linkedForeshadowIds = (old.linkedForeshadowIds + update.linkedForeshadowIds).distinct().takeLast(8),
        )
    }
    return merged.values.toList().takeLast(64)
}

internal fun mergeApocalypseForeshadowPatchesV5(
    previous: List<ApocalypseForeshadowV5>,
    patches: List<ApocalypseForeshadowPatchV5>,
    scene: Int,
): List<ApocalypseForeshadowV5> {
    if (patches.isEmpty()) return previous
    val patchesById = patches.associateBy { it.id }
    return previous.map { old ->
        val patch = patchesById[old.id] ?: return@map old
        old.copy(
            visibleEvidence = (old.visibleEvidence + patch.evidenceAdds).distinct().takeLast(8),
            surfaceMeaning = patch.surfaceMeaning ?: old.surfaceMeaning,
            stage = patch.stage?.let { progressedApocalypseForeshadowStageV5(old.stage, it) } ?: old.stage,
            lastTouchedScene = maxOf(old.lastTouchedScene, patch.lastTouchedScene ?: scene),
            linkedCharacterIds = (old.linkedCharacterIds + patch.linkedCharacterIds).distinct().takeLast(8),
        )
    }
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
    return merged.values.toList().takeLast(40)
}

internal fun apocalypseCharacterDossiersPromptV5(values: List<ApocalypseCharacterDossierV5>): String =
    values.joinToString("\n") { value ->
        buildString {
            append("- ${value.id}/${value.name} [${value.status}] 叙事功能=${value.storyRole}；")
            append("外在目标=${value.publicGoal}；内在需求=${value.privateNeed}；恐惧=${value.fear}；")
            append("秘密=${value.secret}；矛盾魅力=${value.contradiction}；底线=${value.bottomLine}；")
            append("关系网=${value.relationshipWeb.joinToString("、")}；弧光=${value.arcStage}；上次推进=第${value.lastAdvancedScene}幕")
            append("；当前地点=${value.currentLocation.ifBlank { "未记录" }}；身体=${value.physicalState.ifBlank { "未记录" }}；情绪=${value.emotionalState.ifBlank { "未记录" }}")
            append("；已知=${value.knowledge.joinToString("、").ifBlank { "未记录" }}；随身=${value.carriedItems.joinToString("、").ifBlank { "未记录" }}")
            append("；离屏意图=${value.offscreenIntent.ifBlank { "未记录" }}；上次在场=第${value.lastSeenScene}幕")
        }
    }.ifBlank { "尚未建立。总导演必须为本幕实际出现的长期人物建立档案。" }

internal fun apocalypseStoryThreadsPromptV5(values: List<ApocalypseStoryThreadV5>): String =
    values.joinToString("\n") { value ->
        "- ${value.id}/${value.title} [${value.visibility}/${value.status}] 当前=${value.currentState}；下一压力=${value.nextPressure}；" +
            "上次推进=第${value.lastTouchedScene}幕；关联人物=${value.linkedCharacterIds.joinToString("、")}；关联伏笔=${value.linkedForeshadowIds.joinToString("、")}"
    }.ifBlank { "尚未建立剧情线账本。" }

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

private fun normalizeApocalypseThreadVisibilityV5(raw: String): String =
    if (raw.trim().lowercase() == "hidden") "hidden" else "main"

private fun normalizeApocalypseThreadStatusV5(raw: String): String = when (raw.trim().lowercase()) {
    "active", "dormant", "resolved", "abandoned" -> raw.trim().lowercase()
    else -> "active"
}

private fun JSONObject.optionalLedgerStringV5(key: String, maxLength: Int): String? {
    if (!has(key)) return null
    return optString(key).trim().take(maxLength)
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
