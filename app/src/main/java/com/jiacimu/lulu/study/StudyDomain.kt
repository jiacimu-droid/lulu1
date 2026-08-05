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

enum class StudyDrawKind(val label: String, val rarity: StudyRarity) {
    OutfitFragment("画卷专属碎片", StudyRarity.Normal),
    DouyinTicket("抖音时长券 · 20分钟", StudyRarity.Rare),
    GameRoundTicket("游戏局数券 · 4局", StudyRarity.Rare),
    TheaterFragment("小剧场券", StudyRarity.Rare),
    GameTicket("电影券", StudyRarity.Epic),
    VideoCard("视频解锁卡", StudyRarity.Epic),
    AnimeTicket("影视剧一季兑换券", StudyRarity.Rainbow),
}

enum class StudyEntertainmentKind(val label: String) {
    Douyin("抖音时长券"),
    GameRound("游戏局数券"),
    Theater("小剧场"),
    Game("电影券"),
    Video("视频解锁卡"),
    Anime("影视剧一季兑换券"),
}

enum class StudyShopReward {
    SingleTicket, DouyinTicket, GameRoundTicket, TheaterFragment, GameTicket, VideoCard, AnimeTicket,
}

data class StudyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val completed: Boolean = false,
    val pomodoroTarget: Int = 1,
    val pomodoroCompleted: Int = 0,
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
    val kind: StudyDrawKind,
    val title: String,
    val inventoryChanged: Boolean,
) {
    val rarity: StudyRarity get() = kind.rarity
}

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
    val videoCards: Int = 0,
    val animeTickets: Int = 0,
    val unlockedScrolls: List<String> = emptyList(),
    val unlockedVideos: List<String> = emptyList(),
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
    val schemaVersion: Int = 6,
    val activeDate: String = LocalDate.now().toString(),
    val profile: StudyProfile = StudyProfile(),
    val inventory: StudyInventory = StudyInventory(),
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

internal const val BLUE_FRAGMENTS_PER_SCROLL = 10
internal const val SINGLE_DRAW_COST = 100
internal const val TEN_DRAW_COST = 800
internal const val NON_NORMAL_PITY = 30
internal const val STUDY_REWARD_INTERVAL_MINUTES = 5
internal const val STUDY_REWARD_PRAISE = 100
internal const val TASK_COMPLETION_PRAISE = 100

internal val blueFragmentCatalog = listOf(
    "星穹图书馆", "樱吹雪剑道场", "深海回廊", "永夜花庭", "云上列车",
    "琉璃沙漠", "机械蝴蝶", "月光浴场", "废墟花园", "倒悬都市",
    "雨后天台", "星砂邮局", "薄荷钟楼", "雾港旧船", "玻璃温室",
    "极光书房", "柠檬海岸", "雪夜便利店", "琥珀剧院", "云雀庭院",
)
internal val videoCatalog = listOf("完成第一小时", "雨天自习室", "角色的监督留言", "深夜收尾", "周计划达成")
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

internal fun defaultShop(date: LocalDate): List<StudyShopItem> {
    val random = Random(date.toString().hashCode())
    val pool = listOf(
        StudyShopReward.DouyinTicket to 6,
        StudyShopReward.GameRoundTicket to 3,
        StudyShopReward.TheaterFragment to 3,
        StudyShopReward.GameTicket to 2,
        StudyShopReward.VideoCard to 2,
        StudyShopReward.AnimeTicket to 1,
        StudyShopReward.SingleTicket to 83,
    )
    return (1..3).map { slot ->
        val reward = weightedShopReward(pool, random)
        reward.toShopItem("${date}-$slot-${reward.name}")
    }
}

private fun weightedShopReward(pool: List<Pair<StudyShopReward, Int>>, random: Random): StudyShopReward {
    var roll = random.nextInt(pool.sumOf { it.second })
    pool.forEach { (item, weight) ->
        if (roll < weight) return item
        roll -= weight
    }
    return pool.last().first
}

private fun StudyShopReward.toShopItem(id: String): StudyShopItem = when (this) {
    StudyShopReward.SingleTicket -> StudyShopItem(id, "单抽券", "用于一次抽卡", 100, this)
    StudyShopReward.DouyinTicket -> StudyShopItem(id, "抖音时长券", "可使用20分钟", 500, this)
    StudyShopReward.GameRoundTicket -> StudyShopItem(id, "游戏局数券", "可畅玩4局", 600, this)
    StudyShopReward.TheaterFragment -> StudyShopItem(id, "小剧场券", "可生成或续写小剧场1章", 600, this)
    StudyShopReward.GameTicket -> StudyShopItem(id, "电影券", "可观看1部电影", 1_000, this)
    StudyShopReward.VideoCard -> StudyShopItem(id, "视频解锁卡", "解锁一项视频收藏", 1_000, this)
    StudyShopReward.AnimeTicket -> StudyShopItem(id, "影视剧一季兑换券", "可兑换一季影视剧", 2_000, this)
}