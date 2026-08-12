package com.jiacimu.lulu.games

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class ApocalypseV3Page { Home, Play, Settings, World, Archive }

internal enum class ApocalypseV3AssetKind(val label: String) {
    Food("食物"),
    Water("饮水"),
    Medicine("药品"),
    Material("材料"),
    Tool("工具"),
    Weapon("武器"),
    Vehicle("载具"),
    Key("钥匙/权限"),
    Document("文件"),
    Clue("线索"),
    Map("地图"),
    Core("晶核"),
}

internal data class ApocalypseV3Asset(
    val id: String,
    val kind: ApocalypseV3AssetKind,
    val title: String,
    val detail: String,
    val quantity: Int = 1,
    val tag: String = "",
)

internal data class ApocalypseV3Location(
    val id: String,
    val name: String,
    val detail: String,
    val unlocked: Boolean = true,
)

internal enum class ApocalypseAbilityRarity(val label: String) {
    None("普通人"),
    Common("常见"),
    Uncommon("少见"),
    Rare("稀有"),
    Exceptional("极稀有"),
}

internal data class ApocalypseAbilityDefinition(
    val id: String,
    val name: String,
    val rarity: ApocalypseAbilityRarity,
    val potential: String,
    val description: String,
    val branches: List<String>,
)

internal data class ApocalypseAbilityChoice(
    val abilityId: String = "none",
    val branch: String = "普通人",
)

internal data class ApocalypseV3Config(
    val worldMode: String = "标准异变",
    val autoDelayMillis: Long = 2_800L,
    val partyAbilities: Map<String, ApocalypseAbilityChoice> = emptyMap(),
)

internal data class ApocalypseV3Stats(
    val money: Int = 3_000,
    val food: Int = 2,
    val water: Int = 2,
    val medicine: Int = 1,
    val materials: Int = 0,
    val crystalCores: Int = 0,
    val playerAbilityLevel: Int = 1,
    val playerAbilityXp: Int = 0,
    val baseLevel: Int = 0,
    val baseName: String = "尚未建立",
    val health: Int = 100,
    val stamina: Int = 85,
    val infection: Int = 0,
    val morale: Int = 72,
)

internal data class ApocalypseV3Director(
    val phase: String,
    val location: String,
    val sceneGoal: String,
    val activeThreads: List<String>,
    val hiddenThreads: List<String>,
    val worldFacts: List<String>,
    val locations: List<ApocalypseV3Location>,
    val assets: List<ApocalypseV3Asset>,
    val tension: Int = 2,
    val longTermPlan: List<String> = defaultApocalypseLongTermPlan(),
    val factionStates: List<String> = defaultApocalypseFactionStates(),
    val characterArcs: List<String> = defaultApocalypseCharacterArcs(),
    val foreshadowPlan: List<String> = defaultApocalypseForeshadowPlan(),
    val storyThreads: List<ApocalypseStoryThreadV5> = defaultApocalypseStoryThreadsV5(),
    val characterDossiers: List<ApocalypseCharacterDossierV5> = emptyList(),
    val foreshadowLedger: List<ApocalypseForeshadowV5> = defaultApocalypseForeshadowLedgerV5(),
    val recentBeatTypes: List<String> = emptyList(),
    val recentEmotionalTurns: List<String> = emptyList(),
    /** Configured companion abilities are only potentials until an on-screen post-impact awakening. */
    val awakenedCompanionIds: List<String> = emptyList(),
    /** Hard cast continuity: only these actor ids are physically present in the current scene. */
    val presentCharacterIds: List<String> = emptyList(),
    val presentCharacterStateKnown: Boolean = false,
    /** A local one-call scene can request a long-plan refresh on the following turn. */
    val directorRefreshNeeded: Boolean = false,
    val dayIndex: Int = -7,
    val clockMinutes: Int = 14 * 60 + 17,
    val weather: String = "闷热多云",
    val temperatureC: Int = 34,
)

internal data class ApocalypseV3Save(
    val id: String,
    val scene: Int,
    val partyIds: List<String>,
    val narration: String,
    val director: ApocalypseV3Director,
    val stats: ApocalypseV3Stats,
    val log: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

internal data class ApocalypseV3Beat(
    val nextDirector: ApocalypseV3Director,
    val beatType: String,
    val directive: String,
    val worldDelta: String,
    val openingHook: String = "",
    val pressureEscalation: String = "",
    val emotionalTurn: String = "",
    val closingHook: String = "",
    val sceneValueShift: String = "",
    val focusCharacterIds: List<String> = emptyList(),
    val foreshadowMoves: List<String> = emptyList(),
    val moneyDelta: Int = 0,
    val foodDelta: Int = 0,
    val waterDelta: Int = 0,
    val medicineDelta: Int = 0,
    val materialsDelta: Int = 0,
    val coresFound: Int = 0,
    val playerAbilityXpGain: Int = 0,
    val baseDelta: Int = 0,
    val healthDelta: Int = 0,
    val staminaDelta: Int = 0,
    val infectionDelta: Int = 0,
    val moraleDelta: Int = 0,
    val minutesPassed: Int = 30,
)

internal class ApocalypseSurvivalV3Store(context: Context) {
    // Reuse the same preference file so an existing V2 save can be migrated without losing it.
    private val prefs = context.applicationContext.getSharedPreferences("apocalypse_survival_v2", Context.MODE_PRIVATE)

    fun loadSave(): ApocalypseV3Save? {
        val raw = prefs.getString("save_v3", null) ?: prefs.getString("save", null) ?: return null
        return runCatching { decodeApocalypseV3Save(JSONObject(raw)) }.getOrNull()
    }

    fun save(value: ApocalypseV3Save) {
        prefs.edit().putString("save_v3", encodeApocalypseV3Save(value).toString()).apply()
    }

    fun clearSave() {
        prefs.edit().remove("save_v3").remove("save").apply()
    }

    fun loadConfig(): ApocalypseV3Config {
        val raw = prefs.getString("config_v3", null)
        if (!raw.isNullOrBlank()) {
            runCatching { decodeApocalypseV3Config(JSONObject(raw)) }.getOrNull()?.let { return it }
        }
        return ApocalypseV3Config(
            worldMode = prefs.getString("world_mode", "标准异变").orEmpty().ifBlank { "标准异变" },
            autoDelayMillis = prefs.getLong("auto_delay", 2_800L).coerceIn(1_600L, 5_000L),
        )
    }

    fun saveConfig(config: ApocalypseV3Config) {
        prefs.edit().putString("config_v3", encodeApocalypseV3Config(config).toString()).apply()
    }
}

internal fun apocalypseCompanionAbilityCatalog(): List<ApocalypseAbilityDefinition> = listOf(
    ApocalypseAbilityDefinition(
        id = "none",
        name = "普通人",
        rarity = ApocalypseAbilityRarity.None,
        potential = "无异能",
        description = "没有源质异能。仍然可以依靠专业技能、武器、判断力、人脉和训练成为队伍核心。",
        branches = listOf("普通人"),
    ),
    ApocalypseAbilityDefinition(
        "strength", "力量强化", ApocalypseAbilityRarity.Common, "B",
        "最常见的体能类共鸣之一，提升肌力、爆发与负重，但仍然需要进食和恢复。",
        listOf("爆发力", "负重与耐久", "近战控制"),
    ),
    ApocalypseAbilityDefinition(
        "speed", "速度强化", ApocalypseAbilityRarity.Common, "B",
        "神经反射和短距离加速增强，适合侦察、救援和脱离危险。",
        listOf("瞬时加速", "反应强化", "长距离机动"),
    ),
    ApocalypseAbilityDefinition(
        "endurance", "耐受强化", ApocalypseAbilityRarity.Common, "B",
        "提高疲劳、疼痛、寒热和缺氧耐受，战斗表现不华丽但在末世极其可靠。",
        listOf("耐力", "抗毒", "环境适应"),
    ),
    ApocalypseAbilityDefinition(
        "night_vision", "夜视", ApocalypseAbilityRarity.Common, "C+",
        "在低照度环境中维持清晰视觉，高阶可向热差和动态捕捉分化。",
        listOf("极暗视觉", "动态捕捉", "热差感知"),
    ),
    ApocalypseAbilityDefinition(
        "far_sight", "千里眼", ApocalypseAbilityRarity.Common, "B",
        "远距离视觉显著增强，但遮挡仍然存在；成长方向决定它更适合侦察、狙击还是细节识别。",
        listOf("超远距", "微观细节", "高速目标追踪"),
    ),
    ApocalypseAbilityDefinition(
        "keen_hearing", "顺风耳", ApocalypseAbilityRarity.Common, "B",
        "听觉阈值和定位能力增强，可以从复杂环境中分离出微弱声源。",
        listOf("远距听觉", "声源定位", "频率解析"),
    ),
    ApocalypseAbilityDefinition(
        "scent", "气味追踪", ApocalypseAbilityRarity.Common, "C+",
        "能够辨别复杂气味层次，追踪人、动物、血迹和部分污染源。",
        listOf("追踪", "污染识别", "情绪气味"),
    ),
    ApocalypseAbilityDefinition(
        "temperature", "温度感知", ApocalypseAbilityRarity.Common, "C+",
        "感知局部温差和热源变化，可用于找人、发现设备异常或判断墙后活动。",
        listOf("生命热源", "设备热源", "环境预警"),
    ),
    ApocalypseAbilityDefinition(
        "danger", "危险直觉", ApocalypseAbilityRarity.Uncommon, "A-",
        "不是预知未来，而是在危险临近时产生强烈的方向性不适或本能警报。",
        listOf("近身杀意", "环境灾害", "路径风险"),
    ),
    ApocalypseAbilityDefinition(
        "telekinesis", "念动力", ApocalypseAbilityRarity.Uncommon, "A",
        "初期只能稳定推动或牵引小型物体；成长后可走精密控制或高冲击路线。",
        listOf("精密操控", "冲击投射", "多目标控制"),
    ),
    ApocalypseAbilityDefinition(
        "healing", "恢复", ApocalypseAbilityRarity.Uncommon, "A",
        "加速止血、愈合和组织恢复，代价是体力、营养和精神负荷。",
        listOf("外伤恢复", "解毒与代谢", "群体生命场"),
    ),
    ApocalypseAbilityDefinition(
        "shield", "力场护盾", ApocalypseAbilityRarity.Uncommon, "A",
        "制造短时力场抵挡冲击。面积、持续时间和强度无法同时拉满。",
        listOf("单体高强度", "范围屏障", "定向反弹"),
    ),
    ApocalypseAbilityDefinition(
        "fire", "火焰", ApocalypseAbilityRarity.Uncommon, "A",
        "操纵和强化燃烧。氧气、可燃物与封闭空间仍然是现实约束。",
        listOf("爆燃", "持续燃烧", "高温塑形"),
    ),
    ApocalypseAbilityDefinition(
        "water", "水流", ApocalypseAbilityRarity.Uncommon, "A-",
        "控制已有水体的形态和流向，无法凭空制造大量洁净水。",
        listOf("水压冲击", "净化辅助", "雾与湿度"),
    ),
    ApocalypseAbilityDefinition(
        "ice", "冰霜", ApocalypseAbilityRarity.Uncommon, "A",
        "抽取局部热量并冻结含水介质，环境湿度会显著影响表现。",
        listOf("冻结控制", "冰甲", "低温领域"),
    ),
    ApocalypseAbilityDefinition(
        "wind", "风压", ApocalypseAbilityRarity.Uncommon, "A-",
        "操纵局部气流和压差，可用于推离、减速、侦测和移动辅助。",
        listOf("风刃", "机动", "气流侦察"),
    ),
    ApocalypseAbilityDefinition(
        "electric", "雷电", ApocalypseAbilityRarity.Uncommon, "A",
        "产生和引导电流，高阶可干扰设备或形成短时电场。",
        listOf("高压放电", "设备干扰", "电磁感知"),
    ),
    ApocalypseAbilityDefinition(
        "plant", "植物共鸣", ApocalypseAbilityRarity.Uncommon, "A-",
        "感知植物和根系状态，并有限影响生长方向；在赤潮生态中潜力很高。",
        listOf("生长催化", "根系感知", "毒素与药性"),
    ),
    ApocalypseAbilityDefinition(
        "sound", "声波", ApocalypseAbilityRarity.Uncommon, "A",
        "控制局部声压和频率，可用于干扰、定位、驱散或短距冲击。",
        listOf("声压冲击", "静音区", "回声定位"),
    ),
    ApocalypseAbilityDefinition(
        "shadow", "折光隐匿", ApocalypseAbilityRarity.Rare, "A+",
        "扭曲局部光线降低存在感，并非真正消失；高速移动和强光都会增加破绽。",
        listOf("静态隐匿", "移动伪装", "光学诱饵"),
    ),
    ApocalypseAbilityDefinition(
        "mind_link", "精神链接", ApocalypseAbilityRarity.Rare, "A+",
        "在可信任对象间建立有限心智通道，高阶可共享感官或抵抗精神污染。",
        listOf("双向通讯", "感官共享", "精神屏障"),
    ),
    ApocalypseAbilityDefinition(
        "magnetic", "磁场", ApocalypseAbilityRarity.Rare, "S-",
        "感知并操纵局部磁场，对金属、电子设备和导航系统都有巨大潜力。",
        listOf("金属操控", "电磁脉冲", "磁场感知"),
    ),
    ApocalypseAbilityDefinition(
        "gravity", "重力偏转", ApocalypseAbilityRarity.Rare, "S",
        "改变很小范围内的受力方向和强度，初期消耗极高，成长上限很高。",
        listOf("压制", "轻量化", "轨迹偏转"),
    ),
    ApocalypseAbilityDefinition(
        "precognition", "短兆", ApocalypseAbilityRarity.Exceptional, "S",
        "只能捕捉数秒内高度危险的碎片可能性，不是稳定预知未来。",
        listOf("战斗短兆", "灾害短兆", "关键选择感应"),
    ),
    ApocalypseAbilityDefinition(
        "reconstruct", "物质校正", ApocalypseAbilityRarity.Exceptional, "S",
        "极小尺度修复或改变无生命材料的结构，高阶可成为基地建设和装备维护的战略能力。",
        listOf("修复", "强化材料", "精密重构"),
    ),
)

internal fun companionAbilityDefinition(choice: ApocalypseAbilityChoice): ApocalypseAbilityDefinition =
    apocalypseCompanionAbilityCatalog().firstOrNull { it.id == choice.abilityId }
        ?: apocalypseCompanionAbilityCatalog().first()

internal fun companionAbilityChoice(config: ApocalypseV3Config, characterId: String): ApocalypseAbilityChoice =
    config.partyAbilities[characterId] ?: ApocalypseAbilityChoice()

internal fun playerSpaceCapacityM3(level: Int): Int = when (level.coerceIn(1, 5)) {
    1 -> 48
    2 -> 160
    3 -> 600
    4 -> 2_400
    else -> 10_000
}

internal fun playerSpaceAttack(level: Int): String = when (level.coerceIn(1, 5)) {
    1 -> "尚未形成稳定攻击能力；可用瞬收/瞬取制造战术优势"
    2 -> "裂隙刃雏形：近距离切开薄层空间，次数有限"
    3 -> "空间刃 + 短距闪位：可绕过部分普通护甲"
    4 -> "空间锁与裂隙陷阱：能控制通道、保护自己并进行中距离杀伤"
    else -> "空间领域：位置干预、空间断裂与区域控制，但高强度使用仍有鸣蚀风险"
}

internal fun playerSpaceProgression(): List<Pair<String, String>> = listOf(
    "Lv.1 · 纳藏" to "48m³非生命储物空间。内部近似停滞，常温食物、药品和电池老化显著减缓；触碰即可快速收取，小件取物几乎不额外消耗体力。不能装活物，也不能隔空抢走被他人牢牢控制的物品。",
    "Lv.2 · 裂隙" to "容量约160m³，可给常用物品做空间标记；获得第一种真正攻击能力“裂隙刃雏形”，近距离切开薄层空间。你的体能仍弱，因此战斗核心是先手、距离和精确，而不是硬拼。",
    "Lv.3 · 闪位" to "容量约600m³，短距闪位稳定，空间刃可以在数米内成形；可把预先标记的装备瞬间送到手边，形成强大的机动与伏击优势。",
    "Lv.4 · 折叠" to "容量约2400m³，解锁折叠庇护、空间锁和裂隙陷阱。可以短时保护少数同伴或封死狭窄通道，但无法永久替代基地。",
    "Lv.5 · 领域" to "容量约10000m³。在有限区域内干预位置、距离和通道，空间断裂成为主力杀伤手段。你仍不是无代价无敌：持续领域会造成严重精神负荷和空间鸣蚀。",
)

internal fun playerSpacePrompt(stats: ApocalypseV3Stats): String = buildString {
    append("玩家固定异能=空间系 Lv.${stats.playerAbilityLevel}；")
    append("稳定容量约${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³；")
    append("攻击能力=${playerSpaceAttack(stats.playerAbilityLevel)}。")
    append("玩家体能偏弱，空间异能就是主角级金手指：生存、运输和后期战斗都应明显强于普通异能者，但不能无代价秒杀一切。")
}

internal fun abilityXpThresholdV3(level: Int): Int = when (level) {
    1 -> 4
    2 -> 7
    3 -> 11
    4 -> 16
    else -> 99
}

internal fun defaultApocalypseLongTermPlan(): List<String> = listOf(
    "灾前7—5日｜隐秘准备窗口：社会、商场、物流、支付和交通完全正常；玩家利用信息差采购水、食品、药、能源和工具，不得提前出现官方管制或全民恐慌。",
    "灾前4—3日｜异常验证窗口：零星病例、设备噪声和局部抢购开始出现，但城市总体正常；玩家仍能换店、网购、租车、找仓储并调整据点。",
    "灾前2—1日｜最后准备窗口：少数品类可能限购、排队或延迟，公共部门开始内部响应；不能一刀切封死囤货，玩家此前准备必须形成明显优势。",
    "主沉降0—72小时｜通信、电力、医院和交通开始断裂；第一批感染者仍以普通行尸为主；玩家必须把灾前准备转化为真实生存优势。",
    "灾后1—6周｜建立可持续据点，寻找稳定水源与药品；幸存者小团体形成；猎行者和环境异化第一次改变行动规则。",
    "灾后2—6个月｜城市被不同势力切割，晶核成为能源与异能成长资源；基地从藏身处升级为需要治理、生产和关系维护的共同体。",
    "灾后6—18个月｜赤潮生态形成稳定季节和迁徙带，高阶感染者出现统御与学习行为；不同人类秩序开始争夺技术、种源、水源与异能者。",
    "18个月以后｜揭开赤潮真正机制、B8与七日预警的关系；玩家的选择决定是修复旧文明、建立新秩序、与异化生态共存，还是走向另一种进化。",
)

internal fun defaultApocalypseFactionStates(): List<String> = listOf(
    "临江市应急联席会｜灾前仍按正常行政体系运行；灾后可能分裂为救援派、封锁派与资源控制派。",
    "北岸种源站｜掌握种子库、净水菌群和部分生态修复载体资料；人员少但战略价值极高。",
    "曙光供应联合体｜由物流、仓储、安保和零售网络拼成的资源联盟；效率极高，也可能把生存物资变成权力。",
    "零号病区研究组｜最早接触异常神经病例和晶核样本；内部对公开真相还是封存研究存在长期冲突。",
    "旧城互助网｜由居民、维修工、医护、司机和小商户构成，最弱也最贴近普通人的真实生存。",
    "赤潮高阶感染网络｜前期不存在统一意志；随着统御体出现，局部尸群才逐渐形成领地、诱捕与迁徙行为。",
)

internal fun defaultApocalypseCharacterArcs(): List<String> = listOf(
    "同行角色不只是战斗插件：每个人都要有恐惧、底线、欲望、专业技能和与玩家不同的风险判断。",
    "普通人角色不能因为无异能就失去价值；驾驶、维修、医学、谈判、组织、枪械、种植等能力都可成为关键。",
    "异能者成长要与选择、训练、晶核、创伤和人格互相影响；同一种异能也应因分支不同形成完全不同的战斗与生存方式。",
    "重要关系允许经历依赖、争执、信任、误解、牺牲和重新选择，但重大背叛必须提前有可以回看的原因。",
)

internal fun defaultApocalypseForeshadowPlan(): List<String> = listOf(
    "14:17红色通信雪花｜前期只是异常噪声；中期发现与电离层赤潮共振同步；后期才触及预警来源。",
    "B8防灾层｜灾前图纸异常；灾后成为第一批沉降样本与旧时代撤离工程的交叉点，不能第一章就把全部真相讲完。",
    "第一避难区警告｜先制造不确定性；必须通过人员调动、物资异常、地下记录等多条证据逐步说明为什么危险。",
    "玩家空间异能提前觉醒｜不是随机幸运；它与玩家对源质的特殊稳定共鸣有关，但真相应跨多个阶段逐步揭示。",
    "高阶感染者的学习行为｜先出现不合常理的小动作，再出现伏击和领地行为，最后才允许出现真正的统御网络。",
)

internal fun apocalypseWorldLoreV3(): List<Pair<String, String>> = listOf(
    "灾变起源" to "二十年前，各国为修复沙漠化、海洋缺氧和极端气候，逐步部署可在空气、水体与土壤中自复制的生态修复载体。一次异常太阳活动改变全球高层大气电荷环境，分散多年的载体发生同步共振与重组，形成覆盖降水、土壤、微生物和神经系统的‘赤潮’。它不是单一病毒，也不存在炸掉一个实验室就结束灾难的简单答案。",
    "植物" to "最初只是叶脉泛红、夜间气孔异常开放；随后根系会追逐热源、电流和矿物，部分植物释放致幻或神经毒性花粉，也有少数‘净生株’可辅助净化污染水。植物既可能成为食物、药材与过滤材料，也可能把废楼变成危险捕食结构。",
    "动物" to "犬科、鸦科和啮齿类最早出现方向感、协作和繁殖变化。大型动物会出现领地性异变种。动物不会统一变怪物，它们仍会饥饿、迁徙、护幼和争夺水源，因此生态链本身会推动安全区兴衰。",
    "土地与水" to "裸露土壤逐渐形成红锈色生物膜，盐分和重金属迁移速度加快。地表水最先失去可靠性，深层地下水、封闭水塔、净化膜和维护良好的管网成为基地核心资产。结晶菌毯既能采集源质，也会吸引感染者。",
    "气候" to "赤雨、逆温红雾、无雨雷暴、骤冷与热浪共同组成赤潮天气。天气会改变孢粉浓度、无线电距离、感染者活跃度、道路安全和异能稳定性，不只是背景。",
    "人类与异能生态" to "大多数人始终是普通人。灾后形成稳定异能的硬基线约8%，约92%没有稳定异能；常见异能以感官和体能强化为主，少见异能才出现元素、念动力和治疗，真正稀有的规则型能力极少。同行设置的是潜在分化，必须在灾后经历可见觉醒事件才能使用。普通人依然是文明运行主体。",
    "异能分化" to "异能不是固定职业。同一种火焰可以走爆燃、持续燃烧或高温塑形；千里眼可以走超远距、微观细节或高速目标追踪。初始强度、成长潜力、人格习惯和训练方式都会改变最终形态。",
    "感染者" to "早期行尸行动迟缓，主要依赖声音、血味和群体刺激。持续吸收源质后才逐渐出现速度型猎行者、骨甲/感官特化变异体、能够影响低阶尸群的统御体，以及极少数会改变整片区域生态的灾厄级个体。高阶感染者数量更少，但越来越会利用环境。",
    "源晶核" to "感染者脑内会逐渐形成源质结晶。普通行尸多数只有混浊碎核，能量少、杂质高；高阶晶核更完整，可用于异能成长、设备供能和研究。取核必须真正接近尸体，保存不当会持续释放污染，吸收过量会造成鸣蚀。",
    "主角空间系" to "玩家固定为空间系，而且是少见的高稳定共鸣。开局就拥有明显强于普通异能者的物资优势：48m³近停滞储物、快速收取与取物；随着晶核成长会获得裂隙刃、闪位、空间锁和领域。体能弱不是把主角废掉，而是让战斗方式转向空间控制、先手和精确杀伤。",
    "基地与文明" to "真正的基地必须解决水、食物、睡眠、医疗、排污、能源、出入口、防火和撤退路线。随着规模扩大，还要处理分工、权力、教育、生产、贸易与外部关系。末世后期玩的不只是杀丧尸，而是决定什么样的新社会值得活下去。",
)

internal fun initialApocalypseV3Director(): ApocalypseV3Director = ApocalypseV3Director(
    phase = "秩序正常 · 隐秘准备",
    location = "临江市 · 旧城区公寓",
    sceneGoal = "确认七日预警是否值得相信，并在社会秩序仍正常时开始第一轮准备。",
    activeThreads = listOf(
        "七日倒计时：异常正在从网络噪声变成现实迹象",
        "生存准备：水、食品、药、能源、运输和仓储需要在秩序崩溃前解决",
        "据点选择：高层住宅、地下设施、郊区仓库和工业园各有代价",
    ),
    hiddenThreads = listOf(
        "赤潮源于失控的全球生态修复载体与异常太阳活动叠加，而非单一病毒",
        "14:17红色通信雪花来自赤潮载体进入电离层后的共振",
        "被删除的B8防灾层保存着第一批赤潮沉降样本和一条旧时代撤离支线",
    ),
    worldFacts = listOf(
        "赤潮将在七天后进入临江市主沉降期，文明秩序会快速失效",
        "赤潮会同时改变植物、动物、微生物、人类和天气系统",
        "大多数人不会觉醒异能；异能稀有度、分支和成长潜力差异很大",
        "感染者的大脑会在进化过程中形成源晶核，越高阶越纯净",
        "玩家固定为空间系高稳定共鸣者，体能偏弱但拥有主角级生存与后期战斗潜力",
        "灾前社会仍然正常：金钱、法律、监控、交通、舆论和人际信任都有现实约束",
    ),
    locations = listOf(
        ApocalypseV3Location("home", "旧城区公寓", "当前住所。隐蔽、熟悉，但储水、防火和楼梯逃生是弱点。"),
        ApocalypseV3Location("market", "城南综合市场", "食品、水、五金、露营用品集中；大量采购会引起店员、邻居和支付系统注意。"),
        ApocalypseV3Location("hospital", "临江二院", "药品与急救资源丰富，也是最早出现异常发热和神经病例的地方。"),
        ApocalypseV3Location("warehouse", "西郊物流园", "冷库、仓储、货车和燃料集中；灾后会迅速成为各方争夺的补给点。"),
        ApocalypseV3Location("metro", "旧城地铁换乘站", "公开图纸只到B4，但旧施工档案中有被抹掉的B8。"),
        ApocalypseV3Location("seedbank", "北岸种源站", "城市边缘的小型种源与生态实验站，灾前并不起眼。", unlocked = false),
        ApocalypseV3Location("waterworks", "东江二水厂", "拥有深层取水口、药剂库和备用柴油机；一旦供水系统失效就会变成战略节点。", unlocked = false),
    ),
    assets = listOf(
        ApocalypseV3Asset("warning", ApocalypseV3AssetKind.Clue, "七日预警", "无法追溯来源的消息：七天后赤潮抵达临江市，不要去官方公布的第一避难区。"),
        ApocalypseV3Asset("b8_map", ApocalypseV3AssetKind.Map, "B8施工图残片", "旧城地铁公开线路以下还存在一层被红笔圈出的B8防灾层。"),
        ApocalypseV3Asset("prep_list", ApocalypseV3AssetKind.Document, "灾前生存清单", "饮水、耐储食物、常用药、净水、照明、移动电源、燃料、工具、卫生用品、种子、通讯与运输能力都需要提前规划。"),
    ),
    tension = 2,
)

internal fun initialApocalypseV3Scene(partyNames: List<String>): String {
    val names = partyNames.joinToString("、").ifBlank { "你想保护的人" }
    return """
        下午两点十七分，手机信号从满格瞬间掉到零。屏幕中央浮出一层细得像血丝的红色雪花，持续三秒，又忽然消失。

        一条没有号码、没有应用图标、无法转发也无法截图的文字压在所有窗口上方：“七日后，赤潮抵达临江市。”

        第二行更短：“不要去官方公布的第一避难区。”

        第三行停留了足足十秒：“如果你还想让${names}活下来，从今天开始准备。”

        消息消失后，相册里多出一张旧城地铁施工图。公开线路只到地下四层，最底下却有一个被红笔圈起来的“B8”。

        你伸手去放大图片，桌边一整箱矿泉水忽然轻了一下。不是掉下去了——它从现实里干干净净地消失，下一秒出现在意识深处一个足有小货车车厢大小的静止空间里。

        你很快发现这个空间远不止能装一把钥匙。大约四十八立方米，不能装活物，却几乎停止食物和药品的老化；只要亲手触碰，小件物品的收取和取出快得像一个念头。

        你的身体依旧算不上强。真让你扛着水跑十层楼，你大概会先喘得眼前发黑。但这份空间异能明显不是一个鸡肋——末世最缺的就是能安全保存、运输和随时取出的物资。

        更奇怪的是，在空间边缘偶尔会出现一道极细的黑线。它现在还无法稳定成攻击，可你本能地知道：等这份能力继续成长，那条线能切开的绝不会只是纸。

        窗外仍有人为了停车位按喇叭，楼下便利店循环播放第二件半价。新闻主播在谈周末高温。城市没有一点世界末日的样子。

        七天。秩序还在，钱还能花，车还能开，超市货架还是满的。你最大的优势不是力气，而是别人还没有开始准备时，你已经拥有一个能把整车物资悄无声息带走的空间。
    """.trimIndent()
}

internal fun splitApocalypseStoryPages(text: String, maxChars: Int = 112): List<String> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return listOf("……")
    val sourceBlocks = normalized.split(Regex("\\n\\s*\\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
    val pages = mutableListOf<String>()

    fun addPieces(block: String) {
        val sentences = block.split(Regex("(?<=[。！？!?；;])\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
        var current = ""
        sentences.forEach { sentence ->
            val units = if (sentence.length <= maxChars) {
                listOf(sentence)
            } else {
                sentence.split(Regex("(?<=[，,、：:])\\s*"))
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .flatMap { piece -> if (piece.length <= maxChars) listOf(piece) else piece.chunked(maxChars) }
            }
            units.forEach { unit ->
                if (current.isBlank()) {
                    current = unit
                } else if (current.length + unit.length <= maxChars) {
                    current += unit
                } else {
                    pages += current.trim()
                    current = unit
                }
            }
        }
        if (current.isNotBlank()) pages += current.trim()
    }

    sourceBlocks.forEach(::addPieces)
    return pages.filter(String::isNotBlank).ifEmpty { listOf(normalized.take(maxChars)) }
}

internal fun applyApocalypseV3Beat(stats: ApocalypseV3Stats, beat: ApocalypseV3Beat): ApocalypseV3Stats {
    var next = stats.copy(
        money = (stats.money + beat.moneyDelta).coerceIn(0, 9_999_999),
        food = (stats.food + beat.foodDelta).coerceIn(0, 999),
        water = (stats.water + beat.waterDelta).coerceIn(0, 999),
        medicine = (stats.medicine + beat.medicineDelta).coerceIn(0, 999),
        materials = (stats.materials + beat.materialsDelta).coerceIn(0, 999),
        crystalCores = (stats.crystalCores + beat.coresFound).coerceIn(0, 9999),
        playerAbilityXp = (stats.playerAbilityXp + beat.playerAbilityXpGain).coerceAtLeast(0),
        baseLevel = (stats.baseLevel + beat.baseDelta).coerceIn(0, 5),
        baseName = if (stats.baseLevel == 0 && beat.baseDelta > 0) "临时据点" else stats.baseName,
        health = (stats.health + beat.healthDelta).coerceIn(0, 100),
        stamina = (stats.stamina + beat.staminaDelta).coerceIn(0, 100),
        infection = (stats.infection + beat.infectionDelta).coerceIn(0, 100),
        morale = (stats.morale + beat.moraleDelta).coerceIn(0, 100),
    )
    while (next.playerAbilityLevel < 5 && next.playerAbilityXp >= abilityXpThresholdV3(next.playerAbilityLevel)) {
        next = next.copy(
            playerAbilityXp = next.playerAbilityXp - abilityXpThresholdV3(next.playerAbilityLevel),
            playerAbilityLevel = next.playerAbilityLevel + 1,
        )
    }
    return next
}

private fun encodeApocalypseV3Config(value: ApocalypseV3Config): JSONObject = JSONObject()
    .put("worldMode", value.worldMode)
    .put("autoDelayMillis", value.autoDelayMillis)
    .put("partyAbilities", JSONObject().apply {
        value.partyAbilities.forEach { (characterId, choice) ->
            put(characterId, JSONObject().put("abilityId", choice.abilityId).put("branch", choice.branch))
        }
    })

private fun decodeApocalypseV3Config(json: JSONObject): ApocalypseV3Config {
    val abilities = mutableMapOf<String, ApocalypseAbilityChoice>()
    json.optJSONObject("partyAbilities")?.let { objectJson ->
        val keys = objectJson.keys()
        while (keys.hasNext()) {
            val characterId = keys.next()
            val item = objectJson.optJSONObject(characterId) ?: continue
            abilities[characterId] = ApocalypseAbilityChoice(
                abilityId = item.optString("abilityId", "none"),
                branch = item.optString("branch", "普通人"),
            )
        }
    }
    return ApocalypseV3Config(
        worldMode = json.optString("worldMode", "标准异变").ifBlank { "标准异变" },
        autoDelayMillis = json.optLong("autoDelayMillis", 2_800L).coerceIn(1_600L, 5_000L),
        partyAbilities = abilities,
    )
}

private fun encodeApocalypseV3Save(value: ApocalypseV3Save): JSONObject = JSONObject()
    .put("id", value.id)
    .put("scene", value.scene)
    .put("partyIds", JSONArray(value.partyIds))
    .put("narration", value.narration)
    .put("director", encodeApocalypseV3Director(value.director))
    .put("stats", encodeApocalypseV3Stats(value.stats))
    .put("log", JSONArray(value.log))
    .put("updatedAt", value.updatedAt)

private fun decodeApocalypseV3Save(json: JSONObject): ApocalypseV3Save {
    val partyIds = json.optJSONArray("partyIds").v3Strings()
    val restoredDirector = json.optJSONObject("director")?.let(::decodeApocalypseV3Director)
        ?: initialApocalypseV3Director()
    return ApocalypseV3Save(
        id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
        scene = json.optInt("scene", 1).coerceAtLeast(1),
        partyIds = partyIds,
        narration = json.optString("narration").ifBlank { initialApocalypseV3Scene(emptyList()) },
        director = restoredDirector.copy(
            presentCharacterIds = if (restoredDirector.presentCharacterStateKnown) {
                restoredDirector.presentCharacterIds
            } else {
                partyIds
            },
            presentCharacterStateKnown = true,
        ),
        stats = json.optJSONObject("stats")?.let(::decodeApocalypseV3Stats) ?: ApocalypseV3Stats(),
        log = json.optJSONArray("log").v3Strings(),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
    )
}

private fun encodeApocalypseV3Director(value: ApocalypseV3Director): JSONObject = JSONObject()
    .put("phase", value.phase)
    .put("location", value.location)
    .put("sceneGoal", value.sceneGoal)
    .put("activeThreads", JSONArray(value.activeThreads))
    .put("hiddenThreads", JSONArray(value.hiddenThreads))
    .put("worldFacts", JSONArray(value.worldFacts))
    .put("longTermPlan", JSONArray(value.longTermPlan))
    .put("factionStates", JSONArray(value.factionStates))
    .put("characterArcs", JSONArray(value.characterArcs))
    .put("foreshadowPlan", JSONArray(value.foreshadowPlan))
    .put("storyThreads", encodeApocalypseStoryThreadsV5(value.storyThreads))
    .put("characterDossiers", encodeApocalypseCharacterDossiersV5(value.characterDossiers))
    .put("foreshadowLedger", encodeApocalypseForeshadowLedgerV5(value.foreshadowLedger))
    .put("recentBeatTypes", JSONArray(value.recentBeatTypes))
    .put("recentEmotionalTurns", JSONArray(value.recentEmotionalTurns))
    .put("awakenedCompanionIds", JSONArray(value.awakenedCompanionIds))
    .put("presentCharacterIds", JSONArray(value.presentCharacterIds))
    .put("presentCharacterStateKnown", value.presentCharacterStateKnown)
    .put("directorRefreshNeeded", value.directorRefreshNeeded)
    .put("locations", JSONArray().apply {
        value.locations.forEach { location ->
            put(JSONObject().put("id", location.id).put("name", location.name).put("detail", location.detail).put("unlocked", location.unlocked))
        }
    })
    .put("assets", JSONArray().apply {
        value.assets.forEach { asset ->
            put(
                JSONObject()
                    .put("id", asset.id)
                    .put("kind", asset.kind.name)
                    .put("title", asset.title)
                    .put("detail", asset.detail)
                    .put("quantity", asset.quantity)
                    .put("tag", asset.tag),
            )
        }
    })
    .put("tension", value.tension)
    .put("dayIndex", value.dayIndex)
    .put("clockMinutes", value.clockMinutes)
    .put("weather", value.weather)
    .put("temperatureC", value.temperatureC)

private fun decodeApocalypseV3Director(json: JSONObject): ApocalypseV3Director {
    val defaults = initialApocalypseV3Director()
    val restoredDayIndex = json.optInt("dayIndex", defaults.dayIndex).coerceIn(-30, 9999)
    return ApocalypseV3Director(
        phase = apocalypsePhaseForDayV5(restoredDayIndex),
        location = json.optString("location", defaults.location),
        sceneGoal = json.optString("sceneGoal").ifBlank { defaults.sceneGoal },
        activeThreads = json.optJSONArray("activeThreads").v3Strings().ifEmpty { defaults.activeThreads },
        hiddenThreads = json.optJSONArray("hiddenThreads").v3Strings().ifEmpty { defaults.hiddenThreads },
        worldFacts = sanitizePrematureWorldFactsV5(
            restoredDayIndex,
            json.optJSONArray("worldFacts").v3Strings().ifEmpty { defaults.worldFacts },
        ),
        locations = json.optJSONArray("locations").v3Objects { item ->
            ApocalypseV3Location(item.optString("id"), item.optString("name"), item.optString("detail"), item.optBoolean("unlocked", true))
        }.ifEmpty { defaults.locations },
        assets = json.optJSONArray("assets").v3Objects { item ->
            ApocalypseV3Asset(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                kind = parseV3AssetKind(item.optString("kind")),
                title = item.optString("title").ifBlank { "未知物品" },
                detail = item.optString("detail"),
                quantity = item.optInt("quantity", 1).coerceAtLeast(1),
                tag = item.optString("tag"),
            )
        }.ifEmpty { defaults.assets },
        tension = json.optInt("tension", defaults.tension).coerceIn(1, 10),
        longTermPlan = json.optJSONArray("longTermPlan").v3Strings().ifEmpty { defaults.longTermPlan },
        factionStates = json.optJSONArray("factionStates").v3Strings().ifEmpty { defaults.factionStates },
        characterArcs = json.optJSONArray("characterArcs").v3Strings().ifEmpty { defaults.characterArcs },
        foreshadowPlan = json.optJSONArray("foreshadowPlan").v3Strings().ifEmpty { defaults.foreshadowPlan },
        storyThreads = decodeApocalypseStoryThreadsV5(json.optJSONArray("storyThreads"))
            .ifEmpty { defaults.storyThreads },
        characterDossiers = decodeApocalypseCharacterDossiersV5(json.optJSONArray("characterDossiers")),
        foreshadowLedger = decodeApocalypseForeshadowLedgerV5(json.optJSONArray("foreshadowLedger"))
            .ifEmpty { defaults.foreshadowLedger },
        recentBeatTypes = json.optJSONArray("recentBeatTypes").v3Strings().takeLast(8),
        recentEmotionalTurns = json.optJSONArray("recentEmotionalTurns").v3Strings().takeLast(8),
        awakenedCompanionIds = json.optJSONArray("awakenedCompanionIds").v3Strings().distinct().take(12),
        presentCharacterIds = json.optJSONArray("presentCharacterIds").v3Strings().distinct().take(10),
        presentCharacterStateKnown = json.optBoolean("presentCharacterStateKnown", json.has("presentCharacterIds")),
        directorRefreshNeeded = json.optBoolean("directorRefreshNeeded", false),
        dayIndex = restoredDayIndex,
        clockMinutes = json.optInt("clockMinutes", defaults.clockMinutes).coerceIn(0, 1439),
        weather = json.optString("weather", defaults.weather).ifBlank { defaults.weather }.take(40),
        temperatureC = json.optInt("temperatureC", defaults.temperatureC).coerceIn(-35, 55),
    )
}

private fun encodeApocalypseV3Stats(value: ApocalypseV3Stats): JSONObject = JSONObject()
    .put("money", value.money)
    .put("food", value.food)
    .put("water", value.water)
    .put("medicine", value.medicine)
    .put("materials", value.materials)
    .put("crystalCores", value.crystalCores)
    .put("playerAbilityLevel", value.playerAbilityLevel)
    .put("playerAbilityXp", value.playerAbilityXp)
    .put("baseLevel", value.baseLevel)
    .put("baseName", value.baseName)
    .put("health", value.health)
    .put("stamina", value.stamina)
    .put("infection", value.infection)
    .put("morale", value.morale)

private fun decodeApocalypseV3Stats(json: JSONObject): ApocalypseV3Stats = ApocalypseV3Stats(
    money = json.optInt("money", 3_000).coerceIn(0, 9_999_999),
    food = json.optInt("food", 2).coerceAtLeast(0),
    water = json.optInt("water", 2).coerceAtLeast(0),
    medicine = json.optInt("medicine", 1).coerceAtLeast(0),
    materials = json.optInt("materials", 0).coerceAtLeast(0),
    crystalCores = json.optInt("crystalCores", 0).coerceAtLeast(0),
    // V2 used abilityLevel/abilityXp. Read those keys too so existing saves keep progress.
    playerAbilityLevel = json.optInt("playerAbilityLevel", json.optInt("abilityLevel", 1)).coerceIn(1, 5),
    playerAbilityXp = json.optInt("playerAbilityXp", json.optInt("abilityXp", 0)).coerceAtLeast(0),
    baseLevel = json.optInt("baseLevel", 0).coerceIn(0, 5),
    baseName = json.optString("baseName", "尚未建立"),
    health = json.optInt("health", 100).coerceIn(0, 100),
    stamina = json.optInt("stamina", 85).coerceIn(0, 100),
    infection = json.optInt("infection", 0).coerceIn(0, 100),
    morale = json.optInt("morale", 72).coerceIn(0, 100),
)

private fun parseV3AssetKind(raw: String): ApocalypseV3AssetKind = when (raw.lowercase()) {
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

private fun JSONArray?.v3Strings(): List<String> = buildList {
    val array = this@v3Strings ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.v3Objects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@v3Objects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}
