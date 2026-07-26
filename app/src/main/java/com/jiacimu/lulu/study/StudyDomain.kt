package com.jiacimu.lulu.study

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class StudyTaskSource { User, Preset, WeeklyPlan, MonthlyPlan, AiSchedule }
enum class StudyPlanRange { Weekly, Monthly }
enum class StudyDrawKind { BlueFragment, PurpleFragment, VideoFragment, TheaterFragment }
enum class StudyEntertainmentKind(val label: String) { Douyin("抖音视频"), SideStory("番外小剧场") }
enum class StudyShopReward { SingleTicket, TenTicket, SafePurpleTicket, MysteryBox, UniversalBlueFragment, PraisePoints }
enum class StudySuperChoice(val label: String) { DoublePraise("双倍夸夸值"), MysteryBoxes("神秘盒子×2"), DrawTickets("单抽券×3") }

data class StudyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val completed: Boolean = false,
    val pomodoroTarget: Int = 1,
    val pomodoroCompleted: Int = 0,
    val source: StudyTaskSource = StudyTaskSource.User,
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

data class StudyTip(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val date: String,
)

data class StudyEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val detail: String,
    val createdAt: Instant = Instant.now(),
)

data class StudyDrawResult(
    val id: String = UUID.randomUUID().toString(),
    val kind: StudyDrawKind,
    val title: String,
    val rarityLabel: String,
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
) {
    val unlocked: Boolean get() = progress >= target
}

data class StudyShopItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val cost: Int,
    val reward: StudyShopReward,
    val amount: Int,
    val stock: Int,
    val purchased: Int = 0,
) {
    val remaining: Int get() = (stock - purchased).coerceAtLeast(0)
}

data class StudyInventory(
    val singleTickets: Int = 3,
    val tenTickets: Int = 1,
    val safePurpleTickets: Int = 1,
    val mysteryBoxes: Int = 0,
    val universalBlueFragments: Int = 0,
    val blueFragments: Map<String, Int> = emptyMap(),
    val purpleFragments: Int = 0,
    val entertainmentFragments: Map<StudyEntertainmentKind, Int> = emptyMap(),
    val unlockedScrolls: List<String> = emptyList(),
    val unlockedVideos: List<String> = emptyList(),
    val unlockedTheaters: List<String> = emptyList(),
)

data class StudyProfile(
    val selectedCharacterId: String = "lulu",
    val praisePoints: Int = 0,
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
    val claimedLevels: Set<Int> = emptySet(),
    val sleepRewardDate: String = "",
    val inactivityPenaltyDate: String = "",
) {
    val level: Int get() = StudyLevels.levelForExperience(experience)
}

data class PomodoroState(
    val selectedMinutes: Int = 25,
    val remainingSeconds: Int = 25 * 60,
    val running: Boolean = false,
    val voiceEnabled: Boolean = false,
)

data class StudyState(
    val schemaVersion: Int = 2,
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
    val shopRefreshCount: Int = 0,
    val drawsSinceNonNormal: Int = 0,
    val safeDrawUsedDate: String = "",
    val superMomentAvailable: Boolean = false,
    val superMomentClaimedDate: String = "",
    val pomodoro: PomodoroState = PomodoroState(),
)

object StudyLevels {
    val thresholds = listOf(0, 30, 80, 150, 240, 360, 510, 690, 900, 1140, 1410, 1710, 2040, 2400, 2790)
    fun levelForExperience(experience: Int): Int = thresholds.indexOfLast { experience >= it }.coerceAtLeast(0) + 1
    fun currentLevelStart(level: Int): Int = thresholds.getOrElse((level - 1).coerceAtLeast(0)) { thresholds.last() }
    fun nextLevelTarget(level: Int): Int = thresholds.getOrElse(level) { thresholds.last() + 500 }
}

internal val blueFragmentCatalog = listOf(
    "清晨书桌", "夜灯笔记", "单词卡片", "真题铅笔",
    "刑法目录", "民法法条", "宪法图谱", "法制史卷轴",
    "番茄时钟", "安静耳机", "热茶", "小烟花",
    "计划贴纸", "错题本", "倒计时牌", "录取通知",
)
internal val videoCatalog = listOf("完成第一小时", "雨天自习室", "角色的监督留言", "深夜收尾", "周计划达成")
internal val theaterCatalog = listOf("考前一天", "收到录取通知后", "图书馆闭馆广播", "角色替你保管手机", "最后一次模拟考试")

internal fun defaultTasks(date: LocalDate): List<StudyTask> = listOf(
    StudyTask(title = "考研英语真题训练", date = date.toString(), pomodoroTarget = 2, source = StudyTaskSource.Preset),
    StudyTask(title = "词汇复习", date = date.toString(), pomodoroTarget = 1, source = StudyTaskSource.Preset),
    StudyTask(title = "专业课重点整理", date = date.toString(), pomodoroTarget = 2, source = StudyTaskSource.Preset),
)

internal fun defaultPlanItems(): List<StudyPlanItem> = listOf(
    StudyPlanItem(range = StudyPlanRange.Weekly, title = "完成本周英语真题与错题复盘"),
    StudyPlanItem(range = StudyPlanRange.Weekly, title = "专业课推进到本周节点"),
    StudyPlanItem(range = StudyPlanRange.Monthly, title = "完成当月专业课阶段目标"),
    StudyPlanItem(range = StudyPlanRange.Monthly, title = "整理当月英语错误类型"),
)

internal fun defaultTips(date: LocalDate): List<StudyTip> = listOf(
    StudyTip(text = "先开始一个最小番茄钟，再决定是否延长。", date = date.toString()),
    StudyTip(text = "真题训练优先记录错因，不用为了速度跳过复盘。", date = date.toString()),
)

internal fun defaultShop(date: LocalDate): List<StudyShopItem> {
    val seed = date.toEpochDay().toInt()
    val rotating = listOf(
        StudyShopItem("single-$seed", "单抽券", "用于一次普通抽取", 30, StudyShopReward.SingleTicket, 1, 3),
        StudyShopItem("box-$seed", "神秘盒子", "随机开出夸夸值或碎片", 45, StudyShopReward.MysteryBox, 1, 2),
        StudyShopItem("universal-$seed", "万能蓝碎片", "补任意未满的普通收藏", 55, StudyShopReward.UniversalBlueFragment, 1, 2),
        StudyShopItem("safe-$seed", "今日安全抽券", "必定获得紫色碎片", 80, StudyShopReward.SafePurpleTicket, 1, 1),
        StudyShopItem("ten-$seed", "十连券", "用于一次十连抽", 240, StudyShopReward.TenTicket, 1, 1),
    )
    return rotating.shuffled(kotlin.random.Random(seed)).take(4)
}
