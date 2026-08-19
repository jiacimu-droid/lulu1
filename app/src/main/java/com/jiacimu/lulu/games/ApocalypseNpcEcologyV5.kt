package com.jiacimu.lulu.games

/**
 * Runtime contract that keeps the apocalypse campaign populated by recurring people instead of
 * collapsing into one organization or a sequence of empty locations. NPCs are still introduced by
 * story causality; this layer only enforces population density, role diversity and long-term life.
 */
internal fun apocalypseNpcEcologyPromptV5(
    save: ApocalypseV3Save,
    playerAction: String,
): String = buildString {
    val partyIds = save.partyIds.toSet()
    val npcs = save.director.characterDossiers.filter { it.id !in partyIds && it.importance != "companion" }
    val recurring = npcs.filter { it.importance == "recurring" || it.importance == "key" }
    val recentlySeen = npcs.filter { it.lastSeenScene >= (save.scene - 8).coerceAtLeast(1) }
    val recentScenes = save.log.takeLast(5)
    val longSaveStarved = save.scene >= 20 && recurring.size < 6
    val severelyStarved = save.scene >= 8 && recurring.size < 3
    val likelyPublic = apocalypseLikelyPublicActionV5(playerAction, save.director.location)

    appendLine("【NPC生态硬合同｜角色不是一次性剧情道具】")
    appendLine("当前第${save.scene}幕；已建档NPC=${npcs.size}；其中可复用NPC=${recurring.size}；最近8幕出现=${recentlySeen.size}；当前行动像公共/外出场景=${if (likelyPublic) "是" else "否或不确定"}。")
    appendLine("世界必须有人。主线组织、固定任务发布者和同行者不能垄断全部社交；城市、社区、道路、医院、商店、学校、仓库、维修点、物业、车站和避难空间都应有各自的人际网络。")
    appendLine("新NPC首次重要登场时必须通过castUpdates建立稳定npc_* id、自然中文名、职业/处境、当下目标、私人需要、恐惧、底线、关系网、明显但不过度夸张的说话/动作习惯、当前地点、随身物、知道的信息和offscreenIntent。之后再次出现必须复用同一id并记得此前接触。")
    appendLine("不要把NPC只写成‘给任务的人’。他们会拒绝、求助、谈价、帮忙、占便宜、误解、记人情、记仇、改变计划、照顾家人、处理自己的工作和危机；玩家离开镜头后他们照样继续生活。")
    appendLine("长期价值要自然分布：可以出现医护/药师、机械与车辆维修、电工/发电机维护、水厂/管网人员、无线电爱好者、兽医/生态相关人员、仓储与冷链人员、厨师/种植者、消防/救援人员、社区组织者、司机、教师、保安、黑市中间人、信息贩子、投机商、地方小头目等。职业只是起点，人物本身必须有性格和利益。")
    appendLine("阵营不要整齐划一。长期可复用NPC中允许友方、条件式盟友、中立交易者、竞争者、危险机会主义者、未来反派同时存在；不能所有有用的人都天然喜欢玩家，也不能所有反派一眼就像反派。")
    appendLine("末世前就可以提前认识未来会有用的人。不要用镜头语言宣布‘此人以后很重要’；先让他作为普通工作者、邻居、顾客、司机、医护、维修师傅、摊主、警员、同学、路人等合理出现，之后由现实事件和重复来往自然变重要。")
    appendLine("主动联系闭环：已经和玩家有过具体交集、且offscreenIntent与玩家有关的NPC，后续可以基于真实渠道主动打电话、发消息、上门、托人传话、再次偶遇、提出交易、提醒风险、请玩家帮忙或兑现承诺。不能所有关系都永远等玩家先去找。")
    appendLine("主动联系必须有前因和渠道：至少满足曾经见过/交换过联系方式/知道住处或工作地点/有共同联系人/处于同一社区或组织之一。禁止陌生人凭空精准知道玩家位置和私人号码。")
    appendLine("多样性：不要连续两幕只围绕同一个组织、同一个职业网络或同一种NPC冲突。玩家若没有主动追某条线，优先切换到另一组真实人和现实事务。")

    if (recentScenes.isNotEmpty()) {
        appendLine("最近5幕去重参考（只用于判断是否重复，不得覆盖正史）：")
        recentScenes.forEach { line -> appendLine("- ${line.take(260)}") }
        appendLine("如果这些记录反复出现同一组织/同一职业网络/同一NPC，请主动降低其下一幕权重；除非玩家本次明确继续追它，否则换一条社会网络。")
    }

    if (severelyStarved || longSaveStarved) {
        appendLine("【人口饥荒修复｜当前存档明显缺NPC】")
        appendLine("这是硬修复，不是建议：下一次适合出现他人的公共/外出/办事场景，至少自然引入1名可复用NPC；若场景本身人流密集，可以引入2名，但不要一次塞一群简介。")
        appendLine("新NPC至少有一人应与现有主线组织无直接隶属关系；优先来自另一套生活/职业网络。若当前已经反复围绕物流或同一组织，本次必须换网络，除非玩家明确主动追踪它。")
        if (save.scene >= 40 && recurring.size < 6) {
            appendLine("本存档已经进行很久却缺少社会网络：未来6—10幕应逐步建立至少4—6名稳定可复用NPC，不要一幕补齐。至少包含一个可合作的人、一个利益中立的人、一个可能形成竞争/对立的人。")
        }
    }

    appendLine("现有可复用NPC摘要：")
    if (recurring.isEmpty()) {
        appendLine("- 暂无；必须开始建立。")
    } else {
        recurring.sortedByDescending { it.lastSeenScene }.take(12).forEach { dossier ->
            appendLine("- ${dossier.id}|${apocalypseDossierDisplayNameV5(dossier)}|${dossier.storyRole.take(80)}|位置=${dossier.currentLocation.take(70)}|目标=${dossier.publicGoal.take(90)}|离屏=${dossier.offscreenIntent.take(100)}|最近第${dossier.lastSeenScene}幕")
        }
    }
}.trim()

/**
 * Under-populated long saves should not wait for the ordinary six-scene director cadence when the
 * player goes somewhere social. One director wake is worth the extra call because it repairs the
 * cast ledger before the writer invents another empty location.
 */
internal fun shouldWakeApocalypseNpcEcologyDirectorV5(save: ApocalypseV3Save, playerAction: String): Boolean {
    if (!apocalypseLikelyPublicActionV5(playerAction, save.director.location)) return false
    val partyIds = save.partyIds.toSet()
    val recurringCount = save.director.characterDossiers.count {
        it.id !in partyIds && it.importance in setOf("recurring", "key") && it.status !in setOf("dead", "missing")
    }
    return when {
        save.scene >= 40 -> recurringCount < 6
        save.scene >= 20 -> recurringCount < 5
        save.scene >= 8 -> recurringCount < 3
        else -> false
    }
}

private fun apocalypseLikelyPublicActionV5(action: String, location: String): Boolean {
    val text = "$action $location"
    val publicSignals = listOf(
        "出门", "出去", "逛", "街", "市场", "超市", "商场", "医院", "诊所", "药店", "学校",
        "车站", "地铁", "公交", "打车", "开车", "仓库", "物流园", "水厂", "工厂", "维修", "买",
        "采购", "办事", "饭店", "餐馆", "便利店", "公园", "物业", "社区", "警局", "消防",
    )
    val privateSignals = listOf("睡觉", "洗澡", "独处", "关门", "卧室", "自己房间", "在家休息")
    if (privateSignals.any(text::contains) && publicSignals.none(text::contains)) return false
    return publicSignals.any(text::contains)
}
