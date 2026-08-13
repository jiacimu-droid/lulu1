package com.jiacimu.lulu.study

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

enum class StudyTaskSource { User, Preset, WeeklyPlan, MonthlyPlan, AiSchedule }
enum class StudyPlanRange { Weekly, Monthly }

enum class StudyRarity(val label: String) {
    Normal("蓝色"), Rare("紫色"), Epic("金色"), Rainbow("彩色"),
}

enum class StudyGachaRewardType {
    Douyin,
    GameRound,
    Theater,
    Movie,
    Anime,
    Custom,
}

data class StudyGachaRule(
    val id: String,
    val title: String,
    val rarity: StudyRarity,
    val probabilityPercent: Double,
    val amountPerDraw: Int = 1,
    val type: StudyGachaRewardType = StudyGachaRewardType.Custom,
) {
    val custom: Boolean get() = type == StudyGachaRewardType.Custom
}

enum class StudyEntertainmentKind(val label: String) {
    Douyin("抖音时长券"),
    GameRound("游戏局数券"),
    Theater("小剧场"),
    Game("电影券"),
    Anime("影视剧一季兑换券"),
}

enum class StudyShopReward {
    SingleTicket, TenTicket, DouyinTicket, GameRoundTicket, TheaterFragment, GameTicket, AnimeTicket,
}

data class StudyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val completed: Boolean = false,
    val source: StudyTaskSource = StudyTaskSource.User,
    val rewarded: Boolean = false,
)

data class StudyScheduleBlock(
    val id: String = UUID.randomUUID().toString(),
    val date: String,
    val start: String,
    val end: String,
    val title: String,
    val completed: Boolean = false,
)

data class StudyPlanItem(
    val id: String = UUID.randomUUID().toString(),
    val range: StudyPlanRange,
    val title: String,
    val note: String = "",
    val completed: Boolean = false,
)

data class StudyTip(val id: String = UUID.randomUUID().toString(), val text: String, val date: String)
data class StudyEvent(val id: String = UUID.randomUUID().toString(), val title: String, val detail: String, val createdAt: Instant = Instant.now())

data class StudyDrawResult(
    val id: String = UUID.randomUUID().toString(),
    val rewardRuleId: String? = null,
    val title: String,
    val rarity: StudyRarity,
    val amount: Int = 1,
    val inventoryChanged: Boolean,
)

data class StudyAchievement(
    val id: String,
    val title: String,
    val description: String,
    val progress: Int,
    val target: Int,
    val rewardSingleTickets: Int = 1,
    val rewardPraisePoints: Int = 20,
    val claimed: Boolean = false,
) { val unlocked: Boolean get() = progress >= target }

data class StudyShopItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val cost: Int,
    val reward: StudyShopReward,
    val amount: Int = 1,
    val purchased: Boolean = false,
)

data class StudyInventory(
    val singleTickets: Int = 3,
    val tenTickets: Int = 1,
    val blueFragments: Map<String, Int> = emptyMap(),
    val douyinTickets: Int = 0,
    val gameRoundTickets: Int = 0,
    val theaterFragments: Int = 0,
    // 为兼容已有存档保留旧字段名；这里现在存放电影券数量。
    val gameTickets: Int = 0,
    val animeTickets: Int = 0,
    val customRewards: Map<String, Int> = emptyMap(),
    val unlockedScrolls: List<String> = emptyList(),
    val unlockedTheaters: List<String> = emptyList(),
)

data class StudyProfile(
    val selectedCharacterId: String = "lulu",
    val praisePoints: Int = 0,
    // 旧存档字段名保留为 experience；现在表示累计获得的夸夸值，不再用于等级。
    val experience: Int = 0,
    val streakDays: Int = 0,
    val lastStudyDate: String = "",
    val lastSignInDate: String = "",
    val totalStudyMinutes: Int = 0,
    val totalPomodoros: Int = 0,
    val totalTasksCompleted: Int = 0,
    val totalDraws: Int = 0,
    val totalSignIns: Int = 0,
    val vocabularyReviewed: Int = 0,
    // 仅用于兼容旧存档，等级功能已经移除。
    val claimedLevels: Set<Int> = emptySet(),
    val sleepRewardDate: String = "",
    /** Ledger entries use yyyy-MM-dd:sleep / yyyy-MM-dd:wake so each half can be reconsidered once. */
    val sleepRewardGrantedKeys: Set<String> = emptySet(),
    val inactivityPenaltyDate: String = "",
) {
    val totalPraiseEarned: Int get() = experience
}

data class PomodoroState(
    val selectedMinutes: Int = 25,
    val remainingSeconds: Int = 25 * 60,
    val running: Boolean = false,
    val voiceEnabled: Boolean = false,
    val endAtEpochMillis: Long = 0L,
)

data class StudyState(
    val schemaVersion: Int = 7,
    val activeDate: String = LocalDate.now().toString(),
    val profile: StudyProfile = StudyProfile(),
    val inventory: StudyInventory = StudyInventory(),
    val gachaRules: List<StudyGachaRule> = defaultGachaRules(),
    val tasks: List<StudyTask> = defaultTasks(LocalDate.now()),
    val schedules: List<StudyScheduleBlock> = emptyList(),
    val planItems: List<StudyPlanItem> = defaultPlanItems(),
    val tips: List<StudyTip> = defaultTips(LocalDate.now()),
    val events: List<StudyEvent> = emptyList(),
    val achievements: List<StudyAchievement> = emptyList(),
    val shopItems: List<StudyShopItem> = defaultShop(LocalDate.now()),
    val shopDate: String = LocalDate.now().toString(),
    val manualShopRefreshDate: String = "",
    val drawsSinceNonNormal: Int = 0,
    val pendingRewardMinutes: Int = 0,
    val superMomentAvailable: Boolean = false,
    val superMomentClaimedDate: String = "",
    val pomodoro: PomodoroState = PomodoroState(),
    val dailyStudyMinutes: Map<String, Int> = emptyMap(),
    val dailyPomodoros: Map<String, Int> = emptyMap(),
    val dailyVocabularyReviewed: Map<String, Int> = emptyMap(),
) {
    fun studyMinutes(date: LocalDate = LocalDate.now()): Int = dailyStudyMinutes[date.toString()] ?: 0
    fun pomodoros(date: LocalDate = LocalDate.now()): Int = dailyPomodoros[date.toString()] ?: 0
    fun vocabulary(date: LocalDate = LocalDate.now()): Int = dailyVocabularyReviewed[date.toString()] ?: 0
}

internal const val BLUE_FRAGMENTS_PER_SCROLL = 20
internal const val BLUE_FULL_DUPLICATE_RETURN_PRAISE = 20
internal const val SINGLE_DRAW_COST = 100
internal const val TEN_DRAW_COST = 800
internal const val NON_NORMAL_PITY = 30
internal const val STUDY_REWARD_INTERVAL_MINUTES = 5
internal const val STUDY_REWARD_PRAISE = 100
internal const val TASK_COMPLETION_PRAISE = 100

internal const val GACHA_ID_DOUYIN = "builtin_douyin"
internal const val GACHA_ID_GAME_ROUND = "builtin_game_round"
internal const val GACHA_ID_THEATER = "builtin_theater"
internal const val GACHA_ID_MOVIE = "builtin_movie"
internal const val GACHA_ID_ANIME = "builtin_anime"

internal fun defaultGachaRules(): List<StudyGachaRule> = listOf(
    StudyGachaRule(
        id = GACHA_ID_DOUYIN,
        title = "抖音时长券 · 20分钟",
        rarity = StudyRarity.Rare,
        probabilityPercent = 2.5,
        amountPerDraw = 1,
        type = StudyGachaRewardType.Douyin,
    ),
    StudyGachaRule(
        id = GACHA_ID_GAME_ROUND,
        title = "游戏局数券 · 4局",
        rarity = StudyRarity.Rare,
        probabilityPercent = 2.0,
        amountPerDraw = 1,
        type = StudyGachaRewardType.GameRound,
    ),
    StudyGachaRule(
        id = GACHA_ID_THEATER,
        title = "小剧场券",
        rarity = StudyRarity.Rare,
        probabilityPercent = 1.0,
        amountPerDraw = 3,
        type = StudyGachaRewardType.Theater,
    ),
    StudyGachaRule(
        id = GACHA_ID_MOVIE,
        title = "电影券",
        rarity = StudyRarity.Epic,
        probabilityPercent = 0.8,
        amountPerDraw = 1,
        type = StudyGachaRewardType.Movie,
    ),
    StudyGachaRule(
        id = GACHA_ID_ANIME,
        title = "影视剧一季兑换券",
        rarity = StudyRarity.Rainbow,
        probabilityPercent = 0.4,
        amountPerDraw = 1,
        type = StudyGachaRewardType.Anime,
    ),
)

internal fun repairGachaRules(source: List<StudyGachaRule>): List<StudyGachaRule> {
    val defaultsById = defaultGachaRules().associateBy(StudyGachaRule::id)
    return source.asSequence()
        .filter { it.id.isNotBlank() }
        .distinctBy(StudyGachaRule::id)
        .mapNotNull { saved ->
            val builtin = defaultsById[saved.id]
            when {
                builtin != null -> saved.copy(
                    title = saved.title.trim().ifBlank { builtin.title }.take(60),
                    rarity = saved.rarity.takeIf { it != StudyRarity.Normal } ?: builtin.rarity,
                    probabilityPercent = saved.probabilityPercent.coerceIn(0.0, 100.0),
                    amountPerDraw = saved.amountPerDraw.coerceIn(1, 999),
                    type = builtin.type,
                )
                saved.type == StudyGachaRewardType.Custom && saved.title.isNotBlank() -> saved.copy(
                    title = saved.title.trim().take(60),
                    rarity = saved.rarity.takeIf { it != StudyRarity.Normal } ?: StudyRarity.Rare,
                    probabilityPercent = saved.probabilityPercent.coerceIn(0.0, 100.0),
                    amountPerDraw = saved.amountPerDraw.coerceIn(1, 999),
                    type = StudyGachaRewardType.Custom,
                )
                else -> null
            }
        }
        .toList()
}

internal val blueFragmentCatalog = listOf(
    "星穹图书馆", "樱吹雪剑道场", "深海回廊", "永夜花庭", "云上列车",
    "琉璃沙漠", "机械蝴蝶", "月光浴场", "废墟花园", "倒悬都市",
)
internal val theaterCatalog = listOf(
    "少卿今天不早朝", "星舰AI说他爱上我了", "废土便利店的草莓糖", "把魔尊契约当话本",
    "被献祭给龙之后", "捡到S级机甲", "我把修真界改成5A景区", "午夜出租车",
    "会整理书桌的幽灵", "欢迎来到心动游戏", "女王陛下的打脸法庭", "末世便利店女王",
    "女尊朝的首席狼臣", "前男友重生但我是反派", "原始部落的露字祭司", "性转恋综大逃杀",
)

internal fun defaultTasks(date: LocalDate): List<StudyTask> = listOf(
    StudyTask(title = "英语词汇", date = date.toString(), source = StudyTaskSource.Preset),
    StudyTask(title = "英语真题", date = date.toString(), source = StudyTaskSource.Preset),
    StudyTask(title = "专业课听课", date = date.toString(), source = StudyTaskSource.Preset),
    StudyTask(title = "背诵", date = date.toString(), source = StudyTaskSource.Preset),
    StudyTask(title = "框架", date = date.toString(), source = StudyTaskSource.Preset),
    StudyTask(title = "题目", date = date.toString(), source = StudyTaskSource.Preset),
)
internal fun defaultPlanItems(): List<StudyPlanItem> = emptyList()
internal fun defaultTips(date: LocalDate): List<StudyTip> = listOf(
    StudyTip(text = "先开始一个最小番茄钟，再决定是否延长。", date = date.toString()),
    StudyTip(text = "真题训练优先记录错因，不用为了速度跳过复盘。", date = date.toString()),
)

private const val CUSTOM_SHOP_RULE_MARKER = "|gacha-rule|"

private data class StudyShopCandidate(
    val key: String,
    val weight: Int,
    val build: (String) -> StudyShopItem,
)

/**
 * The shop always has two draw-ticket slots plus one collection slot. The collection slot is built
 * from the user's current gacha/collection rules, so newly-added custom rewards are eligible on the
 * next deterministic refresh instead of being excluded by a hard-coded built-in pool.
 */
internal fun defaultShop(
    date: LocalDate,
    gachaRules: List<StudyGachaRule> = defaultGachaRules(),
): List<StudyShopItem> {
    val random = Random(date.toString().hashCode())
    val collectionCandidates = repairGachaRules(gachaRules)
        .mapNotNull { rule ->
            val weight = when (rule.rarity) {
                StudyRarity.Normal -> 0
                StudyRarity.Rare -> 6
                StudyRarity.Epic -> 3
                StudyRarity.Rainbow -> 1
            }
            if (weight <= 0) null else StudyShopCandidate("rule:${rule.id}", weight) { id -> rule.toShopItem(id) }
        }

    val result = mutableListOf(
        StudyShopReward.SingleTicket.toShopItem("${date}-1-SingleTicket"),
        StudyShopReward.TenTicket.toShopItem("${date}-2-TenTicket"),
    )
    if (collectionCandidates.isNotEmpty()) {
        val candidate = weightedShopCandidate(collectionCandidates, random)
        result += candidate.build("${date}-3")
    } else {
        // Damaged/empty collection settings should not leave the third card blank.
        result += StudyShopReward.SingleTicket.toShopItem("${date}-3-SingleTicket-fallback")
    }
    return result
}

private fun weightedShopCandidate(pool: List<StudyShopCandidate>, random: Random): StudyShopCandidate {
    var roll = random.nextInt(pool.sumOf { it.weight }.coerceAtLeast(1))
    pool.forEach { candidate ->
        if (roll < candidate.weight) return candidate
        roll -= candidate.weight
    }
    return pool.last()
}

internal fun StudyRarity.shopCost(): Int = when (this) {
    StudyRarity.Normal -> 1_000
    StudyRarity.Rare -> 1_000
    StudyRarity.Epic -> 2_000
    StudyRarity.Rainbow -> 3_000
}

internal fun StudyShopReward.shopCost(): Int = when (this) {
    StudyShopReward.SingleTicket -> 80
    StudyShopReward.TenTicket -> 650
    StudyShopReward.DouyinTicket -> 1_000
    StudyShopReward.GameRoundTicket -> 1_000
    StudyShopReward.TheaterFragment -> 1_000
    StudyShopReward.GameTicket -> 2_000
    StudyShopReward.AnimeTicket -> 3_000
}

internal fun StudyShopItem.customShopRuleId(): String? =
    id.substringAfter(CUSTOM_SHOP_RULE_MARKER, "").trim().takeIf(String::isNotBlank)

private fun StudyGachaRule.toShopItem(baseId: String): StudyShopItem {
    val amount = amountPerDraw.coerceIn(1, 999)
    val customId = if (custom) "$baseId$CUSTOM_SHOP_RULE_MARKER$id" else "$baseId-$id"
    val reward = when (type) {
        StudyGachaRewardType.Douyin -> StudyShopReward.DouyinTicket
        StudyGachaRewardType.GameRound -> StudyShopReward.GameRoundTicket
        StudyGachaRewardType.Theater -> StudyShopReward.TheaterFragment
        StudyGachaRewardType.Movie -> StudyShopReward.GameTicket
        StudyGachaRewardType.Anime -> StudyShopReward.AnimeTicket
        // Custom items use a harmless legacy enum placeholder; buyShopItem detects the encoded rule id
        // before the legacy reward switch and credits the matching custom inventory instead.
        StudyGachaRewardType.Custom -> StudyShopReward.SingleTicket
    }
    val amountSuffix = if (amount > 1) " ×$amount" else ""
    return StudyShopItem(
        id = customId,
        title = "$title$amountSuffix",
        subtitle = if (custom) {
            "${rarity.label}收藏商品 · 来自你当前的自定义收藏池"
        } else {
            "${rarity.label}收藏商品 · 来自当前收藏池"
        },
        cost = rarity.shopCost(),
        reward = reward,
        amount = amount,
    )
}

private fun StudyShopReward.toShopItem(id: String): StudyShopItem = when (this) {
    StudyShopReward.SingleTicket -> StudyShopItem(id, "单抽券", "商店价，比直接单抽省20夸夸值", shopCost(), this)
    StudyShopReward.TenTicket -> StudyShopItem(id, "十连券", "商店限定折扣，比直接十连省150夸夸值", shopCost(), this)
    StudyShopReward.DouyinTicket -> StudyShopItem(id, "抖音时长券", "紫色收藏商品 · 可使用20分钟", shopCost(), this)
    StudyShopReward.GameRoundTicket -> StudyShopItem(id, "游戏局数券", "紫色收藏商品 · 可畅玩4局", shopCost(), this)
    StudyShopReward.TheaterFragment -> StudyShopItem(id, "小剧场券", "紫色收藏商品 · 可生成或续写小剧场1章", shopCost(), this)
    StudyShopReward.GameTicket -> StudyShopItem(id, "电影券", "金色收藏商品 · 可观看1部电影", shopCost(), this)
    StudyShopReward.AnimeTicket -> StudyShopItem(id, "影视剧一季兑换券", "彩色收藏商品 · 可兑换一整季", shopCost(), this)
}
