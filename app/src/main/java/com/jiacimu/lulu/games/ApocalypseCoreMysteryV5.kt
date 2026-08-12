package com.jiacimu.lulu.games

/**
 * The first scene makes a few explicit promises to the player. They are allowed to sleep for a long
 * time, mutate with the route, or be learned indirectly, but they are not disposable flavour text.
 * This layer keeps those promises alive without forcing the player onto a railroad.
 */
private const val WARNING_SOURCE_FORESHADOW_ID_V5 = "warning_source_origin"
private const val WARNING_SOURCE_THREAD_ID_V5 = "warning_source_identity"

private val requiredOpeningForeshadowIdsV5 = setOf(
    "red_static_1417",
    "b8_shelter",
    "shelter_one_warning",
    "space_early_awaken",
    WARNING_SOURCE_FORESHADOW_ID_V5,
)

private val warningSourceForeshadowTemplateV5 = ApocalypseForeshadowV5(
    id = WARNING_SOURCE_FORESHADOW_ID_V5,
    title = "七日预警的发送源",
    // Hidden production truth. Never expose this field directly in prose.
    hiddenTruth = "发送源是稳定id=npc_warning_origin的真实人类个体，不是系统旁白、未来的玩家、随机黑客或无因果预知。此人在灾前曾参与B8相关旧撤离工程的数据维护，后来接触到赤潮异常观测与第一避难区内部调度，因此比公众更早判断出风险；其掌握的是不完整资料，有盲区也会犯错。发送预警必须借助14:17的异常通信共振窗口，渠道受限且有现实代价，所以不能随时给玩家答案。其选择联系玩家与玩家异常提前的空间共鸣存在可追溯原因，但不得写成万能组织全知监控或天选者神谕。",
    visibleEvidence = listOf(
        "第一幕出现无号码、无应用图标、无法转发截图的七日预警",
        "预警准确点名了第一避难区，并同时留下B8图纸",
    ),
    surfaceMeaning = "现阶段只能确认这不是普通短信，发送源、动机和情报来源均未知。",
    stage = "seeded",
    plantedScene = 1,
    lastTouchedScene = 1,
    targetPayoffStart = 28,
    targetPayoffEnd = 64,
    payoffConsequence = "最终回收必须分别回答发送源是谁、为什么知道、为什么联系玩家、为什么渠道受限；至少依靠三类相互独立且玩家实际见过的证据，并有一项可验证行动或现实后果支撑，不能靠单人自述一口气解释。",
    linkedCharacterIds = listOf("npc_warning_origin"),
)

private val warningSourceThreadTemplateV5 = ApocalypseStoryThreadV5(
    id = WARNING_SOURCE_THREAD_ID_V5,
    title = "七日预警来源",
    visibility = "hidden",
    currentState = "玩家只知道灾前收到无法追溯的七日预警；发送源、目的、信息来源和联系限制都没有得到证明。",
    nextPressure = "只在玩家路线自然接触通信异常、旧工程资料、相关人员、避难体系或可验证物证时增加证据；禁止靠重复神秘短信追着玩家喂答案。",
    status = "active",
    lastTouchedScene = 1,
    linkedCharacterIds = listOf("npc_warning_origin"),
    linkedForeshadowIds = listOf(
        WARNING_SOURCE_FORESHADOW_ID_V5,
        "red_static_1417",
        "b8_shelter",
        "shelter_one_warning",
        "space_early_awaken",
    ),
)

/**
 * Core mysteries use world chronology as a hard maturity gate. Scene numbers are only pacing hints:
 * a player can spend twenty scenes shopping during one afternoon without making a global mystery ripe.
 */
private fun openingMysteryEarliestFullPayoffDayV5(id: String): Int = when (id) {
    "b8_shelter" -> -2
    WARNING_SOURCE_FORESHADOW_ID_V5 -> -1
    "red_static_1417", "shelter_one_warning" -> 0
    "space_early_awaken" -> 7
    else -> Int.MIN_VALUE
}

internal fun ensureApocalypseCoreMysteryContinuityV5(save: ApocalypseV3Save): ApocalypseV3Save {
    val director = save.director
    val templateMap = (defaultApocalypseForeshadowLedgerV5() + warningSourceForeshadowTemplateV5)
        .filter { it.id in requiredOpeningForeshadowIdsV5 }
        .associateBy { it.id }
    val existingById = director.foreshadowLedger.associateBy { it.id }

    var changed = false
    val mergedForeshadows = director.foreshadowLedger.toMutableList()
    templateMap.values.forEach { template ->
        val old = existingById[template.id]
        if (old == null) {
            mergedForeshadows += adaptOpeningMysteryWindowV5(template, save)
            changed = true
        } else {
            val locked = adaptOpeningMysteryWindowV5(
                old.copy(
                    title = template.title,
                    // These are causal anchors, not improvisation slots. Lock their backstage answer.
                    hiddenTruth = template.hiddenTruth,
                    visibleEvidence = (template.visibleEvidence + old.visibleEvidence).distinct().takeLast(12),
                    surfaceMeaning = old.surfaceMeaning.ifBlank { template.surfaceMeaning },
                    stage = if (old.stage == "abandoned") "seeded" else old.stage,
                    plantedScene = minOf(old.plantedScene.takeIf { it > 0 } ?: template.plantedScene, template.plantedScene),
                    payoffConsequence = template.payoffConsequence,
                    linkedCharacterIds = (old.linkedCharacterIds + template.linkedCharacterIds).distinct().takeLast(8),
                ),
                save,
            )
            if (locked != old) {
                val index = mergedForeshadows.indexOfFirst { it.id == old.id }
                if (index >= 0) mergedForeshadows[index] = locked
                changed = true
            }
        }
    }

    val threadTemplates = (defaultApocalypseStoryThreadsV5() + warningSourceThreadTemplateV5)
        .filter { it.id == "b8_warning" || it.id == "early_space_awakening" || it.id == WARNING_SOURCE_THREAD_ID_V5 }
        .associateBy { it.id }
    val existingThreads = director.storyThreads.associateBy { it.id }
    val mergedThreads = director.storyThreads.toMutableList()
    threadTemplates.values.forEach { template ->
        val old = existingThreads[template.id]
        if (old == null) {
            mergedThreads += template.copy(lastTouchedScene = minOf(template.lastTouchedScene, save.scene))
            changed = true
        } else {
            val locked = old.copy(
                title = template.title,
                visibility = "hidden",
                // An opening promise may sleep, but it cannot be silently thrown away before resolution.
                status = if (old.status == "abandoned") "dormant" else old.status,
                linkedCharacterIds = (old.linkedCharacterIds + template.linkedCharacterIds).distinct().takeLast(8),
                linkedForeshadowIds = (old.linkedForeshadowIds + template.linkedForeshadowIds).distinct().takeLast(10),
            )
            if (locked != old) {
                val index = mergedThreads.indexOfFirst { it.id == old.id }
                if (index >= 0) mergedThreads[index] = locked
                changed = true
            }
        }
    }

    val hiddenPromise = "开局七日预警的发送源、信息来源、联系动机与渠道限制是必须回收的核心谜团；可以长期休眠但不得无解释消失。"
    val hiddenThreads = if (director.hiddenThreads.any { it.contains("七日预警") && it.contains("发送") }) {
        director.hiddenThreads
    } else {
        changed = true
        (director.hiddenThreads + hiddenPromise).takeLast(8)
    }

    if (!changed) return save
    return save.copy(
        director = director.copy(
            hiddenThreads = hiddenThreads,
            storyThreads = mergedThreads.distinctBy { it.id }.takeLast(64),
            foreshadowLedger = mergedForeshadows.distinctBy { it.id }.takeLast(40),
            // One refresh is worthwhile after a migration so the long blueprint learns the restored promise.
            directorRefreshNeeded = true,
        ),
        updatedAt = System.currentTimeMillis(),
    )
}

private fun adaptOpeningMysteryWindowV5(
    item: ApocalypseForeshadowV5,
    save: ApocalypseV3Save,
): ApocalypseForeshadowV5 {
    if (item.stage == "paid_off") return item
    val earliestDay = openingMysteryEarliestFullPayoffDayV5(item.id)
    if (save.director.dayIndex >= earliestDay) return item
    if (save.scene < item.targetPayoffEnd - 2) return item

    // The player used many short scenes before enough world time elapsed. Slide the payoff window
    // forward instead of forcing a premature reveal merely because the scene counter is high.
    val shiftedStart = maxOf(item.targetPayoffStart, save.scene + 6)
    val shiftedEnd = maxOf(item.targetPayoffEnd, shiftedStart + 14)
    return item.copy(targetPayoffStart = shiftedStart, targetPayoffEnd = shiftedEnd)
}

internal fun apocalypseCoreMysteryDirectorContractV5(save: ApocalypseV3Save): String = buildString {
    appendLine("【开局核心谜团承诺锁｜隐藏】")
    appendLine("第一幕明确抛出的核心问题属于对玩家的叙事承诺，不是可随花园整理删除的普通钩子。相关id=${requiredOpeningForeshadowIdsV5.joinToString(",")}。在paid_off之前不得abandoned；玩家长期不调查时改为休眠/离屏推进，而不是消失。")
    appendLine("幕数只是节奏提示，不是成熟度。当前是第${save.scene}幕但世界时间=${apocalypseDayLabelV5(save.director.dayIndex)}；如果玩家用很多短幕购物、整理或聊天，不得因此提前揭开世界级谜底，应把targetPayoff窗口顺延。")
    appendLine("完整回收前至少形成三类相互独立、玩家真正见过的证据，尽量来自不同渠道（物证/人物行为/记录/环境规律/机构后果等）；单个陌生人自称身份、突然来电或一份万能文件都不足以完成大回收。")
    appendLine("允许误导，但误导也必须有事实依据；最终答案必须能回看早期细节得到‘原来如此’，不能靠后期新设定补洞。")
    appendLine("禁止反复使用神秘手机消息推动这条线。发送渠道本身有现实限制；之后是否再出现联系必须有已经建立的技术、时间、地点或人物因果。")
    appendLine("这些核心谜团彼此有关联但不是一个答案：发送源、通信异常、避难区警告、B8与提前觉醒分别有自己的问题和回收层级，禁止一场说明会全部讲完。")
}.trim()
