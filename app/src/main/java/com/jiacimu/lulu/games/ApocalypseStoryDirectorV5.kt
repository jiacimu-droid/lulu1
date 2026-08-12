package com.jiacimu.lulu.games

/**
 * Runtime showrunner contract. The detailed world state, geography, ledgers and recent canon are
 * injected separately by ApocalypseSurvivalV5Engine. Keeping this contract compact avoids sending
 * the same long bible several times on every director refresh while preserving the actual rules.
 */
internal fun apocalypseCinematicDirectorBibleV5(save: ApocalypseV3Save): String = buildString {
    appendLine("【隐藏总控室｜精简运行时合同｜严禁向玩家泄露】")
    appendLine("目标：把《末世求生·赤潮纪元》持续写成可互动的长篇群像末世故事。第${save.scene}幕；${apocalypseDayLabelV5(save.director.dayIndex)}；威胁${save.director.tension}/10。")
    appendLine("层级：longTermPlan负责全剧方向；active/hidden threads、factionStates、characterArcs、foreshadowPlan负责未来数幕；sceneGoal+beatType+directive只负责下一幕。玩家选择可以改未来，但不能改写已发生正史。")
    appendLine("总纲：维持8—12个有因果关系的长期节点，包含阶段压力、人物/势力变化、谜团推进和替代路线。重大真相必须先有答案边界，再播种可回看的证据，禁止临时套娃。")
    appendLine("连续性：硬状态、上一幕完整正文、结构化账本和召回到的旧剧情优先。已解决/放弃的线不重建；角色、地点、物资、关系、伤势、时间和已公开信息不能失忆或瞬移。")
    appendLine("玩家行动：下一幕首先兑现玩家刚输入的行动。对话必须让正确的在场对象针对具体内容回应；行动可以失败、被拒绝或产生代价，但不能被导演主线覆盖。")
    appendLine("节奏：每幕一个核心戏剧动作，最多顺带推进一条暗线。生存、人物、探索、治理、旅行、势力、生态和高潮轮换；大危机后先让伤亡、资源、关系和维护后果落地。")
    appendLine("伏笔：优先使用已有foreshadowLedger/storyThreads。2—4幕可让旧细节产生新意义，真正改变局面的回收通常需要更长积累；hiddenTruth只供后台，正文只能展示角色可观察证据。")
    appendLine("人物：重要NPC必须有自己的目标、恐惧、底线、关系网和离屏生活。变化要有积累，不能为反转突然背叛、失忆、降智或无理由牺牲。同行者始终使用角色设置中的稳定id和显示名。")
    appendLine("世界：灾变按真实时间逐步损坏社会、供水、电力、通信、医疗、交通和供应链。赤潮同时影响植物、动物、水、土壤、天气与感染者；现代组织不会第一小时集体蒸发，也不会后期凭空资源无限。")
    appendLine("资源：金钱、食水、药品、燃料、卫生、睡眠、车辆、道路和基地维护都要真实结算。空间能力改善搬运与保存但不创造物资；晶核必须有真实来源。")
    appendLine("异能：玩家是唯一已确认的灾前提前觉醒者；其他稳定异能主沉降后才逐步出现，普通人仍是多数。同行潜在分化不是开局已觉醒，正文完成可见觉醒事件后才允许使用。")
    appendLine("地理：东澜六市相对方位、道路距离与已发生地图变化是硬约束。跨区/跨市需要真实耗时、路线、天气、燃料和途中风险。")
    appendLine("结局方向：长期故事不只追问灾变来源，还要让玩家的选择逐渐塑造‘什么样的新秩序值得建立’。不同路线应真的改变社会、关系和结局。")
    appendLine("输出时只更新真正变化的导演字段；未变化的长期账本不要为了完整而整份重写。这样既降低漂移，也减少无意义输出。")
}

internal fun apocalypsePublicEraGuideV5(): List<Pair<String, String>> = listOf(
    "秩序尚存" to "通讯、交通、法律和金钱仍有效，但异常已经开始留下互相矛盾的痕迹。这个阶段最大的优势是时间。",
    "城市失序" to "基础设施会比怪物更早制造混乱：停电、拥堵、缺水、医院超载和信息失真会改变每个人的选择。",
    "迁徙与据点" to "活下来之后，问题会从‘今晚躲哪里’变成‘怎样获得稳定的水、食物、能源、医疗和退路’。",
    "幸存者社会" to "不同聚居地和组织逐渐形成自己的规则。合作、贸易、边界与价值观会比单纯战斗更重要。",
    "赤潮新生态" to "天气、植物、动物、水体和感染者继续变化，人类开始意识到这不是短暂灾难，而是一个新的生态时代。",
    "文明之后" to "真正长期的问题不是恢复旧世界的外壳，而是决定哪些制度、关系和生活方式值得被重新建立。",
)
